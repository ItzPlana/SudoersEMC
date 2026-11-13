package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Goety ritual recipes through reflection.
 *
 * <p>Goety is an optional dependency, so this class must never link against Goety types. Everything
 * is resolved lazily by name. When Goety is absent, or a required accessor is missing or throws,
 * the reader fails closed and the affected ritual recipes are reported as unsupported instead of
 * being priced from partial data. A failure is logged exactly once per accessor so a broken
 * integration cannot spam the log.</p>
 *
 * <p>Verified against Goety 2.5.60.6 (MC 1.20.1, Forge 47.3.22):</p>
 * <ul>
 *   <li>Recipe type and serializer id are both {@code goety:ritual}.</li>
 *   <li>{@code RitualRecipe extends ModShapelessRecipe} and implements
 *       {@code Recipe<CraftingContainer>}.</li>
 *   <li>{@code matches(...)} always returns {@code false} and {@code assemble(...)} returns
 *       {@code null}, which is why these recipes must never reach the vanilla crafting path.</li>
 * </ul>
 */
public final class GoetyRitualReader {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/scanner/GoetyRitualReader");

    private static final String GOETY_MODID = "goety";
    private static final ResourceLocation RITUAL_ID = new ResourceLocation(GOETY_MODID, "ritual");
    private static final String RITUAL_RECIPE_CLASS = "com.Polarice3.Goety.common.crafting.RitualRecipe";

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static volatile Method activationItemMethod;
    private static volatile Method ritualTypeMethod;
    private static volatile Method ingredientsMethod;
    private static volatile Method resultMethod;
    private static volatile boolean activationFailed;
    private static volatile boolean ritualTypeFailed;
    private static volatile boolean resultFailed;
    private static volatile boolean ingredientsFailed;

    private final boolean readActivationItem;
    private final boolean readRitualType;

    GoetyRitualReader(boolean readActivationItem, boolean readRitualType) {
        this.readActivationItem = readActivationItem;
        this.readRitualType = readRitualType;
    }

    /**
     * @return a reader that never describes anything
     */
    public static GoetyRitualReader unavailable() {
        return new GoetyRitualReader(false, false);
    }

    /**
     * @return {@code true} when Goety is loaded and the ritual recipe class could be resolved
     */
    public static boolean available() {
        resolve();
        return available;
    }

