package com.itzplana.sudoersemc.pricing;

/**
 * 兜底定价链的开关与通用兜底值。
 *
 * <p>各开关只控制对应步骤是否参与定价，不会改变 {@link FallbackPricing} 中定义的优先级顺序。</p>
 *
 * @param useTagPricing              是否启用材料标签定价
 * @param useRarityPricing           是否启用稀有度定价
 * @param useTierPricing             是否启用层级规则定价
 * @param useFallbackForUnsolvable   是否对仍然无法求解的物品使用通用兜底值
 * @param genericFallbackValue       通用兜底值，必须为非负数，默认 {@code 1}
 */
public record PricingSettings(
        boolean useTagPricing,
        boolean useRarityPricing,
        boolean useTierPricing,
        boolean useFallbackForUnsolvable,
        long genericFallbackValue
) {

    /** 默认通用兜底值。 */
    public static final long DEFAULT_GENERIC_FALLBACK_VALUE = 1L;

    public PricingSettings {
        if (genericFallbackValue < 0L) {
            throw new IllegalArgumentException("genericFallbackValue must be >= 0, got " + genericFallbackValue);
        }
    }

    /**
     * @return 所有定价步骤开启、通用兜底值为 {@code 1} 的默认配置
     */
    public static PricingSettings defaults() {
        return new PricingSettings(true, true, true, true, DEFAULT_GENERIC_FALLBACK_VALUE);
    }
}
