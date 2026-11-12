package com.itzplana.sudoersemc.task;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由服务器 tick 驱动的单次扫描状态机。
 *
 * <p>一次扫描的生命周期为：请求扫描 → 分片采集快照 → 求解配方 → 应用结果 → 持久化 → 完成。
 * 采集阶段通过 {@link SnapshotCollector#begin()} 创建 {@link CollectSession}，每个 tick 只调用
 * 一次 {@link CollectSession#step(int)} 推进一个批次；会话自行维护游标并返回本批实际处理量与
 * 是否完成。批量由 {@link BatchProcessor} 根据上一批实际耗时与 {@link #tickBudgetNanos} 自适应
 * 决定，因此采集工作量按配置上限跨 tick 推进，而不是在一个 tick 内完成全量遍历。</p>
 *
 * <p><strong>生产接线（Phase 12）：</strong>{@code Sudoersemc} 使用
 * {@code ScanPipeline.beginCollection(server)} 作为 {@link SnapshotCollector}，采集会话按批
 * 推进物品与配方；应用阶段通过 {@link ResultApplier#isComplete(SnapshotData)} 支持异步求解
 * 轮询，纯计算在工作线程完成后再回到服务器线程写入存储。Phase 27 会在此基础上进一步拆分
 * 采集器与工作线程实现。</p>
 *
 * <p><strong>线程约定：</strong>快照采集（{@link SnapshotCollector} 与 {@link CollectSession}）、
 * 结果应用（{@link ResultApplier}）与指纹提供器（{@link FingerprintSupplier}）必须在服务器
 * 线程执行，因为真实实现会读取 Minecraft 注册表、配方管理器或写入 EMC 后端。只有不接触游戏
 * 状态的纯计算才可以移到工作线程。{@link #tick()}、{@link #requestScan()} 与 {@link #reset()}
 * 同样只能由服务器线程调用，因此本类没有为这些方法加锁。回调内重入 {@link #tick()} 会被忽略；
 * 回调内调用 {@link #requestScan()} 返回 {@code false}；回调内调用 {@link #reset()} 会抛出
 * {@link IllegalStateException}。{@link #progress()} 通过 {@code volatile} 字段发布，其他线程
 * 可以安全读取最近一次完整进度；{@link #cancel()} 也可以从其他线程调用，实际取消会在下一次
 * tick 被观察到。</p>
 *
 * <p><strong>持久化：</strong>本阶段 {@code saveStep()} 只把阶段推进到
 * {@link ScanPhase#COMPLETED}，不访问磁盘。Phase 12 的应用回调在异步求解完成后回到服务器
 * 线程写入 {@code GeneratedEntryStore} / {@code ScanStateStore}，再由接线层调用
 * {@code saveIfDirty()}。</p>
 *
 * <p>任何回调抛出的运行时异常都会被捕获并转换为 {@link ScanPhase#FAILED}，异常消息写入
 * 进度细节，绝不允许异常穿透 tick 循环破坏服务器主循环。</p>
 */
public final class ScanManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/task/ScanManager");

    /**
     * 分片采集会话工厂，必须在服务器线程执行。
     *
     * <p>每次扫描开始时调用一次 {@link #begin()} 创建新会话；会话持有本次采集的游标，
     * {@link ScanManager} 在每个 tick 调用一次 {@link CollectSession#step(int)} 推进有限
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
     * 当前扫描输入的指纹提供器，必须在服务器线程执行。
     *
     * <p>启用后，{@link ScanManager} 会在采集开始时与采集完成时各取一次指纹，并在应用前
     * 再取一次：采集期间发生变化会直接让扫描失败；采集后到应用前发生变化（例如 {@code /reload}
     * 或注册表变动）会拒绝应用过期结果并把扫描转为 {@link ScanPhase#FAILED}。未提供该回调时
     * 跳过一致性校验，仅用于不接触游戏状态的单元测试或过渡接线。</p>
     */
    @FunctionalInterface
    public interface FingerprintSupplier {

        /**
         * @return 当前输入的指纹，不能为 {@code null}
         */
        ScanFingerprint current();
    }

    /**
     * 应用阶段回调，必须在服务器线程执行。
     */
    @FunctionalInterface
    public interface ResultApplier {

        /**
         * 把求解结果写入实际后端。
         *
         * <p>实现可以在第一次调用时启动后台求解并立即返回；此时
         * {@link #isComplete(SnapshotData)} 返回 {@code false}，状态机保持 APPLYING，
         * 后续 tick 继续调用本方法轮询。</p>
         *
         * @param data 采集阶段产生的快照数据
         */
        void apply(SnapshotData data);

        /**
         * 应用阶段是否已经完成。
         *
         * <p>默认实现表示同步应用：{@link #apply(SnapshotData)} 返回时结果已经落盘。</p>
         *
         * @param data 采集阶段产生的快照数据
         * @return {@code true} 表示可以进入 SAVING
         */
        default boolean isComplete(SnapshotData data) {
            return true;
        }

        /**
         * 在新扫描开始、取消或失败时清理未完成的异步状态。默认无操作。
         */
        default void reset() {
        }

        /**
         * 是否由应用回调在工作线程完成求解。
         *
         * <p>返回 {@code true} 时 {@link ScanManager} 跳过仅推进数字的 SOLVING 阶段，直接从
         * COLLECTING 进入 APPLYING，由异步回调轮询真实求解进度；默认 {@code false} 保持原有
         * 同步阶段划分。</p>
         *
         * @return 是否使用异步求解
         */
        default boolean usesAsyncSolve() {
            return false;
        }

        /**
         * 当前 tick 是否已经可以应用结果。
         *
         * <p>异步实现可以在后台求解完成前返回 {@code false}，使 {@link ScanManager} 跳过
         * 应用前的指纹重算；求解完成后再返回 {@code true}，此时才校验输入是否在求解期间变化。
         * 默认返回 {@code true}，表示同步应用每次调用前都应校验。</p>
         *
         * @param data 采集阶段产生的快照数据
         * @return 可以应用时返回 {@code true}
         */
        default boolean isReadyToApply(SnapshotData data) {
            return true;
        }
    }

    /**
     * 把一次性快照包装成单批次采集会话，供过渡接线与测试使用。
     *
     * @param data 固定快照，不能为 {@code null}
     * @return 第一次 {@code step} 即完成的分片采集器
     * @deprecated 生产代码应实现真正的 {@link SnapshotCollector#begin()} 分片采集；
     *         该工厂仅用于尚未接入真实采集的过渡阶段与单元测试。
     */
    @Deprecated
    public static SnapshotCollector oneShot(SnapshotData data) {
        Objects.requireNonNull(data, "data");
        return () -> new CollectSession() {
            private boolean done;

            @Override
            public int itemCount() {
                return data.itemCount();
            }

            @Override
            public int recipeCount() {
                return data.recipeCount();
            }

            @Override
            public int processedItems() {
                return done ? data.itemCount() : 0;
            }

            @Override
            public int processedRecipes() {
                return done ? data.recipeCount() : 0;
            }

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public StepResult step(int maxItems) {
                done = true;
                return new StepResult(data.itemCount() + data.recipeCount(), true);
            }

            @Override
            public SnapshotData finish() {
                return data;
            }
        };
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
    private final long tickBudgetNanos;
    private final FingerprintSupplier fingerprintSupplier;

    private volatile ScanProgress progress = ScanProgress.idle();
    private volatile boolean cancelRequested;

    private SnapshotData snapshot;
    private CollectSession collectSession;
    private ScanFingerprint collectFingerprintBefore;
    private ScanFingerprint collectedFingerprint;
    private long lastBatchNanos;
    private boolean ticking;

    /**
     * 创建一个不做快照一致性校验的调度器。
     *
     * @deprecated 该构造器把 {@link FingerprintSupplier} 固定为 {@code null}，会静默跳过
     *         采集前后与应用前的指纹校验，只适用于不接触游戏状态的单元测试或过渡接线。
     *         生产接线必须使用带 {@link FingerprintSupplier} 的五参构造器，否则 {@code /reload}
     *         后可能把基于旧配方的结果写入 EMC。计划在后续阶段移除本构造器。
     *
     * @param batchProcessor  批量决策器，不能为 {@code null}
     * @param collector       分片采集会话工厂，不能为 {@code null}
     * @param applier         结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos 每 tick 的时间预算（纳秒），不得为负；只用于批量决策，
     *                        不会中断 {@link CollectSession#step(int)} 的执行
     */
    @Deprecated(since = "0.1.0", forRemoval = false)
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos) {
        this(batchProcessor, collector, applier, tickBudgetNanos, null);
    }

    /**
     * 创建带快照一致性校验的调度器。
     *
     * <p>{@code fingerprintSupplier} 会在采集开始时与采集完成时各调用一次，并在应用前
     * 再次调用。采集期间指纹变化或应用前指纹与采集时不一致都会让本次扫描进入
     * {@link ScanPhase#FAILED}，从而避免把基于旧注册表/配方的结果写入 EMC。</p>
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
        this.batchProcessor = Objects.requireNonNull(batchProcessor, "batchProcessor");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.applier = Objects.requireNonNull(applier, "applier");
        if (tickBudgetNanos < 0L) {
            throw new IllegalArgumentException("tickBudgetNanos 必须 >= 0，实际为 " + tickBudgetNanos);
        }
        this.tickBudgetNanos = tickBudgetNanos;
        this.fingerprintSupplier = fingerprintSupplier;
    }

    /**
     * 请求开始一次新扫描。
     *
     * <p>已有扫描处于活动状态时返回 {@code false}，因此重复请求不会创建第二个扫描。
     * 成功请求会清空上一次的快照、采集会话、指纹与取消标志，并把进度重置为
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
        cancelRequested = false;
        snapshot = null;
        collectSession = null;
        collectFingerprintBefore = null;
        collectedFingerprint = null;
        lastBatchNanos = 0L;
        applier.reset();
        long now = System.currentTimeMillis();
        progress = new ScanProgress(ScanPhase.COLLECTING_SNAPSHOT, 0, 0, now, now, "等待采集快照");
        return true;
    }

    /**
     * 请求取消当前扫描。
     *
     * <p>本方法只设置标志，不直接修改阶段；正在运行的扫描会在下一次 {@link #tick()} 中
     * 观察到并转入 {@link ScanPhase#CANCELLED}。空闲时设置该标志没有副作用，下一次
     * {@link #requestScan()} 会将其清除。</p>
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
     * {@link CollectSession#step(int)}，因此采集必然按批次跨 tick 推进；会话返回完成标志后
     * 才进入求解阶段。求解阶段按批量推进进度；应用与持久化各调用一次对应回调。</p>
     *
     * <p>本方法只能由服务器线程调用。回调内重入调用会被直接忽略，防止递归采集。批量由
     * {@link BatchProcessor#nextBatchSize(long, long)} 根据上一批实际耗时与
     * {@code tickBudgetNanos} 计算；{@code tickBudgetNanos} 不是抢占式超时，采集实现必须
     * 自行保证单次 {@code step} 不超过传入的批量上限。实际处理量会回写给
     * {@link BatchProcessor#recordBatch(int, long)}，不会用建议批量冒充实际工作量。</p>
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
            progress = current.advance(ScanPhase.CANCELLED, current.processed(), current.total(),
                    "扫描已取消");
            return;
        }

        ScanPhase phase = current.phase();
        int batch = batchProcessor.nextBatchSize(lastBatchNanos, tickBudgetNanos);
        long startedNanos = System.nanoTime();
        int actualProcessed = 0;
        try {
            switch (phase) {
                case COLLECTING_SNAPSHOT -> actualProcessed = collectStep(batch);
                case SOLVING -> actualProcessed = solveStep(batch);
                case APPLYING -> applyStep();
                case SAVING -> saveStep();
                default -> {
                    // 防御性分支：isRunning() 已经排除了所有终止阶段。
                }
            }
        } catch (RuntimeException e) {
            try {
                applier.reset();
            } catch (RuntimeException resetFailure) {
                LOGGER.warn("清理应用阶段回调失败：{}", resetFailure.getMessage(), resetFailure);
            }
            ScanProgress failed = progress;
            progress = failed.advance(ScanPhase.FAILED, failed.processed(), failed.total(),
                    failureDetail(e));
        } finally {
            // 只有采集/求解真正处理了元素才记入自适应样本；应用与持久化的耗时不得污染样本。
            if (actualProcessed > 0) {
                long elapsedNanos = System.nanoTime() - startedNanos;
                lastBatchNanos = elapsedNanos;
                batchProcessor.recordBatch(actualProcessed, elapsedNanos);
            }
        }
    }

    /**
     * 把状态机恢复到空闲，并清除取消标志、快照、采集会话、指纹与计时样本。
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
        cancelRequested = false;
        snapshot = null;
        collectSession = null;
        collectFingerprintBefore = null;
        collectedFingerprint = null;
        lastBatchNanos = 0L;
        applier.reset();
        progress = ScanProgress.idle();
    }

    /**
     * 采集阶段：首个 tick 创建分片会话，之后每个 tick 只推进一个批次。
     *
     * <p>会话在 {@link CollectSession#step(int)} 中按 {@code batch} 上限处理有限工作量；
     * {@link ScanManager} 不会在一次 tick 内循环调用到完成，因此多物品扫描必然跨越多个
     * tick。采集开始时取一次指纹，会话完成后取第二次指纹并比较；两次不一致说明采集期间
     * 输入已经变化，本次快照不可信，直接让扫描失败。</p>
     *
     * @return 本 tick 实际处理的元素数量，用于自适应批量记账
     */
    private int collectStep(int batch) {
        if (collectSession == null) {
            ScanFingerprint before = currentFingerprint();
            CollectSession session = collector.begin();
            if (session == null) {
                throw new IllegalStateException("快照采集器返回 null 会话");
            }
            collectSession = session;
            collectFingerprintBefore = before;
            publishCollectProgress(batch);
        }

        if (collectSession.isDone()) {
            finishCollection();
            return 0;
        }

        StepResult result = collectSession.step(batch);
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
     * 结束采集：校验采集期间的指纹一致性，取得最终快照并转入求解阶段。
     */
    private void finishCollection() {
        CollectSession session = collectSession;
        if (session == null) {
            throw new IllegalStateException("采集阶段缺少会话");
        }
        ScanFingerprint after = currentFingerprint();
        if (collectFingerprintBefore != null
                && !collectFingerprintBefore.equalsFingerprint(after)) {
            collectSession = null;
            throw new IllegalStateException("快照采集期间扫描输入发生变化，已放弃本次结果");
        }
        SnapshotData collected = session.finish();
        if (collected == null) {
            collectSession = null;
            throw new IllegalStateException("快照采集器返回 null 快照");
        }
        snapshot = collected;
        collectedFingerprint = after;
        collectSession = null;
        collectFingerprintBefore = null;
        progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT, collected.itemCount(),
                collected.itemCount(), "已采集 " + collected.itemCount() + " 个物品快照");
        beginSolving();
    }

    /**
     * 物品/配方快照采集完成后转入求解阶段，并把总量切换为配方数量。
     */
    private void beginSolving() {
        SnapshotData data = snapshot;
        int total = data == null ? 0 : data.recipeCount();
        if (applier.usesAsyncSolve()) {
            // 异步求解的真实工作由应用回调轮询，SOLVING 只会拖长假进度，因此直接进入 APPLYING。
            progress = progress.advance(ScanPhase.APPLYING, 0, 0, "开始后台求解");
            return;
        }
        progress = progress.advance(ScanPhase.SOLVING, 0, total, "开始求解配方");
    }

    /**
     * 求解阶段：按批量推进配方数量，完成后进入应用阶段。
     *
     * <p>Phase 7 尚未接入真实求解器；本阶段只按 {@code batch} 推进进度记账，不会执行
     * 配方计算。真实求解在 Phase 12 的管线中接入。</p>
     *
     * @return 本 tick 推进的实际配方数量，用于自适应批量记账
     */
    private int solveStep(int batch) {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("求解阶段缺少快照数据");
        }

        int total = data.recipeCount();
        ScanProgress current = progress;
        if (total <= 0) {
            progress = current.advance(ScanPhase.APPLYING, 0, 0, "没有需要求解的配方");
            return 0;
        }

        int next = (int) Math.min((long) total, (long) current.processed() + batch);
        int actual = next - current.processed();
        progress = current.advance(ScanPhase.SOLVING, next, total, "求解配方 " + next + "/" + total);
        if (next >= total) {
            progress = progress.advance(ScanPhase.APPLYING, 0, 0, "开始应用结果");
        }
        return actual;
    }

    /**
     * 应用阶段：先校验快照仍然对应当前输入，再调用一次应用器，随后进入持久化。
     */
    private void applyStep() {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("应用阶段缺少快照数据");
        }
        // 异步求解在完成前不重算完整输入指纹，避免在等待期间每个 tick 都对大型注册表排序哈希；
        // 求解完成后再校验，仍能拦截求解期间的 /reload 或注册表变化。
        if (applier.isReadyToApply(data)) {
            verifyFingerprintBeforeApply();
        }
        applier.apply(data);
        if (!applier.isComplete(data)) {
            progress = progress.advance(ScanPhase.APPLYING, 0, 0, "等待后台求解完成");
            return;
        }
        progress = progress.advance(ScanPhase.SAVING, 0, 0, "结果已应用，开始持久化");
    }

    /**
     * 持久化阶段：本阶段只发布完成状态，不访问磁盘。
     *
     * <p>Phase 7 没有存储接线；Phase 12 的管线应用回调负责写入生成值/扫描状态并调用
     * {@code saveIfDirty()}。因此当前状态机的 {@link ScanPhase#COMPLETED} 仅表示内存流程
     * 结束，不代表已经落盘。</p>
     */
    private void saveStep() {
        progress = progress.advance(ScanPhase.COMPLETED, 0, 0, "扫描已完成");
    }

    /**
     * 应用前重新读取指纹，拒绝使用采集后已经过期的快照。
     */
    private void verifyFingerprintBeforeApply() {
        if (fingerprintSupplier == null) {
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
     * 把异常转换为适合展示的失败细节，保证不会因为消息为空而丢失错误类型。
     */
    private static String failureDetail(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
