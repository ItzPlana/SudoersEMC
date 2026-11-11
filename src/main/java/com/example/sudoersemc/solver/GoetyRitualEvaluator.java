package com.example.sudoersemc.solver;

import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.scanner.RecipeSnapshot;

/**
 * Evaluator for Goety ritual recipes ({@code goety:ritual}).
 *
 * <p>Only the {@code goety:craft} ritual type produces an item and therefore participates in EMC
 * calculation. Summoning, conversion, enchanting, teleporting and locating rituals consume items
 * but produce entities, modified items or no item at all, so they are skipped instead of being
 * priced with a formula that does not apply to them.</p>
 *
 * <p>The altar and its pedestals are catalysts that are never consumed, so they are not part of
 * {@link RecipeSnapshot#inputs()} at all. The activation item <em>is</em> consumed and is included
 * in the input list by the scanner.</p>
 *
 * <p>Nothing here references Goety classes: the scanner is responsible for reading the live recipe
 * through reflection and for putting the ritual subtype into
 * {@link #ATTRIBUTE_RITUAL_TYPE}. This keeps the evaluator unit-testable and lets the mod run
 * without Goety installed.</p>
 */
public final class GoetyRitualEvaluator implements RecipeEvaluator {

    /** Attribute holding the ritual subtype, for example {@code goety:craft}. */
    public static final String ATTRIBUTE_RITUAL_TYPE = "ritualType";

    /** The only ritual subtype that produces a real item. */
    public static final String CRAFT_RITUAL = "goety:craft";

    /** Placeholder output used by Goety for rituals that do not produce an item. */
    public static final String DUMMY_OUTPUT_PATH_PREFIX = "jei_dummy/";

    private final StandardRecipeEvaluator delegate = new StandardRecipeEvaluator();

    @Override
    public boolean supports(RecipeSnapshot recipe) {
        return recipe.kind() == com.example.sudoersemc.scanner.RecipeKind.GOETY_RITUAL;
    }

    @Override
    public RecipeEvaluation evaluate(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
        if (recipe == null || lookup == null || policy == null) {
            return RecipeEvaluation.invalid("null-input");
        }
        String ritualType = recipe.attributeOr(ATTRIBUTE_RITUAL_TYPE, "");
        if (!CRAFT_RITUAL.equals(ritualType)) {
            return RecipeEvaluation.invalid("ritual-type:" + (ritualType.isEmpty() ? "unknown" : ritualType));
        }
        if (!recipe.hasOutputs()) {
            return RecipeEvaluation.invalid("no-output");
        }
        if (isDummyOutput(recipe.outputs().get(0).item())) {
            return RecipeEvaluation.invalid("dummy-output");
        }
        // A craft ritual is an ordinary deterministic conversion once the altar is excluded,
        // so the standard cost rule applies.
        return delegate.evaluate(recipe, lookup, policy);
    }

    /**
     * @return {@code true} for Goety's {@code goety:jei_dummy/...} placeholder items
     */
    public static boolean isDummyOutput(ItemKey item) {
        return item != null
                && "goety".equals(item.itemId().getNamespace())
                && item.itemId().getPath().startsWith(DUMMY_OUTPUT_PATH_PREFIX);
    }
}
