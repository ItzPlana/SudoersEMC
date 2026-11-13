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
 * Single-scan state machine driven by server ticks.
 *
 * <p>A scan's lifecycle is: request scan → sharded snapshot collection → recipe solving → apply results →
 * persist → complete. Each {@link #tick()} advances at most one batch of work in one phase:</p>
 * <ul>
 *   <li>{@link ScanPhase#COLLECTING_SNAPSHOT}: the first tick creates the incremental collection session;
 *       subsequent ticks use {@link BatchProcessor#nextBatchSize(long, long)} to decide the budget and call
 *       {@link ScanPipeline.CollectSession#step(int)};</li>
 *   <li>{@link ScanPhase#SOLVING}: the main thread only polls {@link Future#isDone()} and never blocks waiting;</li>
 *   <li>{@link ScanPhase#APPLYING}: one call to the apply callback completes the safety layer and writes;</li>
 *   <li>{@link ScanPhase#SAVING}: keeps the original persistence phase.</li>
 * </ul>
 *
 * <p><strong>Threading:</strong> snapshot collection ({@link SnapshotCollector} / {@link PipelineCollector})
 * and result application ({@link ResultApplier} / {@link PipelineApplier}) must run on the server thread;
 * only pure computation that does not touch game state may be moved to a worker thread via
 * {@link SolveWorker}. {@link #tick()} is also called on the server thread. {@link #progress()} is published
 * via a {@code volatile} field, so other threads can safely read the latest complete progress;
 * {@link #cancel()} may also be called from other threads.</p>
 *
 * <p>Any runtime exception thrown by a callback is caught and converted to {@link ScanPhase#FAILED}, with
 * the exception message written into the progress detail; an exception is never allowed to pierce the tick
 * loop and break the server main loop.</p>
 */
public final class ScanManager {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/task/ScanManager");

    /**
     * Legacy one-shot collection callback; must run on the server thread.
     */
    @FunctionalInterface
    public interface SnapshotCollector {

        /**
         * Collects the complete snapshot needed for one scan.
         *
         * @return non-null snapshot data
         */
        SnapshotData collect();
    }

    /**
     * Legacy apply callback; must run on the server thread.
     */
    @FunctionalInterface
    public interface ResultApplier {

        /**
         * Writes the solve result to the actual backend.
         *
         * @param data snapshot data produced by the collection phase
         */
        void apply(SnapshotData data);
    }

    /**
     * Sharded collection callback: creates a new incremental session at the start of each scan.
     */
    @FunctionalInterface
    public interface PipelineCollector {

        /**
         * @return a new incremental collection session; must not be {@code null}
         */
        ScanPipeline.CollectSession begin();
    }

    /**
     * Sharded apply callback: applies the pure solve result on the server thread.
     */
    @FunctionalInterface
    public interface PipelineApplier {

        /**
         * @param snapshot collection snapshot
         * @param outcome  worker-thread (or synchronous) solve result
         * @return number of entries actually applied, used only for progress display
         */
        int apply(PipelineSnapshot snapshot, SolveOutcome outcome);
    }

    /**
     * Termination callback when a scan is cancelled or fails.
     *
     * <p>Only triggered for {@link ScanPhase#CANCELLED} and {@link ScanPhase#FAILED}, used to write a
     * partial diagnostic report when the pipeline cannot finish the normal apply phase. Implementations
     * must not throw; even if they do, this class only logs and continues.</p>
     */
    @FunctionalInterface
    public interface AbortListener {

        /**
         * @param phase       termination phase, only {@link ScanPhase#CANCELLED} or {@link ScanPhase#FAILED}
         * @param progress    progress snapshot at termination
         * @param diagnostics this scan's timing and memory samples
         */
        void onScanAborted(ScanPhase phase, ScanProgress progress, ScanDiagnostics diagnostics);
    }

    /**
     * Provider of the current scan input fingerprint and reload generation; must run on the server thread.
     *
     * <p>If an implementation can maintain a monotonically increasing reload generation from a cheap
     * source such as Forge's {@code AddReloadListenerEvent}/{@code TagsUpdatedEvent}, it should override
     * {@link #tracksReloadGeneration()} and {@link #reloadGeneration()}. The scan then reads the generation
     * once at start and compares generations at collection completion, solve completion and before apply,
     * without recomputing the full content fingerprint.</p>
     */
    @FunctionalInterface
    public interface FingerprintSupplier {

        /**
         * @return the full fingerprint of current inputs; must not be {@code null}
         */
        ScanFingerprint current();

        /**
         * Cheap, monotonically increasing reload generation. Should increment on every datapack reload
         * (start and completion).
         *
         * @return the current generation
         */
        default long reloadGeneration() {
            return 0L;
        }

        /**
         * @return {@code true} if {@link #reloadGeneration()} cheaply reflects reload changes
         */
        default boolean tracksReloadGeneration() {
            return false;
        }
    }

    /**
     * Minimal data carrier passed between legacy callbacks.
     *
     * @param label       Chinese label of the snapshot source or batch
     * @param itemCount   number of items collected, must not be negative
     * @param recipeCount number of recipes to solve, must not be negative
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
    /** On cancel/fail, hands the partial snapshot to the pipeline to stash for partial diagnostic reports; may be {@code null}. */
    @Nullable
    private final Consumer<PipelineSnapshot> partialSnapshotStasher;
    /** Clears the pipeline's previous report state when a new scan starts; may be {@code null}. */
    @Nullable
    private final Runnable scanStartHook;

    /** Optional input-consistency check; {@code null} keeps the skip semantics used only by unit tests. */
    @Nullable
    private volatile FingerprintSupplier fingerprintSupplier;
    private boolean reloadTracking;
    @Nullable
    private ScanFingerprint collectFingerprintBefore;
    private long collectReloadGeneration;
    private boolean inputSnapshotCaptured;

    private volatile ScanProgress progress = ScanProgress.idle();
    private final ScanCancellation cancellation;
    /** This tick ran an inline solve; its elapsed time must not be used as a batch sample. */
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
     * Compatibility constructor for older callers: uses an internally created diagnostics collector and
     * registers no termination callback.
     */
    public ScanManager(BatchProcessor batchProcessor,
                       SnapshotCollector collector,
                       ResultApplier applier,
                       long tickBudgetNanos) {
        this(batchProcessor, collector, applier, tickBudgetNanos, new ScanDiagnostics(), null);
    }

    /**
     * Compatibility constructor for older callers.
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
     * Creates a legacy scheduler using a shared cancellation token.
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
     * Creates a sharded scheduler.
     *
     * <p>{@code solveWorker} provides the pure solve function; with {@code solveOffThread=true} it runs on
     * the {@link SolveWorker#THREAD_NAME} thread, with {@code false} it runs synchronously within the same
     * tick. Schedulers created via the old constructors keep the original one-shot collection/apply
     * semantics.</p>
     *
     * @param batchProcessor  batch decision component, must not be {@code null}
     * @param collector       sharded collection callback, must not be {@code null}
     * @param applier         apply callback, must not be {@code null}
     * @param tickBudgetNanos time budget allowed for scanning per tick (nanos), must not be negative
     * @param diagnostics     timing and memory sampler; created when {@code null}
     * @param abortListener   cancel/fail termination callback, may be {@code null}
     * @param cancellation    shared cancellation token, may be {@code null}
     * @param solveWorker     pure solve worker thread; required in sharded mode
     * @param solveOffThread  whether to move the pure solve to a worker thread
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
     * Full sharded constructor: additionally wires partial-snapshot stashing and the scan-start callback.
     *
     * @param partialSnapshotStasher callback to stash a partial snapshot on cancel/fail, may be {@code null}
     * @param scanStartHook          callback to clear pipeline report state when a new scan starts, may be {@code null}
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
     * Convenience sharded constructor: uses an internal diagnostics sampler and a new cancellation token.
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
     * @return the shared cancellation token used by this scheduler
     */
    public ScanCancellation cancellation() {
        return cancellation;
    }

    /**
     * @return the diagnostics sampler used by this scan
     */
    public ScanDiagnostics diagnostics() {
        return diagnostics;
    }

    /**
     * Sets the scan input fingerprint provider. Production wiring must provide a non-{@code null}
     * implementation; tests may keep the default.
     *
     * @param supplier fingerprint and reload generation provider, may be {@code null}
     */
    public void setFingerprintSupplier(@Nullable FingerprintSupplier supplier) {
        this.fingerprintSupplier = supplier;
    }

    /**
     * Requests starting a new scan.
     *
     * @return {@code true} if started successfully; {@code false} if a scan is already running
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
     * Requests cancelling the current scan.
     */
    public void cancel() {
        cancellation.request();
    }

    /**
     * @return the most recently published progress snapshot, safe to read from other threads
     */
    public ScanProgress progress() {
        return progress;
    }

    /**
     * @return whether the current phase belongs to an active scan
     */
    public boolean isRunning() {
        return progress.phase().isActive();
    }

    /**
     * @return whether cancellation has been requested but not yet observed by a tick
     */
    public boolean isCancelRequested() {
        return cancellation.isRequested();
    }

    private boolean pipelineMode() {
        return pipelineCollector != null;
    }

    /**
     * Advances the scan state machine once.
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
                // Only collection/solve phases that actually consume the batch budget update the adaptive batch;
                // APPLYING/SAVING persistence and callback elapsed time must not persistently shrink the next collection batch.
                // The sharded solve phase only polls the future and should not produce a batch sample either.
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
                        // Defensive branch: isRunning() has already excluded all terminal phases.
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
     * When cancellation occurs during collection, first finish the session to obtain the partial snapshot
     * already collected, then hand it to the pipeline to stash.
     *
     * <p>The shared cancellation token is still requested at this point;
     * {@link ScanPipeline.CollectSession#finish()} sees the cancellation at the next batch boundary and
     * returns the partial snapshot immediately, so it does not scan the entire remaining registry.</p>
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
     * Restores the state machine to idle and clears the cancellation flag, snapshots and timing samples.
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
     * Captures the input baseline before the first collection: prefers the cheap reload generation,
     * otherwise falls back to the full fingerprint.
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
     * Verifies the scan inputs did not change during collection/solve/apply.
     *
     * @param message Chinese reason written into scan progress on failure
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
    // Legacy one-shot path
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
    // Sharded collection / worker-thread solve path
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
        // Even if solving/applying later fails, the partial diagnostic report can still get this round's complete collection statistics.
        stashPartialSnapshot(collected);
        verifyInputsUnchanged("快照采集完成时扫描输入发生变化");

        if (solveWorker == null) {
            throw new IllegalStateException("分片求解需要 SolveWorker");
        }
        if (solveOffThread) {
            solveFuture = solveWorker.submit(collected);
            progress = progress.advance(ScanPhase.SOLVING, 0, 0, "求解中（工作线程）");
        } else {
            // Inline solve and collection happen in the same tick; its elapsed time must not be an adaptive sample for the next collection batch.
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
