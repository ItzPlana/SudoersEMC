package com.itzplana.sudoersemc.task;

import java.util.Objects;

/**
 * 由服务器 tick 驱动的单次扫描状态机。
 *
 * <p>一次扫描的生命周期为：请求扫描 → 分片采集快照 → 求解配方 → 应用结果 → 持久化 → 完成。
 * 采集阶段通过 {@link SnapshotCollector#begin()} 创建 {@link CollectSession}，每个 tick 只调用
 * 一次 {@link CollectSession#step(int)} 推进一个批次；会话自行维护游标并返回本批实际处理量与
 * 是否完成。批量由 {@link BatchProcessor} 根据上一批实际耗时与 {@link #tickBudgetNanos} 自适应
 * 决定，因此采集工作量按配置上限跨 tick 推进，而不是在一个 tick 内完成全量遍历。</p>
 *
 * <p><strong>生产接线（Phase 7）：</strong>{@code Sudoersemc} 在服务器启动时创建本类，
 * 注册 {@code ServerTickEvent} 驱动 {@link #tick()}，并注入 {@code ScanCollectionPipeline}
 * 提供的分片采集器、异步求解/应用任务、指纹提供器与异步持久化任务。采集使用真实的
 * {@code ItemScanner}/{@code RecipeScanner} 会话，按 {@code performance.itemsPerTick}
 * 与 {@code performance.adaptiveBatchSizing} 跨 tick 推进。</p>
 *
 * <p><strong>跨 tick 应用与持久化：</strong>生产接线使用 {@link #staged(BatchProcessor,
 * SnapshotCollector, StagedResultApplier, long, FingerprintSupplier, StagedSaveHandler)}，
 * 求解在 {@link ApplyTask#advanceSolve(long)} 中轮询工作线程结果，结果按
 * {@link ApplyTask#stage(int, long)} 分批写入，持久化由 {@link SaveTask#advance(long)} 轮询
 * 后台文件写入。每个 tick 只推进有界的工作量；{@code tickBudgetNanos} 作为
 * {@code System.nanoTime()} 截止时间传入任务/会话，实现方应尽快返回，但单元素处理本身无法
 * 被抢占，因此该预算是最佳努力而不是硬实时保证。</p>
 *
 * <p><strong>reload 代次：</strong>{@link FingerprintSupplier} 若声明
 * {@link FingerprintSupplier#tracksReloadGeneration()}，扫描只在开始时读取一次廉价代次，
 * 采集完成、求解完成与 APPLYING 的每个批次都只比较代次；因此 {@code /reload} 落在求解完成
 * 之后或 staging 中途时会立即取消应用并回滚本任务已写入的条目，不会把旧配方价格继续写入
 * EMC。实现仍可选择完整指纹模式，代价是每次校验都要在服务器线程重新计算指纹。</p>
 *
 * <p><strong>线程约定：</strong>快照采集（{@link SnapshotCollector} 与 {@link CollectSession}）、
 * 结果应用（{@link ResultApplier}/{@link StagedResultApplier} 的服务器线程部分）与指纹提供器
 * （{@link FingerprintSupplier}）必须在服务器线程执行，因为真实实现会读取 Minecraft 注册表、
 * 配方管理器或写入 EMC 后端。只有不接触游戏状态的纯计算才可以移到工作线程。
 * {@link #tick()}、{@link #requestScan()} 与 {@link #reset()} 同样只能由服务器线程调用，
 * 因此本类没有为这些方法加锁。回调内重入 {@link #tick()} 会被忽略；回调内调用
 * {@link #requestScan()} 返回 {@code false}；回调内调用 {@link #reset()} 会抛出
 * {@link IllegalStateException}。{@link #progress()} 通过 {@code volatile} 字段发布，其他线程
 * 可以安全读取最近一次完整进度；{@link #cancel()} 也可以从其他线程调用，实际取消会在下一次
 * tick 被观察到。</p>
 *
 * <p><strong>持久化：</strong>生产接线注入 {@link StagedSaveHandler}，由
 * {@code ScanCollectionPipeline} 在后台线程写 {@code scan_state.json} 与
 * {@code GeneratedEntryStore}；{@link #saveStep(long)} 只轮询完成状态。旧式
 * {@link SaveHandler} 仍在单元测试中使用，会同步执行。</p>
 *
 * <p>任何回调抛出的运行时异常都会被捕获并转换为 {@link ScanPhase#FAILED}，异常消息写入
 * 进度细节，绝不允许异常穿透 tick 循环破坏服务器主循环。</p>
 */
public final class ScanManager {

    /**
     * 分片采集会话工厂，必须在服务器线程执行。
     *
     * <p>每次扫描开始时调用一次 {@link #begin()} 创建新会话；会话持有本次采集的游标，
     * {@link ScanManager} 在每个 tick 调用一次 {@link CollectSession#step(int, long)} 推进有限
     * 工作量，直到会话报告完成。{@code begin()} 只应建立游标与计划总量，不得执行全量遍历。</p>
     */
    @FunctionalInterface
    public interface SnapshotCollector {

        /**
         * 开始一次分片采集。
         *
         * @return 新的采集会话，不能为 {@code null}
         */
        CollectSession begin();
    }

