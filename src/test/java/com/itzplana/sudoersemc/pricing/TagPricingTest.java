package com.itzplana.sudoersemc.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 材料标签定价测试。
 *
 * <p>重点是“标签只表示材料关系，不代表价值相同”这一约束：类别基准值只是锚点，
 * 按物品覆盖值优先于类别基准值。</p>
 */
class TagPricingTest {

    private static ItemKey item(String path) {
        return ItemKey.of(new ResourceLocation("sudoersemc", path));
    }

    private static TagPricing pricing() {
        return new TagPricing(
                Map.of(
                        MaterialCategory.INGOT, 256L,
                        MaterialCategory.NUGGET, 28L,
                        MaterialCategory.STORAGE_BLOCK, 2304L,
                        MaterialCategory.GEM, 8192L),
                Map.of(item("special_gem"), 65_536L));
    }

    @Test
    void categoryBaseValueIsUsed() {
        TagPricing pricing = pricing();

        assertEquals(256L, pricing.price(item("iron_ingot"), List.of("forge:ingots/iron")).orElseThrow());
        assertEquals(28L, pricing.price(item("iron_nugget"), List.of("forge:nuggets/iron")).orElseThrow());
        assertEquals(2304L, pricing.price(item("iron_block"),
                List.of("forge:storage_blocks/iron")).orElseThrow());
    }

    @Test
    void perItemOverrideWinsOverCategory() {
        TagPricing pricing = pricing();

        assertEquals(65_536L, pricing.price(item("special_gem"), List.of("forge:gems/special")).orElseThrow());
    }

    @Test
    void firstRecognisedCategoryInListOrderWins() {
        TagPricing pricing = pricing();

        // 同一物品可能同时属于多个标签；按传入顺序取第一个“已配置基准值”的类别
        long value = pricing.price(item("mixed"), List.of(
                "forge:miscellaneous/mixed",
                "forge:storage_blocks/mixed",
                "forge:ingots/mixed")).orElseThrow();

        assertEquals(2304L, value, "storage_blocks 在 ingots 之前，应先命中");
    }

    @Test
    void unknownCategoriesAreSkipped() {
        TagPricing pricing = pricing();

        long value = pricing.price(item("hybrid"), List.of(
                "forge:logs/hybrid",
                "forge:ingots/hybrid")).orElseThrow();

        assertEquals(256L, value, "无法识别的类别应被跳过，继续匹配下一个标签");
    }

    @Test
    void categoryWithoutConfiguredBaseValueIsSkipped() {
        TagPricing pricing = pricing();

        // PLATE 没有配置基准值，应继续寻找下一个可用的类别
        long value = pricing.price(item("plate_or_ingot"), List.of(
                "forge:plates/iron",
                "forge:ingots/iron")).orElseThrow();

        assertEquals(256L, value);
    }

    @Test
    void emptyOrUnknownTagsYieldNoPriceUnlessOverridden() {
        TagPricing pricing = pricing();

        assertTrue(pricing.price(item("no_tags"), List.of()).isEmpty());
        assertTrue(pricing.price(item("no_tags"), null).isEmpty());
        assertTrue(pricing.price(item("no_tags"), List.of("forge:logs/oak")).isEmpty());
        assertTrue(pricing.price(null, List.of("forge:ingots/iron")).isEmpty());
        assertEquals(65_536L, pricing.price(item("special_gem"), List.of()).orElseThrow(),
                "按物品覆盖不应依赖标签列表非空");
        assertEquals(65_536L, pricing.price(item("special_gem"), null).orElseThrow(),
                "按物品覆盖不应依赖标签列表非空");
        assertEquals(65_536L, pricing.price(item("special_gem"), List.of("forge:logs/oak")).orElseThrow(),
                "按物品覆盖不应依赖标签能被识别");
    }

    @Test
    void blankEntriesInTagListAreIgnored() {
        TagPricing pricing = pricing();

        long value = pricing.price(item("blanky"), List.of("   ", "forge:ingots/iron")).orElseThrow();

        assertEquals(256L, value);
    }

    @Test
    void unknownCategoryBaseValueIsNeverUsed() {
        // 即使配置里写了 UNKNOWN，也不应被当作真实类别命中
        TagPricing pricing = new TagPricing(
                Map.of(MaterialCategory.UNKNOWN, 9_999L, MaterialCategory.INGOT, 256L),
                Map.of());

        assertTrue(pricing.price(item("mystery"), List.of("forge:logs/oak")).isEmpty());
    }

    @Test
    void negativeValuesAreRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new TagPricing(Map.of(MaterialCategory.INGOT, -1L), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new TagPricing(Map.of(), Map.of(item("bad"), -5L)));
    }

    @Test
    void exposedMapsAreImmutable() {
        TagPricing pricing = pricing();

        assertThrows(UnsupportedOperationException.class,
                () -> pricing.baseValues().put(MaterialCategory.INGOT, 1L));
        assertFalse(pricing.baseValues().isEmpty());
    }

    @Test
    void defaultsExposeConservativeMaterialAnchors() {
        TagPricing pricing = TagPricing.defaults();

        assertEquals(256L, pricing.baseValues().get(MaterialCategory.INGOT));
        assertEquals(28L, pricing.baseValues().get(MaterialCategory.NUGGET));
        assertEquals(2304L, pricing.baseValues().get(MaterialCategory.STORAGE_BLOCK));
        assertEquals(512L, pricing.baseValues().get(MaterialCategory.GEM));
        assertEquals(64L, pricing.baseValues().get(MaterialCategory.DUST));
        assertEquals(128L, pricing.baseValues().get(MaterialCategory.RAW_MATERIAL));
        assertEquals(256L, pricing.baseValues().get(MaterialCategory.PLATE));
        assertEquals(128L, pricing.baseValues().get(MaterialCategory.ROD));
        assertEquals(128L, pricing.baseValues().get(MaterialCategory.ORE));
        assertTrue(pricing.overrides().isEmpty(), "默认配置不得包含按物品覆盖");
    }

    @Test
    void defaultsPriceCommonForgeMaterialTags() {
        TagPricing pricing = TagPricing.defaults();

        assertEquals(256L, pricing.price(item("iron_ingot"),
                List.of("forge:ingots/iron")).orElseThrow());
        assertEquals(2304L, pricing.price(item("iron_block"),
                List.of("forge:storage_blocks/iron")).orElseThrow());
    }

    @Test
    void defaultsYieldNoPriceForUnknownTags() {
        assertTrue(TagPricing.defaults()
                .price(item("mystery"), List.of("forge:logs/oak")).isEmpty());
    }
}
