package com.itzplana.sudoersemc.diagnostic;

import com.itzplana.sudoersemc.core.PriceSource;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一次扫描结束时生成的不可变诊断快照。
 *
 * <p>本记录只承载数字，不持有任何可变集合或运行时句柄，因此可以安全地在扫描线程之外传递、
 * 缓存或序列化。所有计数都必须是 {@code >= 0} 的数值；构造时即校验，避免负值在报告里被误读为
 * “数据缺失”。</p>
 *
 * <p>{@link #formatText()} 输出固定顺序的中文多行报告，标签按显示宽度对齐（中日韩字符按两列
 * 计算），数值部分使用 {@link Locale#ROOT} 格式化，保证不同区域设置下小数点一致。
 * {@code extraCounters} 按名称字典序追加在固定指标之后，保证不同调用顺序下报告文本一致。</p>
 *
 * @param totalRegisteredItems    本次扫描遍历到的注册物品总数
 * @param itemsWithExistingEmc    受保护的非自有既有值数量（自有条目会先被分离并重新求解）
 * @param itemsSolvedByRecipe     通过配方求解得到价格的物品数量
 * @param itemsSolvedByTag        通过标签定价得到价格的物品数量
 * @param itemsSolvedByRarity     通过稀有度定价得到价格的物品数量
 * @param itemsSolvedByTier       通过层级规则得到价格的物品数量
 * @param itemsSolvedByFallback   通过兜底规则得到价格的物品数量
 * @param unresolvedItems         最终仍未解析出价格的物品数量
 * @param quarantinedDrops        因降幅超阈值而被隔离的降价数量
 * @param failedRecipes           求解失败的配方数量
 * @param totalDurationMillis     本次扫描总耗时毫秒数
 * @param peakHeapBytes           扫描期间观察到的峰值堆内存字节数
 * @param extraCounters           额外的具名计数器；不能包含 {@code null} 键或负值
 */
public record DiagnosticReport(
        long totalRegisteredItems,
        long itemsWithExistingEmc,
        long itemsSolvedByRecipe,
        long itemsSolvedByTag,
        long itemsSolvedByRarity,
        long itemsSolvedByTier,
        long itemsSolvedByFallback,
        long unresolvedItems,
        long quarantinedDrops,
        long failedRecipes,
        long totalDurationMillis,
        long peakHeapBytes,
        Map<String, Long> extraCounters
) {

    /** 标签列的显示宽度（中日韩字符按两列计算）。 */
    private static final int LABEL_WIDTH = 20;

    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    public DiagnosticReport {
        requireNonNegative("totalRegisteredItems", totalRegisteredItems);
        requireNonNegative("itemsWithExistingEmc", itemsWithExistingEmc);
        requireNonNegative("itemsSolvedByRecipe", itemsSolvedByRecipe);
        requireNonNegative("itemsSolvedByTag", itemsSolvedByTag);
        requireNonNegative("itemsSolvedByRarity", itemsSolvedByRarity);
        requireNonNegative("itemsSolvedByTier", itemsSolvedByTier);
        requireNonNegative("itemsSolvedByFallback", itemsSolvedByFallback);
        requireNonNegative("unresolvedItems", unresolvedItems);
        requireNonNegative("quarantinedDrops", quarantinedDrops);
        requireNonNegative("failedRecipes", failedRecipes);
        requireNonNegative("totalDurationMillis", totalDurationMillis);
        requireNonNegative("peakHeapBytes", peakHeapBytes);

        Map<String, Long> copied = new LinkedHashMap<>();
        if (extraCounters != null) {
            for (Map.Entry<String, Long> entry : extraCounters.entrySet()) {
                String name = entry.getKey();
                Long value = entry.getValue();
                if (name == null) {
                    throw new IllegalArgumentException("extraCounters 不能包含 null 键");
                }
                if (value == null) {
                    throw new IllegalArgumentException("extraCounters[" + name + "] 不能为 null");
                }
                requireNonNegative("extraCounters[" + name + "]", value);
                copied.put(name, value);
            }
        }
        extraCounters = Collections.unmodifiableMap(copied);
    }

    /**
     * @return 所有通过定价来源求解出的物品数量之和，即配方、标签、稀有度、层级与兜底五项相加
     */
    public long generatedTotal() {
        return itemsSolvedByRecipe
                + itemsSolvedByTag
                + itemsSolvedByRarity
                + itemsSolvedByTier
                + itemsSolvedByFallback;
    }

    /**
     * 按固定顺序返回非零的定价来源分布。
     *
     * <p>顺序固定为 {@code RECIPE → TAG → RARITY → TIER → FALLBACK}，与枚举声明顺序无关，
     * 这样同一份数据在任何构建中都会产生相同的键顺序。值为 0 的来源不会出现在结果里。</p>
     *
     * @return 不可变的“来源名称 → 数量”映射
     */
    public Map<String, Long> sourceBreakdown() {
        Map<String, Long> breakdown = new LinkedHashMap<>();
        putIfPositive(breakdown, PriceSource.RECIPE, itemsSolvedByRecipe);
        putIfPositive(breakdown, PriceSource.TAG, itemsSolvedByTag);
        putIfPositive(breakdown, PriceSource.RARITY, itemsSolvedByRarity);
        putIfPositive(breakdown, PriceSource.TIER, itemsSolvedByTier);
        putIfPositive(breakdown, PriceSource.FALLBACK, itemsSolvedByFallback);
        return Collections.unmodifiableMap(breakdown);
    }

    /**
     * 生成多行中文诊断报告。
     *
     * <p>输出以固定横幅开始，随后每行一个指标，最后以分隔线结束。峰值堆内存保留一位小数并
     * 换算为 MiB；即使从未采样（值为 0）也会输出 {@code 0.0 MiB}，方便日志解析。</p>
     *
     * @return 以 {@code \n} 分隔的报告文本，末尾没有换行符
     */
    public String formatText() {
        StringBuilder text = new StringBuilder();
        text.append("===== SudoersEMC 诊断报告 =====\n");
        appendLine(text, "注册物品总数", Long.toString(totalRegisteredItems));
        appendLine(text, "受保护的非自有既有值", Long.toString(itemsWithExistingEmc));
        appendLine(text, "配方求解", Long.toString(itemsSolvedByRecipe));
        appendLine(text, "标签定价", Long.toString(itemsSolvedByTag));
        appendLine(text, "稀有度定价", Long.toString(itemsSolvedByRarity));
        appendLine(text, "层级定价", Long.toString(itemsSolvedByTier));
        appendLine(text, "兜底定价", Long.toString(itemsSolvedByFallback));
        appendLine(text, "未解析物品", Long.toString(unresolvedItems));
        appendLine(text, "隔离的降价", Long.toString(quarantinedDrops));
        appendLine(text, "失败配方", Long.toString(failedRecipes));
        extraCounters.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> appendLine(text, entry.getKey(), Long.toString(entry.getValue())));
        appendLine(text, "扫描耗时", totalDurationMillis + " ms");
        appendLine(text, "峰值堆内存",
                String.format(Locale.ROOT, "%.1f", peakHeapBytes / BYTES_PER_MIB) + " MiB");
        text.append("================================");
        return text.toString();
    }

    private static void putIfPositive(Map<String, Long> target, PriceSource source, long count) {
        if (count > 0L) {
            target.put(source.name(), count);
        }
    }

    private static void appendLine(StringBuilder text, String label, String value) {
        text.append(padToDisplayWidth(label)).append(": ").append(value).append('\n');
    }

    /**
     * 按终端显示宽度右补空格。汉字、假名、全角符号占两列，ASCII 占一列；标签本身超出目标宽度
     * 时原样返回，绝不截断。
     */
    private static String padToDisplayWidth(String label) {
        int width = displayWidth(label);
        if (width >= LABEL_WIDTH) {
            return label;
        }
        StringBuilder padded = new StringBuilder(label);
        for (int i = width; i < LABEL_WIDTH; i++) {
            padded.append(' ');
        }
        return padded.toString();
    }

    private static int displayWidth(String text) {
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            width += isWideChar(text.charAt(i)) ? 2 : 1;
        }
        return width;
    }

    private static boolean isWideChar(char c) {
        return (c >= 0x1100 && c <= 0x115F)
                || (c >= 0x2E80 && c <= 0xA4CF)
                || (c >= 0xAC00 && c <= 0xD7A3)
                || (c >= 0xF900 && c <= 0xFAFF)
                || (c >= 0xFE30 && c <= 0xFE6F)
                || (c >= 0xFF00 && c <= 0xFF60)
                || (c >= 0xFFE0 && c <= 0xFFE6);
    }

    private static void requireNonNegative(String name, long value) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " 必须 >= 0，实际为 " + value);
        }
    }
}
