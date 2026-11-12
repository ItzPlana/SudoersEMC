package com.itzplana.sudoersemc.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 Phase 25 新增的 EXTERNAL_BASELINE 计数与旧构造器兼容性。
 */
class Phase25DiagnosticReportTest {

    @Test
    void externalBaselineCounterParticipatesInTotalsAndOrder() {
        DiagnosticReport report = new DiagnosticReport(
                10L, 1L, 2L, 3L, 4L, 5L, 6L, 7L,
                0L, 0L, 0L, 1000L, 0L, Map.of(), List.of());

        assertEquals(27L, report.generatedTotal());
        assertEquals(List.of("RECIPE", "EXTERNAL_BASELINE", "TAG", "RARITY", "TIER", "FALLBACK"),
                new ArrayList<>(report.sourceBreakdown().keySet()));
        assertTrue(report.formatText().contains("外部基线定价"));
    }

    @Test
    void legacyConstructorsDefaultExternalBaselineToZero() {
        DiagnosticReport report = new DiagnosticReport(
                10L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of());
        assertEquals(0L, report.itemsSolvedByExternalBaseline());
        assertEquals(0L, report.generatedTotal());
    }
}
