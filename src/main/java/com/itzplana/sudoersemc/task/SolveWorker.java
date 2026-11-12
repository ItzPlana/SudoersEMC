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
 * 单线程守护线程池，专门执行不接触游戏状态的纯计算求解。
 *
 * <p>线程名固定为 {@value #THREAD_NAME}，便于日志、线程转储与测试断言。提交任务返回
 * {@link Future}，异常通过 {@link Future#get()} 的 {@code ExecutionException} 传回主线程；
 * 本类绝不在工作线程里吞掉异常。</p>
 *
 * <p><strong>线程契约：</strong>求解函数不得访问 Minecraft 注册表、RecipeManager、
 * IProjectEMCProxy 或文件系统。调用方应在服务器线程完成所有权快照、手工值判定器解析与
 * resolver 预计算，再提交不可变快照。</p>
 */
public final class SolveWorker implements AutoCloseable {

    /** 工作线程固定名称。 */
    public static final String THREAD_NAME = "sudoersemc-solver";

    /** 关闭时等待工作线程退出的最长时间（秒）。 */
    private static final long SHUTDOWN_TIMEOUT_SECONDS = 5L;

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/task/SolveWorker");

    /**
     * 纯求解函数。
     */
    @FunctionalInterface
    public interface Solver {

        /**
         * @param snapshot 不可变采集快照，可为 {@code null}
         * @return 纯计算结果
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
     * 提交一次异步求解。
     *
     * @param snapshot 不可变快照，可为 {@code null}
     * @return 求解 future；异常通过 {@code ExecutionException} 传播
     * @throws IllegalStateException 工作线程池已关闭
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
     * 在调用线程同步执行纯求解，供 {@code solveOffThread=false} 的分片模式使用。
     *
     * @param snapshot 不可变快照，可为 {@code null}
     * @return 求解结果
     * @throws IllegalStateException 工作线程池已关闭
     */
    public SolveOutcome solveNow(@Nullable PipelineSnapshot snapshot) {
        if (closed.get()) {
            throw new IllegalStateException("SolveWorker 已关闭，无法执行求解");
        }
        return solver.solve(snapshot);
    }

    /**
     * @return 工作线程池是否已经关闭
     */
    public boolean isShutdown() {
        return closed.get();
    }

    /**
     * 立即关闭线程池并中断正在执行的任务，最多等待 {@value #SHUTDOWN_TIMEOUT_SECONDS} 秒让
     * 工作线程退出。重复调用没有副作用。
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
