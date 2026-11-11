package com.example.sudoersemc.solver;

import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.scanner.IngredientOption;
import com.example.sudoersemc.scanner.RecipeInput;
import com.example.sudoersemc.scanner.RecipeOutput;
import com.example.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluator for every ordinary deterministic recipe: shaped, shapeless, furnace, blast furnace,
 * smoker and stonecutter.
 *
 * <p>Cost rule (documented behaviour):</p>
 * <pre>
 * netInput = sum over inputs of (cheapest known candidate EMC * count)
 *          - sum over remainders of (remainder EMC * count)   // only when creditRemainders
 * unitValue = netInput / outputCount                        // floor division
 * </pre>
 *
 * <p>For every input slot the evaluator picks the cheapest candidate whose EMC is already known.
 * A slot with no known candidate makes the recipe unresolved, and the missing candidates are
 * reported so diagnostics can explain why an item never priced.</p>
 *
 * <p>Multi-output handling is explicit and never uses the generic formula blindly:</p>
 * <ul>
 *   <li>{@code priceSecondaryOutputs == false} (default): the whole net cost is assigned to the
 *       primary output, so byproducts do not dilute the main item's price.</li>
 *   <li>{@code priceSecondaryOutputs == true}: the cost is split evenly across every produced
 *       unit, and all outputs receive the same unit value.</li>
 * </ul>
 */
public final class StandardRecipeEvaluator implements RecipeEvaluator {

    @Override
    public boolean supports(RecipeSnapshot recipe) {
        return switch (recipe.kind()) {
            case SHAPED, SHAPELESS, FURNACE, BLAST_FURNACE, SMOKER, STONECUTTER -> true;
            default -> false;
        };
    }

    @Override
    public RecipeEvaluation evaluate(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
        if (recipe == null || lookup == null || policy == null) {
            return RecipeEvaluation.invalid("null-input");
        }
        if (!recipe.hasOutputs()) {
            return RecipeEvaluation.invalid("no-output");
        }
        if (recipe.outputs().size() > 1 && policy.priceSecondaryOutputs()) {
            return evaluateSplitAcrossOutputs(recipe, lookup, policy);
        }
        return evaluatePrimaryOnly(recipe, lookup, policy);
    }

    private RecipeEvaluation evaluatePrimaryOnly(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
        RecipeOutput primary = recipe.outputs().get(0);
        CostResult cost = sumInputs(recipe.inputs(), lookup);
        if (!cost.resolved()) {
            return RecipeEvaluation.invalid("unresolved-input", cost.unresolved());
        }
        long net = applyRemainderCredit(cost.total(), recipe, lookup, policy);
        long unit = EvaluationPolicy.floorDivide(net, primary.count());
        if (unit <= 0L) {
            // A recipe that consumes free items legitimately produces a free item; report it as
            // valid with zero so the caller can decide whether to publish it.
            return RecipeEvaluation.valid(net, 0L);
        }
        return RecipeEvaluation.valid(net, policy.applyMinimum(unit));
    }

    private RecipeEvaluation evaluateSplitAcrossOutputs(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
        CostResult cost = sumInputs(recipe.inputs(), lookup);
        if (!cost.resolved()) {
            return RecipeEvaluation.invalid("unresolved-input", cost.unresolved());
        }
        long net = applyRemainderCredit(cost.total(), recipe, lookup, policy);
        int totalUnits = 0;
        for (RecipeOutput output : recipe.outputs()) {
            totalUnits += output.count();
        }
        long unit = EvaluationPolicy.floorDivide(net, totalUnits);
        // Report the primary output's unit value; secondary outputs get the same number.
        return RecipeEvaluation.valid(net, unit <= 0L ? 0L : policy.applyMinimum(unit));
    }

    /**
     * Sums the cheapest known candidate for every input slot.
     */
    static CostResult sumInputs(List<RecipeInput> inputs, EmcLookup lookup) {
        long total = 0L;
        List<ItemKey> unresolved = new ArrayList<>();
        for (RecipeInput input : inputs) {
            IngredientOption option = input.option();
            long cheapest = Long.MAX_VALUE;
            for (ItemKey candidate : option.candidates()) {
                long value = lookup.emcOf(candidate);
                if (value > 0L && value < cheapest) {
                    cheapest = value;
                }
            }
            if (cheapest == Long.MAX_VALUE) {
                unresolved.addAll(option.candidates());
                continue;
            }
            total = saturatingAdd(total, saturatingMultiply(cheapest, input.count()));
        }
        return new CostResult(total, unresolved.isEmpty(), UnresolvedCopy.of(unresolved));
    }

    /**
     * Subtracts the value of returned containers from the recipe cost.
     */
    static long applyRemainderCredit(long total, RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
        if (!policy.creditRemainders() || !recipe.hasContainerRemainders()) {
            return total;
        }
        long credit = 0L;
        for (RecipeOutput remainder : recipe.containerRemainders()) {
            long value = lookup.emcOf(remainder.item());
            if (value > 0L) {
                credit = saturatingAdd(credit, saturatingMultiply(value, remainder.count()));
            }
        }
        long net = total - credit;
        return Math.max(net, 0L);
    }

    private static long saturatingAdd(long left, long right) {
        long sum = left + right;
        return sum < 0L ? Long.MAX_VALUE : sum;
    }

    private static long saturatingMultiply(long value, int count) {
        if (value <= 0L || count <= 0) {
            return 0L;
        }
        long product = value * (long) count;
        return product / count != value ? Long.MAX_VALUE : product;
    }

    /**
     * Intermediate sum result.
     *
     * @param total      summed cost of every resolved slot
     * @param resolved   {@code false} when at least one slot had no known candidate
     * @param unresolved every candidate of the unresolved slots, for diagnostics
     */
    record CostResult(long total, boolean resolved, List<ItemKey> unresolved) {
    }

    /** Defensive copy helper so records never retain a mutable list. */
    private static final class UnresolvedCopy {

        private UnresolvedCopy() {
        }

        static List<ItemKey> of(List<ItemKey> original) {
            return original.isEmpty() ? List.of() : List.copyOf(original);
        }
    }
}
