package com.itzplana.sudoersemc.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.itzplana.sudoersemc.Sudoersemc;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.pricing.PricingSettings;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ConfigFileTypeHandler;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.IConfigEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Common Forge configuration for SudoersEMC.
 *
 * <p>The mod entry point only needs to call {@link #register()} once during construction. The
 * configuration file name is derived by Forge from the mod id ({@code config/sudoersemc-common.toml}
 * in the usual Forge layout).</p>
 */
public final class SudoersemcConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // Field declarations. Values are defined in the static initializer below so that the TOML
    // sections are emitted in the documented order.
    public static final ForgeConfigSpec.BooleanValue GENERAL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue GENERAL_ALWAYS_RESCAN;

    public static final ForgeConfigSpec.IntValue PERFORMANCE_ITEMS_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue PERFORMANCE_ADAPTIVE_BATCH_SIZING;

    public static final ForgeConfigSpec.BooleanValue RECIPE_SOLVE_RECIPES;
    public static final ForgeConfigSpec.IntValue RECIPE_MAX_RECIPE_PASSES;
    public static final ForgeConfigSpec.BooleanValue RECIPE_ALLOW_RECIPE_VALUE_INCREASE;

    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_TAG_PRICING;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_RARITY_PRICING;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES;
    public static final ForgeConfigSpec.LongValue FALLBACK_MIN_EMC;

    public static final ForgeConfigSpec.BooleanValue SAFETY_LARGE_DROP_PROTECTION;
    public static final ForgeConfigSpec.DoubleValue SAFETY_MAX_ALLOWED_DROP_RATIO;
    public static final ForgeConfigSpec.BooleanValue SAFETY_PROTECT_CREATIVE_ONLY_ITEMS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>>
            SAFETY_CREATIVE_ITEM_EXCLUSIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>>
            SAFETY_CREATIVE_ITEM_SPECIAL_VALUES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>>
            SAFETY_CREATIVE_NAMESPACES;

    public static final ForgeConfigSpec.BooleanValue OVERFLOW_ENABLED;
    public static final ForgeConfigSpec.LongValue OVERFLOW_THRESHOLD;
    public static final ForgeConfigSpec.LongValue OVERFLOW_RESET_MIN;
    public static final ForgeConfigSpec.LongValue OVERFLOW_RESET_MAX;
    public static final ForgeConfigSpec.IntValue OVERFLOW_BUCKET_COUNT;
    public static final ForgeConfigSpec.IntValue OVERFLOW_WEIGHT_CAP;
    public static final ForgeConfigSpec.ConfigValue<String> OVERFLOW_OVERWRITE_SCOPE;
    public static final ForgeConfigSpec.BooleanValue OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE;
    public static final ForgeConfigSpec.LongValue OVERFLOW_RANDOM_SEED;

    public static final ForgeConfigSpec.BooleanValue NBT_SCAN_NBT_VARIANTS;
    public static final ForgeConfigSpec.IntValue NBT_MAX_NBT_VARIANTS_PER_ITEM;

    public static final ForgeConfigSpec.BooleanValue DIAGNOSTIC_DEBUG_LOGGING;
    public static final ForgeConfigSpec.BooleanValue DIAGNOSTIC_GENERATE_REPORTS;

    public static final ForgeConfigSpec.IntValue SCHEMA_VERSION;

    static {
        // [general]
        BUILDER.comment("General operation switches.").push("general");
        GENERAL_ENABLED = BUILDER.comment(
                        "Master switch for the scan service.",
                        "When false no ScanManager is created: startup auto scanning and manual",
                        "/sudoemc rescan-all are both unavailable.")
                .define("enabled", true);
        GENERAL_ALWAYS_RESCAN = BUILDER.comment(
                        "Rescan all items on every server start instead of reusing cached state.",
                        "Only takes effect when [general] enabled is true.")
                .define("alwaysRescan", false);
        BUILDER.pop();

        // [performance]
        BUILDER.comment("Scanning and processing performance.").push("performance");
        PERFORMANCE_ITEMS_PER_TICK = BUILDER.comment(
                        "Maximum items processed on the server thread per tick.")
                .defineInRange("itemsPerTick", 100, 1, Integer.MAX_VALUE);
        PERFORMANCE_ADAPTIVE_BATCH_SIZING = BUILDER.comment(
                        "Adjust batch sizes based on observed processing time.")
                .define("adaptiveBatchSizing", true);
        BUILDER.pop();

        // [recipe]
        BUILDER.comment("Recipe-derived pricing.").push("recipe");
        RECIPE_SOLVE_RECIPES = BUILDER.comment("Derive EMC values from crafting recipes.")
                .define("solveRecipes", true);
        RECIPE_MAX_RECIPE_PASSES = BUILDER.comment(
                        "Maximum number of recipe solving passes per scan.")
                .defineInRange("maxRecipePasses", 20, 1, Integer.MAX_VALUE);
        RECIPE_ALLOW_RECIPE_VALUE_INCREASE = BUILDER.comment(
                        "Allow a recipe-derived value to be higher than the sum of its inputs.")
                .define("allowRecipeValueIncrease", false);
        BUILDER.pop();

        // [fallback]
        BUILDER.comment("Fallback pricing sources.").push("fallback");
        FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE = BUILDER.comment(
                        "Use a generic fallback value for items no other source can price.")
                .define("useFallbackForUnsolvable", true);
        FALLBACK_USE_TAG_PRICING = BUILDER.comment(
                        "Derive values from item tags and material relationships.",
                        "Category anchors are conservative low-tier floors (INGOT 64, STORAGE_BLOCK 576),",
                        "not per-material values; cheap renewable materials can still be overvalued",
                        "relative to ProjectE values. Use ProjectE custom values for exact pricing.")
                .define("useTagPricing", true);
        FALLBACK_USE_RARITY_PRICING = BUILDER.comment("Derive values from item rarity.")
                .define("useRarityPricing", true);
        FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES = BUILDER.comment(
                        "Use ProjectE integration baselines when available.",
                        "Phase 15 wires this switch into ScanPipeline; the actual baseline",
                        "data source is introduced in Phase 25. False explicitly passes 0.")
                .define("useProjectEIntegrationBaselines", true);
        FALLBACK_MIN_EMC = BUILDER.comment(
                        "Minimum EMC value for fallback pricing; lower candidates are discarded.",
                        "Also passed to the recipe solver as its minimum output value.")
                .defineInRange("minEmc", 1L, 0L, Long.MAX_VALUE);
        BUILDER.pop();

        // [safety]
        BUILDER.comment("Safety guards for generated values.").push("safety");
        SAFETY_LARGE_DROP_PROTECTION = BUILDER.comment(
                        "Protect against unexpectedly large decreases in generated values.")
                .define("largeDropProtection", true);
        SAFETY_MAX_ALLOWED_DROP_RATIO = BUILDER.comment(
                        "Largest allowed relative drop in a generated value, in (0, 1].",
                        "0.0 is rejected because it would quarantine every nonzero drop.")
                .defineInRange("maxAllowedDropRatio", 0.5D, Double.MIN_VALUE, 1.0D);
        SAFETY_PROTECT_CREATIVE_ONLY_ITEMS = BUILDER.comment(
                        "Do not generate survival-obtainable values for creative-only items.")
                .define("protectCreativeOnlyItems", true);
        SAFETY_CREATIVE_ITEM_EXCLUSIONS = BUILDER.comment(
                        "Exact item ids that must never be auto-priced (format: namespace:path).")
                .defineListAllowEmpty("creativeItemExclusions", List.of(),
                        entry -> entry instanceof String);
        SAFETY_CREATIVE_ITEM_SPECIAL_VALUES = BUILDER.comment(
                        "Fixed EMC values that override normal solving (format: namespace:path=positive long).")
                .defineListAllowEmpty("creativeItemSpecialValues", List.of(),
                        entry -> entry instanceof String);
        SAFETY_CREATIVE_NAMESPACES = BUILDER.comment(
                        "Namespaces whose items are treated as creative-only and never auto-priced.")
                .defineListAllowEmpty("creativeNamespaces", List.of(),
                        entry -> entry instanceof String);
        BUILDER.pop();

        // [overflow]
        BUILDER.comment("Economic ceiling and weighted reset behavior.").push("overflow");
        OVERFLOW_ENABLED = BUILDER.comment(
                        "Replace values above the economic ceiling with weighted random resets.")
                .define("enabled", true);
        OVERFLOW_THRESHOLD = BUILDER.comment(
                        "Values strictly greater than this threshold are reset; must be > resetMax.")
                .defineInRange("threshold", 999_999L, 1L, Long.MAX_VALUE);
        OVERFLOW_RESET_MIN = BUILDER.comment(
                        "Inclusive lower bound of the reset range; must be >= 1.")
                .defineInRange("resetMin", 100L, 1L, Long.MAX_VALUE);
        OVERFLOW_RESET_MAX = BUILDER.comment(
                        "Inclusive upper bound of the reset range; must be >= resetMin and < threshold.")
                .defineInRange("resetMax", 999_000L, 1L, Long.MAX_VALUE);
        OVERFLOW_BUCKET_COUNT = BUILDER.comment(
                        "Number of buckets used by the weighted reset sampler; must be >= 1.")
                .defineInRange("bucketCount", 100, 1, Integer.MAX_VALUE);
        OVERFLOW_WEIGHT_CAP = BUILDER.comment(
                        "Maximum weight of a single sampler bucket; must be >= 1.")
                .defineInRange("weightCap", 20, 1, Integer.MAX_VALUE);
        OVERFLOW_OVERWRITE_SCOPE = BUILDER.comment(
                        "Which values overflow protection may reset.",
                        "owned_only: only entries generated and owned by SudoersEMC",
                        "entire_custom_emc: also scan ProjectE's config/ProjectE/custom_emc.json for entries owned by SudoersEMC",
                        "Administrator-configured (non-owned) entries are never rewritten.",
                        "Unknown values fall back to owned_only.")
                .define("overwriteScope", "owned_only");
        OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE = BUILDER.comment(
                        "Explicit opt-in required before SudoersEMC-owned entries in ProjectE's custom_emc.json may be rewritten;",
                        "administrator-configured (non-owned) entries are never rewritten.")
                .define("allowProjectEFileRewrite", false);
        OVERFLOW_RANDOM_SEED = BUILDER.comment(
                        "Sampler seed; 0 requests a fresh random seed at startup.")
                .defineInRange("randomSeed", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        BUILDER.pop();

        // [nbt]
        BUILDER.comment("ItemStack NBT variant scanning.").push("nbt");
        NBT_SCAN_NBT_VARIANTS = BUILDER.comment(
                        "Scan economically relevant NBT variants of items.",
                        "Consumed by the Phase 17 variant pipeline; disabling it skips variant generation.")
                .define("scanNbtVariants", true);
        // Upper bound mirrors VariantScanner.HARD_VARIANT_CEILING; a larger spec value would be
        // silently clamped by the consumer, so reject it at the config boundary instead.
        NBT_MAX_NBT_VARIANTS_PER_ITEM = BUILDER.comment(
                        "Maximum number of NBT variants scanned per item, in [1, 1000].",
                        "Consumed by the Phase 17 variant pipeline; larger values are rejected at this boundary.")
                .defineInRange("maxNbtVariantsPerItem", 100, 1, 1000);
        BUILDER.pop();

        // [diagnostic]
        BUILDER.comment("Diagnostic logging and reports.").push("diagnostic");
        DIAGNOSTIC_DEBUG_LOGGING = BUILDER.comment("Enable verbose diagnostic logging.",
                        "Reserved for Phase 25; this Phase 16 build does not read this key yet.")
                .define("debugLogging", false);
        DIAGNOSTIC_GENERATE_REPORTS = BUILDER.comment("Write diagnostic reports after scans.")
                .define("generateReports", true);
        BUILDER.pop();

        // Root-level schema version. A missing key is treated as legacy v1 by
        // ConfigFileMigration; newly generated files start at the current version.
        SCHEMA_VERSION = BUILDER.comment(
                        "配置 schema 版本，请勿手动修改。当前构建支持 "
                                + ConfigMigrations.SCHEMA_VERSION + "。")
                .defineInRange("schemaVersion",
                        ConfigMigrations.SCHEMA_VERSION,
                        0,
                        Integer.MAX_VALUE);
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private static final ConfigWatcher CONFIG_WATCHER = new ConfigWatcher();

    /** Built-in conservative settings used until the first valid config snapshot is applied. */
    private static final OverflowProtection.Settings SAFE_DEFAULT_OVERFLOW_SETTINGS =
            new OverflowProtection.Settings();

    /**
     * 配置读取与快照发布的互斥锁。
     *
     * <p>Forge 的文件监听线程会先 {@code acceptConfig} 再派发 {@code ModConfigEvent}；
     * {@code /sudoemc reload} 也会在命令线程重读文件。两者都通过本锁串行化“接受配置 + 一次性
     * 读取所有键 + 校验 + 发布快照”的过程，避免重建拿到半新半旧的配置。</p>
     */
    private static final Object SNAPSHOT_LOCK = new Object();

    private static final AtomicLong SNAPSHOT_GENERATION = new AtomicLong();

    /** 最近一次通过完整校验的不可变运行时快照；{@code null} 表示尚未成功捕获。 */
    private static volatile RuntimeConfigSnapshot activeSnapshot;

    private static volatile ConfigValidator.Values activeValues;

    private static volatile ConfigValidationResult lastValidationResult = ConfigValidationResult.ok();

    /**
     * 一次 {@code reloadFromDisk()} 已经捕获好快照时，把快照传给同步派发的 ModConfigEvent
     * 处理器，避免同一个重载动作重复读取 Forge 值并产生两个代次。
     */
    private static final ThreadLocal<PreparedDispatch> PREPARED_DISPATCH = new ThreadLocal<>();

    private static boolean registered;

    private SudoersemcConfig() {
    }

    /**
     * Registers the common configuration spec and the load/reload validation hook.
     *
     * <p>Safe to call more than once; only the first call performs registration.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // Forge parses the TOML after registerConfig(); migrate legacy schema before that.
        ConfigFileMigration.migrate(commonConfigPath());
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(SudoersemcConfig::onConfigLoading);
        modEventBus.addListener(SudoersemcConfig::onConfigReloading);
    }

    /**
     * 注册配置快照监听器。
     *
     * <p>只有完整配置通过 {@link ConfigValidator#validate(ConfigValidator.Values)} 后才会
     * 触发。入口层用它把重建请求调度到服务器线程；监听器本身不得直接访问服务器或世界状态。</p>
     *
     * @param listener 监听器，不能为 {@code null}
     */
    public static void addReloadListener(ConfigWatcher.RebuildListener listener) {
        CONFIG_WATCHER.register(listener);
    }

    /**
     * @return 配置重载协调器，入口层可注册重建监听器并读取最近一次校验结果
     */
    public static ConfigWatcher configWatcher() {
        return CONFIG_WATCHER;
    }

    /**
     * @return 最近一次通过完整校验并发布的不可变快照；首次有效加载前为 {@code null}
     */
    public static RuntimeConfigSnapshot activeSnapshot() {
        return activeSnapshot;
    }

    /**
     * Returns the last validated overflow settings snapshot.
     *
     * <p>运行时组件应优先使用 {@link #activeSnapshot()}；本方法保留给仍需单独读取溢出配置的
     * 兼容路径。任何情况下都不会返回未通过校验的值。</p>
     *
     * @throws IllegalStateException when the config has not been loaded yet
     */
    public static OverflowProtection.Settings overflowSettings() {
        RuntimeConfigSnapshot snapshot = activeSnapshot;
        if (snapshot != null) {
            return snapshot.overflowSettings();
        }
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("SudoersemcConfig is not loaded yet");
        }
        return SAFE_DEFAULT_OVERFLOW_SETTINGS;
    }

    /**
     * 一次性读取当前 Forge 值、完成完整校验并发布不可变快照。
     *
     * <p>这是运行时组件唯一允许的配置读取入口。所有键在 {@link #SNAPSHOT_LOCK} 内只读取一次，
     * 随后全部派生设置都由同一组普通值构造；读取过程中不会把 {@code ForgeConfigSpec} 值交给
     * 任何运行时对象。校验失败时保留上一份有效快照并返回失败结果。</p>
     *
     * @return 捕获结果；失败时 {@link SnapshotCapture#snapshot()} 为 {@code null}
     */
    public static SnapshotCapture captureSnapshot() {
        synchronized (SNAPSHOT_LOCK) {
            return captureSnapshotLocked();
        }
    }

    private static SnapshotCapture captureSnapshotLocked() {
        if (!SPEC.isLoaded()) {
            ConfigValidationResult failure = ConfigValidationResult.failed(
                    List.of("配置尚未加载"), List.of());
            lastValidationResult = failure;
            return new SnapshotCapture(failure, null);
        }

        try {
            // One read per key. Runtime components must never consult the live spec again.
            int itemsPerTick = PERFORMANCE_ITEMS_PER_TICK.get();
            int maxRecipePasses = RECIPE_MAX_RECIPE_PASSES.get();
            long minEmc = FALLBACK_MIN_EMC.get();
            boolean allowRecipeValueIncrease = RECIPE_ALLOW_RECIPE_VALUE_INCREASE.get();
            boolean overflowEnabled = OVERFLOW_ENABLED.get();
            long overflowThreshold = OVERFLOW_THRESHOLD.get();
            long resetMin = OVERFLOW_RESET_MIN.get();
            long resetMax = OVERFLOW_RESET_MAX.get();
            int bucketCount = OVERFLOW_BUCKET_COUNT.get();
            int weightCap = OVERFLOW_WEIGHT_CAP.get();
            String overwriteScope = OVERFLOW_OVERWRITE_SCOPE.get();
            boolean allowProjectEFileRewrite = OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get();
            long overflowRandomSeed = OVERFLOW_RANDOM_SEED.get();
            int maxNbtVariantsPerItem = NBT_MAX_NBT_VARIANTS_PER_ITEM.get();
            boolean nbtScanVariants = NBT_SCAN_NBT_VARIANTS.get();
            double maxAllowedDropRatio = SAFETY_MAX_ALLOWED_DROP_RATIO.get();
            boolean largeDropProtection = SAFETY_LARGE_DROP_PROTECTION.get();
            boolean protectCreativeOnlyItems = SAFETY_PROTECT_CREATIVE_ONLY_ITEMS.get();
            List<String> creativeExclusions = List.copyOf(SAFETY_CREATIVE_ITEM_EXCLUSIONS.get());
            List<String> creativeSpecialValues =
                    List.copyOf(SAFETY_CREATIVE_ITEM_SPECIAL_VALUES.get());
            List<String> creativeNamespaces = List.copyOf(SAFETY_CREATIVE_NAMESPACES.get());
            CreativeItemProtection.ParsedSettings parsedCreative = CreativeItemProtection.parseSettings(
                    protectCreativeOnlyItems, creativeExclusions, creativeSpecialValues,
                    creativeNamespaces);
            if (!parsedCreative.skippedEntries().isEmpty()) {
                LOGGER.warn("[config] 创造物品保护配置有 {} 条无效条目已被忽略：{}",
                        parsedCreative.skippedEntries().size(), parsedCreative.skippedEntries());
            }
            boolean useTagPricing = FALLBACK_USE_TAG_PRICING.get();
            boolean useRarityPricing = FALLBACK_USE_RARITY_PRICING.get();
            boolean useFallbackForUnsolvable = FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE.get();
            boolean solveRecipes = RECIPE_SOLVE_RECIPES.get();
            boolean generateReports = DIAGNOSTIC_GENERATE_REPORTS.get();
            boolean adaptiveBatchSizing = PERFORMANCE_ADAPTIVE_BATCH_SIZING.get();
            boolean generalEnabled = GENERAL_ENABLED.get();
            boolean alwaysRescan = GENERAL_ALWAYS_RESCAN.get();

            ConfigValidator.Values values = new ConfigValidator.Values(
                    itemsPerTick,
                    maxRecipePasses,
                    minEmc,
                    overflowEnabled,
                    overflowThreshold,
                    resetMin,
                    resetMax,
                    bucketCount,
                    weightCap,
                    overwriteScope,
                    allowProjectEFileRewrite,
                    maxNbtVariantsPerItem,
                    maxAllowedDropRatio,
                    creativeExclusions,
                    creativeSpecialValues,
                    creativeNamespaces);
            ConfigValidationResult validation = ConfigValidator.validate(values);
            lastValidationResult = validation;
            if (!validation.valid()) {
                LOGGER.warn("[config] 配置快照校验失败，保留上一份有效快照：\n{}", validation.format());
                return new SnapshotCapture(validation, null);
            }

            long generation = SNAPSHOT_GENERATION.incrementAndGet();
            RuntimeConfigSnapshot snapshot = new RuntimeConfigSnapshot(
                    generation,
                    values,
                    new OverflowProtection.Settings(
                            overflowEnabled,
                            overflowThreshold,
                            resetMin,
                            resetMax,
                            bucketCount,
                            weightCap,
                            ConfigValidator.parseScope(overwriteScope),
                            allowProjectEFileRewrite,
                            overflowRandomSeed),
                    new LargeDropProtection.Settings(
                            largeDropProtection,
                            maxAllowedDropRatio,
                            Set.of(),
                            Set.of()),
                    parsedCreative.settings(),
                    new PricingSettings(
                            useTagPricing,
                            useRarityPricing,
                            true,
                            useFallbackForUnsolvable,
                            PricingSettings.DEFAULT_GENERIC_FALLBACK_VALUE),
                    new SolveSettings(
                            Math.max(1, maxRecipePasses),
                            allowRecipeValueIncrease,
                            minEmc,
                            SolveSettings.DEFAULT_MAX_ALTERNATIVE_COSTS),
                    itemsPerTick,
                    adaptiveBatchSizing,
                    generalEnabled,
                    alwaysRescan,
                    solveRecipes,
                    generateReports,
                    nbtScanVariants,
                    maxNbtVariantsPerItem);

            activeValues = values;
            activeSnapshot = snapshot;
            LOGGER.debug("[config] 已捕获并发布配置快照 generation={}", generation);
            return new SnapshotCapture(validation, snapshot);
        } catch (RuntimeException failure) {
            ConfigValidationResult result = ConfigValidationResult.failed(
                    List.of("无法读取当前配置: " + describe(failure)), List.of());
            lastValidationResult = result;
            LOGGER.warn("[config] 捕获配置快照失败，保留上一份有效快照", failure);
            return new SnapshotCapture(result, null);
        }
    }

    /**
     * Builds a plain-Java validation snapshot from the current Forge values.
     *
     * <p>{@code minEmc} 来自 {@code fallback.minEmc}，同时作为兜底定价下限与求解器最小输出值。
     * 三个创造物品保护列表只做原始读取；格式校验警告由 {@link ConfigValidator} 汇总。</p>
     *
     * @throws IllegalStateException when the config has not been loaded yet
     */
    public static ConfigValidator.Values validationValues() {
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("SudoersemcConfig is not loaded yet");
        }
        return new ConfigValidator.Values(
                PERFORMANCE_ITEMS_PER_TICK.get(),
                RECIPE_MAX_RECIPE_PASSES.get(),
                FALLBACK_MIN_EMC.get(),
                OVERFLOW_ENABLED.get(),
                OVERFLOW_THRESHOLD.get(),
                OVERFLOW_RESET_MIN.get(),
                OVERFLOW_RESET_MAX.get(),
                OVERFLOW_BUCKET_COUNT.get(),
                OVERFLOW_WEIGHT_CAP.get(),
                OVERFLOW_OVERWRITE_SCOPE.get(),
                OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get(),
                NBT_MAX_NBT_VARIANTS_PER_ITEM.get(),
                SAFETY_MAX_ALLOWED_DROP_RATIO.get(),
                List.copyOf(SAFETY_CREATIVE_ITEM_EXCLUSIONS.get()),
                List.copyOf(SAFETY_CREATIVE_ITEM_SPECIAL_VALUES.get()),
                List.copyOf(SAFETY_CREATIVE_NAMESPACES.get()));
    }

    /**
     * @return the most recent validation result; starts as a valid, warning-free result
     */
    public static ConfigValidationResult lastValidationResult() {
        return lastValidationResult;
    }

    /**
     * @return the last snapshot that passed validation and became active; {@code null} before the
     *         first valid load
     */
    public static ConfigValidator.Values activeValues() {
        return activeValues;
    }

    /**
     * 一次配置快照捕获结果。
     *
     * @param validation 本次读取的完整校验结果
     * @param snapshot   校验通过时发布的不可变快照；失败时为 {@code null}
     */
    public record SnapshotCapture(ConfigValidationResult validation,
                                  @Nullable RuntimeConfigSnapshot snapshot) {

        public SnapshotCapture {
            Objects.requireNonNull(validation, "validation");
        }

        /**
         * @return 本次捕获是否产出了可用快照
         */
        public boolean hasSnapshot() {
            return snapshot != null;
        }
    }

    /**
     * {@code reloadFromDisk()} 与同步派发的 {@code ModConfigEvent.Reloading} 之间传递的
     * 已捕获快照，避免一个重载动作读取两次配置。
     */
    private record PreparedDispatch(RuntimeConfigSnapshot snapshot,
                                    ConfigValidationResult validation) {
    }

    /**
     * Validates the values currently held by the loaded spec.
     *
     * @return validation result; never throws
     */
    public static ConfigValidationResult validateCurrent() {
        try {
            return ConfigValidator.validate(validationValues());
        } catch (RuntimeException failure) {
            return ConfigValidationResult.failed(
                    List.of("无法读取当前配置: " + describe(failure)), List.of());
        }
    }

    /**
     * Re-reads the common config file from disk and applies it to the live spec.
     *
     * <p>This mirrors Forge's own {@code ConfigFileTypeHandler.ConfigWatcher}: the existing
     * {@link CommentedFileConfig} bound to the spec is reloaded, corrected when necessary,
     * accepted by the spec and followed by {@code afterReload()}. A
     * {@code ModConfigEvent.Reloading} event is dispatched on the mod event bus so listeners
     * observe the same lifecycle as a file-watch reload.</p>
     *
     * <p>接受配置、一次性读取全部键、校验与发布快照都在 {@link #SNAPSHOT_LOCK} 内完成，因此
     * 文件监听线程和命令重载不会把两个版本的配置混进同一份快照。事件派发时通过
     * {@link #PREPARED_DISPATCH} 复用已捕获快照，避免重复读取。</p>
     *
     * @return validation result for the values read from disk; never throws
     */
    public static ConfigValidationResult reloadFromDisk() {
        if (!SPEC.isLoaded()) {
            return ConfigValidationResult.failed(List.of("配置尚未加载"), List.of());
        }
        ModConfig config = findConfig();
        if (config == null) {
            return ConfigValidationResult.failed(List.of("配置尚未注册"), List.of());
        }

        try {
            SnapshotCapture capture;
            synchronized (SNAPSHOT_LOCK) {
                CommentedConfig data = config.getConfigData();
                if (data == null) {
                    return ConfigValidationResult.failed(List.of("配置文件尚未读取"), List.of());
                }
                if (data instanceof CommentedFileConfig fileConfig) {
                    fileConfig.load();
                    if (!SPEC.isCorrect(fileConfig)) {
                        LOGGER.warn("[config] {} 不满足当前配置结构，已备份并修正", fileConfig.getFile());
                        ConfigFileTypeHandler.backUpConfig(fileConfig);
                        SPEC.correct(fileConfig);
                        fileConfig.save();
                    }
                }
                SPEC.acceptConfig(data);
                SPEC.afterReload();
                capture = captureSnapshotLocked();
            }

            if (capture.hasSnapshot()) {
                PREPARED_DISPATCH.set(new PreparedDispatch(capture.snapshot(), capture.validation()));
                try {
                    dispatchReloading(config);
                } finally {
                    PREPARED_DISPATCH.remove();
                }
            }
            return capture.validation();
        } catch (RuntimeException failure) {
            LOGGER.warn("[config] 重新读取配置文件失败，继续使用上一份有效配置", failure);
            return ConfigValidationResult.failed(
                    List.of("配置重载失败: " + describe(failure)), List.of());
        }
    }

    private static ModConfig findConfig() {
        for (ModConfig candidate : ConfigTracker.INSTANCE.configSets()
                .getOrDefault(ModConfig.Type.COMMON, Set.of())) {
            if (candidate.getSpec() == SPEC) {
                return candidate;
            }
        }
        return null;
    }

    private static void dispatchReloading(ModConfig config) {
        ModList.get().getModContainerById(Sudoersemc.MODID)
                .ifPresent(container -> container.dispatchConfigEvent(IConfigEvent.reloading(config)));
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /**
     * Parses the {@code overflow.overwriteScope} string. Unknown values fall back to
     * {@link OverflowScope#OWNED_ONLY} and log a warning.
     */
    public static OverflowScope parseOverflowScope(String rawValue) {
        if (rawValue != null) {
            String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
            if ("owned_only".equals(normalized)) {
                return OverflowScope.OWNED_ONLY;
            }
            if ("entire_custom_emc".equals(normalized)) {
                return OverflowScope.ENTIRE_CUSTOM_EMC;
            }
            LOGGER.warn(
                    "[overflow] Unknown overwriteScope '{}'; expected 'owned_only' or 'entire_custom_emc'. "
                            + "Falling back to 'owned_only'.",
                    rawValue);
        }
        return OverflowScope.OWNED_ONLY;
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            handleConfigEvent("loading");
        }
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            handleConfigEvent("reloading");
        }
    }

    /**
     * 在配置加载线程上捕获并发布完整快照，然后通知 {@link ConfigWatcher}。
     *
     * <p>Forge 可能在配置加载线程触发这些事件。本方法只做一次完整读取、校验与 volatile
     * 快照交换，绝不访问服务器、世界或扫描状态；真正的组件重建由入口层调度回服务器线程。
     * 只有校验通过的结果才会通知监听器，校验失败时上一份有效快照继续生效。</p>
     *
     * <p>由 {@link #reloadFromDisk()} 同步派发的事件会携带预先捕获的快照，直接复用，
     * 不会为同一个重载动作再次读取 Forge 值。</p>
     */
    private static void handleConfigEvent(String phase) {
        if (!SPEC.isLoaded()) {
            return;
        }

        PreparedDispatch prepared = PREPARED_DISPATCH.get();
        if (prepared != null && prepared.snapshot() == activeSnapshot) {
            CONFIG_WATCHER.fireReload(prepared.validation());
            return;
        }

        SnapshotCapture capture = captureSnapshot();
        lastValidationResult = capture.validation();
        if (!capture.hasSnapshot()) {
            LOGGER.warn("[config] 配置事件（{}）未产生有效快照，保留上一份有效配置", phase);
        }
        CONFIG_WATCHER.fireReload(capture.validation());
    }

    private static Path commonConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("sudoersemc-common.toml");
    }
}
