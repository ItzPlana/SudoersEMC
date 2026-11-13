package com.itzplana.sudoersemc.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.minecraft.world.item.Rarity;
import org.junit.jupiter.api.Test;

/**
 * 稀有度定价测试。
 *
 * <p>稀有度是兜底启发式，不是权威经济值：只验证基准值、倍率、向下取整与非负约束。</p>
 */
class RarityPricingTest {

    @Test
    void defaultsCoverEveryVanillaRarity() {
        RarityPricing pricing = RarityPricing.defaults();

        assertEquals(16L, pricing.price(Rarity.COMMON));
        assertEquals(64L, pricing.price(Rarity.UNCOMMON));
        assertEquals(256L, pricing.price(Rarity.RARE));
        assertEquals(2048L, pricing.price(Rarity.EPIC));
    }

    @Test
    void missingRarityUsesFallbackValue() {
        RarityPricing pricing = RarityPricing.defaults();

        assertEquals(16L, pricing.price(null));
        assertEquals(16L, pricing.fallbackValue());
    }

    @Test
    void multipliersAreAppliedWithFloor() {
        RarityPricing pricing = new RarityPricing(
                Map.of(Rarity.COMMON, 100L),
                Map.of(Rarity.COMMON, 1.5D),
                0L);

        // 100 * 1.5 = 150
        assertEquals(150L, pricing.price(Rarity.COMMON));

        RarityPricing fractional = new RarityPricing(
                Map.of(Rarity.COMMON, 33L),
                Map.of(Rarity.COMMON, 0.5D),
                0L);

        // 33 * 0.5 = 16.5，向下取整为 16
        assertEquals(16L, fractional.price(Rarity.COMMON));
    }

    @Test
    void absentMultiplierDefaultsToOne() {
        RarityPricing pricing = new RarityPricing(
                Map.of(Rarity.RARE, 512L),
                Map.of(),
                0L);

        assertEquals(512L, pricing.price(Rarity.RARE));
    }

    @Test
    void absentBaseValueFallsBack() {
        RarityPricing pricing = new RarityPricing(
                Map.of(Rarity.COMMON, 10L),
                Map.of(),
                77L);

        assertEquals(10L, pricing.price(Rarity.COMMON));
        assertEquals(77L, pricing.price(Rarity.EPIC), "未配置基准值的稀有度使用 fallbackValue");
    }

    @Test
    void priceIsNeverNegative() {
        RarityPricing pricing = new RarityPricing(
                Map.of(Rarity.COMMON, 0L, Rarity.RARE, 100L),
                Map.of(Rarity.COMMON, 0.0D, Rarity.RARE, 0.0D),
                0L);

        assertTrue(pricing.price(Rarity.COMMON) >= 0L);
        assertTrue(pricing.price(Rarity.RARE) >= 0L);
        assertEquals(0L, pricing.price(Rarity.RARE), "倍率为 0 时价格应为 0，而不是负数");
    }

    @Test
    void negativeConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new RarityPricing(Map.of(Rarity.COMMON, -1L), Map.of(), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new RarityPricing(Map.of(), Map.of(Rarity.COMMON, -0.5D), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new RarityPricing(Map.of(), Map.of(), -1L));
        assertThrows(IllegalArgumentException.class,
                () -> new RarityPricing(Map.of(), Map.of(Rarity.COMMON, Double.NaN), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new RarityPricing(Map.of(), Map.of(Rarity.COMMON, Double.POSITIVE_INFINITY), 0L));
    }

    @Test
    void hugeValuesSaturateInsteadOfWrapping() {
        RarityPricing pricing = new RarityPricing(
                Map.of(Rarity.COMMON, Long.MAX_VALUE),
                Map.of(Rarity.COMMON, 2.0D),
                0L);

        assertEquals(Long.MAX_VALUE, pricing.price(Rarity.COMMON),
                "超大值应饱和到 Long.MAX_VALUE，而不是回绕成负数");
    }
}
