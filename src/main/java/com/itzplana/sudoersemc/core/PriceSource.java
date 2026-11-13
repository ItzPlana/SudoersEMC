package com.itzplana.sudoersemc.core;

/**
 * Where a generated EMC value came from. Every generated value must be traceable to exactly one
 * source so diagnostics and ownership checks stay explainable.
 */
public enum PriceSource {
    /** A value that already existed before SudoersEMC ran (ProjectE, another integration, manual). */
    EXISTING,
    /** A value imported from an external EMC baseline file. */
    EXTERNAL_BASELINE,
    /** Computed from a crafting recipe. */
    RECIPE,
    /** Computed from item tags and material relationships. */
    TAG,
    /** Computed from item rarity. */
    RARITY,
    /** Computed from an explicit tier rule. */
    TIER,
    /** Generic configurable fallback value. */
    FALLBACK
}
