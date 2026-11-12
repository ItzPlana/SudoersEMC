package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.config.SudoersemcConfig;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.OptionalIntegrationManager;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeScanner;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.solver.EvaluatorRegistry;
import com.itzplana.sudoersemc.solver.RecipeSolver;
import com.itzplana.sudoersemc.solver.SolveContext;
import com.itzplana.sudoersemc.solver.SolveResult;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.solver.SolveTrace;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.JsonFileIO;
import com.itzplana.sudoersemc.storage.SudoersemcPaths;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 7 的生产扫描管线：把分片扫描、配方求解、结果暂存与状态持久化接到
 * {@link ScanManager} 上。
 *
 * <p>本类补上了复审指出的生产接线缺口：{@code Sudoersemc} 在服务器启动时创建本管线，
 * 并把 {@link #collector(MinecraftServer)}、{@link #apply(ScanManager.SnapshotData)}、
 * {@link #fingerprintSupplier(MinecraftServer)} 与 {@link #persist()} 注入状态机。采集阶段
 * 使用 {@link ItemScanner#beginSession()} 与 {@link RecipeScanner#beginSession} 返回的真实游标，
 * 每个 tick 只检查配置批量允许的条目数，不会在单个 tick 内完成全量注册表/配方遍历。</p>
 *
 * <p><strong>增量扫描：</strong>扫描成功后会把本次指纹写入
 * {@code config/sudoersemc/scan_state.json}。服务器启动时若当前指纹与文件中的指纹一致且
 * {@code general.alwaysRescan=false}，则跳过重复扫描；这与 PR 中“指纹一致即可跳过重新扫描”
 * 的承诺一致。</p>
 *
 * <p><strong>线程约定：</strong>所有公开方法都在服务器线程调用。{@link #apply} 与
 * {@link #persist()} 由 {@link ScanManager} 的 tick 驱动；{@link #shouldScan} 在服务器启动事件中
 * 调用。本类自身不创建线程。</p>
 */
public final class ScanCollectionPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanCollectionPipeline");

    /** 扫描状态文件名，与后续阶段的 ScanStateStore 保持兼容。 */
    public static final String STATE_FILE_NAME = "scan_state.json";

    /** 当前状态文件格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    private final GeneratedEntryStore store;
    private final ItemScanner itemScanner;
    private final RecipeScanner recipeScanner;
    private final Path stateFile;

    private final AtomicReference<CollectedSnapshot> stashed = new AtomicReference<>();
    private final Object stateLock = new Object();

    private StateFile state;
    private boolean stateLoaded;

    private volatile ScanFingerprint lastAppliedFingerprint;
    private volatile long lastDurationMillis;
    private volatile long lastSolvedCount;
    private volatile long lastUnresolvedCount;

    /**
     * 生产构造器：使用 ProjectE 后端查询、Goety 可选读取器与默认状态文件。
     *
     * @param store 生成值存储；{@code null} 表示干跑模式，不写入任何条目
     */
    public ScanCollectionPipeline(GeneratedEntryStore store) {
        this(store, createItemScanner(), RecipeScanner.withGoety().orElseGet(RecipeScanner::new),
                SudoersemcPaths.file(STATE_FILE_NAME));
    }

    /**
     * 测试/嵌入式构造器。
     *
     * @param store        生成值存储，允许为 {@code null}
     * @param itemScanner  物品扫描器，允许为 {@code null}（仅测试 apply/persist 时）
     * @param recipeScanner 配方扫描器，允许为 {@code null}（仅测试 apply/persist 时）
     * @param stateFile    状态文件路径
     */
    ScanCollectionPipeline(GeneratedEntryStore store,
                           ItemScanner itemScanner,
                           RecipeScanner recipeScanner,
                           Path stateFile) {
        this.store = store;
        this.itemScanner = itemScanner;
        this.recipeScanner = recipeScanner;
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
    }

    /**
     * 创建绑定到当前服务器的分片采集器工厂。
     *
     * @param server 当前服务器，不能为 {@code null}
     * @return 每次扫描创建新游标的采集器
     */
    public ScanManager.SnapshotCollector collector(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        if (itemScanner == null || recipeScanner == null) {
            throw new IllegalStateException("扫描管线未配置物品/配方扫描器");
        }
        return () -> new CollectionSession(server);
    }

    /**
     * 创建生产指纹提供器。
     *
     * @param server 当前服务器，不能为 {@code null}
     * @return 在服务器线程计算注册表/配方/标签/配置/基线指纹的回调
     */
    public ScanManager.FingerprintSupplier fingerprintSupplier(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return () -> computeFingerprint(server);
    }

    /**
     * 把采集阶段暂存的快照交给配方求解器，并把生成值写入 {@link GeneratedEntryStore}。
     *
     * <p>该方法只使用纯数据，不访问注册表；必须在服务器线程调用（由
     * {@link ScanManager} 的 APPLYING 阶段驱动）。没有暂存快照时抛出
     * {@link IllegalStateException}，由状态机转为 {@link ScanPhase#FAILED}。</p>
     *
     * @param data 采集阶段发布的计数快照，仅用于契约校验
     */
    public void apply(ScanManager.SnapshotData data) {
        Objects.requireNonNull(data, "data");
        CollectedSnapshot snapshot = stashed.getAndSet(null);
        if (snapshot == null) {
            throw new IllegalStateException("应用阶段没有暂存的扫描快照");
        }
        applySnapshot(snapshot);
    }

    /**
     * 纯数据应用入口，供单元测试直接构造快照验证求解/写入。
     *
     * @param snapshot 采集结果
     */
    void applySnapshot(CollectedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        long startedNanos = System.nanoTime();

        Map<ItemKey, Long> existingValues = new TreeMap<>();
        for (ItemSnapshot item : snapshot.items()) {
            if (item.hasExistingEmc() && item.existingValue() > 0L) {
                existingValues.put(item.key(), item.existingValue());
            }
        }

        SolveContext context = new SolveContext(existingValues);
        SolveResult result = new RecipeSolver().solve(
                snapshot.recipes(), context, EvaluatorRegistry.defaults(), SolveSettings.defaults());

        int staged = 0;
        long now = System.currentTimeMillis();
        for (Map.Entry<ItemKey, Long> entry : result.values().entrySet()) {
            SolveTrace trace = result.traces().get(entry.getKey());
            ResourceLocation recipeId = trace == null ? null : trace.recipeId();
            GeneratedEmcEntry generated = new GeneratedEmcEntry(
                    entry.getKey(), entry.getValue(), PriceSource.RECIPE, recipeId, true, now);
            if (store == null || store.stage(generated)) {
                staged++;
            }
        }

        lastAppliedFingerprint = snapshot.fingerprint();
        lastDurationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
        lastSolvedCount = staged;
        lastUnresolvedCount = result.unresolvedItems().size();
    }

    /**
     * 持久化扫描状态与生成值。由 {@link ScanManager.SaveHandler} 在 SAVING 阶段调用。
     *
     * <p>没有成功应用过扫描时直接返回；否则把指纹与统计写入 {@code scan_state.json}，
     * 并在有生成值时落盘 {@link GeneratedEntryStore}。</p>
     */
    public void persist() {
        ScanFingerprint fingerprint = lastAppliedFingerprint;
        if (fingerprint == null) {
            return;
        }

        StateFile dto = new StateFile();
        dto.schemaVersion = SCHEMA_VERSION;
        dto.fingerprint = FingerprintDto.from(fingerprint);
        dto.lastScanEpochMillis = System.currentTimeMillis();
        dto.lastScanDurationMillis = Math.max(0L, lastDurationMillis);
        dto.lastSolvedCount = saturatingInt(lastSolvedCount);
        dto.lastUnresolvedCount = saturatingInt(lastUnresolvedCount);

        synchronized (stateLock) {
            state = dto;
            stateLoaded = true;
        }
        JsonFileIO.writeAtomically(stateFile, dto);

        if (store != null) {
            store.saveIfDirty();
        }
    }

    /**
     * 判断服务器启动时是否需要发起一次扫描。
     *
     * @param server       当前服务器
     * @param alwaysRescan {@code general.alwaysRescan} 的值
     * @return 需要扫描返回 {@code true}；指纹与上次一致且未强制重扫时返回 {@code false}
     */
    public boolean shouldScan(MinecraftServer server, boolean alwaysRescan) {
        Objects.requireNonNull(server, "server");
        if (alwaysRescan) {
            return true;
        }
        return shouldScan(computeFingerprint(server), false);
    }

    /**
     * 纯指纹判定入口，供单元测试直接验证增量跳过逻辑。
     *
     * @param current      当前输入指纹
     * @param alwaysRescan 是否强制重扫
     * @return 需要扫描返回 {@code true}
     */
    boolean shouldScan(ScanFingerprint current, boolean alwaysRescan) {
        if (alwaysRescan) {
            return true;
        }
        ScanFingerprint stored = storedFingerprint();
        return stored == null || !stored.equalsFingerprint(current);
    }

    /**
     * 计算当前扫描输入指纹。物品/配方/标签来自实时服务器，配置与基线来自静态配置和集成状态。
     *
     * @param server 当前服务器，不能为 {@code null}
     * @return 五通道指纹
     */
    public ScanFingerprint computeFingerprint(MinecraftServer server) {
        Objects.requireNonNull(server, "server");

        List<String> items = new ArrayList<>(BuiltInRegistries.ITEM.keySet().size());
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            items.add(id.toString());
        }

        List<String> recipes = new ArrayList<>();
        try {
            server.getRecipeManager().getRecipeIds().forEach(id -> recipes.add(id.toString()));
        } catch (RuntimeException e) {
            recipes.add("unavailable");
        }

        List<String> tags = new ArrayList<>();
        try {
            BuiltInRegistries.ITEM.getTagNames()
                    .forEach(tag -> tags.add(tag.location().toString()));
        } catch (RuntimeException e) {
            tags.add("unavailable");
        }

        return ScanFingerprint.of(items, recipes, tags, configEntries(), baselineEntries());
    }

    /**
     * @return 最近一次成功应用的指纹；从未应用过时为 {@code null}
     */
    ScanFingerprint lastAppliedFingerprint() {
        return lastAppliedFingerprint;
    }

    /**
     * @return 最近一次求解后实际写入（或干跑模式下本应写入）的条目数
     */
    long lastSolvedCount() {
        return lastSolvedCount;
    }

    /**
     * @return 最近一次求解后仍未解析的物品数
     */
    long lastUnresolvedCount() {
        return lastUnresolvedCount;
    }

    private ScanFingerprint storedFingerprint() {
        StateFile loaded;
        synchronized (stateLock) {
            if (!stateLoaded) {
                state = JsonFileIO.read(stateFile, StateFile.class);
                stateLoaded = true;
                if (state != null && state.schemaVersion > SCHEMA_VERSION) {
                    LOGGER.warn("{} 使用不支持的 schemaVersion {}（当前支持 {}），忽略其指纹",
                            stateFile, state.schemaVersion, SCHEMA_VERSION);
                    state = null;
                }
            }
            loaded = state;
        }
        return loaded == null || loaded.fingerprint == null ? null : loaded.fingerprint.toFingerprint();
    }

    private static List<String> configEntries() {
        List<String> entries = new ArrayList<>();
        entries.add("general.enabled=" + configValue(SudoersemcConfig.GENERAL_ENABLED));
        entries.add("general.alwaysRescan=" + configValue(SudoersemcConfig.GENERAL_ALWAYS_RESCAN));
        entries.add("performance.itemsPerTick=" + configValue(SudoersemcConfig.PERFORMANCE_ITEMS_PER_TICK));
        entries.add("performance.adaptiveBatchSizing="
                + configValue(SudoersemcConfig.PERFORMANCE_ADAPTIVE_BATCH_SIZING));
        entries.add("recipe.solveRecipes=" + configValue(SudoersemcConfig.RECIPE_SOLVE_RECIPES));
        entries.add("recipe.maxRecipePasses=" + configValue(SudoersemcConfig.RECIPE_MAX_RECIPE_PASSES));
        entries.add("recipe.allowRecipeValueIncrease="
                + configValue(SudoersemcConfig.RECIPE_ALLOW_RECIPE_VALUE_INCREASE));
        entries.add("fallback.useFallbackForUnsolvable="
                + configValue(SudoersemcConfig.FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE));
        entries.add("fallback.useTagPricing=" + configValue(SudoersemcConfig.FALLBACK_USE_TAG_PRICING));
        entries.add("fallback.useRarityPricing="
                + configValue(SudoersemcConfig.FALLBACK_USE_RARITY_PRICING));
        entries.add("fallback.useProjectEIntegrationBaselines="
                + configValue(SudoersemcConfig.FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES));
        entries.add("safety.largeDropProtection="
                + configValue(SudoersemcConfig.SAFETY_LARGE_DROP_PROTECTION));
        entries.add("safety.maxAllowedDropRatio="
                + configValue(SudoersemcConfig.SAFETY_MAX_ALLOWED_DROP_RATIO));
        entries.add("safety.protectCreativeOnlyItems="
                + configValue(SudoersemcConfig.SAFETY_PROTECT_CREATIVE_ONLY_ITEMS));
        entries.add("nbt.scanNbtVariants=" + configValue(SudoersemcConfig.NBT_SCAN_NBT_VARIANTS));
        entries.add("nbt.maxNbtVariantsPerItem="
                + configValue(SudoersemcConfig.NBT_MAX_NBT_VARIANTS_PER_ITEM));
        entries.add("diagnostic.debugLogging="
                + configValue(SudoersemcConfig.DIAGNOSTIC_DEBUG_LOGGING));
        entries.add("diagnostic.generateReports="
                + configValue(SudoersemcConfig.DIAGNOSTIC_GENERATE_REPORTS));
        return entries;
    }

    private static String configValue(ForgeConfigSpec.ConfigValue<?> value) {
        try {
            return String.valueOf(value.get());
        } catch (RuntimeException e) {
            // 配置尚未加载时仍返回稳定值，避免启动阶段指纹计算抛异常。
            return "unavailable";
        }
    }

    private static List<String> baselineEntries() {
        List<String> entries = new ArrayList<>();
        entries.add("projecte.available=" + OptionalIntegrationManager.isProjectEAvailable());
        try {
            OptionalIntegrationManager.provider().ifPresent(
                    provider -> entries.add("provider.available=" + provider.isAvailable()));
        } catch (RuntimeException e) {
            entries.add("provider.error=" + e.getClass().getSimpleName());
        }
        return entries;
    }

    private static ItemScanner createItemScanner() {
        ItemScanner.EmcQuery query = new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(ItemStack stack) {
                EmcProvider provider = OptionalIntegrationManager.provider().orElse(null);
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
                EmcProvider provider = OptionalIntegrationManager.provider().orElse(null);
                if (provider == null) {
                    return 0L;
                }
                try {
                    return provider.isAvailable() ? Math.max(0L, provider.getValue(stack)) : 0L;
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.debug("EMC 后端读取物品数值失败，按 0 处理", failure);
                    return 0L;
                }
            }
        };
        return new ItemScanner(query, Set.of(), Set.of());
    }

    private static int saturatingInt(long value) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, value));
    }

    /**
     * 一次采集的完整纯数据快照。
     */
    record CollectedSnapshot(
            List<ItemSnapshot> items,
            List<RecipeSnapshot> recipes,
            int unsupportedRecipes,
            int invalidRecipes,
            int failedRecipes,
            ScanFingerprint fingerprint
    ) {

        CollectedSnapshot {
            items = List.copyOf(Objects.requireNonNull(items, "items"));
            recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
            Objects.requireNonNull(fingerprint, "fingerprint");
            if (unsupportedRecipes < 0 || invalidRecipes < 0 || failedRecipes < 0) {
                throw new IllegalArgumentException("配方扫描计数不能为负数");
            }
        }
    }

    /**
     * 绑定一个服务器实例的分片采集会话。
     */
    private final class CollectionSession implements ScanManager.CollectSession {

        private final MinecraftServer server;
        private final ItemScanner.ItemScanSession itemSession;
        private final RecipeScanner.RecipeScanSession recipeSession;
        private boolean finished;

        private CollectionSession(MinecraftServer server) {
            this.server = server;
            this.itemSession = itemScanner.beginSession();
            this.recipeSession = recipeScanner.beginSession(server.getRecipeManager());
        }

        @Override
        public int itemCount() {
            return itemSession.total();
        }

        @Override
        public int recipeCount() {
            return recipeSession.total();
        }

        @Override
        public int processedItems() {
            return itemSession.processed();
        }

        @Override
        public int processedRecipes() {
            return recipeSession.processed();
        }

        @Override
        public boolean isDone() {
            return itemSession.isDone() && recipeSession.isDone();
        }

        @Override
        public ScanManager.StepResult step(int maxItems) {
            if (!itemSession.isDone()) {
                int actual = itemSession.step(maxItems);
                return new ScanManager.StepResult(actual, isDone());
            }
            if (!recipeSession.isDone()) {
                int actual = recipeSession.step(maxItems);
                return new ScanManager.StepResult(actual, isDone());
            }
            return new ScanManager.StepResult(0, true);
        }

        @Override
        public ScanManager.SnapshotData finish() {
            if (finished) {
                throw new IllegalStateException("采集会话已经结束，不能重复 finish");
            }
            if (!isDone()) {
                throw new IllegalStateException("采集尚未完成，不能提前 finish");
            }
            finished = true;

            List<ItemSnapshot> itemSnapshots = itemSession.snapshots();
            RecipeScanner.ScanResult recipeResult = recipeSession.result();
            CollectedSnapshot snapshot = new CollectedSnapshot(
                    itemSnapshots,
                    recipeResult.recipes(),
                    recipeResult.unsupported(),
                    recipeResult.invalid(),
                    recipeResult.failed(),
                    computeFingerprint(server));
            stashed.set(snapshot);
            return new ScanManager.SnapshotData(
                    "服务器注册表", itemSnapshots.size(), recipeResult.recipes().size());
        }
    }

    /**
     * 状态文件 DTO；字段名与后续阶段的 ScanStateStore 保持一致。
     */
    private static final class StateFile {
        int schemaVersion;
        FingerprintDto fingerprint;
        long lastScanEpochMillis;
        long lastScanDurationMillis;
        int lastSolvedCount;
        int lastUnresolvedCount;
    }

    /**
     * 指纹 DTO。
     */
    private static final class FingerprintDto {
        String item;
        String recipe;
        String tag;
        String config;
        String baseline;

        static FingerprintDto from(ScanFingerprint fingerprint) {
            FingerprintDto dto = new FingerprintDto();
            dto.item = fingerprint.itemFingerprint();
            dto.recipe = fingerprint.recipeFingerprint();
            dto.tag = fingerprint.tagFingerprint();
            dto.config = fingerprint.configFingerprint();
            dto.baseline = fingerprint.baselineFingerprint();
            return dto;
        }

        ScanFingerprint toFingerprint() {
            return new ScanFingerprint(item, recipe, tag, config, baseline);
        }
    }
}
