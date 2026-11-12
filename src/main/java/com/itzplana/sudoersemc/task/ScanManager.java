package com.itzplana.sudoersemc.task;

import java.util.Objects;

/**
 * 由服务器 tick 驱动的单次扫描状态机。
 *
 * <p>一次扫描的生命周期为：请求扫描 → 采集快照 → 求解配方 → 应用结果 → 持久化 → 完成。
 * 每次 {@link #tick()} 最多推进一步当前阶段，并根据 {@link BatchProcessor} 的建议批量
 * 增加已处理数量。</p>
 *
 * <p><strong>阶段边界：</strong>本类只负责调度与进度记账。当前版本的
 * {@link SnapshotCollector#collect()} 仍是一次性全量采集，其执行时间不受
 * {@code tickBudgetNanos} 约束。真正把采集与求解拆成可中断分片的工作由后续管线阶段
 * （真实扫描管线与增量采集会话）实现；接入真实扫描器时必须由采集实现自行控制单 tick
 * 工作量。本类不承诺单个 tick 内的实际工作上限。</p>
 *
 * <p><strong>线程约定：</strong>快照采集（{@link SnapshotCollector}）、结果应用
 * （{@link ResultApplier}）与指纹提供器（{@link FingerprintSupplier}）必须在服务器线程
 * 执行，因为真实实现会读取 Minecraft 注册表、配方管理器或写入 EMC 后端。只有不接触游戏
 * 状态的纯计算才可以移到工作线程。{@link #tick()}、{@link #requestScan()} 与
 * {@link #reset()} 同样只能由服务器线程调用，因此本类没有为这些方法加锁。回调内重入
 * {@link #tick()} 会被忽略；回调内调用 {@link #requestScan()} 返回 {@code false}；
 * 回调内调用 {@link #reset()} 会抛出 {@link IllegalStateException}。
 * {@link #progress()} 通过 {@code volatile} 字段发布，其他线程可以安全读取最近一次完整
 * 进度；{@link #cancel()} 也可以从其他线程调用，实际取消会在下一次 tick 被观察到。</p>
 *
 * <p>任何回调抛出的运行时异常都会被捕获并转换为 {@link ScanPhase#FAILED}，异常消息写入
 * 进度细节，绝不允许异常穿透 tick 循环破坏服务器主循环。</p>
 */
public final class ScanManager {

    /**
     * 采集阶段回调，必须在服务器线程执行。
     */
    @FunctionalInterface
    public interface SnapshotCollector {

        /**
         * 采集一次扫描所需的完整快照。
         *
         * <p>当前接口是一次性调用，调用期间不会收到 tick 预算或截止时间；实现方如需避免
         * 长停顿，应在后续管线阶段改用分片采集会话，或自行保证调用足够轻量。</p>
         *
         * @return 非空快照数据
         */
        SnapshotData collect();
    }

