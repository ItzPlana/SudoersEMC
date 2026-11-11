package com.itzplana.sudoersemc.core;

import java.util.Map;

/**
 * Write-side contract for the ownership store.
 *
 * <p>Kept in {@code core} so the ProjectE integration and the persistence layer can be developed
 * and compiled independently.</p>
 */
public interface OwnedEntrySink {

    /**
     * Stages an entry for persistence.
     *
     * @return {@code true} if the entry was accepted; {@code false} if it was rejected, for example
     *         because the value is invalid or the item is protected
     */
    boolean stage(GeneratedEmcEntry entry);

    /**
     * @return an immutable snapshot of every recorded entry, owned or not
     */
    Map<ItemKey, GeneratedEmcEntry> snapshotAll();

    /**
     * @return {@code true} if the item is recorded as owned by SudoersEMC
     */
    boolean isOwned(ItemKey key);
}
