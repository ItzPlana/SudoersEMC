package com.itzplana.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
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
    private static final ResourceLocation STONE_ID = new ResourceLocation("minecraft", "stone");
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
    void disabledProtectionOnlyDisablesCreativeNamespaces() {
        CreativeItemProtection protection = protection(false);

        CreativeItemProtection.Decision excluded = protection.decide(BEDROCK, false, false);
        CreativeItemProtection.Decision creativeNamespace =
                protection.decide(CREATIVE_CRATE, false, false);
        CreativeItemProtection.Decision special = protection.decide(DIAMOND, true, false);

        assertFalse(excluded.priceable(), "显式排除不随总开关关闭而失效");
        assertEquals("explicit-exclusion", excluded.reason());
        assertTrue(creativeNamespace.priceable(), "总开关关闭后创造命名空间不再拦截");
        assertEquals("protection-disabled", creativeNamespace.reason());
        assertTrue(special.priceable(), "固定值不随总开关关闭而失效");
        assertEquals(64L, special.specialValue());
        assertEquals("special-value", special.reason());
    }

    @Test
    void parsedItemIdsRequireNamespaceAndValidPath() {
        CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                true,
                List.of("stone", "minecraft:", "Minecraft:Stone", "minecraft:stone"),
                List.of("stone=5", "minecraft:stone=5"),
                List.of());

        assertEquals(Set.of(STONE_ID), parsed.settings().explicitExclusions());
        assertEquals(Map.of(STONE_ID, 5L), parsed.settings().specialValues());
        assertEquals(4, parsed.skippedEntries().size(),
                "无命名空间、空 path、大小写非法与特殊值缺少命名空间都必须被忽略");
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

    @Test
    void parsedExclusionsAndNamespacesBlockPricing() {
        CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                true,
                List.of("minecraft:bedrock", "create:creative_crate"),
                List.of(),
                List.of("goety"));
        CreativeItemProtection protection = new CreativeItemProtection(parsed.settings());

        assertTrue(parsed.skippedEntries().isEmpty());
        assertFalse(protection.isPriceable(BEDROCK, true, true));
        assertFalse(protection.isPriceable(CREATIVE_CRATE, true, true));
        assertFalse(protection.isPriceable(DARK_ALTAR, true, true));
    }

    @Test
    void parsedSpecialValueWinsOverNormalPricing() {
        CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                true,
                List.of(),
                List.of("minecraft:diamond=12345"),
                List.of());
        CreativeItemProtection protection = new CreativeItemProtection(parsed.settings());

        CreativeItemProtection.Decision decision = protection.decide(DIAMOND, false, false);

        assertTrue(decision.priceable());
        assertEquals(12_345L, decision.specialValue(),
                "特殊固定值必须在稀有度/无解兜底之前生效");
        assertEquals("special-value", decision.reason());
    }

    @Test
    void emptyParsedConfigurationKeepsNormalItemsPriceable() {
        CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                true, List.of(), List.of(), List.of());
        CreativeItemProtection protection = new CreativeItemProtection(parsed.settings());

        assertTrue(parsed.skippedEntries().isEmpty());
        assertTrue(protection.isPriceable(STICK, false, false));
        assertEquals("unsolvable-fallback", protection.decide(STICK, false, false).reason());
    }

    @Test
    void invalidParsedEntriesAreSkipped() {
        CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                true,
                List.of("minecraft:bedrock", "not a valid id"),
                List.of("minecraft:diamond=64", "minecraft:emerald=0",
                        "minecraft:gold_ingot=abc", "broken-entry"),
                List.of("create", "UPPERCASE"));

        assertEquals(5, parsed.skippedEntries().size(), "非法条目必须全部被忽略");
        assertEquals(Set.of(BEDROCK_ID), parsed.settings().explicitExclusions());
        assertEquals(Map.of(DIAMOND_ID, 64L), parsed.settings().specialValues());
        assertEquals(Set.of("create"), parsed.settings().creativeOnlyNamespaces());
    }

    @Test
    void configStringsAreParsedAndInvalidEntriesAreSkipped() {
        CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                true,
                List.of("minecraft:bedrock", "not a valid id"),
                List.of("minecraft:diamond=64", "minecraft:emerald=0", "broken"),
                List.of("create", "Invalid Namespace"));

        assertEquals(Set.of(BEDROCK_ID), parsed.settings().explicitExclusions());
        assertEquals(Map.of(DIAMOND_ID, 64L), parsed.settings().specialValues());
        assertEquals(Set.of("create"), parsed.settings().creativeOnlyNamespaces());
        assertEquals(4, parsed.skippedEntries().size(),
                "非法条目必须被跳过并进入诊断列表");
    }

    @Test
    void parsedSettingsAreWiredIntoProductionDecisions() {
        CreativeItemProtection protection = new CreativeItemProtection(
                CreativeItemProtection.parseSettings(
                        true,
                        List.of("minecraft:bedrock"),
                        List.of("minecraft:diamond=64"),
                        List.of("create")).settings());

        assertFalse(protection.decide(BEDROCK, true, true).priceable());
        assertEquals(64L, protection.decide(DIAMOND, true, true).specialValue());
        assertFalse(protection.decide(CREATIVE_CRATE, true, true).priceable());
    }
}
