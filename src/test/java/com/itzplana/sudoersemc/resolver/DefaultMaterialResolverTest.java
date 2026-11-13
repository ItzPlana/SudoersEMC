package com.itzplana.sudoersemc.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.OptionalLong;
import java.util.function.ToLongFunction;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * {@link DefaultMaterialResolver} 的单元测试，覆盖容器内容、数量与耐久折价规则。
 */
class DefaultMaterialResolverTest {

    static {
        // Forge 的 ItemStack 静态初始化需要 BuiltInRegistries，因此测试前必须先完成引导。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ResourceLocation SHULKER_ID = new ResourceLocation("minecraft", "shulker_box");
    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");
    private static final ResourceLocation DIAMOND_SWORD_ID =
            new ResourceLocation("minecraft", "diamond_sword");
    private static final ItemKey SHULKER_KEY = ItemKey.of(SHULKER_ID);
    private static final ItemKey DIAMOND_KEY = ItemKey.of(DIAMOND_ID);
    private static final ItemKey DIAMOND_SWORD_KEY = ItemKey.of(DIAMOND_SWORD_ID);

    @Test
    void singleItemContainerResolvesToContainedItemEmc() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L);
        ItemStack stack = container(itemEntry("minecraft:diamond", 1));

        assertTrue(resolver.supports(stack));
        assertEquals(OptionalLong.of(128L), resolver.resolve(stack));
    }

    @Test
    void containerMultipliesContainedItemCount() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L);
        ItemStack stack = container(itemEntry("minecraft:diamond", 64));

        assertEquals(OptionalLong.of(128L * 64L), resolver.resolve(stack));
    }

    @Test
    void containerCountMultiplicationSaturates() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_KEY) ? Long.MAX_VALUE : 0L);
        ItemStack stack = container(itemEntry("minecraft:diamond", 64));

        assertEquals(OptionalLong.of(Long.MAX_VALUE), resolver.resolve(stack));
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
    void damagedContainedItemIsDiscounted() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_SWORD_KEY) ? 1000L : 0L);
        int maxDamage = new ItemStack(Items.DIAMOND_SWORD).getMaxDamage();
        int damage = maxDamage / 2;
        CompoundTag entry = itemEntry("minecraft:diamond_sword", 1);
        CompoundTag itemTag = new CompoundTag();
        itemTag.putInt("Damage", damage);
        entry.put("tag", itemTag);

        long expected = 1000L * (maxDamage - damage + 1L) / maxDamage;
        assertEquals(OptionalLong.of(expected), resolver.resolve(container(entry)));
    }

    @Test
    void containerAddsStoredEmcFromContainedItemTag() {
        DefaultMaterialResolver resolver = storedEmcResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L);
        CompoundTag entry = itemEntry("minecraft:diamond", 1);
        entry.put("tag", storedEmcTag(500L));

        assertEquals(OptionalLong.of(128L + 500L), resolver.resolve(container(entry)));
    }

    @Test
    void containedStoredEmcIsAddedAfterDamageDiscount() {
        DefaultMaterialResolver resolver = storedEmcResolver(
                key -> key.equals(DIAMOND_SWORD_KEY) ? 1000L : 0L);
        int maxDamage = new ItemStack(Items.DIAMOND_SWORD).getMaxDamage();
        int damage = maxDamage / 2;
        CompoundTag itemTag = storedEmcTag(500L);
        itemTag.putInt("Damage", damage);
        CompoundTag entry = itemEntry("minecraft:diamond_sword", 1);
        entry.put("tag", itemTag);

        long discounted = 1000L * (maxDamage - damage + 1L) / maxDamage;
        assertEquals(OptionalLong.of(discounted + 500L), resolver.resolve(container(entry)));
    }

    @Test
    void containedStoredEmcSaturatesInsteadOfOverflowing() {
        DefaultMaterialResolver resolver = storedEmcResolver(
                key -> key.equals(DIAMOND_KEY) ? Long.MAX_VALUE : 0L);
        CompoundTag entry = itemEntry("minecraft:diamond", 1);
        entry.put("tag", storedEmcTag(1L));

        assertEquals(OptionalLong.of(Long.MAX_VALUE), resolver.resolve(container(entry)));
    }

    @Test
    void unknownContainedBaseValueWithStoredEmcReturnsEmpty() {
        DefaultMaterialResolver resolver = storedEmcResolver(key -> 0L);
        CompoundTag entry = itemEntry("minecraft:diamond", 1);
        entry.put("tag", storedEmcTag(4096L));

        assertTrue(resolver.resolve(container(entry)).isEmpty());
    }

    @Test
    void negativeContainedStoredEmcIsIgnored() {
        DefaultMaterialResolver resolver = storedEmcResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L);
        CompoundTag entry = itemEntry("minecraft:diamond", 1);
        entry.put("tag", storedEmcTag(-4096L));

        assertEquals(OptionalLong.of(128L), resolver.resolve(container(entry)));
    }

    @Test
    void containedEnchantedVariantUsesVariantKeyAndStoredEmc() {
        ItemKey enchantedKey = ItemKey.of(DIAMOND_SWORD_ID,
                "{Damage:0,Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}]}");
        DefaultMaterialResolver resolver = storedEmcResolver(
                key -> key.equals(enchantedKey) ? 2000L : 0L);
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:sharpness");
        enchantment.putShort("lvl", (short) 5);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);
        CompoundTag itemTag = storedEmcTag(300L);
        itemTag.putInt("Damage", 0);
        itemTag.put("Enchantments", enchantments);
        CompoundTag entry = itemEntry("minecraft:diamond_sword", 1);
        entry.put("tag", itemTag);

        assertEquals(OptionalLong.of(2300L), resolver.resolve(container(entry)));
    }

    private static CompoundTag storedEmcTag(long storedEmc) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("StoredEMC", storedEmc);
        return tag;
    }

    private static DefaultMaterialResolver storedEmcResolver(ToLongFunction<ItemKey> lookup) {
        // 测试用：显式接受任意内容物为 EMC holder，以便用原版物品验证叠加/饱和逻辑。
        return new DefaultMaterialResolver(lookup, stack -> true);
    }

    @Test
    void storedEmcOnNonHolderItemIsIgnoredByDefault() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L);
        CompoundTag entry = itemEntry("minecraft:diamond", 1);
        entry.put("tag", storedEmcTag(500L));

        assertEquals(OptionalLong.of(128L), resolver.resolve(container(entry)));
    }

    @Test
    void storedEmcIsIgnoredWhenInjectedHolderPredicateRejects() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L,
                stack -> false);
        CompoundTag entry = itemEntry("minecraft:diamond", 1);
        entry.put("tag", storedEmcTag(500L));

        assertEquals(OptionalLong.of(128L), resolver.resolve(container(entry)));
    }

    @Test
    void defaultHolderWhitelistRecognisesProjectEKleinStarsOnly() {
        String[] kleinStars = {
                "klein_star_ein", "klein_star_zwei", "klein_star_drei",
                "klein_star_vier", "klein_star_sphere", "klein_star_omega"
        };
        for (String name : kleinStars) {
            assertTrue(DefaultMaterialResolver.isKnownProjectEEmcHolder(
                    new ResourceLocation("projecte", name)), name);
        }
        assertFalse(DefaultMaterialResolver.isKnownProjectEEmcHolder(DIAMOND_ID));
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
    void projectEStoredEmcIsDelegatedToProjectE() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(SHULKER_KEY) ? 300L : 0L);
        CompoundTag tag = new CompoundTag();
        tag.putLong("StoredEMC", 500L);
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.setTag(tag);

        assertFalse(resolver.supports(stack));
        assertTrue(resolver.resolve(stack).isEmpty());
    }

    @Test
    void storedEmcDoesNotAddToContainerValue() {
        DefaultMaterialResolver resolver = new DefaultMaterialResolver(
                key -> key.equals(DIAMOND_KEY) ? 128L : 0L);
        CompoundTag root = new CompoundTag();
        root.putLong("StoredEMC", 500L);
        CompoundTag blockEntity = new CompoundTag();
        ListTag items = new ListTag();
        items.add(itemEntry("minecraft:diamond", 1));
        blockEntity.put("Items", items);
        root.put("BlockEntityTag", blockEntity);
        ItemStack stack = new ItemStack(Items.SHULKER_BOX);
        stack.setTag(root);

        assertEquals(OptionalLong.of(128L), resolver.resolve(stack));
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
}
