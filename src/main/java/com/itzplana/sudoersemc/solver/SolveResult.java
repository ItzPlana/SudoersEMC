package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;

/**
 * 一次配方求解的最终结果。
 *
 * <p>所有集合都是防御性副本并保持确定性顺序：物品按 {@link ItemKey} 升序，配方按 id 的字符串形式
 * 升序。{@code values} 只包含本次运行新生成的值，不包含运行开始前已有的 EMC。</p>
 *
 * @param values             本次运行生成的值，已有 EMC 不会出现在这里
 * @param traces             每个生成值的定价轨迹
 * @param unresolvedRecipes  结束时仍未获得可用值的配方 id，按 id 升序
 * @param unresolvedItems    结束时仍没有 EMC 且会阻塞配方的物品，按物品升序
 * @param passesUsed         实际执行的轮数；空配方列表时为 0
 * @param converged          是否在达到轮数上限前观察到“没有任何值变化”的完整一轮
 * @param elapsedMillis      求解耗时，单位毫秒
 * @param recipesEvaluated   处理配方次数（跨轮次累计，包含缓存命中与失败调用）
 * @param cyclicItems        结束时仍没有 EMC 且位于循环依赖中的物品，按物品升序；没有环时为空
 */
public record SolveResult(
        Map<ItemKey, Long> values,
        Map<ItemKey, SolveTrace> traces,
        List<ResourceLocation> unresolvedRecipes,
        Set<ItemKey> unresolvedItems,
        int passesUsed,
        boolean converged,
        long elapsedMillis,
        int recipesEvaluated,
        Set<ItemKey> cyclicItems
) {

    public SolveResult {
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(traces, "traces");
        Objects.requireNonNull(unresolvedRecipes, "unresolvedRecipes");
        Objects.requireNonNull(unresolvedItems, "unresolvedItems");
        Objects.requireNonNull(cyclicItems, "cyclicItems");
        if (passesUsed < 0) {
            throw new IllegalArgumentException("passesUsed 不能为负数，实际为 " + passesUsed);
        }
        if (elapsedMillis < 0L) {
            throw new IllegalArgumentException("elapsedMillis 不能为负数，实际为 " + elapsedMillis);
        }
        if (recipesEvaluated < 0) {
            throw new IllegalArgumentException("recipesEvaluated 不能为负数，实际为 " + recipesEvaluated);
        }
        values = Collections.unmodifiableMap(new TreeMap<>(values));
        traces = Collections.unmodifiableMap(new TreeMap<>(traces));
        unresolvedRecipes = List.copyOf(unresolvedRecipes);
        unresolvedItems = Collections.unmodifiableSet(new TreeSet<>(unresolvedItems));
        cyclicItems = Collections.unmodifiableSet(new TreeSet<>(cyclicItems));
    }

    /**
     * 兼容没有循环诊断的调用方：等价于 {@code cyclicItems} 为空集的完整构造。
     */
    public SolveResult(Map<ItemKey, Long> values,
                       Map<ItemKey, SolveTrace> traces,
                       List<ResourceLocation> unresolvedRecipes,
                       Set<ItemKey> unresolvedItems,
                       int passesUsed,
                       boolean converged,
                       long elapsedMillis,
                       int recipesEvaluated) {
        this(values, traces, unresolvedRecipes, unresolvedItems, passesUsed, converged,
                elapsedMillis, recipesEvaluated, Set.of());
    }

    /**
     * @return 本次运行新定价的物品数量
     */
    public int solvedCount() {
        return values.size();
    }

    /**
     * @return 结束时仍未解析的配方数量
     */
    public int unresolvedRecipeCount() {
        return unresolvedRecipes.size();
    }
}
