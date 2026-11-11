package com.example.sudoersemc.core;

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
}
