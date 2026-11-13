package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.DebugLog;
import com.itzplana.sudoersemc.core.VariantRegistration;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.ExternalBaselineSource;
import com.itzplana.sudoersemc.integration.ProjectECustomEmcFile;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.TierPricing;
import com.itzplana.sudoersemc.resolver.ResolverRegistry;
import com.itzplana.sudoersemc.resolver.VariantNormalizer;
import com.itzplana.sudoersemc.resolver.VariantScanner;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OwnershipManager;
import com.itzplana.sudoersemc.safety.PriceDropDecision;
import com.itzplana.sudoersemc.safety.OwnershipManager;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.GoetyRitualReader;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeScanner;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
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
import com.itzplana.sudoersemc.task.ScanManager;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
 * 把扫描、配方求解、兜底定价、安全层与持久化串联起来的运行时管线。
 *
 * <p>一次完整扫描分为两个阶段：</p>
 * <ol>
 *   <li>{@link #collect(MinecraftServer)} 在服务器线程读取物品注册表与配方管理器，产出不可变的
 *       {@link PipelineSnapshot}；</li>
 *   <li>{@link #solveAndApply(PipelineSnapshot)} 只做计算与存储写入；它自身不直接读取注册表，
 *       但注入的 ProjectE 手工值判定器在解析标签条目时可能访问物品注册表与标签管理器，
 *       因此该方法必须由服务器线程调用。</li>
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
 * 抛出 {@link IllegalStateException}；{@link #solveAndApply(PipelineSnapshot)} 在求解或应用
 * 过程遇到运行时异常时记录日志并转换为 {@link IllegalStateException}；
 * {@link #applyCompletedSolve()} 在后台求解失败、被取消或没有待处理结果时同样抛出
 * {@link IllegalStateException}。这些异常由 {@code ScanManager} 转为 {@code FAILED}，绝不把
 * 失败伪装成“成功的空扫描”或全零结果，避免服务器主循环被静默的错误状态污染。</p>
 */
public final class ScanPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanPipeline");

    /**
     * 兜底链贯通的最大求解轮数。
     *
     * <p>取 4 的原因：第一轮负责给只能依靠 TIER/TAG/RARITY/FALLBACK 定价的叶子物品写入种子，
     * 每次兜底补种后都需要重新求解，让新种子向配方上游传播。按求解器 {@code maxPasses=1}
     * 的最坏情况估算，4 轮可以覆盖“兜底种子 + 3 层中间产物”的常见模组链
     * （例如矿石 → 锭 → 齿轮/块）；默认 {@code maxPasses=20} 时通常 2 到 3 轮即收敛。
     * 循环内不会给任何配方输出补种，只有收敛后的最终兜底才会处理它们，避免兜底值抢占
     * 本应由配方成本决定的中间产物。上限同时把异常配方图的开销限制在常量级。</p>
     */
    static final int MAX_SOLVE_ROUNDS = 4;

    /** NBT 变体生成时每处理多少个观测条目检查一次取消信号。 */
    static final int NBT_VARIANT_CANCEL_CHECK_INTERVAL = 64;

    /** 应用循环每处理多少个候选检查一次取消信号。 */
    static final int APPLY_CANCEL_CHECK_INTERVAL = 64;

    /** 既有值收集每处理多少个物品检查一次取消信号。 */
    static final int EXISTING_VALUE_CANCEL_CHECK_INTERVAL = 256;

    /** 兜底/合并/清理等全量循环每处理多少个条目检查一次取消信号。 */
    static final int FULL_SWEEP_CANCEL_CHECK_INTERVAL = 256;
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
    private volatile boolean solveRecipesEnabled = true;
    private volatile boolean generateReportsEnabled = true;
    private volatile boolean nbtVariantsEnabled = true;
    private volatile int maxNbtVariantsPerItem = 100;
    private volatile boolean scanContainerRemainders = true;
    private volatile boolean creditContainerRemainders = true;
    private volatile boolean priceSecondaryOutputs = false;
    private volatile boolean preserveExternallyModifiedValues = true;
    private volatile Supplier<Predicate<ItemKey>> manualValueDetectorSupplier = () -> item -> false;
    /** 外部基线来源；默认不可用，行为与 Phase 24 完全一致。 */
    private volatile ExternalBaselineSource externalBaselineSource = ExternalBaselineSource.unavailable();
    /** 第三方 NBT 材料解析器注册表；默认空表，不重建 ItemStack、不解析 SNBT。 */
    private volatile ResolverRegistry resolverRegistry = ResolverRegistry.defaults();

    private final AtomicReference<PipelineSnapshot> stashedSnapshot = new AtomicReference<>();
    private volatile RecipeSolver recipeSolver = new RecipeSolver();
    private final EvaluatorRegistry evaluatorRegistry = EvaluatorRegistry.defaults();

    /** 协作式取消检查；默认永不取消，由调度器注入共享令牌。 */
    private volatile BooleanSupplier cancellationCheck = () -> false;
    /** 配置与外部基线的指纹来源；默认空表示测试/嵌入式环境没有额外输入。 */
    private volatile Supplier<List<String>> configFingerprintSupplier = List::of;
    private volatile Supplier<List<String>> baselineFingerprintSupplier = List::of;
    /** 取消或失败时写部分报告所需的最近一次快照。 */
    private volatile PipelineSnapshot lastSnapshotForReport;
    private volatile int lastSolverCacheHits;
    private volatile int lastSolverCacheMisses;
    private volatile int lastOwnershipExternalModifications;
    private volatile int lastOwnershipReleased;
    private volatile int lastResolverApplied;
    private final AtomicBoolean manualDetectorFailureWarned = new AtomicBoolean(false);
    /**
     * 跨实例共享的重映射确认状态。配置重载会重建整个管线，失败保护不能随旧实例丢失；
     * 生产环境由 {@code Sudoersemc} 注入同一个 tracker，测试默认使用独立实例。
     */
    private volatile RemapTracker remapTracker = new RemapTracker();
    private final AtomicReference<CompletableFuture<Computation>> pendingSolve = new AtomicReference<>();

    private volatile EmcProvider emcProvider;
    private volatile OverflowScope overflowScope = OverflowScope.OWNED_ONLY;
    private volatile boolean allowProjectEFileRewrite;
    private volatile Path projectECustomEmcFile;
    private volatile boolean useProjectEIntegrationBaselines = true;

    /**
     * 完整扫描输入指纹提供器，必须在服务器线程调用。
     *
     * <p>生产接线由 {@code Sudoersemc} 注入物品注册表、配方 id、标签与配置摘要；注入后，
     * 扫描状态文件会保存完整输入指纹，启动缓存只有在该指纹完全匹配时才会复用。未注入时
     * 退回基于本次快照的指纹，仅用于不接触游戏状态的单元测试。</p>
     */
    @Nullable
    private volatile Supplier<ScanFingerprint> inputFingerprintSupplier;
    private volatile int lastSolvedCount;
    private volatile int lastUnresolvedCount;
    private volatile int lastSolverRoundCount;
    private volatile long lastDurationMillis;
    private volatile ScanDiagnostics diagnostics = new ScanDiagnostics();
    /** 本轮扫描是否已经成功写出完整报告；只对单次扫描有效，由 {@link #beginScan()} 重置。 */
    private volatile boolean fullReportWritten;
    /** 最近一次采集失败的中文原因；采集成功后清空，供应用阶段写出失败报告。 */
    private volatile String lastCollectFailure;
    /** 等待扫描完成回调写出的完整报告参数；为空表示没有待写报告。 */
    private volatile PendingReport pendingReport;

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
     * {@link OverflowScope#ENTIRE_CUSTOM_EMC} 仅扩大扫描范围到 ProjectE 的
     * {@code custom_emc.json}，不会授权改写非自有条目；传入 {@code null} 时恢复默认值，
     * 绝不隐式扩大作用域。</p>
     *
     * @param scope 作用域，允许为 {@code null}
     */
    public void setOverflowScope(@Nullable OverflowScope scope) {
        this.overflowScope = scope == null ? OverflowScope.OWNED_ONLY : scope;
    }

    /**
     * 设置是否允许改写 ProjectE 的 {@code custom_emc.json} 中归 SudoersEMC 所有的条目。
     *
     * <p>默认 {@code false}。只有同时启用 {@link OverflowScope#ENTIRE_CUSTOM_EMC} 作用域时才会
     * 产生实际文件写入；这是避免误改管理员手动配置的第二道保险。无论该开关如何设置，
     * 非自有（管理员手动）条目都不会被改写。</p>
     *
     * @param enabled 是否允许改写 ProjectE 文件中的自有条目
     */
    public void setProjectEFileRewriteEnabled(boolean enabled) {
        this.allowProjectEFileRewrite = enabled;
    }

    /**
     * 设置是否启用配方求解。
     *
     * <p>关闭后管线不再运行 {@link RecipeSolver}，只使用快照中的既有值与兜底定价链。
     * 该开关在每次扫描开始时读取，重建管线后立即生效。</p>
     *
     * @param enabled {@code true} 表示启用配方求解；默认启用
     */
    public void setSolveRecipesEnabled(boolean enabled) {
        this.solveRecipesEnabled = enabled;
    }

    /**
     * 设置是否在配方扫描阶段读取容器返还物（桶、空瓶等）。
     */
    public void setScanContainerRemaindersEnabled(boolean enabled) {
        this.scanContainerRemainders = enabled;
    }

    /**
     * 设置是否允许容器返还物抵扣配方成本。
     *
     * <p>关闭后扫描仍可记录返还物（用于诊断/指纹），但求解器不会从净成本中扣除。</p>
     */
    public void setCreditContainerRemaindersEnabled(boolean enabled) {
        if (this.creditContainerRemainders != enabled) {
            this.creditContainerRemainders = enabled;
            rebuildRecipeSolver();
        }
    }

    /**
     * 设置多输出配方是否为副产物发布单位价值。
     *
     * <p>默认关闭：整条配方的净成本只由主产物承担，副产物不会稀释主产物价格。开启后
     * 成本按所有输出单位均摊，同一物品在一次求解中仍只会发布一次值。</p>
     */
    public void setPriceSecondaryOutputsEnabled(boolean enabled) {
        if (this.priceSecondaryOutputs != enabled) {
            this.priceSecondaryOutputs = enabled;
            rebuildRecipeSolver();
        }
    }

    /**
     * 设置外部/手工值保护策略。
     *
     * <p>默认 {@code true}：检测到 owned 条目被外部改动（ProjectE 手动值、其它模组或管理员
     * 直接写值）时释放所有权并保留外部值；{@code false} 时退回 Phase 14 的“自有条目总是刷新”
     * 语义。该开关只影响检测，不会让被保护的值重新进入自动定价。</p>
     */
    public void setPreserveExternallyModifiedValues(boolean enabled) {
        this.preserveExternallyModifiedValues = enabled;
    }

    /**
     * 注入共享的重映射确认状态。配置重载重建管线时必须复用同一个实例，
     * 否则一次失败的重映射保护会在新管线的首轮丢失。
     *
     * @param tracker 共享 tracker，允许为 {@code null}（恢复为独立实例）
     */
    public void setRemapTracker(@Nullable RemapTracker tracker) {
        this.remapTracker = tracker == null ? new RemapTracker() : tracker;
    }

    /**
     * 记录一次已请求的后端 EMC 重映射，并在完成后解除“外部修改检测暂停”状态。
     *
     * <p>重映射是异步的：完成前后端仍可能返回 SudoersEMC 写入前的旧值。如果此时把旧值
     * 当作外部修改，会误释放所有权；由于存储拒绝覆盖非自有条目，错误释放后这些物品无法
     * 自动重新接管。因此完成信号落地前只检测 ProjectE 手工值，不比较后端漂移。</p>
     *
     * <p>传入 {@code null} 表示后端无法报告完成信号，此时保持现有检测行为不变。</p>
     *
     * @param completion 重映射完成信号，允许为 {@code null}
     */
    public void trackRemapCompletion(@Nullable CompletableFuture<?> completion) {
        remapTracker.track(completion);
    }

    /**
     * @return 是否存在尚未确认完成的后端重映射
     */
    private boolean remapConfirmationPending(PipelineSnapshot snapshot) {
        long generation = snapshot == null
                ? PipelineSnapshot.UNKNOWN_REMAP_GENERATION
                : snapshot.remapGeneration();
        return remapTracker.confirmationPending(generation);
    }

    /**
     * 注入手工值判定器来源。来源在每次应用阶段调用一次，返回的判定器会被复用于本轮全部物品。
     * 传入 {@code null} 恢复“没有任何手工值”的默认行为。
     */
    public void setManualValueDetectorSupplier(
            @Nullable Supplier<Predicate<ItemKey>> supplier) {
        this.manualValueDetectorSupplier = supplier == null
                ? () -> item -> false
                : supplier;
    }

    /**
     * 便捷重载：直接注入一个手工值判定器。
     */
    public void setManualValueDetector(@Nullable Predicate<ItemKey> detector) {
        Predicate<ItemKey> effective = detector == null ? item -> false : detector;
        setManualValueDetectorSupplier(() -> effective);
    }

    /**
     * 注入外部基线来源。传入 {@code null} 恢复不可用来源，行为与 Phase 24 完全一致。
     *
     * @param source 外部基线来源，允许为 {@code null}
     */
    public void setExternalBaselineSource(@Nullable ExternalBaselineSource source) {
        this.externalBaselineSource = source == null
                ? ExternalBaselineSource.unavailable()
                : source;
    }

    /**
     * 注入 NBT 材料解析器注册表。传入 {@code null} 或空表时不会为任何物品重建 ItemStack。
     *
     * @param registry 解析器注册表，允许为 {@code null}
     */
    public void setResolverRegistry(@Nullable ResolverRegistry registry) {
        this.resolverRegistry = registry == null ? ResolverRegistry.defaults() : registry;
    }

    /**
     * 按当前评估策略重建求解器。重建会丢弃评估缓存，但保留已累计的命中/未命中计数器。
     */
    private synchronized void rebuildRecipeSolver() {
        recipeSolver = new RecipeSolver(new EvaluationPolicy(
                EvaluationPolicy.DEFAULT_MINIMUM_EMC,
                creditContainerRemainders,
                priceSecondaryOutputs));
    }

    /**
     * 注入与 {@link com.itzplana.sudoersemc.task.ScanManager} 共享的诊断采样器。
     *
     * <p>注入后报告可以读取整个扫描跨 tick 的耗时、P95 与峰值堆内存；传入 {@code null}
     * 时恢复为管线内部新建的采样器。采集阶段调用 {@link ScanDiagnostics#reset()} 的工作由
     * 扫描调度器负责，管线只负责读取。</p>
     *
     * @param newDiagnostics 诊断采样器，允许为 {@code null}
     */
    public void setDiagnostics(@Nullable ScanDiagnostics newDiagnostics) {
        this.diagnostics = newDiagnostics == null ? new ScanDiagnostics() : newDiagnostics;
    }

    /**
     * @return 当前诊断采样器；默认实例也会在报告时被动采样堆内存
     */
    public ScanDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * 注入协作式取消检查。
     *
     * <p>检查会在物品采集、配方扫描、NBT 变体生成、求解轮次与应用循环中被调用。传入
     * {@code null} 恢复“永不取消”。</p>
     *
     * @param check 取消检查，可为 {@code null}
     */
    public void setCancellationCheck(@Nullable BooleanSupplier check) {
        this.cancellationCheck = check == null ? () -> false : check;
    }

    /**
     * 注入配置指纹来源。来源返回的字符串应稳定、可排序；读取失败时管线会退化为一个固定的
     * 错误标记，不会阻止扫描。
     *
     * @param supplier 配置条目来源，可为 {@code null}
     */
    public void setConfigFingerprintSupplier(@Nullable Supplier<List<String>> supplier) {
        this.configFingerprintSupplier = supplier == null ? List::of : supplier;
    }

    /**
     * 注入外部基线指纹来源（例如 ProjectE 手动 EMC 文件）。
     *
     * @param supplier 基线条目来源，可为 {@code null}
     */
    public void setBaselineFingerprintSupplier(@Nullable Supplier<List<String>> supplier) {
        this.baselineFingerprintSupplier = supplier == null ? List::of : supplier;
    }

    /**
     * @return 是否已经请求协作式取消
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
     * 采集当前输入指纹，供服务器启动时与持久化状态比较。
     *
     * <p>该方法只读取物品注册表、物品标签与配方管理器，不查询 EMC 后端、不求解、不写存储。
     * 物品条目按键去重并包含稀有度，配方条目包含完整内容摘要；配置与外部基线通过注入的
     * 来源读取。任何异常都会返回 {@code null}，调用方应退化为“需要扫描”。</p>
     *
     * @param server 当前服务器，可为 {@code null}
     * @return 当前输入指纹；无法计算时返回 {@code null}
     */
    @Nullable
    public ScanFingerprint collectFingerprint(@Nullable MinecraftServer server) {
        if (server == null) {
            return null;
        }
        // 启动指纹与随后扫描应用阶段的基线指纹必须来自同一份数据，否则每次启动都会误判为
        // “基线通道变化”并触发无意义的重扫。
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
     * 根据物品、配方、配置与基线输入构造五通道指纹。
     *
     * <p>所有集合都先落入 {@link TreeSet} 再交给 {@link ScanFingerprint} 排序哈希，因此结果不受
     * HashMap 遍历顺序影响。物品通道包含注册表 id 与稀有度，配方通道包含配方完整内容摘要，
     * 标签通道记录“物品 -> 标签”的映射而不是所有标签的并集。</p>
     *
     * @param items           物品快照，允许为空
     * @param recipes         配方快照，允许为空
     * @param configEntries   配置条目，允许为 {@code null}
     * @param baselineEntries 外部基线条目，允许为 {@code null}
     * @return 五通道指纹
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
                // 标签通道必须记录“物品 -> 标签”的映射，不能只记录并集：
                // 两个物品互换标签时并集不变，但 TAG 定价结果会变化。
                List<String> sortedTags = new ArrayList<>(item.tagPaths());
                Collections.sort(sortedTags);
                tagEntries.add(item.registryId() + "=" + String.join(",", sortedTags));
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
     * 生成一条配方的稳定内容摘要。字段顺序固定，属性按 key 排序。
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
     * 设置是否写出诊断报告。
     *
     * <p>关闭后 {@code last_report.txt} 不再更新，但本轮扫描的计数、状态存储与持久化
     * 行为保持不变。该开关在每次扫描开始时读取，重建管线后立即生效。</p>
     *
     * @param enabled {@code true} 表示写出诊断报告；默认启用
     */
    public void setGenerateReportsEnabled(boolean enabled) {
        this.generateReportsEnabled = enabled;
    }

    /**
     * 设置是否扫描并生成 NBT 变体快照。
     *
     * <p>关闭后 {@link #collect(MinecraftServer)} 完全不生成变体，基础物品快照不受影响。
     * 该开关在每次采集开始时读取，重建管线后立即生效。</p>
     *
     * @param enabled {@code true} 表示启用 NBT 变体扫描；默认启用
     */
    public void setNbtVariantsEnabled(boolean enabled) {
        this.nbtVariantsEnabled = enabled;
    }

    /**
     * 设置每个物品最多保留的 NBT 变体数量。
     *
     * <p>取值会被限制在 {@code [1, }{@link VariantScanner#HARD_VARIANT_CEILING}{@code ]}
     * 内，避免异常配置导致变体数量失控。该上限在每次采集开始时读取。</p>
     *
     * @param maxVariantsPerItem 每个物品最多保留的变体数
     */
    public void setMaxNbtVariantsPerItem(int maxVariantsPerItem) {
        this.maxNbtVariantsPerItem = Math.max(
                1, Math.min(maxVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));
    }

    /**
     * 注入隔离记录存储。
     *
     * <p>注入后，每当大幅降价保护判定隔离时，管线会把旧值、拟写入值、降幅、原因与时间戳
     * 写入该存储，供管理员通过 {@code /sudoemc approve} 审批。传入 {@code null} 时只保留原有
     * 的内存计数与日志行为。干跑模式（生成条目存储为 {@code null}）永远不会写隔离文件。</p>
     *
     * @param newStore 隔离记录存储，允许为 {@code null}
     */
    public void setQuarantinedDropsStore(@Nullable QuarantinedDropsStore newStore) {
        dropProtection.setQuarantineStore(newStore);
    }

    /**
     * 覆盖 ProjectE 手动 EMC 文件路径，主要用于测试或嵌入式场景。
     *
     * <p>传入 {@code null} 时恢复生产路径（{@code config/ProjectE/custom_emc.json}）。</p>
     *
     * @param file 自定义路径，允许为 {@code null}
     */
    public void setProjectECustomEmcFile(@Nullable Path file) {
        this.projectECustomEmcFile = file;
    }

    /**
     * 安全准备外部基线来源。任何异常都只记录警告，本轮按空基线继续。
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
     * 返回本次扫描结束时可输出的 {@link DebugLog#summary()}。
     *
     * <p>调试日志关闭或没有任何统计时返回空，生产路径因此保持零开销。包内可见便于单元
     * 测试验证 summary 已接线，而不是只存在于 {@link DebugLog} 内部。</p>
     *
     * @return 非空的限流统计汇总
     */
    static Optional<String> debugSummary() {
        if (!DebugLog.isEnabled()) {
            return Optional.empty();
        }
        String summary = DebugLog.summary();
        return summary.isEmpty() ? Optional.empty() : Optional.of(summary);
    }

    private static void logDebugSummary() {
        try {
            debugSummary().ifPresent(summary ->
                    LOGGER.info("SudoersEMC 扫描调试日志汇总：{}", summary));
        } catch (Throwable failure) {
            // 诊断汇总失败绝不能覆盖扫描结果或原始异常。
            LOGGER.debug("输出调试日志汇总失败", failure);
        }
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
     * 注册完整扫描输入指纹提供器。
     *
     * <p>提供器在服务器线程的求解启动阶段调用一次，其返回值会随扫描状态一起持久化。
     * 启动缓存判定必须使用同一套指纹通道，否则模组、配方或配置变化后仍可能误用旧值。</p>
     *
     * @param supplier 指纹提供器；{@code null} 时退回基于本次快照的指纹
     */
    public void setInputFingerprintSupplier(@Nullable Supplier<ScanFingerprint> supplier) {
        this.inputFingerprintSupplier = supplier;
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
String detail = "扫描采集失败：服务器尚未就绪";
            LOGGER.warn("SudoersEMC 扫描采集被调用时服务器尚未就绪，本次扫描失败");
            lastCollectFailure = detail;
            throw new IllegalStateException(detail);
        }
        prepareExternalBaseline(server);

        try {
            CollectionSession session = new CollectionSession(server, false);
            while (!session.isDone()) {
                session.step(Integer.MAX_VALUE);
            }
            return session.buildSnapshot();
        } catch (Throwable failure) {
            String detail = "扫描采集失败：" + failureDetail(failure);
            LOGGER.error("SudoersEMC 扫描采集失败", failure);
            lastCollectFailure = detail;
            throw new IllegalStateException(detail, failure);
        }
    }

    /**
     * 为单个 NBT 变体补读后端的精确既有值。
     *
     * <p>{@link ItemScanner} 只查询无 NBT 的默认堆叠，变体快照默认没有既有值。若不补读，
     * owned 变体的外部修改永远不会进入外部修改检测，非自有变体的后端值也会被配方定价覆盖。
     * 查询必须使用 {@link ItemScanner.EmcQuery#getExactValue} 口径：ProjectE 的
     * {@code hasValue/getValue} 会在 NBT 未命中时回退基础物品值并叠加 Damage / Enchantment /
     * StoredEMC 等处理器，不能作为“该变体自身已有值”的依据；返回 {@code 0} 视为无既有值。
     * 后端不可用、无法还原变体或查询失败时原样返回。</p>
     *
     * @param snapshot 变体快照
     * @param query    EMC 查询，必须与基础扫描使用同一后端
     * @return 携带既有值的新快照，或原快照
     */
    static ItemSnapshot readVariantExistingValue(ItemSnapshot snapshot, ItemScanner.EmcQuery query) {
        if (snapshot == null || !snapshot.key().hasVariant() || query == null) {
            return snapshot;
        }
        ItemStack stack = variantStack(snapshot.key());
        if (stack == null) {
            return snapshot;
        }
        try {
            // ProjectE 的 hasValue/getValue 在 NBT 未命中时会回退基础物品值并叠加
            // Damage/Enchantment/StoredEMC 等处理器，不能据此认定该变体自身已有值；
            // 只有精确 NBT 映射才与 store 写入值同口径。
            long value = Math.max(0L, query.getExactValue(stack));
            if (value <= 0L) {
                return snapshot;
            }
            return ItemSnapshot.of(snapshot.key(), true, value,
                    snapshot.tagPaths(), snapshot.rarity());
        } catch (RuntimeException failure) {
            LOGGER.debug("读取 NBT 变体 {} 的后端 EMC 失败，按无既有值处理", snapshot.key(), failure);
            return snapshot;
        }
    }

    /**
     * 把变体键还原为带 NBT 的物品堆叠；无法解析时返回 {@code null}。
     */
    @Nullable
    private static ItemStack variantStack(ItemKey key) {
        if (key == null || !key.hasVariant()) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.get(key.itemId());
        if (item == null || item == Items.AIR) {
            return null;
        }
        try {
            CompoundTag tag = TagParser.parseTag(key.variantHash());
            ItemStack stack = new ItemStack(item);
            stack.setTag(tag);
            return stack;
        } catch (Exception failure) {
            LOGGER.debug("无法把变体 {} 还原为 ItemStack，跳过既有值读取", key, failure);
            return null;
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
            return collectSnapshotData(items, scanResult, emcQuery());
        }
    }

    /**
     * 把已完成的物品与配方扫描结果组装成不可变快照。
     *
     * <p>该方法从管线字段读取 NBT 变体开关与单物品上限，是 {@link #collect(MinecraftServer)}
     * 的可测试接缝：生产路径与测试都走同一段变体生成与既有值查询逻辑。</p>
     *
     * @param items      基础物品扫描结果，不能为 null
     * @param scanResult 配方扫描结果，不能为 null
     * @param emcQuery   后端查询；允许为 {@code null}（测试或后端缺失时按无既有值处理）
     * @return 组装后的快照
     */
    PipelineSnapshot collectSnapshotData(List<ItemSnapshot> items,
                                         RecipeScanner.ScanResult scanResult,
                                         @Nullable ItemScanner.EmcQuery emcQuery) {
        return collectSnapshotData(items, scanResult, emcQuery, remapTracker.generation());
    }

    /**
     * 带采集代次的组装入口：代次必须在读取任何后端值之前捕获，
     * 才能在 apply 阶段识别“采集结束后、应用开始前完成的重映射”。
     */
    PipelineSnapshot collectSnapshotData(List<ItemSnapshot> items,
                                         RecipeScanner.ScanResult scanResult,
                                         @Nullable ItemScanner.EmcQuery emcQuery,
                                         long remapGeneration) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(scanResult, "scanResult");
        List<ItemSnapshot> variantItems = collectVariantSnapshots(
                items, scanResult.recipes(), nbtVariantsEnabled, maxNbtVariantsPerItem,
                emcQuery, this::isCancelled);
        List<ItemSnapshot> allItems = items;
        if (!variantItems.isEmpty()) {
            List<ItemSnapshot> combined = new ArrayList<>(items.size() + variantItems.size());
            combined.addAll(items);
            combined.addAll(variantItems);
            allItems = List.copyOf(combined);
        }

        Map<ItemKey, Long> existingValues = new TreeMap<>();
        List<ItemSnapshot> resolvedItems = new ArrayList<>(allItems.size());
        int inspected = 0;
        for (ItemSnapshot item : allItems) {
            if (inspected++ % EXISTING_VALUE_CANCEL_CHECK_INTERVAL == 0 && isCancelled()) {
                return partialSnapshot(allItems, scanResult.recipes(), remapGeneration);
            }
            // 基础扫描只查询无 NBT 的默认堆叠；变体必须单独回读后端值，
            // 否则 owned 变体的外部修改永远不会进入外部修改检测。
            ItemSnapshot resolved = item;
            if (!item.hasExistingEmc() && item.key().hasVariant()) {
                resolved = readVariantExistingValue(item, emcQuery);
            }
            if (resolved.hasExistingEmc()) {
                existingValues.put(resolved.key(), resolved.existingValue());
            }
            resolvedItems.add(resolved);
        }

        return new PipelineSnapshot(
                List.copyOf(resolvedItems),
                scanResult.recipes(),
                existingValues,
                scanResult.unsupported(),
                scanResult.invalid(),
                scanResult.failed(),
                scanResult.enumerationFailed(),
                scanResult.failedRecipeDetails(),
                remapGeneration);
    }

    /**
     * 构造取消时的部分快照：物品/配方保持已采集部分，既有值只从当前物品列表重新推导。
     */
    private static PipelineSnapshot partialSnapshot(List<ItemSnapshot> items,
                                                    List<RecipeSnapshot> recipes,
                                                    long remapGeneration) {
        Map<ItemKey, Long> existingValues = new TreeMap<>();
        for (ItemSnapshot item : items) {
            if (item.hasExistingEmc()) {
                existingValues.put(item.key(), item.existingValue());
            }
        }
        return new PipelineSnapshot(items, recipes, existingValues, 0, 0, 0, false, List.of(),
                remapGeneration);
    }

    /**
     * 根据配方输出中观测到的 SNBT 生成 NBT 变体快照。
     *
     * <p>先按物品 id 汇总并去重原始 SNBT，再逐个解析、规范化；每个物品只保留字典序最小的
     * {@code maxNbtVariantsPerItem} 个变体。解析是流式的：同一时刻只持有一个临时
     * {@link CompoundTag}，不会把与配方数成正比的解析结果全部留在内存中。全部物品共享
     * {@link VariantScanner#HARD_TOTAL_VARIANT_CEILING} 的总预算。只有已在基础物品快照中
     * 出现过的物品才会生成变体，因此忽略规则与注册表缺失物品不会被变体扫描重新引入。</p>
     *
     * <p>变体快照的 {@code hasExistingEmc}/{@code existingValue} 来自对带原始 NBT 堆叠的
     * 精确查询：只要任一观测原始 SNBT 已有 NBT 专属 EMC，就取最大值作为既有值，避免覆盖
     * 其它模组或 ProjectE NBT 处理器注册的值。标签路径与稀有度继承基础物品。</p>
     *
     * @param baseItems            基础物品快照，保持扫描器返回的顺序
     * @param recipes              本轮可用配方
     * @param enabled              是否启用变体扫描
     * @param maxNbtVariantsPerItem 每个物品最多保留的变体数
     * @return 不可变的变体快照列表；未启用、无观测变体或全部无法解析时为空
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem) {
return collectVariantSnapshots(baseItems, recipes, enabled, maxNbtVariantsPerItem,
                (ItemScanner.EmcQuery) null, () -> false);
    }

    /**
     * 带协作式取消检查的 NBT 变体生成。
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem,
                                                      BooleanSupplier cancellation) {
        return collectVariantSnapshots(baseItems, recipes, enabled, maxNbtVariantsPerItem,
                null, cancellation);
    }

    /**
     * 带 EMC 精确查询的变体采集重载。
     *
     * @param emcQuery 精确 NBT 查询；{@code null} 时按无既有值处理
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem,
@Nullable ItemScanner.EmcQuery emcQuery) {
        return collectVariantSnapshots(baseItems, recipes, enabled, maxNbtVariantsPerItem,
                emcQuery, () -> false);
    }

    /**
     * 核心重载：同时支持精确 NBT 查询与协作式取消检查。
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem,
                                                      @Nullable ItemScanner.EmcQuery emcQuery,
                                                      BooleanSupplier cancellation) {
        if (!enabled || baseItems == null || baseItems.isEmpty()
                || recipes == null || recipes.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxNbtVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));

Map<ResourceLocation, TreeSet<String>> observedByItem = new TreeMap<>();
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
                        .computeIfAbsent(output.item().itemId(), id -> new TreeSet<>())
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

        Set<ItemKey> seen = new HashSet<>();
        List<ItemSnapshot> variants = new ArrayList<>();
int totalBudget = VariantScanner.HARD_TOTAL_VARIANT_CEILING;
        boolean budgetExhausted = false;
        int processedEntries = 0;

        for (Map.Entry<ResourceLocation, TreeSet<String>> entry : observedByItem.entrySet()) {
            if (processedEntries++ % NBT_VARIANT_CANCEL_CHECK_INTERVAL == 0
                    && cancellation.getAsBoolean()) {
                break;
            }
            ItemSnapshot base = baseByItem.get(entry.getKey());
            if (base == null) {
                // 基础物品未被扫描（被忽略或未注册）时不生成变体，避免绕过排除规则。
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(entry.getKey());
            if (item == null || item == Items.AIR) {
                continue;
            }

            // 原始 SNBT 已按字典序去重；逐个解析并即时裁剪到 limit，保持结果与配方顺序无关。
            TreeSet<ItemKey> uniqueKeys = new TreeSet<>();
            Map<ItemKey, TreeSet<String>> rawFormsByKey = new LinkedHashMap<>();
            for (String snbt : entry.getValue()) {
                CompoundTag tag;
                try {
                    tag = TagParser.parseTag(snbt);
                } catch (Exception failure) {
                    LOGGER.debug("无法解析配方输出 SNBT，跳过该观测变体：{}", snbt, failure);
                    continue;
                }
                ItemKey key = VariantNormalizer.keyFor(entry.getKey(), tag);
                if (!key.hasVariant()) {
                    continue;
                }
                uniqueKeys.add(key);
                TreeSet<String> forms = rawFormsByKey.computeIfAbsent(key, ignored -> new TreeSet<>());
                if (forms.size() < VariantRegistration.MAX_OBSERVED_NBT_PER_VARIANT) {
                    forms.add(snbt);
                }
                if (uniqueKeys.size() > limit) {
                    ItemKey removed = uniqueKeys.pollLast();
                    rawFormsByKey.remove(removed);
                }
            }
            if (uniqueKeys.isEmpty()) {
                continue;
            }

            if (DebugLog.isEnabled()) {
                final long index = processedEntries;
                final int observed = entry.getValue().size();
                final int produced = uniqueKeys.size();
                DebugLog.headOrSampled("variant", index, 20L, 500L,
                        () -> "item=" + entry.getKey() + " observed=" + observed
                                + " variants=" + produced);
            }
            for (ItemKey variantKey : uniqueKeys) {
                if (!seen.add(variantKey)) {
                    continue;
                }
                if (variants.size() >= totalBudget) {
                    budgetExhausted = true;
                    break;
                }
                long existingValue = queryVariantExistingValue(
                        item, variantKey, rawFormsByKey.getOrDefault(variantKey, new TreeSet<>()), emcQuery);
                variants.add(ItemSnapshot.of(
                        variantKey, existingValue > 0L, existingValue, base.tagPaths(), base.rarity()));
            }
            if (variants.size() >= totalBudget) {
                budgetExhausted = true;
                break;
            }
        }

        if (budgetExhausted) {
            LOGGER.debug("NBT 变体生成达到全局上限 {}，后续物品不再采集", totalBudget);
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
     * 查询一个变体身份的精确既有值。
     *
     * <p>注册侧会同时写入规范 SNBT 与观测到的原始 SNBT，因此这里也必须覆盖两类形态：
     * 规范 key 可能已经被 ProjectE 的 NBT 处理器或其它模组注册，原始形态则对应真实堆叠。
     * 同一经济变体可能对应多个原始 SNBT；采用最大值可避免把任何一个已存在的 NBT 专属
     * 值当成“无值”后覆盖。任一查询失败只按该形态无值处理。</p>
     *
     * @return 精确 NBT 既有值；没有专属映射时返回 {@code 0}
     */
    private static long queryVariantExistingValue(Item item, ItemKey variantKey,
                                                  Iterable<String> rawSnbts,
                                                  @Nullable ItemScanner.EmcQuery emcQuery) {
        if (emcQuery == null) {
            return 0L;
        }
        TreeSet<String> probes = new TreeSet<>();
        String canonical = variantKey.variantHash();
        if (canonical != null && !canonical.isBlank()) {
            probes.add(canonical);
        }
        for (String snbt : rawSnbts) {
            if (snbt != null && !snbt.isBlank()) {
                probes.add(snbt);
            }
        }

        long maxValue = 0L;
        for (String snbt : probes) {
            try {
                CompoundTag tag = TagParser.parseTag(snbt);
                ItemStack probe = new ItemStack(item);
                probe.setTag(tag);
                long value = Math.max(0L, emcQuery.getExactValue(probe));
                if (value > maxValue) {
                    maxValue = value;
                }
            } catch (Exception failure) {
                LOGGER.debug("查询变体 {} 的精确 NBT 既有值失败，跳过该形态：{}", variantKey, snbt, failure);
            }
        }
        return maxValue;
    }

    /**
     * 开始新一轮扫描前重置跨扫描的报告状态。
     *
     * <p>生产接线在 {@code ScanManager.requestScan()} 成功后、首个采集 tick 之前调用。
     * {@code fullReportWritten} 与 {@code pendingReport} 只属于单次扫描：若不在扫描开始时清除，
     * 上一轮成功写出的完整报告会让本轮“采集失败”或“采集阶段取消”的部分报告被静默跳过，
     * 磁盘上继续保留旧的成功报告。{@code lastCollectFailure} 同理，旧扫描的失败原因不得泄漏
     * 到新一轮。</p>
     */
    public void beginScan() {
        fullReportWritten = false;
        pendingReport = null;
        lastCollectFailure = null;
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

            @Override
            public long getExactValue(ItemStack stack) {
                EmcProvider provider = emcProvider;
                if (provider == null) {
                    return 0L;
                }
                try {
                    if (!provider.isAvailable()) {
                        return 0L;
                    }
                    return Math.max(0L, provider.getExactValue(stack));
                } catch (RuntimeException | LinkageError failure) {
                    LOGGER.debug("EMC 后端读取精确 NBT 数值失败，按 0 处理", failure);
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
        // 防御性重置：测试或嵌入式调用可能绕过 ScanManager 直接 stash，
        // 但生产路径的真正重置点是每次 requestScan() 触发的 beginScan()。
        fullReportWritten = false;
        pendingReport = null;
        if (snapshot != null) {
            // 新快照代表一次成功采集，清除上一次失败原因，避免恢复扫描被误判为失败。
            lastCollectFailure = null;
        }
        stashedSnapshot.set(snapshot);
    }

    /**
     * @return 最近一次采集失败的中文原因；最近一次采集成功时为 {@code null}
     */
    @Nullable
    public String lastCollectFailure() {
        return lastCollectFailure;
    }

    /**
     * 取出并应用最近一次暂存的快照。
     *
     * <p>快照在取出后立即清空，因此重复调用不会把同一批结果写入两次。没有暂存快照或采集阶段
     * 已经失败时返回全零结果并写出失败诊断报告，不会抛出异常。</p>
     *
     * @return 本次应用的结果；没有暂存数据或采集失败时为 {@link PipelineResult#zeroed(long)}
     */
    public PipelineResult applyStashed() {
        PipelineSnapshot snapshot = stashedSnapshot.getAndSet(null);
        if (snapshot == null) {
            String detail = lastCollectFailure == null
                    ? "应用阶段没有可用的暂存快照"
                    : lastCollectFailure;
            lastCollectFailure = detail;
            LOGGER.warn("SudoersEMC 应用阶段没有可用的暂存快照，跳过本轮写入");
            writeFailedReport(detail, diagnostics);
            return PipelineResult.zeroed(0L);
        }
        String collectFailure = lastCollectFailure;
        if (collectFailure != null) {
            // collect(...) 失败会抛出异常且不会 stash；若暂存快照来自更早的扫描，
            // 残留的失败原因说明本轮采集失败，不能把旧快照当成正常空扫描继续应用。
            lastCollectFailure = null;
            writeFailedReport(collectFailure, diagnostics);
            return PipelineResult.zeroed(0L);
        }
        return solveAndApply(snapshot);
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
     * @return 分离后的物品、仍受保护的既有值、刷新数量与全部自有物品键
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned) {
        return separateOwnedEntries(items, owned, Map.of(), () -> false);
    }

    /**
     * 带“外部保留值”的归属分离。
     *
     * <p>非自有条目优先采用采集阶段观察到的既有值；如果后端没有报告该值（例如 ProjectE 未安装，
     * 但手工文件或所有权迁移要求保留旧值），则使用 {@code preservedExternalValues} 中的值作为种子。
     * 自有条目仍会被重建为无既有值，保持 Phase 14 的刷新语义。</p>
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned,
                                                    Map<ItemKey, Long> preservedExternalValues) {
        return separateOwnedEntries(items, owned, preservedExternalValues, () -> false);
    }

    /**
     * 带协作式取消检查的归属分离。每处理 {@value #FULL_SWEEP_CANCEL_CHECK_INTERVAL} 个物品
     * 检查一次取消信号，命中后返回已完成的部分；调用方会在写入任何候选之前走取消路径。
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned,
                                                    BooleanSupplier cancellation) {
        return separateOwnedEntries(items, owned, Map.of(), cancellation);
    }

    /**
     * 核心重载：同时支持外部保留值种子与协作式取消检查。
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned,
                                                    Map<ItemKey, Long> preservedExternalValues,
                                                    BooleanSupplier cancellation) {
        Map<ItemKey, Long> preserved = preservedExternalValues == null
                ? Map.of()
                : preservedExternalValues;
        List<ItemSnapshot> separated = new ArrayList<>(items.size());
        Map<ItemKey, Long> existingValues = new TreeMap<>();
        Set<ItemKey> ownedItems = new TreeSet<>();
        int refreshedOwned = 0;
        int inspected = 0;
        for (ItemSnapshot item : items) {
            if (inspected++ % FULL_SWEEP_CANCEL_CHECK_INTERVAL == 0
                    && cancellation.getAsBoolean()) {
                break;
            }
            ItemKey key = item.key();
            if (owned.test(key)) {
                ownedItems.add(key);
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
        // 已成功释放所有权的条目即使不在本轮物品快照中，也要保留其最后已知值作为种子，
        // 避免下一轮被配方或兜底定价重新接管。
        for (Map.Entry<ItemKey, Long> entry : preserved.entrySet()) {
            ItemKey key = entry.getKey();
            Long value = entry.getValue();
            if (key != null && value != null && value > 0L) {
                existingValues.putIfAbsent(key, value);
            }
        }
        return new OwnershipSeparation(separated, existingValues, refreshedOwned, ownedItems);
    }

    /**
     * 在应用阶段执行归属分离。
     *
     * <p>分离放在应用阶段而不是采集阶段：采集只如实记录后端观察到的状态，是否归 SudoersEMC
     * 所有由持久化存储决定。这样即使调用方直接构造带既有值的快照，也会在写入前正确刷新
     * 自有条目；干跑模式没有存储，保持原始语义不变。</p>
     *
     * <p>在刷新之前先检测外部修改：owned 条目的持久化值与后端当前值不同，或物品被 ProjectE
     * 手工文件覆盖时，默认释放所有权并保留外部值。未被外部修改的 owned 条目仍然按 Phase 14
     * 语义刷新。</p>
     *
     * @param snapshot 采集结果，不能为 {@code null}
     * @return 分离后的快照、外部修改计数与本次刷新数量
     */
    private OwnershipApplication applyOwnershipSeparation(PipelineSnapshot snapshot) {
        if (store == null) {
            return new OwnershipApplication(snapshot, 0, 0, Set.of(), 0);
        }
        OwnershipDetection detection = detectAndReleaseExternalModifications(snapshot);
        OwnershipSeparation separation = separateOwnedEntries(
                snapshot.items(),
                key -> !detection.protectedItems().contains(key) && store.isOwned(key),
                detection.preservedValues(),
                this::isCancelled);
        // 合并既有值：先保留本轮物品列表之外仍然有效的既有值（例如空物品快照或部分扫描），
        // 再移除被刷新的自有条目，最后叠加分离阶段产生的外部保留值。直接使用分离结果会丢掉
        // 不在物品列表中的既有值，从而让本可继续求解的配方失去输入。
        Map<ItemKey, Long> mergedExistingValues = new TreeMap<>(snapshot.existingValues());
        for (ItemKey owned : separation.ownedItems()) {
            mergedExistingValues.remove(owned);
        }
        mergedExistingValues.putAll(separation.existingValues());
        boolean changed = separation.refreshedOwned() > 0
                || !mergedExistingValues.equals(snapshot.existingValues());
        if (!changed) {
            return new OwnershipApplication(snapshot, detection.detected(), detection.released(),
                    detection.protectedItems(), separation.refreshedOwned());
        }
        if (separation.refreshedOwned() > 0) {
            LOGGER.debug("SudoersEMC 归属分离刷新了 {} 个自有条目，本轮将重新求解其数值",
                    separation.refreshedOwned());
        }
        PipelineSnapshot updated = new PipelineSnapshot(
                separation.items(),
                snapshot.recipes(),
                mergedExistingValues,
                snapshot.unsupportedRecipes(),
                snapshot.invalidRecipes(),
                snapshot.failedRecipes(),
                snapshot.recipeEnumerationFailed(),
                snapshot.failedRecipeDetails(),
                snapshot.remapGeneration());
        return new OwnershipApplication(updated, detection.detected(), detection.released(),
                detection.protectedItems(), separation.refreshedOwned());
    }

    /**
     * 检测 owned 条目的外部修改并释放其所有权，同时保留外部值或最后已知值。
     *
     * <p>两种信号会被视为外部修改：</p>
     * <ul>
     *   <li>持久化记录为 {@code owned=true}，但后端当前值（本轮采集到的既有值）与记录值不同；</li>
     *   <li>物品被 ProjectE 手工文件覆盖，无论数值是否恰好相同。</li>
     * </ul>
     *
     * <p>只比较本轮后端实际报告过值的物品，避免把“未扫描/被忽略”误判为外部删除。手工值通过
     * 注入的判定器识别，不依赖该物品是否出现在既有值映射中。</p>
     */
    private OwnershipDetection detectAndReleaseExternalModifications(PipelineSnapshot snapshot) {
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
        if (remapConfirmationPending(snapshot)) {
            // 重映射尚未确认完成时，后端可能仍返回我们写入前的旧值；
            // 此时比较漂移会误释放所有权，因此只保留手工值检测。
            LOGGER.debug("SudoersEMC 的后端重映射尚未确认完成，本轮跳过 owned 条目的漂移检测");
            drifted = List.of();
        } else {
            try {
                drifted = manager.detectExternalModifications(considered);
            } catch (RuntimeException failure) {
                LOGGER.warn("SudoersEMC 检测外部修改失败，本轮保留现有归属", failure);
                drifted = List.of();
            }
        }

        Set<ItemKey> candidates = new TreeSet<>(drifted);
        candidates.addAll(manualItems);
        int detected = 0;
        int released = 0;
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
            long preservedValue = preservedExternalValue(observedValue, entry.value());
            // 即使所有权释放写入失败，也保留外部值并阻止本轮覆盖；失败只影响后续
            // 扫描是否还能看到 owned 标记，不影响本轮的经济安全。
            if (preservedValue > 0L) {
                preservedValues.put(item, preservedValue);
            }
            if (manager.releaseOwnership(item)) {
                released++;
            }
        }
        if (detected > 0) {
            LOGGER.info("SudoersEMC 检测到 {} 个 owned 条目被外部修改，已释放 {} 个所有权并保留外部值",
                    detected, released);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("SudoersEMC 外部修改条目：{}", candidates);
            }
        }
        return new OwnershipDetection(detected, released, preservedValues, protectedItems);
    }

    private Predicate<ItemKey> manualDetector() {
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
     * 手工值判定。
     *
     * <p>owned 外部修改检测路径保持 fail-open（异常视为“未手动”，只影响检测范围）；兜底定价
     * 路径必须 fail-closed（异常视为“手动”，宁可不定价也不能覆盖管理员固定值）。</p>
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
     * 选择释放所有权时要保留的外部值。
     *
     * <p>后端明确报告的 {@code 0} 是有效观测；只有未观测（{@code null}）才回退到最后已知值。</p>
     *
     * @param observedValue  本轮后端观测值，未观测时为 {@code null}
     * @param lastKnownValue 持久化条目中的最后已知值
     * @return 应保留的值
     */
    static long preservedExternalValue(@Nullable Long observedValue, long lastKnownValue) {
        return observedValue != null ? observedValue : lastKnownValue;
    }

    /**
     * 归属分离结果。
     *
     * @param items          分离后的物品快照，保持输入顺序
     * @param existingValues 仍然受保护的非自有既有值，按键升序排列
     * @param refreshedOwned 被清除既有值的自有条目数量
     * @param ownedItems     本轮扫描中归 SudoersEMC 所有的物品键，按键升序排列
     */
    record OwnershipSeparation(List<ItemSnapshot> items, Map<ItemKey, Long> existingValues,
                               int refreshedOwned, Set<ItemKey> ownedItems) {

        OwnershipSeparation {
            items = List.copyOf(items);
            existingValues = Collections.unmodifiableMap(new TreeMap<>(existingValues));
            ownedItems = Collections.unmodifiableSet(new TreeSet<>(ownedItems));
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

    /** 应用阶段归属处理结果。 */
    record OwnershipApplication(PipelineSnapshot snapshot, int detected, int released,
                                Set<ItemKey> protectedItems, int refreshedOwned) {

        OwnershipApplication {
            protectedItems = protectedItems == null
                    ? Set.of()
                    : Collections.unmodifiableSet(new TreeSet<>(protectedItems));
        }
    }

    /** 从本轮采集快照读取当前值的只读后端。 */
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
     * 求解快照并为可定价物品写入生成值。
     *
     * <p>本方法自身不直接读取注册表，只使用快照中的纯数据；但手工值判定器可能通过标签解析
     * 间接访问注册表，因此必须由服务器线程调用。处理顺序固定为：</p>
     * <ol>
     *   <li>以已有 EMC 与创造保护固定值为初始种子，最多执行 {@link #MAX_SOLVE_ROUNDS} 轮
     *       “配方求解 → 兜底补种”循环：循环内只给没有配方的叶子物品补种，把兜底值沿配方链
     *       向上传播；本轮既没有新增求解值也没有新增兜底值时提前收敛；</li>
     *   <li>收敛后再对仍无值的物品执行一次最终兜底，避免兜底种子抢占配方输出；</li>
     *   <li>统一合并候选：同一物品只保留一条，配方/固定值来源优先于任何兜底来源；</li>
     *   <li>每个候选值先经过大幅降价保护，再经过溢出保护，最后才写入存储；</li>
     *   <li>更新扫描指纹、写出诊断报告并返回汇总结果。安全层、存储写入与报告只执行一次。</li>
     * </ol>
     *
     * <p><strong>归属分离：</strong>应用阶段会先把存储中 {@code owned=true} 的条目对应物品
     * 视为没有既有 EMC，并从既有值映射中移除。采集阶段仍如实记录后端观察到的值，因为
     * {@link ItemScanner} 无法区分 ProjectE 原生值与 SudoersEMC 自己写回的值；只有在这里
     * 依据持久化存储的归属信息做一次分离，自有值才能随配方变化刷新，外部值仍然受到保护。</p>
     *
     * <p><strong>多级自有链条：</strong>分离会移除自有原料的旧值，而兜底定价原本发生在求解之后，
     * 导致依赖这些原料的配方产物拿不到新值。现在会在求解前把自有叶子条目的兜底价作为预置值
     * 注入 {@link SolveContext}：配方仍然可以覆盖它，下游配方则能在同一轮看到它。求解结束后
     * 才为仍未定价的条目生成兜底候选，计数语义保持不变。</p>
     *
     * <p>存储为 {@code null} 时进入干跑模式：计数与报告照常生成，但不会产生任何持久化写入。</p>
     *
     * <p><strong>取消语义：</strong>采集、求解、兜底、合并、应用与清理循环都会在检查点观察取消
     * 信号；观察到后立即停止，已写入的条目各自保持原子一致。本轮不会写入新的扫描指纹，而是
     * 显式让持久化状态失效，确保下一次启动必然重扫；同时写出部分诊断报告，交由调度器发布
     * CANCELLED 状态。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     * @return 汇总结果
     * @throws IllegalStateException 求解或应用过程抛出运行时异常时
     */
    public PipelineResult solveAndApply(@Nullable PipelineSnapshot snapshot) {
        long startedAtNanos = System.nanoTime();
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
fullReportWritten = false;
        lastSnapshotForReport = data;
        lastOwnershipExternalModifications = 0;
        lastOwnershipReleased = 0;
        lastResolverApplied = 0;
        long cacheHitsBefore = recipeSolver.cacheHits();
        long cacheMissesBefore = recipeSolver.cacheMisses();

        // 显式调用求解说明调用方已经拿到了可用快照，旧的采集失败标志不再适用。
        lastCollectFailure = null;
        // 持久化隔离存储已经接管审计；每轮扫描开始时清空内存列表，避免长期自动扫描无限累积。
        dropProtection.clearQuarantine();
        ScanFingerprint inputFingerprint = captureInputFingerprint(data);
        try {
            // 求解开始前已请求取消：不进入任何计算，直接走取消路径并失效旧指纹。
            if (isCancelled()) {
                return cancelledResult(data, startedAtNanos);
            }
            Computation computation = compute(data, startedAtNanos, inputFingerprint);
            // 求解循环内的检查点可能提前结束；此时尚未写入任何条目，按取消处理。
            if (isCancelled()) {
                return cancelledResult(data, startedAtNanos);
            }
            return applyComputation(computation);
        } catch (RuntimeException failure) {
            long durationMillis = elapsedMillis(startedAtNanos);
            lastSolvedCount = 0;
            lastUnresolvedCount = 0;
            lastSolverRoundCount = 0;
            lastDurationMillis = durationMillis;
            LOGGER.error("SudoersEMC 扫描管线求解与应用失败", failure);
            throw new IllegalStateException("扫描管线求解与应用失败", failure);
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
        ScanFingerprint inputFingerprint = captureInputFingerprint(snapshot);
        pendingSolve.set(CompletableFuture.supplyAsync(
                () -> compute(snapshot, startedAtNanos, inputFingerprint), SOLVER_EXECUTOR));
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
     * <p>该方法只在后台求解成功时返回结果。没有待处理结果、求解被取消、求解线程抛出异常或
     * 返回 {@code null} 时都会抛出 {@link IllegalStateException}，由调用方把扫描转为
     * {@link com.itzplana.sudoersemc.task.ScanPhase#FAILED}；绝不会用全零结果伪装成功，
     * 从而避免 {@code ScanManager} 进入 COMPLETED。</p>
     *
     * @return 本次应用的结果
     * @throws IllegalStateException 后台求解失败、被取消或没有待处理结果时抛出
     */
    public PipelineResult applyCompletedSolve() {
        CompletableFuture<Computation> future = pendingSolve.getAndSet(null);
        if (future == null) {
            throw new IllegalStateException("应用求解结果失败：没有待处理的后台求解");
        }
        Computation computation;
        try {
            computation = future.join();
        } catch (RuntimeException failure) {
            String detail = failure.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = failure.getClass().getSimpleName();
            }
            LOGGER.error("SudoersEMC 后台求解失败", failure);
            throw new IllegalStateException("后台求解失败：" + detail, failure);
        }
        if (computation == null) {
            throw new IllegalStateException("应用求解结果失败：后台求解返回 null");
        }
        return applyComputation(computation);
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
    private Computation compute(PipelineSnapshot snapshot, long startedAtNanos,
                                ScanFingerprint inputFingerprint) {
        OwnershipApplication ownership = applyOwnershipSeparation(snapshot);
        PipelineSnapshot data = ownership.snapshot();
        lastOwnershipExternalModifications = ownership.detected();
        lastOwnershipReleased = ownership.released();
        Set<ItemKey> externallyModified = ownership.protectedItems();
        long minimumEmc = solveSettings.minimumEmc();

        long cacheHitsBefore = recipeSolver.cacheHits();
        long cacheMissesBefore = recipeSolver.cacheMisses();
        Map<ItemKey, Candidate> recipeCandidates = new TreeMap<>();
        Map<ItemKey, Candidate> fallbackCandidates = new TreeMap<>();
        Map<ItemKey, Long> seeds = initialSeeds(data);

        CreativeProtectionPlan protection = planCreativeProtection(data, seeds, minimumEmc);
        for (Map.Entry<ItemKey, Long> entry : protection.specialValues().entrySet()) {
            ItemKey item = entry.getKey();
            long value = entry.getValue();
            seeds.put(item, value);
            recipeCandidates.put(item,
                    new Candidate(item, value, PriceSource.SPECIAL_VALUE, null));
        }
        Set<ItemKey> recipeOutputs = recipeOutputItems(data);
        Set<ItemKey> variantItems = variantItemKeys(data.items());

        SolveResult lastSolveResult = skippedSolveResult();
        int roundsUsed = 0;
        for (int round = 1; round <= MAX_SOLVE_ROUNDS; round++) {
            roundsUsed = round;
            SolveResult roundResult = solveRound(data, seeds, protection.deniedItems());
            lastSolveResult = roundResult;

            boolean solvedProgress = mergeSolvedValues(roundResult, recipeCandidates, seeds,
                    protection.deniedItems(), protection.specialValues(), variantItems);
            int fallbackAdded = addFallbackCandidates(
                    data, seeds, fallbackCandidates, recipeOutputs);
            if (!solvedProgress && fallbackAdded == 0) {
                break;
            }
        }
        lastSolverRoundCount = roundsUsed;

        // 收敛后再兜底：配方输出在循环内不会被兜底种子抢先定价，此处只处理仍然无值的物品。
        addFallbackCandidates(data, seeds, fallbackCandidates, Set.of());

        List<Candidate> candidates =
                selectFinalCandidates(recipeCandidates, fallbackCandidates, minimumEmc,
                        this::isCancelled);
        if (!ownership.protectedItems().isEmpty()) {
            List<Candidate> filtered = new ArrayList<>(candidates.size());
            for (Candidate candidate : candidates) {
                if (!ownership.protectedItems().contains(candidate.item())) {
                    filtered.add(candidate);
                }
            }
            candidates = List.copyOf(filtered);
        }
        int solvedByRecipe = 0;
        int solvedByFallback = 0;
        for (Candidate candidate : candidates) {
            if (candidate.source() == PriceSource.RECIPE) {
                solvedByRecipe++;
            } else {
                solvedByFallback++;
            }
        }
        // 记录本轮求解缓存命中/未命中，供诊断报告暴露（Phase 23 缓存指标）。
        lastSolverCacheHits = (int) Math.max(0L, recipeSolver.cacheHits() - cacheHitsBefore);
        lastSolverCacheMisses = (int) Math.max(0L, recipeSolver.cacheMisses() - cacheMissesBefore);
        return new Computation(data, lastSolveResult, List.copyOf(candidates),
                solvedByRecipe, solvedByFallback, ownership.refreshedOwned(),
                externallyModified, inputFingerprint, startedAtNanos);
    }

    /**
     * 应用阶段：安全判定、溢出保护、存储写入、状态更新与报告落盘。必须在服务器线程执行。
     */
    private PipelineResult applyComputation(Computation computation) {
        PipelineSnapshot data = computation.snapshot();
        SolveResult solveResult = computation.solveResult();
        List<Candidate> candidates = computation.candidates();
        Set<ItemKey> variantItems = variantItemKeys(data.items());
        Map<ItemKey, List<String>> variantRegistrationNbts =
                collectVariantRegistrationNbts(data.recipes(), variantItems);

        Map<ItemKey, GeneratedEmcEntry> previousEntries =
                store == null ? Map.of() : store.snapshotAll();
        int staleOwned = countStaleOwned(previousEntries, data, candidates);
        int overflowBefore = overflowPipeline == null ? 0 : overflowPipeline.resetsApplied();

        int applied = 0;
        int quarantined = 0;
        Map<String, Long> sourceCounts = new LinkedHashMap<>();

        int applyIndex = 0;
        for (Candidate candidate : candidates) {
            if (applyIndex++ % APPLY_CANCEL_CHECK_INTERVAL == 0 && isCancelled()) {
                break;
            }
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
                // 溢出保护已经改变数值时，求解器给出的备选成本不再与最终值可比，
                // 继续持久化会让 /sudoemc explain 展示误导性的对比结果。
                List<Long> alternativeCosts = finalValue == candidate.value()
                        ? candidate.alternativeCosts()
                        : List.of();
                boolean staged = store.stage(new GeneratedEmcEntry(
                        candidate.item(),
                        finalValue,
                        candidate.source(),
                        candidate.recipeId(),
                        true,
                        System.currentTimeMillis(),
                        alternativeCosts),
                        variantRegistrationNbts.getOrDefault(candidate.item(), List.of()));
                if (!staged) {
                    LOGGER.debug("物品 {} 的生成值未被存储接受，可能已有非 SudoersEMC 拥有的条目",
                            candidate.item());
                    continue;
                }
            }

            applied++;
            sourceCounts.merge(candidate.source().name(), 1L, Long::sum);
        }

        if (isCancelled()) {
            // 应用阶段取消：已经 stage 的条目由调度器决定回滚，但持久化指纹必须失效，
            // 否则下一次启动会以“输入未变化”跳过扫描并沿用部分结果。
            long durationMillis = elapsedMillis(computation.startedAtNanos());
            invalidateScanState();
            lastSolvedCount = applied;
            lastUnresolvedCount = 0;
            lastDurationMillis = durationMillis;
            writePartialReport(data, diagnostics, true, "扫描已取消");
            fullReportWritten = true;
            int overflowResets = overflowPipeline == null
                    ? 0
                    : Math.max(0, overflowPipeline.resetsApplied() - overflowBefore);
            long auditDropped = overflowPipeline == null ? 0L : overflowPipeline.auditDroppedCount();
            Map<String, Long> extraCounters = buildExtraCounters(data, solveResult,
                    quarantined, overflowResets, durationMillis, store != null,
                    0, lastSolverRoundCount, lastOwnershipExternalModifications,
                    lastOwnershipReleased, computation.refreshedOwned(),
                    auditDropped, staleOwned, diagnostics);
            extraCounters.put("scanCancelled", 1L);
            return new PipelineResult(
                    computation.solvedByRecipe(),
                    computation.solvedByFallback(),
                    applied,
                    quarantined,
                    overflowResets,
                    0,
                    durationMillis,
                    store != null,
                    sourceCounts,
                    extraCounters);
        }

        Set<ItemKey> candidateKeys = new HashSet<>(candidates.size());
        for (Candidate candidate : candidates) {
            candidateKeys.add(candidate.item());
        }
        int retiredEntries = retireStaleOwnedEntries(data, candidateKeys, data.existingValues());
        rewriteProjectECustomEmcIfEnabled();

        if (store != null) {
            // 每轮扫描结束即刷新审计日志，崩溃时最多丢失当前这一轮。
            persistOverflowAudit();
        }
        int overflowResets = overflowPipeline == null
                ? 0
                : Math.max(0, overflowPipeline.resetsApplied() - overflowBefore);
        long auditDropped = overflowPipeline == null ? 0L : overflowPipeline.auditDroppedCount();
        int unresolved = solveResult.unresolvedItems().size();
        long durationMillis = elapsedMillis(computation.startedAtNanos());

        lastSolvedCount = applied;
        lastUnresolvedCount = unresolved;
        lastDurationMillis = durationMillis;

        boolean appliedToStore = store != null;
        updateScanState(computation.inputFingerprint(), durationMillis, applied, unresolved,
                lastSolverRoundCount);
        Map<String, Long> extraCounters = buildExtraCounters(data, solveResult,
                quarantined, overflowResets, durationMillis, appliedToStore,
                retiredEntries, lastSolverRoundCount, lastOwnershipExternalModifications,
                lastOwnershipReleased, computation.refreshedOwned(),
                auditDropped, staleOwned, diagnostics);
        addExternalBaselineCounters(extraCounters, 0L);
        PipelineResult result = new PipelineResult(
                computation.solvedByRecipe(),
                computation.solvedByFallback(),
                applied,
                quarantined,
                overflowResets,
                unresolved,
                durationMillis,
                appliedToStore,
                sourceCounts,
                extraCounters);
        logDebugSummary();
        // 完整报告延迟到 ScanManager 完成回调写出，确保 APPLYING/SAVING tick 已经计数。
        pendingReport = new PendingReport(data, result, null);
        // 同时立即写出一份：直接调用 solveAndApply 的调用方（命令、测试）期望报告已落盘；
        // 保留 pendingReport 让完成回调能用最新 tick 采样重写，不丢失 APPLYING/SAVING 指标。
        writeFullReport(pendingReport);
        return result;
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
     * 纯计算结果载体：快照、求解结果、候选与计数。
     */
    private record Computation(
            PipelineSnapshot snapshot,
            SolveResult solveResult,
            List<Candidate> candidates,
            int solvedByRecipe,
            int solvedByFallback,
            int refreshedOwned,
            Set<ItemKey> externallyModified,
            ScanFingerprint inputFingerprint,
            long startedAtNanos
    ) {

        private Computation {
            candidates = List.copyOf(candidates);
            externallyModified = externallyModified == null ? Set.of() : Set.copyOf(externallyModified);
        }
    }

    /**
     * 求解阶段被取消时的终止路径。
     *
     * <p>此时尚未进入应用循环，不产生任何写入；写出部分报告并把
     * {@code fullReportWritten} 置位，避免调度器终止回调重复写同一份报告。</p>
     */
    private PipelineResult cancelledResult(PipelineSnapshot data, long startedAtNanos) {
        long durationMillis = elapsedMillis(startedAtNanos);
        lastSolvedCount = 0;
        lastUnresolvedCount = 0;
        lastDurationMillis = durationMillis;
        // 取消时统一失效持久化指纹：手动 rescan-all 后取消也不能沿用旧缓存。
        invalidateScanState();
        writePartialReport(data, diagnostics, true, "扫描已取消");
        fullReportWritten = true;
        logDebugSummary();
        return PipelineResult.zeroed(durationMillis);
    }

    /**
     * 清理本轮完全没有参与定价的自有条目。
     *
     * <p>物品被新配置排除、配方被移除或物品从注册表消失后，旧的自有值不应继续生效。判定口径：
     * 条目 {@code owned=true}，且既不在本轮最终候选中，也不在本轮 {@code existingValues} 中，
     * 即视为完全没有参与定价。非自有条目（手动配置或外部后端写入）永远不会删除。</p>
     *
     * <p>以下情况直接跳过，避免把“本轮没有计算”误判成“条目陈旧”：干跑模式
     * （{@code store == null}）、配方求解被禁用、配方描述失败导致本轮求解不完整、本轮没有任何
     * 候选，或快照没有任何物品（例如服务器尚未就绪）。</p>
     *
     * @param snapshot       本轮采集结果
     * @param pricedKeys     本轮选出的候选物品集合
     * @param existingValues 本轮受保护的既有值映射
     * @return 实际删除的自有条目数量
     */
    private int retireStaleOwnedEntries(PipelineSnapshot snapshot, Set<ItemKey> pricedKeys,
                                        Map<ItemKey, Long> existingValues) {
        if (store == null) {
            return 0;
        }
        if (snapshot.failedRecipes() > 0) {
            LOGGER.debug("本轮有 {} 条配方描述失败，求解结果可能不完整，跳过陈旧自有条目清理",
                    snapshot.failedRecipes());
            return 0;
        }

        if (!solveRecipesEnabled) {
            LOGGER.debug("配方求解已禁用，跳过陈旧自有条目清理");
            return 0;
        }
        if (pricedKeys.isEmpty()) {
            LOGGER.debug("本轮没有任何候选，跳过陈旧自有条目清理");
            return 0;
        }
        if (snapshot.items().isEmpty()) {
            LOGGER.debug("本轮快照没有任何物品，跳过陈旧自有条目清理");
            return 0;
        }
        int retired = 0;
        int inspected = 0;
        try {
            for (Map.Entry<ItemKey, GeneratedEmcEntry> entry : store.snapshotAll().entrySet()) {
                if (inspected++ % FULL_SWEEP_CANCEL_CHECK_INTERVAL == 0 && isCancelled()) {
                    LOGGER.debug("清理陈旧自有条目时观察到取消信号，提前结束本轮清理");
                    break;
                }
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
     * @return 最近一次求解实际执行的轮数；空世界或首轮无进展时为 1
     */
    int lastSolverRoundCount() {
        return lastSolverRoundCount;
    }

    /**
     * 把自有叶子条目的兜底价预置到求解上下文中。
     *
     * <p>叶子条目指本轮没有任何“实际会被发布的配方输出”的自有物品。默认
     * {@link EvaluationPolicy#defaults()} 只发布主输出，副产物不会被求解器写值，因此仅作为
     * 副产物出现的自有物品也必须按叶子处理。它们的旧值已被归属分离清除，若等到求解结束才做
     * 兜底定价，依赖它们的多级配方产物在本轮就看不到新值。预置值只参与输入查找，不会让
     * {@link SolveContext#hasSeedValue(ItemKey)} 返回 {@code true}，因此配方仍可覆盖同一物品
     * 的预置值。</p>
     *
     * @param snapshot   已完成归属分离的快照
     * @param ownedItems 自有物品键集合
     * @param context    本次求解上下文
     * @param policy     与求解器一致的发布策略
     * @return 实际预置的条目数量
     */
    private int seedOwnedFallbackValues(PipelineSnapshot snapshot, Set<ItemKey> ownedItems,
                                        SolveContext context, EvaluationPolicy policy) {
        if (ownedItems.isEmpty()) {
            return 0;
        }
        Set<ItemKey> recipeOutputs = new HashSet<>();
        for (RecipeSnapshot recipe : snapshot.recipes()) {
            recipeOutputs.addAll(RecipeSolver.publicationTargets(recipe, policy));
        }

        int seeded = 0;
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            if (!ownedItems.contains(item) || recipeOutputs.contains(item)) {
                continue;
            }
            Optional<Candidate> fallback = fallbackCandidate(itemSnapshot);
            if (fallback.isEmpty()) {
                continue;
            }
            context.putProvisional(item, fallback.get().value());
            seeded++;
        }
        if (seeded > 0) {
            LOGGER.debug("为 {} 个自有叶子条目预置兜底定价，供本轮多级配方链使用", seeded);
        }
        return seeded;
    }

    /**
     * 批准某个物品当前的内存隔离决策。
     *
     * <p>命令层在批准持久化记录后调用本方法，确保 {@link LargeDropProtection#approve} 有生产
     * 调用方；返回的决策只用于诊断与回执，数值应用由命令层依据持久化记录完成。</p>
     *
     * @param item 被批准物品；{@code null} 会被忽略
     * @return 本次从内存隔离列表移除的决策，按加入顺序排列
     */
    public List<PriceDropDecision> approveQuarantined(@Nullable ItemKey item) {
        if (item == null) {
            return List.of();
        }
        return dropProtection.approve(item);
    }

    /**
     * 把求解器产出的配方值转换为候选条目。
     *
     * <p>只有严格大于 0 的既有 EMC 才能成为种子：0 或负值等同于“没有值”，
     * 否则会遮蔽后续的配方与兜底定价。</p>
     */
    private static Map<ItemKey, Long> initialSeeds(PipelineSnapshot snapshot) {
        Map<ItemKey, Long> seeds = new TreeMap<>();
        for (Map.Entry<ItemKey, Long> entry : snapshot.existingValues().entrySet()) {
            Long value = entry.getValue();
            if (value == null) {
                throw new IllegalArgumentException(
                        "existingValues 不允许 null 值：" + entry.getKey());
            }
            if (value > 0L) {
                seeds.put(entry.getKey(), value);
            }
        }
        return seeds;
    }

    /**
     * 执行一轮配方求解。
     *
     * <p>关闭配方求解时返回空结果，但保留“每轮兜底补种”的循环结构：第一轮兜底命中后，
     * 第二轮没有新增值会立即退出。</p>
     */
    private SolveResult solveRound(PipelineSnapshot data, Map<ItemKey, Long> seeds,
                                   Set<ItemKey> deniedItems) {
        if (!solveRecipesEnabled) {
            LOGGER.debug("配方求解已按配置禁用，本轮仅使用既有值与兜底定价");
            return skippedSolveResult();
        }
SolveContext context = new SolveContext(seeds, deniedItems);
        // 复用同一个求解器实例，让未变化配方的评估缓存跨轮次与跨扫描生效。
        return recipeSolver.solve(
                data.recipes(), context, evaluatorRegistry, solveSettings, this::isCancelled);
    }

    /**
     * 把一轮求解结果并入种子与配方候选。
     *
     * <p>同键取较小值，防止后续轮次抬高已生成值；仅当
     * {@link SolveSettings#allowValueIncrease()} 为 {@code true} 时才接受新值覆盖。
     * 被创造保护拒绝的物品会被丢弃；配置了固定值的物品始终使用固定值，并记录为
     * {@link PriceSource#SPECIAL_VALUE}。</p>
     *
     * <p>变体输出只允许发布到本轮真实采集到的变体快照：{@code nbt.scanNbtVariants=false}
     * 或被排除的基础物品不会经由配方求解重新进入持久化。</p>
     *
     * @param acceptedVariants 本轮通过采集门禁的变体身份
     * @return 本轮是否新增或更新了任何种子
     */
    private boolean mergeSolvedValues(SolveResult solveResult,
                                      Map<ItemKey, Candidate> recipeCandidates,
                                      Map<ItemKey, Long> seeds,
                                      Set<ItemKey> deniedItems,
                                      Map<ItemKey, Long> specialValues,
                                      Set<ItemKey> acceptedVariants) {
        boolean changed = false;
        boolean allowIncrease = solveSettings.allowValueIncrease();
        long index = 0L;
        int inspected = 0;
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            if (inspected++ % FULL_SWEEP_CANCEL_CHECK_INTERVAL == 0 && isCancelled()) {
                break;
            }
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
            if (solvedValue <= 0L || deniedItems.contains(item)) {
                continue;
            }
            if (item.hasVariant() && !acceptedVariants.contains(item)) {
                continue;
            }

            Long specialValue = specialValues.get(item);
            PriceSource source = PriceSource.RECIPE;
            if (specialValue != null) {
                solvedValue = specialValue;
                source = PriceSource.SPECIAL_VALUE;
            }

            Long previous = seeds.get(item);
            long mergedValue;
            if (previous == null || source == PriceSource.SPECIAL_VALUE || allowIncrease) {
                mergedValue = solvedValue;
            } else {
                mergedValue = Math.min(previous, solvedValue);
            }
            if (previous == null || mergedValue != previous) {
                seeds.put(item, mergedValue);
                changed = true;
            }

            SolveTrace trace = source == PriceSource.RECIPE
                    ? solveResult.traces().get(item)
                    : null;
            ResourceLocation recipeId = trace == null ? null : trace.recipeId();
            List<Long> alternativeCosts = trace == null ? List.of() : trace.alternativeCosts();
            Candidate existing = recipeCandidates.get(item);
            if (existing == null || mergedValue < existing.value()
                    || allowIncrease || source == PriceSource.SPECIAL_VALUE) {
                recipeCandidates.put(item,
                        new Candidate(item, mergedValue, source, recipeId, alternativeCosts));
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
     * 判断物品是否被排除规则明确拒绝自动定价（配方路径）。
     *
     * <p>配方候选没有特殊固定值语义，因此两类排除都直接生效：</p>
     * <ul>
     *   <li>{@link TierPricing#price} 返回 {@code Optional.of(0L)} 表示层级规则明确排除；</li>
     *   <li>{@link CreativeItemProtection#decide} 返回 {@code priceable=false} 表示创造物品保护排除。</li>
     * </ul>
     *
     * <p>兜底路径需要让 {@code safety.creativeItemSpecialValues} 的固定值优先于层级排除，
     * 因此那里先取得 {@link CreativeItemProtection.Decision}，再单独调用 {@link #isTierExcluded}。</p>
     *
     * <p>变体的 {@link ItemKey} 与基础物品共享物品 id，因此排除规则天然覆盖全部 NBT 变体。</p>
     *
     * @param item 待判定物品身份
     * @return {@code true} 表示该物品不得进入配方候选
     */
    private boolean isExcludedFromPricing(ItemKey item) {
        if (item == null) {
            return false;
        }
        if (isTierExcluded(item)) {
            return true;
        }
        try {
            CreativeItemProtection.Decision decision = creativeProtection.decide(item, true, true);
            return !decision.priceable();
        } catch (RuntimeException failure) {
            LOGGER.warn("排除规则判定失败，按排除处理：{}", item, failure);
            return true;
        }
    }

    /**
     * 通过第三方 resolver 注册表为物品解析 EMC。
     *
     * <p>空注册表直接返回 0，不重建 {@link ItemStack}、不解析 SNBT，保证默认行为与 Phase 24
     * 完全一致。任何异常都退化为“无值”，继续 TAG/RARITY/FALLBACK。</p>
     */
    private long resolveWithRegistry(ItemKey key) {
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
     * 丢弃严格超过溢出阈值的 resolver/baseline 值。
     *
     * <p>外部值最终写入时本来就会经过溢出保护，但候选值会先写入 seeds 并向配方上游传播；
     * 若允许一个异常高的外部值进入 seeds，会在最终保护之前污染整条配方链。因此这里在写入
     * 候选/seeds 之前就拒绝它，并继续尝试 TAG/RARITY/FALLBACK。</p>
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
     * 从 {@link ItemKey} 重建一个用于 resolver 的堆叠。变体 SNBT 无法解析时返回空堆叠。
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
     * 判断物品是否被层级定价规则明确排除。
     *
     * <p>{@link TierPricing#price} 返回 {@code Optional.of(0L)}（或其它非正值）时视为明确排除。
     * 兜底路径只在没有特殊固定值时调用本方法，从而保持
     * {@code safety.creativeItemSpecialValues} 对层级排除项的优先级；显式排除与创造命名空间
     * 仍由 {@link CreativeItemProtection} 判定。</p>
     *
     * @param item 待判定物品身份
     * @return {@code true} 表示层级规则明确拒绝自动定价
     */
    private boolean isTierExcluded(ItemKey item) {
        if (item == null) {
            return false;
        }
        try {
            Optional<Long> tierValue = tierPricing.price(item);
            return tierValue.isPresent() && tierValue.get() <= 0L;
        } catch (RuntimeException failure) {
            LOGGER.warn("层级排除判定失败，按排除处理：{}", item, failure);
            return true;
        }
    }

    /**
     * 合并配方与兜底候选，并执行最终去重与下限过滤。
     *
     * <p>同一个 {@link ItemKey} 只保留一条：配方来源优先于任何兜底来源
     * （TIER/TAG/RARITY/FALLBACK）。候选值必须大于 0 且不低于给定下限。</p>
     *
     * @param recipeCandidates   配方求解产生的候选，允许为空
     * @param fallbackCandidates 兜底定价产生的候选，允许为空
     * @param minimumEmc         最小 EMC
     * @return 按物品身份升序排列的最终候选
     */
    static List<Candidate> selectFinalCandidates(Map<ItemKey, Candidate> recipeCandidates,
                                                 Map<ItemKey, Candidate> fallbackCandidates,
                                                 long minimumEmc) {
        return selectFinalCandidates(recipeCandidates, fallbackCandidates, minimumEmc, () -> false);
    }

    /**
     * 带协作式取消检查的最终候选合并。每处理 {@value #FULL_SWEEP_CANCEL_CHECK_INTERVAL} 个
     * 候选检查一次取消信号，命中后返回已经选出的部分；调用方会在写入任何条目之前走取消路径。
     */
    static List<Candidate> selectFinalCandidates(Map<ItemKey, Candidate> recipeCandidates,
                                                 Map<ItemKey, Candidate> fallbackCandidates,
                                                 long minimumEmc,
                                                 BooleanSupplier cancellation) {
        Map<ItemKey, Candidate> merged = new TreeMap<>(fallbackCandidates);
        merged.putAll(recipeCandidates);
        List<Candidate> selected = new ArrayList<>(merged.size());
        int inspected = 0;
        for (Candidate candidate : merged.values()) {
            if (inspected++ % FULL_SWEEP_CANCEL_CHECK_INTERVAL == 0
                    && cancellation.getAsBoolean()) {
                break;
            }
            if (candidate.value() > 0L && candidate.value() >= minimumEmc) {
                selected.add(candidate);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 统计收敛后仍未定价的物品数量。
     *
     * <p>求解器报告的未解析物品可能已经被后续兜底轮次补种，因此这里再按最终种子过滤一次。</p>
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
     * @return 本轮物品快照中所有带变体哈希的身份
     */
    private static Set<ItemKey> variantItemKeys(List<ItemSnapshot> items) {
        Set<ItemKey> keys = new HashSet<>();
        for (ItemSnapshot item : items) {
            if (item.key().hasVariant()) {
                keys.add(item.key());
            }
        }
        return keys;
    }

    /**
     * 从配方输出收集“变体身份 -> 观测到的原始 SNBT”。
     *
     * <p>求解阶段只携带规范化身份，ProjectE 注册需要原始 NBT。这里只汇总本轮真实采集到的
     * 变体，按经济身份截断原始形态，随后随生成条目一并持久化。</p>
     *
     * @param recipes         本轮可用配方
     * @param acceptedVariants 本轮通过采集门禁的变体身份
     */
    private static Map<ItemKey, List<String>> collectVariantRegistrationNbts(
            List<RecipeSnapshot> recipes, Set<ItemKey> acceptedVariants) {
        if (acceptedVariants.isEmpty()) {
            return Map.of();
        }
        Map<ItemKey, TreeSet<String>> observed = new TreeMap<>();
        for (RecipeSnapshot recipe : recipes) {
            for (RecipeOutput output : recipe.outputs()) {
                ItemKey key = output.item();
                String snbt = output.outputNbt();
                if (!key.hasVariant() || !acceptedVariants.contains(key)
                        || snbt == null || snbt.isBlank()) {
                    continue;
                }
                TreeSet<String> forms = observed.computeIfAbsent(key, ignored -> new TreeSet<>());
                forms.add(snbt);
                if (forms.size() > VariantRegistration.MAX_OBSERVED_NBT_PER_VARIANT) {
                    forms.pollLast();
                }
            }
        }
        if (observed.isEmpty()) {
            return Map.of();
        }
        Map<ItemKey, List<String>> registrations = new TreeMap<>();
        observed.forEach((key, forms) -> registrations.put(key, List.copyOf(forms)));
        return Map.copyOf(registrations);
    }

    /**
     * 构造“跳过配方求解”的空结果。
     *
     * <p>该结果不包含任何生成值、轨迹或未解析物品，表示本轮没有评估配方；用于
     * {@code recipe.solveRecipes=false} 时保持下游候选收集与诊断计数结构不变。</p>
     */
    private static SolveResult skippedSolveResult() {
        return new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0);
    }

    /**
     * 为仍无种子的物品追加兜底候选，并把命中值并入种子。
     *
     * <p>只处理既不在既有 EMC 中、也不在当前种子中的物品。创造物品保护决定物品是否允许自动
     * 定价；特殊固定值优先于层级排除与兜底定价链，但显式排除与创造命名空间仍然优先。
     * 兜底链返回非正值、低于最小 EMC 或空结果时不产生候选。命中值同时写入 {@code seeds}，
     * 使下一轮配方求解能够把兜底价格传播到上游中间产物。</p>
     *
     * @param deferredItems 本次调用暂不处理的物品；循环内传入所有配方输出，最终兜底传入空集
     * @return 本轮新增的兜底候选数量
     */
    private int addFallbackCandidates(PipelineSnapshot snapshot, Map<ItemKey, Long> seeds,
                                      Map<ItemKey, Candidate> candidates,
                                      Set<ItemKey> deferredItems) {
        int added = 0;
        long minimumEmc = solveSettings.minimumEmc();
        Predicate<ItemKey> manualDetector = manualDetector();
        if (manualDetector == null) {
            // 手工值判定器不可用时绝不能 fail-open：否则 TAG/RARITY/baseline 可能覆盖管理员
            // 在 ProjectE 手动文件中固定的值。整轮兜底直接跳过，配方候选仍可正常应用。
            if (manualDetectorFailureWarned.compareAndSet(false, true)) {
                LOGGER.warn("SudoersEMC 手工值判定器不可用，本轮跳过全部兜底候选以避免覆盖手动值");
            }
            if (DebugLog.isEnabled()) {
                DebugLog.log("fallback", "manual-detector-unavailable round-aborted");
            }
            return 0;
        }
        int index = 0;
        int inspected = 0;
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            if (inspected++ % FULL_SWEEP_CANCEL_CHECK_INTERVAL == 0 && isCancelled()) {
                break;
            }
            ItemKey item = itemSnapshot.key();
            index++;
            if (seeds.containsKey(item) || deferredItems.contains(item)) {
                continue;
            }
            Long existingValue = snapshot.existingValues().get(item);
            if (existingValue != null && existingValue > 0L) {
                continue;
            }
            // 手动值优先于任何自动来源：即使该物品没有 owned 条目，也不能被 baseline/TAG/RARITY
            // 重新接管。判定器在每轮开头创建一次，避免逐物品执行文件 stat。
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
                    Candidate special =
                            new Candidate(item, decision.specialValue(), PriceSource.TIER, null);
                    candidates.put(item, special);
                    seeds.put(item, special.value());
                    added++;
                }
                continue;
            }

            // NBT resolver 最具体；只有它没有命中时才读取 PEI 等外部基线。
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
            // resolver 与 PEI baseline 都是外部输入：超过溢出阈值的高值绝不能先写入 seeds，
            // 否则会在最终溢出保护之前污染上游配方。丢弃后继续走 TAG/RARITY/FALLBACK。
            baseline = capBaselineForOverflow(item, baseline);

            if (fallbackPricing == null) {
                if (baseline <= 0L || baseline < minimumEmc) {
                    continue;
                }
                Candidate external =
                        new Candidate(item, baseline, PriceSource.EXTERNAL_BASELINE, null);
                candidates.put(item, external);
                seeds.put(item, external.value());
                added++;
                if (resolverValue > 0L) {
                    lastResolverApplied++;
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
     * 按创造物品保护与兜底定价链为一个物品计算兜底候选。
     *
     * <p>预置值与求解后的兜底候选共用这段逻辑，确保同一轮内看到的定价完全一致。任何定价
     * 组件抛出异常时都按“该物品不定价”处理，不影响其余物品。</p>
     */
    private Optional<Candidate> fallbackCandidate(ItemSnapshot itemSnapshot) {
        ItemKey item = itemSnapshot.key();
        if (isExcludedFromPricing(item)) {
            LOGGER.debug("物品 {} 被排除规则命中，跳过兜底定价", item);
            return Optional.empty();
        }
        CreativeItemProtection.Decision decision;
        try {
            decision = creativeProtection.decide(item, false, false);
        } catch (RuntimeException failure) {
            LOGGER.warn("创造物品保护判定失败，跳过物品 {} 的兜底定价", item, failure);
            return Optional.empty();
        }
        if (!decision.priceable()) {
            return Optional.empty();
        }

        if (decision.specialValue() > 0L) {
            return Optional.of(new Candidate(item, decision.specialValue(), PriceSource.SPECIAL_VALUE, null));
        }

        if (fallbackPricing == null) {
            return Optional.empty();
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
            return Optional.empty();
        }
        if (priced.isEmpty()) {
            return Optional.empty();
        }

        FallbackPricing.PricedValue pricedValue = priced.get();
        if (pricedValue.value() <= 0L) {
            return Optional.empty();
        }
        return Optional.of(new Candidate(item, pricedValue.value(), pricedValue.source(), null));
    }


    /**
     * 汇总创造物品保护在本轮扫描中的判定结果。
     *
     * <p>被排除的物品既不会获得配方/兜底值，也不会作为求解器的下游输入。固定值只在没有
     * 正值既有 EMC 时生效：外部/手动值仍然优先，自有条目会先经过归属分离再重新定价。</p>
     *
     * @param snapshot 当前扫描快照
     * @param seeds    初始种子，用于判断既有 EMC 是否受保护
     * @param minimumEmc 生成值下限
     * @return 禁止定价物品与生效的固定值
     */
    private CreativeProtectionPlan planCreativeProtection(PipelineSnapshot snapshot,
                                                          Map<ItemKey, Long> seeds,
                                                          long minimumEmc) {
        Set<ItemKey> deniedItems = new HashSet<>();
        Map<ItemKey, Long> specialValues = new LinkedHashMap<>();
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            CreativeItemProtection.Decision decision;
            try {
                decision = creativeProtection.decide(item, false, false);
            } catch (RuntimeException failure) {
                LOGGER.warn("创造物品保护判定失败，跳过物品 {}", item, failure);
                continue;
            }
            if (!decision.priceable()) {
                deniedItems.add(item);
                continue;
            }
            long specialValue = decision.specialValue();
            if (specialValue <= 0L) {
                continue;
            }
            if (specialValue < minimumEmc) {
                LOGGER.debug("物品 {} 的固定值 {} 低于最小 EMC {}，跳过",
                        item, specialValue, minimumEmc);
                continue;
            }
            if (seeds.containsKey(item)) {
                // 既有 EMC（外部/手动值）优先于配置固定值。
                continue;
            }
            specialValues.put(item, specialValue);
        }
        return new CreativeProtectionPlan(deniedItems, specialValues);
    }

    /**
     * 收集所有会由配方产出的物品。
     *
     * <p>多轮兜底只给“叶子”物品补种：配方输出必须等待求解器按材料成本定价，否则第一轮
     * 兜底种子会让 {@link RecipeSolver} 跳过发布，配方值永远无法覆盖兜底价。</p>
     */
    private static Set<ItemKey> recipeOutputItems(PipelineSnapshot snapshot) {
        Set<ItemKey> outputs = new HashSet<>();
        EvaluationPolicy policy = EvaluationPolicy.defaults();
        for (RecipeSnapshot recipe : snapshot.recipes()) {
            if (recipe == null) {
                continue;
            }
            outputs.addAll(RecipeSolver.publicationTargets(recipe, policy));
        }
        return outputs;
    }

    /**
     * 创造物品保护在本轮扫描中的预计算结果。
     *
     * @param deniedItems  禁止自动定价的物品
     * @param specialValues 需要作为初始种子生效的固定值
     */
    private record CreativeProtectionPlan(Set<ItemKey> deniedItems,
                                          Map<ItemKey, Long> specialValues) {

        CreativeProtectionPlan {
            deniedItems = Set.copyOf(deniedItems);
            specialValues = Map.copyOf(specialValues);
        }
    }


    /**
     * 执行创造物品保护判定。
     *
     * @return 判定结果；判定异常时返回 {@code null}，调用方必须按“排除”处理
     */
    @Nullable
    private CreativeItemProtection.Decision creativeDecision(ItemKey item) {
        try {
            return creativeProtection.decide(item, true, true);
        } catch (RuntimeException failure) {
            LOGGER.warn("创造物品保护判定失败，按排除处理：{}", item, failure);
            return null;
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
     * 在作用域与显式开关都允许时处理 ProjectE 的 {@code custom_emc.json}。
     *
     * <p>红线：只重置 {@code store::isOwned} 的自有条目；管理员手动配置的条目即使超过阈值也
     * 永不改写。{@link OverflowScope#ENTIRE_CUSTOM_EMC} 仅表示扫描整个文件，不构成改写非自有
     * 条目的授权。干跑模式（{@code store == null}）一律跳过，避免测试或预览路径意外写入真实
     * 配置目录。文件改写成功时立即落盘审计；失败只记录警告，绝不中断本轮扫描。</p>
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
            // 生产接线固定只允许自有条目：ENTIRE_CUSTOM_EMC 不是改写管理员手动值的授权。
            ProjectECustomEmcFile.RewriteResult result = target.rewriteAboveThreshold(
                    overflowPipeline.protection(),
                    store::isOwned,
                    overflowPipeline::recordCustomEmcReset);
            if (result.wroteFile()) {
                // 破坏性写盘已成功，立即落盘审计，避免进程崩溃时只剩 .bak 备份。
                overflowPipeline.flushAudit();
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
     * 把本轮新增的溢出审计记录原子落盘。
     *
     * <p>审计日志不再只在优雅关服时保存：每轮扫描应用完成后都会刷新，崩溃或强杀最多丢失
     * 当前这一轮的记录。写入失败由 {@link OverflowPipeline} 记录警告并保留 dirty 标记，
     * 下一轮扫描会重试；任何失败都不影响已经应用的扫描结果。</p>
     */
    private void persistOverflowAudit() {
        if (overflowPipeline == null) {
            return;
        }
        try {
            overflowPipeline.saveAuditIfDirty();
        } catch (RuntimeException failure) {
            LOGGER.warn("保存溢出审计日志失败，本轮结果仍然有效，下一轮将重试", failure);
        }
    }

    /**
     * 更新增量扫描状态。状态存储自身不会抛出异常，这里仍捕获运行时异常以保护结果返回路径。
     */
    private void updateScanState(@Nullable ScanFingerprint inputFingerprint, long durationMillis,
                                 int solvedCount, int unresolvedCount, int solverRounds) {
        if (stateStore == null || inputFingerprint == null) {
            return;
        }
        try {
            stateStore.update(inputFingerprint, durationMillis, solvedCount, unresolvedCount,
                    solverRounds);
        } catch (RuntimeException failure) {
            LOGGER.warn("更新扫描状态失败，本轮结果仍然有效", failure);
        }
    }

    /**
* 取消或失败时让持久化扫描状态失效。
     *
     * <p>本轮可能已经 stage 了部分条目，调度器仍可能重映射并落盘；如果保留旧指纹，输入未变化
     * 的下一次启动会跳过扫描，把这些部分价格永久沿用。清除指纹后退化为“需要扫描”。</p>
     */
    private void invalidateScanState() {
        if (stateStore == null) {
            return;
        }
        try {
            stateStore.invalidate();
        } catch (RuntimeException failure) {
            LOGGER.warn("失效扫描状态失败，下一次启动可能错误跳过扫描", failure);
        }
    }

    /**
     * 写出等待中的诊断报告。
     *
     * <p>必须在扫描的最后一个 tick 采样完成后调用（生产接线由
     * {@code ScanManager.ResultApplier#onCompleted()} 触发），这样 APPLYING/SAVING tick 的
     * 耗时与峰值内存也会进入报告。报告使用调用时刻的最新诊断采样重建动态计数器，因此
     * tick 数、耗时与峰值堆可能比 {@code applyStashed()} 返回的 {@link PipelineResult#extraCounters()}
     * 更新；磁盘写入失败时 {@code fullReportWritten} 保持 {@code false}，后续取消/失败路径
     * 仍可写出部分报告。</p>
     *
     * <p>{@code solveAndApply} 内部失败时同样通过这里写出失败报告：异常被捕获时 APPLYING
     * tick 尚未由 {@code ScanManager} 统一采样，立即写盘会漏掉本 tick 的耗时与 P95，
     * 因此失败报告与完整报告共用“完成回调后再写”的时点。</p>
     */
    public void writePendingReport() {
        flushPendingReport(diagnostics);
    }

    /**
     * 消费并写出等待中的报告。
     *
     * @param telemetry 调度器共享的诊断采样器；{@code null} 时使用管线内部实例
     * @return {@code true} 表示等待中的报告已被处理（完整报告写出、延迟失败报告写出，
     *         或报告开关关闭）；{@code false} 表示没有等待报告，或完整报告写盘失败、
     *         调用方仍应回退到部分报告
     */
    private boolean flushPendingReport(@Nullable ScanDiagnostics telemetry) {
        PendingReport pending = pendingReport;
        pendingReport = null;
        if (pending == null) {
            return false;
        }
        if (!generateReportsEnabled || reportManager == null) {
            return true;
        }
        if (pending.failureReason() != null) {
            ScanDiagnostics selected = telemetry == null ? diagnostics : telemetry;
            writePartialReportNow(pending.snapshot(), selected, false, "扫描失败");
            return true;
        }
        return writeFullReport(pending);
    }

    /**
     * 写出完整诊断报告。
     *
     * @return 磁盘写入成功返回 {@code true}；写入失败或异常时返回 {@code false}
     */
    private boolean writeFullReport(PendingReport pending) {
        if (!generateReportsEnabled || reportManager == null) {
            return false;
        }
        try {
            Map<String, Long> counters = new LinkedHashMap<>(pending.result().extraCounters());
            refreshDiagnosticCounters(counters, pending.result().durationMillis(), diagnostics);
            if (reportManager.writeReport(buildReport(pending.snapshot(), pending.result(), counters))) {
                fullReportWritten = true;
                return true;
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("写入诊断报告失败，本轮结果仍然有效", failure);
        }
        return false;
    }

    /**
     * 等待完成回调写出的报告参数。
     *
     * @param snapshot      本轮采集快照
     * @param result        本轮完整结果；失败报告时为全零结果
     * @param failureReason 非 {@code null} 表示求解/应用阶段失败，报告必须按部分报告写出
     */
    private record PendingReport(PipelineSnapshot snapshot, PipelineResult result,
                                 @Nullable String failureReason) {
    }

    /**
     * 扫描被取消时写出部分报告。
     *
     * <p>扫描可能停在采集或求解阶段，因此只保证输出耗时、内存与已经采集到的快照统计，
     * 不强求完整的求解结果。报告写入开关关闭时直接忽略。</p>
     *
     * @param telemetry 调度器共享的诊断采样器；{@code null} 时使用管线内部实例
     */
    public void writeCancelledReport(@Nullable ScanDiagnostics telemetry) {
        PipelineSnapshot snapshot = stashedSnapshot.getAndSet(null);
        if (snapshot == null) {
            // applyStashed 已经消费掉暂存快照时，仍用最近一次快照保留物品/配方统计。
            snapshot = lastSnapshotForReport;
        }
        writePartialReport(snapshot, telemetry, true, "扫描已取消");
    }

    /**
     * 扫描失败时写出部分报告。
     *
     * <p>异常消息只写入日志，报告本身保持纯文本结构稳定。报告写入开关关闭时直接忽略。</p>
     *
     * @param detail    失败原因，仅用于日志，可为 {@code null}
     * @param telemetry 调度器共享的诊断采样器；{@code null} 时使用管线内部实例
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
        ScanDiagnostics selected = telemetry == null ? diagnostics : telemetry;
        // 扫描已经完成或求解阶段失败时，等待中的报告必须包含完成回调前的全部 tick；
        // 先消费它，既避免完整报告被部分报告覆盖，也避免延迟失败报告重复写出。
        if (flushPendingReport(selected)) {
            LOGGER.debug("本轮扫描已有待写报告，跳过部分报告（{}）", reason);
            return;
        }
        if (fullReportWritten) {
            LOGGER.debug("完整诊断报告已写出，跳过部分报告（{}）", reason);
            return;
        }
        writePartialReportNow(snapshot, selected, cancelled, reason);
    }

    /**
     * 立即写出部分报告。调用方必须已经消费等待中的报告，避免覆盖完整报告。
     */
    private void writePartialReportNow(@Nullable PipelineSnapshot snapshot,
                                       @Nullable ScanDiagnostics telemetry,
                                       boolean cancelled, String reason) {
        if (!generateReportsEnabled || reportManager == null) {
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
                    lastOwnershipExternalModifications, lastOwnershipReleased, 0, 0L, 0, selected);
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
     * 追加外部基线与 resolver 相关的诊断计数器。
     *
     * <p>所有读取都包在 try/catch 中：来源或注册表属于第三方/可选组件，任何异常都只能让
     * 计数器退化为 0，绝不能影响本轮扫描结果。</p>
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
     * 汇总本轮全部计数器。
     *
     * <p>计数器同时用于 {@link PipelineResult#extraCounters()} 与诊断报告，保证两处数据
     * 完全一致。配方统计、求解轮数、隔离/清理数量、逐 tick 耗时与内存采样都集中在这里，
     * 报告层无需再次推算。</p>
     */
    private Map<String, Long> buildExtraCounters(PipelineSnapshot snapshot,
                                                         SolveResult solveResult,
                                                         int quarantined, int overflowResets,
                                                         long durationMillis,
                                                         boolean appliedToStore,
                                                         int retiredEntries, int solverRounds,
                                                         int ownershipExternalModifications,
                                                         int ownershipReleased,
                                                         int refreshedOwned,
                                                         long auditDropped, int staleOwned,
                                                         @Nullable ScanDiagnostics telemetry) {
        ScanDiagnostics selected = telemetry == null ? new ScanDiagnostics() : telemetry;
        selected.recordMemory();

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
        if (snapshot.recipeEnumerationFailed()) {
            // 枚举整体失败时总数与分类计数都未知，不能伪造一条失败配方。
            counters.put("recipeEnumerationFailed", 1L);
        } else {
            long usableRecipes = snapshot.recipeCount();
            long unsupported = snapshot.unsupportedRecipes();
            long invalid = snapshot.invalidRecipes();
            long failed = snapshot.failedRecipes();
            counters.put("totalRecipes", usableRecipes + unsupported + invalid + failed);
            counters.put("usableRecipes", usableRecipes);
            counters.put("unsupportedRecipes", unsupported);
            counters.put("invalidRecipes", invalid);
            counters.put("failedRecipes", failed);
        }
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
        counters.put("ownedRefreshed", (long) refreshedOwned);
        counters.put("overflowAuditDropped", auditDropped);
        counters.put("staleOwnedEntries", (long) staleOwned);
        counters.put("solverCacheHits", (long) lastSolverCacheHits);
        counters.put("solverCacheMisses", (long) lastSolverCacheMisses);
        counters.put("dryRun", appliedToStore ? 0L : 1L);
        refreshDiagnosticCounters(counters, durationMillis, telemetry);
        counters.put("partialReport", 0L);
        return counters;
    }

    /**
     * 用当前诊断采样刷新动态计数器。
     *
     * <p>延迟写报告与部分报告都复用本方法，保证 tick 数、最大/平均/P95 耗时、总耗时与
     * 峰值堆内存始终来自写出时刻的最新采样，而不是求解结束时的旧快照。</p>
     */
    private static void refreshDiagnosticCounters(Map<String, Long> counters,
                                                  long fallbackDurationMillis,
                                                  @Nullable ScanDiagnostics telemetry) {
        ScanDiagnostics selected = telemetry == null ? new ScanDiagnostics() : telemetry;
        selected.recordMemory();
        long tickCount = selected.tickCount();
        long scanTotalMillis = tickCount > 0L
                ? selected.elapsedSinceResetMillis()
                : fallbackDurationMillis;
        counters.put("scanTotalMillis", Math.max(0L, scanTotalMillis));
        counters.put("scanTicks", tickCount);
        counters.put("tickMaxMicros", nanosToMicros(selected.maxTickNanos()));
        counters.put("tickAvgMicros", Math.round(selected.averageTickNanos() / 1_000.0D));
        counters.put("tickP95Micros", nanosToMicros(selected.p95TickNanos()));
        counters.put("peakHeapBytes", selected.peakHeapBytes());
        counters.put("heapAtReportBytes", selected.heapBytes());
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, (nanos + 500L) / 1_000L);
    }

    /**
     * 根据本轮运行数据构造诊断报告。
     *
     * <p>标准字段只统计实际应用的条目；配方扫描统计、求解轮数与耗时采样等放入
     * {@code extraCounters}，便于后续解析器在不改变报告结构的情况下读取。未解析物品数
     * 使用多轮收敛后的最终计数，而不是某一轮的中间结果。</p>
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
     * 统计本轮未被刷新的自有条目数量。
     *
     * <p>包括两类：物品已不在本次扫描快照中（例如注册表移除或被忽略），以及物品仍在扫描
     * 结果中但本轮没有产生任何候选值（例如配方/标签变化后不再可定价）。本方法只用于诊断
     * 暴露，不会自动删除或迁移记录；删除玩家数据需要明确的业务语义，留待后续阶段。</p>
     *
     * @param previousEntries 应用前的存储快照
     * @param snapshot        本轮扫描快照
     * @param candidates      本轮构建的候选值
     * @return 未被刷新的自有条目数量
     */
    private static int countStaleOwned(Map<ItemKey, GeneratedEmcEntry> previousEntries,
                                       PipelineSnapshot snapshot, List<Candidate> candidates) {
        if (previousEntries.isEmpty()) {
            return 0;
        }
        Set<ItemKey> scannedItems = new HashSet<>();
        for (ItemSnapshot item : snapshot.items()) {
            scannedItems.add(item.key());
        }
        Set<ItemKey> refreshedItems = new HashSet<>();
        for (Candidate candidate : candidates) {
            refreshedItems.add(candidate.item());
        }
        int stale = 0;
        for (Map.Entry<ItemKey, GeneratedEmcEntry> entry : previousEntries.entrySet()) {
            GeneratedEmcEntry value = entry.getValue();
            if (value == null || !value.owned()) {
                continue;
            }
            if (!scannedItems.contains(entry.getKey()) || !refreshedItems.contains(entry.getKey())) {
                stale++;
            }
        }
        return stale;
    }

    /**
     * 计算本次扫描的完整输入指纹。
     *
     * <p>生产接线会注入完整指纹提供器（物品、配方内容、标签成员、配置与基线通道）；提供器
     * 未注入时才退回仅基于快照的指纹，供不接触游戏状态的单元测试使用。已注入提供器但调用失败
     * 或返回 {@code null} 时抛出 {@link IllegalStateException}，绝不把不完整指纹写入扫描状态或
     * 用于采集前后一致性校验。提供器必须在服务器线程调用，因此本方法只在求解启动阶段执行。</p>
     *
     * @throws IllegalStateException 指纹提供器失败或返回 {@code null} 时
     */
    private ScanFingerprint captureInputFingerprint(PipelineSnapshot snapshot) {
        Supplier<ScanFingerprint> supplier = inputFingerprintSupplier;
        if (supplier == null) {
            return fingerprint(snapshot);
        }
        ScanFingerprint fingerprint;
        try {
            fingerprint = supplier.get();
        } catch (RuntimeException failure) {
            LOGGER.error("计算扫描输入指纹失败，拒绝使用不完整指纹继续扫描", failure);
            throw new IllegalStateException("计算扫描输入指纹失败", failure);
        }
        if (fingerprint == null) {
            throw new IllegalStateException("计算扫描输入指纹失败：提供器返回 null");
        }
        return fingerprint;
    }

    /**
     * 计算扫描指纹：物品 id、配方 id 升序摘要；标签、配置与基线通道在快照降级路径中为空。
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
     * 一条待写入的候选值。
     *
     * <p>可见性为包内可见，便于同包测试直接验证“同一物品只保留配方来源”的合并规则。</p>
     *
     * @param item             物品身份
     * @param value            经过求解或兜底定价得到的原始数值，保证 {@code > 0}
     * @param source           数值来源
     * @param recipeId         产生该值的配方，兜底定价结果为 {@code null}
     * @param alternativeCosts 同一轮求解中的其它配方成本；兜底来源为空列表
     */
    record Candidate(ItemKey item, long value, PriceSource source,
                     @Nullable ResourceLocation recipeId, List<Long> alternativeCosts) {

        Candidate {
            alternativeCosts = alternativeCosts == null ? List.of() : List.copyOf(alternativeCosts);
        }

        /**
         * 兼容旧测试与调用点的构造器：不携带备选成本。
         */
        Candidate(ItemKey item, long value, PriceSource source,
                  @Nullable ResourceLocation recipeId) {
            this(item, value, source, recipeId, List.of());
        }
    }

    /**
     * 跨 {@link ScanPipeline} 实例共享的重映射确认状态。
     *
     * <p>配置重载会重建管线；若状态留在管线实例上，一次失败的重映射保护会在新管线的
     * 首轮丢失。把状态抽到可注入的 tracker 后，生产环境在重建之间复用同一实例，
     * 而单元测试仍可使用独立实例，避免测试间相互污染。</p>
     *
     * <p>代次在每次重映射完成（无论成功或失败）时推进，并与采集快照一起带入 apply。
     * 只要采集开始后发生过重映射完成，本轮 observed 值就可能来自写入前，必须跳过漂移检测。</p>
     */
    public static final class RemapTracker {

        private final AtomicReference<CompletableFuture<?>> inFlight = new AtomicReference<>();
        private final AtomicLong generation = new AtomicLong();
        private final Object stateLock = new Object();
        private boolean unconfirmed;

        /**
         * 记录一次重映射请求；完成时推进代次并更新确认状态。
         *
         * @param completion 完成信号，允许为 {@code null}（无法确认时不改变状态）
         */
        public void track(@Nullable CompletableFuture<?> completion) {
            if (completion == null) {
                return;
            }
            synchronized (stateLock) {
                inFlight.set(completion);
            }
            completion.whenComplete((ignored, failure) -> {
                synchronized (stateLock) {
                    if (inFlight.get() != completion) {
                        // 已被更新的重映射请求取代，旧结果不得改变当前状态。
                        return;
                    }
                    // 先推进代次再清除在途标记：任何在此前采集的快照都会被视为过期。
                    generation.incrementAndGet();
                    inFlight.set(null);
                    unconfirmed = failure != null;
                    if (failure != null) {
                        LOGGER.warn("SudoersEMC 的 ProjectE 重映射未成功完成，下一轮继续跳过外部修改释放",
                                failure);
                    }
                }
            });
        }

        /**
         * @return 当前重映射代次
         */
        public long generation() {
            return generation.get();
        }

        /**
         * 判断某次采集的快照是否必须跳过漂移检测。
         *
         * @param snapshotGeneration 采集开始时记录到的代次；未知代次传
         *                           {@link PipelineSnapshot#UNKNOWN_REMAP_GENERATION}
         * @return 仍有在途重映射、上次重映射失败，或采集后发生过重映射完成时返回 {@code true}
         */
        public boolean confirmationPending(long snapshotGeneration) {
            synchronized (stateLock) {
                if (inFlight.get() != null || unconfirmed) {
                    return true;
                }
                return snapshotGeneration >= 0L && snapshotGeneration != generation.get();
            }
        }

        /**
         * 清空跨服务器会话的重映射状态。
         */
        public void reset() {
            synchronized (stateLock) {
                inFlight.set(null);
                unconfirmed = false;
                generation.incrementAndGet();
            }
        }
    }
}
