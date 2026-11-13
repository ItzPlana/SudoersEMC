package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
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
    @Test
    void recipeIdEnumerationFailureAbortsInsteadOfReturningEmptyScan() {
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> scanner.beginScan(new ThrowingIdRecipeManager()));

        assertTrueEnumeratingFailure(failure);
    }

    @Test
    void singleRecipeFailureIncrementsFailedWithoutAborting() {
        RecipeScanner.ScanResult result = scanner.scan(new OneBrokenRecipeManager());

        assertEquals(0, result.recipes().size(),
                "单条配方失败不得产生可用快照");
        assertEquals(1, result.failed(),
                "单条配方失败必须计入 failed，而不是把整次枚举降级为空");
        assertEquals(1, result.total());
    }

    private static void assertTrueEnumeratingFailure(IllegalStateException failure) {
        assertNotNull(failure.getMessage());
        assertEquals(true, failure.getMessage().contains("无法枚举配方 id"),
                "异常必须带可诊断的枚举失败原因，实际为 " + failure.getMessage());
    }

    /** RecipeManager whose id enumeration always fails. */
    private static final class ThrowingIdRecipeManager extends RecipeManager {
        @Override
        public Stream<ResourceLocation> getRecipeIds() {
            throw new IllegalStateException("broken recipe manager");
        }
    }

    /** RecipeManager that exposes one id but fails when the recipe is looked up. */
    private static final class OneBrokenRecipeManager extends RecipeManager {
        @Override
        public Stream<ResourceLocation> getRecipeIds() {
            return Stream.of(RECIPE_ID);
        }

        @Override
        public Optional<? extends Recipe<?>> byKey(ResourceLocation id) {
            throw new IllegalStateException("broken recipe lookup");
        }
    }

    @Test
    void sessionHonoursStepBudgetAndProducesSameRecipesAsScan() {
        RecipeManager manager = new RecipeManager();
        NonNullList<Ingredient> ingredients = NonNullList.withSize(1, Ingredient.EMPTY);
        ingredients.set(0, Ingredient.of(Items.DIRT));
        ShapedRecipe first = new ShapedRecipe(
                new ResourceLocation("sudoersemc_test", "first"), "", CraftingBookCategory.MISC,
                1, 1, ingredients, new ItemStack(Items.DIAMOND));
        NonNullList<Ingredient> secondIngredients = NonNullList.withSize(1, Ingredient.EMPTY);
        secondIngredients.set(0, Ingredient.of(Items.DIRT));
        ShapedRecipe second = new ShapedRecipe(
                new ResourceLocation("sudoersemc_test", "second"), "", CraftingBookCategory.MISC,
                1, 1, secondIngredients, new ItemStack(Items.EMERALD));
        manager.replaceRecipes(List.of(first, second));

        RecipeScanner.RecipeScanSession session = scanner.beginSession(manager);
        assertEquals(2, session.total());
        assertEquals(0, session.processed());
        assertFalse(session.isDone());

        assertEquals(1, session.step(1));
        assertEquals(1, session.processed());
        assertFalse(session.isDone());
        assertEquals(1, session.result().recipes().size(),
                "部分结果只包含已扫描配方");

        assertEquals(1, session.step(1));
        assertTrue(session.isDone());
        assertEquals(2, session.processed());
        assertEquals(2, session.result().recipes().size());
        assertEquals(2, scanner.scan(manager).recipes().size(),
                "分片会话与一次性 scan 必须得到相同配方数量");
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