    /**
     * 由采集实现维护游标的分片采集会话，所有方法必须在服务器线程调用。
     *
     * <p>实现方必须保证 {@link #step(int)} 最多处理 {@code maxItems} 个工作单元；单次调用
     * 允许因为阶段切换而不处理任何元素，但不得在一次调用内完成全量遍历。
     * {@link #itemCount()} 与 {@link #recipeCount()} 是计划总量，用于进度显示；
     * {@link #processedItems()} 与 {@link #processedRecipes()} 必须随 {@code step} 单调递增，
     * 并在全部工作完成时返回 {@code true}。</p>
     */
    public interface CollectSession {

        /**
         * @return 计划检查的物品数量，未知时为 0
         */
        int itemCount();

        /**
         * @return 计划处理的配方数量，未知时为 0
         */
        int recipeCount();

        /**
         * @return 已处理的物品数量
         */
        int processedItems();

        /**
         * @return 已处理的配方数量
         */
        int processedRecipes();

        /**
         * @return 是否已经完成（包括取消后的部分完成）
         */
        boolean isDone();

        /**
         * 推进最多 {@code maxItems} 个工作单元。
         *
         * @param maxItems 本次允许处理的最大单元数；必须大于等于 1
         * @return 本批实际处理量与完成标志
         */
        StepResult step(int maxItems);

        /**
         * 带截止时间的推进入口。
         *
         * <p>默认实现忽略截止时间并委托给 {@link #step(int)}，保持旧会话兼容；真实扫描器会
         * 在每个元素之间检查 {@code deadlineNanos}，在预算耗尽后提前返回。</p>
         *
         * @param maxItems      本次允许处理的最大单元数；必须大于等于 1
         * @param deadlineNanos {@link System#nanoTime()} 截止时间；{@link Long#MAX_VALUE} 表示不限
         * @return 本批实际处理量与完成标志
         */
        default StepResult step(int maxItems, long deadlineNanos) {
            return step(maxItems);
        }

        /**
         * 返回本次采集的最终快照。只在 {@link #isDone()} 为 {@code true} 时调用。
         *
         * @return 非空快照数据
         */
        SnapshotData finish();
    }

    /**
     * 单个采集批次的结果。
     *
     * @param actualProcessed 本批实际处理的工作单元数，必须非负且不超过请求的 {@code maxItems}
     * @param done            会话是否在本次调用后完成
     */
    public record StepResult(int actualProcessed, boolean done) {

        public StepResult {
            if (actualProcessed < 0) {
                throw new IllegalArgumentException(
                        "actualProcessed 必须 >= 0，实际为 " + actualProcessed);
            }
        }
    }

    /**
     * 当前扫描输入的指纹与 reload 代次提供器，必须在服务器线程执行。
     *
     * <p>未提供该回调时跳过一致性校验，仅用于不接触游戏状态的单元测试或过渡接线。</p>
     *
     * <p><strong>两种校验模式：</strong>实现若无法廉价检测 reload，{@link ScanManager} 会在
     * 采集开始、采集完成与求解完成时各取一次完整指纹（{@link #current()}）；采集期间变化会
     * 直接让扫描失败，采集后到应用前发生变化（例如 {@code /reload} 或注册表变动）会拒绝应用
     * 过期结果并把扫描转为 {@link ScanPhase#FAILED}。APPLYING 期间只在每个 staging 批次
     * <em>开始前</em>校验一次，并在最后一批写完后、{@link ApplyTask#commit()} 之前再校验一次
     * 提交门；不会对同一批次前后各计算一次完整指纹。完整指纹模式每批次仍是一次全量开销，
     * 生产接线应覆写 {@link #tracksReloadGeneration()} 使用廉价代次。</p>
     *
     * <p>实现若能通过 Forge 的 {@code AddReloadListenerEvent}/{@code TagsUpdatedEvent} 等
     * 廉价来源维护单调递增的 reload 代次，应覆写 {@link #tracksReloadGeneration()} 与
     * {@link #reloadGeneration()}。此时 {@link ScanManager} 只在扫描开始时读取一次代次，
     * 采集完成、求解完成以及 APPLYING 的每个批次都用代次比较，不再在服务器线程重复计算
     * 全量内容指纹。完整内容指纹由采集结束时的快照提供，供持久化与下次启动的增量判断使用。</p>
     */
    @FunctionalInterface
    public interface FingerprintSupplier {

        /**
         * @return 当前输入的完整指纹，不能为 {@code null}
         */
        ScanFingerprint current();

        /**
         * 廉价、单调递增的 reload 代次。每次 datapack reload（开始与完成）都应递增；
         * 只在 {@link #tracksReloadGeneration()} 返回 {@code true} 时由扫描器使用。
         *
         * @return 当前代次
         */
        default long reloadGeneration() {
            return 0L;
        }

        /**
         * @return {@code true} 表示 {@link #reloadGeneration()} 能廉价反映 reload 变化，
         *         扫描器可跳过采集完成/求解完成/APPLYING 期间的全量指纹计算
         */
        default boolean tracksReloadGeneration() {
            return false;
        }
    }

    /**
     * 旧式同步应用阶段回调，必须在服务器线程执行。保留给单元测试与过渡接线。
     */
    @FunctionalInterface
    public interface ResultApplier {

        /**
         * 把求解结果写入实际后端。
         *
         * @param data 采集阶段产生的快照数据
         */
        void apply(SnapshotData data);
    }

