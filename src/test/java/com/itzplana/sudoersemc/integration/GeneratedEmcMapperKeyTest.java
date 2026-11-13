package com.itzplana.sudoersemc.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.resolver.DefaultMaterialResolver;
import com.itzplana.sudoersemc.resolver.VariantNormalizer;
import java.util.OptionalLong;
import moze_intel.projecte.api.ItemInfo;
import moze_intel.projecte.api.nss.NSSItem;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * PR #5 第三轮 review 的端到端回归测试。
 *
 * <p>验证 {@link VariantNormalizer#keyFor(ResourceLocation, CompoundTag)} 生成的容器变体键
 * 经 {@link GeneratedEmcMapper#toSimpleStack(ItemKey)} 转换后，与真实 {@link ItemStack} 的
 * ProjectE {@link ItemInfo} 精确相等。ProjectE 的 {@code ItemInfo.equals} 比较完整 NBT，
 * {@code EMCMappingHandler.getStoredEmcValue} 又是精确 map 查找；如果容器内容物的嵌套
 * {@code StoredEMC} 没有进入变体键，生成映射必然 miss。</p>
 */
class GeneratedEmcMapperKeyTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ResourceLocation SHULKER_BOX_ID = new ResourceLocation("minecraft", "shulker_box");
    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");

    @Test
    void containerWithNestedStoredEmcProducesExactProjectEMappingKey() throws Exception {
        ItemStack stack = shulkerWith("projecte:klein_star_omega", 4096L);
        ItemKey key = VariantNormalizer.keyFor(SHULKER_BOX_ID, stack.getTag());

        assertNotNull(key.variantHash());
        NormalizedSimpleStack nss = GeneratedEmcMapper.toSimpleStack(key);
        assertNotNull(nss);
        assertTrue(nss instanceof NSSItem);
        NSSItem nssItem = (NSSItem) nss;

        // GeneratedEmcMapper 把 variantHash 原样解析为 NBT；NSSItem 必须保留真实堆叠的完整
        // NBT（包括嵌套 StoredEMC），否则 ProjectE 的精确 ItemInfo 查找会 miss。
        assertEquals(stack.getTag(), nssItem.getNBT());
        ItemInfo mapped = ItemInfo.fromItem(stack.getItem(), nssItem.getNBT());
        assertEquals(ItemInfo.fromStack(stack), mapped);
        assertEquals(ItemInfo.fromStack(stack).hashCode(), mapped.hashCode());
    }

    @Test
    void resolverValueAndMappingKeyAgreeForContainerWithStoredEmc() throws Exception {
        ItemStack stack = shulkerWith("minecraft:diamond", 500L);
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(ItemKey.of(DIAMOND_ID)) ? 128L : 0L,
                contained -> true);

        // 容器价值 = 内容物基准价 128 + 内容物 StoredEMC 500。
        assertEquals(OptionalLong.of(628L), resolver.resolve(stack));

        ItemKey key = VariantNormalizer.keyFor(SHULKER_BOX_ID, stack.getTag());
        NSSItem mapped = (NSSItem) GeneratedEmcMapper.toSimpleStack(key);
        assertEquals(ItemInfo.fromStack(stack), ItemInfo.fromItem(stack.getItem(), mapped.getNBT()));
    }

    @Test
    void containerKeyStillStripsRootStoredEmcForProjectEProcessing() throws Exception {
        ItemStack stack = shulkerWith("projecte:klein_star_omega", 4096L);
        stack.getTag().putLong("StoredEMC", 999L);

        ItemKey key = VariantNormalizer.keyFor(SHULKER_BOX_ID, stack.getTag());
        CompoundTag parsed = TagParser.parseTag(key.variantHash());

        // 根级 StoredEMC 由 ProjectE 的 StoredEMCProcessor 处理，不能进入变体身份；
        // 内容物的嵌套 StoredEMC 则必须保留。
        assertFalse(parsed.contains("StoredEMC"));
        assertEquals(4096L, parsed.getCompound("BlockEntityTag")
                .getList("Items", 10)
                .getCompound(0)
                .getCompound("tag")
                .getLong("StoredEMC"));
    }

    @Test
    void damagedVariantKeyIsIntentionallyNotExactProjectEInfo() throws Exception {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 17);
        tag.put("Enchantments", sharpnessFive());
        sword.setTag(tag);

        ItemKey key = VariantNormalizer.keyFor(BuiltInRegistries.ITEM.getKey(sword.getItem()), tag);
        assertNotNull(key.variantHash());
        assertTrue(key.variantHash().contains("Damage:0"));

        NSSItem mapped = (NSSItem) GeneratedEmcMapper.toSimpleStack(key);
        ItemInfo fromKey = ItemInfo.fromItem(sword.getItem(), mapped.getNBT());

        // 有意限制：真实 Damage>0 的 ItemInfo 与规范化键不同。ProjectE 会回退到无 NBT
        // 基础价并执行 DamageProcessor，不会误用其它损坏程度的变体价格。
        assertNotEquals(ItemInfo.fromStack(sword), fromKey);
        assertTrue(ItemInfo.fromStack(sword).hasNBT());
        assertEquals(17, ItemInfo.fromStack(sword).getNBT().getInt("Damage"));
        assertEquals(0, fromKey.getNBT().getInt("Damage"));
    }

    private static ItemStack shulkerWith(String itemId, long storedEmc) {
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

        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.setTag(root);
        return stack;
    }

    private static ListTag sharpnessFive() {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:sharpness");
        enchantment.putShort("lvl", (short) 5);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);
        return enchantments;
    }
}
