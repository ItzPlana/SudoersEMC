package com.itzplana.sudoersemc.pricing;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Material-tag-based fallback pricing.
 *
 * <p>A tag only describes a material relationship, not that two items have equal value:
 * {@code forge:ingots/iron} and {@code forge:ingots/copper} both map to {@link MaterialCategory#INGOT},
 * yet their real values may differ several-fold. This class therefore configures a conservative base
 * value per {@link MaterialCategory} and allows per-item overrides.</p>
 *
 * <p>This class is pure calculation and does not read Minecraft's tag registry; tag paths are resolved by
 * the caller and passed in.</p>
 */
public final class TagPricing {

    private final Map<MaterialCategory, Long> baseValues;
    private final Map<ItemKey, Long> overrides;

    /**
     * @param baseValues base value per material category, must be non-negative; {@link MaterialCategory#UNKNOWN}
     *                   may exist as a key but is ignored on lookup
     * @param overrides  per-item fixed overrides, taking priority over category base values, must be non-negative
     */
    public TagPricing(Map<MaterialCategory, Long> baseValues, Map<ItemKey, Long> overrides) {
        Objects.requireNonNull(baseValues, "baseValues");
        Objects.requireNonNull(overrides, "overrides");

        EnumMap<MaterialCategory, Long> copiedBaseValues = new EnumMap<>(MaterialCategory.class);
        for (Map.Entry<MaterialCategory, Long> entry : baseValues.entrySet()) {
            MaterialCategory category = Objects.requireNonNull(entry.getKey(), "baseValues key");
            long value = requireNonNegative(entry.getValue(), "baseValues[" + category + "]");
            copiedBaseValues.put(category, value);
        }
        this.baseValues = Map.copyOf(copiedBaseValues);

        Map<ItemKey, Long> copiedOverrides = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Long> entry : overrides.entrySet()) {
            ItemKey key = Objects.requireNonNull(entry.getKey(), "overrides key");
            long value = requireNonNegative(entry.getValue(), "overrides[" + key + "]");
            copiedOverrides.put(key, value);
        }
        this.overrides = Map.copyOf(copiedOverrides);
    }

    /**
     * Conservative material-category base values.
     *
     * <p>The default takes a low-tier ingot {@code 64} as a conservative floor and aligns nuggets and
     * storage blocks by a factor of 9: {@code NUGGET = INGOT / 9}, {@code STORAGE_BLOCK = INGOT * 9},
     * avoiding a "9 ingots → 1 block" arbitrage within the default anchors. Other categories likewise take
     * fractions or multiples of the ingot rather than the real iron values {@code 256/2304}.
     * Material-specific overrides are supplied later by configuration; the default contains no per-item
     * overrides.</p>
     *
     * <p><strong>Risk note:</strong> {@link MaterialCategory} is shared across materials. Cheap renewable
     * mod materials (e.g. copper, tin) may still be overvalued relative to ProjectE's real values; when
     * ProjectE has priced the raw material but not the corresponding block, a "cheap raw → expensive
     * block" arbitrage remains possible. Before enabling in production, correct such materials via
     * per-item overrides or ProjectE manual values; this class currently has no per-tag/per-material
     * configuration entry.</p>
     *
     * @return a tag pricer using default category base values
     */
    public static TagPricing defaults() {
        EnumMap<MaterialCategory, Long> baseValues = new EnumMap<>(MaterialCategory.class);
        long ingot = 64L;
        baseValues.put(MaterialCategory.INGOT, ingot);
        baseValues.put(MaterialCategory.NUGGET, ingot / 9L);
        baseValues.put(MaterialCategory.STORAGE_BLOCK, ingot * 9L);
        baseValues.put(MaterialCategory.GEM, ingot * 2L);
        baseValues.put(MaterialCategory.DUST, ingot / 4L);
        baseValues.put(MaterialCategory.RAW_MATERIAL, ingot / 2L);
        baseValues.put(MaterialCategory.PLATE, ingot);
        baseValues.put(MaterialCategory.ROD, ingot / 2L);
        baseValues.put(MaterialCategory.ORE, ingot / 2L);
        return new TagPricing(baseValues, Map.of());
    }

    /**
     * Computes an item's tag base price.
     *
     * <p>Checks the per-item override first; the override applies regardless of whether the tag list is
     * empty. Without an override, looks up the first category in the given order that is recognisable and
     * has a configured base value. Unrecognised categories ({@link MaterialCategory#UNKNOWN}) are skipped.
     * An empty or {@code null} tag list returns {@link Optional#empty()}. This method never throws.</p>
     *
     * @param item     the item to price; returns empty when {@code null}
     * @param tagPaths tag paths the item has, in priority order
     * @return the hit price; empty when none
     */
    public Optional<Long> price(ItemKey item, List<String> tagPaths) {
        if (item == null) {
            return Optional.empty();
        }

        Long override = overrides.get(item);
        if (override != null) {
            return Optional.of(override);
        }

        if (tagPaths == null || tagPaths.isEmpty()) {
            return Optional.empty();
        }

        for (String tagPath : tagPaths) {
            if (tagPath == null || tagPath.isBlank()) {
                continue;
            }
            MaterialCategory category = MaterialCategory.fromTagPath(tagPath);
            if (category == MaterialCategory.UNKNOWN) {
                continue;
            }
            Long baseValue = baseValues.get(category);
            if (baseValue != null) {
                return Optional.of(baseValue);
            }
        }
        return Optional.empty();
    }

    /**
     * @return immutable view of category base values
     */
    public Map<MaterialCategory, Long> baseValues() {
        return baseValues;
    }

    /**
     * @return immutable view of per-item overrides
     */
    public Map<ItemKey, Long> overrides() {
        return overrides;
    }

    private static long requireNonNegative(Long value, String name) {
        Objects.requireNonNull(value, name);
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
        return value;
    }
}
