package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * {@link ScanManager} 状态机、分片采集协议与回调异常处理验证。
 *
 * <p>所有用例都使用固定批量 1（需要快速完成时使用较大批量），以便准确观察每个 tick 的
 * 阶段推进。{@link ShardedSession} 模拟一个持有游标的采集实现：每次 {@code step} 最多
 * 处理一个批次，因此测试可以断言采集不会在单个 tick 内完成。完整流程通过 while 循环
 * 驱动，并设置保护上限，避免状态机卡死时测试无限运行。</p>
 */
class ScanManagerTest {

    private static final long TICK_BUDGET_NANOS = 1_000_000L;
    private static final int TICK_GUARD = 100;

    private static ScanManager.SnapshotData data(int itemCount, int recipeCount) {
        return new ScanManager.SnapshotData("主世界", itemCount, recipeCount);
    }

    private static ScanFingerprint fingerprint(String itemId) {
        return ScanFingerprint.of(List.of(itemId), List.of(), List.of(), List.of(), List.of());
    }

    /**
     * 测试用分片会话：先按批次处理物品，再按批次处理配方，最后返回快照。
     *
     * <p>{@code maxActualPerStep} 允许模拟“实际处理量小于建议批量”的实现，用于验证
     * {@link BatchProcessor} 记录的是实际工作量而不是建议批量。</p>
     */
    private static final class ShardedSession implements ScanManager.CollectSession {

        private final ScanManager.SnapshotData snapshot;
        private final int maxActualPerStep;
        private int processedItems;
        private int processedRecipes;
        private int steps;

        ShardedSession(ScanManager.SnapshotData snapshot) {
            this(snapshot, Integer.MAX_VALUE);
        }

        ShardedSession(ScanManager.SnapshotData snapshot, int maxActualPerStep) {
            this.snapshot = snapshot;
            this.maxActualPerStep = Math.max(1, maxActualPerStep);
        }

        @Override
        public int itemCount() {
            return snapshot.itemCount();
        }

        @Override
        public int recipeCount() {
            return snapshot.recipeCount();
        }

        @Override
        public int processedItems() {
            return processedItems;
        }

        @Override
        public int processedRecipes() {
            return processedRecipes;
        }

        @Override
        public boolean isDone() {
            return processedItems >= snapshot.itemCount()
                    && processedRecipes >= snapshot.recipeCount();
        }

        @Override
        public ScanManager.StepResult step(int maxItems) {
            steps++;
            int effective = Math.min(Math.max(1, maxItems), maxActualPerStep);
            int actual = 0;
            if (processedItems < snapshot.itemCount()) {
                int take = Math.min(effective, snapshot.itemCount() - processedItems);
                processedItems += take;
                actual += take;
            } else if (processedRecipes < snapshot.recipeCount()) {
                int take = Math.min(effective, snapshot.recipeCount() - processedRecipes);
                processedRecipes += take;
                actual += take;
            }
            return new ScanManager.StepResult(actual, isDone());
        }

        @Override
        public ScanManager.SnapshotData finish() {
            return snapshot;
        }

        int steps() {
            return steps;
        }
    }

    private static ScanManager.SnapshotCollector collector(ScanManager.SnapshotData data) {
        return () -> new ShardedSession(data);
    }

    private static ScanManager.SnapshotCollector collector(
            Supplier<ScanManager.SnapshotData> supplier) {
        return () -> new ShardedSession(supplier.get());
    }

    private static ScanManager manager(BatchProcessor processor,
                                       ScanManager.SnapshotCollector collector,
                                       ScanManager.ResultApplier applier) {
        return new ScanManager(processor, collector, applier, TICK_BUDGET_NANOS, null);
    }

    private static ScanManager manager(BatchProcessor processor,
                                       ScanManager.SnapshotCollector collector,
                                       ScanManager.ResultApplier applier,
                                       ScanManager.FingerprintSupplier fingerprintSupplier) {
        return new ScanManager(processor, collector, applier, TICK_BUDGET_NANOS, fingerprintSupplier);
    }

