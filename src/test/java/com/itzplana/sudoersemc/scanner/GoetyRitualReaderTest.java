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
import org.junit.jupiter.api.Test;

/**
 * {@link GoetyRitualReader} 的必需字段与失败关闭语义测试。
 *
 * <p>通过包级 {@link GoetyRitualReader#withAccess(GoetyRitualAccess)} 注入假访问器，
 * 因此测试 JVM 无需安装 Goety，也不需要启动服务器。</p>
 */
class GoetyRitualReaderTest {

    private static final ResourceLocation RECIPE_ID = new ResourceLocation("goety", "test_ritual");
    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");

    static {
        // ItemStack / BuiltInRegistries 的静态初始化需要先完成版本探测与注册表引导。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // ---------------------------------------------------------------- 必需字段失败

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

    // ---------------------------------------------------------------- 类型闸门与不可用

    @Test
    void nonRitualRecipeIsRejectedBeforeAnyFieldRead() {
        assertNotNull(BuiltInRegistries.RECIPE_TYPE.getKey(RecipeType.CRAFTING),
                "前置条件：minecraft:crafting 必须已注册，否则类型闸门会因空键假通过");
        FakeAccess access = new FakeAccess();

        assertNull(reader(access).describe(craftingRecipe(), RECIPE_ID));
        assertEquals(0, access.calls, "非仪式类型必须在读取字段之前返回");
    }

    @Test
    void goetyAbsentMakesReaderUnavailableWithoutThrowing() {
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
        assertTrue(RecipeScanner.withGoety().isEmpty());
    }

    @Test
    void nullIdIsRejectedWithoutReadingFields() {
        FakeAccess access = new FakeAccess();

        assertNull(reader(access).describe(craftingRecipe(), null));
        assertEquals(0, access.calls);
    }

    // ---------------------------------------------------------------- 正常与边界转换

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

    // ---------------------------------------------------------------- 日志限流

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

    // ---------------------------------------------------------------- 测试辅助

    private static GoetyRitualReader reader(FakeAccess access) {
        return GoetyRitualReader.withAccess(access);
    }

    private static Recipe<CraftingContainer> craftingRecipe() {
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
                return RecipeType.CRAFTING;
            }
        };
    }

    /**
     * 可编程假访问器：每个字段独立设置三态结果，并统计字段读取次数。
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
     * 可控制 {@code isEmpty()} 与 {@code getItems()} 的测试材料。
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