    /**
     * 跨 tick 分片应用任务。
     *
     * <p>{@link #advanceSolve(long)} 只轮询工作线程求解结果，不得阻塞服务器线程；
     * {@link #stage(int, long)} 每个 tick 最多写入 {@code maxItems} 个生成值，并在
     * {@code deadlineNanos} 到期后尽快返回。两者都由服务器线程调用。</p>
     */
    public interface ApplyTask {

        /**
         * @return 工作线程求解是否已经完成；完成后 {@link #stage(int, long)} 才可调用
         */
        boolean isSolveComplete();

        /**
         * 轮询求解结果。不得阻塞等待工作线程。
         *
         * @param deadlineNanos 本 tick 的 {@link System#nanoTime()} 截止时间
         */
        void advanceSolve(long deadlineNanos);

        /**
         * 分批写入求解结果。
         *
         * <p>只写入，不提交。实现必须在全部条目写入后通过 {@link #isStagingComplete()} 报告
         * 完成，但保持 {@link #isDone()} 为 {@code false}；{@link #commit()} 之前调用
         * {@link #cancel()} 必须能回滚本任务已经写入的条目。</p>
         *
         * @param maxItems      本 tick 最多写入的条目数
         * @param deadlineNanos 本 tick 的 {@link System#nanoTime()} 截止时间
         * @return 本 tick 尝试处理的条目数
         */
        int stage(int maxItems, long deadlineNanos);

        /**
         * @return 全部条目是否已经写入 staging，但尚未提交
         */
        default boolean isStagingComplete() {
            return isDone();
        }

        /**
         * 提交已经全部 staging 的结果。{@link ScanManager} 只会在最后一批的后置输入校验通过后
         * 调用；实现应在此写入“已应用”指纹、统计并执行应用完成回调。提交成功后
         * {@link #isDone()} 应返回 {@code true}，之后的 {@link #cancel()} 不再回滚。
         */
        default void commit() {
        }

        /**
         * @return 是否全部写入并成功提交
         */
        boolean isDone();

        /**
         * @return 求解器生成的条目总数
         */
        int solvedCount();

        /**
         * @return 求解器仍未解析的物品数
         */
        int unresolvedCount();

        /**
         * 请求取消。实现应在尚未成功 {@link #commit()} 时回滚本任务已写入的条目；已经提交的
         * 结果不再回滚。
         */
        default void cancel() {
        }

        /**
         * 创建一个立即完成、不写入任何条目的任务，供测试与旧式接线使用。
         *
         * @param solvedCount     生成的条目数，用于进度显示
         * @param unresolvedCount 未解析的物品数，用于进度显示
         * @return 已完成任务
         */
        static ApplyTask completed(int solvedCount, int unresolvedCount) {
            return new ApplyTask() {
                @Override
                public boolean isSolveComplete() {
                    return true;
                }

                @Override
                public void advanceSolve(long deadlineNanos) {
                }

                @Override
                public int stage(int maxItems, long deadlineNanos) {
                    return 0;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public int solvedCount() {
                    return solvedCount;
                }

                @Override
                public int unresolvedCount() {
                    return unresolvedCount;
                }
            };
        }
    }

    /**
     * 跨 tick 异步应用入口，必须在服务器线程调用。
     */
    @FunctionalInterface
    public interface StagedResultApplier {

        /**
         * 取走采集阶段暂存的快照并启动求解。实现应把纯计算提交给工作线程，并立即返回任务。
         *
         * @param data 采集阶段产生的计数快照
         * @return 非空应用任务
         */
        ApplyTask beginApply(SnapshotData data);
    }

    /**
     * 旧式同步持久化阶段回调，必须在服务器线程执行。保留给单元测试与过渡接线。
     */
    @FunctionalInterface
    public interface SaveHandler {

        /**
         * 落盘本阶段产生的扫描状态与生成值。
         */
        void save();
    }

    /**
     * 跨 tick 异步持久化任务。
     */
    public interface SaveTask {

        /**
         * 轮询后台写入。不得阻塞等待磁盘。
         *
         * <p>后台写入失败时必须抛出 {@link RuntimeException}；{@link ScanManager} 会捕获并把
         * 扫描转为 {@link ScanPhase#FAILED}，不得在持久化失败后报告 {@code COMPLETED}。</p>
         *
         * @param deadlineNanos 本 tick 的 {@link System#nanoTime()} 截止时间
         * @return 写入是否已经完成
         */
        boolean advance(long deadlineNanos);

        /**
         * 请求取消。已经开始的原子文件写入无法安全中断，但调用方会停止等待。
         */
        default void cancel() {
        }

        /**
         * @return 立即完成的空任务
         */
        static SaveTask completed() {
            return deadlineNanos -> true;
        }
    }

    /**
     * 跨 tick 异步持久化入口，必须在服务器线程调用。
     */
    @FunctionalInterface
    public interface StagedSaveHandler {

        /**
         * 提交后台写入并返回轮询任务。
         *
         * @return 非空持久化任务
         */
        SaveTask beginSave();
    }

