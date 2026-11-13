package com.itzplana.sudoersemc.solver;

import org.jetbrains.annotations.Nullable;

/**
 * Tie-break strategy when several viable recipes exist for one item.
 *
 * <p><strong>Economic safety:</strong> every strategy uses cost as the primary sort key and only changes
 * the order "when costs are equal". No strategy ever lets the solver accept a recipe above the lowest
 * viable cost.</p>
 *
 * <ul>
 *   <li>{@link #LOWEST_COST}: on equal cost, lexicographic recipe id; matches Phase 22 and earlier, and
 *       is the default;</li>
 *   <li>{@link #PREFER_SIMPLE}: on equal cost, prefers fewer input slots, fewer outputs, fewer container
 *       remainders and non-catalyst recipes, finally falling back to recipe id, so admins can prefer
 *       simple recipes;</li>
 *   <li>{@link #STABLE_ID}: on equal cost, explicitly selects by lexicographic recipe id for stability
 *       across runs and versions.</li>
 * </ul>
 */
public enum RecipeSelectionStrategy {

    /** Lowest cost; on equal cost, lexicographic recipe id (default). */
    LOWEST_COST("lowest_cost"),

    /** Lowest cost; on equal cost, prefer simpler recipes, finally lexicographic recipe id. */
    PREFER_SIMPLE("prefer_simple"),

    /** Lowest cost; on equal cost, explicitly lexicographic recipe id. */
    STABLE_ID("stable_id");

    /** Default strategy, preserving Phase 22 behaviour. */
    public static final RecipeSelectionStrategy DEFAULT = LOWEST_COST;

    private final String configValue;

    RecipeSelectionStrategy(String configValue) {
        this.configValue = configValue;
    }

    /**
     * @return the stable lowercase name used in the config file
     */
    public String configValue() {
        return configValue;
    }

    /**
     * Parses a config value, ignoring leading/trailing whitespace and case.
     *
     * @param rawValue raw config value, may be {@code null}
     * @return the parsed strategy; {@code null} when unrecognised, leaving fallback and warning to the caller
     */
    @Nullable
    public static RecipeSelectionStrategy parse(@Nullable String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.trim().toLowerCase(java.util.Locale.ROOT);
        for (RecipeSelectionStrategy strategy : values()) {
            if (strategy.configValue.equals(normalized)) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * @param rawValue raw config value
     * @return whether it is a recognisable strategy name (ignoring case and leading/trailing whitespace)
     */
    public static boolean isValid(@Nullable String rawValue) {
        return parse(rawValue) != null;
    }
}
