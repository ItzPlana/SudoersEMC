package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.solver.GoetyRitualEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.inventory.CraftingContainer;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.ModList;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Required-field and fail-closed semantics tests for {@link GoetyRitualReader}.
 *
 * <p>Injects a fake accessor via the package-level {@link GoetyRitualReader#withAccess(GoetyRitualAccess)}, so the test
 * JVM needs neither Goety installed nor a running server.</p>
 */
class GoetyRitualReaderTest {

    private static final ResourceLocation RECIPE_ID = new ResourceLocation("goety", "test_ritual");
    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");

    static {
        // Static initialisation of ItemStack / BuiltInRegistries requires version detection and registry bootstrap first.
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---------------------------------------------------------------- required-field failures

    @Test
    void activationReadFailureSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of(Ingredient.of(Items.STONE)));
        access.activation = GoetyRitualAccess.FieldRead.failed(new IllegalStateException("boom"));

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void absentActivationAccessorSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of(Ingredient.of(Items.STONE)));
        access.activation = GoetyRitualAccess.FieldRead.absent();

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void ingredientsReadFailureSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.failed(new IllegalStateException("boom"));

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void absentIngredientsAccessorSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.absent();

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void missingRitualTypeSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ritualType = GoetyRitualAccess.FieldRead.ok(null);

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void ritualTypeReadFailureSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ritualType = GoetyRitualAccess.FieldRead.failed(new IllegalStateException("boom"));

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void emptyResultSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.result = GoetyRitualAccess.FieldRead.ok(ItemStack.EMPTY);

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void resultReadFailureSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.result = GoetyRitualAccess.FieldRead.failed(new IllegalStateException("boom"));

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    // ---------------------------------------------------------------- type gate and unavailability

    @Test
    void nonRitualRecipeIsRejectedBeforeAnyFieldRead() {
        assertNotNull(BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.CRAFTING),
                "前置条件：minecraft:crafting 必须已注册，否则类型闸门会因空键假通过");
        FakeAccess access = new FakeAccess();

        assertNull(reader(access).describe(craftingRecipe(), RECIPE_ID));
        assertEquals(0, access.calls, "非仪式类型必须在读取字段之前返回");
    }

    @Test
    void recipeTypeLinkageErrorSkipsRecipeWithoutThrowing() {
        FakeAccess access = new FakeAccess();

        assertNull(reader(access).describe(recipeWithFailingType(), RECIPE_ID),
                "类型闸门必须隔离 LinkageError，不能让整轮扫描失败");
        assertEquals(0, access.calls, "类型闸门失败必须在读取字段之前返回");
    }

    @Test
    void goetyAbsentMakesReaderUnavailableWithoutThrowing() {
        assumeGoetyAbsent();
        assertFalse(GoetyRitualReader.available());
        assertFalse(GoetyRitualReader.available(), "解析结果必须缓存且幂等");
    }

    @Test
    void unavailableReaderDescribesNothing() {
        GoetyRitualReader reader = GoetyRitualReader.unavailable();

        assertNull(reader.describe(craftingRecipe(), RECIPE_ID));
    }

    @Test
    void recipeScannerWithGoetyStaysEmptyWithoutGoety() {
        assumeGoetyAbsent();
        assertTrue(RecipeScanner.withGoety().isEmpty());
    }

    @Test
    void nullIdIsRejectedWithoutReadingFields() {
        FakeAccess access = new FakeAccess();

        assertNull(reader(access).describe(craftingRecipe(), null));
        assertEquals(0, access.calls);
    }

    // ---------------------------------------------------------------- normal and boundary conversions

    @Test
    void normalCraftRitualProducesSnapshot() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of(Ingredient.of(Items.STONE)));
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.of(Items.ENDER_PEARL));
        access.ritualType = GoetyRitualAccess.FieldRead.ok("goety:craft");
        access.result = GoetyRitualAccess.FieldRead.ok(new ItemStack(Items.DIAMOND));

        RecipeSnapshot snapshot = reader(access).describeReadable(new Object(), RECIPE_ID);

        assertNotNull(snapshot);
        assertEquals(RecipeKind.GOETY_RITUAL, snapshot.kind());
        assertEquals(2, snapshot.inputs().size(), "材料与激活物品都必须计入成本");
        assertTrue(snapshot.catalyst(), "祭坛与基座属于催化剂");
        assertEquals("goety:craft",
                snapshot.attribute(GoetyRitualEvaluator.ATTRIBUTE_RITUAL_TYPE));
        RecipeOutput output = snapshot.primaryOutput();
        assertNotNull(output);
        assertEquals(ItemKey.of(DIAMOND_ID), output.item());
        assertEquals(1, output.count());
    }

    @Test
    void emptyActivationIsLegalZeroCost() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of(Ingredient.of(Items.STONE)));
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.EMPTY);

        RecipeSnapshot snapshot = reader(access).describeReadable(new Object(), RECIPE_ID);

        assertNotNull(snapshot);
        assertEquals(1, snapshot.inputs().size(), "空激活物品合法，但不得凭空产生输入");
    }

    @Test
    void emptyIngredientsWithValidActivationIsStillDescribed() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of());
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.of(Items.ENDER_PEARL));

        RecipeSnapshot snapshot = reader(access).describeReadable(new Object(), RECIPE_ID);

        assertNotNull(snapshot, "空列表是合法值，与读取失败不同");
        assertEquals(1, snapshot.inputs().size());
    }

    @Test
    void emptyIngredientsAndEmptyActivationProduceNoInputs() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of());
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.EMPTY);

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void failingIngredientConversionSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of(
                new StubIngredient(new IllegalStateException("explode"), new ItemStack(Items.STONE))));
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.of(Items.ENDER_PEARL));

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void ingredientWithZeroStacksSkipsWholeRecipe() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of(new StubIngredient(null)));
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.of(Items.ENDER_PEARL));

        assertNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }


    @Test
    void plainListIngredientsAreAccepted() {
        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(
                new ArrayList<>(List.of(Ingredient.of(Items.STONE))));
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.EMPTY);

        assertNotNull(reader(access).describeReadable(new Object(), RECIPE_ID));
    }

    @Test
    void resultNbtIsPreservedInOutput() throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 3);
        ItemStack result = new ItemStack(Items.DIAMOND_SWORD);
        result.setTag(tag);

        FakeAccess access = new FakeAccess();
        access.ingredients = GoetyRitualAccess.FieldRead.ok(List.of(Ingredient.of(Items.STONE)));
        access.result = GoetyRitualAccess.FieldRead.ok(result);
        access.activation = GoetyRitualAccess.FieldRead.ok(Ingredient.EMPTY);

        RecipeSnapshot snapshot = reader(access).describeReadable(new Object(), RECIPE_ID);

        assertNotNull(snapshot);
        RecipeOutput output = snapshot.primaryOutput();
        assertNotNull(output);
        assertNotNull(output.outputNbt(), "结果 NBT 必须通过 RecipeScanner.convertStack 保留");
        CompoundTag parsed = TagParser.parseTag(output.outputNbt());
        assertEquals(3, parsed.getInt("Damage"));
    }

    // ---------------------------------------------------------------- accessor exception defence

    @Test
    void accessRuntimeExceptionSkipsRecipeWithoutThrowing() {
        GoetyRitualReader reader = GoetyRitualReader.withAccess(
                throwingAccess(new IllegalStateException("boom")));

        assertNull(reader.describeReadable(new Object(), RECIPE_ID),
                "访问器运行时异常必须降级为字段读取失败");
    }

    @Test
    void accessLinkageErrorSkipsRecipeWithoutThrowing() {
        GoetyRitualReader reader = GoetyRitualReader.withAccess(
                throwingAccess(new NoClassDefFoundError("goety:missing")));

        assertNull(reader.describeReadable(new Object(), RECIPE_ID),
                "访问器 LinkageError 必须降级为字段读取失败");
    }

    // ---------------------------------------------------------------- log rate limiting

    @Test
    void nonRitualTypeDoesNotEmitLogs() {
        FakeAccess access = new FakeAccess();
        GoetyRitualReader reader = reader(access);
        int before = GoetyRitualReader.testLogger().emittedCount();

        for (int i = 0; i < 1000; i++) {
            assertNull(reader.describe(craftingRecipe(), RECIPE_ID));
        }

        assertEquals(before, GoetyRitualReader.testLogger().emittedCount(),
                "非仪式类型是热路径，不得产生逐条日志");
        assertEquals(0, access.calls);
    }

    @Test
    void repeatedRequiredFailureWarnsOnlyOnce() {
        FakeAccess access = new FakeAccess();
        access.activation = GoetyRitualAccess.FieldRead.failed(new IllegalStateException("boom"));
        GoetyRitualReader reader = reader(access);

        assertNull(reader.describeReadable(new Object(), RECIPE_ID));
        assertNull(reader.describeReadable(new Object(), RECIPE_ID));

        assertTrue(GoetyRitualReader.testLogger().hasEmitted("W", "goety.read.activationItem"),
                "同一失败点必须至少 warn 一次");
        assertTrue(GoetyRitualReader.testLogger().hasEmitted(
                        "D", "goety.read.activationItem.repeat"),
                "重复失败只保留一次 debug，不得再次 warn");
    }

    // ---------------------------------------------------------------- test helpers

    private static GoetyRitualReader reader(FakeAccess access) {
        return GoetyRitualReader.withAccess(access);
    }

    private static void assumeGoetyAbsent() {
        Assumptions.assumeFalse(isGoetyLoaded(), "本测试只验证未安装 Goety 的降级路径");
    }

    private static boolean isGoetyLoaded() {
        try {
            ModList modList = ModList.get();
            return modList != null && modList.isLoaded("goety");
        } catch (Throwable failure) {
            // Treat as not installed when the unit-test JVM has not initialised Forge.
            return false;
        }
    }

    private static GoetyRitualAccess throwingAccess(Throwable failure) {
        return new GoetyRitualAccess() {

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public GoetyRitualAccess.FieldRead<List<Ingredient>> ingredients(Object recipe) {
                throw rethrow(failure);
            }

            @Override
            public GoetyRitualAccess.FieldRead<Ingredient> activationItem(Object recipe) {
                throw rethrow(failure);
            }

            @Override
            public GoetyRitualAccess.FieldRead<String> ritualType(Object recipe) {
                throw rethrow(failure);
            }

            @Override
            public GoetyRitualAccess.FieldRead<ItemStack> result(Object recipe) {
                throw rethrow(failure);
            }
        };
    }

    private static RuntimeException rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new IllegalStateException(failure);
    }

    private static Recipe<CraftingContainer> craftingRecipe() {
        return recipe(false);
    }

    private static Recipe<CraftingContainer> recipeWithFailingType() {
        return recipe(true);
    }

    private static Recipe<CraftingContainer> recipe(boolean failingType) {
        return new Recipe<CraftingContainer>() {

            @Override
            public boolean matches(CraftingContainer container, Level level) {
                return false;
            }

            @Override
            public ItemStack assemble(CraftingContainer container, RegistryAccess access) {
                return ItemStack.EMPTY;
            }

            @Override
            public boolean canCraftInDimensions(int width, int height) {
                return false;
            }

            @Override
            public ItemStack getResultItem(RegistryAccess access) {
                return ItemStack.EMPTY;
            }

            @Override
            public ResourceLocation getId() {
                return RECIPE_ID;
            }

            @Override
            public RecipeSerializer<?> getSerializer() {
                return null;
            }

            @Override
            public RecipeType<?> getType() {
                if (failingType) {
                    throw new NoClassDefFoundError("goety:missing");
                }
                return RecipeType.CRAFTING;
            }
        };
    }

    /**
     * Programmable fake accessor: each field independently set to a three-state result, and counts field reads.
     */
    private static final class FakeAccess implements GoetyRitualAccess {

        private boolean available = true;
        private FieldRead<List<Ingredient>> ingredients = FieldRead.ok(List.of());
        private FieldRead<Ingredient> activation = FieldRead.ok(Ingredient.EMPTY);
        private FieldRead<String> ritualType = FieldRead.ok("goety:craft");
        private FieldRead<ItemStack> result = FieldRead.ok(new ItemStack(Items.DIAMOND));
        private int calls;

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public FieldRead<List<Ingredient>> ingredients(Object recipe) {
            calls++;
            return ingredients;
        }

        @Override
        public FieldRead<Ingredient> activationItem(Object recipe) {
            calls++;
            return activation;
        }

        @Override
        public FieldRead<String> ritualType(Object recipe) {
            calls++;
            return ritualType;
        }

        @Override
        public FieldRead<ItemStack> result(Object recipe) {
            calls++;
            return result;
        }
    }

    /**
     * Test ingredient with controllable {@code isEmpty()} and {@code getItems()}.
     */
    private static final class StubIngredient extends Ingredient {

        private final ItemStack[] items;
        private final RuntimeException failure;

        private StubIngredient(RuntimeException failure, ItemStack... items) {
            super(Stream.empty());
            this.failure = failure;
            this.items = items;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public ItemStack[] getItems() {
            if (failure != null) {
                throw failure;
            }
            return items;
        }
    }
}
