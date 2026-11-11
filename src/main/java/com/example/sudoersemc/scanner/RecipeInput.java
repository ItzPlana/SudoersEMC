package com.example.sudoersemc.scanner;

import java.util.Objects;

/**
 * One consumed recipe slot.
 *
 * @param option what may be consumed
 * @param count  how many items this slot consumes; always {@code >= 1}
 */
public record RecipeInput(IngredientOption option, int count) {

    public RecipeInput {
        Objects.requireNonNull(option, "option");
        if (count < 1) {
            throw new IllegalArgumentException("Recipe input count must be >= 1, got " + count);
        }
    }

    public static RecipeInput of(IngredientOption option) {
        return new RecipeInput(option, 1);
    }
}
