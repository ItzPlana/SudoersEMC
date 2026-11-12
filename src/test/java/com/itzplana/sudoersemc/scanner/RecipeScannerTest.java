package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
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

/**
 * {@link RecipeScanner} 的扫描与 SNBT 保留行为测试。
 *
 * <p>测试只覆盖不依赖配方管理器的转换路径，因此不需要启动服务器；引导逻辑集中在
 * {@link MinecraftTestBootstrap}。</p>
 */
class RecipeScannerTest {

    private static final ResourceLocation RECIPE_ID = new ResourceLocation("sudoersemc_test", "recipe");
    private static final ResourceLocation DIAMOND_SWORD_ID =
            new ResourceLocation("minecraft", "diamond_sword");
    private static final ResourceLocation STONE_ID = new ResourceLocation("minecraft", "stone");
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
    void nbtOutputIsPreservedAsSnbt() throws Exception {
        ItemStack stack = new ItemStack(Items.DIAMOND_SWORD);
        stack.setCount(2);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 7);
        stack.setTag(tag);

        RecipeOutput output = RecipeScanner.convertStack(stack);

        assertNotNull(output);
        assertEquals(ItemKey.of(new ResourceLocation("minecraft", "diamond_sword")), output.item());
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
