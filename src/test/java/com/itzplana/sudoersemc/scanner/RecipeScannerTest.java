package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import java.util.List;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@link RecipeScanner} 输出 SNBT 保留行为的单元测试。
 *
 * <p>测试只覆盖不依赖配方管理器的转换路径，因此不需要启动服务器；Forge 的
 * {@code ItemStack} 静态初始化需要注册表，测试前先完成引导。</p>
 */
class RecipeScannerTest {

    private static final ResourceLocation RECIPE_ID = new ResourceLocation("sudoersemc_test", "recipe");
    private static final ResourceLocation DIAMOND_SWORD_ID =
            new ResourceLocation("minecraft", "diamond_sword");
    private static final ResourceLocation STONE_ID = new ResourceLocation("minecraft", "stone");
    private final RecipeScanner scanner = new RecipeScanner();

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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

    @Test
    void nbtOutputIsPreservedAsSnbt() throws Exception {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.setCount(2);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 7);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertEquals(ItemKey.of(DIAMOND_SWORD_ID), output.item());
        assertEquals(2, output.count());
        assertNotNull(output.outputNbt(), "带 NBT 的输出必须保留 SNBT");
        assertFalse(output.outputNbt().isBlank());
        assertEquals(tag.toString(), output.outputNbt());

        CompoundTag parsed = TagParser.parseTag(output.outputNbt());
        assertEquals(7, parsed.getInt("Damage"), "保留的 SNBT 必须可往返解析");
    }

    @Test
    void outputWithoutNbtHasNullSnbt() {
        RecipeOutput output = RecipeScanner.convertStack(new ItemStack(Items.STONE));

        assertNotNull(output);
        assertNull(output.outputNbt(), "无 NBT 的输出应为 null");
    }

    @Test
    void emptyTagIsNormalizedToNullSnbt() {
        ItemStack stack = new ItemStack(Items.STONE);
        stack.setTag(new CompoundTag());

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertNull(output.outputNbt(), "空标签不应产生空的 SNBT 字符串");
    }

    @Test
    void emptySnbtLiteralIsNormalizedToNull() {
        RecipeOutput compact = RecipeOutput.of(ItemKey.of(DIAMOND_SWORD_ID), 1, "{}");
        assertNull(compact.outputNbt(), "SNBT \"{}\" 必须归一为 null");
        assertFalse(compact.hasOutputNbt(), "空 SNBT 不得被视为携带 NBT");

        RecipeOutput spaced = RecipeOutput.of(ItemKey.of(DIAMOND_SWORD_ID), 1, "{ } ");
        assertNull(spaced.outputNbt(), "带空白的空标签也必须归一为 null");
    }

    @Test
    void nonEmptySnbtIsPreservedByRecipeOutput() {
        RecipeOutput output = RecipeOutput.of(ItemKey.of(DIAMOND_SWORD_ID), 1, "{Damage:3}");

        assertEquals("{Damage:3}", output.outputNbt(), "非空 SNBT 必须原样保留");
        assertFalse(output.outputNbt().isBlank());
    }

    @Test
    void defaultDamageZeroTagSerialisesToNullSnbt() {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 0);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertNull(output.outputNbt(), "默认 {Damage:0} 必须归一为 null，避免生成伪变体");
        assertFalse(output.hasOutputNbt());
    }

    @Test
    void shapedRecipeKeepsKindAndActualGridDimensions() {
        ShapedRecipe recipe = new ShapedRecipe(
                new ResourceLocation("sudoersemc_test", "four_wide"), "test",
                CraftingBookCategory.MISC, 4, 1,
                NonNullList.withSize(4, Ingredient.of(Items.STONE)),
                new ItemStack(Items.DIAMOND));

        RecipeSnapshot snapshot = new RecipeScanner().describe(recipe, recipe.getId());

        assertNotNull(snapshot, "4x1 配方不得再被 3x3 限制拒绝");
        assertEquals(RecipeKind.SHAPED, snapshot.kind());
        assertEquals("4", snapshot.attribute("gridWidth"));
        assertEquals("1", snapshot.attribute("gridHeight"));
        assertEquals(1, snapshot.outputs().size(), "单个结果堆叠不得产生重复产物");
        assertEquals(ItemKey.of(new ResourceLocation("minecraft", "diamond")),
                snapshot.primaryOutput().item());
    }

    @Test
    void shapelessRecipeIsClassifiedAsShapeless() {
        ItemStack result = new ItemStack(Items.STICK);
        result.setCount(4);
        ShapelessRecipe recipe = new ShapelessRecipe(
                new ResourceLocation("sudoersemc_test", "four_sticks"), "test",
                CraftingBookCategory.MISC, result,
                NonNullList.withSize(2, Ingredient.of(Items.OAK_PLANKS)));

        RecipeSnapshot snapshot = new RecipeScanner().describe(recipe, recipe.getId());

        assertNotNull(snapshot);
        assertEquals(RecipeKind.SHAPELESS, snapshot.kind());
        assertEquals("2", snapshot.attribute("gridWidth"));
        assertEquals("1", snapshot.attribute("gridHeight"));
        assertEquals(1, snapshot.outputs().size());
        assertEquals(4, snapshot.primaryOutput().count(), "结果堆叠数量必须保留");
    }

    @Test
    void recipeEnumerationFailureIsReportedAsUnknownTotal() {
        RecipeScanner.ScanResult result = scanner.scan(new ExplodingRecipeManager());

        assertTrue(result.enumerationFailed(), "枚举失败必须使用独立标志");
        assertEquals(0, result.failed(), "不得把枚举失败伪造成一条失败配方");
        assertEquals(0, result.total(), "枚举失败时配方总数未知");
        assertEquals(1, result.failedRecipeDetails().size());
        assertTrue(result.failedRecipeDetails().get(0).contains("recipe-manager"));
    }

    @Test
    void failedRecipeDetailsAreCappedButFailureCountKeepsGrowing() {
        int brokenRecipes = RecipeScanner.MAX_FAILED_DETAILS + 10;
        RecipeScanner.ScanResult result = scanner.scan(new FailingRecipeManager(brokenRecipes));

        assertFalse(result.enumerationFailed());
        assertEquals(brokenRecipes, result.failed(), "失败总数必须继续累加");
        assertEquals(RecipeScanner.MAX_FAILED_DETAILS, result.failedRecipeDetails().size(),
                "失败明细必须限制在内存上限内");
    }


    @Test
    void zeroDamageOnlyTagIsNormalizedToPlainItem() {
        ItemStack stack = new ItemStack(Items.STONE);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 0);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertNull(output.outputNbt(), "仅 Damage:0 不应生成配方输出 NBT");
        assertEquals(ItemKey.of(STONE_ID), output.item(), "仅 Damage:0 不应产生伪变体身份");
    }

    @Test
    void longDamageAboveIntRangeKeepsRecipeVariant() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Damage", 4294967296L);
        ItemStack stack = new ItemStack(Items.STONE);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertEquals(ItemKey.of(STONE_ID, "{Damage:4294967296L}"), output.item(),
                "LongTag 2^32 不能被 getInt() 截断后丢失变体");
        assertEquals(tag.toString(), output.outputNbt(),
                "非零 long 伤害必须保留原始 SNBT");
    }

    @Test
    void fractionalDamageKeepsRecipeVariant() {
        CompoundTag tag = new CompoundTag();
        tag.putDouble("Damage", 0.5D);
        ItemStack stack = new ItemStack(Items.STONE);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertEquals(ItemKey.of(STONE_ID, "{Damage:0.5d}"), output.item(),
                "0.5 不能被 getInt() 截断为 0 后误判为默认伤害");
        assertEquals(tag.toString(), output.outputNbt(),
                "非零小数伤害必须保留原始 SNBT");
    }

    @Test
    void zeroDamageOfEveryNumericTypeIsDroppedFromRecipeOutput() {
        assertZeroDamageDropped((byte) 0);
        assertZeroDamageDropped((short) 0);
        assertZeroDamageDropped(0);
        assertZeroDamageDropped(0L);
        assertZeroDamageDropped(0.0F);
        assertZeroDamageDropped(0.0D);
    }

    @Test
    void linkageErrorDuringRecipeLookupCountsAsFailed() {
        RecipeManager manager = new RecipeManager() {

            @Override
            public Stream<ResourceLocation> getRecipeIds() {
                return Stream.of(RECIPE_ID);
            }

            @Override
            public Optional<? extends Recipe<?>> byKey(ResourceLocation id) {
                throw new NoClassDefFoundError("broken recipe class");
            }
        };

        RecipeScanner.ScanResult result = scanner.scan(manager);

        assertEquals(0, result.recipes().size());
        assertEquals(1, result.failed(), "单条配方链接失败必须计入 failed");
        assertEquals(1, result.total());
    }

    @Test
    void linkageErrorWhileDescribingRecipeCountsAsFailed() {
        Recipe<CraftingContainer> brokenResult = new CraftingRecipe() {

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
                return true;
            }

            @Override
            public ItemStack getResultItem(RegistryAccess access) {
                throw new NoClassDefFoundError("broken recipe result");
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

            @Override
            public CraftingBookCategory category() {
                return CraftingBookCategory.MISC;
            }

            @Override
            public NonNullList<Ingredient> getIngredients() {
                return NonNullList.of(Ingredient.EMPTY, Ingredient.of(Items.DIRT));
            }
        };

        RecipeManager manager = new RecipeManager() {

            @Override
            public Stream<ResourceLocation> getRecipeIds() {
                return Stream.of(RECIPE_ID);
            }

            @Override
            public Optional<? extends Recipe<?>> byKey(ResourceLocation id) {
                return Optional.of(brokenResult);
            }
        };

        RecipeScanner.ScanResult result = scanner.scan(manager);

        assertEquals(0, result.recipes().size());
        assertEquals(1, result.failed(), "单条配方描述时的 LinkageError 必须计入 failed");
        assertEquals(1, result.total());
    }

    @Test
    void nonDamageableNbtOutputUsesVariantIdentityAndKeepsRawNbt() {
        ItemStack stack = new ItemStack(Items.STONE);
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"只影响展示\"}");
        CompoundTag tag = new CompoundTag();
        tag.putInt("Marker", 7);
        tag.put("display", display);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertEquals(ItemKey.of(STONE_ID, "{Marker:7}"), output.item(),
                "求解与变体扫描必须使用同一规范化身份");
        assertEquals(tag.toString(), output.outputNbt(),
                "注册 ProjectE 仍需要包含 display 的原始 SNBT");
    }

    /**
     * 数值为 0 的 {@code Damage} 不论标签类型都必须归一为无变体、无 NBT。
     */
    private static void assertZeroDamageDropped(Number value) {
        CompoundTag tag = new CompoundTag();
        if (value instanceof Byte byteValue) {
            tag.putByte("Damage", byteValue);
        } else if (value instanceof Short shortValue) {
            tag.putShort("Damage", shortValue);
        } else if (value instanceof Integer intValue) {
            tag.putInt("Damage", intValue);
        } else if (value instanceof Long longValue) {
            tag.putLong("Damage", longValue);
        } else if (value instanceof Float floatValue) {
            tag.putFloat("Damage", floatValue);
        } else {
            tag.putDouble("Damage", value.doubleValue());
        }
        ItemStack stack = new ItemStack(Items.STONE);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertEquals(ItemKey.of(STONE_ID), output.item(),
                "数值为 0 的 Damage 不得产生变体身份：" + tag);
        assertNull(output.outputNbt(),
                "数值为 0 的 Damage 不得保留为配方输出 NBT：" + tag);
    }


    private static TagKey<Item> unboundTag() {
        return TagKey.create(Registries.ITEM, new ResourceLocation("sudoersemc_test", "unbound"));
    }

    /** 模拟配方管理器整体枚举失败。 */
    private static final class ExplodingRecipeManager extends RecipeManager {

        @Override
        public Stream<ResourceLocation> getRecipeIds() {
            throw new IllegalStateException("枚举爆炸");
        }
    }

    /** 模拟大量配方在读取时抛异常，用于验证失败明细上限。 */
    private static final class FailingRecipeManager extends RecipeManager {

        private final int recipeCount;

        private FailingRecipeManager(int recipeCount) {
            this.recipeCount = recipeCount;
        }

        @Override
        public Stream<ResourceLocation> getRecipeIds() {
            return java.util.stream.IntStream.range(0, recipeCount)
                    .mapToObj(index -> new ResourceLocation("sudoersemc_test", "broken_" + index));
        }

        @Override
        public Optional<? extends Recipe<?>> byKey(ResourceLocation id) {
            throw new IllegalStateException("配方损坏");
        }
    }

    private static ShapedRecipe shaped(int width, int height, Ingredient first, Ingredient second,
                                       ItemStack result) {
        NonNullList<Ingredient> ingredients = NonNullList.withSize(width * height, Ingredient.EMPTY);
        ingredients.set(0, first);
        ingredients.set(1, second);
        return new ShapedRecipe(RECIPE_ID, "", CraftingBookCategory.MISC, width, height, ingredients, result);
    }

    @Test
    void containerRemaindersAreCapturedButNeverListedAsOutputs() {
        ShapelessRecipe recipe = new ShapelessRecipe(
                new ResourceLocation("sudoersemc_test", "cake_from_milk"), "test",
                CraftingBookCategory.MISC, new ItemStack(Items.CAKE),
                NonNullList.withSize(1, Ingredient.of(Items.MILK_BUCKET)));

        RecipeSnapshot snapshot = new RecipeScanner().describe(recipe, recipe.getId());

        assertNotNull(snapshot);
        ItemKey bucket = ItemKey.of(new ResourceLocation("minecraft", "bucket"));
        assertEquals(List.of(bucket),
                snapshot.containerRemainders().stream().map(RecipeOutput::item).toList());
        assertEquals(1, snapshot.containerRemainders().get(0).count());
        assertTrue(snapshot.outputs().stream().noneMatch(output -> output.item().equals(bucket)),
                "容器返还只能进入成本抵扣，不能重复发布为产物");
    }

    @Test
    void multiCandidateIngredientSkipsRemainderCredit() {
        ShapelessRecipe recipe = new ShapelessRecipe(
                new ResourceLocation("sudoersemc_test", "cake_from_any_bucket"), "test",
                CraftingBookCategory.MISC, new ItemStack(Items.CAKE),
                NonNullList.withSize(1, Ingredient.of(Items.MILK_BUCKET, Items.WATER_BUCKET)));

        RecipeSnapshot snapshot = new RecipeScanner().describe(recipe, recipe.getId());

        assertNotNull(snapshot);
        assertTrue(snapshot.containerRemainders().isEmpty(),
                "多候选输入的返还物无法与最便宜成本口径对齐，必须放弃抵扣");
    }

    @Test
    void containerRemainderScanningCanBeDisabled() {
        ShapelessRecipe recipe = new ShapelessRecipe(
                new ResourceLocation("sudoersemc_test", "cake_without_remainders"), "test",
                CraftingBookCategory.MISC, new ItemStack(Items.CAKE),
                NonNullList.withSize(1, Ingredient.of(Items.MILK_BUCKET)));
        RecipeScanner scanner = new RecipeScanner(GoetyRitualReader.unavailable(), false);

        RecipeSnapshot snapshot = scanner.describe(recipe, recipe.getId());

        assertNotNull(snapshot);
        assertTrue(snapshot.containerRemainders().isEmpty(),
                "关闭扫描开关后不得读取 remaining items");
    }
}
