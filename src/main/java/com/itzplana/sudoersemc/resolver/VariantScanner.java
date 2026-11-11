package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 从观测到的 NBT 变体列表生成去重后的 {@link ItemKey} 列表。
 *
 * <p>每个标签先经 {@link VariantNormalizer#normalize(CompoundTag)} 规范化；规范化结果为
 * {@code null} 的标签（例如只包含自定义名称）会被丢弃。相同规范化结果只保留一个键。扫描
 * 最多返回 {@code maxVariantsPerItem} 个键，并额外受 {@link #HARD_VARIANT_CEILING} 限制，
 * 避免异常数据导致内存失控。结果按 {@link ItemKey#compareTo(ItemKey)} 排序且不可变。</p>
 */
public final class VariantScanner {

    /**
     * 单个物品允许生成的变体硬上限，防止损坏的数据源拖垮扫描流程。
     */
    public static final int HARD_VARIANT_CEILING = 1000;

    /**
     * 扫描并规范化观测到的变体。
     *
     * @param baseStack           用于确定物品注册 id 的基础堆叠，不能为空
     * @param observedVariants    观测到的原始标签列表，允许为 {@code null} 或包含 {@code null} 元素
     * @param maxVariantsPerItem  每个物品最多保留的变体数，必须大于等于 1
     * @return 按物品键排序的不可变列表
     * @throws IllegalArgumentException 当 {@code maxVariantsPerItem < 1}、基础堆叠为空或物品未注册
     */
    public List<ItemKey> scan(ItemStack baseStack, List<CompoundTag> observedVariants, int maxVariantsPerItem) {
        Objects.requireNonNull(baseStack, "baseStack");
        if (baseStack.isEmpty()) {
            throw new IllegalArgumentException("baseStack must not be empty");
        }
        if (maxVariantsPerItem < 1) {
            throw new IllegalArgumentException("maxVariantsPerItem must be >= 1, got " + maxVariantsPerItem);
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(baseStack.getItem());
        if (itemId == null) {
            throw new IllegalArgumentException("baseStack item is not registered: " + baseStack.getItem());
        }
        if (observedVariants == null || observedVariants.isEmpty()) {
            return List.of();
        }

        int limit = Math.min(maxVariantsPerItem, HARD_VARIANT_CEILING);
        TreeSet<ItemKey> unique = new TreeSet<>();
        for (CompoundTag observed : observedVariants) {
            if (unique.size() >= limit) {
                break;
            }
            String normalized = VariantNormalizer.normalize(observed);
            if (normalized != null) {
                unique.add(ItemKey.of(itemId, normalized));
            }
        }
        return List.copyOf(unique);
    }
}