    /**
     * 回调之间传递的最小数据载体。
     *
     * <p>当前阶段只携带标签、物品数量与配方数量，使 {@link ScanManager} 保持对 Minecraft
     * 的零依赖。Phase 12 接入真实流水线（{@code ScanPipeline}）时，可以通过泛型化或子类
     * 承载更丰富的快照内容，而无需让状态机本身引用游戏类型。</p>
     *
     * @param label       快照来源或批次的中文标签
     * @param itemCount   采集到的物品数量，不得为负
     * @param recipeCount 待求解的配方数量，不得为负
     */
    public record SnapshotData(String label, int itemCount, int recipeCount) {

        public SnapshotData {
            label = label == null ? "" : label;
            if (itemCount < 0) {
                throw new IllegalArgumentException("itemCount 必须 >= 0，实际为 " + itemCount);
            }
            if (recipeCount < 0) {
                throw new IllegalArgumentException("recipeCount 必须 >= 0，实际为 " + recipeCount);
            }
        }
    }

    private final BatchProcessor batchProcessor;
    private final SnapshotCollector collector;
    private final ResultApplier applier;
    private final StagedResultApplier stagedApplier;
    private final long tickBudgetNanos;
    private final FingerprintSupplier fingerprintSupplier;
    private final SaveHandler saveHandler;
    private final StagedSaveHandler stagedSaveHandler;
    private final boolean reloadTracking;

    private volatile ScanProgress progress = ScanProgress.idle();
    private volatile boolean cancelRequested;

    private SnapshotData snapshot;
    private CollectSession collectSession;
    private ScanFingerprint collectFingerprintBefore;
    private ScanFingerprint collectedFingerprint;
    private long collectReloadGeneration;
    private ApplyTask applyTask;
    private SaveTask saveTask;
    private long lastBatchNanos;
    private boolean ticking;

    /**
     * 创建一个不做快照一致性校验的调度器。
     *
     * @deprecated 该构造器把 {@link FingerprintSupplier} 固定为 {@code null}，会静默跳过
     *         采集前后与应用前的指纹校验，只适用于不接触游戏状态的单元测试或过渡接线。
     *         生产接线必须使用 {@link #staged(BatchProcessor, SnapshotCollector,
     *         StagedResultApplier, long, FingerprintSupplier, StagedSaveHandler)} 或带
     *         {@link FingerprintSupplier} 的构造器，否则 {@code /reload} 后可能把基于旧配方的
     *         结果写入 EMC。计划在后续阶段移除本构造器。
     *
     * @param batchProcessor  批量决策器，不能为 {@code null}
     * @param collector       分片采集会话工厂，不能为 {@code null}
     * @param applier         结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos 每 tick 的时间预算（纳秒），不得为负；作为最佳努力截止时间传入
     *                        采集/应用任务，但单元素处理本身无法被抢占
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos) {
        this(batchProcessor, collector, applier, null, tickBudgetNanos, null, null, null);
    }

    /**
     * 创建带快照一致性校验的调度器，不执行持久化回调。
     *
     * @param batchProcessor      批量决策器，不能为 {@code null}
     * @param collector           分片采集会话工厂，不能为 {@code null}
     * @param applier             结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos     每 tick 的时间预算（纳秒），不得为负
     * @param fingerprintSupplier 输入指纹提供器；生产接线必须提供非 {@code null} 实现，
     *                            {@code null} 仅用于不接触游戏状态的单元测试并会跳过全部校验
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos,
                       FingerprintSupplier fingerprintSupplier) {
        this(batchProcessor, collector, applier, null, tickBudgetNanos, fingerprintSupplier, null, null);
    }

    /**
     * 创建带快照一致性校验与同步持久化回调的调度器。
     *
     * @param batchProcessor      批量决策器，不能为 {@code null}
     * @param collector           分片采集会话工厂，不能为 {@code null}
     * @param applier             结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos     每 tick 的时间预算（纳秒），不得为负
     * @param fingerprintSupplier 输入指纹提供器；生产接线必须提供非 {@code null} 实现
     * @param saveHandler         持久化回调；{@code null} 仅用于不落盘的单元测试
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos,
                       FingerprintSupplier fingerprintSupplier,
                       SaveHandler saveHandler) {
        this(batchProcessor, collector, applier, null, tickBudgetNanos, fingerprintSupplier, saveHandler, null);
    }

    private ScanManager(BatchProcessor batchProcessor,
                        SnapshotCollector collector,
                        ResultApplier applier,
                        StagedResultApplier stagedApplier,
                        long tickBudgetNanos,
                        FingerprintSupplier fingerprintSupplier,
                        SaveHandler saveHandler,
                        StagedSaveHandler stagedSaveHandler) {
        this.batchProcessor = Objects.requireNonNull(batchProcessor, "batchProcessor");
        this.collector = Objects.requireNonNull(collector, "collector");
        if (applier == null && stagedApplier == null) {
            throw new IllegalArgumentException("必须提供同步或分片应用器");
        }
        if (tickBudgetNanos < 0L) {
            throw new IllegalArgumentException("tickBudgetNanos 必须 >= 0，实际为 " + tickBudgetNanos);
        }
        this.applier = applier;
        this.stagedApplier = stagedApplier;
        this.tickBudgetNanos = tickBudgetNanos;
        this.fingerprintSupplier = fingerprintSupplier;
        this.saveHandler = saveHandler;
        this.stagedSaveHandler = stagedSaveHandler;
        this.reloadTracking = fingerprintSupplier != null
                && fingerprintSupplier.tracksReloadGeneration();
    }

    /**
     * 创建生产用跨 tick 调度器：求解/应用/持久化都由任务分片推进。
     *
     * @param batchProcessor      批量决策器，不能为 {@code null}
     * @param collector           分片采集会话工厂，不能为 {@code null}
     * @param applier             异步应用入口，不能为 {@code null}
     * @param tickBudgetNanos     每 tick 的时间预算（纳秒），不得为负
     * @param fingerprintSupplier 输入指纹提供器；生产接线必须提供非 {@code null} 实现
     * @param saveHandler         异步持久化入口；{@code null} 表示不落盘（仅测试）
     * @return 新的调度器
     */
    public static ScanManager staged(BatchProcessor batchProcessor,
                                     SnapshotCollector collector,
                                     StagedResultApplier applier,
                                     long tickBudgetNanos,
                                     FingerprintSupplier fingerprintSupplier,
                                     StagedSaveHandler saveHandler) {
        return new ScanManager(batchProcessor, collector, null,
                Objects.requireNonNull(applier, "applier"),
                tickBudgetNanos, fingerprintSupplier, null, saveHandler);
    }

