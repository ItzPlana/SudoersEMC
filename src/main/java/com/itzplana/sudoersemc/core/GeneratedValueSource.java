package com.itzplana.sudoersemc.core;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the EMC values SudoersEMC currently owns.
 *
 * <p>Implemented by the persistence layer and consumed by the ProjectE integration so the two
 * layers stay independent of each other's concrete classes.</p>
 */
@FunctionalInterface
public interface GeneratedValueSource {

    /**
     * @return an immutable snapshot of owned values keyed by item identity
     */
    Map<ItemKey, Long> snapshotOwnedValues();

    /**
     * Returns the raw observed SNBT for each owned variant entry, for ProjectE's exact-NBT registration.
     *
     * <p>{@link ItemKey#variantHash()} is the canonicalised economic identity and may have stripped
     * display fields; ProjectE matches on full NBT exactly, so the registration side needs the raw
     * values. The default implementation returns an empty map; only the persistence store needs to
     * override it.</p>
     *
     * @return an immutable snapshot from variant identity to its list of raw SNBT
     */
    default Map<ItemKey, List<String>> snapshotOwnedVariantNbts() {
        return Map.of();
    }
}
