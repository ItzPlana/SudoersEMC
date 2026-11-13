package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link ScanProgress} 的夹取、比例、阶段语义与时间戳验证。
 */
class ScanProgressTest {

    private static ScanProgress progress(ScanPhase phase, int processed, int total) {
        return new ScanProgress(phase, processed, total, 1_000L, 1_500L, "细节");
    }

    @Test
    void fractionIsZeroWhenTotalIsZero() {
        assertEquals(0.0, progress(ScanPhase.COLLECTING_SNAPSHOT, 5, 0).fraction(), 1.0e-9);
        assertEquals(0.0, progress(ScanPhase.COLLECTING_SNAPSHOT, 0, -1).fraction(), 1.0e-9);
    }

    @Test
    void fractionIsClampedToOne() {
        ScanProgress clamped = progress(ScanPhase.SOLVING, 50, 10);

        assertEquals(10, clamped.processed(), "构造器应把 processed 夹到 total");
        assertEquals(1.0, clamped.fraction(), 1.0e-9);
    }

    @Test
    void fractionReportsPartialProgress() {
        assertEquals(0.5, progress(ScanPhase.SOLVING, 5, 10).fraction(), 1.0e-9);
    }

    @Test
    void processedIsClampedIntoRange() {
        assertEquals(0, progress(ScanPhase.SOLVING, -5, 10).processed());
        assertEquals(0, progress(ScanPhase.SOLVING, -5, 0).processed());
        assertEquals(10, progress(ScanPhase.SOLVING, 100, 10).processed());
    }

    @Test
    void isRunningMatchesActivePhases() {
        assertTrue(progress(ScanPhase.COLLECTING_SNAPSHOT, 0, 1).isRunning());
        assertTrue(progress(ScanPhase.SOLVING, 0, 1).isRunning());
        assertTrue(progress(ScanPhase.APPLYING, 0, 0).isRunning());
        assertTrue(progress(ScanPhase.SAVING, 0, 0).isRunning());

        assertFalse(progress(ScanPhase.IDLE, 0, 0).isRunning());
        assertFalse(progress(ScanPhase.COMPLETED, 1, 1).isRunning());
        assertFalse(progress(ScanPhase.CANCELLED, 1, 1).isRunning());
        assertFalse(progress(ScanPhase.FAILED, 1, 1).isRunning());
    }

    @Test
    void advanceCreatesNewInstanceAndKeepsStartTime() {
        ScanProgress original = progress(ScanPhase.COLLECTING_SNAPSHOT, 0, 10);

        ScanProgress advanced = original.advance(ScanPhase.SOLVING, 4, 8, "求解");

        assertNotSame(original, advanced);
        assertEquals(original.startedAtMillis(), advanced.startedAtMillis());
        assertEquals(ScanPhase.SOLVING, advanced.phase());
        assertEquals(4, advanced.processed());
        assertEquals(8, advanced.total());
        assertEquals("求解", advanced.detail());
        assertTrue(advanced.lastUpdateMillis() >= original.lastUpdateMillis());
    }

    @Test
    void elapsedMillisIsNeverNegative() {
        assertEquals(500L, progress(ScanPhase.SOLVING, 0, 1).elapsedMillis());

        ScanProgress inverted = new ScanProgress(ScanPhase.SOLVING, 0, 1, 2_000L, 1_000L, "");
        assertEquals(0L, inverted.elapsedMillis(), "时间戳倒序时按 0 处理");
    }

    @Test
    void negativeTimingsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanProgress(ScanPhase.SOLVING, 0, 1, -1L, 0L, ""));
        assertThrows(IllegalArgumentException.class,
                () -> new ScanProgress(ScanPhase.SOLVING, 0, 1, 0L, -1L, ""));
    }

    @Test
    void nullDetailBecomesEmptyString() {
        ScanProgress value = new ScanProgress(ScanPhase.IDLE, 0, 0, 0L, 0L, null);

        assertEquals("", value.detail());
    }

    @Test
    void idleIsNotRunning() {
        ScanProgress idle = ScanProgress.idle();

        assertEquals(ScanPhase.IDLE, idle.phase());
        assertFalse(idle.isRunning());
        assertEquals(0.0, idle.fraction(), 1.0e-9);
        assertEquals(0L, idle.elapsedMillis());
        assertEquals("", idle.detail());
    }
}