    /**
     * @return a reader wired for the currently loaded Goety version
     */
    public static GoetyRitualReader create() {
        resolve();
        return new GoetyRitualReader(activationItemMethod != null, ritualTypeMethod != null);
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            if (!ModList.get().isLoaded(GOETY_MODID)) {
                available = false;
                return;
            }
            Class<?> ritualClass = Class.forName(RITUAL_RECIPE_CLASS);
            activationItemMethod = findMethod(ritualClass, "getActivationItem");
            ritualTypeMethod = findMethod(ritualClass, "getRitualType");
            ingredientsMethod = findMethod(ritualClass, "getIngredients");
            resultMethod = findMethod(ritualClass, "getResultItem", net.minecraft.core.RegistryAccess.class);
            available = ingredientsMethod != null && resultMethod != null;
        } catch (Throwable t) {
            available = false;
            LOGGER.debug("Goety ritual reader unavailable", t);
        }
    }

    @Nullable
    private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Describes a Goety ritual recipe.
     *
     * @return the snapshot, or {@code null} when the recipe is not a Goety ritual or cannot be read
     */
    @Nullable
    public RecipeSnapshot describe(Recipe<?> recipe, ResourceLocation id) {
        if (!available || recipe == null) {
            return null;
        }
        if (!RITUAL_ID.equals(recipe.getType() == null ? null : BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()))) {
            return null;
        }
        return describeRitual(recipe, id);
    }

    /**
     * Describes a recipe that is already known to be a Goety ritual. Fail-closed: every field that
     * contributes to the cost must be readable, otherwise the whole ritual is reported as
     * unsupported instead of being priced with missing inputs.
     *
     * <p>Package-private so the reflection bridge can be exercised by unit tests without Goety.</p>
     */
    @Nullable
    RecipeSnapshot describeRitual(Recipe<?> recipe, ResourceLocation id) {
        NonNullList<Ingredient> ingredients = readIngredients(recipe);
        if (ingredients == null) {
            return null;
        }
        List<RecipeInput> convertedIngredients = convert(ingredients);
        if (convertedIngredients == null) {
            return null;
        }
        List<RecipeInput> inputs = new ArrayList<>(convertedIngredients);
        if (inputs.isEmpty()) {
            return null;
        }

        Ingredient activation = readActivationItem(recipe);
        if (activation == null) {
            return null;
        }
        List<RecipeInput> activationInputs = convert(NonNullList.of(Ingredient.EMPTY, activation));
        if (activationInputs == null || activationInputs.isEmpty()) {
            return null;
        }
        // The activation item is consumed once the ritual completes, so it is a real cost.
        inputs.addAll(activationInputs);

        ItemStack result = readResult(recipe);
        if (result == null || result.isEmpty()) {
            return null;
        }
        ResourceLocation resultId = BuiltInRegistries.ITEM.getKey(result.getItem());
        if (resultId == null) {
            return null;
        }
        RecipeOutput output = new RecipeOutput(ItemKey.of(resultId), Math.max(1, result.getCount()));

        String ritualType = readRitualType(recipe);
        if (ritualType == null) {
            // GoetyRitualEvaluator only prices goety:craft rituals, so a snapshot without the
            // subtype could never be evaluated. Report it as unsupported while scanning.
            return null;
        }

        return new RecipeSnapshot(id, RecipeKind.GOETY_RITUAL, inputs, List.of(output), List.of(),
                // The dark altar and its pedestals are catalysts and are never part of `inputs`.
                true, Map.of(GoetyRitualEvaluatorAttributes.TYPE, ritualType));
    }

    @Nullable
    private NonNullList<Ingredient> readIngredients(Recipe<?> recipe) {
        try {
            Object value = ingredientsMethod.invoke(recipe);
            if (value instanceof NonNullList<?> list) {
                @SuppressWarnings("unchecked")
                NonNullList<Ingredient> typed = (NonNullList<Ingredient>) list;
                return typed;
            }
        } catch (Throwable t) {
            logIngredientsFailure(t);
        }
        return null;
    }

    @Nullable
    private Ingredient readActivationItem(Recipe<?> recipe) {
        if (!readActivationItem || activationItemMethod == null) {
            return null;
        }
        try {
            Object value = activationItemMethod.invoke(recipe);
            return value instanceof Ingredient ingredient && !ingredient.isEmpty() ? ingredient : null;
        } catch (Throwable t) {
            if (!activationFailed) {
                activationFailed = true;
                LOGGER.warn("Goety ritual activation item could not be read; ritual recipes will be treated as unsupported", t);
            }
            return null;
        }
    }

    @Nullable
    private String readRitualType(Recipe<?> recipe) {
        if (!readRitualType || ritualTypeMethod == null) {
            return null;
        }
        try {
            Object value = ritualTypeMethod.invoke(recipe);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable t) {
            if (!ritualTypeFailed) {
                ritualTypeFailed = true;
                LOGGER.warn("Goety ritual type could not be read; ritual recipes will be treated as unsupported", t);
            }
            return null;
        }
    }

    @Nullable
    private static ItemStack readResult(Recipe<?> recipe) {
        try {
            Object value = resultMethod.invoke(recipe, (Object) null);
            return value instanceof ItemStack stack ? stack : null;
        } catch (Throwable t) {
            if (!resultFailed) {
                resultFailed = true;
                // getResultItem may require a registry access on some versions; passing null is the
                // documented "no NBT context" path and works for item-only results.
                LOGGER.debug("Goety ritual result could not be read with a null registry access", t);
            }
            return null;
        }
    }

    /**
     * Converts ritual ingredients. Fail-closed: a slot that cannot be converted makes the whole
     * ritual unsupported, otherwise a partially priced ritual would understate its real cost.
     *
     * @return the converted slots, or {@code null} when any non-empty slot cannot be read
     */
    @Nullable
    static List<RecipeInput> convert(NonNullList<Ingredient> ingredients) {
        List<RecipeInput> converted = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] stacks;
            try {
                stacks = ingredient.getItems();
            } catch (RuntimeException e) {
                return null;
            }
            List<ItemKey> candidates = new ArrayList<>(stacks.length);
            for (ItemStack stack : stacks) {
                if (stack.isEmpty() || IngredientStacks.isEmptyTagPlaceholder(stack)) {
                    continue;
                }
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemId != null) {
                    candidates.add(ItemKey.of(itemId));
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            converted.add(RecipeInput.of(new IngredientOption(candidates)));
        }
        return converted;
    }

    private static void logIngredientsFailure(Throwable cause) {
        if (!ingredientsFailed) {
            ingredientsFailed = true;
            // Uses the debug channel: a partially readable ritual must not look like a crash.
            LOGGER.debug("Goety ritual ingredients could not be read; ritual recipes will be treated as unsupported", cause);
        }
    }

    /** Attribute keys shared with {@link com.itzplana.sudoersemc.solver.GoetyRitualEvaluator}. */
    private static final class GoetyRitualEvaluatorAttributes {

        private static final String TYPE = "ritualType";

        private GoetyRitualEvaluatorAttributes() {
        }
    }
}
