package com.itzplana.sudoersemc.task;

import java.util.Objects;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由服务器 tick 驱动的单次扫描状态机。
 *
 * <p>一次扫描的生命周期为：请求扫描 → 采集快照 → 求解配方 → 应用结果 → 持久化 → 完成。
 * 每次 {@link #tick()} 最多推进一步当前阶段，并根据 {@link BatchProcessor} 的建议批量
 * 增加已处理数量。这样把可能较重的全量扫描拆散到多个 tick，避免服务器出现长时间卡顿。</p>
 *
 * <p><strong>线程约定：</strong>快照采集（{@link SnapshotCollector}）与结果应用
 * （{@link ResultApplier}）必须在服务器线程执行，因为真实实现会读取 Minecraft 注册表、
 * 配方管理器或写入 EMC 后端。只有不接触游戏状态的纯计算才可以移到工作线程。{@link #tick()}
 * 同样由服务器线程调用。{@link #progress()} 通过 {@code volatile} 字段发布，其他线程可以
 * 安全读取最近一次完整进度；{@link #cancel()} 也可以从其他线程调用。状态机仍在下一次
 * tick 转入 {@link ScanPhase#CANCELLED}，但共享 {@link ScanCancellation} 令牌允许管线在
 * 物品采集、配方扫描、NBT 变体、求解与应用循环内部提前停止重活。</p>
 *
 * <p>任何回调抛出的运行时异常都会被捕获并转换为 {@link ScanPhase#FAILED}，异常消息写入
 * 进度细节，绝不允许异常穿透 tick 循环破坏服务器主循环。</p>
 */
public final class ScanManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/task/ScanManager");

    /**
     * 采集阶段回调，必须在服务器线程执行。
     */
    @FunctionalInterface
    public interface SnapshotCollector {

        /**
         * 采集一次扫描所需的完整快照。
         *
         * @return 非空快照数据
         */
        SnapshotData collect();
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
     * 扫描被取消或失败时的终止回调。
     *
     * <p>只会在 {@link ScanPhase#CANCELLED} 与 {@link ScanPhase#FAILED} 时触发，用于在
     * 管线来不及走完正常应用阶段时写出部分诊断报告。实现方不得抛出异常；即使抛出，本类
     * 也只记录日志并继续。</p>
     */
    @FunctionalInterface
    public interface AbortListener {

        /**
         * @param phase       终止阶段，只会是 {@link ScanPhase#CANCELLED} 或 {@link ScanPhase#FAILED}
         * @param progress    终止时的进度快照
         * @param diagnostics 本次扫描的耗时与内存采样
         */
        void onScanAborted(ScanPhase phase, ScanProgress progress, ScanDiagnostics diagnostics);
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
    private final ScanDiagnostics diagnostics;
    @Nullable
    private final AbortListener abortListener;

    private volatile ScanProgress progress = ScanProgress.idle();
    private final ScanCancellation cancellation;

    private SnapshotData snapshot;
    private long lastBatchNanos;

    /**
     * 兼容旧调用点的构造器：使用内部新建的诊断收集器，不注册终止回调。
     *
     * @param batchProcessor 批量决策器，不能为 {@code null}
     * @param collector      快照采集回调，不能为 {@code null}
     * @param applier        结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos 每 tick 允许用于扫描的时间预算（纳秒），不得为负；
     *                        0 表示预算未知，自适应模式会沿用当前批量
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos) {
        this(batchProcessor, collector, applier, tickBudgetNanos, new ScanDiagnostics(), null);
    }

    /**
     * @param batchProcessor 批量决策器，不能为 {@code null}
     * @param collector      快照采集回调，不能为 {@code null}
     * @param applier        结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos 每 tick 允许用于扫描的时间预算（纳秒），不得为负；
     *                        0 表示预算未知，自适应模式会沿用当前批量
     * @param diagnostics    耗时与内存采样器；{@code null} 时新建一个
     * @param abortListener  取消/失败终止回调，可为 {@code null}
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos,
                       @Nullable ScanDiagnostics diagnostics,
                       @Nullable AbortListener abortListener) {
        this(batchProcessor, collector, applier, tickBudgetNanos, diagnostics, abortListener,
                new ScanCancellation());
    }

    /**
     * 使用共享取消令牌创建调度器。
     *
     * <p>真实管线可以持有同一个 {@link ScanCancellation}，在物品采集、配方扫描、NBT 变体、
     * 求解与应用循环中主动检查取消信号，从而不必等到下一次 {@link #tick()}。传入
     * {@code null} 时新建令牌，行为与旧构造器一致。</p>
     *
     * @param batchProcessor 批量决策器，不能为 {@code null}
     * @param collector      快照采集回调，不能为 {@code null}
     * @param applier        结果应用回调，不能为 {@code null}
     * @param tickBudgetNanos 每 tick 允许用于扫描的时间预算（纳秒），不得为负
     * @param diagnostics    耗时与内存采样器；{@code null} 时新建一个
     * @param abortListener  取消/失败终止回调，可为 {@code null}
     * @param cancellation   共享取消令牌，可为 {@code null}
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos,
                       @Nullable ScanDiagnostics diagnostics,
                       @Nullable AbortListener abortListener,
                       @Nullable ScanCancellation cancellation) {
        this.batchProcessor = Objects.requireNonNull(batchProcessor, "batchProcessor");
        this.collector = Objects.requireNonNull(collector, "collector");
        this.applier = Objects.requireNonNull(applier, "applier");
        if (tickBudgetNanos < 0L) {
            throw new IllegalArgumentException("tickBudgetNanos 必须 >= 0，实际为 " + tickBudgetNanos);
        }
        this.tickBudgetNanos = tickBudgetNanos;
        this.diagnostics = diagnostics == null ? new ScanDiagnostics() : diagnostics;
        this.abortListener = abortListener;
        this.cancellation = cancellation == null ? new ScanCancellation() : cancellation;
    }

    /**
     * @return 本次调度器使用的共享取消令牌；管线可以持有同一实例并做协作式检查
     */
    public ScanCancellation cancellation() {
        return cancellation;
    }

    /**
     * @return 本次扫描使用的诊断采样器，便于调用方复用同一份耗时与内存数据
     */
    public ScanDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * 请求开始一次新扫描。
     *
     * <p>已有扫描处于活动状态时返回 {@code false}，因此重复请求不会创建第二个扫描。
     * 成功请求会清空上一次的快照与取消标志，并把进度重置为
     * {@link ScanPhase#COLLECTING_SNAPSHOT}。扫描完成、取消或失败后可以再次请求。</p>
     *
     * @return 成功开始返回 {@code true}；已有扫描在运行返回 {@code false}
     */
    public synchronized boolean requestScan() {
        if (isRunning()) {
            return false;
        }
        cancellation.clear();
        snapshot = null;
        lastBatchNanos = 0L;
        diagnostics.reset();
        diagnostics.recordMemory();
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
        cancellation.request();
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
        return cancellation.isRequested();
    }

    /**
     * 推进一次扫描状态机。
     *
     * <p>非活动状态下直接返回，不调用任何回调，也不消耗批量预算。取消标志优先于阶段推进。
     * 采集阶段只调用一次采集器；随后按建议批量累加已处理数量。求解阶段同样按批量推进。
     * 应用阶段调用一次应用器后进入持久化，持久化阶段完成后转入
     * {@link ScanPhase#COMPLETED}。</p>
     *
     * <p>回调抛出的 {@link RuntimeException} 会被捕获并转为 {@link ScanPhase#FAILED}，
     * 细节字段保存异常消息（无消息时退化为异常类名）。本方法不会向外传播这些异常。</p>
     */
    public void tick() {
        ScanProgress current = progress;
        if (!current.isRunning()) {
            return;
        }

        long startedNanos = System.nanoTime();
        diagnostics.recordMemory();
        int batch = 0;
        boolean batchRecorded = false;
        ScanPhase abortedPhase = null;
        try {
            if (cancellation.isRequested()) {
                cancellation.clear();
                progress = current.advance(ScanPhase.CANCELLED, current.processed(),
                        current.total(), "扫描已取消");
                abortedPhase = ScanPhase.CANCELLED;
            } else {
                batch = batchProcessor.nextBatchSize(lastBatchNanos, tickBudgetNanos);
                batchRecorded = true;
                switch (current.phase()) {
                    case COLLECTING_SNAPSHOT -> collectStep(batch);
                    case SOLVING -> solveStep(batch);
                    case APPLYING -> applyStep();
                    case SAVING -> saveStep();
                    default -> {
                        // 防御性分支：isRunning() 已经排除了所有终止阶段。
                    }
                }
            }
        } catch (RuntimeException e) {
            ScanProgress failed = progress;
            progress = failed.advance(ScanPhase.FAILED, failed.processed(), failed.total(),
                    failureDetail(e));
            abortedPhase = ScanPhase.FAILED;
        } finally {
            long elapsedNanos = System.nanoTime() - startedNanos;
            lastBatchNanos = elapsedNanos;
            if (batchRecorded) {
                batchProcessor.recordBatch(batch, elapsedNanos);
            }
            // tick 边界统一采样：即使本 tick 被取消或失败，也会留下耗时与内存数据。
            diagnostics.recordTick(elapsedNanos);
            diagnostics.recordMemory();
        }

        if (abortedPhase != null) {
            notifyAborted(abortedPhase);
        }
    }

    private void notifyAborted(ScanPhase phase) {
        AbortListener listener = abortListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onScanAborted(phase, progress, diagnostics);
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 扫描终止回调失败（{}），已忽略", phase, failure);
        }
    }

    /**
     * 把状态机恢复到空闲，并清除取消标志、快照与计时样本。
     *
     * <p>可在运行中调用；后续 {@link #tick()} 将不再推进旧扫描。批量决策器的自适应基准
     * 不会被重置，以便下一次扫描沿用已经学到的批量。</p>
     */
    public synchronized void reset() {
        cancellation.clear();
        snapshot = null;
        lastBatchNanos = 0L;
        diagnostics.reset();
        progress = ScanProgress.idle();
    }

    /**
     * 采集阶段：首次进入时调用采集器，随后按批量推进物品数量。
     */
    private void collectStep(int batch) {
        if (snapshot == null) {
            SnapshotData collected = collector.collect();
            if (collected == null) {
                throw new IllegalStateException("快照采集器返回 null");
            }
            snapshot = collected;
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
     * 应用阶段：调用一次应用器，随后进入持久化。
     */
    private void applyStep() {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("应用阶段缺少快照数据");
        }
        applier.apply(data);
        progress = progress.advance(ScanPhase.SAVING, 0, 0, "结果已应用，开始持久化");
    }

    /**
     * 持久化阶段：当前版本没有独立持久化回调，直接标记完成。Phase 9+ 可在此接入落盘逻辑。
     */
    private void saveStep() {
        progress = progress.advance(ScanPhase.COMPLETED, 0, 0, "扫描已完成");
    }

    /**
     * 把异常转换为适合展示的失败细节，保证不会因为消息为空而丢失错误类型。
     */
    private static String failureDetail(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
