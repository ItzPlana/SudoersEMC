package com.itzplana.sudoersemc.solver;

/**
 * 一次配方求解的收敛与诊断参数。
 *
 * <p>这些参数只影响求解过程，不改变任何现有 EMC 值：已有值永远优先，生成的候选值只在缺口处发布。</p>
 *
 * @param maxPasses                最大迭代轮数，必须 {@code >= 1}；默认 20
 * @param allowValueIncrease       是否允许后续轮次抬高本轮已经生成的值；默认 {@code false}
 * @param minimumEmc               交给评估器的最小 EMC，必须 {@code >= 0}；默认 1
 * @param maxAlternativeCostsPerItem 每个物品最多保留多少条备选成本，必须 {@code >= 0}；默认 8
 */
public record SolveSettings(
        int maxPasses,
        boolean allowValueIncrease,
        long minimumEmc,
        int maxAlternativeCostsPerItem
) {

    /** 默认最大迭代轮数。 */
    public static final int DEFAULT_MAX_PASSES = 20;

    /** 默认最小 EMC。 */
    public static final long DEFAULT_MINIMUM_EMC = 1L;

    /** 默认每个物品保留的备选成本条数。 */
    public static final int DEFAULT_MAX_ALTERNATIVE_COSTS = 8;

    public SolveSettings {
        if (maxPasses < 1) {
            throw new IllegalArgumentException("maxPasses 必须 >= 1，实际为 " + maxPasses);
        }
        if (minimumEmc < 0L) {
            throw new IllegalArgumentException("minimumEmc 必须 >= 0，实际为 " + minimumEmc);
        }
        if (maxAlternativeCostsPerItem < 0) {
            throw new IllegalArgumentException(
                    "maxAlternativeCostsPerItem 必须 >= 0，实际为 " + maxAlternativeCostsPerItem);
        }
    }

    /**
     * @return 生产环境默认参数：最多 20 轮、禁止抬价、最小 EMC 为 1、每个物品保留 8 条备选成本
     */
    public static SolveSettings defaults() {
        return new SolveSettings(DEFAULT_MAX_PASSES, false, DEFAULT_MINIMUM_EMC,
                DEFAULT_MAX_ALTERNATIVE_COSTS);
    }
}
