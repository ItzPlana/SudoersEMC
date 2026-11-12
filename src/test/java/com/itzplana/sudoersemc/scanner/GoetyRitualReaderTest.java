package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GoetyRitualReaderTest {

    private static final ResourceLocation RITUAL_ID = new ResourceLocation("sudoersemc_test", "ritual");

    private final GoetyRitualReader reader = new GoetyRitualReader(true, true);
    private final FakeRitualRecipe recipe = new FakeRitualRecipe(false, false, "goety:craft");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.ensure();
    }

    @BeforeEach
    void installReflectionBridge() throws Exception {
        setMethod("ingredientsMethod", FakeRitualRecipe.class.getMethod("getIngredients"));
        setMethod("activationItemMethod", FakeRitualRecipe.class.getMethod("getActivationItem"));
        setMethod("resultMethod", FakeRitualRecipe.class.getMethod("getResultItem", RegistryAccess.class));
        setMethod("ritualTypeMethod", FakeRitualRecipe.class.getMethod("getRitualType"));
    }

    @AfterEach
    void clearReflectionBridge() throws Exception {
        setMethod("ingredientsMethod", null);
        setMethod("activationItemMethod", null);
        setMethod("resultMethod", null);
        setMethod("ritualTypeMethod", null);
    }

    @Test
    void successfulRitualIncludesActivationItem() {
        RecipeSnapshot snapshot = reader.describeRitual(recipe, RITUAL_ID);

        assertNotNull(snapshot);
        assertEquals(RecipeKind.GOETY_RITUAL, snapshot.kind());
        assertEquals(2, snapshot.inputs().size(), "ingredients and the activation item are both costs");
        assertTrue(snapshot.catalyst(), "the altar is a catalyst");
        assertEquals("goety:craft", snapshot.attribute("ritualType"));
        assertEquals(ItemKey.of(new ResourceLocation("minecraft", "diamond")), snapshot.primaryOutput().item());
    }

    @Test
    void unreadableIngredientsReturnUnsupported() {
        FakeRitualRecipe failing = new FakeRitualRecipe(true, false, "goety:craft");

        assertNull(reader.describeRitual(failing, RITUAL_ID),
                "a partially readable ritual must not be priced");
    }

    @Test
    void unreadableActivationItemReturnsUnsupported() {
        FakeRitualRecipe failing = new FakeRitualRecipe(false, true, "goety:craft");

        assertNull(reader.describeRitual(failing, RITUAL_ID),
                "the activation item is a real cost and cannot be skipped");
    }

    @Test
    void missingRitualTypeReturnsUnsupported() {
        FakeRitualRecipe failing = new FakeRitualRecipe(false, false, null);

        assertNull(reader.describeRitual(failing, RITUAL_ID),
                "without a ritual subtype the evaluator can never price the snapshot");
    }

    @Test
    void convertRejectsUnboundTagSlot() {
        NonNullList<Ingredient> ingredients = ingredients(
                Ingredient.of(Items.DIRT), Ingredient.of(unboundTag()));

        assertNull(GoetyRitualReader.convert(ingredients),
                "Forge represents an empty tag as a named barrier; it is not a real candidate");
    }

    @Test
    void convertRejectsThrowingSlot() {
        NonNullList<Ingredient> ingredients = ingredients(
                Ingredient.of(Items.DIRT), new ThrowingIngredient());

        assertNull(GoetyRitualReader.convert(ingredients));
    }

    @Test
    void convertSkipsEmptySlots() {
        NonNullList<Ingredient> ingredients = ingredients(Ingredient.EMPTY, Ingredient.of(Items.DIRT));

        List<RecipeInput> converted = GoetyRitualReader.convert(ingredients);

        assertNotNull(converted);
        assertEquals(1, converted.size());
    }

    private static TagKey<Item> unboundTag() {
        return TagKey.create(Registries.ITEM, new ResourceLocation("sudoersemc_test", "unbound"));
    }

    private static NonNullList<Ingredient> ingredients(Ingredient... values) {
        NonNullList<Ingredient> list = NonNullList.withSize(values.length, Ingredient.EMPTY);
        for (int i = 0; i < values.length; i++) {
            list.set(i, values[i]);
        }
        return list;
    }

    private static void setMethod(String fieldName, Method method) throws Exception {
        Field field = GoetyRitualReader.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(null, method);
    }

    /** Recipe double whose reflective accessors can be made to fail on demand. */
    private static final class FakeRitualRecipe extends ShapelessRecipe {

        private final boolean failIngredients;
        private final boolean failActivation;
        private final String ritualType;

        private FakeRitualRecipe(boolean failIngredients, boolean failActivation, String ritualType) {
            super(RITUAL_ID, "", CraftingBookCategory.MISC, new ItemStack(Items.DIAMOND),
                    NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT)));
            this.failIngredients = failIngredients;
            this.failActivation = failActivation;
            this.ritualType = ritualType;
        }

        @Override
        public NonNullList<Ingredient> getIngredients() {
            if (failIngredients) {
                throw new IllegalStateException("broken reflection bridge");
            }
            return super.getIngredients();
        }

        public Ingredient getActivationItem() {
            if (failActivation) {
                throw new IllegalStateException("broken reflection bridge");
            }
            return Ingredient.of(Items.GOLD_INGOT);
        }

        public String getRitualType() {
            return ritualType;
        }
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
