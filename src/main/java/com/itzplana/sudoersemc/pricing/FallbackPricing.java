package com.itzplana.sudoersemc.pricing;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.item.Rarity;

/**
 * Fallback pricing chain: when recipe solving cannot produce a value, selects a price source for an item
 * by fixed priority.
 *
 * <p>Priority from high to low (must match this order exactly):</p>
 * <ol>
 *   <li>{@link PriceSource#EXISTING}: the item already has EMC greater than 0;</li>
 *   <li>exclusion check: an item explicitly excluded by a tier rule terminates immediately and takes part
 *       in no further pricing;</li>
 *   <li>{@link PriceSource#EXTERNAL_BASELINE}: external baseline value greater than 0;</li>
 *   <li>{@link PriceSource#TAG}: hits a material-tag base value (controlled by {@link PricingSettings#useTagPricing()});</li>
 *   <li>{@link PriceSource#TIER}: hits a tier fixed value (controlled by {@link PricingSettings#useTierPricing()});</li>
 *   <li>{@link PriceSource#RARITY}: value by rarity (controlled by {@link PricingSettings#useRarityPricing()});</li>
 *   <li>{@link PriceSource#FALLBACK}: generic fallback value (controlled by
 *       {@link PricingSettings#useFallbackForUnsolvable()}).</li>
 * </ol>
 *
 * <p>A tier rule returning {@code Optional.of(0L)} means "explicitly excluded". This class then returns
 * {@link Optional#empty()} directly and never treats an excluded item as free. A negative value is never
 * returned under any circumstance.</p>
 *
 * <p>Exclusion semantics and price priority are two different things: exclusion means "refuse to price",
 * not "a price source". Rarity pricing returns a value for any input, so if the exclusion check came after
 * it the tier exclusion rule would be unreachable; therefore the exclusion check precedes every pricing
 * step.</p>
 *
 * <p>A pure-multiplier tier rule does not produce a price on its own: {@link TierPricing#price} returns
 * empty for it and the chain continues to rarity and the generic fallback. Callers needing scaling should
 * use {@link TierPricing#multiplierFor} themselves.</p>
 *
 * <p>{@link PricedValue#value()} of {@code 0} means a "free price" (explicitly configured as 0), not
 * unpriced; unpriced is always expressed as {@link Optional#empty()}.</p>
 */
public final class FallbackPricing {

    /**
     * One fallback pricing request.
     *
     * <p>The tag list is copied and {@code null} elements filtered; negative existing values and external
     * baselines are treated as 0 so the chain never returns a negative value.</p>
     *
     * @param item             the item to price
     * @param tagPaths         tag paths the item has
     * @param rarity           item rarity, may be {@code null}
     * @param existingValue    the item's current EMC; {@code 0} means none
     * @param externalBaseline external baseline value; {@code 0} means none
     */
    public record PricingRequest(
            ItemKey item,
            List<String> tagPaths,
            Rarity rarity,
            long existingValue,
            long externalBaseline
    ) {

        public PricingRequest {
            Objects.requireNonNull(item, "item");
            tagPaths = copyTagPaths(tagPaths);
            existingValue = Math.max(0L, existingValue);
            externalBaseline = Math.max(0L, externalBaseline);
        }

        private static List<String> copyTagPaths(List<String> tagPaths) {
            if (tagPaths == null || tagPaths.isEmpty()) {
                return List.of();
            }
            List<String> copy = new ArrayList<>(tagPaths.size());
            for (String tagPath : tagPaths) {
                if (tagPath != null) {
                    copy.add(tagPath);
                }
            }
            return List.copyOf(copy);
        }
    }

    /**
     * Result of one fallback pricing.
     *
     * @param item   the priced item
     * @param value  non-negative price
     * @param source price source
     * @param reason short reason marker for diagnostics
     */
    public record PricedValue(ItemKey item, long value, PriceSource source, String reason) {

