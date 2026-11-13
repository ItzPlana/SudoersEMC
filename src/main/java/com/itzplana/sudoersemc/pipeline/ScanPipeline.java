package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.core.VariantRegistration;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.ProjectECustomEmcFile;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.TierPricing;
import com.itzplana.sudoersemc.resolver.VariantNormalizer;
import com.itzplana.sudoersemc.resolver.VariantScanner;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OwnershipManager;
import com.itzplana.sudoersemc.safety.PriceDropDecision;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
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
import com.itzplana.sudoersemc.storage.ScanStateStore;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
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

    private final AtomicReference<PipelineSnapshot> stashedSnapshot = new AtomicReference<>();
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
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(scanResult, "scanResult");
        List<ItemSnapshot> variantItems = collectVariantSnapshots(
                items, scanResult.recipes(), nbtVariantsEnabled, maxNbtVariantsPerItem, emcQuery);
        List<ItemSnapshot> allItems = items;
        if (!variantItems.isEmpty()) {
            List<ItemSnapshot> combined = new ArrayList<>(items.size() + variantItems.size());
            combined.addAll(items);
            combined.addAll(variantItems);
            allItems = List.copyOf(combined);
        }

        Map<ItemKey, Long> existingValues = new TreeMap<>();
        for (ItemSnapshot item : allItems) {
            if (item.hasExistingEmc()) {
                existingValues.put(item.key(), item.existingValue());
            }
        }

        return new PipelineSnapshot(
                allItems,
                scanResult.recipes(),
                existingValues,
                scanResult.unsupported(),
                scanResult.invalid(),
                scanResult.failed());
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
        return collectVariantSnapshots(baseItems, recipes, enabled, maxNbtVariantsPerItem, null);
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
        if (!enabled || baseItems == null || baseItems.isEmpty()
                || recipes == null || recipes.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxNbtVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));

        Map<ResourceLocation, TreeSet<String>> observedByItem = new TreeMap<>();
        for (RecipeSnapshot recipe : recipes) {
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

        for (Map.Entry<ResourceLocation, TreeSet<String>> entry : observedByItem.entrySet()) {
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
     * @return 分离后的物品、仍受保护的既有值、刷新数量与全部自有物品键
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned) {
        List<ItemSnapshot> separated = new ArrayList<>(items.size());
        Map<ItemKey, Long> existingValues = new TreeMap<>();
        Set<ItemKey> ownedItems = new TreeSet<>();
        int refreshedOwned = 0;
        for (ItemSnapshot item : items) {
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
            }
        }
        return new OwnershipSeparation(separated, existingValues, refreshedOwned, ownedItems);
    }

    /**
     * 归属分离的应用结果。
     *
     * @param snapshot       分离后的快照
     * @param ownedItems     本轮扫描中归 SudoersEMC 所有的物品键
     * @param refreshedOwned 被清除既有值的自有条目数量
     */
    private record AppliedSeparation(PipelineSnapshot snapshot, Set<ItemKey> ownedItems,
                                     int refreshedOwned) {

        AppliedSeparation {
            ownedItems = Collections.unmodifiableSet(new TreeSet<>(ownedItems));
        }
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

    /**
     * 求解快照并为可定价物品写入生成值。
     *
     * <p>本方法不访问注册表，只使用快照中的纯数据。处理顺序固定为：</p>
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
     * @param snapshot 采集结果，允许为 {@code null}
     * @return 汇总结果
     * @throws IllegalStateException 求解或应用过程抛出运行时异常时
     */
    public PipelineResult solveAndApply(@Nullable PipelineSnapshot snapshot) {
        long startedAtNanos = System.nanoTime();
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
        ScanFingerprint inputFingerprint = captureInputFingerprint(data);
        try {
            return applyComputation(compute(data, startedAtNanos, inputFingerprint));
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
        Set<ItemKey> externallyModified = detectExternallyModifiedOwnership(snapshot);
        AppliedSeparation separation = applyOwnershipSeparation(snapshot, externallyModified);
        PipelineSnapshot data = separation.snapshot();
        long minimumEmc = solveSettings.minimumEmc();

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
                selectFinalCandidates(recipeCandidates, fallbackCandidates, minimumEmc);
        int solvedByRecipe = 0;
        int solvedByFallback = 0;
        for (Candidate candidate : candidates) {
            if (candidate.source() == PriceSource.RECIPE) {
                solvedByRecipe++;
            } else {
                solvedByFallback++;
            }
        }
        return new Computation(data, lastSolveResult, List.copyOf(candidates),
                solvedByRecipe, solvedByFallback, separation.refreshedOwned(),
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

        releaseExternallyModifiedOwnership(computation.externallyModified());

        Map<ItemKey, GeneratedEmcEntry> previousEntries =
                store == null ? Map.of() : store.snapshotAll();
        int staleOwned = countStaleOwned(previousEntries, data, candidates);
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
                        System.currentTimeMillis()),
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
        writeDiagnosticReport(data, solveResult, unresolved, applied, quarantined, overflowResets,
                durationMillis, lastSolverRoundCount, appliedToStore, sourceCounts,
                computation.refreshedOwned(), auditDropped, staleOwned);

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
    private AppliedSeparation applyOwnershipSeparation(PipelineSnapshot snapshot,
                                                       Set<ItemKey> externallyModified) {
        if (store == null) {
            return new AppliedSeparation(snapshot, Set.of(), 0);
        }
        Set<ItemKey> protectedItems = externallyModified == null ? Set.of() : externallyModified;
        OwnershipSeparation separation = separateOwnedEntries(snapshot.items(),
                key -> store.isOwned(key) && !protectedItems.contains(key));
        if (separation.refreshedOwned() == 0) {
            return new AppliedSeparation(snapshot, separation.ownedItems(), 0);
        }
        LOGGER.debug("SudoersEMC 归属分离刷新了 {} 个自有条目，本轮将重新求解其数值",
                separation.refreshedOwned());
        return new AppliedSeparation(
                new PipelineSnapshot(
                        separation.items(),
                        snapshot.recipes(),
                        separation.existingValues(),
                        snapshot.unsupportedRecipes(),
                        snapshot.invalidRecipes(),
                        snapshot.failedRecipes()),
                separation.ownedItems(),
                separation.refreshedOwned());
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
        return new RecipeSolver().solve(
                data.recipes(), context, EvaluatorRegistry.defaults(), solveSettings);
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
    private int addRecipeCandidates(SolveResult solveResult, List<Candidate> candidates,
                                    Set<ItemKey> variantItems) {
        int added = 0;
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            ItemKey item = entry.getKey();
            if (item.hasVariant() && !variantItems.contains(item)) {
                continue;
            }
            if (isTierExcluded(item)) {
                LOGGER.debug("物品 {} 被层级规则排除，跳过配方候选", item);
                continue;
            }
            CreativeItemProtection.Decision decision = creativeDecision(item);
            if (decision == null || !decision.priceable()) {
                LOGGER.debug("物品 {} 被创造物品保护排除，跳过配方候选", item);
                continue;
            }
            // 固定值必须在配方路径生效：配置注释承诺 specialValue 覆盖正常求解，不能因为
            // 求解器先产出了配方值就被 addFallbackCandidates 的“已有求解值”条件跳过。
            if (decision.specialValue() > 0L) {
                candidates.add(new Candidate(item, decision.specialValue(), PriceSource.SPECIAL_VALUE, null));
                added++;
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
     * <p>只处理既没有正值既有 EMC、也不在当前种子中的物品；0 值既有 EMC 等同于“没有值”。
     * 创造物品保护决定物品是否允许自动定价；固定值优先于兜底定价链。兜底链返回非正值、
     * 低于最小 EMC 或空结果时不产生候选。命中值同时写入 {@code seeds}，使下一轮配方求解
     * 能够把兜底价格传播到上游中间产物。</p>
     *
     * @param deferredItems 本次调用暂不处理的物品；循环内传入所有配方输出，最终兜底传入空集
     * @return 本轮新增的兜底候选数量
     */
    private int addFallbackCandidates(PipelineSnapshot snapshot, Map<ItemKey, Long> seeds,
                                      Map<ItemKey, Candidate> candidates,
                                      Set<ItemKey> deferredItems) {
        int added = 0;
        long minimumEmc = solveSettings.minimumEmc();
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            if (seeds.containsKey(item) || deferredItems.contains(item)) {
                continue;
            }
            Long existingValue = snapshot.existingValues().get(item);
            if (existingValue != null && existingValue > 0L) {
                continue;
            }
            Optional<Candidate> fallback = fallbackCandidate(itemSnapshot);
            if (fallback.isEmpty()) {
                continue;
            }
            Candidate candidate = fallback.get();
            if (candidate.value() <= 0L || candidate.value() < minimumEmc) {
                continue;
            }
            candidates.put(item, candidate);
            seeds.put(item, candidate.value());
            added++;
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
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            ItemKey item = entry.getKey();
            long solvedValue = entry.getValue();
            if (solvedValue <= 0L || deniedItems.contains(item)) {
                continue;
            }
            if (item.hasVariant() && !acceptedVariants.contains(item)) {
                continue;
            }
            if (isTierExcluded(item)) {
                LOGGER.debug("物品 {} 被层级规则排除，跳过配方候选", item);
                continue;
            }
            CreativeItemProtection.Decision decision = creativeDecision(item);
            if (decision == null || !decision.priceable()) {
                LOGGER.debug("物品 {} 被创造物品保护排除，跳过配方候选", item);
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
            Candidate existing = recipeCandidates.get(item);
            if (existing == null || mergedValue < existing.value()
                    || allowIncrease || source == PriceSource.SPECIAL_VALUE) {
                recipeCandidates.put(item,
                        new Candidate(item, mergedValue, source, recipeId));
            }
        }
        return changed;
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
        if (isTierExcluded(item)) {
            return true;
        }
        CreativeItemProtection.Decision decision = creativeDecision(item);
        return decision == null || !decision.priceable();
    }

    /**
     * 判断层级规则是否明确排除该物品。
     *
     * <p>{@link TierPricing#price} 返回 {@code Optional.of(0L)} 表示排除；判定异常时按排除处理。</p>
     */
    private boolean isTierExcluded(ItemKey item) {
        if (item == null) {
            return false;
        }
        try {
            Optional<Long> tierValue = tierPricing.price(item);
            return tierValue.isPresent() && tierValue.get() <= 0L;
        } catch (RuntimeException failure) {
            LOGGER.warn("层级排除规则判定失败，按排除处理：{}", item, failure);
            return true;
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
     * 写出诊断报告。报告写入失败只影响诊断文件，不影响本轮数值结果。
     */
    private void writeDiagnosticReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                       int unresolvedCount, int applied, int quarantined,
                                       int overflowResets, long durationMillis, int solverRounds,
                                       boolean appliedToStore, Map<String, Long> sourceCounts,
                                       int refreshedOwned, long auditDropped, int staleOwned) {
        if (!generateReportsEnabled || reportManager == null) {
            return;
        }
        try {
            reportManager.writeReport(buildReport(snapshot, solveResult, unresolvedCount,
                    applied, quarantined, overflowResets, durationMillis, solverRounds,
                    appliedToStore, sourceCounts, refreshedOwned, auditDropped, staleOwned));
        } catch (RuntimeException failure) {
            LOGGER.warn("写入诊断报告失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * 根据本轮运行数据构造诊断报告。
     *
     * <p>标准字段只统计实际应用的条目；求解器的失败配方、轮数与干跑标记等放入
     * {@code extraCounters}，便于后续解析器在不改变报告结构的情况下读取。未解析物品数
     * 使用多轮收敛后的最终计数，而不是某一轮的中间结果。</p>
     */
    private static DiagnosticReport buildReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                                int unresolvedCount, int applied, int quarantined,
                                                int overflowResets, long durationMillis,
                                                int solverRounds, boolean appliedToStore,
                                                Map<String, Long> sourceCounts, int refreshedOwned,
                                                long auditDropped, int staleOwned) {
        Map<String, Long> extraCounters = new LinkedHashMap<>();
        extraCounters.put("unsupportedRecipes", (long) snapshot.unsupportedRecipes());
        extraCounters.put("invalidRecipes", (long) snapshot.invalidRecipes());
        extraCounters.put("failedRecipes", (long) snapshot.failedRecipes());
        extraCounters.put("solverPasses", (long) solveResult.passesUsed());
        extraCounters.put("solverRounds", (long) solverRounds);
        extraCounters.put("recipesEvaluated", (long) solveResult.recipesEvaluated());
        extraCounters.put("solverConverged", solveResult.converged() ? 1L : 0L);
        extraCounters.put("specialValues",
                sourceCounts.getOrDefault(PriceSource.SPECIAL_VALUE.name(), 0L));
        extraCounters.put("overflowResets", (long) overflowResets);
        extraCounters.put("ownedRefreshed", (long) refreshedOwned);
        extraCounters.put("overflowAuditDropped", auditDropped);
        extraCounters.put("staleOwnedEntries", (long) staleOwned);
        extraCounters.put("dryRun", appliedToStore ? 0L : 1L);

        return new DiagnosticReport(
                snapshot.itemCount(),
                snapshot.existingValues().size(),
                sourceCounts.getOrDefault(PriceSource.RECIPE.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.TAG.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.RARITY.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.TIER.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.FALLBACK.name(), 0L),
                unresolvedCount,
                quarantined,
                snapshot.failedRecipes(),
                durationMillis,
                usedHeapBytes(),
                extraCounters);
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
     * <p>可见性为包内可见，便于同包测试直接验证“同一物品只保留配方来源”的合并规则。</p>
     *
     * @param item     物品身份
     * @param value    经过求解或兜底定价得到的原始数值，保证 {@code > 0}
     * @param source   数值来源
     * @param recipeId 产生该值的配方，兜底定价结果为 {@code null}
     */
    record Candidate(ItemKey item, long value, PriceSource source,
                     @Nullable ResourceLocation recipeId) {
    }
}
