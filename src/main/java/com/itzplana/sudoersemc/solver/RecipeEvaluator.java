package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.scanner.RecipeSnapshot;

/**
 * Turns one {@link RecipeSnapshot} into at most one unit EMC value.
 *
 * <p>Implementations must be pure: no Minecraft registries, no ProjectE access, no file I/O. All
 * lookups go through {@link EmcLookup}.</p>
 */
public interface RecipeEvaluator {

    /**
     * @return {@code true} when this evaluator handles the recipe family
     */
    boolean supports(RecipeSnapshot recipe);

    /**
     * Evaluates the recipe. Implementations must never throw for malformed input; return an invalid
     * result with a reason instead.
     */
    RecipeEvaluation evaluate(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy);
}
