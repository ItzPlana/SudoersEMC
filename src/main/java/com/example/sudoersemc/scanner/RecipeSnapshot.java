package com.example.sudoersemc.scanner;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable, Minecraft-independent description of one recipe.
 *
 * <p>Scanners translate live {@code Recipe} objects into this form on the server thread. Everything
 * after that point is pure data, which keeps evaluation unit-testable and safe to run on a worker
 * thread.</p>
 *
 * @param id                   recipe id
 * @param kind                 recipe family
 * @param inputs               consumed slots
 * @param outputs              produced stacks; the first entry is the primary output
 * @param containerRemainders  stacks returned to the crafter (buckets, empty bottles, ...)
 * @param catalyst             {@code true} when the recipe's main tool or station is not consumed
 * @param attributes           extra type-specific data (for example a ritual subtype)
 */
public record RecipeSnapshot(
        ResourceLocation id,
        RecipeKind kind,
        List<RecipeInput> inputs,
        List<RecipeOutput> outputs,
        List<RecipeOutput> containerRemainders,
        boolean catalyst,
        Map<String, String> attributes
) {

    public RecipeSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        outputs = List.copyOf(Objects.requireNonNull(outputs, "outputs"));
        containerRemainders = List.copyOf(Objects.requireNonNull(containerRemainders, "containerRemainders"));
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    /**
     * Convenience constructor for the common case: no remainders, no catalyst, no attributes.
     */
    public static RecipeSnapshot of(ResourceLocation id, RecipeKind kind,
                                    List<RecipeInput> inputs, List<RecipeOutput> outputs) {
        return new RecipeSnapshot(id, kind, inputs, outputs, List.of(), false, Map.of());
    }

    public boolean hasOutputs() {
        return !outputs.isEmpty();
    }

    /**
     * @return the primary output, or {@code null} when the recipe produces nothing
     */
    @Nullable
    public RecipeOutput primaryOutput() {
        return outputs.isEmpty() ? null : outputs.get(0);
    }

    public boolean hasMultipleOutputs() {
        return outputs.size() > 1;
    }

    public boolean hasContainerRemainders() {
        return !containerRemainders.isEmpty();
    }

    @Nullable
    public String attribute(String key) {
        return attributes.get(key);
    }

    /**
     * @param key attribute name
     * @return the attribute value, or {@code fallback} when absent
     */
    public String attributeOr(String key, String fallback) {
        return attributes.getOrDefault(key, fallback);
    }
}
