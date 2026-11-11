package com.example.sudoersemc.scanner;

import com.example.sudoersemc.core.ItemKey;
import java.util.Objects;

/**
 * One produced stack, or one returned container.
 *
 * @param item  produced item identity
 * @param count produced amount; always {@code >= 1}
 */
public record RecipeOutput(ItemKey item, int count) {

    public RecipeOutput {
        Objects.requireNonNull(item, "item");
        if (count < 1) {
            throw new IllegalArgumentException("Recipe output count must be >= 1, got " + count);
        }
    }

    public static RecipeOutput of(ItemKey item) {
        return new RecipeOutput(item, 1);
    }
}
