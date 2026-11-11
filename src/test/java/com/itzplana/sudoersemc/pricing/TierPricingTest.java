package com.itzplana.sudoersemc.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 层级定价测试。
 *
 * <p>规则按注册顺序求值，第一个生效的规则决定结果：排除 → 固定值 → 倍率。
 * 引擎不得内置大型模组清单，默认规则只覆盖没有经济意义的原版物品。</p>
 */
class TierPricingTest {

    private static ItemKey item(String namespace, String path) {
        return ItemKey.of(new ResourceLocation(namespace, path));
    }

    @Test
    void namespaceRuleMatches() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.NAMESPACE, "sudoersemc", 1.0D, 512L, false)));

        assertEquals(512L, pricing.price(item("sudoersemc", "anything")).orElseThrow());
        assertTrue(pricing.price(item("minecraft", "anything")).isEmpty());
    }

    @Test
    void exactIdRuleMatches() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.EXACT_ID, "sudoersemc:special", 1.0D, 1024L, false)));

        assertEquals(1024L, pricing.price(item("sudoersemc", "special")).orElseThrow());
        assertTrue(pricing.price(item("sudoersemc", "special_plus")).isEmpty());
        assertTrue(pricing.price(item("other", "special")).isEmpty());
    }

    @Test
    void pathContainsRuleMatches() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.PATH_CONTAINS, "spawn_egg", 1.0D, null, true)));

        assertEquals(Optional.of(0L), pricing.price(item("minecraft", "cow_spawn_egg")));
        assertEquals(Optional.of(0L), pricing.price(item("mod", "spawn_egg_special")));
        assertTrue(pricing.price(item("minecraft", "cow")).isEmpty());
    }

    @Test
    void excludeWinsOverFixedValueInTheSameRule() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.EXACT_ID, "sudoersemc:blocked", 2.0D, 500L, true)));

        assertEquals(Optional.of(0L), pricing.price(item("sudoersemc", "blocked")),
                "同一条规则里 exclude 优先级最高，即使配了固定值");
        assertEquals(1.0D, pricing.multiplierFor(item("sudoersemc", "blocked")), "排除规则不产生倍率");
    }

    @Test
    void fixedValueWinsOverMultiplier() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.EXACT_ID, "sudoersemc:tiered", 3.0D, 700L, false)));

        assertEquals(700L, pricing.price(item("sudoersemc", "tiered")).orElseThrow());
        assertEquals(1.0D, pricing.multiplierFor(item("sudoersemc", "tiered")),
                "固定值规则不暴露倍率");
    }

    @Test
    void multiplierOnlyRuleExposesMultiplierAndNoPrice() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.NAMESPACE, "sudoersemc", 2.5D, null, false)));

        assertTrue(pricing.price(item("sudoersemc", "scaled")).isEmpty(),
                "纯倍率规则不单独产生价格");
        assertEquals(2.5D, pricing.multiplierFor(item("sudoersemc", "scaled")));
    }

    @Test
    void firstMatchingRuleWins() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.EXACT_ID, "sudoersemc:priority", 1.0D, 111L, false),
                new TierPricing.TierRule(TierPricing.Type.NAMESPACE, "sudoersemc", 1.0D, 999L, false)));

        assertEquals(111L, pricing.price(item("sudoersemc", "priority")).orElseThrow(),
                "精确 id 规则注册在前，应先命中");
    }

    @Test
    void patternIsNormalisedToLowerCase() {
        TierPricing pricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.EXACT_ID, "  SudoersEMC:MiXeD  ", 1.0D, 42L, false)));

        assertEquals(42L, pricing.price(item("sudoersemc", "mixed")).orElseThrow());
    }

    @Test
    void nullItemAndNoMatchYieldNoPrice() {
        TierPricing pricing = TierPricing.defaults();

        assertTrue(pricing.price(null).isEmpty());
        assertEquals(1.0D, pricing.multiplierFor(null));
        assertTrue(pricing.price(item("sudoersemc", "ordinary_item")).isEmpty());
    }

    @Test
    void defaultRulesOnlyExcludeMeaninglessVanillaItems() {
        TierPricing pricing = TierPricing.defaults();

        assertEquals(Optional.of(0L), pricing.price(item("minecraft", "air")));
        assertEquals(Optional.of(0L), pricing.price(item("minecraft", "pig_spawn_egg")));
        assertEquals(Optional.of(0L), pricing.price(item("minecraft", "debug_stick")));
        assertTrue(pricing.price(item("minecraft", "iron_ingot")).isEmpty(),
                "默认规则不得排除正常可交易物品");
        assertTrue(pricing.price(item("minecraft", "diamond")).isEmpty());
    }

    @Test
    void invalidRulesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new TierPricing.TierRule(TierPricing.Type.NAMESPACE, "   ", 1.0D, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new TierPricing.TierRule(TierPricing.Type.NAMESPACE, "mod", 1.0D, -1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new TierPricing.TierRule(TierPricing.Type.NAMESPACE, "mod", -0.1D, null, false));
        assertThrows(IllegalArgumentException.class,
                () -> new TierPricing.TierRule(TierPricing.Type.NAMESPACE, "mod", Double.NaN, null, false));
    }

    @Test
    void ruleListIsImmutable() {
        TierPricing pricing = TierPricing.defaults();

        assertThrows(UnsupportedOperationException.class, () -> pricing.rules().clear());
        assertFalse(pricing.rules().isEmpty());
    }
}