        public PricedValue {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reason, "reason");
            value = Math.max(0L, value);
        }
    }

    private final TagPricing tagPricing;
    private final RarityPricing rarityPricing;
    private final TierPricing tierPricing;
    private final PricingSettings settings;

    public FallbackPricing(TagPricing tagPricing, RarityPricing rarityPricing, TierPricing tierPricing,
                           PricingSettings settings) {
        this.tagPricing = Objects.requireNonNull(tagPricing, "tagPricing");
        this.rarityPricing = Objects.requireNonNull(rarityPricing, "rarityPricing");
        this.tierPricing = Objects.requireNonNull(tierPricing, "tierPricing");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * Returns the tier rules used by the pricing chain.
     *
     * <p>The scan pipeline needs to reuse {@link TierPricing#price}'s exclusion semantics
     * ({@code Optional.of(0L)}) so it can refuse pricing early even when an excluded item has a recipe,
     * rather than relying only on the fallback chain.</p>
     *
     * @return the tier pricer used by this instance, never {@code null}
     */
    public TierPricing tierPricing() {
        return tierPricing;
    }

    /**
     * Creates a pricing chain with default tag anchors, default rarity pricing and default tier rules.
     *
     * @param settings pricing switches and generic fallback value
     * @return a ready-to-use fallback pricing chain
     */
    public static FallbackPricing withDefaults(PricingSettings settings) {
        return new FallbackPricing(
                TagPricing.defaults(),
                RarityPricing.defaults(),
                TierPricing.defaults(),
                settings
        );
    }


    /**
     * Prices in the order {@code EXISTING → exclusion check → EXTERNAL_BASELINE → TAG → TIER → RARITY → FALLBACK}.
     *
     * <p>The exclusion check precedes the external baseline: the baseline is just "another pricing source"
     * and must never turn explicitly excluded items such as spawn eggs or command blocks into tradeable
     * resources. A tier exclusion marker ({@code 0L}) terminates the whole chain immediately and returns
     * {@link Optional#empty()}. A pure-multiplier tier rule returns empty, so it does not block subsequent
     * rarity pricing.</p>
     *
     * @param request pricing request
     * @return the hit price and source; empty when unpriced
     */
    public Optional<PricedValue> price(PricingRequest request) {
        Objects.requireNonNull(request, "request");
        ItemKey item = request.item();

        long existingValue = request.existingValue();
        if (existingValue > 0L) {
            return Optional.of(new PricedValue(item, existingValue, PriceSource.EXISTING, "existing-value"));
        }

        // Exclusion semantics and price priority are two different things: exclusion means "refuse to price", not "a price source".
        // Rarity pricing returns a value for any input; if the exclusion check came after it, the tier exclusion rule would be
        // unreachable and items like spawn eggs or command blocks would be mispriced. So the exclusion check must precede every pricing step.
        if (settings.useTierPricing() && isExcluded(item)) {
            return Optional.empty();
        }

        long externalBaseline = request.externalBaseline();
        if (externalBaseline > 0L) {
            return Optional.of(new PricedValue(
                    item, externalBaseline, PriceSource.EXTERNAL_BASELINE, "external-baseline"));
        }

        if (settings.useTagPricing()) {
            Optional<Long> tagValue = tagPricing.price(item, request.tagPaths());
            if (tagValue.isPresent()) {
                return Optional.of(new PricedValue(item, tagValue.get(), PriceSource.TAG, "tag-category"));
            }
        }

        if (settings.useTierPricing()) {
            Optional<Long> tierValue = tierPricing.price(item);
            if (tierValue.isPresent()) {
                long value = tierValue.get();
                if (value <= 0L) {
                    // Explicit exclusion rule: rather leave the item unpriced than turn it into a free item
                    return Optional.empty();
                }
                return Optional.of(new PricedValue(item, value, PriceSource.TIER, "tier-rule"));
            }
        }

        if (settings.useRarityPricing()) {
            long rarityValue = rarityPricing.price(request.rarity());
            String rarityName = request.rarity() == null ? "UNKNOWN" : request.rarity().name();
            return Optional.of(new PricedValue(
                    item, rarityValue, PriceSource.RARITY, "rarity:" + rarityName));
        }

        if (settings.useFallbackForUnsolvable()) {
            return Optional.of(new PricedValue(
                    item, settings.genericFallbackValue(), PriceSource.FALLBACK, "generic-fallback"));
        }

        return Optional.empty();
    }

    /**
     * Whether the item is explicitly excluded by a tier rule.
     *
     * <p>{@link TierPricing#price} uses {@code Optional.of(0L)} to mean "explicitly excluded". This is
     * extracted separately because exclusion and pricing are two semantics: exclusion must take effect
     * before any pricing step, or it would be shadowed by rarity pricing, which always returns a value.</p>
     */
    private boolean isExcluded(ItemKey item) {
        Optional<Long> tierValue = tierPricing.price(item);
        return tierValue.isPresent() && tierValue.get() <= 0L;
    }
}
