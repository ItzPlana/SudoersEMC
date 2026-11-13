package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable data produced by the collection phase of one scan pipeline.
 *
 * <p>This type carries only pure data: item snapshots, recipe snapshots, existing EMC and scan failure
 * counts. It holds no registry handles, so it can be safely handed to the later solve phase after
 * collection on the server thread, and is easy to construct directly in unit tests.</p>
 *
 * <p>All collections and maps are defensively copied at construction. {@code existingValues} is copied
 * into a {@link TreeMap} keyed ascending, guaranteeing stable traversal order for the same input. The
 * three failure counts in the record must be non-negative, so a corrupt scan result is not silently
 * accepted as "missing data".</p>
 *
 * @param items              item snapshots scanned this run, in the order the scanner returned
 * @param recipes            usable recipe snapshots scanned this run, in the order the scanner returned
 * @param existingValues     items that already have EMC; values are the old values observed at scan time
 * @param unsupportedRecipes number of recipes this build cannot evaluate
 * @param invalidRecipes     number of recipes missing an id, inputs or outputs
 * @param failedRecipes      number of recipes that threw while being described
 * @param recipeEnumerationFailed whether enumerating recipe ids failed outright; when {@code true} the
 *                                recipe statistics above are unavailable and the report must show "unknown" rather than a fake 0 or 1
 * @param failedRecipeDetails readable details of failed recipes; the report layer truncates count and length again
 * @param remapGeneration    backend remap generation observed at collection start; {@link #UNKNOWN_REMAP_GENERATION}
 *                           means the caller did not go through collection (e.g. a hand-built test), and the apply phase does no generation comparison
 */
public record PipelineSnapshot(
        List<ItemSnapshot> items,
        List<RecipeSnapshot> recipes,
        Map<ItemKey, Long> existingValues,
        int unsupportedRecipes,
        int invalidRecipes,
        int failedRecipes,
        boolean recipeEnumerationFailed,
        List<String> failedRecipeDetails,
        long remapGeneration
) {

    /**
     * Unknown remap generation: a hand-built snapshot has no collection point, so it cannot be used to
     * judge whether a remap occurred.
     */
    public static final long UNKNOWN_REMAP_GENERATION = -1L;

    public PipelineSnapshot {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
        Objects.requireNonNull(existingValues, "existingValues");
        existingValues = Collections.unmodifiableMap(new TreeMap<>(existingValues));
        requireNonNegative("unsupportedRecipes", unsupportedRecipes);
        requireNonNegative("invalidRecipes", invalidRecipes);
        requireNonNegative("failedRecipes", failedRecipes);
        List<String> details = new ArrayList<>();
        if (failedRecipeDetails != null) {
            for (String detail : failedRecipeDetails) {
                details.add(detail == null ? "" : detail);
            }
        }
        failedRecipeDetails = Collections.unmodifiableList(details);
        if (remapGeneration < UNKNOWN_REMAP_GENERATION) {
            throw new IllegalArgumentException(
                    "remapGeneration 必须 >= " + UNKNOWN_REMAP_GENERATION + "，实际为 " + remapGeneration);
        }
    }

    /**
     * Compatibility constructor for earlier callers: carries no remap generation, treated as unknown.
     */
    public PipelineSnapshot(List<ItemSnapshot> items,
                            List<RecipeSnapshot> recipes,
                            Map<ItemKey, Long> existingValues,
                            int unsupportedRecipes,
                            int invalidRecipes,
                            int failedRecipes,
                            boolean recipeEnumerationFailed,
                            List<String> failedRecipeDetails) {
        this(items, recipes, existingValues, unsupportedRecipes, invalidRecipes, failedRecipes,
                recipeEnumerationFailed, failedRecipeDetails, UNKNOWN_REMAP_GENERATION);
    }

    /**
     * Compatibility constructor for early Phase 22 callers: carries no recipe-enumeration-failed flag.
     */
    public PipelineSnapshot(List<ItemSnapshot> items,
                            List<RecipeSnapshot> recipes,
                            Map<ItemKey, Long> existingValues,
                            int unsupportedRecipes,
                            int invalidRecipes,
                            int failedRecipes,
                            List<String> failedRecipeDetails) {
        this(items, recipes, existingValues, unsupportedRecipes, invalidRecipes, failedRecipes,
                false, failedRecipeDetails);
    }

    /**
     * Compatibility constructor for Phase 21 and earlier callers: carries no enumeration-failed flag or
     * failed-recipe details.
     */
    public PipelineSnapshot(List<ItemSnapshot> items,
                            List<RecipeSnapshot> recipes,
                            Map<ItemKey, Long> existingValues,
                            int unsupportedRecipes,
                            int invalidRecipes,
                            int failedRecipes) {
        this(items, recipes, existingValues, unsupportedRecipes, invalidRecipes, failedRecipes,
                false, List.of());
    }

    /**
     * Returns a copy carrying the given remap generation; the same generation returns the current instance.
     *
     * @param generation generation observed at collection start, must be >= {@link #UNKNOWN_REMAP_GENERATION}
     * @return a snapshot carrying that generation
     */
    public PipelineSnapshot withRemapGeneration(long generation) {
        if (generation == remapGeneration) {
            return this;
        }
        return new PipelineSnapshot(
                items,
                recipes,
                existingValues,
                unsupportedRecipes,
                invalidRecipes,
                failedRecipes,
                recipeEnumerationFailed,
                failedRecipeDetails,
                generation);
    }

    /**
     * @return an empty snapshot with no items, recipes or existing values
     */
    public static PipelineSnapshot empty() {
        return new PipelineSnapshot(List.of(), List.of(), Map.of(), 0, 0, 0, false, List.of());
    }

    /**
     * @return number of item snapshots
     */
    public int itemCount() {
        return items.size();
    }

    /**
     * @return number of usable recipe snapshots
     */
    public int recipeCount() {
        return recipes.size();
    }

    /**
     * Whether this collection has no processable content at all.
     *
     * <p>Returns {@code true} only when both items and recipes are empty; having only existing EMC but no
     * item snapshots is still treated as empty, because without item identities no candidate value can be
     * produced.</p>
     *
     * @return whether the item list and recipe list are both empty
     */
    public boolean isEmpty() {
        return items.isEmpty() && recipes.isEmpty();
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " 必须 >= 0，实际为 " + value);
        }
    }
}
