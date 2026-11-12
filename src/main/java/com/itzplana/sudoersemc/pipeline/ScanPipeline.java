package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.TierPricing;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OwnershipManager;
import com.itzplana.sudoersemc.safety.PriceDropDecision;
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
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把扫描、配方求解、兜底定价、安全层与持久化串联起来的运行时管线。
 *
 * <p>一次完整扫描分为两个阶段：</p>
 * <ol>
 *   <li>{@link #beginCollection(MinecraftServer)} 返回按批推进的采集会话，物品与配方都不会在
 *       单个 tick 内全量遍历；长时间运行或测试仍可使用一次性的 {@link #collect(MinecraftServer)}；</li>
 *   <li>求解与写入拆成两步：{@link #startStashedSolve()} 把纯计算提交到工作线程，服务器线程
 *       轮询 {@link #isSolveComplete()} 后调用 {@link #applyCompletedSolve()} 完成安全判定、
 *       存储写入与报告落盘。同步调用 {@link #solveAndApply(PipelineSnapshot)} 保留给测试。</li>
 * </ol>
 *
 * <p>{@link com.itzplana.sudoersemc.task.ScanManager} 会在采集阶段与阶段之间跨越多个 tick，
 * 因此本类提供 {@link #stash(PipelineSnapshot)} 与 {@link #applyStashed()} 配对使用：
 * 采集回调只负责暂存快照，应用回调再取出并执行求解。相比让外部 lambda 捕获数组，显式暂存字段
 * 更容易测试、诊断与避免重复应用。</p>
 *
 * <p><strong>空值语义：</strong>{@code store} 为 {@code null} 表示干跑模式，所有计数与报告照常
 * 计算，但不会写入任何持久化条目。其余协作者为 {@code null} 时按“关闭该能力”处理：降价保护与
 * 创造物品保护使用安全默认值，兜底定价与溢出保护在缺失时直接跳过。这样单元测试可以在没有完整
 * 运行时环境的情况下验证管线主干。</p>
 *
 * <p><strong>异常契约：</strong>{@link #collect(MinecraftServer)} 在服务器未就绪或采集失败时
 * 抛出 {@link IllegalStateException}，由 {@code ScanManager} 转为 {@code FAILED}，绝不把失败
 * 伪装成“成功的空扫描”；{@link #solveAndApply(PipelineSnapshot)} 仍不向调用方抛出异常，
 * 任何未预期失败记录日志后返回全零结果，保证服务器主循环不被单次扫描拖垮。</p>
 */
public final class ScanPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanPipeline");

    /**
     * 单线程守护线程池，只执行不接触游戏状态的纯求解计算。
     *
     * <p>求解函数只读取不可变快照、定价规则与存储归属标记，不访问注册表、RecipeManager、
     * ProjectE API 或文件系统；存储写入与报告落盘全部回到服务器线程的
     * {@link #applyCompletedSolve()}。</p>
     */
    private static final ExecutorService SOLVER_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sudoersemc-solver");
        thread.setDaemon(true);
        return thread;
    });

    private final GeneratedEntryStore store;
    private final ScanStateStore stateStore;
    private final ReportManager reportManager;
    private final LargeDropProtection dropProtection;
    private final CreativeItemProtection creativeProtection;
    private final OverflowPipeline overflowPipeline;
    private final FallbackPricing fallbackPricing;
    private final TierPricing tierPricing;
    private final SolveSettings solveSettings;

    private final AtomicReference<PipelineSnapshot> stashedSnapshot = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Computation>> pendingSolve = new AtomicReference<>();

    private volatile EmcProvider emcProvider;
    private volatile OverflowScope overflowScope = OverflowScope.OWNED_ONLY;
    private volatile boolean solveRecipesEnabled = true;
    private volatile boolean generateReportsEnabled = true;
    private volatile boolean useProjectEIntegrationBaselines = true;
    private volatile int lastSolvedCount;
    private volatile int lastUnresolvedCount;
    private volatile long lastDurationMillis;

    /**
     * 创建管线。
     *
     * @param store              生成值存储；{@code null} 表示干跑模式，不写入任何条目
     * @param stateStore         扫描状态存储；{@code null} 时不更新增量扫描指纹
     * @param reportManager      诊断报告管理器；{@code null} 时不写报告
     * @param dropProtection     大幅降价保护；{@code null} 时使用 {@link LargeDropProtection.Settings#defaults()}
     * @param creativeProtection 创造物品保护；{@code null} 时使用启用但不含任何排除项的默认配置
     * @param overflowPipeline   溢出保护管线；{@code null} 时跳过溢出处理
     * @param fallbackPricing    兜底定价链；{@code null} 时跳过兜底定价
     * @param solveSettings      配方求解参数；{@code null} 时使用 {@link SolveSettings#defaults()}
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
     * 注册 ProjectE（或其它后端）的 EMC 查询实现。
     *
     * <p>采集阶段通过该提供者判断物品是否已有 EMC。传入 {@code null} 表示没有后端，此时所有
     * 物品都会被当作“没有既有值”，管线仍然可以仅依靠配方与兜底定价工作。</p>
     *
     * @param provider EMC 后端查询接口，允许为 {@code null}
     */
    public void setEmcProvider(@Nullable EmcProvider provider) {
        this.emcProvider = provider;
    }

    /**
     * 设置溢出保护作用域。
     *
     * <p>生产环境默认 {@link OverflowScope#OWNED_ONLY}，只允许改写 SudoersEMC 自己拥有的条目。
     * 传入 {@code null} 时恢复该默认值，绝不隐式扩大作用域。</p>
     *
     * @param scope 作用域，允许为 {@code null}
     */
    public void setOverflowScope(@Nullable OverflowScope scope) {
        this.overflowScope = scope == null ? OverflowScope.OWNED_ONLY : scope;
    }

    /**
     * 控制是否运行配方求解。
     *
     * <p>{@code recipe.solveRecipes=false} 时求解阶段直接跳过，只保留兜底定价；关闭求解不会
     * 清空既有值，也不会阻止安全层与报告继续工作。</p>
     *
     * @param enabled 是否启用配方求解
     */
    public void setSolveRecipesEnabled(boolean enabled) {
        this.solveRecipesEnabled = enabled;
    }

    /**
     * 控制是否写出诊断报告。
     *
     * <p>{@code diagnostic.generateReports=false} 时仍然更新扫描状态与存储，只跳过报告文件。</p>
     *
     * @param enabled 是否写报告
     */
    public void setGenerateReportsEnabled(boolean enabled) {
        this.generateReportsEnabled = enabled;
    }

    /**
     * 控制是否允许使用 ProjectE 集成基线。
     *
     * <p><strong>阶段说明：</strong>Phase 12 尚未提供外部基线数据源，本开关当前只记录
     * {@code fallback.useProjectEIntegrationBaselines} 的值；真正的基线读取器在 Phase 25
     * 接线后生效。关闭时显式传 {@code 0}，避免未来接线后误用基线。</p>
     *
     * @param enabled 是否允许使用外部基线
     */
    public void setUseProjectEIntegrationBaselines(boolean enabled) {
        this.useProjectEIntegrationBaselines = enabled;
    }

    /**
     * 采集一次扫描所需的全部数据。
     *
     * <p><strong>必须由服务器线程调用。</strong>本方法读取物品注册表与配方管理器，并把实时对象
     * 转换为不可变的 {@link ItemSnapshot} / {@link RecipeSnapshot}。返回后所有数据都可以安全地
     * 脱离服务器线程处理。</p>
     *
     * <p>服务器未就绪、注册表/配方扫描失败或出现未预期异常时抛出
     * {@link IllegalStateException}。调用方（{@code ScanManager}）会把它转换为扫描失败，
     * 从而避免用空快照覆盖上一次有效的扫描状态。</p>
     *
     * @param server 当前服务器实例，允许为 {@code null}
     * @return 本次采集的不可变快照
     * @throws IllegalStateException 服务器未就绪或采集过程失败时抛出
     */
    public PipelineSnapshot collect(@Nullable MinecraftServer server) {
        if (server == null) {
            LOGGER.warn("SudoersEMC 扫描采集被调用时服务器尚未就绪，本次扫描失败");
            throw new IllegalStateException("扫描采集失败：服务器尚未就绪");
        }

        try {
            CollectionSession session = new CollectionSession(server, false);
            while (!session.isDone()) {
                session.step(Integer.MAX_VALUE);
            }
            return session.buildSnapshot();
        } catch (Throwable failure) {
            LOGGER.error("SudoersEMC 扫描采集失败", failure);
            throw new IllegalStateException("SudoersEMC 扫描采集失败", failure);
        }
    }

    /**
     * 创建一个真正的分片采集会话，供 {@link ScanManager} 跨 tick 推进。
     *
     * <p>会话先按批遍历物品注册表，再按批描述配方；每个 {@link ScanManager.CollectSession#step(int)}
     * 最多处理调用方给定的工作单元。完成时 {@link ScanManager.CollectSession#finish()} 会把
     * 快照暂存到本管线，等待应用阶段取出。</p>
     *
     * @param server 当前服务器实例，不能为 {@code null}
     * @return 分片采集会话
     * @throws IllegalStateException 服务器未就绪或无法创建会话时抛出
     */
    public ScanManager.CollectSession beginCollection(@Nullable MinecraftServer server) {
        if (server == null) {
            throw new IllegalStateException("扫描采集失败：服务器尚未就绪");
        }
        try {
            return new CollectionSession(server, true);
        } catch (RuntimeException | LinkageError failure) {
            throw new IllegalStateException("SudoersEMC 扫描采集失败", failure);
        }
    }

    /**
     * 分片采集会话：物品与配方分别使用各自的增量会话，绝不单个 tick 全量遍历。
     */
    private final class CollectionSession implements ScanManager.CollectSession {

        private final MinecraftServer server;
        private final boolean stashOnFinish;
        private final ItemScanner.ScanSession itemSession;

        @Nullable
        private RecipeScanner recipeScanner;
        @Nullable
        private RecipeScanner.ScanSession recipeSession;
        @Nullable
        private PipelineSnapshot completedSnapshot;

        private CollectionSession(MinecraftServer server, boolean stashOnFinish) {
            this.server = server;
            this.stashOnFinish = stashOnFinish;
            this.itemSession = new ItemScanner(emcQuery(), Set.of(), Set.of()).beginScan();
        }

        @Override
        public int itemCount() {
            return itemSession.totalItems();
        }

        @Override
        public int recipeCount() {
            return recipeSession == null ? 0 : recipeSession.totalRecipes();
        }

        @Override
        public int processedItems() {
            return itemSession.processedItems();
        }

        @Override
        public int processedRecipes() {
            return recipeSession == null ? 0 : recipeSession.processedRecipes();
        }

        @Override
        public boolean isDone() {
            return itemSession.isDone() && recipeSession != null && recipeSession.isDone();
        }

        @Override
        public ScanManager.StepResult step(int maxItems) {
            if (maxItems < 1) {
                throw new IllegalArgumentException("maxItems 必须 >= 1，实际为 " + maxItems);
            }
            if (!itemSession.isDone()) {
                int processed = itemSession.step(maxItems);
                return new ScanManager.StepResult(processed, false);
            }
            if (recipeSession == null) {
                RecipeScanner scanner = recipeScanner;
                if (scanner == null) {
                    scanner = RecipeScanner.withGoety().orElseGet(RecipeScanner::new);
                    recipeScanner = scanner;
                }
                if (server.getRecipeManager() == null) {
                    throw new IllegalStateException("扫描采集失败：配方管理器尚未就绪");
                }
                recipeSession = scanner.beginScan(server.getRecipeManager());
            }
            if (!recipeSession.isDone()) {
                int processed = recipeSession.step(maxItems);
                return new ScanManager.StepResult(processed, recipeSession.isDone());
            }
            return new ScanManager.StepResult(0, true);
        }

        @Override
        public ScanManager.SnapshotData finish() {
            PipelineSnapshot snapshot = buildSnapshot();
            completedSnapshot = snapshot;
            if (stashOnFinish) {
                stash(snapshot);
            }
            return new ScanManager.SnapshotData(
                    "真实扫描管线", snapshot.itemCount(), snapshot.recipeCount());
        }

        private PipelineSnapshot buildSnapshot() {
            PipelineSnapshot existing = completedSnapshot;
            if (existing != null) {
                return existing;
            }
            List<ItemSnapshot> items = itemSession.snapshots();
            RecipeScanner.ScanResult scanResult = recipeSession == null
                    ? new RecipeScanner.ScanResult(List.of(), 0, 0, 0)
                    : recipeSession.result();
            Map<ItemKey, Long> existingValues = new TreeMap<>();
            for (ItemSnapshot item : items) {
                if (item.hasExistingEmc()) {
                    existingValues.put(item.key(), item.existingValue());
                }
            }
            return new PipelineSnapshot(
                    items,
                    scanResult.recipes(),
                    existingValues,
                    scanResult.unsupported(),
                    scanResult.invalid(),
                    scanResult.failed());
        }
    }

    /**
     * 构建 EMC 查询适配器，把后端的运行时异常与链接错误统一降级为“无值”。
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
     * 暂存采集结果，等待应用阶段取用。
     *
     * <p>该方法是“stash + applyStashed”接线方式的一半，专门服务于
     * {@link com.itzplana.sudoersemc.task.ScanManager} 跨 tick 的阶段划分。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     */
    public void stash(@Nullable PipelineSnapshot snapshot) {
        stashedSnapshot.set(snapshot);
    }

    /**
     * 取出并应用最近一次暂存的快照。
     *
     * <p>快照在取出后立即清空，因此重复调用不会把同一批结果写入两次。没有暂存快照时返回全零
     * 结果并记录警告，不会抛出异常。</p>
     *
     * @return 本次应用的结果；没有暂存数据时为 {@link PipelineResult#zeroed(long)}
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
     * 求解快照并为可定价物品写入生成值。
     *
     * <p>本方法不访问注册表，只使用快照中的纯数据。处理顺序固定为：</p>
     * <ol>
     *   <li>用已有 EMC 作为种子运行配方求解器，求解器产出的值以 {@link PriceSource#RECIPE} 记录；</li>
     *   <li>对既没有既有 EMC、也没有被求解器定价的物品，依次尝试创造物品保护固定值与兜底定价链；</li>
     *   <li>每个候选值先经过大幅降价保护，再经过溢出保护，最后才写入存储；</li>
     *   <li>更新扫描指纹、写出诊断报告并返回汇总结果。</li>
     * </ol>
     *
     * <p>存储为 {@code null} 时进入干跑模式：计数与报告照常生成，但不会产生任何持久化写入。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     * @return 汇总结果；任何未预期异常都会转为全零结果
     */
    public PipelineResult solveAndApply(@Nullable PipelineSnapshot snapshot) {
        long startedAtNanos = System.nanoTime();
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
        try {
            return applyComputation(compute(data, startedAtNanos));
        } catch (Throwable failure) {
            return failedResult(startedAtNanos, failure);
        }
    }

    /**
     * 开始异步求解最近一次暂存的快照。
     *
     * <p>本方法只在服务器线程调用。它立即取走暂存快照并把纯计算提交到 {@link #SOLVER_EXECUTOR}；
     * 调用方在后续 tick 轮询 {@link #isSolveComplete()}，完成后在服务器线程调用
     * {@link #applyCompletedSolve()} 执行安全层、存储写入与报告落盘。</p>
     *
     * @return 存在暂存快照并已提交求解时返回 {@code true}
     */
    public boolean startStashedSolve() {
        CompletableFuture<Computation> previous = pendingSolve.getAndSet(null);
        if (previous != null) {
            previous.cancel(false);
        }
        PipelineSnapshot snapshot = stashedSnapshot.getAndSet(null);
        if (snapshot == null) {
            LOGGER.warn("SudoersEMC 应用阶段没有可用的暂存快照，跳过本轮写入");
            return false;
        }
        long startedAtNanos = System.nanoTime();
        pendingSolve.set(CompletableFuture.supplyAsync(
                () -> compute(snapshot, startedAtNanos), SOLVER_EXECUTOR));
        return true;
    }

    /**
     * @return 后台求解是否已经完成（成功、失败或取消都算完成）
     */
    public boolean isSolveComplete() {
        CompletableFuture<Computation> future = pendingSolve.get();
        return future != null && future.isDone();
    }

    /**
     * 在服务器线程应用已完成的异步求解结果。
     *
     * <p>求解失败、被取消或没有待处理结果时返回全零结果，不更新扫描状态，也不写报告。</p>
     *
     * @return 本次应用的结果
     */
    public PipelineResult applyCompletedSolve() {
        CompletableFuture<Computation> future = pendingSolve.getAndSet(null);
        long startedAtNanos = System.nanoTime();
        if (future == null) {
            return PipelineResult.zeroed(0L);
        }
        try {
            Computation computation = future.join();
            if (computation == null) {
                return PipelineResult.zeroed(0L);
            }
            return applyComputation(computation);
        } catch (RuntimeException failure) {
            return failedResult(startedAtNanos, failure);
        }
    }

    /**
     * 取消尚未完成的异步求解。计算本身不可中断，但其结果会被丢弃，绝不会写入存储。
     */
    public void cancelPendingSolve() {
        CompletableFuture<Computation> future = pendingSolve.getAndSet(null);
        if (future != null) {
            future.cancel(false);
        }
    }

    /**
     * 纯计算阶段：归属分离、配方求解与候选构建。不得访问注册表、配方管理器或文件系统。
     */
    private Computation compute(PipelineSnapshot snapshot, long startedAtNanos) {
        Set<ItemKey> externallyModified = detectExternallyModifiedOwnership(snapshot);
        PipelineSnapshot data = applyOwnershipSeparation(snapshot, externallyModified);
        SolveContext context = new SolveContext(data.existingValues());
        SolveResult solveResult;
        if (solveRecipesEnabled) {
            solveResult = new RecipeSolver().solve(
                    data.recipes(), context, EvaluatorRegistry.defaults(), solveSettings);
        } else {
            solveResult = new SolveResult(
                    Map.of(), Map.of(), List.of(), Set.of(), 0, true, 0L, 0, Set.of());
        }

        List<Candidate> candidates = new ArrayList<>();
        int solvedByRecipe = addRecipeCandidates(solveResult, candidates);
        int solvedByFallback = addFallbackCandidates(data, solveResult, candidates);
        return new Computation(data, solveResult, List.copyOf(candidates),
                solvedByRecipe, solvedByFallback, externallyModified, startedAtNanos);
    }

    /**
     * 应用阶段：安全判定、溢出保护、存储写入、状态更新与报告落盘。必须在服务器线程执行。
     */
    private PipelineResult applyComputation(Computation computation) {
        PipelineSnapshot data = computation.snapshot();
        SolveResult solveResult = computation.solveResult();
        List<Candidate> candidates = computation.candidates();

        releaseExternallyModifiedOwnership(computation.externallyModified());

        Map<ItemKey, GeneratedEmcEntry> previousEntries =
                store == null ? Map.of() : store.snapshotAll();
        int overflowBefore = overflowPipeline == null ? 0 : overflowPipeline.resetsApplied();

        int applied = 0;
        int quarantined = 0;
        Map<String, Long> sourceCounts = new LinkedHashMap<>();

        for (Candidate candidate : candidates) {
            GeneratedEmcEntry previous = previousEntries.get(candidate.item());
            long previousValue = previous == null ? 0L : previous.value();

            PriceDropDecision decision = evaluateDropProtection(candidate, previousValue);
            if (!decision.allowed()) {
                quarantined++;
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
                        System.currentTimeMillis()));
                if (!staged) {
                    LOGGER.debug("物品 {} 的生成值未被存储接受，可能已有非 SudoersEMC 拥有的条目",
                            candidate.item());
                    continue;
                }
            }

            applied++;
            sourceCounts.merge(candidate.source().name(), 1L, Long::sum);
        }

        int overflowResets = overflowPipeline == null
                ? 0
                : Math.max(0, overflowPipeline.resetsApplied() - overflowBefore);
        int unresolved = solveResult.unresolvedItems().size();
        long durationMillis = elapsedMillis(computation.startedAtNanos());

        lastSolvedCount = applied;
        lastUnresolvedCount = unresolved;
        lastDurationMillis = durationMillis;

        boolean appliedToStore = store != null;
        updateScanState(data, durationMillis, applied, unresolved);
        writeDiagnosticReport(data, solveResult, applied, quarantined, overflowResets,
                durationMillis, appliedToStore, sourceCounts);

        return new PipelineResult(
                computation.solvedByRecipe(),
                computation.solvedByFallback(),
                applied,
                quarantined,
                overflowResets,
                unresolved,
                durationMillis,
                appliedToStore,
                sourceCounts);
    }

    /**
     * 把一次失败统一转换为全零结果，并清空最近一次运行的统计字段。
     */
    private PipelineResult failedResult(long startedAtNanos, Throwable failure) {
        long durationMillis = elapsedMillis(startedAtNanos);
        lastSolvedCount = 0;
        lastUnresolvedCount = 0;
        lastDurationMillis = durationMillis;
        LOGGER.error("SudoersEMC 扫描管线求解与应用失败，本轮返回全零结果", failure);
        return PipelineResult.zeroed(durationMillis);
    }

    /**
     * 把存储中归 SudoersEMC 所有的物品从“已有 EMC”中分离出来。
     *
     * <p>返回的物品快照保持输入顺序；自有条目会被重建为不含既有值的形式，标签与稀有度原样
     * 保留，后续求解与兜底定价因此可以重新计算它们。非自有条目保持原样，其既有值继续写入
     * {@code existingValues}，确保手工值或外部后端值不会被覆盖。</p>
     *
     * @param items 本次扫描到的物品快照，不能为 {@code null}
     * @param owned 归属判定；返回 {@code true} 表示该物品由 SudoersEMC 所有
     * @return 分离后的物品、既有值与刷新数量
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned) {
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
            }
        }
        return new OwnershipSeparation(separated, existingValues, refreshedOwned);
    }

    /**
     * 只读检测被外部修改过的自有条目。
     *
     * <p>采集阶段观察到的 ProjectE 当前值如果与持久化记录中的自有值不同，说明管理员或其它
     * 模组已经改写了该条目。检测只读取存储快照与本轮快照，不写任何状态，因此可以安全地在
     * 求解工作线程执行；真正的所有权迁移在服务器线程的 {@link #applyComputation} 中完成。</p>
     *
     * @return 被外部修改的物品集合；没有存储或读取失败时为空
     */
    private Set<ItemKey> detectExternallyModifiedOwnership(PipelineSnapshot snapshot) {
        if (store == null) {
            return Set.of();
        }
        Map<ItemKey, Long> observed = new TreeMap<>();
        for (ItemSnapshot item : snapshot.items()) {
            if (item.hasExistingEmc() && item.existingValue() > 0L) {
                observed.put(item.key(), item.existingValue());
            }
        }
        OwnershipManager manager = new OwnershipManager(store, item -> observed.getOrDefault(item, 0L));
        try {
            return Set.copyOf(manager.detectExternalModifications());
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 检测外部修改失败，本轮保留现有归属", failure);
            return Set.of();
        }
    }

    /**
     * 在服务器线程释放被外部修改条目的所有权。
     *
     * <p>迁移只改变 {@code owned} 标记，不修改数值；失败时保留原归属并记录警告。</p>
     *
     * @return 成功释放所有权的条目数量
     */
    private int releaseExternallyModifiedOwnership(Set<ItemKey> externallyModified) {
        if (store == null || externallyModified == null || externallyModified.isEmpty()) {
            return 0;
        }
        OwnershipManager manager = new OwnershipManager(store, item -> 0L);
        int released = 0;
        for (ItemKey item : externallyModified) {
            try {
                if (manager.releaseOwnership(item)) {
                    released++;
                }
            } catch (RuntimeException failure) {
                LOGGER.warn("释放外部修改条目的所有权失败：{}", item, failure);
            }
        }
        if (released > 0) {
            LOGGER.info("SudoersEMC 检测到 {} 个外部修改条目，已释放 {} 个条目的所有权",
                    externallyModified.size(), released);
        }
        return released;
    }

    /**
     * 在应用阶段执行归属分离。
     *
     * <p>分离放在应用阶段而不是采集阶段：采集只如实记录后端观察到的状态，是否归 SudoersEMC
     * 所有由持久化存储决定。外部修改的条目在本轮被当作受保护的既有值，绝不参与重新求解。</p>
     */
    private PipelineSnapshot applyOwnershipSeparation(PipelineSnapshot snapshot,
                                                      Set<ItemKey> externallyModified) {
        if (store == null) {
            return snapshot;
        }
        Set<ItemKey> protectedItems = externallyModified == null ? Set.of() : externallyModified;
        OwnershipSeparation separation = separateOwnedEntries(snapshot.items(),
                key -> store.isOwned(key) && !protectedItems.contains(key));
        if (separation.refreshedOwned() == 0) {
            return snapshot;
        }
        LOGGER.debug("SudoersEMC 归属分离刷新了 {} 个自有条目，本轮将重新求解其数值",
                separation.refreshedOwned());
        return new PipelineSnapshot(
                separation.items(),
                snapshot.recipes(),
                separation.existingValues(),
                snapshot.unsupportedRecipes(),
                snapshot.invalidRecipes(),
                snapshot.failedRecipes());
    }

    /**
     * 归属分离结果。
     *
     * @param items          分离后的物品快照，保持输入顺序
     * @param existingValues 仍然受保护的非自有既有值，按键升序排列
     * @param refreshedOwned 被清除既有值的自有条目数量
     */
    record OwnershipSeparation(List<ItemSnapshot> items, Map<ItemKey, Long> existingValues,
                               int refreshedOwned) {

        OwnershipSeparation {
            items = List.copyOf(items);
            existingValues = Collections.unmodifiableMap(new TreeMap<>(existingValues));
        }
    }

    /**
     * 纯计算结果载体：快照、求解结果、候选与计数。
     */
    private record Computation(
            PipelineSnapshot snapshot,
            SolveResult solveResult,
            List<Candidate> candidates,
            int solvedByRecipe,
            int solvedByFallback,
            Set<ItemKey> externallyModified,
            long startedAtNanos
    ) {

        private Computation {
            candidates = List.copyOf(candidates);
            externallyModified = externallyModified == null ? Set.of() : Set.copyOf(externallyModified);
        }
    }

    /**
     * @return 最近一次成功运行中实际写入存储（或干跑模式下本应写入）的条目数量
     */
    public int lastSolvedCount() {
        return lastSolvedCount;
    }

    /**
     * @return 最近一次运行结束时求解器仍未解析的物品数量
     */
    public int lastUnresolvedCount() {
        return lastUnresolvedCount;
    }

    /**
     * @return 最近一次运行的耗时毫秒数
     */
    public long lastDurationMillis() {
        return lastDurationMillis;
    }

    /**
     * 把求解器产出的配方值转换为候选条目。
     *
     * <p>求解器生成值具有权威性：这些物品不会再进入兜底定价链，来源固定为
     * {@link PriceSource#RECIPE}。配方 id 来自对应的 {@link SolveTrace}，缺失时允许为
     * {@code null}，不影响数值本身。</p>
     *
     * @return 追加的候选数量
     */
    private int addRecipeCandidates(SolveResult solveResult, List<Candidate> candidates) {
        int added = 0;
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            ItemKey item = entry.getKey();
            if (isExcludedFromPricing(item)) {
                LOGGER.debug("物品 {} 被排除规则命中，跳过配方候选", item);
                continue;
            }
            long value = entry.getValue();
            if (value <= 0L) {
                continue;
            }
            SolveTrace trace = solveResult.traces().get(item);
            ResourceLocation recipeId = trace == null ? null : trace.recipeId();
            candidates.add(new Candidate(item, value, PriceSource.RECIPE, recipeId));
            added++;
        }
        return added;
    }

    /**
     * 为求解器没有覆盖的物品追加兜底候选。
     *
     * <p>只处理既不在既有 EMC 中、也不在求解结果中的物品。创造物品保护决定物品是否允许自动
     * 定价；固定值优先于兜底定价链。兜底链返回非正值或空结果时不产生候选。</p>
     *
     * @return 追加的候选数量
     */
    private int addFallbackCandidates(PipelineSnapshot snapshot, SolveResult solveResult,
                                      List<Candidate> candidates) {
        int added = 0;
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            if (snapshot.existingValues().containsKey(item) || solveResult.values().containsKey(item)) {
                continue;
            }
            if (isExcludedFromPricing(item)) {
                LOGGER.debug("物品 {} 被排除规则命中，跳过兜底定价", item);
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
                candidates.add(new Candidate(item, decision.specialValue(), PriceSource.TIER, null));
                added++;
                continue;
            }

            if (fallbackPricing == null) {
                continue;
            }

            Optional<FallbackPricing.PricedValue> priced;
            try {
                long externalBaseline = useProjectEIntegrationBaselines
                        ? externalBaselineFor(itemSnapshot)
                        : 0L;
                priced = fallbackPricing.price(new FallbackPricing.PricingRequest(
                        item, itemSnapshot.tagPaths(), itemSnapshot.rarity(), 0L, externalBaseline));
            } catch (RuntimeException failure) {
                LOGGER.warn("兜底定价失败，跳过物品 {}", item, failure);
                continue;
            }
            if (priced.isEmpty()) {
                continue;
            }

            FallbackPricing.PricedValue pricedValue = priced.get();
            if (pricedValue.value() <= 0L) {
                continue;
            }
            candidates.add(new Candidate(item, pricedValue.value(), pricedValue.source(), null));
            added++;
        }
        return added;
    }

    /**
     * 判断物品是否被排除规则明确拒绝自动定价。
     *
     * <p>配方路径与兜底路径共用本方法，避免两处逻辑漂移：</p>
     * <ul>
     *   <li>{@link TierPricing#price} 返回 {@code Optional.of(0L)} 表示层级规则明确排除；</li>
     *   <li>{@link CreativeItemProtection#decide} 返回 {@code priceable=false} 表示创造物品保护排除。</li>
     * </ul>
     *
     * <p>变体的 {@link ItemKey} 与基础物品共享物品 id，因此排除规则天然覆盖全部 NBT 变体。</p>
     *
     * @param item 待判定物品身份
     * @return {@code true} 表示该物品不得进入候选或写入存储
     */
    private boolean isExcludedFromPricing(ItemKey item) {
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
     * 读取物品的 ProjectE 集成基线。
     *
     * <p><strong>Phase 12 阶段说明：</strong>本阶段没有接入外部基线数据源，返回值恒为
     * {@code 0}。Phase 25 的 {@code ExternalBaselineSource} 接线后由本方法转发真实基线。
     * 配置开关 {@code fallback.useProjectEIntegrationBaselines=false} 时调用方会直接传
     * {@code 0}，不会走到这里。</p>
     */
    private long externalBaselineFor(ItemSnapshot item) {
        return 0L;
    }

    /**
     * 判定一次拟写入值是否被大幅降价保护隔离。
     *
     * <p>保护本身抛出异常时选择放行并记录警告：保护层是安全辅助，不应因为单个物品的异常状态
     * 阻断整轮扫描。放行结果使用 {@code protection-error} 作为机器可读原因。</p>
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
     * 对候选值应用溢出保护并写入审计日志。
     *
     * <p>没有配置溢出管线时原值返回。管线内部已经保证不抛异常，这里仍然兜底一层，避免未来实现
     * 变化破坏“整轮扫描绝不中断”的契约。</p>
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
     * 更新增量扫描状态。状态存储自身不会抛出异常，这里仍捕获运行时异常以保护结果返回路径。
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
     * 写出诊断报告。报告写入失败只影响诊断文件，不影响本轮数值结果。
     */
    private void writeDiagnosticReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                       int applied, int quarantined, int overflowResets,
                                       long durationMillis, boolean appliedToStore,
                                       Map<String, Long> sourceCounts) {
        if (!generateReportsEnabled || reportManager == null) {
            return;
        }
        try {
            reportManager.writeReport(buildReport(snapshot, solveResult, quarantined,
                    overflowResets, durationMillis, appliedToStore, sourceCounts));
        } catch (RuntimeException failure) {
            LOGGER.warn("写入诊断报告失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * 根据本轮运行数据构造诊断报告。
     *
     * <p>标准字段只统计实际应用的条目；求解器的失败配方、轮数与干跑标记等放入
     * {@code extraCounters}，便于后续解析器在不改变报告结构的情况下读取。</p>
     */
    private static DiagnosticReport buildReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                                int quarantined, int overflowResets,
                                                long durationMillis, boolean appliedToStore,
                                                Map<String, Long> sourceCounts) {
        Map<String, Long> extraCounters = new LinkedHashMap<>();
        extraCounters.put("unsupportedRecipes", (long) snapshot.unsupportedRecipes());
        extraCounters.put("invalidRecipes", (long) snapshot.invalidRecipes());
        extraCounters.put("failedRecipes", (long) snapshot.failedRecipes());
        extraCounters.put("solverPasses", (long) solveResult.passesUsed());
        extraCounters.put("recipesEvaluated", (long) solveResult.recipesEvaluated());
        extraCounters.put("solverConverged", solveResult.converged() ? 1L : 0L);
        extraCounters.put("overflowResets", (long) overflowResets);
        extraCounters.put("dryRun", appliedToStore ? 0L : 1L);

        return new DiagnosticReport(
                snapshot.itemCount(),
                snapshot.existingValues().size(),
                sourceCounts.getOrDefault(PriceSource.RECIPE.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.TAG.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.RARITY.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.TIER.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.FALLBACK.name(), 0L),
                solveResult.unresolvedItems().size(),
                quarantined,
                snapshot.failedRecipes(),
                durationMillis,
                usedHeapBytes(),
                extraCounters);
    }

    /**
     * 计算扫描指纹：物品 id、配方 id 升序摘要；标签、配置与基线通道本轮为空。
     */
    private static ScanFingerprint fingerprint(PipelineSnapshot snapshot) {
        List<String> itemIds = new ArrayList<>(snapshot.items().size());
        for (ItemSnapshot item : snapshot.items()) {
            itemIds.add(item.registryId().toString());
        }
        List<String> recipeIds = new ArrayList<>(snapshot.recipes().size());
        for (RecipeSnapshot recipe : snapshot.recipes()) {
            recipeIds.add(recipe.id().toString());
        }
        return ScanFingerprint.of(itemIds, recipeIds, List.of(), List.of(), List.of());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * 一条待写入的候选值。
     *
     * @param item     物品身份
     * @param value    经过求解或兜底定价得到的原始数值，保证 {@code > 0}
     * @param source   数值来源
     * @param recipeId 产生该值的配方，兜底定价结果为 {@code null}
     */
    private record Candidate(ItemKey item, long value, PriceSource source,
                             @Nullable ResourceLocation recipeId) {
    }
}
