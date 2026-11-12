package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.itzplana.sudoersemc.core.ItemKey;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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
}
