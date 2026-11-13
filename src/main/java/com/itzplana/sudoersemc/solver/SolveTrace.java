package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;

/**
 * Pricing trace of one item within a solve run, used to explain "how this value was finally derived".
 *
 * <p>A trace only describes a value the solver actually published; it is never generated for items that
 * already had EMC. {@code pass} is the pass that last wrote the value; {@code alternativeCosts} are the
 * distinct costs other valid recipes gave in the same pass, deduplicated and sorted ascending, excluding
 * the finally adopted lowest value, so one can directly see "why another recipe was not chosen".</p>
 *
 * @param item             the item being priced
 * @param value            the finally adopted unit EMC
 * @param source           the value source; solver-generated values are always {@link PriceSource#RECIPE}
 * @param recipeId         the recipe corresponding to the adopted value
 * @param pass             the pass that last wrote the value, starting at 1
 * @param alternativeCosts distinct costs of other valid recipes in the same pass, ascending and excluding the adopted value
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