    /**
     * 当前扫描输入的指纹提供器，必须在服务器线程执行。
     *
     * <p>启用后，{@link ScanManager} 会在采集前后与应用前各取一次指纹：采集期间发生变化
     * 会直接让扫描失败；采集后到应用前发生变化（例如 {@code /reload} 或注册表变动）会拒绝
     * 应用过期结果并把扫描转为 {@link ScanPhase#FAILED}。未提供该回调时跳过一致性校验，
     * 仅用于不接触游戏状态的单元测试或过渡接线。</p>
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
         * @param data 采集阶段产生的快照数据
         */
        void apply(SnapshotData data);
    }

    /**
     * 回调之间传递的最小数据载体。
     *
     * <p>当前阶段只携带标签、物品数量与配方数量，使 {@link ScanManager} 保持对 Minecraft
     * 的零依赖。Phase 9+ 接入真实流水线时，可以通过泛型化或子类承载更丰富的快照内容，
     * 而无需让状态机本身引用游戏类型。</p>
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
    private ScanFingerprint collectedFingerprint;
    private long lastBatchNanos;
    private boolean ticking;

    /**
     * 创建一个不做快照一致性校验的调度器。
     *
     * <p>该构造器等价于传入 {@code null} 指纹提供器，仅用于兼容尚未接线的过渡阶段或纯
     * 单元测试。生产接线应使用带 {@link FingerprintSupplier} 的构造器，避免在配方重载后
     * 把过期结果写入 EMC。</p>
     *
     * @param batchProcessor  批量决策器，不能为 {@code null}
     * @param collector       快照采集回调，不能为 {@code null}
     * @param applier         结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos 每 tick 的时间预算（纳秒），不得为负；只用于批量决策，
     *                        不会中断或限制 {@link SnapshotCollector#collect()} 的执行
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos) {
        this(batchProcessor, collector, applier, tickBudgetNanos, null);
    }

    /**
     * 创建带快照一致性校验的调度器。
     *
     * <p>{@code fingerprintSupplier} 会在采集前后与应用前被调用，且必须返回非 {@code null}
     * 的指纹。采集期间指纹变化或应用前指纹与采集时不一致都会让本次扫描进入
     * {@link ScanPhase#FAILED}，从而避免把基于旧注册表/配方的结果写入 EMC。</p>
     *
     * @param batchProcessor      批量决策器，不能为 {@code null}
     * @param collector           快照采集回调，不能为 {@code null}
     * @param applier             结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos     每 tick 的时间预算（纳秒），不得为负
     * @param fingerprintSupplier 输入指纹提供器；{@code null} 表示跳过一致性校验
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
     * 成功请求会清空上一次的快照、指纹与取消标志，并把进度重置为
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
        collectedFingerprint = null;
        lastBatchNanos = 0L;
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
     * 采集阶段只调用一次采集器；随后按建议批量累加已处理数量。求解阶段同样按批量推进。
     * 应用阶段调用一次应用器后进入持久化，持久化阶段完成后转入
     * {@link ScanPhase#COMPLETED}。</p>
     *
     * <p>本方法只能由服务器线程调用。回调内重入调用会被直接忽略，防止递归采集。采集器与
     * 应用器的实际执行时间不受 {@code tickBudgetNanos} 约束；预算只影响随后的进度步长和
     * 自适应批量。</p>
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
        try {
            switch (phase) {
                case COLLECTING_SNAPSHOT -> collectStep(batch);
                case SOLVING -> solveStep(batch);
                case APPLYING -> applyStep();
                case SAVING -> saveStep();
                default -> {
                    // 防御性分支：isRunning() 已经排除了所有终止阶段。
                }
            }
        } catch (RuntimeException e) {
            ScanProgress failed = progress;
            progress = failed.advance(ScanPhase.FAILED, failed.processed(), failed.total(),
                    failureDetail(e));
        } finally {
            // 只有采集/求解真正按批量处理元素，应用与持久化耗时不得污染自适应样本。
            if (phase == ScanPhase.COLLECTING_SNAPSHOT || phase == ScanPhase.SOLVING) {
                long elapsedNanos = System.nanoTime() - startedNanos;
                lastBatchNanos = elapsedNanos;
                batchProcessor.recordBatch(batch, elapsedNanos);
            }
        }
    }

    /**
     * 把状态机恢复到空闲，并清除取消标志、快照、指纹与计时样本。
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
        collectedFingerprint = null;
        lastBatchNanos = 0L;
        progress = ScanProgress.idle();
    }

    /**
     * 采集阶段：首次进入时调用采集器，随后按批量推进物品数量。
     *
     * <p>若配置了指纹提供器，采集前后会各取一次指纹；两次不一致说明采集期间输入已经变化，
     * 本次快照不可信，直接让扫描失败。</p>
     */
    private void collectStep(int batch) {
        if (snapshot == null) {
            ScanFingerprint before = currentFingerprint();
            SnapshotData collected = collector.collect();
            if (collected == null) {
                throw new IllegalStateException("快照采集器返回 null");
            }
            ScanFingerprint after = currentFingerprint();
            if (before != null && !after.equalsFingerprint(before)) {
                throw new IllegalStateException("快照采集期间扫描输入发生变化，已放弃本次结果");
            }
            snapshot = collected;
            collectedFingerprint = after;
            progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT, 0, collected.itemCount(),
                    "已采集 " + collected.itemCount() + " 个物品快照");
        }

        ScanProgress current = progress;
        int total = current.total();
        if (total <= 0) {
            beginSolving();
            return;
        }

        int next = (int) Math.min((long) total, (long) current.processed() + batch);
        progress = current.advance(ScanPhase.COLLECTING_SNAPSHOT, next, total,
                "采集快照 " + next + "/" + total);
        if (next >= total) {
            beginSolving();
        }
    }

    /**
     * 物品采集完成后转入配方求解，并把总量切换为配方数量。
     */
    private void beginSolving() {
        SnapshotData data = snapshot;
        int total = data == null ? 0 : data.recipeCount();
        progress = progress.advance(ScanPhase.SOLVING, 0, total, "开始求解配方");
    }

    /**
     * 求解阶段：按批量推进配方数量，完成后进入应用阶段。
     */
    private void solveStep(int batch) {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("求解阶段缺少快照数据");
        }

        int total = data.recipeCount();
        ScanProgress current = progress;
        if (total <= 0) {
            progress = current.advance(ScanPhase.APPLYING, 0, 0, "没有需要求解的配方");
            return;
        }

        int next = (int) Math.min((long) total, (long) current.processed() + batch);
        progress = current.advance(ScanPhase.SOLVING, next, total, "求解配方 " + next + "/" + total);
        if (next >= total) {
            progress = progress.advance(ScanPhase.APPLYING, 0, 0, "开始应用结果");
        }
    }

    /**
     * 应用阶段：先校验快照仍然对应当前输入，再调用一次应用器，随后进入持久化。
     */
    private void applyStep() {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("应用阶段缺少快照数据");
        }
        verifyFingerprintBeforeApply();
        applier.apply(data);
        progress = progress.advance(ScanPhase.SAVING, 0, 0, "结果已应用，开始持久化");
    }

    /**
     * 持久化阶段：本状态机只发布完成状态。实际的状态/结果落盘由后续阶段接入
     * {@code ScanStateStore} 与管线应用回调完成。
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
