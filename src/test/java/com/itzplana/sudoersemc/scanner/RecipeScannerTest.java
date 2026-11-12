package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.stream.Stream;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class RecipeScannerTest {

    private static final ResourceLocation RECIPE_ID = new ResourceLocation("sudoersemc_test", "recipe");
    private final RecipeScanner scanner = new RecipeScanner();

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.ensure();
    }

    @Test
    void shapedRecipeProducesSnapshotAndBucketRemainder() {
        ShapedRecipe recipe = shaped(2, 1,
                Ingredient.of(Items.MILK_BUCKET), Ingredient.of(Items.WHEAT), new ItemStack(Items.CAKE));

        RecipeSnapshot snapshot = scanner.describe(recipe, RECIPE_ID);

        assertNotNull(snapshot, "vanilla shaped recipes must not fail with a null container");
        assertEquals(RecipeKind.SHAPED, snapshot.kind());
        assertEquals(2, snapshot.inputs().size());
        assertEquals(1, snapshot.outputs().size());
        assertEquals(ItemKey.of(new ResourceLocation("minecraft", "cake")), snapshot.primaryOutput().item());
        assertEquals(1, snapshot.containerRemainders().size(),
                "the milk bucket slot returns exactly one bucket");
        assertEquals(ItemKey.of(new ResourceLocation("minecraft", "bucket")),
                snapshot.containerRemainders().get(0).item());
    }

    @Test
    void shapelessRecipeIsTaggedShapeless() {
        NonNullList<Ingredient> ingredients = NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT));
        ShapelessRecipe recipe = new ShapelessRecipe(RECIPE_ID, "", CraftingBookCategory.MISC,
                new ItemStack(Items.DIAMOND), ingredients);

        RecipeSnapshot snapshot = scanner.describe(recipe, RECIPE_ID);

        assertNotNull(snapshot);
        assertEquals(RecipeKind.SHAPELESS, snapshot.kind());
    }

    @Test
    void unboundTagSlotMakesRecipeUnsupported() {
        Ingredient unbound = Ingredient.of(unboundTag());
        assertFalse(unbound.isEmpty(), "an unbound tag ingredient is still a non-empty slot");
        ShapedRecipe recipe = shaped(2, 1,
                Ingredient.of(Items.DIRT), unbound, new ItemStack(Items.CAKE));

        assertNull(scanner.describe(recipe, RECIPE_ID),
                "a recipe must not be priced while one of its slots cannot be converted");
    }

    @Test
    void throwingIngredientSlotMakesRecipeUnsupported() {
        ShapedRecipe recipe = shaped(2, 1,
                Ingredient.of(Items.DIRT), new ThrowingIngredient(), new ItemStack(Items.CAKE));

        assertNull(scanner.describe(recipe, RECIPE_ID),
                "a getItems() failure must fail the whole recipe closed");
    }

    private static TagKey<Item> unboundTag() {
        return TagKey.create(Registries.ITEM, new ResourceLocation("sudoersemc_test", "unbound"));
    }

    private static ShapedRecipe shaped(int width, int height, Ingredient first, Ingredient second,
                                       ItemStack result) {
        NonNullList<Ingredient> ingredients = NonNullList.withSize(width * height, Ingredient.EMPTY);
        ingredients.set(0, first);
        ingredients.set(1, second);
        return new ShapedRecipe(RECIPE_ID, "", CraftingBookCategory.MISC, width, height, ingredients, result);
    }

    /** Ingredient whose candidate lookup fails, mirroring a broken tag or modded ingredient. */
    private static final class ThrowingIngredient extends Ingredient {

        private ThrowingIngredient() {
            super(Stream.empty());
        }

        @Override
        public ItemStack[] getItems() {
            throw new IllegalStateException("broken ingredient");
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }
}
