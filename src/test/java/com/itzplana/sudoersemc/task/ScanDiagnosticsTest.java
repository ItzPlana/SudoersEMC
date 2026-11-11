package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link ScanDiagnostics} 的阶段累计、报告格式、内存采样与重置验证。
 *
 * <p>涉及时间的断言留出充足余量：只要两次打点之间真实等待了约 20ms，纳秒累计值就应
 * 明显超过断言下限，避免调度抖动造成偶发失败。</p>
 */
class ScanDiagnosticsTest {

    @Test
    void repeatedMarksAccumulatePerStage() throws InterruptedException {
        ScanDiagnostics diagnostics = new ScanDiagnostics();

        Thread.sleep(20L);
        diagnostics.mark("采集快照");
        Thread.sleep(20L);
        diagnostics.mark("采集快照");

        assertTrue(diagnostics.stageMillis("采集快照") >= 30L,
                "两次相隔约 20ms 的同阶段打点应累加，而不是覆盖");
        assertEquals(0L, diagnostics.stageMillis("求解配方"));
    }

    @Test
    void unknownStageReturnsZero() {
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        diagnostics.mark("采集快照");

        assertEquals(0L, diagnostics.stageMillis("不存在的阶段"));
        assertEquals(0L, diagnostics.stageMillis(null));
        assertFalse(diagnostics.stageMillis().containsKey("不存在的阶段"));
    }

    @Test
    void nullOrBlankStageIsIgnored() {
        ScanDiagnostics diagnostics = new ScanDiagnostics();

        diagnostics.mark(null);
        diagnostics.mark("");
        diagnostics.mark("   ");

        assertTrue(diagnostics.stageMillis().isEmpty());
        assertEquals(0L, diagnostics.totalMillis());
    }

    @Test
    void totalMillisIsSumOfStageDurations() {
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        diagnostics.mark("采集快照");
        diagnostics.mark("求解配方");

        long sum = 0L;
        for (long millis : diagnostics.stageMillis().values()) {
            sum += millis;
        }
        assertEquals(sum, diagnostics.totalMillis());
    }

    @Test
    void formatReportContainsStageNamesAndPeakHeap() {
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        diagnostics.mark("采集快照");
        diagnostics.mark("求解配方");
        diagnostics.recordMemory();

        String report = diagnostics.formatReport();

        assertTrue(report.contains("采集快照"));
        assertTrue(report.contains("求解配方"));
        assertTrue(report.contains("总计"));
        assertTrue(report.contains("峰值堆内存"));
        assertTrue(report.contains("MiB"));
    }

    @Test
    void recordMemoryTracksNonNegativePeak() {
        ScanDiagnostics diagnostics = new ScanDiagnostics();

        assertEquals(0L, diagnostics.peakHeapBytes());
        diagnostics.recordMemory();
        long first = diagnostics.peakHeapBytes();
        assertTrue(first >= 0L);
        diagnostics.recordMemory();
        assertTrue(diagnostics.peakHeapBytes() >= first, "峰值只能单调不减");
    }

    @Test
    void resetClearsStagesAndPeak() throws InterruptedException {
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        Thread.sleep(5L);
        diagnostics.mark("采集快照");
        diagnostics.recordMemory();

        diagnostics.reset();

        assertTrue(diagnostics.stageMillis().isEmpty());
        assertEquals(0L, diagnostics.stageMillis("采集快照"));
        assertEquals(0L, diagnostics.totalMillis());
        assertEquals(0L, diagnostics.peakHeapBytes());
    }

    @Test
    void stageMillisSnapshotIsReadOnly() {
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        diagnostics.mark("采集快照");

        Map<String, Long> snapshot = diagnostics.stageMillis();

        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("求解配方", 1L));
    }
}
