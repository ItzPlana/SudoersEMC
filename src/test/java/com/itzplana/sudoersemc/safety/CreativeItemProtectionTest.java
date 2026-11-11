package com.itzplana.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * {@link CreativeItemProtection} 的单元测试，核心回归点是“无法求解”绝不能等同于“创造专属”：
 * 没有显式排除或创造命名空间标记时，无解物品仍然必须允许兜底定价。
 */
class CreativeItemProtectionTest {

    private static final ResourceLocation BEDROCK_ID = new ResourceLocation("minecraft", "bedrock");
    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");
    private static final ResourceLocation STICK_ID = new ResourceLocation("minecraft", "stick");
    private static final ResourceLocation CREATIVE_CRATE_ID =
            new ResourceLocation("create", "creative_crate");
    private static final ResourceLocation DARK_ALTAR_ID =
            new ResourceLocation("goety", "dark_altar");

    private static final ItemKey BEDROCK = ItemKey.of(BEDROCK_ID);
    private static final ItemKey DIAMOND = ItemKey.of(DIAMOND_ID);
    private static final ItemKey STICK = ItemKey.of(STICK_ID);
    private static final ItemKey CREATIVE_CRATE = ItemKey.of(CREATIVE_CRATE_ID);
    private static final ItemKey DARK_ALTAR = ItemKey.of(DARK_ALTAR_ID);

    private static CreativeItemProtection protection(boolean enabled) {
        return new CreativeItemProtection(new CreativeItemProtection.Settings(
                enabled,
                Set.of(BEDROCK_ID),
                Map.of(DIAMOND_ID, 64L),
                Set.of("create")));
    }

    @Test
    void explicitExclusionBlocksPricing() {
        CreativeItemProtection.Decision decision =
                protection(true).decide(BEDROCK, true, true);

        assertFalse(decision.priceable());
        assertEquals(0L, decision.specialValue());
        assertEquals("explicit-exclusion", decision.reason());
        assertFalse(protection(true).isPriceable(BEDROCK, true, true));
    }

    @Test
    void creativeOnlyNamespaceBlocksPricing() {
        CreativeItemProtection.Decision decision =
                protection(true).decide(CREATIVE_CRATE, true, true);

        assertFalse(decision.priceable());
        assertEquals(0L, decision.specialValue());
        assertEquals("creative-only-namespace", decision.reason());
    }

    @Test
    void specialValueIsReturnedWithItsReason() {
        CreativeItemProtection.Decision decision =
                protection(true).decide(DIAMOND, true, false);

        assertTrue(decision.priceable());
        assertEquals(64L, decision.specialValue());
        assertEquals("special-value", decision.reason());
    }

    @Test
    void unsolvableItemWithoutExplicitExclusionStaysPriceable() {
        CreativeItemProtection.Decision decision =
                protection(true).decide(DARK_ALTAR, false, false);

        assertTrue(decision.priceable(), "未知不等于创造专属，无解物品必须允许兜底定价");
        assertEquals(0L, decision.specialValue());
        assertEquals("unsolvable-fallback", decision.reason());
        assertTrue(protection(true).isPriceable(DARK_ALTAR, false, false));
    }

    @Test
    void disabledProtectionAlwaysAllowsPricing() {
        CreativeItemProtection protection = protection(false);

        CreativeItemProtection.Decision excluded = protection.decide(BEDROCK, false, false);
        CreativeItemProtection.Decision creativeNamespace =
                protection.decide(CREATIVE_CRATE, false, false);

        assertTrue(excluded.priceable());
        assertEquals("protection-disabled", excluded.reason());
        assertTrue(creativeNamespace.priceable());
        assertEquals("protection-disabled", creativeNamespace.reason());
    }

    @Test
    void normalItemIsPriceable() {
        CreativeItemProtection.Decision withRecipe = protection(true).decide(STICK, true, false);
        CreativeItemProtection.Decision withTag = protection(true).decide(STICK, false, true);

        assertTrue(withRecipe.priceable());
        assertEquals(0L, withRecipe.specialValue());
        assertEquals("normal", withRecipe.reason());
        assertTrue(withTag.priceable());
        assertEquals("normal", withTag.reason());
    }

    @Test
    void explicitExclusionWinsOverConfiguredSpecialValue() {
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(
                        true,
                        Set.of(DIAMOND_ID),
                        Map.of(DIAMOND_ID, 64L),
                        Set.of()));

        CreativeItemProtection.Decision decision = protection.decide(DIAMOND, true, true);

        assertFalse(decision.priceable());
        assertEquals(0L, decision.specialValue());
        assertEquals("explicit-exclusion", decision.reason());
    }
}
