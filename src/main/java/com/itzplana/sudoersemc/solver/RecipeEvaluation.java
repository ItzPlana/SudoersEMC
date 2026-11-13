package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Result of evaluating one recipe.
 *
 * @param valid            whether a usable unit EMC value was produced
 * @param totalInputEmc    summed EMC of everything consumed, before remainder credit
 * @param outputUnitEmc    value assigned to one unit of the primary output; {@code 0} when invalid
 * @param unresolvedInputs inputs whose EMC is still unknown
 * @param reason           short machine-readable explanation, {@code null} when valid
 */
public record RecipeEvaluation(
        boolean valid,
        long totalInputEmc,
        long outputUnitEmc,
        List<ItemKey> unresolvedInputs,
        @Nullable String reason
) {

    public RecipeEvaluation {
        if (totalInputEmc < 0) {
            throw new IllegalArgumentException("totalInputEmc must be >= 0");
        }
        if (outputUnitEmc < 0) {
            throw new IllegalArgumentException("outputUnitEmc must be >= 0");
        }
        unresolvedInputs = List.copyOf(unresolvedInputs);
    }

    public static RecipeEvaluation valid(long totalInputEmc, long outputUnitEmc) {
        return new RecipeEvaluation(true, totalInputEmc, outputUnitEmc, List.of(), null);
    }

    public static RecipeEvaluation invalid(String reason, List<ItemKey> unresolvedInputs) {
        return new RecipeEvaluation(false, 0L, 0L, unresolvedInputs, reason);
    }

    public static RecipeEvaluation invalid(String reason) {
        return invalid(reason, List.of());
    }

    /**
     * @return {@code true} when the result is usable but worth flagging in diagnostics
     */
    public boolean isZeroValued() {
        return valid && outputUnitEmc == 0L;
    }
}
