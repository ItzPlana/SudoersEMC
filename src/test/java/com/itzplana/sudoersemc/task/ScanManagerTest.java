package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.pipeline.SolveOutcome;
import com.itzplana.sudoersemc.solver.SolveResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link ScanManager} 状态机与回调异常处理验证。
 *
 * <p>所有用例都使用固定批量 1（需要快速完成时使用较大批量），以便准确观察每个 tick 的
 * 阶段推进。完整流程通过 while 循环驱动，并设置保护上限，避免状态机卡死时测试无限运行。</p>
 */
class ScanManagerTest {

    private static final long TICK_BUDGET_NANOS = 1_000_000L;
    private static final int TICK_GUARD = 100;

    private static ScanManager.SnapshotData data(int itemCount, int recipeCount) {
        return new ScanManager.SnapshotData("主世界", itemCount, recipeCount);
    }

    private static ScanManager manager(BatchProcessor processor,
                                       ScanManager.SnapshotCollector collector,
                                       ScanManager.ResultApplier applier) {
        return new ScanManager(processor, collector, applier, TICK_BUDGET_NANOS);
    }

    @Test
    void requestScanIsRejectedWhileRunning() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> data(1, 1), snapshot -> { });

        assertTrue(manager.requestScan());
        assertTrue(manager.isRunning());
        assertFalse(manager.requestScan(), "活动扫描期间不得重复启动");
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
    }

    @Test
    void tickDoesNothingWhenIdle() {
        AtomicInteger collections = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> {
                    collections.incrementAndGet();
                    return data(1, 1);
                },
                snapshot -> { });

        ScanProgress before = manager.progress();
        manager.tick();

        assertSame(before, manager.progress(), "空闲 tick 不应发布新进度");
        assertEquals(0, collections.get(), "空闲 tick 不应调用采集器");
        assertEquals(ScanPhase.IDLE, manager.progress().phase());
    }

    @Test
    void fullHappyPathReachesCompleted() {
        AtomicInteger collections = new AtomicInteger();
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> {
                    collections.incrementAndGet();
                    return data(3, 2);
                },
                snapshot -> applications.incrementAndGet());

        assertTrue(manager.requestScan());
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertFalse(manager.isRunning());
        assertEquals(1, collections.get(), "采集器只能被调用一次");
        assertEquals(1, applications.get(), "应用器只能被调用一次");
        assertTrue(manager.progress().detail().contains("完成"));
        assertTrue(ticks > 4, "3 个物品 + 2 个配方应当跨越多个 tick");
    }

    @Test
    void cancelIsObservedOnNextTick() {
        AtomicInteger collections = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> {
                    collections.incrementAndGet();
                    return data(5, 5);
                },
                snapshot -> { });

        manager.requestScan();
        manager.cancel();
        assertTrue(manager.isCancelRequested());

        manager.tick();

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
        assertFalse(manager.isCancelRequested(), "取消被观察后应清除标志");
        assertFalse(manager.isRunning());
        assertEquals(0, collections.get(), "取消优先于采集");
    }

    @Test
    void collectorFailureEndsInFailedState() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> {
                    throw new IllegalStateException("采集爆炸");
                },
                snapshot -> { });

        manager.requestScan();

        assertDoesNotThrow(manager::tick, "回调异常不得穿透 tick");
        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("采集爆炸"));
        assertFalse(manager.isRunning());
    }

    @Test
    void applierFailureEndsInFailedState() {
        ScanManager manager = manager(new BatchProcessor(10, false),
                () -> data(0, 0),
                snapshot -> {
                    throw new IllegalArgumentException("应用失败");
                });

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("应用失败"));
        assertFalse(manager.isRunning());
    }

    @Test
    void exceptionWithoutMessageUsesClassName() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> {
                    throw new IllegalStateException();
                },
                snapshot -> { });

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("IllegalStateException"));
    }

    @Test
    void resetReturnsToIdleAndClearsCancelFlag() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> data(1, 1), snapshot -> { });

        manager.requestScan();
        manager.cancel();
        manager.reset();

        assertEquals(ScanPhase.IDLE, manager.progress().phase());
        assertFalse(manager.isCancelRequested());
        assertFalse(manager.isRunning());
        assertTrue(manager.requestScan(), "重置后应允许重新请求扫描");
        assertTrue(manager.isRunning());
    }

    @Test
    void zeroWorkStillCompletes() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> data(0, 0), snapshot -> { });

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertTrue(ticks <= 5, "无物品无配方时应在少量 tick 内完成");
    }

    @Test
    void newScanCanStartAfterCompletion() {
        ScanManager manager = manager(new BatchProcessor(10, false),
                () -> data(0, 0), snapshot -> { });

        manager.requestScan();
        while (manager.isRunning()) {
            manager.tick();
        }
        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());

        assertTrue(manager.requestScan());
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
        assertTrue(manager.isRunning());
    }

    @Test
    void requestScanResetsPreviousProgress() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> data(10, 10), snapshot -> { });

        manager.requestScan();
        manager.tick();
        assertTrue(manager.progress().processed() > 0);

        manager.reset();
        manager.requestScan();

        assertEquals(0, manager.progress().processed());
        assertEquals(0, manager.progress().total());
        assertEquals("等待采集快照", manager.progress().detail());
    }

    @Test
    void diagnosticsRecordTickTimingsAndMemory() {
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        ScanManager manager = new ScanManager(new BatchProcessor(10, false),
                () -> data(0, 0), snapshot -> { }, TICK_BUDGET_NANOS, diagnostics, null);

        assertSame(diagnostics, manager.diagnostics());
        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertTrue(diagnostics.tickCount() > 0, "每个 tick 都应留下耗时样本");
        assertTrue(diagnostics.totalTickNanos() >= 0L);
        assertTrue(diagnostics.maxTickNanos() >= 0L);
        assertTrue(diagnostics.p95TickNanos() >= 0L);
        assertTrue(diagnostics.peakHeapBytes() >= 0L, "tick 边界必须采样堆内存");
        assertTrue(diagnostics.heapBytes() >= 0L);
    }

    @Test
    void cancelInvokesAbortListener() {
        AtomicReference<ScanPhase> observedPhase = new AtomicReference<>();
        AtomicReference<ScanDiagnostics> observedTelemetry = new AtomicReference<>();
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> data(5, 5), snapshot -> { }, TICK_BUDGET_NANOS, diagnostics,
                (phase, progress, telemetry) -> {
                    observedPhase.set(phase);
                    observedTelemetry.set(telemetry);
                });

        manager.requestScan();
        manager.cancel();
        manager.tick();

        assertEquals(ScanPhase.CANCELLED, observedPhase.get(),
                "取消必须触发终止回调，供管线写出部分报告");
        assertSame(diagnostics, observedTelemetry.get());
        assertTrue(diagnostics.tickCount() > 0, "取消的 tick 也应记录耗时");
    }

    @Test
    void collectorFailureInvokesAbortListener() {
        AtomicReference<ScanPhase> observedPhase = new AtomicReference<>();
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> {
                    throw new IllegalStateException("采集爆炸");
                },
                snapshot -> { }, TICK_BUDGET_NANOS, new ScanDiagnostics(),
                (phase, progress, telemetry) -> observedPhase.set(phase));

        manager.requestScan();
        assertDoesNotThrow(manager::tick);

        assertEquals(ScanPhase.FAILED, observedPhase.get(),
                "失败必须触发终止回调，供管线写出部分报告");
    }

    @Test
    void abortListenerExceptionDoesNotEscape() {
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> {
                    throw new IllegalStateException("采集爆炸");
                },
                snapshot -> { }, TICK_BUDGET_NANOS, new ScanDiagnostics(),
                (phase, progress, telemetry) -> {
                    throw new IllegalStateException("报告回调爆炸");
                });

        manager.requestScan();
        assertDoesNotThrow(manager::tick);
        assertEquals(ScanPhase.FAILED, manager.progress().phase());
    }

    @Test
    void sharedCancellationTokenIsVisibleToPipelineAndClearedAfterObservation() {
        ScanCancellation cancellation = new ScanCancellation();
        AtomicInteger collectorSawCancellation = new AtomicInteger();
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> {
                    if (cancellation.isRequested()) {
                        collectorSawCancellation.incrementAndGet();
                    }
                    return data(2, 2);
                },
                snapshot -> { }, TICK_BUDGET_NANOS, new ScanDiagnostics(), null, cancellation);

        manager.requestScan();
        cancellation.request();
        assertTrue(manager.isCancelRequested());

        manager.tick();

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
        assertEquals(0, collectorSawCancellation.get(), "tick 开头的取消检查必须优先于采集");
        assertFalse(manager.isCancelRequested(), "令牌在终止后清除，下一次扫描可复用");

        manager.requestScan();
        manager.tick();
        assertEquals(0, collectorSawCancellation.get(), "新扫描开始后取消信号不应残留");
    }

    @Test
    void cancellationRequestedInsideCollectorIsObservedOnNextTick() {
        ScanCancellation cancellation = new ScanCancellation();
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> {
                    cancellation.request();
                    return data(5, 5);
                },
                snapshot -> { }, TICK_BUDGET_NANOS, new ScanDiagnostics(), null, cancellation);

        manager.requestScan();
        manager.tick();
        assertTrue(manager.isRunning(), "采集回调本身完成，进度仍在采集阶段");

        manager.tick();
        assertEquals(ScanPhase.CANCELLED, manager.progress().phase(),
                "管线内部请求的取消信号应在下一次 tick 被调度器观察");
    }
    // ------------------------------------------------------------------
    // Phase 27：分片采集 + 工作线程求解
    // ------------------------------------------------------------------

    @Test
    void pipelineCollectingSpansAtLeastThreeTicksWithSmallBudget() {
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger applied = new AtomicInteger();
        SolveWorker worker = new SolveWorker(snapshot -> emptyOutcome());
        ScanManager manager = pipelineManager(worker, false,
                pipelineSession(5, 0, steps), applied);

        manager.requestScan();
        int collectingTicks = 0;
        while (manager.progress().phase() == ScanPhase.COLLECTING_SNAPSHOT
                && collectingTicks < TICK_GUARD) {
            manager.tick();
            collectingTicks++;
        }

        assertTrue(collectingTicks >= 3, "小批量下采集必须跨至少 3 个 tick");
        while (manager.isRunning() && collectingTicks < TICK_GUARD) {
            manager.tick();
            collectingTicks++;
        }
        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(1, applied.get(), "应用回调只能执行一次");
        worker.shutdownNow();
    }

    @Test
    void pipelineSolveRunsOnDedicatedWorkerThread() {
        AtomicReference<String> solverThread = new AtomicReference<>();
        SolveWorker worker = new SolveWorker(snapshot -> {
            solverThread.set(Thread.currentThread().getName());
            return emptyOutcome();
        });
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, true,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        boolean sawSolvingDetail = false;
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
            if (manager.progress().phase() == ScanPhase.SOLVING
                    && manager.progress().detail().contains("工作线程")) {
                sawSolvingDetail = true;
            }
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertTrue(solverThread.get() != null
                && solverThread.get().contains(SolveWorker.THREAD_NAME),
                "纯求解必须在 sudoersemc-solver 线程执行，实际为 " + solverThread.get());
        assertTrue(sawSolvingDetail, "SOLVING 进度必须标明工作线程");
        assertEquals(1, applied.get());
        worker.shutdownNow();
    }

    @Test
    void pipelineWorkerFailureEndsInFailedState() {
        SolveWorker worker = new SolveWorker(snapshot -> {
            throw new IllegalStateException("求解爆炸");
        });
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, true,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("求解爆炸"),
                "工作线程异常消息必须写入进度：" + manager.progress().detail());
        assertEquals(0, applied.get(), "失败时不得进入应用阶段");
        worker.shutdownNow();
    }

    @Test
    void pipelineCancelDuringSolvingCancelsFutureAndInterruptsWorker() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        SolveWorker worker = new SolveWorker(snapshot -> {
            started.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException interruption) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return emptyOutcome();
        });
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, true,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        assertTrue(started.await(2L, TimeUnit.SECONDS), "求解任务应在线程池启动");

        manager.cancel();
        manager.tick();

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
        assertTrue(interrupted.await(2L, TimeUnit.SECONDS), "取消必须中断工作线程");
        assertEquals(0, applied.get());
        worker.shutdownNow();
    }

    @Test
    void solveOffThreadFalseCompletesInline() {
        AtomicReference<String> solverThread = new AtomicReference<>();
        SolveWorker worker = new SolveWorker(snapshot -> {
            solverThread.set(Thread.currentThread().getName());
            return emptyOutcome();
        });
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, false,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertFalse(solverThread.get() != null
                        && solverThread.get().contains(SolveWorker.THREAD_NAME),
                "solveOffThread=false 时不应使用工作线程");
        assertEquals(1, applied.get());
        worker.shutdownNow();
    }

    @Test
    void pipelineProgressShowsCollectingBatchDetail() {
        SolveWorker worker = new SolveWorker(snapshot -> emptyOutcome());
        ScanManager manager = pipelineManager(worker, false,
                pipelineSession(3, 0, new AtomicInteger()), new AtomicInteger());

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("采集物品 1/3"),
                "进度必须显示物品批次：" + manager.progress().detail());
        assertTrue(manager.progress().detail().contains("批 1"),
                "进度必须显示当前批量：" + manager.progress().detail());
        worker.shutdownNow();
    }

    @Test
    void pipelineWorkerFailedOutcomeEndsInFailedState() {
        SolveWorker worker = new SolveWorker(snapshot -> outcome(false, true, "生产求解失败"));
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, true,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase(),
                "SolveOutcome.failed() 必须以 FAILED 结束，不能继续 APPLYING");
        assertTrue(manager.progress().detail().contains("生产求解失败"));
        assertEquals(0, applied.get(), "失败结果不得进入应用阶段");
        worker.shutdownNow();
    }

    @Test
    void pipelineWorkerCancelledOutcomeEndsInCancelledState() {
        SolveWorker worker = new SolveWorker(snapshot -> outcome(true, false, ""));
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, true,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase(),
                "SolveOutcome.cancelled() 必须以 CANCELLED 结束");
        assertEquals(0, applied.get(), "取消结果不得进入应用阶段");
        assertFalse(manager.isCancelRequested(), "取消状态被观察后不得残留令牌");
        worker.shutdownNow();
    }

    @Test
    void solveOffThreadFalseFailedOutcomeEndsInFailedState() {
        SolveWorker worker = new SolveWorker(snapshot -> outcome(false, true, "同步求解失败"));
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, false,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase(),
                "内联求解返回 failed 时必须以 FAILED 结束");
        assertTrue(manager.progress().detail().contains("同步求解失败"));
        assertEquals(0, applied.get());
        worker.shutdownNow();
    }

    @Test
    void solveOffThreadFalseCancelledOutcomeEndsInCancelledState() {
        SolveWorker worker = new SolveWorker(snapshot -> outcome(true, false, ""));
        AtomicInteger applied = new AtomicInteger();
        ScanManager manager = pipelineManager(worker, false,
                pipelineSession(1, 0, new AtomicInteger()), applied);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase(),
                "内联求解返回 cancelled 时必须以 CANCELLED 结束");
        assertEquals(0, applied.get());
        assertFalse(manager.isCancelRequested());
        worker.shutdownNow();
    }

    @Test
    void cancelDuringCollectingStashesPartialSnapshot() {
        AtomicReference<PipelineSnapshot> stashed = new AtomicReference<>();
        AtomicInteger finishCalls = new AtomicInteger();
        ScanPipeline.CollectSession session = new ScanPipeline.CollectSession() {
            private int cursor;
            private boolean done;

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public int itemCount() {
                return 5;
            }

            @Override
            public int recipeCount() {
                return 0;
            }

            @Override
            public int processedItems() {
                return cursor;
            }

            @Override
            public int processedRecipes() {
                return 0;
            }

            @Override
            public void step(int budget) {
                cursor = (int) Math.min(5L, (long) cursor + Math.max(1, budget));
                done = cursor >= 5;
            }

            @Override
            public PipelineSnapshot finish() {
                finishCalls.incrementAndGet();
                done = true;
                return PipelineSnapshot.empty();
            }
        };
        SolveWorker worker = new SolveWorker(snapshot -> emptyOutcome());
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> session,
                (snapshot, solveOutcome) -> 0,
                TICK_BUDGET_NANOS, new ScanDiagnostics(), null, new ScanCancellation(),
                worker, true, stashed::set, null);

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
        manager.cancel();
        manager.tick();

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
        assertEquals(1, finishCalls.get(), "取消时必须完成采集会话以取得部分快照");
        assertNotNull(stashed.get(), "部分快照必须交给管线暂存，供取消报告使用");
        worker.shutdownNow();
    }

    @Test
    void cancelRequestedByCollectorStepStashesPartialSnapshot() {
        AtomicReference<PipelineSnapshot> stashed = new AtomicReference<>();
        ScanCancellation cancellation = new ScanCancellation();
        AtomicInteger finishCalls = new AtomicInteger();
        ScanPipeline.CollectSession session = new ScanPipeline.CollectSession() {
            private boolean done;

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public int itemCount() {
                return 5;
            }

            @Override
            public int recipeCount() {
                return 0;
            }

            @Override
            public int processedItems() {
                return 0;
            }

            @Override
            public int processedRecipes() {
                return 0;
            }

            @Override
            public void step(int budget) {
                cancellation.request();
            }

            @Override
            public PipelineSnapshot finish() {
                finishCalls.incrementAndGet();
                done = true;
                return PipelineSnapshot.empty();
            }
        };
        SolveWorker worker = new SolveWorker(snapshot -> emptyOutcome());
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> session,
                (snapshot, solveOutcome) -> 0,
                TICK_BUDGET_NANOS, new ScanDiagnostics(), null, cancellation,
                worker, true, stashed::set, null);

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase(),
                "采集批内请求取消时本 tick 只需记录进度");
        assertEquals(1, finishCalls.get(), "采集批内取消同样必须暂存部分快照");
        assertNotNull(stashed.get());
        manager.tick();
        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
        worker.shutdownNow();
    }

    @Test
    void legacyApplyingAndSavingTicksDoNotRecordBatchSamples() {
        BatchProcessor processor = new BatchProcessor(10, false);
        ScanManager manager = new ScanManager(processor,
                () -> data(0, 0), snapshot -> { },
                TICK_BUDGET_NANOS, new ScanDiagnostics(), null, new ScanCancellation());

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(2L, processor.batchesProcessed(),
                "只有采集与求解阶段可记录批量样本，APPLYING/SAVING 不得污染自适应批量");
    }

    @Test
    void inlineSolveTickDoesNotRecordBatchSample() {
        BatchProcessor processor = new BatchProcessor(1, false);
        SolveWorker worker = new SolveWorker(snapshot -> emptyOutcome());
        ScanManager manager = new ScanManager(processor,
                () -> pipelineSession(0, 0, new AtomicInteger()),
                (snapshot, solveOutcome) -> 0,
                TICK_BUDGET_NANOS, new ScanDiagnostics(), null, new ScanCancellation(),
                worker, false);

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.APPLYING, manager.progress().phase());
        assertEquals(0L, processor.batchesProcessed(),
                "内联求解与采集同 tick 时不得把求解耗时记入批量样本");
        worker.shutdownNow();
    }

    @Test
    void requestScanInvokesPipelineStartHook() {
        AtomicInteger hooks = new AtomicInteger();
        SolveWorker worker = new SolveWorker(snapshot -> emptyOutcome());
        ScanManager manager = new ScanManager(new BatchProcessor(1, false),
                () -> pipelineSession(0, 0, new AtomicInteger()),
                (snapshot, solveOutcome) -> 0,
                TICK_BUDGET_NANOS, new ScanDiagnostics(), null, new ScanCancellation(),
                worker, true, null, hooks::incrementAndGet);

        assertTrue(manager.requestScan());
        assertEquals(1, hooks.get(), "requestScan 必须通知管线清空上一轮报告状态");
        worker.shutdownNow();
    }

    private static SolveOutcome outcome(boolean cancelled, boolean failed, String detail) {
        return new SolveOutcome(PipelineSnapshot.empty(), List.of(),
                new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0),
                0, 1, 0, 0, 0, Set.of(), 0, cancelled, failed, detail,
                System.nanoTime(), 0L);
    }

    private static ScanManager pipelineManager(SolveWorker worker, boolean solveOffThread,
                                               ScanPipeline.CollectSession session,
                                               AtomicInteger applied) {
        return new ScanManager(new BatchProcessor(1, false),
                () -> session,
                (snapshot, outcome) -> {
                    applied.incrementAndGet();
                    return 1;
                },
                TICK_BUDGET_NANOS, new ScanDiagnostics(), null, new ScanCancellation(),
                worker, solveOffThread);
    }

    private static ScanPipeline.CollectSession pipelineSession(int items, int recipes,
                                                               AtomicInteger steps) {
        return new ScanPipeline.CollectSession() {
            private int itemCursor;
            private int recipeCursor;
            private boolean done;

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public int itemCount() {
                return items;
            }

            @Override
            public int recipeCount() {
                return recipes;
            }

            @Override
            public int processedItems() {
                return itemCursor;
            }

            @Override
            public int processedRecipes() {
                return recipeCursor;
            }

            @Override
            public void step(int budget) {
                steps.incrementAndGet();
                int batch = Math.max(1, budget);
                if (itemCursor < items) {
                    itemCursor = (int) Math.min((long) items, (long) itemCursor + batch);
                    done = itemCursor >= items && recipeCursor >= recipes;
                    return;
                }
                if (recipeCursor < recipes) {
                    recipeCursor = (int) Math.min((long) recipes, (long) recipeCursor + batch);
                    done = itemCursor >= items && recipeCursor >= recipes;
                    return;
                }
                done = true;
            }

            @Override
            public PipelineSnapshot finish() {
                done = true;
                return PipelineSnapshot.empty();
            }
        };
    }

    @Test
    void resetDuringSolvingCancelsFutureAndReturnsIdle() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        SolveWorker worker = new SolveWorker(snapshot -> {
            started.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException interruption) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return emptyOutcome();
        });
        ScanManager manager = pipelineManager(worker, true,
                pipelineSession(1, 0, new AtomicInteger()), new AtomicInteger());

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        assertTrue(started.await(2L, TimeUnit.SECONDS));

        manager.cancel();
        manager.reset();

        assertEquals(ScanPhase.IDLE, manager.progress().phase());
        assertFalse(manager.isRunning(), "reset 后旧扫描不得再阻塞重建检查");
        assertFalse(manager.isCancelRequested(), "reset 必须清除取消标志");
        assertTrue(interrupted.await(2L, TimeUnit.SECONDS), "reset 必须取消并中断工作线程");
        worker.shutdownNow();
    }

    private static SolveOutcome emptyOutcome() {
        return new SolveOutcome(PipelineSnapshot.empty(), List.of(),
                new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0),
                0, 1, 0, 0, 0, Set.of(), 0, false, false, "", System.nanoTime(), 0L);
    }
}
