package com.itzplana.sudoersemc.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultMaterialResolver} 的单元测试，覆盖容器内容与 {@code stored_emc} 规则。
 */
class DefaultMaterialResolverTest {

    static {
        // Forge 的 ItemStack 静态初始化需要 BuiltInRegistries，因此测试前必须先完成引导。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ResourceLocation SHULKER_ID = new ResourceLocation("minecraft", "shulker_box");
    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");
    private static final ItemKey SHULKER_KEY = ItemKey.of(SHULKER_ID);
    private static final ItemKey DIAMOND_KEY = ItemKey.of(DIAMOND_ID);

    @Test
    void singleItemContainerResolvesToContainedItemEmc() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L);
        ItemStack stack = container(itemEntry("minecraft:diamond", 1));

        assertTrue(resolver.supports(stack));
        assertEquals(OptionalLong.of(128L), resolver.resolve(stack));
    }

    @Test
    void containerWithVariantItemUsesTheVariantKey() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(ItemKey.of(DIAMOND_ID, "{Damage:7}")) ? 64L : 0L);
        CompoundTag entry = itemEntry("minecraft:diamond", 1);
        CompoundTag itemTag = new CompoundTag();
        itemTag.putInt("Damage", 7);
        entry.put("tag", itemTag);
        ItemStack stack = container(entry);

        assertEquals(OptionalLong.of(64L), resolver.resolve(stack));
    }

    @Test
    void multiItemContainerReturnsEmpty() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(key -> 100L);
        ItemStack stack = container(
                itemEntry("minecraft:diamond", 1),
                itemEntry("minecraft:emerald", 1));

        assertTrue(resolver.resolve(stack).isEmpty());
    }

    @Test
    void emptyContainerReturnsEmpty() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(key -> 100L);

        assertTrue(resolver.resolve(container()).isEmpty());
        assertTrue(resolver.resolve(container(itemEntry("minecraft:diamond", 0))).isEmpty());
    }

    @Test
    void zeroStoredEmcStillUsesKnownBaseValue() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(SHULKER_KEY) ? 300L : 0L);
        ItemStack stack = storedEmcStack(0L);

        assertTrue(resolver.supports(stack));
        assertEquals(OptionalLong.of(300L), resolver.resolve(stack));
    }

    @Test
    void storedEmcAloneResolvesToStoredAmount() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(key -> 0L);
        ItemStack stack = storedEmcStack(500L);

        assertTrue(resolver.supports(stack));
        assertEquals(OptionalLong.of(500L), resolver.resolve(stack));
    }

    @Test
    void storedEmcPlusKnownBaseItemSumsBoth() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(SHULKER_KEY) ? 300L : 0L);
        ItemStack stack = storedEmcStack(500L);

        assertEquals(OptionalLong.of(800L), resolver.resolve(stack));
    }

    @Test
    void unrelatedTagReturnsEmpty() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(key -> 100L);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 3);
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.setTag(tag);

        assertFalse(resolver.supports(stack));
        assertTrue(resolver.resolve(stack).isEmpty());
    }

    @Test
    void unknownBaseItemWithStoredEmcReturnsOnlyStoredAmount() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(key -> 0L);
        ItemStack stack = storedEmcStack(700L);

        assertEquals(OptionalLong.of(700L), resolver.resolve(stack));
    }

    private static ItemStack container(CompoundTag... entries) {
        ListTag items = new ListTag();
        for (CompoundTag entry : entries) {
            items.add(entry);
        }
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Items", items);
        CompoundTag root = new CompoundTag();
        root.put("BlockEntityTag", blockEntity);

        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.setTag(root);
        return stack;
    }

    private static CompoundTag itemEntry(String id, int count) {
        CompoundTag entry = new CompoundTag();
        entry.putString("id", id);
        entry.putByte("Count", (byte) count);
        entry.putByte("Slot", (byte) 0);
        return entry;
    }

    private static ItemStack storedEmcStack(long storedEmc) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("stored_emc", storedEmc);
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.setTag(tag);
        return stack;
    }
}
