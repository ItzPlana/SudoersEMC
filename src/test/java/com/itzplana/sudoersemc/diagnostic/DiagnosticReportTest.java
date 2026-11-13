package com.itzplana.sudoersemc.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.PriceSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@link DiagnosticReport} 的单元测试。
 *
 * <p>断言定价来源求和、来源分布的过滤与顺序，以及中文报告的标签与数值渲染。</p>
 */
class DiagnosticReportTest {

    private static DiagnosticReport sample() {
        return new DiagnosticReport(
                1000L,
                100L,
                10L,
                20L,
                30L,
                40L,
                50L,
                7L,
                3L,
                2L,
                1234L,
                52_428_800L,
                Map.of("customCounter", 9L));
    }

    @Test
    void generatedTotalSumsAllPricingSources() {
        assertEquals(150L, sample().generatedTotal());

        DiagnosticReport none = new DiagnosticReport(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of());
        assertEquals(0L, none.generatedTotal());
    }

    @Test
    void sourceBreakdownOmitsZeroValuesAndIsOrdered() {
        Map<String, Long> breakdown = sample().sourceBreakdown();

        assertEquals(5, breakdown.size());
        assertEquals(
                List.of(
                        PriceSource.RECIPE.name(),
                        PriceSource.TAG.name(),
                        PriceSource.RARITY.name(),
                        PriceSource.TIER.name(),
                        PriceSource.FALLBACK.name()),
                new ArrayList<>(breakdown.keySet()));
        assertEquals(10L, breakdown.get(PriceSource.RECIPE.name()));
        assertEquals(20L, breakdown.get(PriceSource.TAG.name()));
        assertEquals(30L, breakdown.get(PriceSource.RARITY.name()));
        assertEquals(40L, breakdown.get(PriceSource.TIER.name()));
        assertEquals(50L, breakdown.get(PriceSource.FALLBACK.name()));

        DiagnosticReport withoutRarity = new DiagnosticReport(
                1000L, 100L, 10L, 20L, 0L, 40L, 50L, 7L, 3L, 2L, 1234L, 0L, Map.of());
        Map<String, Long> reduced = withoutRarity.sourceBreakdown();

        assertEquals(4, reduced.size());
        assertFalse(reduced.containsKey(PriceSource.RARITY.name()));
        assertEquals(
                List.of(
                        PriceSource.RECIPE.name(),
                        PriceSource.TAG.name(),
                        PriceSource.TIER.name(),
                        PriceSource.FALLBACK.name()),
                new ArrayList<>(reduced.keySet()));
    }

    @Test
    void formatTextContainsAllLabelsAndComputedNumbers() {
        String text = sample().formatText();

        assertTrue(text.contains("===== SudoersEMC 诊断报告 ====="));
        assertTrue(text.contains("================================"));

        assertLine(text, "注册物品总数", "1000");
        assertLine(text, "受保护的非自有既有值", "100");
        assertLine(text, "配方求解", "10");
        assertLine(text, "标签定价", "20");
        assertLine(text, "稀有度定价", "30");
        assertLine(text, "层级定价", "40");
        assertLine(text, "兜底定价", "50");
        assertLine(text, "未解析物品", "7");
        assertLine(text, "隔离的降价", "3");
        assertLine(text, "失败配方", "2");
        assertLine(text, "customCounter", "9");
        assertLine(text, "扫描耗时", "1234 ms");
        assertLine(text, "峰值堆内存", "50.0 MiB");
    }

    @Test
    void extraCountersAreRenderedInNameOrder() {
        DiagnosticReport report = new DiagnosticReport(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                Map.of("zeta", 2L, "alpha", 1L));

        String text = report.formatText();
        int failedRecipes = text.indexOf("失败配方");
        int alpha = text.indexOf("alpha");
        int zeta = text.indexOf("zeta");
        int duration = text.indexOf("扫描耗时");

        assertTrue(failedRecipes >= 0 && failedRecipes < alpha,
                "额外计数器应追加在固定指标之后");
        assertTrue(alpha < zeta, "额外计数器应按名称字典序输出");
        assertTrue(zeta < duration, "额外计数器应位于扫描耗时之前");
    }

    @Test
    void negativeCountersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticReport(
                -1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticReport(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of("bad", -1L)));
    }

    private static void assertLine(String text, String label, String value) {
        assertTrue(
                text.lines().anyMatch(line -> line.startsWith(label) && line.endsWith(": " + value)),
                "报告应包含 " + label + ": " + value + "，实际为:\n" + text);
    }
}
