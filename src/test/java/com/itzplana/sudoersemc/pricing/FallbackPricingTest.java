package com.itzplana.sudoersemc.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Rarity;
import org.junit.jupiter.api.Test;

/**
 * 兜底定价链优先级测试。
 *
 * <p>必须严格遵循 {@code EXISTING → EXTERNAL_BASELINE → 排除检查 → TAG → TIER → RARITY → FALLBACK}。
 * 层级排除先于所有定价；纯倍率层级规则返回空，因此不会阻断稀有度定价。</p>
 */
class FallbackPricingTest {

    private static ItemKey item(String path) {
        return ItemKey.of(new ResourceLocation("sudoersemc", path));
    }

    private static TagPricing tagPricing() {
        return new TagPricing(Map.of(MaterialCategory.INGOT, 256L), Map.of());
    }

    private static FallbackPricing pricing(PricingSettings settings) {
        return new FallbackPricing(tagPricing(), RarityPricing.defaults(), TierPricing.defaults(), settings);
    }

    private static FallbackPricing.PricingRequest request(long existing, long baseline) {
        return new FallbackPricing.PricingRequest(
                item("target"), List.of("forge:ingots/iron"), Rarity.COMMON, existing, baseline);
    }

    @Test
    void existingValueBeatsEverything() {
        FallbackPricing pricing = pricing(PricingSettings.defaults());

        FallbackPricing.PricedValue value = pricing.price(request(777L, 888L)).orElseThrow();

        assertEquals(777L, value.value());
        assertEquals(PriceSource.EXISTING, value.source());
        assertEquals("existing-value", value.reason());
    }

    @Test
    void externalBaselineBeatsTagAndBelow() {
        FallbackPricing pricing = pricing(PricingSettings.defaults());

        FallbackPricing.PricedValue value = pricing.price(request(0L, 500L)).orElseThrow();

        assertEquals(500L, value.value());
        assertEquals(PriceSource.EXTERNAL_BASELINE, value.source());
    }

    @Test
    void tagBeatsRarityWhenEnabled() {
        FallbackPricing pricing = pricing(PricingSettings.defaults());

        FallbackPricing.PricedValue value = pricing.price(request(0L, 0L)).orElseThrow();

        assertEquals(256L, value.value(), "标签基准值 256 优先于稀有度 16");
        assertEquals(PriceSource.TAG, value.source());
    }

    @Test
    void disablingTagPricingFallsThroughToRarity() {
        FallbackPricing pricing = pricing(new PricingSettings(false, true, true, false, 1L));

        FallbackPricing.PricedValue value = pricing.price(request(0L, 0L)).orElseThrow();

        assertEquals(16L, value.value());
        assertEquals(PriceSource.RARITY, value.source());
        assertEquals("rarity:COMMON", value.reason());
    }

    @Test
    void rarityAlwaysProducesAValueWhenEnabled() {
        FallbackPricing pricing = pricing(new PricingSettings(false, true, true, false, 1L));

        // 稀有度定价总会给值，因此即便同时启用了层级规则，层级规则也不会被触及
        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("minecraft_air_like"), List.of(), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(PriceSource.RARITY, value.source());
    }

