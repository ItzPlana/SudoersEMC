package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.SolveOutcome;
import com.itzplana.sudoersemc.solver.SolveResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * {@link SolveWorker} 的关闭语义验证。
 */
class SolveWorkerTest {

    @Test
    void shutdownNowWaitsForWorkerToTerminate() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        SolveWorker worker = new SolveWorker(snapshot -> {
            started.countDown();
            // 模拟不响应中断的纯 CPU 求解：忽略 InterruptedException 并继续占用线程。
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException ignored) {
                    // 故意忽略中断，验证 shutdownNow 的 awaitTermination 兜底。
                }
            }
            finished.countDown();
            return emptyOutcome();
        });
        worker.submit(null);
        assertTrue(started.await(2L, TimeUnit.SECONDS), "求解任务应已启动");

        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                release.countDown();
            }
        }, "solve-worker-test-releaser");
        releaser.setDaemon(true);
        releaser.start();

        long startedAtNanos = System.nanoTime();
        worker.shutdownNow();
        long elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L;

        assertTrue(elapsedMillis >= 150L,
                "shutdownNow 必须等待正在退出的工作线程，实际仅耗时 " + elapsedMillis + "ms");
        assertTrue(finished.await(1L, TimeUnit.SECONDS),
                "shutdownNow 返回时工作线程应已终止");
        assertTrue(worker.isShutdown());
    }

    private static SolveOutcome emptyOutcome() {
        return new SolveOutcome(PipelineSnapshot.empty(), List.of(),
                new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0),
                0, 1, 0, 0, 0, Set.of(), 0, false, false, "",
                System.nanoTime(), 0L);
    }
}
