package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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

    private static ScanFingerprint fingerprint(String itemId) {
        return ScanFingerprint.of(List.of(itemId), List.of(), List.of(), List.of(), List.of());
    }

    private static ScanManager manager(BatchProcessor processor,
                                       ScanManager.SnapshotCollector collector,
                                       ScanManager.ResultApplier applier) {
        return new ScanManager(processor, collector, applier, TICK_BUDGET_NANOS);
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
    void cancelDuringCollectionIsObservedOnNextTick() {
        AtomicInteger collections = new AtomicInteger();
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> {
                    collections.incrementAndGet();
                    return data(5, 5);
                },
                snapshot -> applications.incrementAndGet());

        manager.requestScan();
        manager.tick();
        assertTrue(manager.isRunning(), "第一次 tick 后仍应处于采集阶段");
        assertEquals(ScanPhase.COLLECTING_SNAPSHOT, manager.progress().phase());

        manager.cancel();
        manager.tick();

        assertEquals(ScanPhase.CANCELLED, manager.progress().phase());
        assertEquals(1, collections.get(), "取消后不得再次调用采集器");
        assertEquals(0, applications.get(), "取消后不得调用应用器");
    }

    @Test
    void cancelFromAnotherThreadIsObservedOnNextTick() throws InterruptedException {
        ScanManager manager = manager(new BatchProcessor(1, false),
                () -> data(100, 100), snapshot -> { });

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
    void applyIsRejectedWhenFingerprintChangesAfterCollection() {
        AtomicReference<ScanFingerprint> current = new AtomicReference<>(fingerprint("minecraft:stone"));
        AtomicInteger applications = new AtomicInteger();
        ScanManager manager = manager(new BatchProcessor(10, false),
                () -> data(0, 0),
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
                () -> data(0, 0),
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
                () -> data(0, 0),
                snapshot -> applications.incrementAndGet(),
                () -> fingerprint(calls.getAndIncrement() == 0 ? "minecraft:stone" : "minecraft:dirt"));

        manager.requestScan();
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase());
        assertEquals(0, applications.get(), "采集期间输入变化时不得进入应用阶段");
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
    void reentrantTickAndRequestScanFromCallbackAreIgnored() {
        AtomicInteger collections = new AtomicInteger();
        AtomicBoolean nestedRequestResult = new AtomicBoolean(true);
        ScanManager[] holder = new ScanManager[1];
        holder[0] = manager(new BatchProcessor(10, false),
                () -> {
                    collections.incrementAndGet();
                    holder[0].tick();
                    nestedRequestResult.set(holder[0].requestScan());
                    return data(0, 0);
                },
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
                () -> {
                    assertThrows(IllegalStateException.class, () -> holder[0].reset());
                    return data(0, 0);
                },
                snapshot -> { });

        holder[0].requestScan();
        assertDoesNotThrow(holder[0]::tick, "回调内 reset 异常应由断言捕获，不穿透 tick");
        assertEquals(ScanPhase.SOLVING, holder[0].progress().phase());
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
    void adaptiveSamplesAreOnlyRecordedForCollectingAndSolving() {
        BatchProcessor processor = new BatchProcessor(10, true);
        ScanManager manager = manager(processor, () -> data(0, 0), snapshot -> { });

        manager.requestScan();
        int ticks = 0;
        while (manager.isRunning() && ticks++ < TICK_GUARD) {
            manager.tick();
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        assertEquals(2L, processor.batchesProcessed(),
                "只有采集与求解阶段计入自适应样本");
    }

    @Test
    void newScanCanStartAfterCompletion() {
        ScanManager manager = manager(new BatchProcessor(10, false),
                () -> data(0, 0), snapshot -> { });

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
}
