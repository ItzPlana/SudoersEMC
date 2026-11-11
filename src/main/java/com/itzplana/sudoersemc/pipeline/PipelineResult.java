package com.itzplana.sudoersemc.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次“求解并应用”运行的结果汇总。
 *
 * <p>所有计数描述的是同一轮管线运行：{@code solvedByRecipe} 与 {@code solvedByFallback} 是
 * 候选值数量，{@code applied} 是最终成功写入存储（或在干跑模式下本应写入）的数量。
 * {@code sourceBreakdown} 的键为 {@link com.itzplana.sudoersemc.core.PriceSource#name()}，
 * 值为该来源实际应用的数量；在非干跑模式下它的总和等于 {@code applied}。</p>
 *
 * @param solvedByRecipe    配方求解器给出正值的物品数量
 * @param solvedByFallback  通过创造物品保护固定值或兜底定价链得到正值的物品数量
 * @param applied           最终成功写入存储的条目数量；干跑模式下为本应写入的数量
 * @param quarantined       因大幅降价保护被隔离、未写入的候选数量
 * @param overflowResets    本次运行实际发生的溢出重置次数
 * @param unresolved        求解结束时仍未解析的物品数量
 * @param durationMillis    本次运行的耗时毫秒数
 * @param appliedToStore    是否真正写入了持久化存储；存储为 {@code null} 的干跑模式为 {@code false}
 * @param sourceBreakdown   实际应用条目的来源分布
 */
public record PipelineResult(
        int solvedByRecipe,
        int solvedByFallback,
        int applied,
        int quarantined,
        int overflowResets,
        int unresolved,
        long durationMillis,
        boolean appliedToStore,
        Map<String, Long> sourceBreakdown
) {

    public PipelineResult {
        requireNonNegative("solvedByRecipe", solvedByRecipe);
        requireNonNegative("solvedByFallback", solvedByFallback);
        requireNonNegative("applied", applied);
        requireNonNegative("quarantined", quarantined);
        requireNonNegative("overflowResets", overflowResets);
        requireNonNegative("unresolved", unresolved);
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis 必须 >= 0，实际为 " + durationMillis);
        }

        Map<String, Long> copied = new LinkedHashMap<>();
        if (sourceBreakdown != null) {
            for (Map.Entry<String, Long> entry : sourceBreakdown.entrySet()) {
                String key = entry.getKey();
                Long value = entry.getValue();
                if (key == null) {
                    throw new IllegalArgumentException("sourceBreakdown 不能包含 null 键");
                }
                if (value == null || value < 0L) {
                    throw new IllegalArgumentException(
                            "sourceBreakdown[" + key + "] 必须为非负数值，实际为 " + value);
                }
                if (value > 0L) {
                    copied.put(key, value);
                }
            }
        }
        sourceBreakdown = Collections.unmodifiableMap(copied);
    }

    /**
     * 构造一次“没有任何产出”的结果。
     *
     * <p>用于空快照、缺少暂存快照以及管线内部异常等场景。{@code appliedToStore} 固定为
     * {@code false}，因为此结果不承诺任何条目已经落盘。</p>
     *
     * @param durationMillis 本次运行耗时，负数会被归零
     * @return 所有计数为零的结果
     */
    public static PipelineResult zeroed(long durationMillis) {
        return new PipelineResult(0, 0, 0, 0, 0, 0, Math.max(0L, durationMillis), false, Map.of());
    }

    /**
     * @return {@code sourceBreakdown} 中所有计数的总和
     */
    public long sourceTotal() {
        long total = 0L;
        for (Long value : sourceBreakdown.values()) {
            total += value;
        }
        return total;
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " 必须 >= 0，实际为 " + value);
        }
    }
}
