package com.example.sudoersemc.solver;

import com.example.sudoersemc.core.ItemKey;

/**
 * Read-only EMC lookup used by evaluators and the solver.
 *
 * <p>Implementations are supplied by the pipeline so the calculation layer never touches ProjectE
 * or Minecraft registries directly.</p>
 */
@FunctionalInterface
public interface EmcLookup {

    /**
     * @return the known EMC value of the item, or {@code 0} when it has none
     */
    long emcOf(ItemKey item);

    /**
     * @return {@code true} when the item already has a usable EMC value
     */
    default boolean isKnown(ItemKey item) {
        return emcOf(item) > 0L;
    }
}
