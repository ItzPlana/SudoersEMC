package com.example.sudoersemc.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.scanner.IngredientOption;
import com.example.sudoersemc.scanner.RecipeInput;
import com.example.sudoersemc.scanner.RecipeKind;
import com.example.sudoersemc.scanner.RecipeOutput;
import com.example.sudoersemc.scanner.RecipeSnapshot;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Verifies Goety ritual handling: only {@code goety:craft} is priced, every other ritual type and
 * the {@code jei_dummy} placeholder are skipped.
 */
class GoetyRitualEvaluatorTest {

    private static final EvaluationPolicy POLICY = EvaluationPolicy.defaults();
    private final GoetyRitualEvaluator evaluator = new GoetyRitualEvaluator();

    private static ItemKey item(String namespace, String path) {
        return ItemKey.of(new ResourceLocation(namespace, path));
    }

    private static EmcLookup lookup(Map<ItemKey, Long> values) {
        return key -> values.getOrDefault(key, 0L);
    }

    private static RecipeSnapshot ritual(String ritualType, List<RecipeInput> inputs, RecipeOutput output) {
        return new RecipeSnapshot(
                new ResourceLocation("goety", "test_ritual"),
                RecipeKind.GOETY_RITUAL,
                inputs,
                List.of(output),
                List.of(),
                true,
                Map.of(GoetyRitualEvaluator.ATTRIBUTE_RITUAL_TYPE, ritualType));
    }

    @Test
    void craftRitualIsPricedLikeADeterministicRecipe() {
        ItemKey ingredient = item("minecraft", "crying_obsidian");
        ItemKey activation = item("goety", "empty_focus");
        ItemKey result = item("goety", "philosophers_stone");
        RecipeSnapshot snapshot = ritual("goety:craft",
                List.of(RecipeInput.of(IngredientOption.of(ingredient)),
                        RecipeInput.of(IngredientOption.of(activation))),
                new RecipeOutput(result, 1));

        RecipeEvaluation evaluation = evaluator.evaluate(
                snapshot, lookup(Map.of(ingredient, 100L, activation, 20L)), POLICY);

        assertTrue(evaluation.valid());
        assertEquals(120L, evaluation.totalInputEmc());
        assertEquals(120L, evaluation.outputUnitEmc());
    }

    @Test
    void craftRitualWithMultipleIngredientsSumsThemAll() {
        ItemKey a = item("minecraft", "a");
        ItemKey b = item("minecraft", "b");
        ItemKey c = item("minecraft", "c");
        ItemKey result = item("goety", "out");
        RecipeSnapshot snapshot = ritual("goety:craft",
                List.of(RecipeInput.of(IngredientOption.of(a)),
                        RecipeInput.of(IngredientOption.of(b)),
                        RecipeInput.of(IngredientOption.of(c))),
                new RecipeOutput(result, 2));

        RecipeEvaluation evaluation = evaluator.evaluate(
                snapshot, lookup(Map.of(a, 10L, b, 20L, c, 30L)), POLICY);

        assertTrue(evaluation.valid());
        assertEquals(60L, evaluation.totalInputEmc());
        assertEquals(30L, evaluation.outputUnitEmc(), "60 / 2");
    }

    @Test
    void nonCraftRitualsAreSkipped() {
        ItemKey ingredient = item("minecraft", "netherite_ingot");
        ItemKey dummy = item("goety", "jei_dummy/none");
        for (String type : new String[]{
                "goety:summon", "goety:summon_tamed", "goety:summon_tamed_no_variant",
                "goety:convert", "goety:convert_tamed", "goety:convert_complete_tamed",
                "goety:enchant", "goety:teleport", "goety:locate"}) {
            RecipeSnapshot snapshot = ritual(type,
                    List.of(RecipeInput.of(IngredientOption.of(ingredient))),
                    new RecipeOutput(dummy, 1));

            RecipeEvaluation evaluation = evaluator.evaluate(
                    snapshot, lookup(Map.of(ingredient, 500L)), POLICY);

            assertFalse(evaluation.valid(), type + " must not be priced");
            assertEquals("ritual-type:" + type, evaluation.reason());
        }
    }

    @Test
    void dummyOutputIsRejectedEvenForCraftRituals() {
        ItemKey ingredient = item("minecraft", "stone");
        ItemKey dummy = item("goety", "jei_dummy/none");
        RecipeSnapshot snapshot = ritual("goety:craft",
                List.of(RecipeInput.of(IngredientOption.of(ingredient))),
                new RecipeOutput(dummy, 1));

        RecipeEvaluation evaluation = evaluator.evaluate(
                snapshot, lookup(Map.of(ingredient, 1L)), POLICY);

        assertFalse(evaluation.valid());
        assertEquals("dummy-output", evaluation.reason());
    }

    @Test
    void missingRitualTypeIsRejected() {
        ItemKey ingredient = item("minecraft", "stone");
        ItemKey result = item("goety", "out");
        RecipeSnapshot snapshot = new RecipeSnapshot(
                new ResourceLocation("goety", "test"),
                RecipeKind.GOETY_RITUAL,
                List.of(RecipeInput.of(IngredientOption.of(ingredient))),
                List.of(new RecipeOutput(result, 1)),
                List.of(),
                true,
                Map.of());

        RecipeEvaluation evaluation = evaluator.evaluate(
                snapshot, lookup(Map.of(ingredient, 1L)), POLICY);

        assertFalse(evaluation.valid());
        assertEquals("ritual-type:unknown", evaluation.reason());
    }

