package com.itzplana.sudoersemc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * SudoersEMC 生成或采纳的一条 EMC 值。
 *
 * @param item             物品身份，包含所有与经济性相关的变体
 * @param value            以 ProjectE 单位表示的 EMC 值，始终 {@code >= 0}
 * @param source           该值的产生方式
 * @param recipeId         产生该值的配方，非配方来源为 {@code null}
 * @param owned            SudoersEMC 是否拥有该条目，可在后续扫描中更新或删除
 * @param timestamp        条目最后生成时的 epoch 毫秒
 * @param alternativeCosts 同一轮求解中其它有效配方的不同成本；不可变，元素始终 {@code >= 0}
 */
public record GeneratedEmcEntry(
        ItemKey item,
        long value,
        PriceSource source,
        @Nullable ResourceLocation recipeId,
        boolean owned,
        long timestamp,
        List<Long> alternativeCosts
) {

    public GeneratedEmcEntry {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(source, "source");
        if (value < 0) {
            throw new IllegalArgumentException("EMC value must be >= 0, got " + value);
        }

        List<Long> copied = new ArrayList<>();
        if (alternativeCosts != null) {
            for (Long cost : alternativeCosts) {
                if (cost == null) {
                    throw new IllegalArgumentException("alternative cost must not be null");
                }
                if (cost < 0L) {
                    throw new IllegalArgumentException(
                            "alternative cost must be >= 0, got " + cost);
                }
                copied.add(cost);
            }
        }
        alternativeCosts = List.copyOf(copied);
    }

    /**
     * 兼容旧调用点的构造器：不携带备选成本。
     *
     * @param item     物品身份
     * @param value    EMC 值
     * @param source   数值来源
     * @param recipeId 配方 id，可为 {@code null}
     * @param owned    是否归 SudoersEMC 所有
     * @param timestamp 生成时间
     */
    public GeneratedEmcEntry(ItemKey item, long value, PriceSource source,
                             @Nullable ResourceLocation recipeId, boolean owned, long timestamp) {
        this(item, value, source, recipeId, owned, timestamp, List.of());
    }

    /**
     * @param newValue     新的 EMC 值
     * @param newTimestamp 新的生成时间
     * @return 保留来源、归属与备选成本的副本
     */
    public GeneratedEmcEntry withValue(long newValue, long newTimestamp) {
        return new GeneratedEmcEntry(
                item, newValue, source, recipeId, owned, newTimestamp, alternativeCosts);
    }

    /**
     * @param newOwned 新的归属状态
     * @return 保留数值与备选成本的副本
     */
    public GeneratedEmcEntry withOwned(boolean newOwned) {
        return new GeneratedEmcEntry(
                item, value, source, recipeId, newOwned, timestamp, alternativeCosts);
    }

    /**
     * @param newAlternativeCosts 新的备选成本列表，可为 {@code null}
     * @return 除备选成本外其余字段相同的副本
     */
    public GeneratedEmcEntry withAlternativeCosts(List<Long> newAlternativeCosts) {
        return new GeneratedEmcEntry(
                item, value, source, recipeId, owned, timestamp, newAlternativeCosts);
    }
}
