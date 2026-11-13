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
 * Fallback pricing chain priority tests.
 *
 * <p>Must strictly follow {@code EXISTING → exclusion check → EXTERNAL_BASELINE → TAG → TIER → RARITY → FALLBACK}.
 * Tier exclusion precedes all pricing; a pure-multiplier tier rule returns empty, so it does not block subsequent
 * rarity and generic fallback values.</p>
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

        // This item matches no tier rule, so it falls through to rarity pricing
        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("minecraft_air_like"), List.of(), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(PriceSource.RARITY, value.source());
    }

    @Test
    void tierExclusionYieldsNoPriceEvenWhenRarityPricingIsDisabled() {
        FallbackPricing pricing = new FallbackPricing(
                tagPricing(), RarityPricing.defaults(), TierPricing.defaults(),
                new PricingSettings(true, false, true, true, 1L));

        // Default rules mark spawn_egg as excluded; exclusion returns 0, so the chain should terminate rather than produce a 0 price
        Optional<FallbackPricing.PricedValue> value = pricing.price(new FallbackPricing.PricingRequest(
                item("pig_spawn_egg"), List.of(), Rarity.COMMON, 0L, 0L));

        assertTrue(value.isEmpty(), "被排除的物品不应被定价");
    }

    @Test
    void tierExclusionTerminatesTheChainInsteadOfFallingBack() {
        // Enable the generic fallback value to verify an "explicit exclusion" is not overridden by the generic fallback
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

        // Empty tag list → skip TAG; rarity defaults to COMMON 16
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
    void defaultSettingsStillHonourTierExclusions() {
        FallbackPricing pricing = pricing(PricingSettings.defaults());

        Optional<FallbackPricing.PricedValue> value = pricing.price(new FallbackPricing.PricingRequest(
                item("pig_spawn_egg"), List.of(), Rarity.COMMON, 0L, 0L));

        assertTrue(value.isEmpty(), "默认配置下层级排除必须先于稀有度生效");
    }

    @Test
    void excludedItemStaysUnpricedEvenWithTagsAndBaseline() {
        FallbackPricing pricing = pricing(PricingSettings.defaults());

        Optional<FallbackPricing.PricedValue> value = pricing.price(new FallbackPricing.PricingRequest(
                item("pig_spawn_egg"), List.of("forge:ingots/iron"), Rarity.EPIC, 0L, 500L));

        assertTrue(value.isEmpty(), "排除项必须先于外部基线、TAG 与 RARITY 生效");
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

    @Test
    void zeroFromNonTierSourcesRepresentsAFreePrice() {
        TagPricing zeroTagPricing = new TagPricing(Map.of(MaterialCategory.INGOT, 0L), Map.of());
        FallbackPricing pricing = new FallbackPricing(
                zeroTagPricing, RarityPricing.defaults(), TierPricing.defaults(), PricingSettings.defaults());

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                item("free_ingot"), List.of("forge:ingots/iron"), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(0L, value.value(), "非 TIER 来源显式配置的 0 价应作为免费价格返回");
        assertEquals(PriceSource.TAG, value.source());
    }
}
