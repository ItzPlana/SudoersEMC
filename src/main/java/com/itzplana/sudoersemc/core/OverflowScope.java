package com.itzplana.sudoersemc.core;

/**
 * Controls how far overflow protection is allowed to reach.
 *
 * <p>{@link #OWNED_ONLY} is the safe default: SudoersEMC only rewrites values it generated itself.
 * {@link #ENTIRE_CUSTOM_EMC} additionally scans ProjectE's {@code config/ProjectE/custom_emc.json}
 * for above-threshold entries owned by SudoersEMC, and therefore requires an explicit opt-in.
 * Administrator-configured (non-owned) entries are never rewritten in either mode.</p>
 */
public enum OverflowScope {
    /** Only entries recorded as owned by SudoersEMC are eligible. */
    OWNED_ONLY,
    /** Owned entries plus owned above-threshold entries in ProjectE's custom EMC file. */
    ENTIRE_CUSTOM_EMC
}
