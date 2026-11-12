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
 * 把扫描、配方求解、兜底定价、安全层与持久化串联起来的运行时管线。
 *
 * <p>一次完整扫描分为两个阶段：</p>
 * <ol>
 *   <li>{@link #collect(MinecraftServer)} 在服务器线程读取物品注册表与配方管理器，产出不可变的
 *       {@link PipelineSnapshot}；</li>
 *   <li>{@link #solveAndApply(PipelineSnapshot)} 只做纯计算与存储写入，不再访问注册表。</li>
 * </ol>
 *
 * <p>{@link com.itzplana.sudoersemc.task.ScanManager} 会在采集阶段与阶段之间跨越多个 tick，
 * 因此本类提供 {@link #stash(PipelineSnapshot)} 与 {@link #applyStashed()} 配对使用：
 * 采集回调只负责暂存快照，应用回调再取出并执行求解。相比让外部 lambda 捕获数组，显式暂存字段
 * 更容易测试、诊断与避免重复应用。</p>
 *
 * <p><strong>空值语义：</strong>{@code store} 为 {@code null} 表示干跑模式，所有计数与报告照常
 * 计算，但不会写入任何持久化条目，也不会写隔离记录文件。其余协作者为 {@code null} 时按
 * “关闭该能力”处理：降价保护与创造物品保护使用安全默认值，兜底定价与溢出保护在缺失时直接
 * 跳过。这样单元测试可以在没有完整运行时环境的情况下验证管线主干。</p>
 *
 * <p><strong>异常契约：</strong>{@link #collect(MinecraftServer)} 与
 * {@link #solveAndApply(PipelineSnapshot)} 都不会向调用方抛出异常。任何失败都会记录日志并返回
 * 空快照或全零结果，保证服务器主循环与 {@code ScanManager} 状态机不会被单次扫描拖垮。</p>
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
     * 上限同时把异常配方图的开销限制在常量级，达到上限后直接使用当前种子定价。</p>
     */
    static final int MAX_SOLVE_ROUNDS = 4;

    /** NBT 变体生成时每处理多少个观测条目检查一次取消信号。 */
    static final int NBT_VARIANT_CANCEL_CHECK_INTERVAL = 64;

    /** 应用循环每处理多少个候选检查一次取消信号。 */
    static final int APPLY_CANCEL_CHECK_INTERVAL = 64;

    /** 既有值收集每处理多少个物品检查一次取消信号。 */
    static final int EXISTING_VALUE_CANCEL_CHECK_INTERVAL = 256;

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
    /** 服务器线程预先解析的手工值判定器；工作线程只读取，不再触发文件 I/O。 */
    private volatile Predicate<ItemKey> preparedManualDetector;
    private volatile boolean manualDetectorPrepared;
    /** 服务器线程预先计算的 resolver 值；工作线程求解时只读。 */
    private volatile Map<ItemKey, Long> preparedResolverValues = Map.of();
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
     * 注入隔离记录存储。
     *
     * <p>注入后，每当大幅降价保护判定隔离时，管线会把旧值、拟写入值、降幅、原因与时间戳
     * 写入该存储，供管理员通过 {@code /sudoemc approve} 审批。传入 {@code null} 时只保留原有
     * 的内存计数与日志行为。干跑模式（生成条目存储为 {@code null}）永远不会写隔离文件。</p>
     *
     * @param newStore 隔离记录存储，允许为 {@code null}
     */
    public void setQuarantinedDropsStore(@Nullable QuarantinedDropsStore newStore) {
        this.quarantinedDropsStore = newStore;
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
     * 设置是否允许改写 ProjectE 的 {@code custom_emc.json}。
     *
     * <p>默认 {@code false}。只有同时启用 {@link OverflowScope#ENTIRE_CUSTOM_EMC} 作用域时才会
     * 产生实际文件写入；这是避免误改管理员手动配置的第二道保险。</p>
     *
     * @param enabled 是否允许改写 ProjectE 文件
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
     * 注入手工值判定器来源。来源在每次应用阶段调用一次，返回的判定器会被复用于本轮全部物品。
     * 传入 {@code null} 恢复“没有任何手工值”的默认行为。
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
        this.preparedResolverValues = Map.of();
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
     * HashMap 遍历顺序影响。物品通道包含注册表 id 与稀有度，配方通道包含配方完整内容摘要。</p>
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
     * 开始一次增量采集会话。
     *
     * <p><strong>必须由服务器线程调用。</strong>会话按“物品批次 → 配方批次 → NBT 变体汇总 →
     * 既有 EMC 读取”推进；每批只处理给定预算，完成后 {@link CollectSession#finish()} 返回
     * 不可变的 {@link PipelineSnapshot}。物品与配方均按 {@link ResourceLocation} 排序后分页，
     * 保证跨 tick 游标稳定。</p>
     *
     * @param server 当前服务器实例，允许为 {@code null}
     * @return 增量采集会话；服务器未就绪时返回已经完成的空会话
     */
    public CollectSession beginCollect(@Nullable MinecraftServer server) {
        fullReportWritten = false;
        if (server == null) {
            LOGGER.warn("SudoersEMC 扫描采集被调用时服务器尚未就绪，返回空快照");
            return ScanCollector.completed(PipelineSnapshot.empty());
        }
        prepareExternalBaseline(server);
        return ScanCollector.begin(this, server);
    }

    /**
     * 采集一次扫描所需的全部数据。
     *
     * <p><strong>必须由服务器线程调用。</strong>本方法是 {@link #beginCollect(MinecraftServer)}
     * 的兼容包装：循环执行 {@code step(Integer.MAX_VALUE)} 并在完成后取回快照，因此未启用
     * 分片扫描的调用点仍保持一次性调用的语义。</p>
     *
     * @param server 当前服务器实例，允许为 {@code null}
     * @return 采集结果；任何失败或服务器未就绪时返回空快照
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
     * 增量采集会话。
     *
     * <p>{@link #itemCount()} 与 {@link #recipeCount()} 是本次采集计划处理的注册项/配方总数，
     * 供进度显示使用；{@link #processedItems()} 与 {@link #processedRecipes()} 是当前已推进
     * 的数量。所有方法必须在服务器线程调用。</p>
     */
    public interface CollectSession {

        /**
         * @return 是否已经完成（包括取消后的部分完成）
         */
        boolean isDone();

        /**
         * @return 计划检查的物品注册项数量
         */
        int itemCount();

        /**
         * @return 计划检查的配方数量
         */
        int recipeCount();

        /**
         * @return 已检查的物品注册项数量
         */
        int processedItems();

        /**
         * @return 已检查的配方数量
         */
        int processedRecipes();

        /**
         * 按预算推进当前阶段。
         *
         * @param budget 本次最多处理的工作量；小于 1 时按 1 处理
         */
        void step(int budget);

        /**
         * 完成剩余工作并返回不可变快照。取消命中时返回已采集的部分快照。
         *
         * @return 采集快照，永不为 {@code null}
         */
        PipelineSnapshot finish();
    }

    /**
     * 创建生产用物品扫描器；EMC 查询通过当前管线注入的后端执行。
     */
    ItemScanner newItemScanner() {
        return new ItemScanner(emcQuery(), Set.of(), Set.of());
    }

    /**
     * 创建生产用配方扫描器，按当前配置决定是否读取容器返还物与 Goety 仪式。
     */
    RecipeScanner newRecipeScanner() {
        return RecipeScanner.withGoety(scanContainerRemainders)
                .orElseGet(() -> new RecipeScanner(
                        GoetyRitualReader.unavailable(), scanContainerRemainders));
    }

    /**
     * @return 协作式取消检查，供采集会话在批次边界复用
     */
    BooleanSupplier cancellationPredicate() {
        return this::isCancelled;
    }

    /**
     * @return 是否启用 NBT 变体扫描
     */
    boolean nbtVariantsEnabled() {
        return nbtVariantsEnabled;
    }

    /**
     * @return 每个物品最多保留的 NBT 变体数
     */
    int maxNbtVariantsPerItem() {
        return maxNbtVariantsPerItem;
    }

    /**
     * 在服务器线程准备纯求解所需的输入：解析一次手工值判定器，并在存在 resolver 时预计算
     * 所有物品的 resolver 值。工作线程随后只读取这些不可变结果，不再访问文件或注册表。
     */
    void prepareSolveInputs(@Nullable List<ItemSnapshot> items) {
        this.preparedManualDetector = resolveManualDetector();
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
     * 构造 EMC 后端查询；后端缺失或单次查询失败时退化为“没有既有值”。
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
     * 构造取消时的部分快照：物品/配方保持已采集部分，既有值只从当前物品列表重新推导。
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
     * 根据配方输出中观测到的 SNBT 生成 NBT 变体快照。
     *
     * <p>处理顺序固定：先按物品 id 汇总所有 {@link RecipeOutput#outputNbt()}，再把 SNBT
     * 解析回 {@link CompoundTag}，交给 {@link VariantScanner} 规范化、去重并限制数量。只有
     * 已在基础物品快照中出现过的物品才会生成变体，因此忽略规则与注册表缺失物品不会被
     * 变体扫描重新引入。</p>
     *
     * <p>变体快照的 {@code hasExistingEmc} 固定为 {@code false}、{@code existingValue} 固定为
     * {@code 0}，标签路径与稀有度继承基础物品。结果按 {@link ItemKey} 排序（物品 id，然后
     * variantHash），保证相同输入得到稳定的输出顺序；无法解析或无法规范化的标签会被跳过。</p>
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
                () -> false);
    }

    /**
     * 带协作式取消检查的 NBT 变体生成。
     *
     * <p>每处理 {@value #NBT_VARIANT_CANCEL_CHECK_INTERVAL} 个观测条目检查一次取消信号。取消时
     * 返回已经生成并排序的变体子集，序列不可变且与完整结果保持相同排序规则。</p>
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
                // 基础物品未被扫描（被忽略或未注册）时不生成变体，避免绕过排除规则。
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
     * 为单个物品的观测 SNBT 生成规范化变体键。
     *
     * <p>该方法是完整采集与分片采集共享的唯一实现：解析 SNBT、调用 {@link VariantScanner}
     * 去重并写入采样调试日志。基础物品是否允许生成变体由调用方判断。</p>
     *
     * @param variantScanner 复用的变体扫描器
     * @param registryId     基础物品 id，可为 {@code null}
     * @param observedNbt    配方输出中观测到的 SNBT 列表，可为 {@code null}
     * @param limit          每个物品最多保留的变体数
     * @param processedIndex 已处理观测条目序号，仅用于调试采样
     * @return 变体键列表；无法解析或失败时为空
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
     * 暂存采集结果，等待应用阶段取用。
     *
     * <p>该方法是“stash + applyStashed”接线方式的一半，专门服务于
     * {@link com.itzplana.sudoersemc.task.ScanManager} 跨 tick 的阶段划分。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     */
    public void stash(@Nullable PipelineSnapshot snapshot) {
        fullReportWritten = false;
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
        return separateOwnedEntries(items, owned, Map.of());
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
        // 已成功释放所有权的条目即使不在本轮物品快照中，也要保留其最后已知值作为种子，
        // 避免下一轮被配方或兜底定价重新接管。
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
     * @return 分离后的快照与外部修改计数
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
     * 纯归属分离：与 {@link #applyOwnershipSeparation(PipelineSnapshot)} 结果相同，但不释放
     * 任何所有权。供工作线程的 {@code solveOnly} 使用；释放动作由服务器线程的应用阶段执行。
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
     * 只读检测 owned 条目的外部修改，并保留外部值或最后已知值。
     *
     * <p>与 {@link #applyOwnershipSeparation(PipelineSnapshot)} 不同，本方法不迁移所有权，
     * 因此可以在工作线程执行（归属存储的 {@code snapshotAll()} / {@code isOwned()} 是并发
     * 安全的只读快照）。真正的所有权释放由服务器线程在应用阶段执行。</p>
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
            // 即使所有权释放写入失败，也保留外部值并阻止本轮覆盖；失败只影响后续
            // 扫描是否还能看到 owned 标记，不影响本轮的经济安全。
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
     * 在服务器线程释放检测到的外部修改条目的所有权。
     *
     * @param detected       检测阶段统计的条目数，仅用于日志
     * @param protectedItems 需要释放所有权的物品集合
     * @return 成功释放的数量
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
                                Set<ItemKey> protectedItems) {

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
     * 纯求解阶段：归属分离、种子构建、多轮“配方求解 → 兜底补种”收敛、候选去重与未解析统计。
     *
     * <p>本方法<strong>不写存储</strong>、不触发溢出审计、不写报告或扫描状态文件、不请求重映射。
     * 唯一的持久化相关读取是 {@code GeneratedEntryStore} 的并发安全只读快照（{@code snapshotAll()}
     * 与 {@code isOwned()}）；所有权释放推迟到服务器线程的
     * {@link #applySolved(PipelineSnapshot, SolveOutcome)}。因此本方法可以安全地在工作线程执行。</p>
     *
     * <p>服务器线程会在采集完成时预先解析手工值判定器并预计算 resolver 值；工作线程随后只读这些
     * 不可变结果，不会访问文件系统或 Minecraft 注册表。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     * @return 不可变的纯求解结果；未预期异常会转为失败结果，绝不向工作线程之外抛出
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
                if (isCancelled()) {
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
     * 应用已求解结果：安全层、存储写入、陈旧条目清理、扫描状态与诊断报告。
     *
     * <p><strong>必须由服务器线程调用。</strong>本方法会先释放求解阶段检测到的外部修改条目
     * 所有权，再逐条执行大幅降价保护、溢出保护与 {@code store.stage}。候选、计数与报告字段
     * 全部来自 {@link SolveOutcome}，因此 {@code applySolved(snapshot, solveOnly(snapshot))}
     * 与拆分前的一次性求解应用逐位一致。</p>
     *
     * @param snapshot 原始采集结果，仅用于防御性空值处理；求解后的有效快照来自 outcome
     * @param outcome  纯求解结果，允许为 {@code null}
     * @return 汇总结果；任何未预期异常都会转为全零结果
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
            int released = releaseDetectedOwnership(
                    outcome.ownershipDetected(), outcome.protectedItems());
            lastOwnershipExternalModifications = outcome.ownershipDetected();
            lastOwnershipReleased = released;

            if (outcome.failed()) {
                long durationMillis = elapsedMillis(startedAtNanos);
                lastSolvedCount = 0;
                lastUnresolvedCount = 0;
                lastSolverRoundCount = 0;
                lastDurationMillis = durationMillis;
                LOGGER.error("SudoersEMC 扫描管线求解与应用失败，本轮返回全零结果：{}",
                        outcome.failureDetail());
                writePartialReport(data, diagnostics, false, "扫描失败");
                return PipelineResult.zeroed(durationMillis);
            }
            if (outcome.cancelled() || isCancelled()) {
                return cancelledResult(data, startedAtNanos);
            }

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
                // 取消时不能把未完成的候选集合写成新的扫描指纹，否则下一次启动会误判为未变化。
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
                // 已写入的条目各自原子且一致；这里只写部分报告，交由调度器终止扫描。
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
            // 失败也要留下部分报告；报告层自身保证不抛出异常。
            writePartialReport(data, diagnostics, false, "扫描失败");
            return PipelineResult.zeroed(durationMillis);
        }
    }

    /**
     * 求解快照并为可定价物品写入生成值。
     *
     * <p>本方法是 {@link #solveOnly(PipelineSnapshot)} 与
     * {@link #applySolved(PipelineSnapshot, SolveOutcome)} 的兼容包装，保证拆分前后结果逐位一致。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     * @return 汇总结果；任何未预期异常都会转为全零结果
     */
    public PipelineResult solveAndApply(@Nullable PipelineSnapshot snapshot) {
        return applySolved(snapshot, solveOnly(snapshot));
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
        writePartialReport(data, diagnostics, true, "扫描已取消");
        fullReportWritten = true;
        return PipelineResult.zeroed(durationMillis);
    }

    /**
     * 清理本轮完全没有参与定价的自有条目。
     *
     * <p>物品被新配置排除、配方被移除或物品从注册表消失后，旧的自有值不应继续生效。判定口径：
     * 条目 {@code owned=true}，且既不在本轮最终候选中，也不在本轮 {@code existingValues} 中，
     * 即视为完全没有参与定价。非自有条目（手动配置或外部后端写入）永远不会删除。干跑模式
     * （{@code store == null}）或空快照（例如服务器尚未就绪）直接跳过，避免误删全部条目。</p>
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
     * @return 最近一次 {@link #solveAndApply(PipelineSnapshot)} 实际执行的求解轮数；
     *         空世界或第一轮就无新增值时为 {@code 1}
     */
    int lastSolverRoundCount() {
        return lastSolverRoundCount;
    }

    /**
     * 构造初始求解种子。
     *
     * <p>只有严格大于 0 的既有 EMC 才能成为种子：0 或负值等同于“没有值”，
     * 否则会遮蔽后续的配方与兜底定价。</p>
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
     * 执行一轮配方求解。
     *
     * <p>关闭配方求解时返回空结果，但保留“每轮兜底补种”的循环结构：第一轮兜底命中后，
     * 第二轮没有新增值会立即退出。</p>
     */
    private SolveResult solveRound(PipelineSnapshot data, Map<ItemKey, Long> seeds) {
        if (!solveRecipesEnabled) {
            LOGGER.debug("配方求解已按配置禁用，本轮仅使用既有值与兜底定价");
            return skippedSolveResult();
        }
        SolveContext context = new SolveContext(seeds);
        // 复用同一个求解器实例，让未变化配方的评估缓存跨轮次与跨扫描生效。
        return recipeSolver.solve(
                data.recipes(), context, evaluatorRegistry, solveSettings, this::isCancelled);
    }

    /**
     * 把一轮求解结果并入种子与配方候选。
     *
     * <p>同键取较小值，防止后续轮次抬高已生成值；仅当
     * {@link SolveSettings#allowValueIncrease()} 为 {@code true} 时才接受新值覆盖。</p>
     *
     * @return 本轮是否新增或更新了任何种子
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
     * 通过第三方 resolver 注册表为物品解析 EMC。
     *
     * <p>空注册表直接返回 0，不重建 {@link ItemStack}、不解析 SNBT，保证默认行为与 Phase 24
     * 完全一致。任何异常都退化为“无值”，继续 TAG/RARITY/FALLBACK。</p>
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
     * 直接通过当前线程可访问的注册表与 resolver 解析；仅供服务器线程或测试线程使用。
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
     * 定价；固定值优先于兜底定价链。兜底链返回非正值、低于最小 EMC 或空结果时不产生候选。
     * 命中值同时写入 {@code seeds}，使下一轮配方求解能够把兜底价格传播到上游中间产物。</p>
     *
     * @return 本轮新增的兜底候选数量
     */
    private int addFallbackCandidates(PipelineSnapshot snapshot, Map<ItemKey, Long> seeds,
                                      Map<ItemKey, Candidate> candidates) {
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
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            index++;
            if (seeds.containsKey(item) || snapshot.existingValues().containsKey(item)) {
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
                    Candidate candidate =
                            new Candidate(item, decision.specialValue(), PriceSource.TIER, null);
                    candidates.put(item, candidate);
                    seeds.put(item, candidate.value());
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
     * 把一次被隔离的降价写入持久化隔离记录。
     *
     * <p>干跑模式（生成条目存储为 {@code null}）或未注入隔离存储时不产生任何磁盘写入，原有
     * 的计数与日志行为保持不变。记录失败只影响隔离文件，不影响本轮扫描结果。</p>
     *
     * @param candidate 被隔离的候选值
     * @param decision  降价保护判定结果
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
     * 在作用域与显式开关都允许时改写 ProjectE 的 {@code custom_emc.json}。
     *
     * <p>干跑模式（{@code store == null}）一律跳过，避免测试或预览路径意外写入真实配置目录。
     * 文件改写失败只记录警告，绝不中断本轮扫描。</p>
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
            ProjectECustomEmcFile.RewriteResult result = target.rewriteAboveThreshold(
                    overflowPipeline.protection(),
                    store::isOwned,
                    // ENTIRE_CUSTOM_EMC 本身就是改写整个文件的显式 opt-in。
                    true,
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
     * 写出完整诊断报告。报告写入失败只影响诊断文件，不影响本轮数值结果。
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
     * 计算扫描指纹：物品/配方内容、标签、配置与外部基线五个通道全部参与。
     *
     * <p>配置与基线来源在读取失败时退化为固定错误标记，不会阻止扫描完成。</p>
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
    public record Candidate(ItemKey item, long value, PriceSource source,
                            @Nullable ResourceLocation recipeId, List<Long> alternativeCosts) {
        public Candidate {
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
}
