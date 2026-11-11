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
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * 内置的 NBT 材料解析器，覆盖原版容器内容与 ProjectE 的 {@code stored_emc}。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>{@code BlockEntityTag.Items} 中恰好只有一个非空堆叠时，装有一个物品的容器价值等于
 *       该物品；容器里有多个非空堆叠时无法给出唯一价格，返回空。</li>
 *   <li>{@code stored_emc} 表示 ProjectE 已存储的 EMC，会叠加在基础物品 EMC 之上；基础物品
 *       未知（查询结果为 0）时只返回存储量。</li>
 * </ul>
 *
 * <p>查询函数必须返回非负 EMC，0 表示未知。解析结果始终大于 0，避免把无法定价的物品
 * 当成免费物品。容器内容优先于 {@code stored_emc}；两者同时存在时以容器内容为准。</p>
 */
public final class DefaultMaterialResolver implements NbtMaterialResolver {

    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";
    private static final String STORED_EMC = "stored_emc";
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
        if (tag == null) {
            return false;
        }
        if (tag.contains(STORED_EMC, Tag.TAG_ANY_NUMERIC)) {
            return true;
        }
        return containerItems(tag) != null;
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

        OptionalLong containerValue = resolveSingleItemContainer(tag);
        if (containerValue.isPresent()) {
            return containerValue;
        }

        if (!tag.contains(STORED_EMC, Tag.TAG_ANY_NUMERIC)) {
            return OptionalLong.empty();
        }

        // 存储量为 0 时仍应回退到基础物品 EMC；负数按 0 处理，避免产生负价格。
        long stored = Math.max(0L, tag.getLong(STORED_EMC));
        long baseValue = 0L;
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (itemId != null) {
            baseValue = Math.max(0L, emcLookup.applyAsLong(ItemKey.of(itemId)));
        }
        long total = saturatedAdd(baseValue, stored);
        return total > 0L ? OptionalLong.of(total) : OptionalLong.empty();
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
        long value = emcLookup.applyAsLong(VariantNormalizer.keyFor(itemId, itemTag));
        return value > 0L ? OptionalLong.of(value) : OptionalLong.empty();
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
     * 饱和加法：避免极端 EMC 数据在相加时溢出成负数。
     */
    private static long saturatedAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
