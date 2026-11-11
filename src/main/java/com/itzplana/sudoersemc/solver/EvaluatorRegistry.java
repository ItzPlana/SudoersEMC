package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Ordered registry of {@link RecipeEvaluator} implementations.
 *
 * <p>Evaluators are consulted in registration order, so specialised evaluators must be registered
 * before the generic ones. The registry is immutable after {@link #build()}.</p>
 */
public final class EvaluatorRegistry {

    private final List<RecipeEvaluator> evaluators;

    private EvaluatorRegistry(List<RecipeEvaluator> evaluators) {
        this.evaluators = evaluators;
    }

    /**
     * @return a registry with the Goety ritual evaluator first and the standard evaluator last
     */
    public static EvaluatorRegistry defaults() {
        return new EvaluatorRegistry(List.of(
                new GoetyRitualEvaluator(),
                new StandardRecipeEvaluator()));
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * @return the first evaluator that supports the recipe, or {@code null} when none does
     */
    @Nullable
    public RecipeEvaluator find(RecipeSnapshot recipe) {
        for (RecipeEvaluator evaluator : evaluators) {
            if (evaluator.supports(recipe)) {
                return evaluator;
            }
        }
        return null;
    }

    /**
     * Evaluates a recipe with the first supporting evaluator.
     *
     * <p>An unsupported recipe is reported as invalid with reason {@code unsupported-kind} instead
     * of throwing, so one unknown recipe type cannot abort a scan.</p>
     */
    public RecipeEvaluation evaluate(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
        RecipeEvaluator evaluator = find(recipe);
        if (evaluator == null) {
            return RecipeEvaluation.invalid("unsupported-kind:" + recipe.kind().name());
        }
        try {
            RecipeEvaluation result = evaluator.evaluate(recipe, lookup, policy);
            return result == null ? RecipeEvaluation.invalid("evaluator-returned-null") : result;
        } catch (RuntimeException e) {
            // Isolate per-recipe failures: a single malformed recipe must never abort a scan.
            return RecipeEvaluation.invalid("evaluator-error:" + e.getClass().getSimpleName());
        }
    }

    public int size() {
        return evaluators.size();
    }

    /** Mutable builder used while assembling the pipeline. */
    public static final class Builder {

        private final List<RecipeEvaluator> evaluators = new ArrayList<>();

        private Builder() {
        }

        public Builder add(RecipeEvaluator evaluator) {
            evaluators.add(evaluator);
            return this;
        }

        public EvaluatorRegistry build() {
            return new EvaluatorRegistry(List.copyOf(evaluators));
        }
    }
}
