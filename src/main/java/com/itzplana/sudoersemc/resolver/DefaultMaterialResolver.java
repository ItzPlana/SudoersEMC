package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.function.ToLongFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 内置的 NBT 材料解析器，覆盖原版容器内容。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>{@code BlockEntityTag.Items} 中恰好只有一个非空堆叠时，容器价值等于该物品的
 *       单元 EMC 乘以 {@code Count}；容器里有多个非空堆叠时无法给出唯一价格，返回空。</li>
 *   <li>容器内可损坏物品的 {@code Damage} 会按与 ProjectE {@code DamageProcessor}
 *       相同的公式折价，避免把损坏物品按满耐久计价。</li>
 *   <li>ProjectE 的 {@code StoredEMC} 由 {@code StoredEMCProcessor} 在 EMC 查询阶段
 *       动态叠加，本解析器不再重复处理，否则会造成双重计价。</li>
 * </ul>
 *
 * <p>查询函数必须返回非负 EMC，0 表示未知。解析结果始终大于 0，避免把无法定价的物品
 * 当成免费物品。</p>
 */
public final class DefaultMaterialResolver implements NbtMaterialResolver {

    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";
    private static final String ID = "id";
    private static final String COUNT = "Count";
    private static final String ITEM_TAG = "tag";

    private final ToLongFunction<ItemKey> emcLookup;

    /**
     * @param emcLookup 从物品键查询 EMC 的函数，0 表示未知；不得为 {@code null}
     */
    public DefaultMaterialResolver(ToLongFunction<ItemKey> emcLookup) {
        this.emcLookup = Objects.requireNonNull(emcLookup, "emcLookup");
    }

    @Override
    public boolean supports(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && containerItems(tag) != null;
    }

    @Override
    public OptionalLong resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return OptionalLong.empty();
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return OptionalLong.empty();
        }
        return resolveSingleItemContainer(tag);
    }

    /**
     * 读取容器内容。只有列表中恰好一个 {@code Count > 0} 的条目时才能给出唯一价格。
     */
    private OptionalLong resolveSingleItemContainer(CompoundTag tag) {
        ListTag items = containerItems(tag);
        if (items == null) {
            return OptionalLong.empty();
        }

        CompoundTag single = null;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            if (entry.getByte(COUNT) <= 0) {
                continue;
            }
            if (single != null) {
                // 多个非空堆叠意味着容器价值不能由单个物品代表。
                return OptionalLong.empty();
            }
            single = entry;
        }

        if (single == null) {
            return OptionalLong.empty();
        }

        ResourceLocation itemId = ResourceLocation.tryParse(single.getString(ID));
        if (itemId == null) {
            return OptionalLong.empty();
        }
        CompoundTag itemTag = single.contains(ITEM_TAG, Tag.TAG_COMPOUND)
                ? single.getCompound(ITEM_TAG)
                : null;
        long unitValue = containedItemValue(itemId, itemTag);
        if (unitValue <= 0L) {
            return OptionalLong.empty();
        }
        long count = single.getByte(COUNT);
        return OptionalLong.of(saturatedMultiply(unitValue, count));
    }

    /**
     * 计算容器内单个物品的 EMC。可损坏物品按剩余耐久折价，公式与 ProjectE 的
     * {@code DamageProcessor} 一致。
     */
    private long containedItemValue(ResourceLocation itemId, @Nullable CompoundTag itemTag) {
        long value = emcLookup.applyAsLong(VariantNormalizer.keyFor(itemId, itemTag));
        if (value <= 0L) {
            return value;
        }

        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null || !item.canBeDepleted()) {
            return value;
        }

        ItemStack contained = new ItemStack(item);
        if (itemTag != null) {
            contained.setTag(itemTag);
        }
        int maxDamage = contained.getMaxDamage();
        int damage = contained.getDamageValue();
        if (maxDamage <= 0 || damage <= 0 || damage > maxDamage) {
            // ProjectE 在 damage > maxDamage 时放弃折价，这里保持一致。
            return value;
        }
        long remaining = maxDamage - damage + 1L;
        if (value > Long.MAX_VALUE / remaining) {
            return Long.MAX_VALUE;
        }
        return value * remaining / maxDamage;
    }

    /**
     * 读取 {@code BlockEntityTag.Items}；不存在或类型不正确时返回 {@code null}。
     */
    private static @Nullable ListTag containerItems(CompoundTag tag) {
        if (!tag.contains(BLOCK_ENTITY_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag blockEntity = tag.getCompound(BLOCK_ENTITY_TAG);
        if (!blockEntity.contains(ITEMS, Tag.TAG_LIST)) {
            return null;
        }
        return blockEntity.getList(ITEMS, Tag.TAG_COMPOUND);
    }

    /**
     * 饱和乘法：避免容器内大量物品的 EMC 相乘时溢出成负数。
     */
    private static long saturatedMultiply(long value, long count) {
        if (value <= 0L || count <= 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / count) {
            return Long.MAX_VALUE;
        }
        return value * count;
    }
}
