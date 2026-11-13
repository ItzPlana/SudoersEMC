package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.Objects;

/**
 * Numeric and policy knobs shared by all evaluators.
 *
 * @param minimumEmc        smallest value an evaluator may emit; defaults to {@code 1}
 * @param creditRemainders  subtract returned containers from the recipe cost
 * @param priceSecondaryOutputs whether byproducts other than the primary output receive a value
 */
public record EvaluationPolicy(long minimumEmc, boolean creditRemainders, boolean priceSecondaryOutputs) {

    public static final long DEFAULT_MINIMUM_EMC = 1L;

    public EvaluationPolicy {
        if (minimumEmc < 0) {
            throw new IllegalArgumentException("minimumEmc must be >= 0, got " + minimumEmc);
        }
    }

    public static EvaluationPolicy defaults() {
        return new EvaluationPolicy(DEFAULT_MINIMUM_EMC, true, false);
    }

    /**
     * Floor integer division used for every deterministic recipe.
     *
     * @param total        total cost to distribute
     * @param outputCount  number of produced units, must be {@code >= 1}
     * @return {@code total / outputCount}, rounded down
     */
    public static long floorDivide(long total, int outputCount) {
        if (outputCount < 1) {
            throw new IllegalArgumentException("outputCount must be >= 1, got " + outputCount);
        }
        if (total <= 0L) {
            return 0L;
        }
        return total / outputCount;
    }

    /**
     * Applies the configured minimum, but never turns a genuinely free recipe into a paid one
     * unless a minimum above zero was configured explicitly.
     */
    public long applyMinimum(long value) {
        return Math.max(value, minimumEmc);
    }

    public static EvaluationPolicy of(long minimumEmc) {
        return new EvaluationPolicy(minimumEmc, true, false);
    }

    public EvaluationPolicy withMinimumEmc(long newMinimum) {
        return new EvaluationPolicy(newMinimum, creditRemainders, priceSecondaryOutputs);
    }

    /**
     * @return {@code true} when this recipe needs the multi-output policy instead of the generic formula
     */
    public static boolean needsMultiOutputPolicy(RecipeSnapshot recipe) {
        Objects.requireNonNull(recipe, "recipe");
        return recipe.hasMultipleOutputs();
    }
}
