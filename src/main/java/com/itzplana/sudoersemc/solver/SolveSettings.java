package com.itzplana.sudoersemc.solver;

import java.util.Objects;

/**
 * Convergence, diagnostic and recipe-selection parameters for one recipe solve.
 *
 * <p>These parameters only affect the solving process; they never change any existing EMC value:
 * existing values always win, and generated candidates are published only into gaps.</p>
 *
 * @param maxPasses                maximum iteration passes, must be {@code >= 1}; default 20
 * @param allowValueIncrease       whether later passes may raise a value already generated this run; default {@code false}
 * @param minimumEmc               minimum EMC handed to evaluators, must be {@code >= 0}; default 1
 * @param maxAlternativeCostsPerItem maximum alternative costs kept per item, must be {@code >= 0}; default 8
 * @param selectionStrategy        tie-break strategy when several same-cost recipes produce one item; default
 *                                 {@link RecipeSelectionStrategy#LOWEST_COST}
 */
public record SolveSettings(
        int maxPasses,
        boolean allowValueIncrease,
        long minimumEmc,
        int maxAlternativeCostsPerItem,
        RecipeSelectionStrategy selectionStrategy
) {

    /** Default maximum iteration passes. */
    public static final int DEFAULT_MAX_PASSES = 20;

    /** Default minimum EMC. */
    public static final long DEFAULT_MINIMUM_EMC = 1L;

    /** Default number of alternative costs kept per item. */
    public static final int DEFAULT_MAX_ALTERNATIVE_COSTS = 8;

    public SolveSettings {
        if (maxPasses < 1) {
            throw new IllegalArgumentException("maxPasses 必须 >= 1，实际为 " + maxPasses);
        }
        if (minimumEmc < 0L) {
            throw new IllegalArgumentException("minimumEmc 必须 >= 0，实际为 " + minimumEmc);
        }
        if (maxAlternativeCostsPerItem < 0) {
            throw new IllegalArgumentException(
                    "maxAlternativeCostsPerItem 必须 >= 0，实际为 " + maxAlternativeCostsPerItem);
        }
        selectionStrategy = Objects.requireNonNull(selectionStrategy, "selectionStrategy");
    }

    /**
     * Compatibility constructor for Phase 22 and earlier callers: uses the default recipe selection strategy.
     */
    public SolveSettings(int maxPasses, boolean allowValueIncrease, long minimumEmc,
                         int maxAlternativeCostsPerItem) {
        this(maxPasses, allowValueIncrease, minimumEmc, maxAlternativeCostsPerItem,
                RecipeSelectionStrategy.DEFAULT);
    }

    /**
     * @return production defaults: at most 20 passes, no price increase, minimum EMC 1, 8 alternative
     *         costs per item, ties broken by recipe id
     */
    public static SolveSettings defaults() {
        return new SolveSettings(DEFAULT_MAX_PASSES, false, DEFAULT_MINIMUM_EMC,
                DEFAULT_MAX_ALTERNATIVE_COSTS);
    }
}