    /**
     * 请求开始一次新扫描。
     *
     * <p>已有扫描处于活动状态时返回 {@code false}，因此重复请求不会创建第二个扫描。
     * 成功请求会清空上一次的快照、采集会话、后台任务、指纹与取消标志，并把进度重置为
     * {@link ScanPhase#COLLECTING_SNAPSHOT}。扫描完成、取消或失败后可以再次请求。</p>
     *
     * <p>本方法只能在服务器线程调用。若在 {@link #tick()} 的回调内调用，将直接返回
     * {@code false}，防止回调重入启动第二个扫描。</p>
     *
     * @return 成功开始返回 {@code true}；已有扫描在运行或处于 tick 回调内返回 {@code false}
     */
    public boolean requestScan() {
        if (ticking || isRunning()) {
            return false;
        }
        cancelTasks();
        cancelRequested = false;
        snapshot = null;
        collectSession = null;
        collectFingerprintBefore = null;
        collectedFingerprint = null;
        collectReloadGeneration = 0L;
        lastBatchNanos = 0L;
        long now = System.currentTimeMillis();
        progress = new ScanProgress(ScanPhase.COLLECTING_SNAPSHOT, 0, 0, now, now, "等待采集快照");
        return true;
    }

    /**
     * 请求取消当前扫描。
     *
     * <p>本方法只设置标志，不直接修改阶段；正在运行的扫描会在下一次 {@link #tick()} 中
     * 观察到并转入 {@link ScanPhase#CANCELLED}，同时取消尚未完成的后台求解/持久化任务。
     * 已经开始的原子文件写入无法安全中断，但调用方会停止等待。空闲时设置该标志没有副作用，
     * 下一次 {@link #requestScan()} 会将其清除。</p>
     */
    public void cancel() {
        cancelRequested = true;
    }

    /**
     * @return 最近一次发布的进度快照，可安全地从其他线程读取
     */
    public ScanProgress progress() {
        return progress;
    }

    /**
     * @return 当前阶段是否属于活动扫描
     */
    public boolean isRunning() {
        return progress.phase().isActive();
    }

    /**
     * @return 是否已经请求取消但尚未被 tick 观察
     */
    public boolean isCancelRequested() {
        return cancelRequested;
    }

    /**
     * 推进一次扫描状态机。
     *
     * <p>非活动状态下直接返回，不调用任何回调，也不消耗批量预算。取消标志优先于阶段推进。
     * 采集阶段在首个 tick 创建 {@link CollectSession}，之后每个 tick 只调用一次
     * {@link CollectSession#step(int, long)}，因此采集必然按批次跨 tick 推进；会话返回完成标志后
     * 才进入求解阶段。求解阶段只轮询工作线程，应用阶段按批次 staging，持久化阶段只轮询后台
     * 文件写入。</p>
     *
     * <p>本方法只能由服务器线程调用。回调内重入调用会被直接忽略，防止递归采集。批量由
     * {@link BatchProcessor#nextBatchSize(long, long)} 根据上一批实际耗时与
     * {@code tickBudgetNanos} 计算；{@code tickBudgetNanos} 会换算成
     * {@link System#nanoTime()} 截止时间传给采集/应用任务，实现方在每个元素之间检查并提前
     * 返回。单元素处理本身无法被抢占，因此该预算是最佳努力而非硬实时保证。实际处理量会回写给
     * {@link BatchProcessor#recordBatch(int, long)}，不会用建议批量冒充实际工作量；求解空转
     * 与应用/持久化阶段不会写入自适应样本。</p>
     *
     * <p>回调抛出的 {@link RuntimeException} 会被捕获并转为 {@link ScanPhase#FAILED}，
     * 细节字段保存异常消息（无消息时退化为异常类名）。本方法不会向外传播这些异常。</p>
     */
    public void tick() {
        if (ticking) {
            return;
        }
        ScanProgress current = progress;
        if (!current.isRunning()) {
            return;
        }
        ticking = true;
        try {
            tickInternal(current);
        } finally {
            ticking = false;
        }
    }

