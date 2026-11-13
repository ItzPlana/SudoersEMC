package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;

/**
 * Final result of one recipe solve.
 *
 * <p>All collections are defensive copies with deterministic order: items ascending by {@link ItemKey},
 * recipes ascending by their id's string form. {@code values} contains only values newly generated this
 * run, not EMC that existed before the run.</p>
 *
 * @param values             values generated this run; pre-existing EMC never appears here
 * @param traces             pricing trace for each generated value
 * @param unresolvedRecipes  recipe ids still without a usable value at the end, ascending by id
 * @param unresolvedItems    items still without EMC at the end that block recipes, ascending by item
 * @param passesUsed         passes actually executed; 0 for an empty recipe list
 * @param converged          whether a full pass with "no value change" was observed before the pass limit
 * @param elapsedMillis      solve duration in milliseconds
 * @param recipesEvaluated   recipe evaluations (accumulated across passes, including cache hits and failed calls)
 * @param cyclicItems        items still without EMC at the end that lie on a dependency cycle, ascending by item; empty when acyclic
 */
public record SolveResult(
        Map<ItemKey, Long> values,
        Map<ItemKey, SolveTrace> traces,
        List<ResourceLocation> unresolvedRecipes,
        Set<ItemKey> unresolvedItems,
        int passesUsed,
        boolean converged,
        long elapsedMillis,
        int recipesEvaluated,
        Set<ItemKey> cyclicItems
) {

    public SolveResult {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(traces, "traces");
        Objects.requireNonNull(unresolvedRecipes, "unresolvedRecipes");
        Objects.requireNonNull(unresolvedItems, "unresolvedItems");
        Objects.requireNonNull(cyclicItems, "cyclicItems");
        if (passesUsed < 0) {
            throw new IllegalArgumentException("passesUsed 不能为负数，实际为 " + passesUsed);
        }
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsedMillis 不能为负数，实际为 " + elapsedMillis);
        }
        if (recipesEvaluated < 0) {
            throw new IllegalArgumentException("recipesEvaluated 不能为负数，实际为 " + recipesEvaluated);
        }
        values = Collections.unmodifiableMap(new TreeMap<>(values));
        traces = Collections.unmodifiableMap(new TreeMap<>(traces));
        unresolvedRecipes = List.copyOf(unresolvedRecipes);
        unresolvedItems = Collections.unmodifiableSet(new TreeSet<>(unresolvedItems));
        cyclicItems = Collections.unmodifiableSet(new TreeSet<>(cyclicItems));
    }

    /**
     * Compatibility constructor for callers without cycle diagnostics: equivalent to the full
     * constructor with an empty {@code cyclicItems}.
     */
    public SolveResult(Map<ItemKey, Long> values,
                       Map<ItemKey, SolveTrace> traces,
                       List<ResourceLocation> unresolvedRecipes,
                       Set<ItemKey> unresolvedItems,
                       int passesUsed,
                       boolean converged,
                       long elapsedMillis,
                       int recipesEvaluated) {
        this(values, traces, unresolvedRecipes, unresolvedItems, passesUsed, converged,
                elapsedMillis, recipesEvaluated, Set.of());
    }

    /**
     * @return number of items newly priced this run
     */
    public int solvedCount() {
        return values.size();
    }

    /**
     * @return number of recipes still unresolved at the end
     */
    public int unresolvedRecipeCount() {
        return unresolvedRecipes.size();
    }
}
