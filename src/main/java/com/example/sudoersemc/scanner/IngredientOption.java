package com.example.sudoersemc.scanner;

import com.example.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Objects;

/**
 * One acceptable choice for a recipe slot.
 *
 * <p>Minecraft ingredients are either a single item or a tag with many members. All candidates are
 * kept so the evaluator can pick the cheapest one whose EMC is already known.</p>
 *
 * @param candidates every item that satisfies the slot; never empty
 */
public record IngredientOption(List<ItemKey> candidates) {

    public IngredientOption {
        Objects.requireNonNull(candidates, "candidates");
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("IngredientOption requires at least one candidate");
        }
        candidates = List.copyOf(candidates);
    }

    public static IngredientOption of(ItemKey item) {
        return new IngredientOption(List.of(item));
    }

    /**
     * @return {@code true} when this option offers more than one item (a tag or a multi-item ingredient)
     */
    public boolean isMultiChoice() {
        return candidates.size() > 1;
    }
}
