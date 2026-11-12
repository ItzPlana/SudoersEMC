package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.pipeline.SolveOutcome;
import com.itzplana.sudoersemc.solver.SolveResult;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanCancellation;
import com.itzplana.sudoersemc.task.ScanDiagnostics;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import com.itzplana.sudoersemc.task.SolveWorker;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 停服 / 重开服时旧扫描调度器清理的集成级验证。
 */
class SudoersemcLifecycleTest {

    private static final long TICK_BUDGET_NANOS = 1_000_000L;

    @Test
    void shutdownResetStopsActiveSolvingScan() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        SolveWorker worker = blockingWorker(started, interrupted);
        ScanManager manager = manager(worker);

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        assertTrue(started.await(2L, TimeUnit.SECONDS));

        Sudoersemc.resetScanManagerForShutdown(manager);

        assertEquals(ScanPhase.IDLE, manager.progress().phase());
        assertFalse(manager.isRunning());
        assertFalse(manager.isCancelRequested());
        assertTrue(interrupted.await(2L, TimeUnit.SECONDS),
                "停服清理必须取消并中断未完成的求解任务");
        worker.shutdownNow();
    }

    @Test
    void closedWorkerLeavesRunningManagerUntilDefensiveReset() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        SolveWorker worker = blockingWorker(started, interrupted);
        ScanManager manager = manager(worker);

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        assertTrue(started.await(2L, TimeUnit.SECONDS));

        // 模拟旧实现：只关闭 worker，不重置 manager。没有 tick 推进时阶段仍是 SOLVING。
        worker.shutdownNow();
        assertTrue(manager.isRunning(),
                "旧实现下已关闭 worker 的 manager 仍会阻塞 rebuild 的 isRunning 检查");

        // 重开服路径的防御性 reset 必须让重建检查立即通过。
        Sudoersemc.resetScanManagerForShutdown(manager);

        assertFalse(manager.isRunning(), "reset 后 rebuild 不得再被旧扫描判定为进行中");
        assertEquals(ScanPhase.IDLE, manager.progress().phase());
    }

    @Test
    void resetNullManagerIsNoOp() {
        Sudoersemc.resetScanManagerForShutdown(null);
    }

    private static SolveWorker blockingWorker(CountDownLatch started, CountDownLatch interrupted) {
        return new SolveWorker(snapshot -> {
            started.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException interruption) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return emptyOutcome();
        });
    }

    private static ScanManager manager(SolveWorker worker) {
        ScanPipeline.CollectSession session = new ScanPipeline.CollectSession() {
            private boolean done;

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public int itemCount() {
                return 1;
            }

            @Override
            public int recipeCount() {
                return 0;
            }

            @Override
            public int processedItems() {
                return done ? 1 : 0;
            }

            @Override
            public int processedRecipes() {
                return 0;
            }

            @Override
            public void step(int budget) {
                done = true;
            }

            @Override
            public PipelineSnapshot finish() {
                done = true;
                return PipelineSnapshot.empty();
            }
        };
        return new ScanManager(new BatchProcessor(1, false),
                () -> session,
                (snapshot, outcome) -> 0,
                TICK_BUDGET_NANOS, new ScanDiagnostics(), null, new ScanCancellation(),
                worker, true);
    }

    private static SolveOutcome emptyOutcome() {
        return new SolveOutcome(PipelineSnapshot.empty(), List.of(),
                new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0),
                0, 1, 0, 0, 0, Set.of(), 0, false, false, "", System.nanoTime(), 0L);
    }
}
