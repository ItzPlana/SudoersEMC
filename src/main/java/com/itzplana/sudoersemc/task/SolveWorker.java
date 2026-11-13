package com.itzplana.sudoersemc.task;

import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.SolveOutcome;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single-thread daemon pool dedicated to pure computation solving that never touches game state.
 *
 * <p>The thread name is fixed at {@value #THREAD_NAME} for logs, thread dumps and test assertions.
 * Submitting a task returns a {@link Future}; exceptions are passed back to the main thread via
 * {@link Future#get()}'s {@code ExecutionException}; this class never swallows exceptions on the worker
 * thread.</p>
 *
 * <p><strong>Thread contract:</strong> the solve function must not access the Minecraft registry,
 * RecipeManager, IProjectEMCProxy or the file system. Callers should complete the ownership snapshot,
 * manual-value detector resolution and resolver pre-computation on the server thread, then submit an
 * immutable snapshot.</p>
 */
public final class SolveWorker implements AutoCloseable {

    /** Fixed worker thread name. */
    public static final String THREAD_NAME = "sudoersemc-solver";

    /** Maximum time (seconds) to wait for the worker thread to exit on shutdown. */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/task/SolveWorker");

    /**
     * Pure solve function.
     */
    @FunctionalInterface
    public interface Solver {

        /**
         * @param snapshot immutable collection snapshot, may be {@code null}
         * @return the pure computation result
         */
        SolveOutcome solve(@Nullable PipelineSnapshot snapshot);
    }

    private final Solver solver;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SolveWorker(Solver solver) {
        this.solver = Objects.requireNonNull(solver, "solver");
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Submits one asynchronous solve.
     *
     * @param snapshot immutable snapshot, may be {@code null}
     * @return the solve future; exceptions propagate via {@code ExecutionException}
     * @throws IllegalStateException the worker pool is already closed
     */
    public Future<SolveOutcome> submit(@Nullable PipelineSnapshot snapshot) {
        if (closed.get()) {
            throw new IllegalStateException("SolveWorker 已关闭，无法提交新的求解任务");
        }
        try {
            return executor.submit(() -> solver.solve(snapshot));
        } catch (RejectedExecutionException rejection) {
            throw new IllegalStateException("SolveWorker 已关闭，无法提交新的求解任务", rejection);
        }
    }

    /**
     * Runs the pure solve synchronously on the calling thread, for the sharded mode with
     * {@code solveOffThread=false}.
     *
     * @param snapshot immutable snapshot, may be {@code null}
     * @return the solve result
     * @throws IllegalStateException the worker pool is already closed
     */
    public SolveOutcome solveNow(@Nullable PipelineSnapshot snapshot) {
        if (closed.get()) {
            throw new IllegalStateException("SolveWorker 已关闭，无法执行求解");
        }
        return solver.solve(snapshot);
    }

    /**
     * @return whether the worker pool is already closed
     */
    public boolean isShutdown() {
        return closed.get();
    }

    /**
     * Immediately closes the pool and interrupts running tasks, waiting at most
     * {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds for the worker thread to exit. Repeated calls have no
     * side effect.
     */
    public void shutdownNow() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
            try {
                if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    LOGGER.warn("SudoersEMC 求解工作线程未在 {} 秒内终止", SHUTDOWN_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                LOGGER.warn("SudoersEMC 等待求解工作线程终止时被中断");
            }
            LOGGER.debug("SudoersEMC 求解工作线程已关闭");
        }
    }

    @Override
    public void close() {
        shutdownNow();
    }
}