    @Test
    void unresolvedIngredientKeepsTheRitualUnresolved() {
        ItemKey known = item("minecraft", "stone");
        ItemKey unknown = item("minecraft", "mystery");
        ItemKey result = item("goety", "out");
        RecipeSnapshot snapshot = ritual("goety:craft",
                List.of(RecipeInput.of(IngredientOption.of(known)),
                        RecipeInput.of(IngredientOption.of(unknown))),
                new RecipeOutput(result, 1));

        RecipeEvaluation evaluation = evaluator.evaluate(snapshot, lookup(Map.of(known, 5L)), POLICY);

        assertFalse(evaluation.valid());
        assertEquals("unresolved-input", evaluation.reason());
        assertTrue(evaluation.unresolvedInputs().contains(unknown));
    }

    @Test
    void supportsOnlyGoetyRituals() {
        assertTrue(evaluator.supports(ritual("goety:craft", List.of(), new RecipeOutput(item("goety", "x"), 1))));
        assertFalse(evaluator.supports(RecipeSnapshot.of(
                new ResourceLocation("test", "r"), RecipeKind.SHAPED, List.of(),
                List.of(new RecipeOutput(item("test", "x"), 1)))));
    }

    @Test
    void dummyDetectionMatchesOnlyGoetyNamespace() {
        assertTrue(GoetyRitualEvaluator.isDummyOutput(item("goety", "jei_dummy/none")));
        assertTrue(GoetyRitualEvaluator.isDummyOutput(item("goety", "jei_dummy/anything")));
        assertFalse(GoetyRitualEvaluator.isDummyOutput(item("goety", "philosophers_stone")));
        assertFalse(GoetyRitualEvaluator.isDummyOutput(item("other", "jei_dummy/none")));
        assertFalse(GoetyRitualEvaluator.isDummyOutput(null));
    }

    @Test
    void registrySelectsGoetyEvaluatorBeforeStandard() {
        EvaluatorRegistry registry = EvaluatorRegistry.defaults();
        RecipeSnapshot snapshot = ritual("goety:craft",
                List.of(RecipeInput.of(IngredientOption.of(item("minecraft", "stone")))),
                new RecipeOutput(item("goety", "out"), 1));

        RecipeEvaluator selected = registry.find(snapshot);

        assertNotNull(selected);
        assertTrue(selected instanceof GoetyRitualEvaluator);
    }

    @Test
    void registrySelectsStandardEvaluatorForVanillaRecipes() {
        EvaluatorRegistry registry = EvaluatorRegistry.defaults();
        RecipeSnapshot snapshot = RecipeSnapshot.of(
                new ResourceLocation("test", "r"), RecipeKind.SHAPED,
                List.of(RecipeInput.of(IngredientOption.of(item("minecraft", "stone")))),
                List.of(new RecipeOutput(item("test", "out"), 1)));

        RecipeEvaluator selected = registry.find(snapshot);

        assertNotNull(selected);
        assertTrue(selected instanceof StandardRecipeEvaluator);
    }

    @Test
    void registryReturnsNullForUnsupportedKind() {
        EvaluatorRegistry registry = EvaluatorRegistry.defaults();
        RecipeSnapshot snapshot = RecipeSnapshot.of(
                new ResourceLocation("test", "r"), RecipeKind.UNSUPPORTED, List.of(),
                List.of(new RecipeOutput(item("test", "out"), 1)));

        assertNull(registry.find(snapshot));
    }

    @Test
    void registryReportsUnsupportedKindInsteadOfThrowing() {
        EvaluatorRegistry registry = EvaluatorRegistry.defaults();
        RecipeSnapshot snapshot = RecipeSnapshot.of(
                new ResourceLocation("test", "r"), RecipeKind.UNSUPPORTED, List.of(),
                List.of(new RecipeOutput(item("test", "out"), 1)));

        RecipeEvaluation evaluation = registry.evaluate(snapshot, key -> 0L, POLICY);

        assertFalse(evaluation.valid());
        assertEquals("unsupported-kind:UNSUPPORTED", evaluation.reason());
    }

    @Test
    void registryIsolatesEvaluatorExceptions() {
        RecipeEvaluator exploding = new RecipeEvaluator() {
            @Override
            public boolean supports(RecipeSnapshot recipe) {
                return true;
            }

            @Override
            public RecipeEvaluation evaluate(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
                throw new IllegalStateException("boom");
            }
        };
        EvaluatorRegistry registry = EvaluatorRegistry.builder().add(exploding).build();
        RecipeSnapshot snapshot = RecipeSnapshot.of(
                new ResourceLocation("test", "r"), RecipeKind.SHAPED, List.of(),
                List.of(new RecipeOutput(item("test", "out"), 1)));

        RecipeEvaluation evaluation = registry.evaluate(snapshot, key -> 0L, POLICY);

        assertFalse(evaluation.valid());
        assertEquals("evaluator-error:IllegalStateException", evaluation.reason());
    }
}
