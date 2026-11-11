package com.example.sudoersemc.core;

import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One EMC value produced or adopted by SudoersEMC.
 *
 * @param item      item identity, including any economically relevant variant
 * @param value     the EMC value in ProjectE units; always {@code >= 0}
 * @param source    how the value was produced
 * @param recipeId  the recipe that produced the value, or {@code null} for non-recipe sources
 * @param owned     whether SudoersEMC owns this entry and may update or remove it later
 * @param timestamp epoch milliseconds when the entry was last generated
 */
public record GeneratedEmcEntry(
        ItemKey item,
        long value,
        PriceSource source,
        @Nullable ResourceLocation recipeId,
        boolean owned,
        long timestamp
) {

    public GeneratedEmcEntry {
        if (value < 0) {
            throw new IllegalArgumentException("EMC value must be >= 0, got " + value);
        }
    }

    public GeneratedEmcEntry withValue(long newValue, long newTimestamp) {
        return new GeneratedEmcEntry(item, newValue, source, recipeId, owned, newTimestamp);
    }

    public GeneratedEmcEntry withOwned(boolean newOwned) {
        return new GeneratedEmcEntry(item, value, source, recipeId, newOwned, timestamp);
    }
}
