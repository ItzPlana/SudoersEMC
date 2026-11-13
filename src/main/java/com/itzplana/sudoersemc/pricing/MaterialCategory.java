package com.itzplana.sudoersemc.pricing;

import java.util.Locale;

/**
 * Material category of an item.
 *
 * <p>A tag only expresses a "material relationship", not that two items have equal value. For example
 * {@code forge:ingots/iron} and {@code forge:ingots/gold} are both {@link #INGOT}, yet their real values
 * differ greatly. The category is therefore only an anchor for relative price; the concrete number is
 * decided by {@link TagPricing}'s configuration.</p>
 *
 * <p>This enum is a pure calculation type and does not read Minecraft's tag registry; tag paths are
 * resolved by the caller and passed in.</p>
 */
public enum MaterialCategory {

    /** Ingot materials, e.g. {@code forge:ingots/iron}. */
    INGOT,

    /** Nugget materials, e.g. {@code forge:nuggets/iron}. */
    NUGGET,

    /** Gem materials, e.g. {@code forge:gems/diamond}. */
    GEM,

    /** Dust materials, e.g. {@code forge:dusts/redstone}. */
    DUST,

    /** Raw material ores, e.g. {@code forge:raw_materials/iron}. */
    RAW_MATERIAL,

    /** Storage block materials, e.g. {@code forge:storage_blocks/iron}. */
    STORAGE_BLOCK,

    /** Plate materials, e.g. {@code forge:plates/iron}. */
    PLATE,

    /** Rod materials, e.g. {@code forge:rods/iron}. */
    ROD,

    /** Ore materials, e.g. {@code forge:ores/iron}. */
    ORE,

    /** Unrecognised material category. */
    UNKNOWN;

    /**
     * Infers the material category from a tag path.
     *
     * <p>Supports both namespaced ({@code forge:ingots/iron}, {@code create:plates/iron}) and
     * non-namespaced ({@code ingots/iron}) forms, and accepts singular and plural (e.g. {@code ingot} and
     * {@code ingots}). Unrecognised, blank or {@code null} paths all return {@link #UNKNOWN}; this method
     * never throws.</p>
     *
     * <p>Only the first path segment after the namespace is read as the category name, so deeper tags such
     * as {@code forge:ores/iron/deepslate} are also recognised as {@link #ORE}.</p>
     *
     * @param tagPath tag path, e.g. {@code forge:storage_blocks/iron}
     * @return the corresponding material category; {@link #UNKNOWN} when unrecognised
     */
    public static MaterialCategory fromTagPath(String tagPath) {
        if (tagPath == null) {
            return UNKNOWN;
        }
        String normalized = tagPath.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.isEmpty()) {
            return UNKNOWN;
        }
        int namespaceSeparator = normalized.lastIndexOf(':');
        if (namespaceSeparator >= 0) {
            normalized = normalized.substring(namespaceSeparator + 1);
        }
        int pathSeparator = normalized.indexOf('/');
        String head = pathSeparator >= 0 ? normalized.substring(0, pathSeparator) : normalized;
        if (head.isEmpty()) {
            return UNKNOWN;
        }
        return switch (head) {
            case "ingot", "ingots" -> INGOT;
            case "nugget", "nuggets" -> NUGGET;
            case "gem", "gems" -> GEM;
            case "dust", "dusts" -> DUST;
            case "raw_material", "raw_materials" -> RAW_MATERIAL;
            case "storage_block", "storage_blocks" -> STORAGE_BLOCK;
            case "plate", "plates" -> PLATE;
            case "rod", "rods" -> ROD;
            case "ore", "ores" -> ORE;
            default -> UNKNOWN;
        };
    }
}
