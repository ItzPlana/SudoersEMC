package com.itzplana.sudoersemc.pricing;

/**
 * Switches and generic fallback value for the fallback pricing chain.
 *
 * <p>Each switch only controls whether its step participates in pricing; it never changes the priority
 * order defined in {@link FallbackPricing}.</p>
 *
 * @param useTagPricing              whether to enable material-tag pricing
 * @param useRarityPricing           whether to enable rarity pricing
 * @param useTierPricing             whether to enable tier-rule pricing
 * @param useFallbackForUnsolvable   whether to use the generic fallback value for items still unsolvable
 * @param genericFallbackValue       generic fallback value, must be non-negative, default {@code 1}
 */
public record PricingSettings(
        boolean useTagPricing,
        boolean useRarityPricing,
        boolean useTierPricing,
        boolean useFallbackForUnsolvable,
        long genericFallbackValue
) {

    /** Default generic fallback value. */
    public static final long DEFAULT_GENERIC_FALLBACK_VALUE = 1L;

    public PricingSettings {
        if (genericFallbackValue < 0L) {
            throw new IllegalArgumentException("genericFallbackValue must be >= 0, got " + genericFallbackValue);
        }
    }

    /**
     * @return default configuration with all pricing steps enabled and a generic fallback value of {@code 1}
     */
    public static PricingSettings defaults() {
        return new PricingSettings(true, true, true, true, DEFAULT_GENERIC_FALLBACK_VALUE);
    }
}