    private void tickInternal(ScanProgress current) {
        if (cancelRequested) {
            cancelRequested = false;
            cancelTasks();
            progress = current.advance(ScanPhase.CANCELLED, current.processed(), current.total(),
                    "扫描已取消");
            return;
        }

        ScanPhase phase = current.phase();
        int batch = batchProcessor.nextBatchSize(lastBatchNanos, tickBudgetNanos);
        long startedNanos = System.nanoTime();
        long deadlineNanos = deadlineFrom(startedNanos);
        int actualProcessed = 0;
        try {
            switch (phase) {
                case COLLECTING_SNAPSHOT -> actualProcessed = collectStep(batch, deadlineNanos);
                case SOLVING -> actualProcessed = solveStep(batch, deadlineNanos);
                case APPLYING -> actualProcessed = applyStep(batch, deadlineNanos);
                case SAVING -> saveStep(deadlineNanos);
                default -> {
                    // 防御性分支：isRunning() 已经排除了所有终止阶段。
                }
            }
        } catch (RuntimeException e) {
            cancelTasks();
            ScanProgress failed = progress;
            progress = failed.advance(ScanPhase.FAILED, failed.processed(), failed.total(),
                    failureDetail(e));
        } finally {
            // 只有采集真正处理了元素才记入自适应样本；求解空转、应用与持久化不得污染样本。
            if (actualProcessed > 0 && phase == ScanPhase.COLLECTING_SNAPSHOT) {
                long elapsedNanos = System.nanoTime() - startedNanos;
                lastBatchNanos = elapsedNanos;
                batchProcessor.recordBatch(actualProcessed, elapsedNanos);
            }
        }
    }

    /**
     * 把状态机恢复到空闲，并清除取消标志、快照、采集会话、后台任务、指纹与计时样本。
     *
     * <p>可在运行中调用；后续 {@link #tick()} 将不再推进旧扫描。批量决策器的自适应基准
     * 不会被重置，以便下一次扫描沿用已经学到的批量。</p>
     *
     * <p>本方法只能在服务器线程调用。若在 {@link #tick()} 的回调内调用，会抛出
     * {@link IllegalStateException}，避免在回调执行过程中破坏正在推进的状态。</p>
     *
     * @throws IllegalStateException 当在 tick 回调内调用时
     */
    public void reset() {
        if (ticking) {
            throw new IllegalStateException("不能在 tick 回调中重置 ScanManager");
        }
        cancelTasks();
        cancelRequested = false;
        snapshot = null;
        collectSession = null;
        collectFingerprintBefore = null;
        collectedFingerprint = null;
        collectReloadGeneration = 0L;
        lastBatchNanos = 0L;
        progress = ScanProgress.idle();
    }

    /**
     * 采集阶段：首个 tick 创建分片会话，之后每个 tick 只推进一个批次。
     *
     * <p>会话在 {@link CollectSession#step(int, long)} 中按 {@code batch} 上限处理有限工作量，
     * 并在每个元素之间检查截止时间；{@link ScanManager} 不会在一次 tick 内循环调用到完成，
     * 因此多物品扫描必然跨越多个 tick。支持廉价 reload 代次的提供器只在扫描开始时读取一次
     * 代次，采集完成时比较代次；其余实现仍按旧行为在采集开始/完成时各取一次完整指纹并比较。
     * 两次不一致说明采集期间输入已经变化，本次快照不可信，直接让扫描失败。</p>
     *
     * @return 本 tick 实际处理的元素数量，用于自适应批量记账
     */
    private int collectStep(int batch, long deadlineNanos) {
        if (collectSession == null) {
            if (reloadTracking) {
                // 完整内容指纹由采集结束时的 CollectedSnapshot 提供；这里只记录廉价代次。
                collectReloadGeneration = fingerprintSupplier.reloadGeneration();
                collectFingerprintBefore = null;
            } else {
                collectFingerprintBefore = currentFingerprint();
            }
            CollectSession session = collector.begin();
            if (session == null) {
                throw new IllegalStateException("快照采集器返回 null 会话");
            }
            collectSession = session;
            publishCollectProgress(batch);
        }

        if (collectSession.isDone()) {
            finishCollection();
            return 0;
        }

        StepResult result = collectSession.step(batch, deadlineNanos);
        if (result == null) {
            throw new IllegalStateException("快照采集会话 step 返回 null");
        }
        if (result.actualProcessed() > batch) {
            throw new IllegalStateException(
                    "快照采集会话超出单批预算：请求 " + batch
                            + "，实际 " + result.actualProcessed());
        }
        publishCollectProgress(batch);
        if (result.done() || collectSession.isDone()) {
            finishCollection();
        }
        return result.actualProcessed();
    }