    @Test
    void tierExclusionYieldsNoPriceEvenWhenRarityPricingIsDisabled() {
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), TierPricing.defaults(),
                new PricingSettings(true, false, true, true, 1L));

        // 默认规则把 spawn_egg 标记为排除；排除返回 0，定价链应终止而不是产出 0 价
        Optional<FallbackPricing.PricedValue> value = pricing.price(new FallbackPricing.PricingRequest(
                item("pig_spawn_egg"), List.of(), Rarity.COMMON, 0L, 0L));

        assertTrue(value.isEmpty(), "被排除的物品不应被定价");
    }

    @Test
    void tierExclusionTerminatesTheChainInsteadOfFallingBack() {
        // 打开通用兜底值，验证“明确排除”不会被通用兜底值覆盖
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), TierPricing.defaults(),
                new PricingSettings(true, false, true, true, 99L));

        Optional<FallbackPricing.PricedValue> value = pricing.price(new FallbackPricing.PricingRequest(
                item("debug_stick"), List.of(), Rarity.COMMON, 0L, 0L));

        assertTrue(value.isEmpty(), "被明确排除的物品绝不能变成免费或兜底价");
    }

    @Test
    void tierFixedValueAppliesWhenRarityDisabled() {
        TierPricing tierPricing = new TierPricing(List.of(
                new TierPricing.TierRule(TierPricing.Type.EXACT_ID, "sudoersemc:high_tier", 1.0D, 4096L, false)));
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), tierPricing,
                new PricingSettings(true, false, true, true, 1L));

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("high_tier"), List.of(), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(4096L, value.value());
        assertEquals(PriceSource.TIER, value.source());
    }

    @Test
    void genericFallbackIsUsedLast() {
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), TierPricing.defaults(),
                new PricingSettings(true, false, false, true, 7L));

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("no_tags_here"), List.of(), null, 0L, 0L)).orElseThrow();

        assertEquals(7L, value.value());
        assertEquals(PriceSource.FALLBACK, value.source());
        assertEquals("generic-fallback", value.reason());
    }

    @Test
    void disabledFallbackYieldsNoPrice() {
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), TierPricing.defaults(),
                new PricingSettings(false, false, false, false, 1L));

        assertTrue(pricing.price(new FallbackPricing.PricingRequest(
                item("nothing"), List.of(), null, 0L, 0L)).isEmpty());
    }

    @Test
    void negativeInputsAreClampedToZeroAndIgnored() {
        FallbackPricing pricing = pricing(new PricingSettings(false, false, false, false, 1L));

        FallbackPricing.PricingRequest request = new FallbackPricing.PricingRequest(
                item("negative"), List.of(), Rarity.COMMON, -100L, -200L);

        assertEquals(0L, request.existingValue());
        assertEquals(0L, request.externalBaseline());
        assertTrue(pricing.price(request).isEmpty(), "钳制后既无既有值也无基线，且全部步骤关闭");
    }

    @Test
    void nullTagsAreFilteredFromTheRequest() {
        FallbackPricing.PricingRequest request = new FallbackPricing.PricingRequest(
                item("nulltags"), java.util.Arrays.asList("forge:ingots/iron", null, "  "),
                Rarity.COMMON, 0L, 0L);

        assertEquals(2, request.tagPaths().size(), "只过滤 null 元素；空白字符串保留，由定价器跳过");
        assertFalse(request.tagPaths().isEmpty());
    }

    @Test
    void withDefaultsFactoryProducesAWorkingChain() {
        FallbackPricing pricing = FallbackPricing.withDefaults(PricingSettings.defaults());

        // 空标签表 → 跳过 TAG；稀有度默认 COMMON 16
        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("plain"), List.of(), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(16L, value.value());
        assertEquals(PriceSource.RARITY, value.source());
    }

    @Test
    void settingsRejectNegativeGenericFallback() {
        assertThrows(IllegalArgumentException.class,
                () -> new PricingSettings(true, true, true, true, -1L));
    }

    @Test
    void pricedValueNeverStoresNegativeValues() {
        FallbackPricing.PricedValue value = new FallbackPricing.PricedValue(
                item("clamped"), -50L, PriceSource.FALLBACK, "manual");

        assertEquals(0L, value.value());
    }

    @Test
    void nullRequestIsRejected() {
        FallbackPricing pricing = pricing(PricingSettings.defaults());

        assertThrows(NullPointerException.class, () -> pricing.price(null));
    }

    @Test
    void tierFixedValueBeatsRarity() {
        TierPricing tierPricing = new TierPricing(List.of(
                new TierPricing.TierRule(
                        TierPricing.Type.EXACT_ID, "sudoersemc:high_tier", 1.0D, 4096L, false)));
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), tierPricing, PricingSettings.defaults());

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("high_tier"), List.of(), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(4096L, value.value(), "TIER 固定值应先于 RARITY 生效");
        assertEquals(PriceSource.TIER, value.source());
    }

    @Test
    void excludedItemStaysUnpricedEvenWithTagsAndRarity() {
        FallbackPricing pricing = pricing(PricingSettings.defaults());

        Optional<FallbackPricing.PricedValue> value = pricing.price(new FallbackPricing.PricingRequest(
                item("pig_spawn_egg"), List.of("forge:ingots/iron"), Rarity.EPIC, 0L, 0L));

        assertTrue(value.isEmpty(), "排除项必须先于 TAG 与 RARITY 生效");
    }

    @Test
    void pureMultiplierTierRuleDoesNotBlockRarity() {
        TierPricing tierPricing = new TierPricing(List.of(
                new TierPricing.TierRule(
                        TierPricing.Type.PATH_CONTAINS, "special", 2.0D, null, false)));
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), tierPricing, PricingSettings.defaults());

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("special_thing"), List.of(), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(16L, value.value(), "纯倍率规则不产生固定值，应继续落到 RARITY");
        assertEquals(PriceSource.RARITY, value.source());
    }
}
