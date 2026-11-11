package com.itzplana.sudoersemc.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.scanner.IngredientOption;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeKind;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Verifies the deterministic cost rule used by every ordinary recipe.
 */
class StandardRecipeEvaluatorTest {

    private static final EvaluationPolicy POLICY = EvaluationPolicy.defaults();
    private final StandardRecipeEvaluator evaluator = new StandardRecipeEvaluator();

    private static ItemKey item(String path) {
        return ItemKey.of(new ResourceLocation("test", path));
    }

    private static EmcLookup lookup(Map<ItemKey, Long> values) {
        return key -> values.getOrDefault(key, 0L);
    }

    private static RecipeSnapshot recipe(RecipeKind kind, List<RecipeInput> inputs, List<RecipeOutput> outputs) {
        return RecipeSnapshot.of(new ResourceLocation("test", "recipe"), kind, inputs, outputs);
    }

    private static RecipeInput input(ItemKey item, int count) {
        return new RecipeInput(IngredientOption.of(item), count);
    }

    @Test
    void singleInputSingleOutputDividesEvenly() {
        ItemKey plank = item("plank");
        ItemKey stick = item("stick");
        RecipeSnapshot snapshot = recipe(RecipeKind.SHAPED,
                List.of(input(plank, 2)), List.of(new RecipeOutput(stick, 4)));

        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(plank, 100L)), POLICY);

        assertTrue(result.valid());
        assertEquals(200L, result.totalInputEmc());
        // 200 / 4 = 50, exact floor division
        assertEquals(50L, result.outputUnitEmc());
    }

    @Test
    void floorDivisionRoundsDown() {
        ItemKey ore = item("ore");
        ItemKey nugget = item("nugget");
        RecipeSnapshot snapshot = recipe(RecipeKind.SHAPELESS,
                List.of(input(ore, 1)), List.of(new RecipeOutput(nugget, 3)));

        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(ore, 100L)), POLICY);

        assertTrue(result.valid());
        // 100 / 3 = 33.33 -> 33, never 34
        assertEquals(33L, result.outputUnitEmc());
    }

    @Test
    void multipleInputsAreSummed() {
        ItemKey a = item("a");
        ItemKey b = item("b");
        ItemKey out = item("out");
        RecipeSnapshot snapshot = recipe(RecipeKind.SHAPED,
                List.of(input(a, 2), input(b, 3)),
                List.of(new RecipeOutput(out, 1)));

        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(a, 10L, b, 5L)), POLICY);

        assertTrue(result.valid());
        assertEquals(2 * 10L + 3 * 5L, result.totalInputEmc());
        assertEquals(35L, result.outputUnitEmc());
    }

    @Test
    void missingInputMakesRecipeUnresolved() {
        ItemKey known = item("known");
        ItemKey unknown = item("unknown");
        ItemKey out = item("out");
        RecipeSnapshot snapshot = recipe(RecipeKind.FURNACE,
                List.of(input(known, 1), input(unknown, 1)),
                List.of(new RecipeOutput(out, 1)));

        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(known, 10L)), POLICY);

        assertFalse(result.valid());
        assertEquals("unresolved-input", result.reason());
        assertTrue(result.unresolvedInputs().contains(unknown));
    }

    @Test
    void tagInputPicksCheapestKnownCandidate() {
        ItemKey cheap = item("cheap");
        ItemKey expensive = item("expensive");
        ItemKey untagged = item("untagged");
        ItemKey out = item("out");
        RecipeInput tagSlot = RecipeInput.of(new IngredientOption(List.of(expensive, cheap, untagged)));
        RecipeSnapshot snapshot = recipe(RecipeKind.SHAPED, List.of(tagSlot), List.of(new RecipeOutput(out, 1)));

        RecipeEvaluation result = evaluator.evaluate(
                snapshot, lookup(Map.of(cheap, 7L, expensive, 99L)), POLICY);

        assertTrue(result.valid());
        // `untagged` has no EMC, so it must not win despite being unknown-cheapest
        assertEquals(7L, result.outputUnitEmc());
    }

    @Test
    void containerRemaindersAreCredited() {
        ItemKey bucket = item("bucket");
        ItemKey milk = item("milk");
        ItemKey emptyBucket = item("empty_bucket");
        RecipeInput in = input(milk, 1);
        RecipeSnapshot snapshot = new RecipeSnapshot(
                new ResourceLocation("test", "recipe"), RecipeKind.SHAPELESS,
                List.of(in), List.of(new RecipeOutput(bucket, 1)),
                List.of(new RecipeOutput(emptyBucket, 1)), false, Map.of());

        RecipeEvaluation result = evaluator.evaluate(
                snapshot, lookup(Map.of(milk, 100L, emptyBucket, 30L)), POLICY);

        assertTrue(result.valid());
        assertEquals(70L, result.outputUnitEmc(), "100 - 30 credit");
    }

    @Test
    void remainderCreditNeverGoesNegative() {
        ItemKey cheap = item("cheap");
        ItemKey out = item("out");
        ItemKey expensiveRemainder = item("expensive_remainder");
        RecipeSnapshot snapshot = new RecipeSnapshot(
                new ResourceLocation("test", "recipe"), RecipeKind.SHAPELESS,
                List.of(input(cheap, 1)), List.of(new RecipeOutput(out, 1)),
                List.of(new RecipeOutput(expensiveRemainder, 1)), false, Map.of());

        RecipeEvaluation result = evaluator.evaluate(
                snapshot, lookup(Map.of(cheap, 10L, expensiveRemainder, 500L)), POLICY);

        assertTrue(result.valid());
        assertEquals(0L, result.outputUnitEmc());
        assertEquals(0L, result.totalInputEmc());
    }

    @Test
    void multiOutputPrimaryOnlyAssignsWholeCostToPrimary() {
        ItemKey in = item("in");
        ItemKey primary = item("primary");
        ItemKey byproduct = item("byproduct");
        RecipeSnapshot snapshot = recipe(RecipeKind.SHAPED,
                List.of(input(in, 1)),
                List.of(new RecipeOutput(primary, 1), new RecipeOutput(byproduct, 4)));

        // Default policy: priceSecondaryOutputs = false
        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(in, 100L)), POLICY);

        assertTrue(result.valid());
        assertEquals(100L, result.outputUnitEmc(), "byproduct must not dilute the primary output");
    }

    @Test
    void multiOutputSplitPolicyDividesAcrossAllUnits() {
        ItemKey in = item("in");
        ItemKey primary = item("primary");
        ItemKey byproduct = item("byproduct");
        RecipeSnapshot snapshot = recipe(RecipeKind.SHAPED,
                List.of(input(in, 1)),
                List.of(new RecipeOutput(primary, 1), new RecipeOutput(byproduct, 4)));

        EvaluationPolicy split = new EvaluationPolicy(EvaluationPolicy.DEFAULT_MINIMUM_EMC, true, true);
        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(in, 100L)), split);

        assertTrue(result.valid());
        // 100 cost spread over 1 + 4 = 5 units
        assertEquals(20L, result.outputUnitEmc());
    }

    @Test
    void minimumEmcIsAppliedToNonZeroResults() {
        ItemKey in = item("in");
        ItemKey out = item("out");
        RecipeSnapshot snapshot = recipe(RecipeKind.STONECUTTER, List.of(input(in, 1)),
                List.of(new RecipeOutput(out, 1)));

        EvaluationPolicy minimumFive = EvaluationPolicy.of(5L);
        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(in, 2L)), minimumFive);

        assertTrue(result.valid());
        assertEquals(5L, result.outputUnitEmc());
    }

    @Test
    void unknownInputStaysUnresolvedEvenWhenMinimumIsConfigured() {
        ItemKey in = item("in");
        ItemKey out = item("out");
        RecipeSnapshot snapshot = recipe(RecipeKind.FURNACE,
                List.of(input(in, 1)), List.of(new RecipeOutput(out, 1)));

        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of()), POLICY.withMinimumEmc(5L));

        assertFalse(result.valid(), "an unknown input must stay unresolved, not become free");
        assertEquals("unresolved-input", result.reason());
    }

    @Test
    void supportsOnlyTheDocumentedRecipeKinds() {
        for (RecipeKind kind : new RecipeKind[]{RecipeKind.SHAPED, RecipeKind.SHAPELESS, RecipeKind.FURNACE,
                RecipeKind.BLAST_FURNACE, RecipeKind.SMOKER, RecipeKind.STONECUTTER}) {
            assertTrue(evaluator.supports(recipe(kind, List.of(), List.of())), kind.name());
        }
        assertFalse(evaluator.supports(recipe(RecipeKind.GOETY_RITUAL, List.of(), List.of())));
        assertFalse(evaluator.supports(recipe(RecipeKind.UNSUPPORTED, List.of(), List.of())));
    }

    @Test
    void emptyOutputsAreInvalid() {
        RecipeSnapshot snapshot = recipe(RecipeKind.SHAPED, List.of(input(item("in"), 1)), List.of());
        RecipeEvaluation result = evaluator.evaluate(snapshot, lookup(Map.of(item("in"), 1L)), POLICY);
        assertFalse(result.valid());
        assertEquals("no-output", result.reason());
    }
}