    /**
     * 发布采集进度：物品未完成时显示物品进度，之后显示配方进度。
     */
    private void publishCollectProgress(int batch) {
        CollectSession session = collectSession;
        if (session == null) {
            return;
        }
        int itemTotal = session.itemCount();
        int recipeTotal = session.recipeCount();
        int processedItems = session.processedItems();
        int processedRecipes = session.processedRecipes();
        if (processedItems < itemTotal) {
            progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT, processedItems, itemTotal,
                    "采集物品 " + processedItems + "/" + itemTotal + "（批 " + batch + "）");
        } else if (processedRecipes < recipeTotal) {
            progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT, processedRecipes, recipeTotal,
                    "采集配方 " + processedRecipes + "/" + recipeTotal + "（批 " + batch + "）");
        } else if (itemTotal == 0 && recipeTotal == 0) {
            progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT, 0, 0,
                    "汇总快照（批 " + batch + "）");
        } else {
            progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT, 1, 1,
                    "汇总快照（批 " + batch + "）");
        }
    }

    /**
     * 结束采集：校验采集期间的指纹一致性，取得最终快照并进入求解阶段。
     */
    private void finishCollection() {
        CollectSession session = collectSession;
        if (session == null) {
            throw new IllegalStateException("采集阶段缺少会话");
        }
        if (reloadTracking) {
            verifyReloadGenerationUnchanged("快照采集期间扫描输入发生变化");
        } else {
            ScanFingerprint after = currentFingerprint();
            if (collectFingerprintBefore != null
                    && !collectFingerprintBefore.equalsFingerprint(after)) {
                collectSession = null;
                throw new IllegalStateException("快照采集期间扫描输入发生变化，已放弃本次结果");
            }
            collectedFingerprint = after;
        }
        SnapshotData collected = session.finish();
        if (collected == null) {
            collectSession = null;
            throw new IllegalStateException("快照采集器返回 null 快照");
        }
        snapshot = collected;
        collectSession = null;
        collectFingerprintBefore = null;
        progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT, collected.itemCount(),
                collected.itemCount(), "已采集 " + collected.itemCount() + " 个物品快照");
        beginSolving();
    }

    /**
     * 物品/配方快照采集完成后转入求解阶段。
     *
     * <p>生产接线会在这里调用 {@link StagedResultApplier#beginApply(SnapshotData)} 启动后台
     * 求解；状态机随后只轮询结果，不会在服务器线程执行 {@code RecipeSolver.solve()}。</p>
     */
    private void beginSolving() {
        SnapshotData data = snapshot;
        int total = data == null ? 0 : data.recipeCount();
        // 指纹在完成采集时已经与采集前比较过；应用前还会在 solveStep/applyStep 再比较一次，
        // 此处不再重复触发完整指纹计算。
        if (stagedApplier != null) {
            applyTask = stagedApplier.beginApply(data);
            if (applyTask == null) {
                throw new IllegalStateException("分片应用器返回 null 任务");
            }
        }
        progress = progress.advance(ScanPhase.SOLVING, 0, total,
                stagedApplier == null ? "准备应用结果" : "求解配方（工作线程）");
    }

    /**
     * 求解阶段：生产接线只轮询后台求解结果，空转不产生自适应样本。
     *
     * <p>{@link #solveStep(int, long)} 返回 0，因为本阶段没有在服务器线程处理任何元素；
     * 真实求解由 {@code ScanCollectionPipeline} 的工作线程完成。求解完成后再次校验指纹，
     * 再进入分片应用阶段。</p>
     *
     * @return 始终为 0；求解空转不得计入自适应批量样本
     */
    private int solveStep(int batch, long deadlineNanos) {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("求解阶段缺少快照数据");
        }

        if (stagedApplier == null) {
            progress = progress.advance(ScanPhase.APPLYING, 0, 0, "开始应用结果");
            return 0;
        }

        ApplyTask task = applyTask;
        if (task == null) {
            throw new IllegalStateException("求解阶段缺少分片应用任务");
        }
        if (!task.isSolveComplete()) {
            task.advanceSolve(deadlineNanos);
            if (!task.isSolveComplete()) {
                progress = progress.advance(ScanPhase.SOLVING, 0, data.recipeCount(),
                        "求解配方（工作线程）");
                return 0;
            }
        }

        verifyFingerprintBeforeApply();
        int solved = Math.max(0, task.solvedCount());
        progress = progress.advance(ScanPhase.APPLYING, 0, solved,
                "开始应用 " + solved + " 个生成值");
        return 0;
    }

    /**
     * 应用阶段：先校验快照仍然对应当前输入，再分片写入结果。
     *
     * <p>生产接线每个 tick 最多写入一批，写入过程中检查截止时间；全部写入完成后进入持久化。
     * 分片路径在每个 staging 批次开始前校验一次输入，并在最后一批写完、提交之前再校验一次：
     * 支持廉价 reload 代次的提供器只比较代次，因此 APPLYING 跨越多个 tick 或 {@code /reload}
     * 落在最后一批内部时，本任务不会被 {@link ApplyTask#commit()}，{@link ApplyTask#cancel()}
     * 会回滚本任务已写入的条目。旧式 {@link ResultApplier} 仍然同步执行一次，以保持单元测试
     * 兼容。</p>
     *
     * @return 本 tick 尝试写入的条目数；应用阶段不写入自适应样本
     */
    private int applyStep(int batch, long deadlineNanos) {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("应用阶段缺少快照数据");
        }

        if (stagedApplier == null) {
            verifyFingerprintBeforeApply();
            applier.apply(data);
            progress = progress.advance(ScanPhase.SAVING, 0, 0, "结果已应用，开始持久化");
            return 0;
        }

        ApplyTask task = applyTask;
        if (task == null) {
            throw new IllegalStateException("应用阶段缺少分片应用任务");
        }
        int staged = 0;
        if (!task.isDone()) {
            // 每个 staging 批次只在开始前校验一次；非 reloadTracking 实现因此不再对同一批次
            // 前后各计算一次完整指纹。
            verifyFingerprintBeforeApply();
            staged = Math.max(0, task.stage(batch, deadlineNanos));
            if (task.isStagingComplete()) {
                // 最后一批写完后、提交前再校验一次。reload 落在本批 staging 内部时这里抛错，
                // tickInternal -> cancelTasks -> task.cancel() 会回滚尚未 commit 的条目。
                verifyFingerprintBeforeApply();
                task.commit();
            }
        }
        ScanProgress current = progress;
        if (staged > 0) {
            int processed = Math.min(current.total(),
                    current.processed() + staged);
            progress = current.advance(ScanPhase.APPLYING, processed, current.total(),
                    "应用结果 " + processed + "/" + current.total());
        }
        if (task.isDone()) {
            int solved = Math.max(0, task.solvedCount());
            int unresolved = Math.max(0, task.unresolvedCount());
            applyTask = null;
            progress = progress.advance(ScanPhase.SAVING, 0, 0,
                    "已应用 " + solved + " 个生成值（未解析 " + unresolved + "），开始持久化");
        }
        return staged;
    }

    /**
     * 持久化阶段：轮询后台写入任务，完成后发布完成状态。
     *
     * <p>生产实现由 {@code ScanCollectionPipeline} 在后台线程写 {@code scan_state.json} 与
     * {@code GeneratedEntryStore}；{@link SaveTask#advance(long)} 只检查完成状态，不阻塞磁盘。
     * 旧式 {@link SaveHandler} 仍同步执行一次。</p>
     */
    private void saveStep(long deadlineNanos) {
        if (stagedSaveHandler != null) {
            if (saveTask == null) {
                saveTask = stagedSaveHandler.beginSave();
                if (saveTask == null) {
                    throw new IllegalStateException("持久化处理器返回 null 任务");
                }
            }
            if (!saveTask.advance(deadlineNanos)) {
                progress = progress.advance(ScanPhase.SAVING, 0, 0, "持久化中（后台写入）");
                return;
            }
            saveTask = null;
            progress = progress.advance(ScanPhase.COMPLETED, 0, 0, "扫描已完成");
            return;
        }

        if (saveHandler != null) {
            saveHandler.save();
        }
        progress = progress.advance(ScanPhase.COMPLETED, 0, 0, "扫描已完成");
    }

    /**
     * 应用前重新校验输入，拒绝使用采集后已经过期的快照。
     *
     * <p>支持廉价 reload 代次的提供器只比较代次，避免在服务器线程重复计算全量内容指纹；
     * 其余实现保持完整指纹比较。</p>
     */
    private void verifyFingerprintBeforeApply() {
        if (fingerprintSupplier == null) {
            return;
        }
        if (reloadTracking) {
            verifyReloadGenerationUnchanged("扫描输入在采集后发生变化");
            return;
        }
        ScanFingerprint current = currentFingerprint();
        if (collectedFingerprint == null || !collectedFingerprint.equalsFingerprint(current)) {
            throw new IllegalStateException(
                    "扫描输入在采集后发生变化（可能发生了 /reload 或注册表变更），"
                            + "已拒绝应用过期结果；请重新扫描");
        }
    }

    /**
     * 比较扫描开始时的 reload 代次与当前值；变化说明期间发生了 {@code /reload}
     * 或等价的注册表/数据包重载。
     *
     * @param message 失败信息前缀，便于区分采集期间与应用期间
     */
    private void verifyReloadGenerationUnchanged(String message) {
        long current = fingerprintSupplier.reloadGeneration();
        if (current != collectReloadGeneration) {
            throw new IllegalStateException(
                    message + "（可能发生了 /reload 或注册表变更），"
                            + "已放弃本次扫描结果；请重新扫描");
        }
    }

    /**
     * @return 当前指纹；未配置提供器时返回 {@code null}
     * @throws IllegalStateException 配置了提供器但它返回 {@code null}
     */
    private ScanFingerprint currentFingerprint() {
        if (fingerprintSupplier == null) {
            return null;
        }
        ScanFingerprint fingerprint = fingerprintSupplier.current();
        if (fingerprint == null) {
            throw new IllegalStateException("指纹提供器返回 null");
        }
        return fingerprint;
    }

    /**
     * 取消尚未完成的后台任务。可以在失败/取消/重置路径安全重复调用。
     */
    private void cancelTasks() {
        ApplyTask currentApply = applyTask;
        applyTask = null;
        if (currentApply != null) {
            try {
                currentApply.cancel();
            } catch (RuntimeException ignored) {
                // 取消失败不能阻止状态机进入终止状态。
            }
        }
        SaveTask currentSave = saveTask;
        saveTask = null;
        if (currentSave != null) {
            try {
                currentSave.cancel();
            } catch (RuntimeException ignored) {
                // 同上。
            }
        }
    }

    /**
     * 把每 tick 预算换算成绝对截止时间，饱和处理溢出。
     */
    private long deadlineFrom(long startedNanos) {
        if (tickBudgetNanos <= 0L) {
            return Long.MAX_VALUE;
        }
        long deadline = startedNanos + tickBudgetNanos;
        return deadline < startedNanos ? Long.MAX_VALUE : deadline;
    }

    /**
     * 把异常转换为适合展示的失败细节，保证不会因为消息为空而丢失错误类型。
     */
    private static String failureDetail(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
