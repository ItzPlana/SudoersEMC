package com.itzplana.sudoersemc.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.PriceSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        assertLine(text, "已有 EMC", "100");
        assertLine(text, "配方求解", "10");
        assertLine(text, "标签定价", "20");
        assertLine(text, "稀有度定价", "30");
        assertLine(text, "层级定价", "40");
        assertLine(text, "兜底定价", "50");
        assertLine(text, "未解析物品", "7");
        assertLine(text, "隔离的降价", "3");
        assertLine(text, "失败配方", "2");
        assertLine(text, "扫描耗时", "1234 ms");
        assertLine(text, "峰值堆内存", "50.0 MiB");
    }

    @Test
    void negativeCountersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticReport(
                -1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new DiagnosticReport(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of("bad", -1L)));
    }

    @Test
    void extraCountersAndRecipeStatsAreRendered() {
        Map<String, Long> counters = new LinkedHashMap<>();
        counters.put("totalRecipes", 10L);
        counters.put("usableRecipes", 4L);
        counters.put("unsupportedRecipes", 2L);
        counters.put("invalidRecipes", 1L);
        counters.put("failedRecipes", 3L);
        counters.put("retiredEntries", 3L);
        counters.put("quarantinedDrops", 2L);
        counters.put("scanTotalMillis", 999L);
        counters.put("scanTicks", 4L);
        counters.put("tickMaxMicros", 12_000L);
        counters.put("tickAvgMicros", 5_000L);
        counters.put("tickP95Micros", 11_000L);
        counters.put("heapAtReportBytes", 1_048_576L);
        DiagnosticReport report = new DiagnosticReport(
                10L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 2L, 3L, 999L, 2_097_152L,
                counters, List.of("recipe:boom"));

        String text = report.formatText();

        assertLine(text, "配方扫描总数", "10");
        assertLine(text, "可用配方", "4");
        assertLine(text, "不支持配方", "2");
        assertLine(text, "无效配方", "1");
        assertLine(text, "失败配方", "3");
        assertTrue(text.contains("recipe:boom"), "失败明细必须出现在报告中");
        assertTrue(text.contains("retiredEntries: 3"), "全部额外计数器都必须输出");
        assertTrue(text.contains("quarantinedDrops: 2"));
        assertTrue(text.contains("tick 采样数"));
        assertTrue(text.contains("tick 最大耗时"));
        assertTrue(text.contains("12.00 ms"));
        assertTrue(text.contains("tick P95 耗时"));
        assertTrue(text.contains("11.00 ms"));
        assertTrue(text.contains("报告时堆内存"));
        assertTrue(text.contains("1.0 MiB"));
        assertTrue(text.contains("扫描总耗时"));
        assertTrue(text.contains("999 ms"));
    }

    @Test
    void failedRecipeDetailsAreCappedAndSanitized() {
        List<String> details = new ArrayList<>();
        details.add("multi\nline\r\n" + "x".repeat(400));
        for (int i = 0; i < 15; i++) {
            details.add("recipe_" + i + ": boom");
        }
        DiagnosticReport report = new DiagnosticReport(
                1L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 16L, 1L, 0L,
                Map.of("totalRecipes", 16L), details);

        String text = report.formatText();

        assertTrue(text.contains("显示 " + DiagnosticReport.MAX_FAILED_RECIPE_DETAILS + "/16 条"),
                "失败明细必须显示显示的条数与总数");
        assertTrue(text.contains("recipe_0: boom"));
        assertTrue(text.contains("recipe_8: boom"));
        assertFalse(text.contains("recipe_9: boom"), "第 11 条起必须被截断");
        assertTrue(text.contains("其余 6 条失败配方未显示"));
        assertFalse(text.contains("multi\nline"), "明细中的换行必须被清洗为单行");
        assertTrue(text.lines().anyMatch(line ->
                        line.startsWith("  - multi line") && line.length() <= 250),
                "超长单条明细必须被截断到可读长度");
    }

    @Test
    void emptyAndMissingCountersRemainReadable() {
        DiagnosticReport empty = new DiagnosticReport(
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, Map.of(), null);

        String text = empty.formatText();

        assertTrue(text.contains("配方扫描统计"));
        assertTrue(text.contains("无数据"));
        assertTrue(text.contains("每 tick 耗时"));
        assertTrue(text.contains("无采样"));
        assertTrue(text.contains("报告时堆内存"));
        assertTrue(text.contains("不可用"));
        assertTrue(text.contains("额外计数器"));
        assertTrue(text.contains("（无）"));
        assertFalse(text.endsWith("\n"), "报告末尾保持无换行");
    }

    private static void assertLine(String text, String label, String value) {
        assertTrue(
                text.lines().anyMatch(line -> line.startsWith(label) && line.endsWith(": " + value)),
                "报告应包含 " + label + ": " + value + "，实际为:\n" + text);
    }
}