    @Test
    void requestScanIsRejectedWhileRunning() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                collector(data(1, 1)), snapshot -> { });

        assertTrue(manager.requestScan());
        assertTrue(manager.isRunning());
        assertFalse(manager.requestScan(), "活动扫描期间不得重复启动");
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
    }

    @Test
    void tickDoesNothingWhenIdle() {
        AtomicInteger collections = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(1, false),
                collector(() -> {
                    collections.incrementAndGet();
                    return data(1, 1);
                }),
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
                collector(() -> {
                    collections.incrementAndGet();
                    return data(3, 2);
                }),
                snapshot -> applications.incrementAndGet());

        assertTrue(manager.requestScan());
        int ticks = 0;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertFalse(manager.isRunning());
        assertEquals(1, collections.get(), "一次扫描只能创建一个采集会话");
        assertEquals(1, applications.get(), "应用器只能被调用一次");
        assertTrue(manager.progress().detail().contains("完成"));
        assertTrue(ticks > 4, "3 个物品 + 2 个配方应当跨越多个 tick");
    }

    @Test
    void cancelIsObservedOnNextTick() {
        AtomicInteger collections = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(1, false),
                collector(() -> {
                    collections.incrementAndGet();
                    return data(5, 5);
                }),
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
    void cancelDuringCollectionIsObservedOnNextTick() {
        AtomicInteger collections = new AtomicInteger();
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(1, false),
                collector(() -> {
                    collections.incrementAndGet();
                    return data(5, 5);
                }),
                snapshot -> applications.incrementAndGet());

        manager.requestScan();
        manager.tick();
        assertTrue(manager.isRunning(), "第一次 tick 后仍应处于采集阶段");
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
        assertEquals(1, collections.get(), "首次 tick 应创建采集会话");

        manager.cancel();
        manager.tick();

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
        assertEquals(1, collections.get(), "取消后不得再次调用采集器");
        assertEquals(0, applications.get(), "取消后不得调用应用器");
    }

    @Test
    void cancelFromAnotherThreadIsObservedOnNextTick() throws InterruptedException {
        ScanManager manager = manager(new BatchProcessor(1, false),
                collector(data(100, 100)), snapshot -> { });

        manager.requestScan();
        manager.tick();

        Thread canceller = new Thread(manager::cancel, "scan-canceller");
        canceller.start();
        canceller.join(5_000L);
        assertFalse(canceller.isAlive(), "取消线程必须结束");

        manager.tick();
        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
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
                collector(data(0, 0)),
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
    void applyIsRejectedWhenFingerprintChangesAfterCollection() {
        AtomicReference<ScanFingerprint> current = new AtomicReference<>(fingerprint("minecraft:stone"));
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(10, false),
                collector(data(0, 0)),
                snapshot -> applications.incrementAndGet(),
                current::get);

        manager.requestScan();
        manager.tick();
        current.set(fingerprint("minecraft:dirt"));
        manager.tick();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertEquals(0, applications.get(), "指纹不一致时不得应用过期结果");
        assertTrue(manager.progress().detail().contains("重新扫描"));
    }

    @Test
    void applyProceedsWhenFingerprintStaysStable() {
        AtomicReference<ScanFingerprint> current = new AtomicReference<>(fingerprint("minecraft:stone"));
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(10, false),
                collector(data(0, 0)),
                snapshot -> applications.incrementAndGet(),
                current::get);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks++ < TICK_GUARD) {
            manager.tick();
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(1, applications.get(), "指纹稳定时应正常应用一次");
    }

    @Test
    void fingerprintChangeDuringCollectionFailsScan() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(10, false),
                collector(data(0, 0)),
                snapshot -> applications.incrementAndGet(),
                () -> fingerprint(calls.getAndIncrement() == 0 ? "minecraft:stone" : "minecraft:dirt"));

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertEquals(0, applications.get(), "采集期间输入变化时不得进入应用阶段");
        assertTrue(manager.progress().detail().contains("采集期间"));
    }

    @Test
    void fingerprintChangeBetweenCollectionTicksFailsScan() {
        AtomicReference<ScanFingerprint> current = new AtomicReference<>(fingerprint("minecraft:stone"));
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(2, false),
                collector(data(4, 0)),
                snapshot -> applications.incrementAndGet(),
                current::get);

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
        assertEquals(2, manager.progress().processed());

        current.set(fingerprint("minecraft:dirt"));
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertEquals(0, applications.get(), "跨 tick 采集期间输入变化时不得应用结果");
        assertTrue(manager.progress().detail().contains("采集期间"));
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
                collector(data(1, 1)), snapshot -> { });

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
    void reentrantTickAndRequestScanFromCallbackAreIgnored() {
        AtomicInteger collections = new AtomicInteger();
        AtomicBoolean nestedRequestResult = new AtomicBoolean(true);
        ScanManager[] holder = new ScanManager[1];
        holder[0] = manager(new BatchProcessor(10, false),
                collector(() -> {
                    collections.incrementAndGet();
                    holder[0].tick();
                    nestedRequestResult.set(holder[0].requestScan());
                    return data(0, 0);
                }),
                snapshot -> { });

        holder[0].requestScan();
        holder[0].tick();

        assertEquals(1, collections.get(), "重入 tick 不得再次调用采集器");
        assertFalse(nestedRequestResult.get(), "回调内 requestScan 必须被拒绝");
        assertEquals(ScanPhase.SOLVING, holder[0].progress().phase());
    }

    @Test
    void resetFromCallbackIsRejected() {
        ScanManager[] holder = new ScanManager[1];
        holder[0] = manager(new BatchProcessor(10, false),
                collector(() -> {
                    assertThrows(IllegalStateException.class, () -> holder[0].reset());
                    return data(0, 0);
                }),
                snapshot -> { });

        holder[0].requestScan();
        assertDoesNotThrow(holder[0]::tick, "回调内 reset 异常应由断言捕获，不穿透 tick");
        assertEquals(ScanPhase.SOLVING, holder[0].progress().phase());
    }

    @Test
    void zeroWorkStillCompletes() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                collector(data(0, 0)), snapshot -> { });

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
    void adaptiveSamplesAreOnlyRecordedForCollection() {
        BatchProcessor processor = new BatchProcessor(10, true);
        ScanManager manager = manager(processor, collector(data(1, 1)), snapshot -> { });

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && manager.progress().phase() != ScanPhase.APPLYING
                && ticks++ < TICK_GUARD) {
            manager.tick();
        }
        assertEquals(ScanPhase.APPLYING, manager.progress().phase());
        long batchesBeforeApply = processor.batchesProcessed();
        assertEquals(2L, batchesBeforeApply,
                "只有 2 个真实采集批次（物品 + 配方）应计入自适应样本；求解空转不得记账");

        while (manager.isRunning() && ticks++ < TICK_GUARD) {
            manager.tick();
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(batchesBeforeApply, processor.batchesProcessed(),
                "应用与持久化阶段不得写入自适应样本");
        assertEquals(1, processor.currentBatchSize(),
                "记录的批量必须是实际处理量 1，而不是建议批量 10");
    }

    @Test
    void collectionIsShardedAcrossTicks() {
        ShardedSession session = new ShardedSession(data(5, 0));
        AtomicInteger begun = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(2, false),
                () -> {
                    begun.incrementAndGet();
                    return session;
                },
                snapshot -> { });

        assertTrue(manager.requestScan());
        manager.tick();
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());
        assertEquals(2, manager.progress().processed(), "单个 tick 只能推进一个批次");
        assertEquals(5, manager.progress().total());
        assertEquals(1, session.steps());
        assertTrue(manager.isRunning(), "5 个物品不应在一个 tick 内完成");

        manager.tick();
        assertEquals(4, manager.progress().processed());
        assertEquals(2, session.steps());
        assertTrue(manager.isRunning(), "两个批次后仍未完成");

        int ticks = 2;
        while (manager.isRunning() && ticks < TICK_GUARD) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(5, session.processedItems(), "所有物品最终都应被处理");
        assertEquals(1, begun.get(), "一次扫描只创建一次采集会话");
    }

    @Test
    void recordBatchUsesActualProcessedNotSuggestedBatch() {
        BatchProcessor processor = new BatchProcessor(10, true);
        ShardedSession session = new ShardedSession(data(3, 0), 1);
        ScanManager manager = manager(processor, () -> session, snapshot -> { });

        manager.requestScan();
        manager.tick();

        assertEquals(1, session.processedItems(), "会话只实际处理 1 个元素");
        assertEquals(1L, processor.batchesProcessed());
        assertEquals(1, processor.currentBatchSize(),
                "recordBatch 必须记录实际处理量 1，而不是建议批量 10");

        manager.tick();
        manager.tick();

        assertEquals(3, session.processedItems());
        assertEquals(3L, processor.batchesProcessed());
        assertEquals(1, processor.currentBatchSize());
    }

    @Test
    void zeroWorkScanDoesNotPolluteAdaptiveSamples() {
        BatchProcessor processor = new BatchProcessor(10, true);
        ScanManager manager = manager(processor, collector(data(0, 0)), snapshot -> { });

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks++ < TICK_GUARD) {
            manager.tick();
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(0L, processor.batchesProcessed(), "没有实际处理元素时不应记录自适应样本");
        assertEquals(10, processor.currentBatchSize(), "无工作扫描不得缩小批量");
    }

    @Test
    void sessionExceedingStepBudgetFailsScan() {
        ScanManager.CollectSession rogue = new ScanManager.CollectSession() {
            @Override
            public int itemCount() {
                return 10;
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
            public boolean isDone() {
                return false;
            }

            @Override
            public ScanManager.StepResult step(int maxItems) {
                return new ScanManager.StepResult(maxItems + 1, false);
            }

            @Override
            public ScanManager.SnapshotData finish() {
                return data(10, 0);
            }
        };
        ScanManager manager = manager(new BatchProcessor(2, false),
                () -> rogue, snapshot -> { });

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("超出单批预算"));
    }

    @Test
    void nullCollectorSessionFailsScan() {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> null, snapshot -> { });

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("null 会话"));
    }

    @Test
    void newScanCanStartAfterCompletion() {
        ScanManager manager = manager(new BatchProcessor(10, false),
                collector(data(0, 0)), snapshot -> { });

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks++ < TICK_GUARD) {
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
                collector(data(10, 10)), snapshot -> { });

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
    void saveHandlerRunsOnceInSavingStage() {
        AtomicInteger saves = new AtomicInteger();
        ScanManager manager = new ScanManager(
                new BatchProcessor(10, false),
                collector(data(1, 1)),
                snapshot -> { },
                TICK_BUDGET_NANOS,
                () -> fingerprint("minecraft:dirt"),
                saves::incrementAndGet);

        assertTrue(manager.requestScan());
        for (int tick = 0; tick < TICK_GUARD && manager.isRunning(); tick++) {
            manager.tick();
        }

        assertEquals(1, saves.get(), "save handler must run exactly once");
        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
    }

    @Test
    void saveHandlerFailureTurnsScanIntoFailed() {
        ScanManager manager = new ScanManager(
                new BatchProcessor(10, false),
                collector(data(1, 1)),
                snapshot -> { },
                TICK_BUDGET_NANOS,
                () -> fingerprint("minecraft:dirt"),
                () -> {
                    throw new IllegalStateException("disk full");
                });

        assertTrue(manager.requestScan());
        for (int tick = 0; tick < TICK_GUARD && manager.isRunning(); tick++) {
            manager.tick();
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("disk full"));
    }

    @Test
    void solveIdleDoesNotGrowAdaptiveBatch() {
        BatchProcessor processor = new BatchProcessor(10, true);
        ScanManager.ApplyTask neverSolved = new ScanManager.ApplyTask() {
            @Override
            public boolean isSolveComplete() {
                return false;
            }

            @Override
            public void advanceSolve(long deadlineNanos) {
            }

            @Override
            public int stage(int maxItems, long deadlineNanos) {
                return 0;
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public int solvedCount() {
                return 0;
            }

            @Override
            public int unresolvedCount() {
                return 0;
            }
        };
        ScanManager manager = ScanManager.staged(processor, collector(data(0, 0)),
                snapshot -> neverSolved, TICK_BUDGET_NANOS, null, null);

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        long samplesBefore = processor.batchesProcessed();
        int batchBefore = processor.currentBatchSize();

        for (int tick = 0; tick < 50 && manager.progress().phase() == ScanPhase.SOLVING; tick++) {
            manager.tick();
        }

        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        assertEquals(samplesBefore, processor.batchesProcessed(),
                "求解空转 tick 不得写入自适应样本");
        assertEquals(batchBefore, processor.currentBatchSize(),
                "求解空转不得把自适应批量推到上限");

        manager.cancel();
        manager.tick();
        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
    }

    @Test
    void stagedApplyIsShardedAcrossTicks() {
        AtomicInteger stagedTotal = new AtomicInteger();
        AtomicInteger maxStep = new AtomicInteger();
        ScanManager.ApplyTask task = new ScanManager.ApplyTask() {
            private boolean done;

            @Override
            public boolean isSolveComplete() {
                return true;
            }

            @Override
            public void advanceSolve(long deadlineNanos) {
            }

            @Override
            public int stage(int maxItems, long deadlineNanos) {
                int take = Math.min(maxItems, 10 - stagedTotal.get());
                maxStep.accumulateAndGet(take, Math::max);
                stagedTotal.addAndGet(take);
                if (stagedTotal.get() >= 10) {
                    done = true;
                }
                return take;
            }

            @Override
            public boolean isDone() {
                return done;
            }

            @Override
            public int solvedCount() {
                return 10;
            }

            @Override
            public int unresolvedCount() {
                return 0;
            }
        };
        ScanManager manager = ScanManager.staged(new BatchProcessor(3, false),
                collector(data(0, 0)), snapshot -> task, TICK_BUDGET_NANOS, null, null);

        manager.requestScan();
        manager.tick();
        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        manager.tick();
        assertEquals(ScanPhase.APPLYING, manager.progress().phase());

        manager.tick();
        assertEquals(3, stagedTotal.get(), "单 tick 只能写入一个批次");
        assertEquals(ScanPhase.APPLYING, manager.progress().phase());

        int ticks = 0;
        while (manager.isRunning() && ticks++ < TICK_GUARD) {
            manager.tick();
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(10, stagedTotal.get());
        assertTrue(maxStep.get() <= 3, "每个 tick 的 staging 不得超过批量上限");
    }

    @Test
    void stagedSaveIsPolledAcrossTicks() {
        AtomicInteger advances = new AtomicInteger();
        ScanManager.SaveTask save = deadlineNanos -> advances.incrementAndGet() >= 3;
        ScanManager manager = ScanManager.staged(new BatchProcessor(10, false),
                collector(data(0, 0)),
                snapshot -> ScanManager.ApplyTask.completed(0, 0),
                TICK_BUDGET_NANOS, null, () -> save);

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks++ < TICK_GUARD) {
            manager.tick();
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(3, advances.get(), "SAVING 阶段每 tick 只轮询一次，完成前保持 SAVING");
    }

    @Test
    void collectionStepReceivesDeadline() {
        AtomicReference<Long> seenDeadline = new AtomicReference<>();
        ScanManager.CollectSession session = new ScanManager.CollectSession() {
            private int processed;

            @Override
            public int itemCount() {
                return 10;
            }

            @Override
            public int recipeCount() {
                return 0;
            }

            @Override
            public int processedItems() {
                return processed;
            }

            @Override
            public int processedRecipes() {
                return 0;
            }

            @Override
            public boolean isDone() {
                return false;
            }

            @Override
            public ScanManager.StepResult step(int maxItems) {
                return step(maxItems, Long.MAX_VALUE);
            }

            @Override
            public ScanManager.StepResult step(int maxItems, long deadlineNanos) {
                seenDeadline.set(deadlineNanos);
                processed++;
                return new ScanManager.StepResult(1, false);
            }

            @Override
            public ScanManager.SnapshotData finish() {
                return data(10, 0);
            }
        };
        long before = System.nanoTime();
        ScanManager manager = manager(new BatchProcessor(5, false),
                () -> session, snapshot -> { });

        manager.requestScan();
        manager.tick();

        Long deadline = seenDeadline.get();
        assertTrue(deadline != null && deadline > before,
                "采集会话必须收到本 tick 的绝对截止时间");
        assertEquals(1, manager.progress().processed());
    }

    @Test
    void reloadDuringCollectionFailsScan() {
        ReloadTrackingFingerprints fingerprints = new ReloadTrackingFingerprints();
        ShardedSession session = new ShardedSession(data(4, 0));
        ScanManager manager = ScanManager.staged(new BatchProcessor(2, false),
                () -> session,
                snapshot -> ScanManager.ApplyTask.completed(0, 0),
                TICK_BUDGET_NANOS, fingerprints, null);

        assertTrue(manager.requestScan());
        manager.tick();
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());

        fingerprints.reload();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("采集期间"));
    }

    @Test
    void reloadAfterSolveBeforeFirstStagingTickFailsScan() {
        ReloadTrackingFingerprints fingerprints = new ReloadTrackingFingerprints();
        CountingApplyTask task = new CountingApplyTask(10, 3);
        ScanManager manager = ScanManager.staged(new BatchProcessor(3, false),
                collector(data(0, 0)), snapshot -> task, TICK_BUDGET_NANOS,
                fingerprints, null);

        assertTrue(manager.requestScan());
        manager.tick();
        assertEquals(ScanPhase.SOLVING, manager.progress().phase());
        manager.tick();
        assertEquals(ScanPhase.APPLYING, manager.progress().phase(),
                "求解完成且代次未变化时应进入 APPLYING");

        fingerprints.reload();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertEquals(0, task.stageCalls(), "求解完成后 reload，首个 staging tick 不得写入");
        assertTrue(task.cancelled(), "失败后必须取消应用任务");
        assertTrue(manager.progress().detail().contains("重新扫描"));
    }

    @Test
    void reloadDuringStagingAbortsRemainingBatches() {
        ReloadTrackingFingerprints fingerprints = new ReloadTrackingFingerprints();
        CountingApplyTask task = new CountingApplyTask(9, 3);
        ScanManager manager = ScanManager.staged(new BatchProcessor(3, false),
                collector(data(0, 0)), snapshot -> task, TICK_BUDGET_NANOS,
                fingerprints, null);

        assertTrue(manager.requestScan());
        manager.tick();
        manager.tick();
        manager.tick();
        assertEquals(ScanPhase.APPLYING, manager.progress().phase());
        assertEquals(3, task.staged(), "首个 staging 批次应已写入 3 条");

        fingerprints.reload();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertEquals(3, task.staged(), "reload 后不得继续写入后续批次");
        assertTrue(task.cancelled(), "失败后必须取消应用任务并触发回滚");
    }

    @Test
    void reloadTrackingSupplierNeverRecomputesFullFingerprint() {
        ReloadTrackingFingerprints fingerprints = new ReloadTrackingFingerprints();
        CountingApplyTask task = new CountingApplyTask(9, 3);
        ScanManager manager = ScanManager.staged(new BatchProcessor(3, false),
                collector(data(0, 0)), snapshot -> task, TICK_BUDGET_NANOS,
                fingerprints, null);

        assertTrue(manager.requestScan());
        for (int tick = 0; tick < TICK_GUARD && manager.isRunning(); tick++) {
            manager.tick();
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(0, fingerprints.fullCalls(),
                "支持 reload 代次时不得在采集/求解/应用期间重新计算完整指纹");
    }

    @Test
    void stagedSaveFailureTurnsScanIntoFailed() {
        ScanManager.SaveTask failing = deadlineNanos -> {
            throw new IllegalStateException("磁盘写入失败");
        };
        ScanManager manager = ScanManager.staged(new BatchProcessor(10, false),
                collector(data(0, 0)),
                snapshot -> ScanManager.ApplyTask.completed(0, 0),
                TICK_BUDGET_NANOS, null, () -> failing);

        assertTrue(manager.requestScan());
        for (int tick = 0; tick < TICK_GUARD && manager.isRunning(); tick++) {
            manager.tick();
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertTrue(manager.progress().detail().contains("磁盘写入失败"));
    }

    /**
     * 支持廉价 reload 代次的指纹提供器。{@link #reload()} 模拟一次 {@code /reload}；
     * {@link #fullCalls()} 用于证明扫描过程中不会重复计算完整指纹。
     */
    private static final class ReloadTrackingFingerprints
            implements ScanManager.FingerprintSupplier {

        private final AtomicLong generation = new AtomicLong();
        private final AtomicInteger fullCalls = new AtomicInteger();

        @Override
        public ScanFingerprint current() {
            fullCalls.incrementAndGet();
            return fingerprint("minecraft:stone");
        }

        @Override
        public long reloadGeneration() {
            return generation.get();
        }

        @Override
        public boolean tracksReloadGeneration() {
            return true;
        }

        void reload() {
            generation.incrementAndGet();
        }

        int fullCalls() {
            return fullCalls.get();
        }
    }

    /**
     * 计数用分片应用任务：每次 stage 最多写入 {@code perStage} 条。
     */
    private static final class CountingApplyTask implements ScanManager.ApplyTask {

        private final int total;
        private final int perStage;
        private int staged;
        private int stageCalls;
        private boolean done;
        private boolean cancelled;

        CountingApplyTask(int total, int perStage) {
            this.total = total;
            this.perStage = perStage;
        }

        @Override
        public boolean isSolveComplete() {
            return true;
        }

        @Override
        public void advanceSolve(long deadlineNanos) {
        }

        @Override
        public int stage(int maxItems, long deadlineNanos) {
            if (done || cancelled) {
                return 0;
            }
            stageCalls++;
            int take = Math.min(Math.min(maxItems, perStage), total - staged);
            staged += take;
            if (staged >= total) {
                done = true;
            }
            return take;
        }

        @Override
        public boolean isDone() {
            return done;
        }

        @Override
        public int solvedCount() {
            return total;
        }

        @Override
        public int unresolvedCount() {
            return 0;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        int stageCalls() {
            return stageCalls;
        }

        int staged() {
            return staged;
        }

        boolean cancelled() {
            return cancelled;
        }
    }
}
