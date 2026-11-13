package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.DebugLog;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.ExternalBaselineSource;
import com.itzplana.sudoersemc.integration.ProjectECustomEmcFile;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.TierPricing;
import com.itzplana.sudoersemc.resolver.ResolverRegistry;
import com.itzplana.sudoersemc.resolver.VariantScanner;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.PriceDropDecision;
import com.itzplana.sudoersemc.safety.OwnershipManager;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.GoetyRitualReader;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeScanner;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.solver.EvaluatorRegistry;
import com.itzplana.sudoersemc.solver.EvaluationPolicy;
import com.itzplana.sudoersemc.solver.RecipeSolver;
import com.itzplana.sudoersemc.solver.SolveContext;
import com.itzplana.sudoersemc.solver.SolveResult;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.solver.SolveTrace;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.ScanDiagnostics;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime pipeline wiring together scanning, recipe solving, fallback pricing, the safety layer and
 * persistence.
 *
 * <p>A complete scan has two phases:</p>
 * <ol>
 *   <li>{@link #collect(MinecraftServer)} reads the item registry and recipe manager on the server thread,
 *       producing an immutable {@link PipelineSnapshot};</li>
 *   <li>{@link #solveAndApply(PipelineSnapshot)} does only pure computation and store writes, no longer
 *       accessing registries.</li>
 * </ol>
 *
 * <p>{@link com.itzplana.sudoersemc.task.ScanManager} spans multiple ticks during and between collection
 * phases, so this class provides {@link #stash(PipelineSnapshot)} and {@link #applyStashed()} as a pair:
 * the collection callback only stashes the snapshot, and the apply callback retrieves it and runs the
 * solve. Compared with letting an external lambda capture an array, an explicit stash field is easier to
 * test, diagnose and protect against double application.</p>
 *
 * <p><strong>Null semantics:</strong> a {@code null} {@code store} means dry-run mode — all counts and
 * reports are computed as usual, but no persistent entries are written and no quarantine record file is
 * written. Other collaborators being {@code null} means "that capability is off": drop protection and
 * creative-item protection use safe defaults, while fallback pricing and overflow protection are skipped
 * when absent. This lets unit tests verify the pipeline backbone without a full runtime environment.</p>
 *
 * <p><strong>Exception contract:</strong> neither {@link #collect(MinecraftServer)} nor
 * {@link #solveAndApply(PipelineSnapshot)} throws to the caller. Any failure is logged and returns an empty
 * snapshot or all-zero result, ensuring the server main loop and the {@code ScanManager} state machine are
 * never dragged down by a single scan.</p>
 */
public final class ScanPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanPipeline");

    /**
     * Maximum solve rounds for the fallback chain to propagate through.
     *
     * <p>Why 4: the first round seeds leaf items that can only be priced by TIER/TAG/RARITY/FALLBACK; after
     * each fallback re-seed a re-solve is needed so new seeds propagate upstream through recipes. Estimating
     * the worst case of solver {@code maxPasses=1}, 4 rounds cover the common mod chain of "fallback seed +
     * 3 intermediate layers" (e.g. ore → ingot → gear/block); with the default {@code maxPasses=20} it usually
     * converges in 2 to 3 rounds. The cap also bounds the cost of an anomalous recipe graph to a constant;
     * once reached, the current seeds are used for pricing directly.</p>
     */
    static final int MAX_SOLVE_ROUNDS = 4;

    /** How many observed entries NBT variant generation processes between cancellation checks. */
    static final int NBT_VARIANT_CANCEL_CHECK_INTERVAL = 64;

    /** How many candidates the apply loop processes between cancellation checks. */
    static final int APPLY_CANCEL_CHECK_INTERVAL = 64;

    private final GeneratedEntryStore store;
    private volatile QuarantinedDropsStore quarantinedDropsStore;
    private final ScanStateStore stateStore;
    private final ReportManager reportManager;
    private final LargeDropProtection dropProtection;
    private final CreativeItemProtection creativeProtection;
    private final OverflowPipeline overflowPipeline;
    private final FallbackPricing fallbackPricing;
    private final TierPricing tierPricing;
    private final SolveSettings solveSettings;
    private volatile boolean solveRecipesEnabled = true;
    private volatile boolean generateReportsEnabled = true;
    private volatile boolean nbtVariantsEnabled = true;
    private volatile int maxNbtVariantsPerItem = 100;
    private volatile boolean scanContainerRemainders = true;
    private volatile boolean creditContainerRemainders = true;
    private volatile boolean priceSecondaryOutputs = false;
    private volatile boolean preserveExternallyModifiedValues = true;
    private volatile Supplier<Predicate<ItemKey>> manualValueDetectorSupplier = () -> item -> false;
    /** Manual-value detector pre-resolved on the server thread; the worker thread only reads it and triggers no file I/O. */
    private volatile Predicate<ItemKey> preparedManualDetector;
    private volatile boolean manualDetectorPrepared;
    /** Resolver values pre-computed on the server thread; read-only during worker-thread solving. */
    private volatile Map<ItemKey, Long> preparedResolverValues = Map.of();
    /** External baseline source; unavailable by default, behaving exactly as in Phase 24. */
    private volatile ExternalBaselineSource externalBaselineSource = ExternalBaselineSource.unavailable();
    /** Third-party NBT material resolver registry; default empty registry rebuilds no ItemStack and parses no SNBT. */
    private volatile ResolverRegistry resolverRegistry = ResolverRegistry.defaults();

    private final AtomicReference<PipelineSnapshot> stashedSnapshot = new AtomicReference<>();
    private volatile RecipeSolver recipeSolver = new RecipeSolver();
    private final EvaluatorRegistry evaluatorRegistry = EvaluatorRegistry.defaults();

    /** Cooperative cancellation check; defaults to never cancel, with the scheduler injecting a shared token. */
    private volatile BooleanSupplier cancellationCheck = () -> false;
    /** Fingerprint sources for config and external baseline; default empty means a test/embedded environment has no extra inputs. */
    private volatile Supplier<List<String>> configFingerprintSupplier = List::of;
    private volatile Supplier<List<String>> baselineFingerprintSupplier = List::of;
    /** Most recent snapshot needed to write a partial report on cancellation or failure. */
    private volatile PipelineSnapshot lastSnapshotForReport;
    private volatile int lastSolverCacheHits;
    private volatile int lastSolverCacheMisses;
    private volatile int lastOwnershipExternalModifications;
    private volatile int lastOwnershipReleased;
    private volatile int lastResolverApplied;
    private final AtomicBoolean manualDetectorFailureWarned = new AtomicBoolean(false);

    private volatile EmcProvider emcProvider;
    private volatile OverflowScope overflowScope = OverflowScope.OWNED_ONLY;
    private volatile boolean allowProjectEFileRewrite;
    private volatile Path projectECustomEmcFile;
    private volatile int lastSolvedCount;
    private volatile int lastUnresolvedCount;
    private volatile int lastSolverRoundCount;
    private volatile long lastDurationMillis;
    private volatile ScanDiagnostics diagnostics = new ScanDiagnostics();
    private volatile boolean fullReportWritten;

    /**
     * Creates the pipeline.
     *
     * @param store              generated value store; {@code null} means dry-run mode, writing no entries
     * @param stateStore         scan state store; {@code null} means the incremental scan fingerprint is not updated
     * @param reportManager      diagnostic report manager; {@code null} means no reports are written
     * @param dropProtection     large-drop protection; {@code null} uses {@link LargeDropProtection.Settings#defaults()}
     * @param creativeProtection creative-item protection; {@code null} uses an enabled default with no exclusions
     * @param overflowPipeline   overflow protection pipeline; {@code null} skips overflow handling
     * @param fallbackPricing    fallback pricing chain; {@code null} skips fallback pricing
     * @param solveSettings      recipe solve parameters; {@code null} uses {@link SolveSettings#defaults()}
     */
    public ScanPipeline(
            @Nullable GeneratedEntryStore store,
            @Nullable ScanStateStore stateStore,
            @Nullable ReportManager reportManager,
            @Nullable LargeDropProtection dropProtection,
            @Nullable CreativeItemProtection creativeProtection,
            @Nullable OverflowPipeline overflowPipeline,
            @Nullable FallbackPricing fallbackPricing,
            @Nullable SolveSettings solveSettings
    ) {
        this.store = store;
        this.stateStore = stateStore;
        this.reportManager = reportManager;
        this.dropProtection = dropProtection == null
                ? new LargeDropProtection(LargeDropProtection.Settings.defaults())
                : dropProtection;
        this.creativeProtection = creativeProtection == null
                ? new CreativeItemProtection(new CreativeItemProtection.Settings(
                        true, Set.of(), Map.of(), Set.of()))
                : creativeProtection;
        this.overflowPipeline = overflowPipeline;
        this.fallbackPricing = fallbackPricing;
        this.tierPricing = fallbackPricing == null ? TierPricing.defaults() : fallbackPricing.tierPricing();
        this.solveSettings = solveSettings == null ? SolveSettings.defaults() : solveSettings;
    }

    /**
     * Registers the EMC query implementation for ProjectE (or another backend).
     *
     * <p>The collection phase uses this provider to decide whether an item already has EMC. Passing
     * {@code null} means no backend, in which case all items are treated as "no existing value" and the
     * pipeline can still work using only recipes and fallback pricing.</p>
     *
     * @param provider EMC backend query interface, may be {@code null}
     */
    public void setEmcProvider(@Nullable EmcProvider provider) {
        this.emcProvider = provider;
    }

    /**
     * Injects the quarantine record store.
     *
     * <p>Once injected, whenever large-drop protection decides to quarantine, the pipeline writes the old
     * value, proposed value, drop ratio, reason and timestamp to this store for admin approval via
     * {@code /sudoemc approve}. Passing {@code null} keeps only the original in-memory counting and logging.
     * Dry-run mode (generated entry store is {@code null}) never writes the quarantine file.</p>
     *
     * @param newStore quarantine record store, may be {@code null}
     */
    public void setQuarantinedDropsStore(@Nullable QuarantinedDropsStore newStore) {
        this.quarantinedDropsStore = newStore;
    }

    /**
     * Sets the overflow protection scope.
     *
     * <p>Production defaults to {@link OverflowScope#OWNED_ONLY}, allowing only entries SudoersEMC owns to
     * be rewritten. Passing {@code null} restores that default; the scope is never implicitly widened.</p>
     *
     * @param scope scope, may be {@code null}
     */
    public void setOverflowScope(@Nullable OverflowScope scope) {
        this.overflowScope = scope == null ? OverflowScope.OWNED_ONLY : scope;
    }

    /**
     * Sets whether rewriting ProjectE's {@code custom_emc.json} is allowed.
     *
     * <p>Default {@code false}. Actual file writes occur only when {@link OverflowScope#ENTIRE_CUSTOM_EMC}
     * scope is also enabled; this is a second safeguard against accidentally modifying admin manual config.</p>
     *
     * @param enabled whether rewriting the ProjectE file is allowed
     */
    public void setProjectEFileRewriteEnabled(boolean enabled) {
        this.allowProjectEFileRewrite = enabled;
    }

    /**
     * Sets whether recipe solving is enabled.
     *
     * <p>When off, the pipeline no longer runs {@link RecipeSolver} and uses only the snapshot's existing
     * values and the fallback pricing chain. This switch is read at the start of each scan and takes effect
     * immediately after rebuilding the pipeline.</p>
     *
     * @param enabled {@code true} to enable recipe solving; enabled by default
     */
    public void setSolveRecipesEnabled(boolean enabled) {
        this.solveRecipesEnabled = enabled;
    }

    /**
     * Sets whether the recipe scan phase reads container remainders (buckets, empty bottles, etc.).
     */
    public void setScanContainerRemaindersEnabled(boolean enabled) {
        this.scanContainerRemainders = enabled;
    }

    /**
     * Sets whether container remainders may credit recipe cost.
     *
     * <p>When off, scanning may still record remainders (for diagnostics/fingerprint), but the solver does
     * not subtract them from the net cost.</p>
     */
    public void setCreditContainerRemaindersEnabled(boolean enabled) {
        if (this.creditContainerRemainders != enabled) {
            this.creditContainerRemainders = enabled;
            rebuildRecipeSolver();
        }
    }

    /**
     * Sets whether multi-output recipes publish a unit value for byproducts.
     *
     * <p>Default off: the whole recipe's net cost is borne by the primary output, so byproducts do not
     * dilute the main item's price. When enabled, cost is split evenly across all output units, and the same
     * item still publishes only one value per solve.</p>
     */
    public void setPriceSecondaryOutputsEnabled(boolean enabled) {
        if (this.priceSecondaryOutputs != enabled) {
            this.priceSecondaryOutputs = enabled;
            rebuildRecipeSolver();
        }
    }

    /**
     * Sets the external/manual value protection policy.
     *
     * <p>Default {@code true}: when an owned entry is detected as externally modified (a ProjectE manual
     * value, another mod, or an admin writing a value directly), ownership is released and the external
     * value kept; {@code false} falls back to Phase 14's "owned entries always refresh" semantics. This
     * switch only affects detection; it does not let a protected value re-enter automatic pricing.</p>
     */
    public void setPreserveExternallyModifiedValues(boolean enabled) {
        this.preserveExternallyModifiedValues = enabled;
    }

    /**
     * Injects the manual-value detector source. The source is called once per apply phase, and the returned
     * detector is reused for all items in that round. Passing {@code null} restores the default "no manual
     * values" behaviour.
     */
    public void setManualValueDetectorSupplier(
            @Nullable Supplier<Predicate<ItemKey>> supplier) {
        this.manualValueDetectorSupplier = supplier == null
                ? () -> item -> false
                : supplier;
        this.preparedManualDetector = null;
        this.manualDetectorPrepared = false;
    }

    /**
     * Convenience overload: injects a manual-value detector directly.
     */
    public void setManualValueDetector(@Nullable Predicate<ItemKey> detector) {
        Predicate<ItemKey> effective = detector == null ? item -> false : detector;
        setManualValueDetectorSupplier(() -> effective);
    }

    /**
     * Injects the external baseline source. Passing {@code null} restores the unavailable source, behaving
     * exactly as in Phase 24.
     *
     * @param source external baseline source, may be {@code null}
     */
    public void setExternalBaselineSource(@Nullable ExternalBaselineSource source) {
        this.externalBaselineSource = source == null
                ? ExternalBaselineSource.unavailable()
                : source;
    }

    /**
     * Injects the NBT material resolver registry. Passing {@code null} or an empty registry means no
     * {@link ItemStack} is rebuilt for any item.
     *
     * @param registry resolver registry, may be {@code null}
     */
    public void setResolverRegistry(@Nullable ResolverRegistry registry) {
        this.resolverRegistry = registry == null ? ResolverRegistry.defaults() : registry;
        this.preparedResolverValues = Map.of();
    }

    /**
     * Rebuilds the solver with the current evaluation policy. Rebuilding discards the evaluation cache but
     * keeps the accumulated hit/miss counters.
     */
    private synchronized void rebuildRecipeSolver() {
        recipeSolver = new RecipeSolver(new EvaluationPolicy(
                EvaluationPolicy.DEFAULT_MINIMUM_EMC,
                creditContainerRemainders,
                priceSecondaryOutputs));
    }

    /**
     * Injects the diagnostics sampler shared with {@link com.itzplana.sudoersemc.task.ScanManager}.
     *
     * <p>Once injected, reports can read cross-tick elapsed time, P95 and peak heap memory for the whole
     * scan; passing {@code null} restores a sampler created internally by the pipeline. Resetting via
     * {@link ScanDiagnostics#reset()} during collection is the scan scheduler's responsibility; the
     * pipeline only reads.</p>
     *
     * @param newDiagnostics diagnostics sampler, may be {@code null}
     */
    public void setDiagnostics(@Nullable ScanDiagnostics newDiagnostics) {
        this.diagnostics = newDiagnostics == null ? new ScanDiagnostics() : newDiagnostics;
    }

    /**
     * @return the current diagnostics sampler; the default instance also passively samples heap memory at report time
     */
    public ScanDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Injects the cooperative cancellation check.
     *
     * <p>The check is called during item collection, recipe scanning, NBT variant generation, solve rounds
     * and the apply loop. Passing {@code null} restores "never cancel".</p>
     *
     * @param check cancellation check, may be {@code null}
     */
    public void setCancellationCheck(@Nullable BooleanSupplier check) {
        this.cancellationCheck = check == null ? () -> false : check;
    }

    /**
     * Injects the config fingerprint source. The strings it returns should be stable and sortable; on a
     * read failure the pipeline degrades to a fixed error marker and does not block the scan.
     *
     * @param supplier config entry source, may be {@code null}
     */
    public void setConfigFingerprintSupplier(@Nullable Supplier<List<String>> supplier) {
        this.configFingerprintSupplier = supplier == null ? List::of : supplier;
    }

    /**
     * Injects the external baseline fingerprint source (e.g. ProjectE's manual EMC file).
     *
     * @param supplier baseline entry source, may be {@code null}
     */
    public void setBaselineFingerprintSupplier(@Nullable Supplier<List<String>> supplier) {
        this.baselineFingerprintSupplier = supplier == null ? List::of : supplier;
    }

    /**
     * @return whether cooperative cancellation has been requested
     */
    public boolean isCancelled() {
        BooleanSupplier check = cancellationCheck;
        try {
            return check.getAsBoolean();
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 取消检查抛出异常，按未取消继续", failure);
            return false;
        }
    }

    /**
     * Collects the current input fingerprint for comparison with persisted state at server startup.
     *
     * <p>This method only reads the item registry, item tags and recipe manager; it does not query the EMC
     * backend, solve, or write stores. Item entries are deduplicated by key and include rarity; recipe entries
     * include a full content digest; config and external baseline are read via the injected sources. Any
     * exception returns {@code null}, and the caller should degrade to "scan needed".</p>
     *
     * @param server the current server, may be {@code null}
     * @return the current input fingerprint; {@code null} when it cannot be computed
     */
    @Nullable
    public ScanFingerprint collectFingerprint(@Nullable MinecraftServer server) {
        if (server == null) {
            return null;
        }
        // The startup fingerprint and the baseline fingerprint of the subsequent scan apply phase must come
        // from the same data, otherwise every startup would be misjudged as a "baseline channel change" and
        // trigger a pointless rescan.
        prepareExternalBaseline(server);
        try {
            ItemScanner identityScanner = new ItemScanner(new ItemScanner.EmcQuery() {
                @Override
                public boolean hasValue(ItemStack stack) {
                    return false;
                }

                @Override
                public long getValue(ItemStack stack) {
                    return 0L;
                }
            }, Set.of(), Set.of());
            List<ItemSnapshot> items = identityScanner.scan(this::isCancelled);
            RecipeScanner recipeScanner = RecipeScanner.withGoety(scanContainerRemainders)
                    .orElseGet(() -> new RecipeScanner(
                            GoetyRitualReader.unavailable(), scanContainerRemainders));
            List<RecipeSnapshot> recipes =
                    recipeScanner.scan(server.getRecipeManager(), this::isCancelled).recipes();
            return fingerprintFor(items, recipes,
                    safeFingerprintEntries(configFingerprintSupplier, "config"),
                    safeFingerprintEntries(baselineFingerprintSupplier, "baseline"));
        } catch (Throwable failure) {
            LOGGER.warn("SudoersEMC 无法计算当前扫描指纹，退化为需要扫描", failure);
            return null;
        }
    }

    /**
     * Builds the five-channel fingerprint from item, recipe, config and baseline inputs.
     *
     * <p>All collections go into a {@link TreeSet} before being handed to {@link ScanFingerprint} for sorted
     * hashing, so the result is unaffected by HashMap iteration order. The item channel contains registry id
     * and rarity; the recipe channel contains the recipe's full content digest.</p>
     *
     * @param items           item snapshots, may be empty
     * @param recipes         recipe snapshots, may be empty
     * @param configEntries   config entries, may be {@code null}
     * @param baselineEntries external baseline entries, may be {@code null}
     * @return the five-channel fingerprint
     */
    static ScanFingerprint fingerprintFor(List<ItemSnapshot> items,
                                          List<RecipeSnapshot> recipes,
                                          Iterable<String> configEntries,
                                          Iterable<String> baselineEntries) {
        Set<String> itemEntries = new TreeSet<>();
        Set<String> tagEntries = new TreeSet<>();
        if (items != null) {
            for (ItemSnapshot item : items) {
                if (item == null) {
                    continue;
                }
                itemEntries.add(item.registryId() + "|" + item.rarity().name());
                tagEntries.addAll(item.tagPaths());
            }
        }

        Set<String> recipeEntries = new TreeSet<>();
        if (recipes != null) {
            for (RecipeSnapshot recipe : recipes) {
                if (recipe != null) {
                    recipeEntries.add(recipeFingerprintEntry(recipe));
                }
            }
        }

        return ScanFingerprint.of(itemEntries, recipeEntries, tagEntries,
                sortedEntries(configEntries), sortedEntries(baselineEntries));
    }

    /**
     * Generates a stable content digest for one recipe. Field order is fixed and attributes are sorted by key.
     */
    private static String recipeFingerprintEntry(RecipeSnapshot recipe) {
        StringBuilder builder = new StringBuilder(128);
        builder.append(recipe.id()).append('|').append(recipe.kind().name());
        builder.append("|in:");
        for (RecipeInput input : recipe.inputs()) {
            builder.append('[');
            List<String> candidates = new ArrayList<>(input.option().candidates().size());
            for (ItemKey candidate : input.option().candidates()) {
                candidates.add(candidate.toString());
            }
            Collections.sort(candidates);
            builder.append(String.join(",", candidates));
            builder.append('x').append(input.count()).append(']');
        }
        builder.append("|out:");
        appendOutputs(builder, recipe.outputs());
        builder.append("|rem:");
        appendOutputs(builder, recipe.containerRemainders());
        builder.append("|catalyst:").append(recipe.catalyst());
        builder.append("|attrs:");
        Map<String, String> attributes = recipe.attributes();
        for (Map.Entry<String, String> entry : new TreeMap<>(attributes).entrySet()) {
            builder.append(entry.getKey()).append('=').append(entry.getValue()).append(';');
        }
        return builder.toString();
    }

    private static void appendOutputs(StringBuilder builder, List<RecipeOutput> outputs) {
        for (RecipeOutput output : outputs) {
            builder.append('[').append(output.item())
                    .append('x').append(output.count())
                    .append(':').append(output.outputNbt())
                    .append(']');
        }
    }

    private static List<String> sortedEntries(Iterable<String> entries) {
        Set<String> sorted = new TreeSet<>();
        if (entries != null) {
            for (String entry : entries) {
                if (entry != null) {
                    sorted.add(entry);
                }
            }
        }
        return List.copyOf(sorted);
    }

    private List<String> safeFingerprintEntries(Supplier<List<String>> supplier, String channel) {
        try {
            return sortedEntries(supplier.get());
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 读取 {} 指纹输入失败，使用固定错误标记", channel, failure);
            return List.of("fingerprint-error:" + channel);
        }
    }

    /**
     * Sets whether diagnostic reports are written.
     *
     * <p>When off, {@code last_report.txt} is no longer updated, but this round's counts, state store and
     * persistence behaviour are unchanged. This switch is read at the start of each scan and takes effect
     * immediately after rebuilding the pipeline.</p>
     *
     * @param enabled {@code true} to write diagnostic reports; enabled by default
     */
    public void setGenerateReportsEnabled(boolean enabled) {
        this.generateReportsEnabled = enabled;
    }

    /**
     * Sets whether to scan and generate NBT variant snapshots.
     *
     * <p>When off, {@link #collect(MinecraftServer)} generates no variants at all; base item snapshots are
     * unaffected. This switch is read at the start of each collection and takes effect immediately after
     * rebuilding the pipeline.</p>
     *
     * @param enabled {@code true} to enable NBT variant scanning; enabled by default
     */
    public void setNbtVariantsEnabled(boolean enabled) {
        this.nbtVariantsEnabled = enabled;
    }

    /**
     * Sets the maximum NBT variants kept per item.
     *
     * <p>The value is clamped to {@code [1, }{@link VariantScanner#HARD_VARIANT_CEILING}{@code ]} to prevent
     * an anomalous configuration from letting the variant count run away. This cap is read at the start of
     * each collection.</p>
     *
     * @param maxVariantsPerItem maximum variants kept per item
     */
    public void setMaxNbtVariantsPerItem(int maxVariantsPerItem) {
        this.maxNbtVariantsPerItem = Math.max(
                1, Math.min(maxVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));
    }

    /**
     * Overrides the ProjectE manual EMC file path, mainly for tests or embedded scenarios.
     *
     * <p>Passing {@code null} restores the production path ({@code config/ProjectE/custom_emc.json}).</p>
     *
     * @param file custom path, may be {@code null}
     */
    public void setProjectECustomEmcFile(@Nullable Path file) {
        this.projectECustomEmcFile = file;
    }

    /**
     * Begins a new scan cycle, clearing report state left over from the previous round.
     *
     * <p>The scheduler calls this on {@code requestScan()}. This way, even if a new scan is cancelled before
     * its first collection tick, the partial report will not wrongly reuse the previous scan's full-report
     * marker or snapshot.</p>
     */
    public void beginScanCycle() {
        fullReportWritten = false;
        stashedSnapshot.set(null);
        lastSnapshotForReport = null;
    }

    /**
     * Safely prepares the external baseline source. Any exception only logs a warning; this round continues
     * with an empty baseline.
     */
    private void prepareExternalBaseline(@Nullable MinecraftServer server) {
        ExternalBaselineSource source = externalBaselineSource;
        try {
            source.prepare(server);
        } catch (Throwable failure) {
            LOGGER.warn("SudoersEMC 准备外部基线失败，本轮按空基线处理", failure);
        }
    }

    /**
     * Begins an incremental collection session.
     *
     * <p><strong>Must be called on the server thread.</strong> The session advances through "item batches →
     * recipe batches → NBT variant aggregation → existing EMC reads"; each batch processes only the given
     * budget, and {@link CollectSession#finish()} returns an immutable {@link PipelineSnapshot} on completion.
     * Items and recipes are paged after sorting by {@link ResourceLocation}, keeping the cross-tick cursor
     * stable.</p>
     *
     * @param server the current server instance, may be {@code null}
     * @return the incremental collection session; an already-completed empty session when the server is not ready
     */
    public CollectSession beginCollect(@Nullable MinecraftServer server) {
        beginScanCycle();
        if (server == null) {
            LOGGER.warn("SudoersEMC 扫描采集被调用时服务器尚未就绪，返回空快照");
            // An empty session does not go through ScanCollector.finishInternal, so the manual-value detector
            // must be frozen here too; otherwise the first scan's solveOnly would parse ProjectEManualValues on
            // the worker thread and access the Forge registry.
            prepareSolveInputs(List.of());
            return ScanCollector.completed(PipelineSnapshot.empty());
        }
        prepareExternalBaseline(server);
        return ScanCollector.begin(this, server);
    }

    /**
     * Collects all data needed for one scan.
     *
     * <p><strong>Must be called on the server thread.</strong> This method is a compatibility wrapper around
     * {@link #beginCollect(MinecraftServer)}: it loops {@code step(Integer.MAX_VALUE)} and retrieves the
     * snapshot on completion, so call sites that do not enable sharded scanning keep one-shot semantics.</p>
     *
     * @param server the current server instance, may be {@code null}
     * @return the collection result; an empty snapshot on any failure or when the server is not ready
     */
    public PipelineSnapshot collect(@Nullable MinecraftServer server) {
        try {
            CollectSession session = beginCollect(server);
            while (!session.isDone()) {
                session.step(Integer.MAX_VALUE);
            }
            return session.finish();
        } catch (Throwable failure) {
            LOGGER.error("SudoersEMC 扫描采集失败，本轮按空快照处理", failure);
            return PipelineSnapshot.empty();
        }
    }

    /**
     * Incremental collection session.
     *
     * <p>{@link #itemCount()} and {@link #recipeCount()} are the total registry entries/recipes this
     * collection plans to process, for progress display; {@link #processedItems()} and
     * {@link #processedRecipes()} are the counts advanced so far. All methods must be called on the server
     * thread.</p>
     */
    public interface CollectSession {

        /**
         * @return whether collection is complete (including partial completion after cancellation)
         */
        boolean isDone();

        /**
         * @return the number of item registry entries planned to be checked
         */
        int itemCount();

        /**
         * @return the number of recipes planned to be checked
         */
        int recipeCount();

        /**
         * @return the number of item registry entries already checked
         */
        int processedItems();

        /**
         * @return the number of recipes already checked
         */
        int processedRecipes();

        /**
         * Advances the current phase within a budget.
         *
         * @param budget maximum work to process this call; values below 1 are treated as 1
         */
        void step(int budget);

        /**
         * Finishes the remaining work and returns an immutable snapshot. On a cancellation hit it returns the
         * partial snapshot already collected.
         *
         * @return the collection snapshot, never {@code null}
         */
        PipelineSnapshot finish();
    }

    /**
     * Creates the production item scanner; EMC queries go through the backend injected into this pipeline.
     */
    ItemScanner newItemScanner() {
        return new ItemScanner(emcQuery(), Set.of(), Set.of());
    }

    /**
     * Creates the production recipe scanner, deciding whether to read container remainders and Goety rituals
     * per the current configuration.
     */
    RecipeScanner newRecipeScanner() {
        return RecipeScanner.withGoety(scanContainerRemainders)
                .orElseGet(() -> new RecipeScanner(
                        GoetyRitualReader.unavailable(), scanContainerRemainders));
    }

    /**
     * @return the cooperative cancellation check, reused by collection sessions at batch boundaries
     */
    BooleanSupplier cancellationPredicate() {
        return this::isCancelled;
    }

    /**
     * @return whether NBT variant scanning is enabled
     */
    boolean nbtVariantsEnabled() {
        return nbtVariantsEnabled;
    }

    /**
     * @return the maximum NBT variants kept per item
     */
    int maxNbtVariantsPerItem() {
        return maxNbtVariantsPerItem;
    }

    /**
     * Prepares the inputs needed for pure solving on the server thread: resolves the manual-value detector
     * once and, when resolvers exist, pre-computes resolver values for all items. The worker thread then only
     * reads these immutable results and no longer accesses files or registries.
     */
    void prepareSolveInputs(@Nullable List<ItemSnapshot> items) {
        // Manual-value detection must complete on the server thread and be frozen into a pure set: the #tag
        // branch of ProjectEManualValues accesses ForgeRegistries.ITEMS and must never run on the
        // sudoersemc-solver thread. The frozen scope covers this round's snapshot items and existing store
        // entries, matching the two sets the worker thread actually queries.
        Predicate<ItemKey> detector = resolveManualDetector();
        if (detector == null) {
            this.preparedManualDetector = null;
        } else {
            Set<ItemKey> manualItems = new HashSet<>();
            if (items != null) {
                for (ItemSnapshot item : items) {
                    if (item != null && isManuallySet(detector, item.key())) {
                        manualItems.add(item.key());
                    }
                }
            }
            GeneratedEntryStore currentStore = store;
            if (currentStore != null) {
                try {
                    for (ItemKey stored : currentStore.snapshotAll().keySet()) {
                        if (stored != null && isManuallySet(detector, stored)) {
                            manualItems.add(stored);
                        }
                    }
                } catch (RuntimeException failure) {
                    LOGGER.warn("SudoersEMC 读取归属快照以固化手工值判定失败，本轮按已扫描物品判定", failure);
                }
            }
            Set<ItemKey> frozen = Set.copyOf(manualItems);
            this.preparedManualDetector = frozen::contains;
        }
        this.manualDetectorPrepared = true;

        ResolverRegistry registry = resolverRegistry;
        if (registry == null || registry.size() == 0 || items == null || items.isEmpty()) {
            this.preparedResolverValues = Map.of();
            return;
        }
        Map<ItemKey, Long> values = new TreeMap<>();
        for (ItemSnapshot item : items) {
            if (item != null) {
                values.put(item.key(), resolveWithRegistryDirect(item.key()));
            }
        }
        this.preparedResolverValues = Map.copyOf(values);
    }

    /**
     * Builds the EMC backend query; a missing backend or a single failed query degrades to "no existing value".
     */
    private ItemScanner.EmcQuery emcQuery() {
        return new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(ItemStack stack) {
                EmcProvider provider = emcProvider;
                if (provider == null) {
                    return false;
                }
                try {
                    return provider.isAvailable() && provider.hasValue(stack);
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.debug("EMC 后端查询物品是否有值失败，按无值处理", failure);
                    return false;
                }
            }

            @Override
            public long getValue(ItemStack stack) {
                EmcProvider provider = emcProvider;
                if (provider == null) {
                    return 0L;
                }
                try {
                    if (!provider.isAvailable()) {
                        return 0L;
                    }
                    return Math.max(0L, provider.getValue(stack));
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.debug("EMC 后端读取物品数值失败，按 0 处理", failure);
                    return 0L;
                }
            }
        };
    }

    /**
     * Builds the partial snapshot on cancellation: items/recipes keep the collected portion, and existing
     * values are re-derived only from the current item list.
     */
    private static PipelineSnapshot partialSnapshot(List<ItemSnapshot> items,
                                                    List<RecipeSnapshot> recipes) {
        Map<ItemKey, Long> existingValues = new TreeMap<>();
        for (ItemSnapshot item : items) {
            if (item.hasExistingEmc()) {
                existingValues.put(item.key(), item.existingValue());
            }
        }
        return new PipelineSnapshot(items, recipes, existingValues, 0, 0, 0, List.of());
    }

    /**
     * Generates NBT variant snapshots from SNBT observed in recipe outputs.
     *
     * <p>Processing order is fixed: first aggregate all {@link RecipeOutput#outputNbt()} by item id, then
     * parse the SNBT back into {@link CompoundTag} and hand it to {@link VariantScanner} to normalise,
     * deduplicate and cap. Only items already present in the base item snapshots get variants, so ignore
     * rules and registry-missing items are not re-introduced by variant scanning.</p>
     *
     * <p>A variant snapshot's {@code hasExistingEmc} is fixed to {@code false} and {@code existingValue} to
     * {@code 0}; tag paths and rarity are inherited from the base item. Results are sorted by {@link ItemKey}
     * (item id, then variantHash) so identical input yields a stable output order; tags that cannot be parsed
     * or normalised are skipped.</p>
     *
     * @param baseItems             base item snapshots, keeping the scanner's returned order
     * @param recipes               recipes available this round
     * @param enabled               whether variant scanning is enabled
     * @param maxNbtVariantsPerItem maximum variants kept per item
     * @return immutable variant snapshot list; empty when disabled, no observed variants, or all unparseable
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem) {
        return collectVariantSnapshots(baseItems, recipes, enabled, maxNbtVariantsPerItem,
                () -> false);
    }

    /**
     * NBT variant generation with a cooperative cancellation check.
     *
     * <p>Checks cancellation every {@value #NBT_VARIANT_CANCEL_CHECK_INTERVAL} observed entries. On
     * cancellation it returns the already-generated and sorted variant subset; the sequence is immutable
     * and keeps the same ordering rules as the full result.</p>
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem,
                                                      BooleanSupplier cancellation) {
        if (!enabled || baseItems == null || baseItems.isEmpty()
                || recipes == null || recipes.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxNbtVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));

        Map<ResourceLocation, List<String>> observedByItem = new TreeMap<>();
        int scannedRecipes = 0;
        for (RecipeSnapshot recipe : recipes) {
            if (scannedRecipes++ % NBT_VARIANT_CANCEL_CHECK_INTERVAL == 0
                    && cancellation.getAsBoolean()) {
                return List.of();
            }
            for (RecipeOutput output : recipe.outputs()) {
                String outputNbt = output.outputNbt();
                if (outputNbt == null || outputNbt.isBlank()) {
                    continue;
                }
                observedByItem
                        .computeIfAbsent(output.item().itemId(), id -> new ArrayList<>())
                        .add(outputNbt);
            }
        }
        if (observedByItem.isEmpty()) {
            return List.of();
        }

        Map<ResourceLocation, ItemSnapshot> baseByItem = new LinkedHashMap<>();
        for (ItemSnapshot item : baseItems) {
            if (!item.key().hasVariant()) {
                baseByItem.putIfAbsent(item.registryId(), item);
            }
        }

        VariantScanner variantScanner = new VariantScanner();
        Set<ItemKey> seen = new HashSet<>();
        List<ItemSnapshot> variants = new ArrayList<>();
        int processedEntries = 0;
        for (Map.Entry<ResourceLocation, List<String>> entry : observedByItem.entrySet()) {
            if (processedEntries++ % NBT_VARIANT_CANCEL_CHECK_INTERVAL == 0
                    && cancellation.getAsBoolean()) {
                break;
            }
            ItemSnapshot base = baseByItem.get(entry.getKey());
            if (base == null) {
                // Do not generate variants when the base item was not scanned (ignored or unregistered), to avoid bypassing exclusion rules.
                continue;
            }
            List<ItemKey> variantKeys = variantKeysFor(
                    variantScanner, entry.getKey(), entry.getValue(), limit, processedEntries);
            for (ItemKey variantKey : variantKeys) {
                if (!variantKey.hasVariant() || !seen.add(variantKey)) {
                    continue;
                }
                variants.add(ItemSnapshot.of(
                        variantKey, false, 0L, base.tagPaths(), base.rarity()));
            }
        }

        if (variants.isEmpty()) {
            return List.of();
        }
        variants.sort(Comparator.comparing(ItemSnapshot::key));
        if (DebugLog.isEnabled()) {
            DebugLog.log("variant", "collected variants=" + variants.size()
                    + " observedItems=" + observedByItem.size());
        }
        return List.copyOf(variants);
    }

    /**
     * Generates normalised variant keys for one item's observed SNBT.
     *
     * <p>This method is the single implementation shared by full and sharded collection: it parses SNBT, calls
     * {@link VariantScanner} to deduplicate, and writes sampled debug logs. Whether the base item may generate
     * variants is decided by the caller.</p>
     *
     * @param variantScanner the reused variant scanner
     * @param registryId     base item id, may be {@code null}
     * @param observedNbt    SNBT list observed in recipe outputs, may be {@code null}
     * @param limit          maximum variants kept per item
     * @param processedIndex index of processed observed entries, for debug sampling only
     * @return the variant key list; empty when unparseable or on failure
     */
    static List<ItemKey> variantKeysFor(VariantScanner variantScanner,
                                        @Nullable ResourceLocation registryId,
                                        @Nullable List<String> observedNbt,
                                        int limit,
                                        long processedIndex) {
        if (variantScanner == null || registryId == null
                || observedNbt == null || observedNbt.isEmpty()) {
            return List.of();
        }
        Item item = BuiltInRegistries.ITEM.get(registryId);
        if (item == null || item == Items.AIR) {
            return List.of();
        }

        List<CompoundTag> observedTags = new ArrayList<>(observedNbt.size());
        for (String snbt : observedNbt) {
            try {
                observedTags.add(TagParser.parseTag(snbt));
            } catch (Exception failure) {
                LOGGER.debug("无法解析配方输出 SNBT，跳过该观测变体：{}", snbt, failure);
            }
        }
        if (observedTags.isEmpty()) {
            return List.of();
        }

        List<ItemKey> variantKeys;
        try {
            variantKeys = variantScanner.scan(new ItemStack(item), observedTags, limit);
        } catch (RuntimeException failure) {
            LOGGER.debug("生成物品 {} 的 NBT 变体失败，跳过", registryId, failure);
            return List.of();
        }
        if (DebugLog.isEnabled()) {
            final int observed = observedNbt.size();
            final int produced = variantKeys.size();
            DebugLog.headOrSampled("variant", processedIndex, 20L, 500L,
                    () -> "item=" + registryId + " observed=" + observed
                            + " variants=" + produced);
        }
        return variantKeys;
    }

    /**
     * Stashes the collection result, awaiting retrieval by the apply phase.
     *
     * <p>This method is one half of the "stash + applyStashed" wiring, serving
     * {@link com.itzplana.sudoersemc.task.ScanManager}'s cross-tick phase split.</p>
     *
     * @param snapshot the collection result, may be {@code null}
     */
    public void stash(@Nullable PipelineSnapshot snapshot) {
        fullReportWritten = false;
        stashedSnapshot.set(snapshot);
    }

    /**
     * Retrieves and applies the most recently stashed snapshot.
     *
     * <p>The snapshot is cleared immediately after retrieval, so repeated calls do not write the same batch of
     * results twice. With no stashed snapshot it returns an all-zero result and logs a warning, never throwing.</p>
     *
     * @return this application's result; {@link PipelineResult#zeroed(long)} when there is no stashed data
     */
    public PipelineResult applyStashed() {
        PipelineSnapshot snapshot = stashedSnapshot.getAndSet(null);
        if (snapshot == null) {
            LOGGER.warn("SudoersEMC 应用阶段没有可用的暂存快照，跳过本轮写入");
            return PipelineResult.zeroed(0L);
        }
        return solveAndApply(snapshot);
    }

    /**
     * Separates items owned by SudoersEMC in the store out of "already has EMC".
     *
     * <p>Returned item snapshots keep input order; owned entries are rebuilt without an existing value, with tags
     * and rarity preserved as-is, so subsequent solving and fallback pricing can recompute them. Non-owned entries
     * are kept as-is and their existing values continue into {@code existingValues}, ensuring manual or external
     * backend values are not overwritten.</p>
     *
     * @param items item snapshots scanned this round, must not be {@code null}
     * @param owned ownership predicate; returning {@code true} means the item is owned by SudoersEMC
     * @return the separated items, existing values and refresh count
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned) {
        return separateOwnedEntries(items, owned, Map.of());
    }

    /**
     * Ownership separation with "externally preserved values".
     *
     * <p>Non-owned entries prefer the existing value observed during collection; if the backend did not report
     * that value (e.g. ProjectE is not installed, but a manual file or ownership migration requires keeping the
     * old value), the value in {@code preservedExternalValues} is used as a seed. Owned entries are still rebuilt
     * without an existing value, keeping Phase 14 refresh semantics.</p>
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned,
                                                    Map<ItemKey, Long> preservedExternalValues) {
        Map<ItemKey, Long> preserved = preservedExternalValues == null
                ? Map.of()
                : preservedExternalValues;
        List<ItemSnapshot> separated = new ArrayList<>(items.size());
        Map<ItemKey, Long> existingValues = new TreeMap<>();
        int refreshedOwned = 0;
        for (ItemSnapshot item : items) {
            ItemKey key = item.key();
            if (owned.test(key)) {
                if (item.hasExistingEmc() || item.existingValue() > 0L) {
                    refreshedOwned++;
                }
                separated.add(ItemSnapshot.of(key, false, 0L, item.tagPaths(), item.rarity()));
                continue;
            }
            separated.add(item);
            if (item.hasExistingEmc()) {
                existingValues.put(key, item.existingValue());
            } else {
                Long preservedValue = preserved.get(key);
                if (preservedValue != null && preservedValue > 0L) {
                    existingValues.put(key, preservedValue);
                }
            }
        }
        // Entries whose ownership was successfully released keep their last-known value as a seed even if not in
        // this round's item snapshots, to avoid being re-taken by recipes or fallback pricing next round.
        for (Map.Entry<ItemKey, Long> entry : preserved.entrySet()) {
            ItemKey key = entry.getKey();
            Long value = entry.getValue();
            if (key != null && value != null && value > 0L) {
                existingValues.putIfAbsent(key, value);
            }
        }
        return new OwnershipSeparation(separated, existingValues, refreshedOwned);
    }

    /**
     * Performs ownership separation in the apply phase.
     *
     * <p>Separation happens in the apply phase rather than collection: collection only faithfully records the
     * state observed from the backend, while whether an item is owned by SudoersEMC is decided by the persistent
     * store. This way, even if a caller directly constructs a snapshot with existing values, owned entries are
     * correctly refreshed before writing; dry-run mode has no store and keeps the original semantics.</p>
     *
     * <p>External modifications are detected before refreshing: when an owned entry's persisted value differs
     * from the backend's current value, or the item is overridden by the ProjectE manual file, ownership is
     * released by default and the external value kept. Owned entries not externally modified still refresh per
     * Phase 14 semantics.</p>
     *
     * @param snapshot the collection result, must not be {@code null}
     * @return the separated snapshot and external-modification count
     */
    private OwnershipApplication applyOwnershipSeparation(PipelineSnapshot snapshot) {
        if (store == null) {
            return new OwnershipApplication(snapshot, 0, 0, Set.of());
        }
        OwnershipDetection detection = detectExternalModificationsOnly(snapshot);
        int released = releaseDetectedOwnership(detection.detected(), detection.protectedItems());
        OwnershipSeparation separation = separateOwnedEntries(
                snapshot.items(),
                key -> !detection.protectedItems().contains(key) && store.isOwned(key),
                detection.preservedValues());
        boolean changed = separation.refreshedOwned() > 0
                || !separation.existingValues().equals(snapshot.existingValues());
        if (!changed) {
            return new OwnershipApplication(snapshot, detection.detected(), released,
                    detection.protectedItems());
        }
        if (separation.refreshedOwned() > 0) {
            LOGGER.debug("SudoersEMC 归属分离刷新了 {} 个自有条目，本轮将重新求解其数值",
                    separation.refreshedOwned());
        }
        PipelineSnapshot updated = new PipelineSnapshot(
                separation.items(),
                snapshot.recipes(),
                separation.existingValues(),
                snapshot.unsupportedRecipes(),
                snapshot.invalidRecipes(),
                snapshot.failedRecipes(),
                snapshot.failedRecipeDetails());
        return new OwnershipApplication(updated, detection.detected(), released,
                detection.protectedItems());
    }

    /**
     * Pure ownership separation: same result as {@link #applyOwnershipSeparation(PipelineSnapshot)} but releases
     * no ownership. Used by the worker thread's {@code solveOnly}; the release action is performed by the server
     * thread's apply phase.
     */
    private OwnershipApplication applyOwnershipSeparationPure(PipelineSnapshot snapshot) {
        if (store == null) {
            return new OwnershipApplication(snapshot, 0, 0, Set.of());
        }
        OwnershipDetection detection = detectExternalModificationsOnly(snapshot);
        OwnershipSeparation separation = separateOwnedEntries(
                snapshot.items(),
                key -> !detection.protectedItems().contains(key) && store.isOwned(key),
                detection.preservedValues());
        boolean changed = separation.refreshedOwned() > 0
                || !separation.existingValues().equals(snapshot.existingValues());
        if (!changed) {
            return new OwnershipApplication(snapshot, detection.detected(), 0,
                    detection.protectedItems());
        }
        if (separation.refreshedOwned() > 0) {
            LOGGER.debug("SudoersEMC 归属分离刷新了 {} 个自有条目，本轮将重新求解其数值",
                    separation.refreshedOwned());
        }
        PipelineSnapshot updated = new PipelineSnapshot(
                separation.items(),
                snapshot.recipes(),
                separation.existingValues(),
                snapshot.unsupportedRecipes(),
                snapshot.invalidRecipes(),
                snapshot.failedRecipes(),
                snapshot.failedRecipeDetails());
        return new OwnershipApplication(updated, detection.detected(), 0,
                detection.protectedItems());
    }

    /**
     * Read-only detection of external modifications to owned entries, preserving external or last-known values.
     *
     * <p>Unlike {@link #applyOwnershipSeparation(PipelineSnapshot)}, this method does not migrate ownership, so
     * it can run on a worker thread (the ownership store's {@code snapshotAll()} / {@code isOwned()} are
     * concurrency-safe read-only snapshots). Actual ownership release is performed by the server thread in the
     * apply phase.</p>
     *
     * <p>Two signals count as external modification:</p>
     * <ul>
     *   <li>the persisted record is {@code owned=true}, but the backend's current value (the existing value
     *       collected this round) differs from the recorded value;</li>
     *   <li>the item is overridden by the ProjectE manual file, regardless of whether the value happens to match.</li>
     * </ul>
     *
     * <p>Only items the backend actually reported a value for this round are compared, to avoid misjudging
     * "not scanned / ignored" as external deletion. Manual values are identified via the injected detector and
     * do not depend on whether the item appears in the existing-values map.</p>
     */
    private OwnershipDetection detectExternalModificationsOnly(PipelineSnapshot snapshot) {
        if (!preserveExternallyModifiedValues) {
            return new OwnershipDetection(0, 0, Map.of(), Set.of());
        }
        Map<ItemKey, GeneratedEmcEntry> entries;
        try {
            entries = store.snapshotAll();
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 读取归属快照失败，本轮跳过外部修改检测", failure);
            return new OwnershipDetection(0, 0, Map.of(), Set.of());
        }
        if (entries == null || entries.isEmpty()) {
            return new OwnershipDetection(0, 0, Map.of(), Set.of());
        }

        Map<ItemKey, Long> observed = snapshot.existingValues();
        Set<ItemKey> considered = new TreeSet<>(observed.keySet());
        Set<ItemKey> manualItems = new TreeSet<>();
        Predicate<ItemKey> manualDetector = manualDetector();
        if (manualDetector != null) {
            for (Map.Entry<ItemKey, GeneratedEmcEntry> mapEntry : entries.entrySet()) {
                GeneratedEmcEntry entry = mapEntry.getValue();
                if (entry == null || !entry.owned()) {
                    continue;
                }
                ItemKey item = entry.item() == null ? mapEntry.getKey() : entry.item();
                if (item != null && isManuallySet(manualDetector, item)) {
                    manualItems.add(item);
                }
            }
        }

        OwnershipManager manager = new OwnershipManager(store, new MapEmcValueReader(observed));
        List<ItemKey> drifted;
        try {
            drifted = manager.detectExternalModifications(considered);
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 检测外部修改失败，本轮保留现有归属", failure);
            drifted = List.of();
        }

        Set<ItemKey> candidates = new TreeSet<>(drifted);
        candidates.addAll(manualItems);
        int detected = 0;
        Map<ItemKey, Long> preservedValues = new TreeMap<>();
        Set<ItemKey> protectedItems = new TreeSet<>();
        for (ItemKey item : candidates) {
            GeneratedEmcEntry entry = entries.get(item);
            if (entry == null || !entry.owned()) {
                continue;
            }
            detected++;
            protectedItems.add(item);
            Long observedValue = observed.get(item);
            long preservedValue = observedValue != null && observedValue > 0L
                    ? observedValue
                    : entry.value();
            // Even if the ownership-release write fails, keep the external value and block this round's overwrite;
            // the failure only affects whether later scans still see the owned marker, not this round's economic safety.
            if (preservedValue > 0L) {
                preservedValues.put(item, preservedValue);
            }
        }
        if (detected > 0 && LOGGER.isDebugEnabled()) {
            LOGGER.debug("SudoersEMC 外部修改条目：{}", candidates);
        }
        return new OwnershipDetection(detected, 0, preservedValues, protectedItems);
    }

    /**
     * Releases ownership of detected externally-modified entries on the server thread.
     *
     * @param detected       entry count from the detection phase, for logging only
     * @param protectedItems the set of items whose ownership must be released
     * @return the number successfully released
     */
    private int releaseDetectedOwnership(int detected, Set<ItemKey> protectedItems) {
        if (store == null || protectedItems == null || protectedItems.isEmpty()) {
            return 0;
        }
        OwnershipManager manager = new OwnershipManager(store, new MapEmcValueReader(Map.of()));
        int released = 0;
        for (ItemKey item : protectedItems) {
            if (manager.releaseOwnership(item)) {
                released++;
            }
        }
        if (detected > 0) {
            LOGGER.info("SudoersEMC 检测到 {} 个 owned 条目被外部修改，已释放 {} 个所有权并保留外部值",
                    detected, released);
        }
        return released;
    }

    private Predicate<ItemKey> manualDetector() {
        if (manualDetectorPrepared) {
            return preparedManualDetector;
        }
        return resolveManualDetector();
    }

    private Predicate<ItemKey> resolveManualDetector() {
        try {
            return manualValueDetectorSupplier.get();
        } catch (RuntimeException failure) {
            if (manualDetectorFailureWarned.compareAndSet(false, true)) {
                LOGGER.warn("SudoersEMC 读取手工值判定器失败，本轮跳过手工值检测", failure);
            }
            return null;
        }
    }

    private static boolean isManuallySet(Predicate<ItemKey> detector, ItemKey item) {
        return isManuallySet(detector, item, false);
    }

    /**
     * Manual-value detection.
     *
     * <p>The owned external-modification detection path stays fail-open (an exception is treated as "not
     * manual", only affecting detection scope); the fallback pricing path must be fail-closed (an exception is
     * treated as "manual" — better not to price than to overwrite an admin-fixed value).</p>
     */
    private static boolean isManuallySet(Predicate<ItemKey> detector, ItemKey item,
                                         boolean failClosed) {
        try {
            return detector.test(item);
        } catch (RuntimeException failure) {
            if (failClosed) {
                LOGGER.debug("手工值判定异常，按手动值保护处理：{}", item, failure);
                return true;
            }
            return false;
        }
    }

    /**
     * Ownership separation result.
     *
     * @param items          separated item snapshots, keeping input order
     * @param existingValues still-protected non-owned existing values, sorted ascending by key
     * @param refreshedOwned number of owned entries whose existing value was cleared
     */
    record OwnershipSeparation(List<ItemSnapshot> items, Map<ItemKey, Long> existingValues,
                               int refreshedOwned) {

        OwnershipSeparation {
            items = List.copyOf(items);
            existingValues = Collections.unmodifiableMap(new TreeMap<>(existingValues));
        }
    }

    record OwnershipDetection(int detected, int released, Map<ItemKey, Long> preservedValues,
                              Set<ItemKey> protectedItems) {

        OwnershipDetection {
            preservedValues = Collections.unmodifiableMap(
                    new TreeMap<>(preservedValues == null ? Map.of() : preservedValues));
            protectedItems = protectedItems == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(protectedItems));
        }
    }

    /** Ownership handling result of the apply phase. */
    record OwnershipApplication(PipelineSnapshot snapshot, int detected, int released,
                                Set<ItemKey> protectedItems) {

        OwnershipApplication {
            protectedItems = protectedItems == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(protectedItems));
        }
    }

    /** Read-only backend that reads current values from this round's collection snapshot. */
    private static final class MapEmcValueReader implements OwnershipManager.EmcValueReader {

        private final Map<ItemKey, Long> values;

        MapEmcValueReader(Map<ItemKey, Long> values) {
            this.values = values == null ? Map.of() : values;
        }

        @Override
        public long currentValue(ItemKey item) {
            Long value = values.get(item);
            return value == null ? 0L : value;
        }
    }

    /**
     * Pure solve phase: ownership separation, seed building, multi-round "recipe solve → fallback re-seed"
     * convergence, candidate deduplication and unresolved counting.
     *
     * <p>This method <strong>does not write stores</strong>, trigger overflow audits, write reports or scan
     * state files, or request remapping. The only persistence-related reads are the concurrency-safe read-only
     * snapshots of {@code GeneratedEntryStore} ({@code snapshotAll()} and {@code isOwned()}); ownership release
     * is deferred to {@link #applySolved(PipelineSnapshot, SolveOutcome)} on the server thread. This method can
     * therefore safely run on a worker thread.</p>
     *
     * <p>The server thread pre-resolves the manual-value detector and pre-computes resolver values when
     * collection completes; the worker thread then only reads these immutable results and does not access the
     * file system or Minecraft registries.</p>
     *
     * @param snapshot the collection result, may be {@code null}
     * @return an immutable pure solve result; unexpected exceptions become a failed result and never throw out of the worker thread
     */
    public SolveOutcome solveOnly(@Nullable PipelineSnapshot snapshot) {
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
        fullReportWritten = false;
        lastOwnershipExternalModifications = 0;
        lastOwnershipReleased = 0;
        lastResolverApplied = 0;
        long cacheHitsBefore = recipeSolver.cacheHits();
        long cacheMissesBefore = recipeSolver.cacheMisses();
        long startedAtNanos = System.nanoTime();
        int roundsUsed = 0;
        SolveResult lastSolveResult = skippedSolveResult();
        OwnershipApplication ownership = new OwnershipApplication(data, 0, 0, Set.of());

        try {
            ownership = applyOwnershipSeparationPure(data);
            data = ownership.snapshot();
            lastSnapshotForReport = data;
            lastOwnershipExternalModifications = ownership.detected();
            long minimumEmc = solveSettings.minimumEmc();

            Map<ItemKey, Candidate> recipeCandidates = new TreeMap<>();
            Map<ItemKey, Candidate> fallbackCandidates = new TreeMap<>();
            Map<ItemKey, Long> seeds = initialSeeds(data);

            boolean cancelledDuringSolve = false;
            for (int round = 1; round <= MAX_SOLVE_ROUNDS; round++) {
                // Future.cancel(true) only sets the interrupt flag; the cooperative token may already have been
                // cleared by the scheduler, so both must be checked, otherwise a CPU solve that ignores interrupts
                // would keep running the whole round.
                if (isCancelled() || Thread.currentThread().isInterrupted()) {
                    cancelledDuringSolve = true;
                    break;
                }
                roundsUsed = round;
                SolveResult roundResult = solveRound(data, seeds);
                lastSolveResult = roundResult;

                boolean solvedProgress = mergeSolvedValues(roundResult, recipeCandidates, seeds);
                int fallbackAdded = addFallbackCandidates(data, seeds, fallbackCandidates);
                if (!solvedProgress && fallbackAdded == 0) {
                    break;
                }
            }
            lastSolverRoundCount = roundsUsed;
            int cacheHits = (int) Math.min(Integer.MAX_VALUE,
                    recipeSolver.cacheHits() - cacheHitsBefore);
            int cacheMisses = (int) Math.min(Integer.MAX_VALUE,
                    recipeSolver.cacheMisses() - cacheMissesBefore);
            lastSolverCacheHits = cacheHits;
            lastSolverCacheMisses = cacheMisses;

            if (cancelledDuringSolve || isCancelled()) {
                return SolveOutcome.cancelled(data, lastSolveResult, roundsUsed, cacheHits,
                        cacheMisses, ownership.detected(), ownership.protectedItems(),
                        lastResolverApplied, startedAtNanos);
            }

            List<Candidate> candidates =
                    selectFinalCandidates(recipeCandidates, fallbackCandidates, minimumEmc);
            if (!ownership.protectedItems().isEmpty()) {
                List<Candidate> filtered = new ArrayList<>(candidates.size());
                for (Candidate candidate : candidates) {
                    if (!ownership.protectedItems().contains(candidate.item())) {
                        filtered.add(candidate);
                    }
                }
                candidates = List.copyOf(filtered);
            }
            int unresolved = countUnresolved(lastSolveResult, seeds);
            long durationMillis = elapsedMillis(startedAtNanos);
            return new SolveOutcome(data, candidates, lastSolveResult, unresolved, roundsUsed,
                    cacheHits, cacheMisses, ownership.detected(), ownership.protectedItems(),
                    lastResolverApplied, false, false, "", startedAtNanos, durationMillis);
        } catch (Throwable failure) {
            LOGGER.error("SudoersEMC 纯求解阶段失败：{}", failureDetail(failure), failure);
            long durationMillis = elapsedMillis(startedAtNanos);
            int cacheHits = (int) Math.min(Integer.MAX_VALUE,
                    recipeSolver.cacheHits() - cacheHitsBefore);
            int cacheMisses = (int) Math.min(Integer.MAX_VALUE,
                    recipeSolver.cacheMisses() - cacheMissesBefore);
            return SolveOutcome.failed(data, startedAtNanos, failureDetail(failure),
                    roundsUsed, cacheHits, cacheMisses);
        }
    }

    /**
     * Applies an already-solved result: safety layer, store writes, stale-entry cleanup, scan state and diagnostic report.
     *
     * <p><strong>Must be called on the server thread.</strong> This method first releases ownership of externally
     * modified entries detected during solving, then runs large-drop protection, overflow protection and
     * {@code store.stage} per entry. Candidates, counts and report fields all come from {@link SolveOutcome}, so
     * {@code applySolved(snapshot, solveOnly(snapshot))} is bit-for-bit identical to the pre-split one-shot solve-and-apply.</p>
     *
     * @param snapshot the original collection result, used only for defensive null handling; the effective post-solve snapshot comes from outcome
     * @param outcome  the pure solve result, may be {@code null}
     * @return the aggregate result; any unexpected exception becomes an all-zero result
     */
    public PipelineResult applySolved(@Nullable PipelineSnapshot snapshot,
                                      @Nullable SolveOutcome outcome) {
        if (outcome == null) {
            LOGGER.warn("SudoersEMC 应用阶段没有可用的求解结果，跳过本轮写入");
            return PipelineResult.zeroed(0L);
        }
        PipelineSnapshot data = outcome.effectiveSnapshot();
        fullReportWritten = false;
        lastSnapshotForReport = data;
        lastSolverRoundCount = outcome.solverRounds();
        lastSolverCacheHits = outcome.cacheHits();
        lastSolverCacheMisses = outcome.cacheMisses();
        lastResolverApplied = outcome.resolverApplied();
        long startedAtNanos = outcome.startedAtNanos();

        try {
            // Cancellation and failure must be checked before ownership release: releaseDetectedOwnership writes the
            // store, an apply-phase side effect. The contract requires failed/cancelled results to produce no writes.
            lastOwnershipExternalModifications = outcome.ownershipDetected();
            if (outcome.failed()) {
                long durationMillis = elapsedMillis(startedAtNanos);
                lastSolvedCount = 0;
                lastUnresolvedCount = 0;
                lastSolverRoundCount = 0;
                lastDurationMillis = durationMillis;
                lastOwnershipReleased = 0;
                LOGGER.error("SudoersEMC 扫描管线求解与应用失败，本轮返回全零结果：{}",
                        outcome.failureDetail());
                writePartialReport(data, diagnostics, false, "扫描失败");
                return PipelineResult.zeroed(durationMillis);
            }
            if (outcome.cancelled() || isCancelled()) {
                lastOwnershipReleased = 0;
                return cancelledResult(data, startedAtNanos);
            }

            int released = releaseDetectedOwnership(
                    outcome.ownershipDetected(), outcome.protectedItems());
            lastOwnershipReleased = released;

            List<Candidate> candidates = outcome.candidates();
            SolveResult lastSolveResult = outcome.solveResult();
            int solvedByRecipe = 0;
            int solvedByFallback = 0;
            for (Candidate candidate : candidates) {
                if (candidate.source() == PriceSource.RECIPE) {
                    solvedByRecipe++;
                } else {
                    solvedByFallback++;
                }
            }

            Map<ItemKey, GeneratedEmcEntry> previousEntries =
                    store == null ? Map.of() : store.snapshotAll();
            int overflowBefore = overflowPipeline == null ? 0 : overflowPipeline.resetsApplied();

            int applied = 0;
            int quarantined = 0;
            int externalBaselineApplied = 0;
            Map<String, Long> sourceCounts = new LinkedHashMap<>();
            boolean cancelledDuringApply = false;
            int applyIndex = 0;

            for (Candidate candidate : candidates) {
                if (applyIndex++ % APPLY_CANCEL_CHECK_INTERVAL == 0 && isCancelled()) {
                    cancelledDuringApply = true;
                    break;
                }
                GeneratedEmcEntry previous = previousEntries.get(candidate.item());
                long previousValue = previous == null ? 0L : previous.value();

                PriceDropDecision decision = evaluateDropProtection(candidate, previousValue);
                if (!decision.allowed()) {
                    quarantined++;
                    recordQuarantine(candidate, decision);
                    LOGGER.debug("物品 {} 的拟写入值 {} 因大幅降价被隔离（旧值 {}，降幅 {}）",
                            candidate.item(), candidate.value(), previousValue, decision.dropRatio());
                    continue;
                }

                long finalValue = applyOverflowProtection(candidate);
                if (finalValue <= 0L) {
                    LOGGER.debug("物品 {} 经过溢出保护后数值为 {}，跳过写入", candidate.item(), finalValue);
                    continue;
                }

                if (store != null) {
                    boolean staged = store.stage(new GeneratedEmcEntry(
                            candidate.item(),
                            finalValue,
                            candidate.source(),
                            candidate.recipeId(),
                            true,
                            System.currentTimeMillis(),
                            candidate.alternativeCosts()));
                    if (!staged) {
                        LOGGER.debug("物品 {} 的生成值未被存储接受，可能已有非 SudoersEMC 拥有的条目",
                                candidate.item());
                        continue;
                    }
                }
                applied++;
                sourceCounts.merge(candidate.source().name(), 1L, Long::sum);
                if (candidate.source() == PriceSource.EXTERNAL_BASELINE) {
                    externalBaselineApplied++;
                }
                if (DebugLog.isEnabled()) {
                    final long index = applyIndex;
                    final long before = previousValue;
                    final long after = finalValue;
                    DebugLog.headOrSampled("apply", index, 20L, 500L,
                            () -> "applied item=" + candidate.item()
                                    + " source=" + candidate.source()
                                    + " previous=" + before + " final=" + after);
                }
            }

            Set<ItemKey> candidateKeys = new HashSet<>(candidates.size());
            for (Candidate candidate : candidates) {
                candidateKeys.add(candidate.item());
            }
            int retiredEntries = 0;
            if (!cancelledDuringApply) {
                retiredEntries = retireStaleOwnedEntries(
                        data, candidateKeys, data.existingValues());
                rewriteProjectECustomEmcIfEnabled();
            }

            int overflowResets = overflowPipeline == null
                    ? 0
                    : Math.max(0, overflowPipeline.resetsApplied() - overflowBefore);
            int unresolved = cancelledDuringApply ? 0 : outcome.unresolved();
            long durationMillis = elapsedMillis(startedAtNanos);

            lastSolvedCount = applied;
            lastUnresolvedCount = unresolved;
            lastDurationMillis = durationMillis;

            boolean appliedToStore = store != null;
            if (!cancelledDuringApply) {
                // On cancellation the unfinished candidate set must not be written as a new scan fingerprint, otherwise the next startup would misjudge it as unchanged.
                updateScanState(data, durationMillis, applied, unresolved);
            }

            Map<String, Long> extraCounters = buildExtraCounters(data, lastSolveResult,
                    quarantined, overflowResets, durationMillis, appliedToStore,
                    retiredEntries, outcome.solverRounds(), lastOwnershipExternalModifications,
                    lastOwnershipReleased, diagnostics);
            extraCounters.put("solverCacheHits", (long) lastSolverCacheHits);
            extraCounters.put("solverCacheMisses", (long) lastSolverCacheMisses);
            addExternalBaselineCounters(extraCounters, externalBaselineApplied);
            if (cancelledDuringApply) {
                extraCounters.put("scanCancelled", 1L);
                extraCounters.put("partialReport", 1L);
            }
            PipelineResult result = new PipelineResult(
                    solvedByRecipe,
                    solvedByFallback,
                    applied,
                    quarantined,
                    overflowResets,
                    unresolved,
                    durationMillis,
                    appliedToStore,
                    sourceCounts,
                    extraCounters);
            if (cancelledDuringApply) {
                // Already-written entries are each atomic and consistent; here only a partial report is written, leaving the scheduler to terminate the scan.
                writePartialReport(data, diagnostics, true, "扫描已取消");
                fullReportWritten = true;
            } else {
                writeDiagnosticReport(data, result);
            }
            return result;
        } catch (Throwable failure) {
            long durationMillis = elapsedMillis(startedAtNanos);
            lastSolvedCount = 0;
            lastUnresolvedCount = 0;
            lastSolverRoundCount = 0;
            lastDurationMillis = durationMillis;
            LOGGER.error("SudoersEMC 扫描管线求解与应用失败，本轮返回全零结果：{}",
                    failureDetail(failure), failure);
            // A failure must also leave a partial report; the report layer itself guarantees it does not throw.
            writePartialReport(data, diagnostics, false, "扫描失败");
            return PipelineResult.zeroed(durationMillis);
        }
    }

    /**
     * Solves the snapshot and writes generated values for priceable items.
     *
     * <p>This method is a compatibility wrapper around {@link #solveOnly(PipelineSnapshot)} and
     * {@link #applySolved(PipelineSnapshot, SolveOutcome)}, guaranteeing bit-for-bit identical results before and
     * after the split.</p>
     *
     * @param snapshot the collection result, may be {@code null}
     * @return the aggregate result; any unexpected exception becomes an all-zero result
     */
    public PipelineResult solveAndApply(@Nullable PipelineSnapshot snapshot) {
        return applySolved(snapshot, solveOnly(snapshot));
    }

    /**
     * Termination path when the solve phase is cancelled.
     *
     * <p>The apply loop has not been entered yet, so no writes occur; a partial report is written and
     * {@code fullReportWritten} is set to avoid the scheduler's termination callback writing the same report twice.</p>
     */
    private PipelineResult cancelledResult(PipelineSnapshot data, long startedAtNanos) {
        long durationMillis = elapsedMillis(startedAtNanos);
        lastSolvedCount = 0;
        lastUnresolvedCount = 0;
        lastDurationMillis = durationMillis;
        writePartialReport(data, diagnostics, true, "扫描已取消");
        fullReportWritten = true;
        return PipelineResult.zeroed(durationMillis);
    }

    /**
     * Cleans up owned entries that did not participate in pricing at all this round.
     *
     * <p>After an item is excluded by new config, a recipe is removed, or an item disappears from the registry, its
     * old owned value should no longer apply. Criterion: an entry with {@code owned=true} that is neither in this
     * round's final candidates nor in this round's {@code existingValues} is considered to have not participated in
     * pricing at all. Non-owned entries (manual config or external backend writes) are never deleted. Dry-run mode
     * ({@code store == null}) or an empty snapshot (e.g. server not ready) is skipped directly to avoid deleting all
     * entries by mistake.</p>
     *
     * @param snapshot       this round's collection result
     * @param pricedKeys     the set of candidate items selected this round
     * @param existingValues this round's protected existing-values map
     * @return the number of owned entries actually deleted
     */
    private int retireStaleOwnedEntries(PipelineSnapshot snapshot, Set<ItemKey> pricedKeys,
                                        Map<ItemKey, Long> existingValues) {
        if (store == null) {
            return 0;
        }
        if (snapshot.items().isEmpty()) {
            LOGGER.debug("本轮快照没有任何物品，跳过陈旧自有条目清理");
            return 0;
        }
        int retired = 0;
        try {
            for (Map.Entry<ItemKey, GeneratedEmcEntry> entry : store.snapshotAll().entrySet()) {
                GeneratedEmcEntry value = entry.getValue();
                if (value == null || !value.owned()) {
                    continue;
                }
                ItemKey key = entry.getKey();
                if (pricedKeys.contains(key) || existingValues.containsKey(key)) {
                    continue;
                }
                if (store.removeOwned(key)) {
                    retired++;
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("清理陈旧自有条目失败，本轮扫描结果仍然有效", failure);
        }
        LOGGER.debug("本轮扫描清理检查完成：retired={}", retired);
        if (retired > 0) {
            LOGGER.info("SudoersEMC 清理了 {} 个不再参与定价的自有 EMC 条目", retired);
        }
        return retired;
    }

    /**
     * @return the number of entries actually written to the store (or that would have been written in dry-run mode)
     *         in the most recent successful run
     */
    public int lastSolvedCount() {
        return lastSolvedCount;
    }

    /**
     * @return the number of items the solver still had not resolved at the end of the most recent run
     */
    public int lastUnresolvedCount() {
        return lastUnresolvedCount;
    }

    /**
     * @return the elapsed milliseconds of the most recent run
     */
    public long lastDurationMillis() {
        return lastDurationMillis;
    }

    /**
     * @return the number of solve rounds actually executed by the most recent {@link #solveAndApply(PipelineSnapshot)};
     *         {@code 1} for an empty world or when the first round adds no new values
     */
    int lastSolverRoundCount() {
        return lastSolverRoundCount;
    }

    /**
     * Builds the initial solve seeds.
     *
     * <p>Only existing EMC strictly greater than 0 can become a seed: 0 or negative is equivalent to "no value",
     * otherwise it would mask subsequent recipe and fallback pricing.</p>
     */
    private static Map<ItemKey, Long> initialSeeds(PipelineSnapshot snapshot) {
        Map<ItemKey, Long> seeds = new TreeMap<>();
        for (Map.Entry<ItemKey, Long> entry : snapshot.existingValues().entrySet()) {
            Long value = entry.getValue();
            if (value != null && value > 0L) {
                seeds.put(entry.getKey(), value);
            }
        }
        return seeds;
    }

    /**
     * Runs one round of recipe solving.
     *
     * <p>When recipe solving is disabled it returns an empty result but keeps the "fallback re-seed each round"
     * loop structure: after the first round's fallback hits, the second round exits immediately with no new values.</p>
     */
    private SolveResult solveRound(PipelineSnapshot data, Map<ItemKey, Long> seeds) {
        if (!solveRecipesEnabled) {
            LOGGER.debug("配方求解已按配置禁用，本轮仅使用既有值与兜底定价");
            return skippedSolveResult();
        }
        SolveContext context = new SolveContext(seeds);
        // Reuse the same solver instance so the evaluation cache for unchanged recipes works across rounds and scans.
        return recipeSolver.solve(
                data.recipes(), context, evaluatorRegistry, solveSettings, this::isCancelled);
    }

    /**
     * Merges one round's solve result into seeds and recipe candidates.
     *
     * <p>For the same key the smaller value is kept, preventing later rounds from raising an already-generated
     * value; a new value only overwrites when {@link SolveSettings#allowValueIncrease()} is {@code true}.</p>
     *
     * @return whether this round added or updated any seed
     */
    private boolean mergeSolvedValues(SolveResult solveResult,
                                      Map<ItemKey, Candidate> recipeCandidates,
                                      Map<ItemKey, Long> seeds) {
        boolean changed = false;
        boolean allowIncrease = solveSettings.allowValueIncrease();
        long index = 0L;
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            ItemKey item = entry.getKey();
            index++;
            if (isExcludedFromPricing(item)) {
                LOGGER.debug("物品 {} 被排除规则命中，跳过配方候选", item);
                if (DebugLog.isEnabled()) {
                    DebugLog.headOrSampled("recipe-merge", index, 20L, 500L,
                            () -> "skip excluded item=" + item);
                }
                continue;
            }
            long solvedValue = entry.getValue();
            if (solvedValue <= 0L) {
                continue;
            }

            Long previous = seeds.get(item);
            long mergedValue;
            if (previous == null) {
                mergedValue = solvedValue;
            } else if (allowIncrease) {
                mergedValue = solvedValue;
            } else {
                mergedValue = Math.min(previous, solvedValue);
            }
            if (previous == null || mergedValue != previous) {
                seeds.put(item, mergedValue);
                changed = true;
            }

            SolveTrace trace = solveResult.traces().get(item);
            ResourceLocation recipeId = trace == null ? null : trace.recipeId();
            List<Long> alternativeCosts = trace == null ? List.of() : trace.alternativeCosts();
            Candidate existing = recipeCandidates.get(item);
            if (existing == null || mergedValue < existing.value() || allowIncrease) {
                recipeCandidates.put(item,
                        new Candidate(item, mergedValue, PriceSource.RECIPE, recipeId, alternativeCosts));
            }
            if (DebugLog.isEnabled()) {
                final long logIndex = index;
                final long value = mergedValue;
                final long previousValue = previous == null ? 0L : previous;
                DebugLog.headOrSampled("recipe-merge", logIndex, 20L, 500L,
                        () -> "merge item=" + item + " value=" + value
                                + " previous=" + previousValue);
            }
        }
        return changed;
    }

    /**
     * Determines whether an item is explicitly rejected from automatic pricing by exclusion rules.
     *
     * <p>The recipe path and fallback path share this method to avoid logic drift between the two:</p>
     * <ul>
     *   <li>{@link TierPricing#price} returning {@code Optional.of(0L)} means a tier rule explicitly excludes it;</li>
     *   <li>{@link CreativeItemProtection#decide} returning {@code priceable=false} means creative-item protection excludes it.</li>
     * </ul>
     *
     * <p>A variant's {@link ItemKey} shares the item id with the base item, so exclusion rules naturally cover all
     * NBT variants.</p>
     *
     * @param item the item identity to judge
     * @return {@code true} means the item must not enter candidates or be written to the store
     */
    public boolean isExcludedFromPricing(ItemKey item) {
        if (item == null) {
            return false;
        }
        try {
            Optional<Long> tierValue = tierPricing.price(item);
            if (tierValue.isPresent() && tierValue.get() <= 0L) {
                return true;
            }
            CreativeItemProtection.Decision decision = creativeProtection.decide(item, true, true);
            return !decision.priceable();
        } catch (RuntimeException failure) {
            LOGGER.warn("排除规则判定失败，按排除处理：{}", item, failure);
            return true;
        }
    }

    /**
     * Resolves EMC for an item via the third-party resolver registry.
     *
     * <p>An empty registry returns 0 directly, rebuilding no {@link ItemStack} and parsing no SNBT, keeping the
     * default behaviour exactly as in Phase 24. Any exception degrades to "no value" and continues
     * TAG/RARITY/FALLBACK.</p>
     */
    private long resolveWithRegistry(ItemKey key) {
        if (key == null) {
            return 0L;
        }
        Map<ItemKey, Long> prepared = preparedResolverValues;
        Long cached = prepared.get(key);
        if (cached != null) {
            return cached;
        }
        return resolveWithRegistryDirect(key);
    }

    /**
     * Resolves directly via the registry and resolvers accessible to the current thread; for the server thread or
     * test threads only.
     */
    private long resolveWithRegistryDirect(ItemKey key) {
        ResolverRegistry registry = resolverRegistry;
        if (key == null || registry == null || registry.size() == 0) {
            return 0L;
        }
        ItemStack stack = stackFor(key);
        if (stack.isEmpty()) {
            return 0L;
        }
        try {
            return Math.max(0L, registry.resolve(stack).orElse(0L));
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.debug("NBT resolver 解析物品 {} 失败，按无值处理", key, failure);
            return 0L;
        }
    }

    /**
     * Discards resolver/baseline values strictly above the overflow threshold.
     *
     * <p>External values would pass overflow protection when finally written, but candidate values are written
     * into seeds first and propagate upstream through recipes; allowing an anomalously high external value into
     * seeds would pollute the whole recipe chain before final protection. So it is rejected here before writing
     * candidates/seeds, and TAG/RARITY/FALLBACK is tried next.</p>
     */
    private long capBaselineForOverflow(ItemKey item, long baseline) {
        if (baseline <= 0L || overflowPipeline == null) {
            return baseline;
        }
        try {
            var settings = overflowPipeline.protection().settings();
            if (!settings.enabled()) {
                return baseline;
            }
            if (baseline > settings.threshold()) {
                if (DebugLog.isEnabled()) {
                    DebugLog.log("fallback", () -> "baseline-over-threshold item=" + item
                            + " baseline=" + baseline + " threshold=" + settings.threshold());
                }
                return 0L;
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("检查外部基线溢出阈值失败，丢弃该基线：{}", item, failure);
            return 0L;
        }
        return baseline;
    }

    /**
     * Rebuilds a stack for the resolver from an {@link ItemKey}. Returns an empty stack when the variant SNBT
     * cannot be parsed.
     */
    private static ItemStack stackFor(ItemKey key) {
        Item item = BuiltInRegistries.ITEM.get(key.itemId());
        if (item == null || item == Items.AIR) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = new ItemStack(item);
        if (key.hasVariant()) {
            try {
                CompoundTag tag = TagParser.parseTag(key.variantHash());
                stack.setTag(tag);
            } catch (Exception failure) {
                return ItemStack.EMPTY;
            }
        }
        return stack;
    }

    /**
     * Merges recipe and fallback candidates, applying final deduplication and minimum filtering.
     *
     * <p>Only one entry is kept per {@link ItemKey}: a recipe source takes priority over any fallback source
     * (TIER/TAG/RARITY/FALLBACK). A candidate value must be greater than 0 and not below the given minimum.</p>
     *
     * @param recipeCandidates   candidates produced by recipe solving, may be empty
     * @param fallbackCandidates candidates produced by fallback pricing, may be empty
     * @param minimumEmc         minimum EMC
     * @return the final candidates sorted ascending by item identity
     */
    static List<Candidate> selectFinalCandidates(Map<ItemKey, Candidate> recipeCandidates,
                                                 Map<ItemKey, Candidate> fallbackCandidates,
                                                 long minimumEmc) {
        Map<ItemKey, Candidate> merged = new TreeMap<>(fallbackCandidates);
        merged.putAll(recipeCandidates);
        List<Candidate> selected = new ArrayList<>(merged.size());
        for (Candidate candidate : merged.values()) {
            if (candidate.value() > 0L && candidate.value() >= minimumEmc) {
                selected.add(candidate);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * Counts items still unpriced after convergence.
     *
     * <p>Items the solver reports as unresolved may already have been re-seeded by later fallback rounds, so they are
     * filtered once more against the final seeds here.</p>
     */
    private static int countUnresolved(SolveResult solveResult, Map<ItemKey, Long> seeds) {
        int unresolved = 0;
        for (ItemKey item : solveResult.unresolvedItems()) {
            Long seed = seeds.get(item);
            if (seed == null || seed <= 0L) {
                unresolved++;
            }
        }
        return unresolved;
    }

    /**
     * Builds the empty "recipe solving skipped" result.
     *
     * <p>This result contains no generated values, traces or unresolved items, meaning no recipes were evaluated this
     * round; used when {@code recipe.solveRecipes=false} to keep downstream candidate collection and diagnostic counter
     * structure unchanged.</p>
     */
    private static SolveResult skippedSolveResult() {
        return new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0);
    }

    /**
     * Appends fallback candidates for items still without a seed and merges hit values into seeds.
     *
     * <p>Only processes items neither in existing EMC nor in the current seeds. Creative-item protection decides whether
     * an item may be auto-priced; fixed values take priority over the fallback pricing chain. No candidate is produced
     * when the fallback chain returns a non-positive value, a value below the minimum EMC, or an empty result. Hit values
     * are also written into {@code seeds} so the next solve round can propagate fallback prices to upstream intermediates.</p>
     *
     * @return the number of fallback candidates added this round
     */
    private int addFallbackCandidates(PipelineSnapshot snapshot, Map<ItemKey, Long> seeds,
                                      Map<ItemKey, Candidate> candidates) {
        int added = 0;
        long minimumEmc = solveSettings.minimumEmc();
        Predicate<ItemKey> manualDetector = manualDetector();
        if (manualDetector == null) {
            // When the manual-value detector is unavailable, never fail-open: otherwise TAG/RARITY/baseline could overwrite
            // values an admin fixed in the ProjectE manual file. The whole fallback round is skipped; recipe candidates still apply normally.
            if (manualDetectorFailureWarned.compareAndSet(false, true)) {
                LOGGER.warn("SudoersEMC 手工值判定器不可用，本轮跳过全部兜底候选以避免覆盖手动值");
            }
            if (DebugLog.isEnabled()) {
                DebugLog.log("fallback", "manual-detector-unavailable round-aborted");
            }
            return 0;
        }
        int index = 0;
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            index++;
            if (seeds.containsKey(item) || snapshot.existingValues().containsKey(item)) {
                continue;
            }
            // Manual values take priority over any automatic source: even without an owned entry, the item must not be
            // re-taken by baseline/TAG/RARITY. The detector is created once at the start of each round to avoid a per-item file stat.
            if (isManuallySet(manualDetector, item, true)) {
                if (DebugLog.isEnabled()) {
                    DebugLog.headOrSampled("fallback", index, 20L, 500L,
                            () -> "skip manual-protected item=" + item);
                }
                continue;
            }
            if (isExcludedFromPricing(item)) {
                LOGGER.debug("物品 {} 被排除规则命中，跳过兜底定价", item);
                if (DebugLog.isEnabled()) {
                    DebugLog.headOrSampled("fallback", index, 20L, 500L,
                            () -> "skip excluded item=" + item);
                }
                continue;
            }

            CreativeItemProtection.Decision decision;
            try {
                decision = creativeProtection.decide(item, false, false);
            } catch (RuntimeException failure) {
                LOGGER.warn("创造物品保护判定失败，跳过物品 {} 的兜底定价", item, failure);
                continue;
            }
            if (!decision.priceable()) {
                continue;
            }

            if (decision.specialValue() > 0L) {
                if (decision.specialValue() >= minimumEmc) {
                    Candidate candidate =
                            new Candidate(item, decision.specialValue(), PriceSource.TIER, null);
                    candidates.put(item, candidate);
                    seeds.put(item, candidate.value());
                    added++;
                }
                continue;
            }

            // The NBT resolver is most specific; external baselines such as PEI are read only when it does not hit.
            long resolverValue = resolveWithRegistry(item);
            long baseline = resolverValue;
            if (baseline <= 0L) {
                try {
                    baseline = externalBaselineSource.lookup(item).orElse(0L);
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.debug("读取外部基线失败，按 0 处理：{}", item, failure);
                    baseline = 0L;
                }
            }
            // Both resolver and PEI baseline are external inputs: a high value above the overflow threshold must never be
            // written into seeds first, or it would pollute upstream recipes before final overflow protection. After discarding, continue with TAG/RARITY/FALLBACK.
            baseline = capBaselineForOverflow(item, baseline);

            if (fallbackPricing == null) {
                if (baseline <= 0L || baseline < minimumEmc) {
                    continue;
                }
                Candidate candidate =
                        new Candidate(item, baseline, PriceSource.EXTERNAL_BASELINE, null);
                candidates.put(item, candidate);
                seeds.put(item, candidate.value());
                added++;
                if (resolverValue > 0L) {
                    lastResolverApplied++;
                }
                if (DebugLog.isEnabled()) {
                    DebugLog.headOrSampled("fallback", index, 20L, 500L,
                            () -> "hit item=" + item + " source=EXTERNAL_BASELINE value="
                                    + candidate.value());
                }
                continue;
            }

            Optional<FallbackPricing.PricedValue> priced;
            try {
                priced = fallbackPricing.price(new FallbackPricing.PricingRequest(
                        item, itemSnapshot.tagPaths(), itemSnapshot.rarity(), 0L, baseline));
            } catch (RuntimeException failure) {
                LOGGER.warn("兜底定价失败，跳过物品 {}", item, failure);
                continue;
            }
            if (priced.isEmpty()) {
                if (DebugLog.isEnabled()) {
                    final long baselineForLog = baseline;
                    DebugLog.headOrSampled("fallback", index, 20L, 500L,
                            () -> "miss item=" + item + " baseline=" + baselineForLog);
                }
                continue;
            }

            FallbackPricing.PricedValue pricedValue = priced.get();
            if (pricedValue.value() <= 0L || pricedValue.value() < minimumEmc) {
                if (DebugLog.isEnabled()) {
                    DebugLog.headOrSampled("fallback", index, 20L, 500L,
                            () -> "discard item=" + item + " value=" + pricedValue.value()
                                    + " minEmc=" + minimumEmc);
                }
                continue;
            }
            Candidate candidate =
                    new Candidate(item, pricedValue.value(), pricedValue.source(), null);
            candidates.put(item, candidate);
            seeds.put(item, candidate.value());
            added++;
            if (resolverValue > 0L && pricedValue.source() == PriceSource.EXTERNAL_BASELINE) {
                lastResolverApplied++;
            }
            if (DebugLog.isEnabled()) {
                DebugLog.headOrSampled("fallback", index, 20L, 500L,
                        () -> "hit item=" + item + " source=" + pricedValue.source()
                                + " value=" + pricedValue.value());
            }
        }
        if (DebugLog.isEnabled()) {
            DebugLog.log("fallback", "round added=" + added + " totalCandidates=" + candidates.size());
        }
        return added;
    }

    /**
     * Approves an item's current in-memory quarantine decision.
     *
     * <p>The command layer calls this after approving the persistent record, ensuring {@link LargeDropProtection#approve}
     * has a production caller; the returned decision is only for diagnostics and receipts, while value application is
     * done by the command layer based on the persistent record.</p>
     *
     * @param item the approved item; {@code null} is ignored
     * @return the decisions removed from the in-memory quarantine list this call, in insertion order
     */
    public List<PriceDropDecision> approveQuarantined(@Nullable ItemKey item) {
        if (item == null) {
            return List.of();
        }
        return dropProtection.approve(item);
    }

    /**
     * Writes one quarantined drop into the persistent quarantine record.
     *
     * <p>Dry-run mode (generated entry store is {@code null}) or no injected quarantine store produces no disk writes;
     * the original counting and logging behaviour is unchanged. A record failure only affects the quarantine file, not
     * this round's scan result.</p>
     *
     * @param candidate the quarantined candidate value
     * @param decision  the drop-protection decision
     */
    private void recordQuarantine(Candidate candidate, PriceDropDecision decision) {
        QuarantinedDropsStore target = quarantinedDropsStore;
        if (target == null || store == null) {
            return;
        }
        try {
            target.record(new QuarantinedDropsStore.QuarantinedDrop(
                    candidate.item(),
                    decision.previousValue(),
                    decision.proposedValue(),
                    decision.dropRatio(),
                    decision.reason(),
                    System.currentTimeMillis(),
                    false));
        } catch (RuntimeException failure) {
            LOGGER.warn("写入隔离记录失败，物品 {} 的隔离计数仍然有效", candidate.item(), failure);
        }
    }

    /**
     * Decides whether a proposed write value is quarantined by large-drop protection.
     *
     * <p>When the protection itself throws, the value is allowed through and a warning logged: the protection layer is
     * a safety aid and should not block a whole scan because of one item's anomalous state. The allowed result uses
     * {@code protection-error} as the machine-readable reason.</p>
     */
    private PriceDropDecision evaluateDropProtection(Candidate candidate, long previousValue) {
        try {
            return dropProtection.evaluate(candidate.item(), previousValue, candidate.value());
        } catch (RuntimeException failure) {
            LOGGER.warn("大幅降价保护判定失败，放行物品 {}", candidate.item(), failure);
            return PriceDropDecision.allowed(
                    previousValue, candidate.value(), 0.0D, "protection-error");
        }
    }

    /**
     * Applies overflow protection to a candidate value and writes an audit log.
     *
     * <p>Returns the original value when no overflow pipeline is configured. The pipeline already guarantees it does
     * not throw; an extra fallback layer is kept here so a future implementation change cannot break the "a whole scan
     * never interrupts" contract.</p>
     */
    private long applyOverflowProtection(Candidate candidate) {
        if (overflowPipeline == null) {
            return candidate.value();
        }
        try {
            return overflowPipeline.applyAndAudit(
                    candidate.item(), candidate.value(), overflowScope);
        } catch (RuntimeException failure) {
            LOGGER.warn("溢出保护执行失败，保留物品 {} 的原值 {}", candidate.item(), candidate.value(), failure);
            return candidate.value();
        }
    }

    /**
     * Rewrites ProjectE's {@code custom_emc.json} when both the scope and the explicit switch allow it.
     *
     * <p>Dry-run mode ({@code store == null}) always skips, to avoid test or preview paths accidentally writing the
     * real config directory. A file rewrite failure only logs a warning and never interrupts this round's scan.</p>
     */
    private void rewriteProjectECustomEmcIfEnabled() {
        if (overflowScope != OverflowScope.ENTIRE_CUSTOM_EMC || !allowProjectEFileRewrite) {
            return;
        }
        if (overflowPipeline == null) {
            return;
        }
        if (store == null) {
            LOGGER.debug("干跑模式跳过 ProjectE custom_emc.json 改写");
            return;
        }
        try {
            ProjectECustomEmcFile target = projectECustomEmcFile == null
                    ? new ProjectECustomEmcFile()
                    : new ProjectECustomEmcFile(projectECustomEmcFile);
            // ENTIRE_CUSTOM_EMC is itself an explicit opt-in to rewriting the whole file; the listener fires only after a successful disk write.
            ProjectECustomEmcFile.RewriteResult result = target.rewriteAboveThreshold(
                    overflowPipeline.protection(),
                    store::isOwned,
                    overflowPipeline::recordCustomEmcReset);
            if (result.wroteFile()) {
                LOGGER.info("ProjectE custom_emc.json 受控改写完成：rewritten={} skipped={} notOwned={} backup={}",
                        result.rewritten(), result.skipped(), result.notOwned(), result.backup());
            } else {
                LOGGER.debug("ProjectE custom_emc.json 本轮无需改写：skipped={} notOwned={}",
                        result.skipped(), result.notOwned());
            }
        } catch (Throwable failure) {
            LOGGER.warn("改写 ProjectE custom_emc.json 失败，本轮扫描结果仍然有效", failure);
        }
    }

    /**
     * Updates the incremental scan state. The state store itself does not throw; runtime exceptions are still caught
     * here to protect the result-return path.
     */
    private void updateScanState(PipelineSnapshot snapshot, long durationMillis,
                                 int solvedCount, int unresolvedCount) {
        if (stateStore == null) {
            return;
        }
        try {
            stateStore.update(fingerprint(snapshot), durationMillis, solvedCount, unresolvedCount);
        } catch (RuntimeException failure) {
            LOGGER.warn("更新扫描状态失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * Writes the full diagnostic report. A report write failure only affects the diagnostic file, not this round's
     * numeric result.
     */
    private void writeDiagnosticReport(PipelineSnapshot snapshot, PipelineResult result) {
        if (!generateReportsEnabled || reportManager == null) {
            return;
        }
        try {
            reportManager.writeReport(buildReport(snapshot, result, result.extraCounters()));
            fullReportWritten = true;
        } catch (RuntimeException failure) {
            LOGGER.warn("写入诊断报告失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * Writes a partial report when the scan is cancelled.
     *
     * <p>The scan may stop in the collection or solve phase, so only elapsed time, memory and the statistics of the
     * already-collected snapshot are guaranteed; a complete solve result is not required. Ignored when report writing
     * is disabled.</p>
     *
     * @param telemetry the scheduler-shared diagnostics sampler; the pipeline's internal instance is used when {@code null}
     */
    public void writeCancelledReport(@Nullable ScanDiagnostics telemetry) {
        PipelineSnapshot snapshot = stashedSnapshot.getAndSet(null);
        if (snapshot == null) {
            // When applyStashed has already consumed the stashed snapshot, still use the most recent snapshot to keep item/recipe statistics.
            snapshot = lastSnapshotForReport;
        }
        writePartialReport(snapshot, telemetry, true, "扫描已取消");
    }

    /**
     * Writes a partial report when the scan fails.
     *
     * <p>The exception message goes only to the log; the report itself keeps a stable plain-text structure. Ignored
     * when report writing is disabled.</p>
     *
     * @param detail    failure reason, for logging only, may be {@code null}
     * @param telemetry the scheduler-shared diagnostics sampler; the pipeline's internal instance is used when {@code null}
     */
    public void writeFailedReport(@Nullable String detail, @Nullable ScanDiagnostics telemetry) {
        if (detail != null && !detail.isBlank()) {
            LOGGER.warn("SudoersEMC 扫描未完成，生成部分诊断报告：{}", detail);
        }
        PipelineSnapshot snapshot = stashedSnapshot.getAndSet(null);
        if (snapshot == null) {
            snapshot = lastSnapshotForReport;
        }
        writePartialReport(snapshot, telemetry, false, "扫描失败");
    }

    private void writePartialReport(@Nullable PipelineSnapshot snapshot,
                                    @Nullable ScanDiagnostics telemetry,
                                    boolean cancelled, String reason) {
        if (!generateReportsEnabled || reportManager == null) {
            return;
        }
        if (fullReportWritten) {
            LOGGER.debug("完整诊断报告已写出，跳过部分报告（{}）", reason);
            return;
        }
        ScanDiagnostics selected = telemetry == null ? diagnostics : telemetry;
        try {
            selected.recordMemory();
            PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
            long durationMillis = Math.max(0L, selected.elapsedSinceResetMillis());
            PipelineResult partial = PipelineResult.zeroed(durationMillis);
            Map<String, Long> counters = buildExtraCounters(data, skippedSolveResult(), 0, 0,
                    durationMillis, store != null, 0, 0,
                    lastOwnershipExternalModifications, lastOwnershipReleased, selected);
            addExternalBaselineCounters(counters, 0L);
            counters.put("partialReport", 1L);
            counters.put("scanCancelled", cancelled ? 1L : 0L);
            counters.put("scanFailed", cancelled ? 0L : 1L);
            counters.put("scanAborts", 1L);
            reportManager.writeReport(buildReport(data, partial, counters));
            LOGGER.info("SudoersEMC 已写出部分诊断报告（{}）", reason);
        } catch (RuntimeException failure) {
            LOGGER.warn("写出部分诊断报告失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * Appends external-baseline and resolver related diagnostic counters.
     *
     * <p>All reads are wrapped in try/catch: the source or registry is a third-party/optional component, and any
     * exception may only degrade the counter to 0, never affecting this round's scan result.</p>
     */
    private void addExternalBaselineCounters(Map<String, Long> counters, long applied) {
        if (counters == null) {
            return;
        }
        boolean available = false;
        int entries = 0;
        int resolverRegistered = 0;
        try {
            ExternalBaselineSource source = externalBaselineSource;
            if (source != null) {
                available = source.isAvailable();
                entries = Math.max(0, source.entryCount());
            }
        } catch (Throwable failure) {
            LOGGER.debug("读取外部基线诊断计数失败，按不可用处理", failure);
        }
        try {
            ResolverRegistry registry = resolverRegistry;
            resolverRegistered = registry == null ? 0 : Math.max(0, registry.size());
        } catch (Throwable failure) {
            LOGGER.debug("读取 resolver 诊断计数失败，按 0 处理", failure);
        }
        counters.put("externalBaselineAvailable", available ? 1L : 0L);
        counters.put("externalBaselineEntries", (long) entries);
        counters.put("externalBaselineApplied", Math.max(0L, applied));
        counters.put("resolverRegistered", (long) resolverRegistered);
        counters.put("resolverApplied", (long) Math.max(0, lastResolverApplied));
    }

    /**
     * Aggregates all counters for this round.
     *
     * <p>Counters feed both {@link PipelineResult#extraCounters()} and the diagnostic report, guaranteeing the two are
     * identical. Recipe statistics, solve rounds, quarantine/cleanup counts, per-tick timing and memory sampling are
     * all centralised here so the report layer need not recompute them.</p>
     */
    private static Map<String, Long> buildExtraCounters(PipelineSnapshot snapshot,
                                                         SolveResult solveResult,
                                                         int quarantined, int overflowResets,
                                                         long durationMillis,
                                                         boolean appliedToStore,
                                                         int retiredEntries, int solverRounds,
                                                         int ownershipExternalModifications,
                                                         int ownershipReleased,
                                                         @Nullable ScanDiagnostics telemetry) {
        ScanDiagnostics selected = telemetry == null ? new ScanDiagnostics() : telemetry;
        selected.recordMemory();

        long tickCount = selected.tickCount();
        long scanTotalMillis = tickCount > 0L
                ? selected.elapsedSinceResetMillis()
                : durationMillis;
        long usableRecipes = snapshot.recipeCount();
        long unsupported = snapshot.unsupportedRecipes();
        long invalid = snapshot.invalidRecipes();
        long failed = snapshot.failedRecipes();
        long totalRecipes = usableRecipes + unsupported + invalid + failed;
        long shapedRecipes = 0L;
        long shapelessRecipes = 0L;
        long multiOutputRecipes = 0L;
        long containerRemainderRecipes = 0L;
        for (RecipeSnapshot recipe : snapshot.recipes()) {
            if (recipe == null) {
                continue;
            }
            if (recipe.kind() == com.itzplana.sudoersemc.scanner.RecipeKind.SHAPED) {
                shapedRecipes++;
            } else if (recipe.kind() == com.itzplana.sudoersemc.scanner.RecipeKind.SHAPELESS) {
                shapelessRecipes++;
            }
            if (recipe.hasMultipleOutputs()) {
                multiOutputRecipes++;
            }
            if (recipe.hasContainerRemainders()) {
                containerRemainderRecipes++;
            }
        }


        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("totalRecipes", totalRecipes);
        counters.put("usableRecipes", usableRecipes);
        counters.put("unsupportedRecipes", unsupported);
        counters.put("invalidRecipes", invalid);
        counters.put("failedRecipes", failed);
        counters.put("failedRecipeDetailsRecorded", (long) snapshot.failedRecipeDetails().size());
        counters.put("solverPasses", (long) solveResult.passesUsed());
        counters.put("solverRounds", (long) solverRounds);
        counters.put("recipesEvaluated", (long) solveResult.recipesEvaluated());
        counters.put("solverConverged", solveResult.converged() ? 1L : 0L);
        counters.put("quarantinedDrops", (long) quarantined);
        counters.put("overflowResets", (long) overflowResets);
        counters.put("retiredEntries", (long) retiredEntries);
        counters.put("shapedRecipes", shapedRecipes);
        counters.put("shapelessRecipes", shapelessRecipes);
        counters.put("multiOutputRecipes", multiOutputRecipes);
        counters.put("containerRemainderRecipes", containerRemainderRecipes);
        counters.put("ownershipExternalModifications", (long) ownershipExternalModifications);
        counters.put("ownershipReleasedEntries", (long) ownershipReleased);
        counters.put("dryRun", appliedToStore ? 0L : 1L);
        counters.put("scanTotalMillis", Math.max(0L, scanTotalMillis));
        counters.put("scanTicks", tickCount);
        counters.put("tickMaxMicros", nanosToMicros(selected.maxTickNanos()));
        counters.put("tickAvgMicros", Math.round(selected.averageTickNanos() / 1_000.0D));
        counters.put("tickP95Micros", nanosToMicros(selected.p95TickNanos()));
        counters.put("peakHeapBytes", selected.peakHeapBytes());
        counters.put("heapAtReportBytes", selected.heapBytes());
        counters.put("partialReport", 0L);
        return counters;
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, (nanos + 500L) / 1_000L);
    }

    /**
     * Builds the diagnostic report from this round's run data.
     *
     * <p>Standard fields count only actually-applied entries; recipe scan statistics, solve rounds and timing samples
     * go into {@code extraCounters} so later parsers can read them without changing the report structure. The
     * unresolved-item count uses the final count after multi-round convergence, not an intermediate round's result.</p>
     */
    private static DiagnosticReport buildReport(PipelineSnapshot snapshot, PipelineResult result,
                                                Map<String, Long> extraCounters) {
        return new DiagnosticReport(
                snapshot.itemCount(),
                snapshot.existingValues().size(),
                result.sourceBreakdown().getOrDefault(PriceSource.RECIPE.name(), 0L),
                result.sourceBreakdown().getOrDefault(PriceSource.EXTERNAL_BASELINE.name(), 0L),
                result.sourceBreakdown().getOrDefault(PriceSource.TAG.name(), 0L),
                result.sourceBreakdown().getOrDefault(PriceSource.RARITY.name(), 0L),
                result.sourceBreakdown().getOrDefault(PriceSource.TIER.name(), 0L),
                result.sourceBreakdown().getOrDefault(PriceSource.FALLBACK.name(), 0L),
                result.unresolved(),
                result.quarantined(),
                snapshot.failedRecipes(),
                result.durationMillis(),
                extraCounters.getOrDefault("peakHeapBytes", 0L),
                extraCounters,
                snapshot.failedRecipeDetails());
    }

    private static String failureDetail(Throwable failure) {
        if (failure == null) {
            return "未知错误";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /**
     * Computes the scan fingerprint: all five channels — item/recipe content, tags, config and external baseline —
     * participate.
     *
     * <p>Config and baseline sources degrade to a fixed error marker on read failure and do not block scan completion.</p>
     */
    private ScanFingerprint fingerprint(PipelineSnapshot snapshot) {
        return fingerprintFor(
                snapshot.items(),
                snapshot.recipes(),
                safeFingerprintEntries(configFingerprintSupplier, "config"),
                safeFingerprintEntries(baselineFingerprintSupplier, "baseline"));
    }


    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * One candidate value pending write.
     *
     * <p>Package-private visibility lets same-package tests directly verify the "keep only the recipe source per
     * item" merge rule.</p>
     *
     * @param item             item identity
     * @param value            raw value from solving or fallback pricing, guaranteed {@code > 0}
     * @param source           value source
     * @param recipeId         the recipe that produced this value; {@code null} for fallback pricing results
     * @param alternativeCosts other recipe costs in the same solve round; empty list for fallback sources
     */
    public record Candidate(ItemKey item, long value, PriceSource source,
                            @Nullable ResourceLocation recipeId, List<Long> alternativeCosts) {
        public Candidate {
            alternativeCosts = alternativeCosts == null ? List.of() : List.copyOf(alternativeCosts);
        }

        /**
         * Compatibility constructor for old tests and call sites: carries no alternative costs.
         */
        Candidate(ItemKey item, long value, PriceSource source,
                  @Nullable ResourceLocation recipeId) {
            this(item, value, source, recipeId, List.of());
        }
    }
}
