package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;

/**
 * 某个物品在一次求解运行中的定价轨迹，用于解释“这个值最终是怎么来的”。
 *
 * <p>轨迹只描述求解器实际发布的值，不会为已有 EMC 的物品生成。{@code pass} 是最后一次写入该值的
 * 轮次；{@code alternativeCosts} 是同一轮中其它有效配方给出的不同成本，已去重并按升序排列，
 * 不包含最终采用的最低值，因此可以直接看出“为什么没有选择另一条配方”。</p>
 *
 * @param item             被定价的物品
 * @param value            最终采用的单位 EMC
 * @param source           值来源；求解器生成的值始终为 {@link PriceSource#RECIPE}
 * @param recipeId         最终采用值对应的配方
 * @param pass             最后一次写入该值的轮次，从 1 开始
 * @param alternativeCosts 同一轮中其它有效配方的不同成本，升序且不含最终采用值
 */
public record SolveTrace(
        ItemKey item,
        long value,
        PriceSource source,
        ResourceLocation recipeId,
        int pass,
        List<Long> alternativeCosts
) {

    public SolveTrace {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(alternativeCosts, "alternativeCosts");
        if (value < 0L) {
            throw new IllegalArgumentException("轨迹中的 EMC 不能为负数，实际为 " + value);
        }
        if (pass < 1) {
            throw new IllegalArgumentException("轨迹轮次必须从 1 开始，实际为 " + pass);
        }
        TreeSet<Long> normalized = new TreeSet<>();
        for (Long cost : alternativeCosts) {
            Objects.requireNonNull(cost, "alternativeCosts 不能包含 null 元素");
            if (cost < 0L) {
                throw new IllegalArgumentException("备选成本不能为负数，实际为 " + cost);
            }
            normalized.add(cost);
        }
        alternativeCosts = List.copyOf(normalized);
    }
}
