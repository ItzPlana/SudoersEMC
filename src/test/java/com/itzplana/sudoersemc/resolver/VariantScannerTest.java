package com.itzplana.sudoersemc.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * {@link VariantScanner} 的单元测试，覆盖去重、过滤、上限与排序。
 */
class VariantScannerTest {

    private static final ResourceLocation STONE_ID = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIAMOND_SWORD_ID =
            new ResourceLocation("minecraft", "diamond_sword");
    private static final ItemStack BASE_STACK;
    private static final ItemStack DAMAGEABLE_STACK;

    static {
        // Forge 的 ItemStack 静态初始化需要 BuiltInRegistries，因此测试前必须先完成引导。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        BASE_STACK = new ItemStack(Items.STONE);
        DAMAGEABLE_STACK = new ItemStack(Items.DIAMOND_SWORD);
    }

    private final VariantScanner scanner = new VariantScanner();

    @Test
    void duplicateTagsCollapseToOneKey() {
        CompoundTag first = damageTag(5);
        CompoundTag second = damageTag(5);

        List<ItemKey> keys = scanner.scan(BASE_STACK, List.of(first, second), 10);

        assertEquals(List.of(ItemKey.of(STONE_ID, "{Damage:5}")), keys);
    }

    @Test
    void emptyAndDisplayOnlyTagsAreDropped() {
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"仅展示名称\"}");
        CompoundTag displayOnly = new CompoundTag();
        displayOnly.put("display", display);

        List<ItemKey> keys = scanner.scan(BASE_STACK, List.of(new CompoundTag(), displayOnly), 10);

        assertTrue(keys.isEmpty());
    }

    @Test
    void maxVariantsPerItemIsRespected() {
        List<CompoundTag> variants = List.of(damageTag(1), damageTag(2), damageTag(3));

        List<ItemKey> keys = scanner.scan(BASE_STACK, variants, 2);

        assertEquals(2, keys.size());
    }

    @Test
    void hardCeilingIsEnforced() {
        List<CompoundTag> variants = new ArrayList<>(1001);
        for (int i = 0; i <= 1000; i++) {
            CompoundTag tag = new CompoundTag();
            tag.putInt("Marker", i);
            variants.add(tag);
        }

        List<ItemKey> keys = scanner.scan(BASE_STACK, variants, 2000);

        assertEquals(VariantScanner.HARD_VARIANT_CEILING, keys.size());
    }

    @Test
    void truncationKeepsSmallestVariantsRegardlessOfInputOrder() {
        List<CompoundTag> variants = List.of(
                markerTag(9), markerTag(2), markerTag(5), markerTag(1));

        List<ItemKey> keys = scanner.scan(BASE_STACK, variants, 2);

        assertEquals(List.of(
                ItemKey.of(STONE_ID, "{Marker:1}"),
                ItemKey.of(STONE_ID, "{Marker:2}")
        ), keys);
    }

    @Test
    void damageOnlyVariantsAreDroppedForDamageableItems() {
        List<ItemKey> keys = scanner.scan(DAMAGEABLE_STACK,
                List.of(damageTag(1), damageTag(2)), 10);

        assertTrue(keys.isEmpty());
    }

    @Test
    void damageValuesCollapseForDamageableVariants() {
        List<ItemKey> keys = scanner.scan(DAMAGEABLE_STACK,
                List.of(enchantedSwordTag(3), enchantedSwordTag(900)), 10);

        assertEquals(List.of(ItemKey.of(DIAMOND_SWORD_ID,
                "{Damage:0,Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}]}")), keys);
    }

    @Test
    void containerContentStoredEmcProducesDistinctBoundedVariants() {
        ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
        List<ItemKey> keys = scanner.scan(shulker, List.of(
                containerTag("projecte:klein_star_omega", 100L),
                containerTag("projecte:klein_star_omega", 200L)), 10);

        // 不同存储量对应不同容器价值，必须保留为不同变体；数量仍受 scan limit 约束。
        assertEquals(2, keys.size());
        assertTrue(keys.get(0).variantHash().contains("StoredEMC"));
        assertTrue(keys.get(1).variantHash().contains("StoredEMC"));
        assertNotEquals(keys.get(0), keys.get(1));
    }

    private static CompoundTag enchantedSwordTag(int damage) {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:sharpness");
        enchantment.putShort("lvl", (short) 5);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", damage);
        tag.put("Enchantments", enchantments);
        return tag;
    }

    @Test
    void resultIsSortedDeterministically() {
        List<ItemKey> keys = scanner.scan(BASE_STACK,
                List.of(damageTag(9), damageTag(1), damageTag(5)), 10);

        assertEquals(List.of(
                ItemKey.of(STONE_ID, "{Damage:1}"),
                ItemKey.of(STONE_ID, "{Damage:5}"),
                ItemKey.of(STONE_ID, "{Damage:9}")
        ), keys);
    }

    @Test
    void resultIsImmutable() {
        List<ItemKey> keys = scanner.scan(BASE_STACK, List.of(damageTag(1)), 10);

        assertThrows(UnsupportedOperationException.class,
                () -> keys.add(ItemKey.of(STONE_ID, "{Damage:2}")));
    }

    @Test
    void invalidMaxVariantsPerItemIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> scanner.scan(BASE_STACK, List.of(damageTag(1)), 0));
        assertThrows(IllegalArgumentException.class,
                () -> scanner.scan(BASE_STACK, List.of(damageTag(1)), -1));
    }

    private static CompoundTag damageTag(int damage) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", damage);
        return tag;
    }

    private static CompoundTag markerTag(int marker) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Marker", marker);
        return tag;
    }

    private static CompoundTag containerTag(String itemId, long storedEmc) {
        CompoundTag contentTag = new CompoundTag();
        contentTag.putLong("StoredEMC", storedEmc);
        CompoundTag entry = new CompoundTag();
        entry.putString("id", itemId);
        entry.putByte("Count", (byte) 1);
        entry.put("tag", contentTag);
        ListTag items = new ListTag();
        items.add(entry);
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.put("Items", items);
        CompoundTag root = new CompoundTag();
        root.put("BlockEntityTag", blockEntity);
        return root;
    }
}
