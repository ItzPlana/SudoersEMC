package com.itzplana.sudoersemc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * One EMC value generated or adopted by SudoersEMC.
 *
 * @param item             item identity, including every economically relevant variant
 * @param value            EMC value in ProjectE units, always {@code >= 0}
 * @param source           how the value was produced
 * @param recipeId         the recipe that produced the value; {@code null} for non-recipe sources
 * @param owned            whether SudoersEMC owns this entry and may update or delete it in later scans
 * @param timestamp        epoch milliseconds when the entry was last generated
 * @param alternativeCosts distinct costs of other valid recipes in the same solve round; immutable, elements always {@code >= 0}
 */
public record GeneratedEmcEntry(
        ItemKey item,
        long value,
        PriceSource source,
        @Nullable ResourceLocation recipeId,
        boolean owned,
        long timestamp,
        List<Long> alternativeCosts
) {

    public GeneratedEmcEntry {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(source, "source");
        if (value < 0) {
            throw new IllegalArgumentException("EMC value must be >= 0, got " + value);
        }

        List<Long> copied = new ArrayList<>();
        if (alternativeCosts != null) {
            for (Long cost : alternativeCosts) {
                if (cost == null) {
                    throw new IllegalArgumentException("alternative cost must not be null");
                }
                if (cost < 0L) {
                    throw new IllegalArgumentException(
                            "alternative cost must be >= 0, got " + cost);
                }
                copied.add(cost);
            }
        }
        alternativeCosts = List.copyOf(copied);
    }

    /**
     * Compatibility constructor for older callers: carries no alternative costs.
     *
     * @param item     item identity
     * @param value    EMC value
     * @param source   value source
     * @param recipeId recipe id, may be {@code null}
     * @param owned    whether owned by SudoersEMC
     * @param timestamp generation time
     */
    public GeneratedEmcEntry(ItemKey item, long value, PriceSource source,
                             @Nullable ResourceLocation recipeId, boolean owned, long timestamp) {
        this(item, value, source, recipeId, owned, timestamp, List.of());
    }

    /**
     * @param newValue     the new EMC value
     * @param newTimestamp the new generation time
     * @return a copy preserving source, ownership and alternative costs
     */
    public GeneratedEmcEntry withValue(long newValue, long newTimestamp) {
        return new GeneratedEmcEntry(
                item, newValue, source, recipeId, owned, newTimestamp, alternativeCosts);
    }

    /**
     * @param newOwned the new ownership state
     * @return a copy preserving the value and alternative costs
     */
    public GeneratedEmcEntry withOwned(boolean newOwned) {
        return new GeneratedEmcEntry(
                item, value, source, recipeId, newOwned, timestamp, alternativeCosts);
    }

    /**
     * @param newAlternativeCosts the new alternative-cost list, may be {@code null}
     * @return a copy identical except for alternative costs
     */
    public GeneratedEmcEntry withAlternativeCosts(List<Long> newAlternativeCosts) {
        return new GeneratedEmcEntry(
                item, value, source, recipeId, owned, timestamp, newAlternativeCosts);
    }
}
