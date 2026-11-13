package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.config.SudoersemcConfig;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.OptionalIntegrationManager;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
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
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.storage.SudoersemcPaths;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.ForgeConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Phase 7 的生产扫描管线：把分片扫描、配方求解、结果暂存与状态持久化接到
 * {@link ScanManager} 上。
 *
 * <p><strong>跨 tick 边界：</strong>采集使用 {@link ItemScanner#beginSession()} 与
 * {@link RecipeScanner#beginSession} 返回的真实游标，每个 tick 只检查配置批量允许的条目数；
 * 求解在后台工作线程执行，{@link ScanManager} 通过 {@link ScanManager.ApplyTask#advanceSolve(long)}
 * 轮询结果；求解结果按 {@link ScanManager.ApplyTask#stage(int, long)} 跨 tick 分片写入；
 * {@code scan_state.json} 与 {@code GeneratedEntryStore} 的写入由
 * {@link ScanManager.SaveTask#advance(long)} 轮询后台任务完成。服务器线程不会在一个 tick 内
 * 执行完整求解或完整文件写入。</p>
 *
 * <p><strong>归属与安全：</strong>{@link GeneratedEntryStore#snapshotAll()} 中
 * {@code owned=true} 的条目作为本次求解的初始 generated 值（可以被更低的新成本替换），
 * 而不是 {@link SolveContext} 的已知种子；只有外部来源（ProjectE、其它模组、手工值）才进入
 * seed 集合。写入前会执行 {@link LargeDropProtection} 降价检查与
 * {@link OverflowPipeline} 溢出重置，并通过 {@link EmcProvider#stageGeneratedValue} 写入，
 * 从而保留 ProjectE 手工值保护。</p>
 *
 * <p><strong>指纹与 reload 代次：</strong>物品通道使用注册表 id；配方通道使用配方 id 与完整
 * 内容（类型、输入候选与数量、输出、容器残留、催化剂与属性）；标签通道使用标签 id 与其成员
 * 物品。仅保留 id、修改配方 JSON 或标签成员都会改变完整指纹。完整指纹只在服务器启动判断
 * 增量扫描时计算一次；扫描期间由 {@link #onDatapackReload()} 维护的廉价代次负责校验，
 * 采集结束时的 {@code CollectedSnapshot.fingerprint} 直接复用已转换好的配方快照，
 * 不再调用 {@code RecipeScanner.scan()} 全量遍历配方，因此不会在服务器线程产生长 tick。</p>
 *
 * <p><strong>增量扫描：</strong>扫描成功后会把本次指纹写入
 * {@code config/sudoersemc/scan_state.json}。服务器启动时若当前指纹与文件中的指纹一致且
 * {@code general.alwaysRescan=false}，则跳过重复扫描。</p>
 *
 * <p><strong>线程约定：</strong>公开的采集、指纹与 staging 入口在服务器线程调用；只有纯数据
 * 求解与文件写入在后台守护线程执行。工作线程从不访问 Minecraft 注册表或 EMC 后端。</p>
 */
public final class ScanCollectionPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanCollectionPipeline");

    /** 扫描状态文件名，与后续阶段的 ScanStateStore 保持兼容。 */
    public static final String STATE_FILE_NAME = "scan_state.json";

    /** 当前状态文件格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 溢出审计日志文件名，与 OverflowResetLog 的生产文件名保持一致。 */
    private static final String OVERFLOW_LOG_FILE_NAME = "overflow_resets.json";

    /**
     * 纯数据求解与文件写入的守护线程池。多个管线实例共享；缓存线程池避免为每次扫描创建
     * 新线程，也不会阻止 JVM 退出。
     */
    private static final ExecutorService WORKER = Executors.newCachedThreadPool(new ThreadFactory() {
        private int index;

        @Override
        public synchronized Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "sudoersemc-scan-worker-" + (++index));
            thread.setDaemon(true);
            return thread;
        }
    });

    private final GeneratedEntryStore store;
    private final ItemScanner itemScanner;
    private final RecipeScanner recipeScanner;
    private final Path stateFile;
    private final EmcProvider provider;
    private final LargeDropProtection dropProtection;
    private final OverflowPipeline overflowPipeline;
    private final Runnable afterApply;

    private final AtomicReference<CollectedSnapshot> stashed = new AtomicReference<>();
    private final Object stateLock = new Object();

    private StateFile state;
    private boolean stateLoaded;
    private volatile CompletableFuture<Void> pendingSave;

    /**
     * datapack/reload 代次。扫描开始时读取一次，APPLYING 期间只比较这个原子计数，
     * 不在服务器线程重复计算全量内容指纹。
     */
    private final AtomicLong reloadGenerationCounter = new AtomicLong();

    private volatile ScanFingerprint lastAppliedFingerprint;
    private volatile long lastDurationMillis;
    private volatile long lastSolvedCount;
    private volatile long lastUnresolvedCount;
    private volatile long lastQuarantinedCount;
    private volatile long lastOverflowResetCount;

    /**
     * 生产构造器：使用 ProjectE 后端查询、Goety 可选读取器、配置驱动的安全策略与默认状态文件。
     *
     * @param store 生成值存储；{@code null} 表示干跑模式，不写入任何条目
     */
    public ScanCollectionPipeline(GeneratedEntryStore store) {
        this(store, null);
    }

    /**
     * 生产构造器，允许在应用完成后执行回调（例如请求 ProjectE remap）。
     *
     * @param store      生成值存储；{@code null} 表示干跑模式
     * @param afterApply 全部结果 staging 完成后的服务器线程回调；允许为 {@code null}
     */
    public ScanCollectionPipeline(GeneratedEntryStore store, Runnable afterApply) {
        this(store,
                createItemScanner(),
                RecipeScanner.withGoety().orElseGet(RecipeScanner::new),
                SudoersemcPaths.file(STATE_FILE_NAME),
                OptionalIntegrationManager.provider().orElse(null),
                defaultDropProtection(),
                defaultOverflowPipeline(SudoersemcPaths.file(STATE_FILE_NAME)),
                afterApply);
    }

    /**
     * 测试/嵌入式构造器：不注入 ProjectE 后端与自定义安全策略。
     *
     * @param store         生成值存储，允许为 {@code null}
     * @param itemScanner   物品扫描器，允许为 {@code null}（仅测试 apply/persist 时）
     * @param recipeScanner 配方扫描器，允许为 {@code null}（仅测试 apply/persist 时）
     * @param stateFile     状态文件路径
     */
    ScanCollectionPipeline(GeneratedEntryStore store,
                           ItemScanner itemScanner,
                           RecipeScanner recipeScanner,
                           Path stateFile) {
        this(store, itemScanner, recipeScanner, stateFile, null,
                defaultDropProtection(), defaultOverflowPipeline(stateFile), null);
    }

    /**
     * 完整测试构造器：允许注入假的 EMC 后端与安全策略。
     *
     * @param store          生成值存储，允许为 {@code null}
     * @param itemScanner    物品扫描器，允许为 {@code null}
     * @param recipeScanner  配方扫描器，允许为 {@code null}
     * @param stateFile      状态文件路径
     * @param provider       EMC 后端；{@code null} 时直接写入 store
     * @param dropProtection 降价保护，不能为 {@code null}
     * @param overflowPipeline 溢出保护，不能为 {@code null}
     */
    ScanCollectionPipeline(GeneratedEntryStore store,
                           ItemScanner itemScanner,
                           RecipeScanner recipeScanner,
                           Path stateFile,
                           EmcProvider provider,
                           LargeDropProtection dropProtection,
                           OverflowPipeline overflowPipeline) {
        this(store, itemScanner, recipeScanner, stateFile, provider, dropProtection, overflowPipeline, null);
    }

    private ScanCollectionPipeline(GeneratedEntryStore store,
                                   ItemScanner itemScanner,
                                   RecipeScanner recipeScanner,
                                   Path stateFile,
                                   EmcProvider provider,
                                   LargeDropProtection dropProtection,
                                   OverflowPipeline overflowPipeline,
                                   Runnable afterApply) {
        this.store = store;
        this.itemScanner = itemScanner;
        this.recipeScanner = recipeScanner;
        this.stateFile = Objects.requireNonNull(stateFile, "stateFile");
        this.provider = provider;
        this.dropProtection = Objects.requireNonNull(dropProtection, "dropProtection");
        this.overflowPipeline = Objects.requireNonNull(overflowPipeline, "overflowPipeline");
        this.afterApply = afterApply;
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
     * <p>该实现声明支持廉价 reload 代次：{@link ScanManager} 在扫描开始时只读取一次
     * {@link #reloadGeneration()}，采集完成、求解完成与 APPLYING 的每个批次都只比较代次。
     * 完整内容指纹不再在扫描过程中重复计算；采集结束时的 {@code CollectedSnapshot.fingerprint}
     * 由已经转换好的配方快照生成，供持久化与下次启动的增量判断使用。</p>
     *
     * @param server 当前服务器，不能为 {@code null}
     * @return 提供完整指纹与廉价 reload 代次的回调
     */
    public ScanManager.FingerprintSupplier fingerprintSupplier(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return fingerprintSupplier(() -> computeFingerprint(server));
    }

    /**
     * 测试/嵌入式入口：用给定的完整指纹回调构造与生产接线相同的 reload 代次提供器。
     *
     * @param fullFingerprint 完整指纹回调，不能为 {@code null}
     * @return 支持廉价 reload 代次的提供器
     */
    ScanManager.FingerprintSupplier fingerprintSupplier(Supplier<ScanFingerprint> fullFingerprint) {
        Objects.requireNonNull(fullFingerprint, "fullFingerprint");
        return new ScanManager.FingerprintSupplier() {
            @Override
            public ScanFingerprint current() {
                return fullFingerprint.get();
            }

            @Override
            public long reloadGeneration() {
                return reloadGenerationCounter.get();
            }

            @Override
            public boolean tracksReloadGeneration() {
                return true;
            }
        };
    }

    /**
     * 记录一次 datapack/reload。Forge 的 {@code AddReloadListenerEvent}（reload 开始）与
     * {@code TagsUpdatedEvent}（资源重载完成）都会调用这里；代次在 reload 窗口两端各递增一次，
     * 因此扫描无论从哪个时点开始，采集/求解/应用阶段都能检测到输入变化。
     */
    public void onDatapackReload() {
        reloadGenerationCounter.incrementAndGet();
    }

    /**
     * @return 当前 reload 代次，供诊断与测试读取
     */
    public long reloadGeneration() {
        return reloadGenerationCounter.get();
    }

    /**
     * @return 跨 tick 异步应用入口，供 {@link ScanManager#staged} 使用
     */
    public ScanManager.StagedResultApplier stagedApplier() {
        return this::beginApply;
    }

    /**
     * @return 跨 tick 异步持久化入口，供 {@link ScanManager#staged} 使用
     */
    public ScanManager.StagedSaveHandler stagedSaveHandler() {
        return this::beginPersist;
    }

    /**
     * 旧式同步应用入口。保留给直接调用方与测试；生产接线使用 {@link #stagedApplier()}。
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
     * 纯数据同步应用入口，供单元测试直接构造快照验证求解/写入。
     *
     * @param snapshot 采集结果
     */
    void applySnapshot(CollectedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ApplyJob job = prepareApply(snapshot);
        job.awaitSolve();
        while (!job.isDone()) {
            job.stage(Integer.MAX_VALUE, Long.MAX_VALUE);
        }
    }

    /**
     * 直接从纯数据快照启动跨 tick 求解/应用任务，供回归测试验证分批 staging。
     *
     * @param snapshot 采集结果
     * @return 分片应用任务
     */
    ScanManager.ApplyTask beginApplySnapshot(CollectedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return prepareApply(snapshot);
    }

    /**
     * 启动一次跨 tick 求解/应用任务。只做纯数据准备，不执行求解。
     *
     * @param data 采集阶段产生的快照
     * @return 分片应用任务
     */
    public ScanManager.ApplyTask beginApply(ScanManager.SnapshotData data) {
        Objects.requireNonNull(data, "data");
        CollectedSnapshot snapshot = stashed.getAndSet(null);
        if (snapshot == null) {
            throw new IllegalStateException("应用阶段没有暂存的扫描快照");
        }
        return prepareApply(snapshot);
    }

    /**
     * 准备求解上下文并提交后台求解。
     *
     * <p>owned 条目从 seed 中分离，作为初始 generated 值；外部值作为 seed。
     * 求解器本身是纯数据计算，不访问注册表或 EMC 后端。</p>
     */
    private ApplyJob prepareApply(CollectedSnapshot snapshot) {
        Map<ItemKey, GeneratedEmcEntry> previousEntries =
                store == null ? Map.of() : store.snapshotAll();
        Map<ItemKey, Long> seeds = new TreeMap<>();
        Map<ItemKey, Long> ownedValues = new TreeMap<>();
        for (Map.Entry<ItemKey, GeneratedEmcEntry> entry : previousEntries.entrySet()) {
            GeneratedEmcEntry value = entry.getValue();
            if (value == null || value.value() <= 0L) {
                continue;
            }
            if (value.owned()) {
                ownedValues.put(entry.getKey(), value.value());
            } else {
                // 非 owned 条目来自外部来源，必须作为种子，求解器不得覆盖。
                seeds.put(entry.getKey(), value.value());
            }
        }
        for (ItemSnapshot item : snapshot.items()) {
            if (item.hasExistingEmc()
                    && item.existingValue() > 0L
                    && !previousEntries.containsKey(item.key())) {
                seeds.put(item.key(), item.existingValue());
            }
        }

        SolveContext context = new SolveContext(seeds, ownedValues);
        long startedNanos = System.nanoTime();
        int overflowResetsBefore = overflowPipeline.resetsApplied();
        CompletableFuture<SolveResult> future = CompletableFuture.supplyAsync(
                () -> new RecipeSolver().solve(
                        snapshot.recipes(), context, EvaluatorRegistry.defaults(), SolveSettings.defaults()),
                WORKER);
        return new ApplyJob(snapshot, previousEntries, future, startedNanos, overflowResetsBefore);
    }

    /**
     * 启动一次跨 tick 持久化任务。状态 DTO 在服务器线程生成，文件写入在后台执行。
     *
     * @return 分片持久化任务；没有成功应用过扫描时返回立即完成的任务
     */
    public ScanManager.SaveTask beginPersist() {
        ScanFingerprint fingerprint = lastAppliedFingerprint;
        if (fingerprint == null) {
            return ScanManager.SaveTask.completed();
        }
        StateFile dto = buildState(fingerprint);
        synchronized (stateLock) {
            state = dto;
            stateLoaded = true;
        }
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
            if (!writeState(dto)) {
                throw new IllegalStateException("扫描状态持久化失败: " + stateFile);
            }
        }, WORKER);
        pendingSave = future;
        return new ScanManager.SaveTask() {
            private volatile boolean cancelled;
            private boolean done;

            @Override
            public boolean advance(long deadlineNanos) {
                if (cancelled) {
                    return true;
                }
                if (!done) {
                    if (!future.isDone()) {
                        return false;
                    }
                    try {
                        future.join();
                    } catch (CompletionException e) {
                        done = true;
                        throw unwrap(e);
                    }
                    done = true;
                }
                return true;
            }

            @Override
            public void cancel() {
                cancelled = true;
            }
        };
    }

    /**
     * 持久化扫描状态与生成值。旧式同步入口，由服务器停止流程与测试使用。
     *
     * <p>如果存在尚未完成的异步写入，会先等待它完成，避免两个写入者同时替换同一文件。</p>
     */
    public void persist() {
        ScanFingerprint fingerprint = lastAppliedFingerprint;
        if (fingerprint == null) {
            return;
        }

        CompletableFuture<Void> pending = pendingSave;
        if (pending != null) {
            joinQuietly(pending);
            pendingSave = null;
        }

        StateFile dto = buildState(fingerprint);
        synchronized (stateLock) {
            state = dto;
            stateLoaded = true;
        }
        if (!writeState(dto)) {
            LOGGER.warn("Synchronous scan-state persistence failed for {}", stateFile);
        }
    }

    private StateFile buildState(ScanFingerprint fingerprint) {
        StateFile dto = new StateFile();
        dto.schemaVersion = SCHEMA_VERSION;
        dto.fingerprint = FingerprintDto.from(fingerprint);
        dto.lastScanEpochMillis = System.currentTimeMillis();
        dto.lastScanDurationMillis = Math.max(0L, lastDurationMillis);
        dto.lastSolvedCount = saturatingInt(lastSolvedCount);
        dto.lastUnresolvedCount = saturatingInt(lastUnresolvedCount);
        return dto;
    }

    /**
     * 同步写入扫描状态与生成值。
     *
     * @return 两个文件都成功写入（或无需写入）时返回 {@code true}
     */
    private boolean writeState(StateFile dto) {
        // 先写生成值，再写状态指纹：任何一步失败都保留旧指纹，下次启动会重新扫描，
        // 不会出现“指纹已更新但条目未落盘”导致增量扫描跳过的情况。
        if (store != null && !store.saveIfDirty()) {
            LOGGER.warn("Could not persist generated entries to {}", store.file());
            return false;
        }
        if (!JsonFileIO.writeAtomically(stateFile, dto)) {
            LOGGER.warn("Could not persist scan state to {}", stateFile);
            return false;
        }
        return true;
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
     * 计算当前扫描输入指纹。物品/配方内容/标签成员来自实时服务器，配置与基线来自静态配置和
     * 集成状态。
     *
     * @param server 当前服务器，不能为 {@code null}
     * @return 五通道指纹
     */
    public ScanFingerprint computeFingerprint(MinecraftServer server) {
        Objects.requireNonNull(server, "server");
        return computeFingerprint(server, null);
    }

    /**
     * 计算当前扫描输入指纹。
     *
     * @param server           当前服务器
     * @param collectedRecipes 采集阶段已经生成的配方快照；{@code null} 时从实时 RecipeManager 重建
     */
    private ScanFingerprint computeFingerprint(MinecraftServer server,
                                               List<RecipeSnapshot> collectedRecipes) {
        List<String> items = new ArrayList<>(BuiltInRegistries.ITEM.keySet().size());
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            items.add(id.toString());
        }

        List<String> recipes;
        if (collectedRecipes != null) {
            // 采集结束路径：复用已经转换好的配方快照，避免在服务器线程再次全量 scan。
            recipes = recipeEntries(collectedRecipes);
        } else if (recipeScanner != null) {
            try {
                // 配方内容指纹：只做描述，不求值。相比仅 id，能捕获 /reload 后保留 id 但修改
                // 输入/输出/类型的情况。
                recipes = recipeEntries(recipeScanner.scan(server.getRecipeManager()).recipes());
            } catch (RuntimeException e) {
                LOGGER.warn("Could not compute recipe content fingerprint; using unavailable marker", e);
                recipes = List.of("unavailable");
            }
        } else {
            recipes = recipeIds(server);
        }

        List<String> tags;
        try {
            tags = tagEntries();
        } catch (RuntimeException e) {
            tags = List.of("unavailable");
        }

        return ScanFingerprint.of(items, recipes, tags, configEntries(), baselineEntries());
    }

    private static List<String> recipeIds(MinecraftServer server) {
        List<String> recipes = new ArrayList<>();
        try {
            server.getRecipeManager().getRecipeIds().forEach(id -> recipes.add(id.toString()));
        } catch (RuntimeException e) {
            recipes.add("unavailable");
        }
        return recipes;
    }

    /**
     * 把配方快照转换为稳定的内容字符串。同一 id、不同输入/输出会得到不同字符串。
     *
     * @param recipes 配方快照
     * @return 每个配方一条内容描述
     */
    static List<String> recipeEntries(List<RecipeSnapshot> recipes) {
        List<String> entries = new ArrayList<>(recipes.size());
        for (RecipeSnapshot recipe : recipes) {
            if (recipe != null) {
                entries.add(recipeEntry(recipe));
            }
        }
        return entries;
    }

    private static String recipeEntry(RecipeSnapshot recipe) {
        StringBuilder builder = new StringBuilder(128);
        builder.append(recipe.id()).append('|').append(recipe.kind()).append("|in:");
        for (RecipeInput input : recipe.inputs()) {
            builder.append(input.count()).append('x');
            List<String> candidates = new ArrayList<>();
            for (ItemKey candidate : input.option().candidates()) {
                candidates.add(candidate.toString());
            }
            candidates.sort(Comparator.naturalOrder());
            builder.append('[').append(String.join(",", candidates)).append(']').append(';');
        }
        builder.append("|out:");
        appendOutputs(builder, recipe.outputs());
        builder.append("|rem:");
        appendOutputs(builder, recipe.containerRemainders());
        builder.append("|catalyst:").append(recipe.catalyst());
        builder.append("|attr:");
        new TreeMap<>(recipe.attributes()).forEach((key, value) ->
                builder.append(key).append('=').append(value).append(';'));
        return builder.toString();
    }

    private static void appendOutputs(StringBuilder builder, List<RecipeOutput> outputs) {
        for (RecipeOutput output : outputs) {
            builder.append(output.item()).append('x').append(output.count()).append(';');
        }
    }

    /**
     * 读取当前标签 id 与成员物品。标签成员变化会改变指纹。
     */
    private static List<String> tagEntries() {
        NavigableMap<String, List<String>> tags = new TreeMap<>();
        for (TagKey<Item> tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            List<String> members = new ArrayList<>();
            BuiltInRegistries.ITEM.getTag(tag).ifPresent(named -> {
                for (Holder<Item> holder : named) {
                    ResourceLocation id = BuiltInRegistries.ITEM.getKey(holder.value());
                    if (id != null) {
                        members.add(id.toString());
                    }
                }
            });
            members.sort(Comparator.naturalOrder());
            tags.put(tag.location().toString(), members);
        }
        return tagEntries(tags);
    }

    /**
     * 把“标签 id -> 成员物品”转换为稳定字符串。用于生产指纹与回归测试。
     *
     * @param tags 标签到成员的映射
     * @return 每个标签一条描述
     */
    static List<String> tagEntries(Map<String, ? extends Iterable<String>> tags) {
        List<String> entries = new ArrayList<>(tags.size());
        new TreeMap<>(tags).forEach((tag, rawMembers) -> {
            List<String> members = new ArrayList<>();
            for (String member : rawMembers) {
                if (member != null) {
                    members.add(member);
                }
            }
            members.sort(Comparator.naturalOrder());
            entries.add(tag + "=" + String.join(",", members));
        });
        return entries;
    }

    /**
     * @return 最近一次成功应用的指纹；从未应用过时为 {@code null}
     */
    ScanFingerprint lastAppliedFingerprint() {
        return lastAppliedFingerprint;
    }

    /**
     * @return 最近一次求解后实际写入的条目数
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

    /**
     * @return 最近一次应用中因降价保护被隔离的条目数
     */
    long lastQuarantinedCount() {
        return lastQuarantinedCount;
    }

    /**
     * @return 最近一次应用中实际执行溢出重置的次数
     */
    long lastOverflowResetCount() {
        return lastOverflowResetCount;
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

    private static boolean booleanConfig(ForgeConfigSpec.ConfigValue<Boolean> value, boolean fallback) {
        try {
            Boolean configured = value.get();
            return configured == null ? fallback : configured;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static long longConfig(ForgeConfigSpec.ConfigValue<Long> value, long fallback) {
        try {
            Long configured = value.get();
            return configured == null ? fallback : configured;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static int intConfig(ForgeConfigSpec.ConfigValue<Integer> value, int fallback) {
        try {
            Integer configured = value.get();
            return configured == null ? fallback : configured;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static double doubleConfig(ForgeConfigSpec.ConfigValue<Double> value, double fallback) {
        try {
            Double configured = value.get();
            return configured == null ? fallback : configured;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static String stringConfig(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            String configured = value.get();
            return configured == null ? fallback : configured;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static LargeDropProtection defaultDropProtection() {
        boolean enabled = booleanConfig(SudoersemcConfig.SAFETY_LARGE_DROP_PROTECTION, true);
        double ratio = doubleConfig(SudoersemcConfig.SAFETY_MAX_ALLOWED_DROP_RATIO, 0.5D);
        if (!(ratio > 0.0D && ratio <= 1.0D)) {
            ratio = 0.5D;
        }
        return new LargeDropProtection(new LargeDropProtection.Settings(
                enabled, ratio, Set.of(), Set.of()));
    }

    private static OverflowPipeline defaultOverflowPipeline(Path stateFile) {
        OverflowProtection.Settings settings;
        try {
            boolean enabled = booleanConfig(SudoersemcConfig.OVERFLOW_ENABLED, true);
            long threshold = longConfig(SudoersemcConfig.OVERFLOW_THRESHOLD,
                    OverflowProtection.DEFAULT_THRESHOLD);
            long resetMin = longConfig(SudoersemcConfig.OVERFLOW_RESET_MIN,
                    OverflowProtection.DEFAULT_RESET_MIN);
            long resetMax = longConfig(SudoersemcConfig.OVERFLOW_RESET_MAX,
                    OverflowProtection.DEFAULT_RESET_MAX);
            int bucketCount = intConfig(SudoersemcConfig.OVERFLOW_BUCKET_COUNT,
                    OverflowProtection.DEFAULT_BUCKET_COUNT);
            int weightCap = intConfig(SudoersemcConfig.OVERFLOW_WEIGHT_CAP,
                    OverflowProtection.DEFAULT_WEIGHT_CAP);
            String rawScope = stringConfig(SudoersemcConfig.OVERFLOW_OVERWRITE_SCOPE, "owned_only");
            OverflowScope scope = "entire_custom_emc".equalsIgnoreCase(rawScope.trim())
                    ? OverflowScope.ENTIRE_CUSTOM_EMC
                    : OverflowScope.OWNED_ONLY;
            boolean allowRewrite = booleanConfig(
                    SudoersemcConfig.OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE, false);
            long seed = longConfig(SudoersemcConfig.OVERFLOW_RANDOM_SEED, 0L);
            settings = new OverflowProtection.Settings(
                    enabled, threshold, resetMin, resetMax, bucketCount, weightCap,
                    scope, allowRewrite, seed);
        } catch (RuntimeException e) {
            settings = new OverflowProtection.Settings();
        }
        Path logFile = stateFile.resolveSibling(OVERFLOW_LOG_FILE_NAME);
        return new OverflowPipeline(new OverflowProtection(settings), new OverflowResetLog(logFile));
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

    private static void joinQuietly(CompletableFuture<Void> future) {
        try {
            future.join();
        } catch (CompletionException e) {
            LOGGER.warn("Background scan task failed", e.getCause() == null ? e : e.getCause());
        } catch (RuntimeException e) {
            LOGGER.warn("Background scan task failed", e);
        }
    }

    private static RuntimeException unwrap(CompletionException exception) {
        Throwable cause = exception.getCause();
        if (cause instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("后台扫描任务失败", cause == null ? exception : cause);
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
            return step(maxItems, Long.MAX_VALUE);
        }

        @Override
        public ScanManager.StepResult step(int maxItems, long deadlineNanos) {
            if (!itemSession.isDone()) {
                int actual = itemSession.step(maxItems, deadlineNanos);
                return new ScanManager.StepResult(actual, isDone());
            }
            if (!recipeSession.isDone()) {
                int actual = recipeSession.step(maxItems, deadlineNanos);
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
                    computeFingerprint(server, recipeResult.recipes()));
            stashed.set(snapshot);
            return new ScanManager.SnapshotData(
                    "服务器注册表", itemSnapshots.size(), recipeResult.recipes().size());
        }
    }

    /**
     * 一次跨 tick 求解 + staging 任务。求解在 {@link #WORKER} 上执行，staging 在服务器线程执行。
     */
    private final class ApplyJob implements ScanManager.ApplyTask {

        private final CollectedSnapshot snapshot;
        private final Map<ItemKey, GeneratedEmcEntry> previousEntries;
        private final CompletableFuture<SolveResult> future;
        private final long startedNanos;
        private final int overflowResetsBefore;

        private volatile SolveResult result;
        private Iterator<Map.Entry<ItemKey, Long>> iterator;
        private boolean done;
        private volatile boolean cancelled;
        private boolean rolledBack;
        private long stagedCount;
        private long quarantinedCount;
        private final List<ItemKey> stagedKeys = new ArrayList<>();

        private ApplyJob(CollectedSnapshot snapshot,
                         Map<ItemKey, GeneratedEmcEntry> previousEntries,
                         CompletableFuture<SolveResult> future,
                         long startedNanos,
                         int overflowResetsBefore) {
            this.snapshot = snapshot;
            this.previousEntries = previousEntries;
            this.future = future;
            this.startedNanos = startedNanos;
            this.overflowResetsBefore = overflowResetsBefore;
        }

        private void awaitSolve() {
            try {
                setResult(future.join());
            } catch (CompletionException e) {
                throw unwrap(e);
            }
        }

        private void setResult(SolveResult solved) {
            if (solved == null) {
                throw new IllegalStateException("配方求解返回 null");
            }
            this.result = solved;
        }

        @Override
        public boolean isSolveComplete() {
            return result != null;
        }

        @Override
        public void advanceSolve(long deadlineNanos) {
            if (result != null || cancelled) {
                return;
            }
            if (!future.isDone()) {
                return;
            }
            try {
                setResult(future.join());
            } catch (CompletionException e) {
                throw unwrap(e);
            }
        }

        @Override
        public int stage(int maxItems, long deadlineNanos) {
            if (done || cancelled) {
                return 0;
            }
            SolveResult solved = result;
            if (solved == null) {
                throw new IllegalStateException("求解尚未完成，不能开始 staging");
            }
            if (maxItems < 1) {
                throw new IllegalArgumentException("maxItems 必须 >= 1，实际为 " + maxItems);
            }
            if (iterator == null) {
                iterator = solved.values().entrySet().iterator();
            }

            int processed = 0;
            while (processed < maxItems && iterator.hasNext()) {
                if (deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos) {
                    break;
                }
                Map.Entry<ItemKey, Long> entry = iterator.next();
                processed++;
                stageOne(entry.getKey(), entry.getValue(), solved.traces().get(entry.getKey()));
            }
            if (!iterator.hasNext()) {
                finish();
            }
            return processed;
        }

        private void stageOne(ItemKey key, long proposedValue, SolveTrace trace) {
            if (key == null || proposedValue <= 0L) {
                return;
            }
            GeneratedEmcEntry previous = previousEntries.get(key);
            if (previous != null && previous.owned()) {
                if (!dropProtection.evaluate(key, previous.value(), proposedValue).allowed()) {
                    quarantinedCount++;
                    return;
                }
            }

            long value = overflowPipeline.applyAndAudit(key, proposedValue, OverflowScope.OWNED_ONLY);
            if (value <= 0L) {
                return;
            }

            ResourceLocation recipeId = trace == null ? null : trace.recipeId();
            boolean accepted;
            if (provider != null) {
                accepted = provider.stageGeneratedValue(key, value, PriceSource.RECIPE, recipeId);
            } else if (store != null) {
                accepted = store.stage(new GeneratedEmcEntry(
                        key, value, PriceSource.RECIPE, recipeId, true, System.currentTimeMillis()));
            } else {
                // 干跑模式：不写入任何存储，但统计为“本应写入”。
                accepted = true;
            }
            if (accepted) {
                stagedCount++;
                stagedKeys.add(key);
            }
        }

        private void finish() {
            if (done) {
                return;
            }
            done = true;
            lastAppliedFingerprint = snapshot.fingerprint();
            lastDurationMillis = Math.max(0L, (System.nanoTime() - startedNanos) / 1_000_000L);
            lastSolvedCount = stagedCount;
            lastUnresolvedCount = result == null ? 0 : result.unresolvedItems().size();
            lastQuarantinedCount = quarantinedCount;
            lastOverflowResetCount = Math.max(0,
                    overflowPipeline.resetsApplied() - overflowResetsBefore);
            if (afterApply != null) {
                afterApply.run();
            }
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public int solvedCount() {
            SolveResult solved = result;
            return solved == null ? 0 : solved.values().size();
        }

        @Override
        public int unresolvedCount() {
            SolveResult solved = result;
            return solved == null ? 0 : solved.unresolvedItems().size();
        }

        @Override
        public void cancel() {
            if (done || cancelled) {
                return;
            }
            cancelled = true;
            future.cancel(true);
            rollbackPartialStaging();
        }

        /**
         * 取消/失败时回滚已经写入的条目：owned 条目恢复旧值，新条目从 store 移除。
         *
         * <p>只回滚本任务实际写入并记录过的键；在服务器线程调用，与 staging 共用线程，
         * 因此不需要额外锁。项目E 手工值从未被写入，不会受影响。</p>
         */
        private void rollbackPartialStaging() {
            if (rolledBack) {
                return;
            }
            rolledBack = true;
            if (store == null || stagedKeys.isEmpty()) {
                stagedKeys.clear();
                return;
            }
            for (ItemKey key : stagedKeys) {
                GeneratedEmcEntry previous = previousEntries.get(key);
                if (previous != null && previous.owned()) {
                    store.stage(previous);
                } else {
                    store.removeOwned(key);
                }
            }
            stagedKeys.clear();
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
