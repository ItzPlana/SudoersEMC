package com.itzplana.sudoersemc.task;

import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.pipeline.SolveOutcome;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 由服务器 tick 驱动的单次扫描状态机。
 *
 * <p>一次扫描的生命周期为：请求扫描 → 分片采集快照 → 求解配方 → 应用结果 → 持久化 → 完成。
 * 每次 {@link #tick()} 最多推进一个阶段的一批工作：</p>
 * <ul>
 *   <li>{@link ScanPhase#COLLECTING_SNAPSHOT}：首次 tick 创建增量采集会话，随后每 tick 用
 *       {@link BatchProcessor#nextBatchSize(long, long)} 决定预算并调用
 *       {@link ScanPipeline.CollectSession#step(int)}；</li>
 *   <li>{@link ScanPhase#SOLVING}：主线程只轮询 {@link Future#isDone()}，不阻塞等待；</li>
 *   <li>{@link ScanPhase#APPLYING}：一次调用应用回调完成安全层与写入；</li>
 *   <li>{@link ScanPhase#SAVING}：保留原有落盘阶段。</li>
 * </ul>
 *
 * <p><strong>线程约定：</strong>快照采集（{@link SnapshotCollector} / {@link PipelineCollector}）
 * 与结果应用（{@link ResultApplier} / {@link PipelineApplier}）必须在服务器线程执行；只有不接触
 * 游戏状态的纯计算可以通过 {@link SolveWorker} 移到工作线程。{@link #tick()} 同样由服务器线程
 * 调用。{@link #progress()} 通过 {@code volatile} 字段发布，其他线程可以安全读取最近一次完整
 * 进度；{@link #cancel()} 也可以从其他线程调用。</p>
 *
 * <p>任何回调抛出的运行时异常都会被捕获并转换为 {@link ScanPhase#FAILED}，异常消息写入进度
 * 细节，绝不允许异常穿透 tick 循环破坏服务器主循环。</p>
 */
public final class ScanManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/task/ScanManager");

    /**
     * 传统一次性采集回调，必须在服务器线程执行。
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
     * 传统应用回调，必须在服务器线程执行。
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
     * 分片采集回调：每次扫描开始时创建新的增量会话。
     */
    @FunctionalInterface
    public interface PipelineCollector {

        /**
         * @return 新的增量采集会话；不能为 {@code null}
         */
        ScanPipeline.CollectSession begin();
    }

    /**
     * 分片应用回调：在服务器线程应用纯求解结果。
     */
    @FunctionalInterface
    public interface PipelineApplier {

        /**
         * @param snapshot 采集快照
         * @param outcome  工作线程（或同步）求解结果
         * @return 实际应用的条目数量，仅用于进度显示
         */
        int apply(PipelineSnapshot snapshot, SolveOutcome outcome);
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
     * 当前扫描输入的指纹与 reload 代次提供器，必须在服务器线程执行。
     *
     * <p>实现若可通过 Forge 的 {@code AddReloadListenerEvent}/{@code TagsUpdatedEvent} 等
     * 廉价来源维护单调递增的 reload 代次，应覆写 {@link #tracksReloadGeneration()} 与
     * {@link #reloadGeneration()}。此时扫描只在开始时读取一次代次，采集完成、求解完成与
     * 应用前都用代次比较，不再重复计算全量内容指纹。</p>
     */
    @FunctionalInterface
    public interface FingerprintSupplier {

        /**
         * @return 当前输入的完整指纹，不能为 {@code null}
         */
        ScanFingerprint current();

        /**
         * 廉价、单调递增的 reload 代次。每次 datapack reload（开始与完成）都应递增。
         *
         * @return 当前代次
         */
        default long reloadGeneration() {
            return 0L;
        }

        /**
         * @return {@code true} 表示 {@link #reloadGeneration()} 能廉价反映 reload 变化
         */
        default boolean tracksReloadGeneration() {
            return false;
        }
    }

    /**
     * 传统回调之间传递的最小数据载体。
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
    @Nullable
    private final SnapshotCollector collector;
    @Nullable
    private final ResultApplier applier;
    @Nullable
    private final PipelineCollector pipelineCollector;
    @Nullable
    private final PipelineApplier pipelineApplier;
    private final long tickBudgetNanos;
    private final ScanDiagnostics diagnostics;
    @Nullable
    private final AbortListener abortListener;
    @Nullable
    private final SolveWorker solveWorker;
    private final boolean solveOffThread;
    /** 取消/失败时把部分快照交给管线暂存，供部分诊断报告使用；可为 {@code null}。 */
    @Nullable
    private final Consumer<PipelineSnapshot> partialSnapshotStasher;
    /** 新扫描开始时清空管线上一轮的报告状态；可为 {@code null}。 */
    @Nullable
    private final Runnable scanStartHook;

    /** 可选的输入一致性校验；{@code null} 时保持仅供单元测试使用的跳过语义。 */
    @Nullable
    private volatile FingerprintSupplier fingerprintSupplier;
    private boolean reloadTracking;
    @Nullable
    private ScanFingerprint collectFingerprintBefore;
    private long collectReloadGeneration;
    private boolean inputSnapshotCaptured;

    private volatile ScanProgress progress = ScanProgress.idle();
    private final ScanCancellation cancellation;
    /** 本 tick 执行了内联求解，不能把该耗时当作批量样本。 */
    private boolean skipBatchSample;

    @Nullable
    private SnapshotData snapshot;
    @Nullable
    private PipelineSnapshot pipelineSnapshot;
    @Nullable
    private ScanPipeline.CollectSession collectSession;
    @Nullable
    private Future<SolveOutcome> solveFuture;
    @Nullable
    private SolveOutcome solveOutcome;
    private long lastBatchNanos;

    /**
     * 兼容旧调用点的构造器：使用内部新建的诊断收集器，不注册终止回调。
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos) {
        this(batchProcessor, collector, applier, tickBudgetNanos, new ScanDiagnostics(), null);
    }

    /**
     * 兼容旧调用点的构造器。
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
     * 使用共享取消令牌创建传统调度器。
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos,
                       @Nullable ScanDiagnostics diagnostics,
                       @Nullable AbortListener abortListener,
                       @Nullable ScanCancellation cancellation) {
        this(batchProcessor, collector, applier, null, null, tickBudgetNanos, diagnostics,
                abortListener, cancellation, null, false, null, null);
    }

    /**
     * 创建分片调度器。
     *
     * <p>{@code solveWorker} 提供纯求解函数；{@code solveOffThread=true} 时在
     * {@link SolveWorker#THREAD_NAME} 线程执行，{@code false} 时在同一 tick 内同步执行。
     * 通过旧构造器创建的调度器保持原有一次性采集/应用语义。</p>
     *
     * @param batchProcessor  批量决策器，不能为 {@code null}
     * @param collector       分片采集回调，不能为 {@code null}
     * @param applier         应用回调，不能为 {@code null}
     * @param tickBudgetNanos 每 tick 允许用于扫描的时间预算（纳秒），不得为负
     * @param diagnostics     耗时与内存采样器；{@code null} 时新建
     * @param abortListener   取消/失败终止回调，可为 {@code null}
     * @param cancellation    共享取消令牌，可为 {@code null}
     * @param solveWorker     纯求解工作线程；分片模式必须提供
     * @param solveOffThread  是否把纯求解移到工作线程
     */
    public ScanManager(BatchProcessor batchProcessor,
                       PipelineCollector collector,
                       PipelineApplier applier,
                       long tickBudgetNanos,
                       @Nullable ScanDiagnostics diagnostics,
                       @Nullable AbortListener abortListener,
                       @Nullable ScanCancellation cancellation,
                       @Nullable SolveWorker solveWorker,
                       boolean solveOffThread) {
        this(batchProcessor, collector, applier, tickBudgetNanos, diagnostics, abortListener,
                cancellation, solveWorker, solveOffThread, null, null);
    }

    /**
     * 完整分片构造器：额外接入部分快照暂存与扫描开始回调。
     *
     * @param partialSnapshotStasher 取消/失败时暂存部分快照的回调，可为 {@code null}
     * @param scanStartHook          新扫描开始时清空管线报告状态的回调，可为 {@code null}
     */
    public ScanManager(BatchProcessor batchProcessor,
                       PipelineCollector collector,
                       PipelineApplier applier,
                       long tickBudgetNanos,
                       @Nullable ScanDiagnostics diagnostics,
                       @Nullable AbortListener abortListener,
                       @Nullable ScanCancellation cancellation,
                       @Nullable SolveWorker solveWorker,
                       boolean solveOffThread,
                       @Nullable Consumer<PipelineSnapshot> partialSnapshotStasher,
                       @Nullable Runnable scanStartHook) {
        this(batchProcessor, null, null, collector, applier, tickBudgetNanos, diagnostics,
                abortListener, cancellation, solveWorker, solveOffThread,
                partialSnapshotStasher, scanStartHook);
    }

    /**
     * 便捷分片构造器：使用内部诊断采样器与新建取消令牌。
     */
    public ScanManager(BatchProcessor batchProcessor,
                       PipelineCollector collector,
                       PipelineApplier applier,
                       long tickBudgetNanos,
                       @Nullable SolveWorker solveWorker,
                       boolean solveOffThread) {
        this(batchProcessor, collector, applier, tickBudgetNanos, new ScanDiagnostics(), null,
                new ScanCancellation(), solveWorker, solveOffThread);
    }

    private ScanManager(BatchProcessor batchProcessor,
                        @Nullable SnapshotCollector collector,
                        @Nullable ResultApplier applier,
                        @Nullable PipelineCollector pipelineCollector,
                        @Nullable PipelineApplier pipelineApplier,
                        long tickBudgetNanos,
                        @Nullable ScanDiagnostics diagnostics,
                        @Nullable AbortListener abortListener,
                        @Nullable ScanCancellation cancellation,
                        @Nullable SolveWorker solveWorker,
                        boolean solveOffThread,
                        @Nullable Consumer<PipelineSnapshot> partialSnapshotStasher,
                        @Nullable Runnable scanStartHook) {
        this.batchProcessor = Objects.requireNonNull(batchProcessor, "batchProcessor");
        boolean pipelineMode = pipelineCollector != null || pipelineApplier != null;
        if (pipelineMode) {
            if (pipelineCollector == null || pipelineApplier == null) {
                throw new IllegalArgumentException("分片采集与分片应用回调必须同时提供");
            }
            this.collector = null;
            this.applier = null;
            this.pipelineCollector = pipelineCollector;
            this.pipelineApplier = pipelineApplier;
        } else {
            this.collector = Objects.requireNonNull(collector, "collector");
            this.applier = Objects.requireNonNull(applier, "applier");
            this.pipelineCollector = null;
            this.pipelineApplier = null;
        }
        if (tickBudgetNanos < 0L) {
            throw new IllegalArgumentException("tickBudgetNanos 必须 >= 0，实际为 " + tickBudgetNanos);
        }
        this.tickBudgetNanos = tickBudgetNanos;
        this.diagnostics = diagnostics == null ? new ScanDiagnostics() : diagnostics;
        this.abortListener = abortListener;
        this.cancellation = cancellation == null ? new ScanCancellation() : cancellation;
        this.solveWorker = solveWorker;
        this.solveOffThread = solveOffThread;
        this.partialSnapshotStasher = partialSnapshotStasher;
        this.scanStartHook = scanStartHook;
    }

    /**
     * @return 本次调度器使用的共享取消令牌
     */
    public ScanCancellation cancellation() {
        return cancellation;
    }

    /**
     * @return 本次扫描使用的诊断采样器
     */
    public ScanDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * 设置扫描输入指纹提供器。生产接线必须提供非 {@code null} 实现；测试可保持默认值。
     *
     * @param supplier 指纹与 reload 代次提供器，可为 {@code null}
     */
    public void setFingerprintSupplier(@Nullable FingerprintSupplier supplier) {
        this.fingerprintSupplier = supplier;
    }

    /**
     * 请求开始一次新扫描。
     *
     * @return 成功开始返回 {@code true}；已有扫描在运行返回 {@code false}
     */
    public synchronized boolean requestScan() {
        if (isRunning()) {
            return false;
        }
        cancelOutstandingFuture();
        cancellation.clear();
        snapshot = null;
        pipelineSnapshot = null;
        collectSession = null;
        solveOutcome = null;
        inputSnapshotCaptured = false;
        collectFingerprintBefore = null;
        collectReloadGeneration = 0L;
        lastBatchNanos = 0L;
        diagnostics.reset();
        diagnostics.recordMemory();
        notifyScanStart();
        long now = System.currentTimeMillis();
        progress = new ScanProgress(ScanPhase.COLLECTING_SNAPSHOT, 0, 0, now, now,
                pipelineMode() ? "等待开始分片采集" : "等待采集快照");
        return true;
    }

    /**
     * 请求取消当前扫描。
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

    private boolean pipelineMode() {
        return pipelineCollector != null;
    }

    /**
     * 推进一次扫描状态机。
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
        skipBatchSample = false;
        ScanPhase abortedPhase = null;
        try {
            if (cancellation.isRequested()) {
                capturePartialCollectionForAbort();
                cancelOutstandingFuture();
                cancellation.clear();
                progress = current.advance(ScanPhase.CANCELLED, current.processed(),
                        current.total(), "扫描已取消");
                abortedPhase = ScanPhase.CANCELLED;
            } else {
                batch = batchProcessor.nextBatchSize(lastBatchNanos, tickBudgetNanos);
                // 只有真正消耗批量预算的采集/求解阶段才更新自适应批量；APPLYING/SAVING
                // 的落盘与回调耗时不能持久缩小下一轮采集批量。分片求解阶段只轮询 future，
                // 也不应产生批量样本。
                ScanPhase phase = current.phase();
                batchRecorded = phase == ScanPhase.COLLECTING_SNAPSHOT
                        || (phase == ScanPhase.SOLVING && !pipelineMode());
                switch (phase) {
                    case COLLECTING_SNAPSHOT -> {
                        if (pipelineMode()) {
                            collectPipelineStep(batch);
                        } else {
                            collectStep(batch);
                        }
                    }
                    case SOLVING -> {
                        if (pipelineMode()) {
                            solvePipelineStep();
                        } else {
                            solveStep(batch);
                        }
                    }
                    case APPLYING -> {
                        if (pipelineMode()) {
                            applyPipelineStep();
                        } else {
                            applyStep();
                        }
                    }
                    case SAVING -> saveStep();
                    default -> {
                        // 防御性分支：isRunning() 已经排除了所有终止阶段。
                    }
                }
            }
        } catch (CancellationException cancelled) {
            cancellation.clear();
            progress = progress.advance(ScanPhase.CANCELLED, progress.processed(),
                    progress.total(), "扫描已取消");
            abortedPhase = ScanPhase.CANCELLED;
        } catch (RuntimeException e) {
            ScanProgress failed = progress;
            progress = failed.advance(ScanPhase.FAILED, failed.processed(), failed.total(),
                    failureDetail(e));
            abortedPhase = ScanPhase.FAILED;
        } finally {
            long elapsedNanos = System.nanoTime() - startedNanos;
            lastBatchNanos = elapsedNanos;
            if (batchRecorded && !skipBatchSample) {
                batchProcessor.recordBatch(batch, elapsedNanos);
            }
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

    private void notifyScanStart() {
        Runnable hook = scanStartHook;
        if (hook == null) {
            return;
        }
        try {
            hook.run();
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 扫描开始回调失败，已忽略", failure);
        }
    }

    /**
     * 取消发生在采集阶段时，先完成会话以取得已采集的部分快照，再交给管线暂存。
     *
     * <p>共享取消令牌此时仍处于请求状态，{@link ScanPipeline.CollectSession#finish()} 会在
     * 下一个批次边界看到取消并立即返回部分快照，因此不会把剩余注册表全部扫完。</p>
     */
    private void capturePartialCollectionForAbort() {
        ScanPipeline.CollectSession session = collectSession;
        if (session == null) {
            return;
        }
        collectSession = null;
        try {
            PipelineSnapshot partial = session.finish();
            pipelineSnapshot = partial;
            stashPartialSnapshot(partial);
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 取消时收集部分快照失败，报告将回退到最近一次快照", failure);
        }
    }

    private void stashPartialSnapshot(@Nullable PipelineSnapshot snapshot) {
        Consumer<PipelineSnapshot> stasher = partialSnapshotStasher;
        if (stasher == null || snapshot == null) {
            return;
        }
        try {
            stasher.accept(snapshot);
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 暂存部分快照失败，诊断报告可能缺少本次统计", failure);
        }
    }

    /**
     * 把状态机恢复到空闲，并清除取消标志、快照与计时样本。
     */
    public synchronized void reset() {
        cancelOutstandingFuture();
        cancellation.clear();
        snapshot = null;
        pipelineSnapshot = null;
        collectSession = null;
        solveOutcome = null;
        inputSnapshotCaptured = false;
        collectFingerprintBefore = null;
        collectReloadGeneration = 0L;
        lastBatchNanos = 0L;
        diagnostics.reset();
        progress = ScanProgress.idle();
    }

    /**
     * 在首次采集前捕获输入基线：优先使用廉价 reload 代次，否则退化为完整指纹。
     */
    private void captureInputsIfNeeded() {
        if (inputSnapshotCaptured) {
            return;
        }
        inputSnapshotCaptured = true;
        FingerprintSupplier supplier = fingerprintSupplier;
        if (supplier == null) {
            return;
        }
        reloadTracking = supplier.tracksReloadGeneration();
        if (reloadTracking) {
            collectReloadGeneration = supplier.reloadGeneration();
        } else {
            collectFingerprintBefore = Objects.requireNonNull(
                    supplier.current(), "扫描指纹不能为 null");
        }
    }

    /**
     * 校验扫描输入在采集/求解/应用期间没有变化。
     *
     * @param message 失败时写入扫描进度的中文原因
     */
    private void verifyInputsUnchanged(String message) {
        FingerprintSupplier supplier = fingerprintSupplier;
        if (supplier == null) {
            return;
        }
        if (reloadTracking) {
            long current = supplier.reloadGeneration();
            if (current != collectReloadGeneration) {
                throw new IllegalStateException(message
                        + "（reload 代次 " + collectReloadGeneration + " -> " + current + "）");
            }
        } else {
            ScanFingerprint current = Objects.requireNonNull(
                    supplier.current(), "扫描指纹不能为 null");
            if (!Objects.equals(current, collectFingerprintBefore)) {
                throw new IllegalStateException(message);
            }
        }
    }

    // ---------------------------------------------------------------------
    // 传统一次性路径
    // ---------------------------------------------------------------------

    private void collectStep(int batch) {
        captureInputsIfNeeded();
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

    private void beginSolving() {
        verifyInputsUnchanged("快照采集期间扫描输入发生变化");
        SnapshotData data = snapshot;
        int total = data == null ? 0 : data.recipeCount();
        progress = progress.advance(ScanPhase.SOLVING, 0, total, "开始求解配方");
    }

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
                verifyInputsUnchanged("求解完成时扫描输入发生变化");
        }
    }

    private void applyStep() {
        SnapshotData data = snapshot;
        if (data == null) {
            throw new IllegalStateException("应用阶段缺少快照数据");
        }
        verifyInputsUnchanged("应用结果前扫描输入发生变化");
        applier.apply(data);
        progress = progress.advance(ScanPhase.SAVING, 0, 0, "结果已应用，开始持久化");
    }

    // ---------------------------------------------------------------------
    // 分片采集 / 工作线程求解路径
    // ---------------------------------------------------------------------

    private void collectPipelineStep(int batch) {
        captureInputsIfNeeded();
        if (collectSession == null) {
            if (pipelineCollector == null) {
                throw new IllegalStateException("分片采集回调缺失");
            }
            collectSession = pipelineCollector.begin();
            if (collectSession == null) {
                throw new IllegalStateException("分片采集会话返回 null");
            }
            publishCollectProgress(batch);
        }

        if (collectSession.isDone()) {
            finishCollection();
            return;
        }

        collectSession.step(batch);
        if (cancellation.isRequested()) {
            int processed = collectSession.processedItems();
            int total = collectSession.itemCount();
            capturePartialCollectionForAbort();
            progress = progress.advance(ScanPhase.COLLECTING_SNAPSHOT,
                    processed, total, "采集已请求取消");
            return;
        }
        publishCollectProgress(batch);
        if (collectSession.isDone()) {
            finishCollection();
        }
    }

    private void publishCollectProgress(int batch) {
        if (collectSession == null) {
            return;
        }
        int itemTotal = collectSession.itemCount();
        int recipeTotal = collectSession.recipeCount();
        int processedItems = collectSession.processedItems();
        int processedRecipes = collectSession.processedRecipes();
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
                    "汇总变体与既有值（批 " + batch + "）");
        }
    }

    private void finishCollection() {
        if (collectSession == null) {
            throw new IllegalStateException("采集阶段缺少会话");
        }
        PipelineSnapshot collected = collectSession.finish();
        if (collected == null) {
            throw new IllegalStateException("分片采集会话返回 null 快照");
        }
        pipelineSnapshot = collected;
        collectSession = null;
        // 即使随后在求解/应用中失败，部分诊断报告也能拿到本轮采集的完整统计。
        stashPartialSnapshot(collected);
        verifyInputsUnchanged("快照采集完成时扫描输入发生变化");

        if (solveWorker == null) {
            throw new IllegalStateException("分片求解需要 SolveWorker");
        }
        if (solveOffThread) {
            solveFuture = solveWorker.submit(collected);
            progress = progress.advance(ScanPhase.SOLVING, 0, 0, "求解中（工作线程）");
        } else {
            // 内联求解与采集发生在同一 tick，其耗时不能作为下一轮采集批量的自适应样本。
            skipBatchSample = true;
            solveOutcome = solveWorker.solveNow(collected);
            if (solveOutcome == null) {
                throw new IllegalStateException("同步求解返回 null");
            }
            if (solveOutcome.failed()) {
                throw new IllegalStateException("同步求解失败: " + solveOutcome.failureDetail());
            }
            if (solveOutcome.cancelled() || cancellation.isRequested()) {
                throw new CancellationException("求解已取消");
            }
            verifyInputsUnchanged("求解完成时扫描输入发生变化");
            progress = progress.advance(ScanPhase.APPLYING, 0,
                    solveOutcome.candidateCount(),
                    "求解完成（同步），准备应用 " + solveOutcome.candidateCount() + " 条");
        }
    }

    private void solvePipelineStep() {
        Future<SolveOutcome> future = solveFuture;
        if (future == null) {
            throw new IllegalStateException("求解阶段缺少工作线程任务");
        }
        if (!future.isDone()) {
            progress = progress.advance(ScanPhase.SOLVING, 0, 0, "求解中（工作线程）");
            return;
        }
        try {
            solveOutcome = future.get();
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause() == null ? failure : failure.getCause();
            throw new IllegalStateException("工作线程求解失败: " + failureDetail(cause), cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("求解等待被中断", interrupted);
        } finally {
            solveFuture = null;
        }
        if (solveOutcome == null) {
            throw new IllegalStateException("工作线程求解返回 null");
        }
        if (solveOutcome.failed()) {
            throw new IllegalStateException("工作线程求解失败: " + solveOutcome.failureDetail());
        }
        if (solveOutcome.cancelled() || cancellation.isRequested()) {
            throw new CancellationException("求解已取消");
        }
        verifyInputsUnchanged("求解完成时扫描输入发生变化");
        progress = progress.advance(ScanPhase.APPLYING, 0, solveOutcome.candidateCount(),
                "求解完成，准备应用 " + solveOutcome.candidateCount() + " 条");
    }

    private void applyPipelineStep() {
        if (pipelineSnapshot == null) {
            throw new IllegalStateException("应用阶段缺少快照数据");
        }
        if (solveOutcome == null) {
            throw new IllegalStateException("应用阶段缺少求解结果");
        }
        if (pipelineApplier == null) {
            throw new IllegalStateException("分片应用回调缺失");
        }
        verifyInputsUnchanged("应用结果前扫描输入发生变化");
        int applied = pipelineApplier.apply(pipelineSnapshot, solveOutcome);
        progress = progress.advance(ScanPhase.SAVING, 0, 0,
                "应用 " + applied + " 条，开始持久化");
    }

    private void saveStep() {
        progress = progress.advance(ScanPhase.COMPLETED, 0, 0, "扫描已完成");
    }

    private void cancelOutstandingFuture() {
        Future<SolveOutcome> future = solveFuture;
        solveFuture = null;
        if (future != null) {
            future.cancel(true);
        }
    }

    private static String failureDetail(Throwable error) {
        if (error == null) {
            return "未知错误";
        }
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
