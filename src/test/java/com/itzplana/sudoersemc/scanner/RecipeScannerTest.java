package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.junit.jupiter.api.Test;

/**
 * {@link RecipeScanner} 输出 SNBT 保留行为的单元测试。
 *
 * <p>测试只覆盖不依赖配方管理器的转换路径，因此不需要启动服务器；Forge 的
 * {@code ItemStack} 静态初始化需要注册表，测试前先完成引导。</p>
 */
class RecipeScannerTest {

    private static final ResourceLocation DIAMOND_SWORD_ID =
            new ResourceLocation("minecraft", "diamond_sword");

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
