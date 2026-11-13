package com.itzplana.sudoersemc.pricing;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.Rarity;

/**
 * Rarity-based fallback pricing.
 *
 * <p>Rarity is an intrinsic item attribute, suitable for giving a conservative fallback price to items
 * with "no tag, no recipe and no external baseline". The final price is computed as
 * {@code base * multiplier} using integer arithmetic rounded down.</p>
 *
 * <p>This is the only pricer in this package that depends on a Minecraft type, because {@link Rarity} is
 * a vanilla enum; the rest of the logic remains pure calculation.</p>
 */
public final class RarityPricing {

    private final Map<Rarity, Long> baseValues;
    private final Map<Rarity, Double> multipliers;
    private final long fallbackValue;

    /**
     * @param baseValues    base value per rarity, must be non-negative
     * @param multipliers   multiplier per rarity, must be finite and >= 0; missing entries are treated as {@code 1.0}
     * @param fallbackValue base value used when rarity is missing or {@code null}, must be non-negative
     */
    public RarityPricing(Map<Rarity, Long> baseValues, Map<Rarity, Double> multipliers, long fallbackValue) {
        Objects.requireNonNull(baseValues, "baseValues");
        Objects.requireNonNull(multipliers, "multipliers");
        if (fallbackValue < 0L) {
            throw new IllegalArgumentException("fallbackValue must be >= 0, got " + fallbackValue);
        }

        EnumMap<Rarity, Long> copiedBaseValues = new EnumMap<>(Rarity.class);
        for (Map.Entry<Rarity, Long> entry : baseValues.entrySet()) {
            Rarity rarity = Objects.requireNonNull(entry.getKey(), "baseValues key");
            Long value = Objects.requireNonNull(entry.getValue(), "baseValues[" + rarity + "]");
            if (value < 0L) {
                throw new IllegalArgumentException("baseValues[" + rarity + "] must be >= 0, got " + value);
            }
            copiedBaseValues.put(rarity, value);
        }
        this.baseValues = Map.copyOf(copiedBaseValues);

        EnumMap<Rarity, Double> copiedMultipliers = new EnumMap<>(Rarity.class);
        for (Map.Entry<Rarity, Double> entry : multipliers.entrySet()) {
            Rarity rarity = Objects.requireNonNull(entry.getKey(), "multipliers key");
            Double multiplier = Objects.requireNonNull(entry.getValue(), "multipliers[" + rarity + "]");
            if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 0.0D) {
                throw new IllegalArgumentException(
                        "multipliers[" + rarity + "] must be finite and >= 0, got " + multiplier);
            }
            copiedMultipliers.put(rarity, multiplier);
        }
        this.multipliers = Map.copyOf(copiedMultipliers);
        this.fallbackValue = fallbackValue;
    }

    /**
     * Computes the price for a rarity.
     *
     * <p>The result is {@code floor(base * multiplier)} and is never negative. When rarity is missing or
     * {@code null}, the {@code fallbackValue} given at construction is used and the multiplier is treated
     * as {@code 1.0}. If the result exceeds the {@code long} range it saturates to {@code Long.MAX_VALUE}
     * per Java narrowing rules rather than wrapping negative; the downstream safety layer still handles
     * above-threshold prices via the overflow policy.</p>
     *
     * @param rarity item rarity, may be {@code null}
     * @return non-negative price
     */
    public long price(Rarity rarity) {
        long base = rarity == null ? fallbackValue : baseValues.getOrDefault(rarity, fallbackValue);
        double multiplier = rarity == null ? 1.0D : multipliers.getOrDefault(rarity, 1.0D);
        if (base <= 0L || multiplier <= 0.0D) {
            return 0L;
        }
        double scaled = base * multiplier;
        return Math.max(0L, (long) Math.floor(scaled));
    }

    /**
     * Conservative defaults: COMMON 16, UNCOMMON 64, RARE 256, EPIC 2048, all multipliers {@code 1.0}.
     *
     * @return the default rarity pricer
     */
    public static RarityPricing defaults() {
        EnumMap<Rarity, Long> baseValues = new EnumMap<>(Rarity.class);
        baseValues.put(Rarity.COMMON, 16L);
        baseValues.put(Rarity.UNCOMMON, 64L);
        baseValues.put(Rarity.RARE, 256L);
        baseValues.put(Rarity.EPIC, 2048L);

        EnumMap<Rarity, Double> multipliers = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            multipliers.put(rarity, 1.0D);
        }
        return new RarityPricing(baseValues, multipliers, 16L);
    }

    /**
     * @return immutable view of rarity base values
     */
    public Map<Rarity, Long> baseValues() {
        return baseValues;
    }

    /**
     * @return immutable view of rarity multipliers
     */
    public Map<Rarity, Double> multipliers() {
        return multipliers;
    }

    /**
     * @return the base value used when rarity is missing
     */
    public long fallbackValue() {
        return fallbackValue;
    }
}
