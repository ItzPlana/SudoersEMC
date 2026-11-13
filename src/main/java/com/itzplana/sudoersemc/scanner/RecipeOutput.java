package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One produced stack, or one returned container.
 *
 * @param item      produced item identity
 * @param count     produced amount; always {@code >= 1}
 * @param outputNbt SNBT representation of the output stack; {@code null} when the stack has no NBT
 */
public record RecipeOutput(ItemKey item, int count, @Nullable String outputNbt) {

    public RecipeOutput {
        Objects.requireNonNull(item, "item");
        if (count < 1) {
            throw new IllegalArgumentException("Recipe output count must be >= 1, got " + count);
        }
        if (outputNbt != null) {
            // Blank SNBT forms such as "{}" or "{ }" all mean an empty tag; normalise to null so
            // downstream does not treat an empty tag as a real variant and generate a variantHash.
            String compact = outputNbt.replaceAll("\\s+", "");
            if (compact.isEmpty() || "{}".equals(compact)) {
                outputNbt = null;
            }
        }
    }

    /**
     * Backward-compatible constructor: with no output NBT, equivalent to {@code outputNbt = null}.
     *
     * @param item  produced item identity
     * @param count produced amount; always {@code >= 1}
     */
    public RecipeOutput(ItemKey item, int count) {
        this(item, count, null);
    }

    /**
     * Creates a single output without NBT.
     */
    public static RecipeOutput of(ItemKey item) {
        return new RecipeOutput(item, 1, null);
    }

    /**
     * Creates a recipe output with SNBT.
     *
     * @param item      produced item identity
     * @param count     produced amount; always {@code >= 1}
     * @param outputNbt SNBT of the output stack, may be {@code null}
     */
    public static RecipeOutput of(ItemKey item, int count, @Nullable String outputNbt) {
        return new RecipeOutput(item, count, outputNbt);
    }

    /**
     * @return whether the output stack carries NBT
     */
    public boolean hasOutputNbt() {
        return outputNbt != null;
    }
}
