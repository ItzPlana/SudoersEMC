package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 一次求解运行的可变工作状态，同时实现 {@link EmcLookup} 以便直接交给评估器。
 *
 * <p>状态分为两层：{@code knownValues} 是运行开始前就存在的 EMC 快照，整个运行期间只读；
 * {@code generatedValues} 是本次运行产出的值。查找时生成值优先于已有值，但在发布阶段求解器会
 * 先检查 {@link #hasSeedValue(ItemKey)}，因此已有 EMC 永远不会被覆盖。</p>
 *
 * <p>该类不做线程安全承诺；求解在单个线程内完成。物品映射使用 {@link TreeMap}，保证诊断输出顺序
 * 只取决于物品身份，而不依赖哈希表迭代顺序。</p>
 */
public final class SolveContext implements EmcLookup {

    private final Map<ItemKey, Long> knownValues;
    private final NavigableMap<ItemKey, Long> generatedValues = new TreeMap<>();
    private final NavigableMap<ItemKey, SolveTrace> traces = new TreeMap<>();
    private int currentPass = 1;

    /**
     * 使用已有 EMC 快照创建上下文。
     *
     * <p>只保留正值：EMC 为 0 或负数的条目等同于“没有值”，保留它们会错误地遮蔽后续配方计算。</p>
     *
     * @param knownValues 运行开始前已存在的 EMC；允许为空
     */
    public SolveContext(Map<ItemKey, Long> knownValues) {
        Objects.requireNonNull(knownValues, "knownValues");
        NavigableMap<ItemKey, Long> copy = new TreeMap<>();
        for (Map.Entry<ItemKey, Long> entry : knownValues.entrySet()) {
            ItemKey item = Objects.requireNonNull(entry.getKey(), "knownValues 的键不能为 null");
            Long value = Objects.requireNonNull(entry.getValue(), "knownValues 的值不能为 null");
            if (value > 0L) {
                copy.put(item, value);
            }
        }
        this.knownValues = Collections.unmodifiableMap(copy);
    }

    /**
     * @return 不含任何已有 EMC 的空上下文
     */
    public static SolveContext empty() {
        return new SolveContext(Map.of());
    }

    /**
     * 按“生成值优先、已有值兜底”的顺序查找 EMC。
     *
     * @return 正值；物品未知时返回 0
     */
    @Override
    public long emcOf(ItemKey item) {
        if (item == null) {
            return 0L;
        }
        Long generated = generatedValues.get(item);
        if (generated != null) {
            return generated;
        }
        return knownValues.getOrDefault(item, 0L);
    }

    /**
     * @return {@code true} 当物品已有正值（无论来自快照还是本次运行）
     */
    @Override
    public boolean isKnown(ItemKey item) {
        return emcOf(item) > 0L;
    }

    /**
     * @return {@code true} 当物品在运行开始时就已有 EMC；这类物品永远不会被求解器改写
     */
    public boolean hasSeedValue(ItemKey item) {
        return item != null && knownValues.containsKey(item);
    }

    /**
     * @return 本次运行已生成的值，尚未生成时返回 {@code null}
     */
    @Nullable
    public Long generatedValue(ItemKey item) {
        return item == null ? null : generatedValues.get(item);
    }

    /**
     * @return 运行开始时的只读 EMC 快照
     */
    public Map<ItemKey, Long> knownValues() {
        return knownValues;
    }

    /**
     * @return 本次运行已生成值的只读视图，按 {@link ItemKey} 升序排列
     */
    public Map<ItemKey, Long> generatedValues() {
        return Collections.unmodifiableMap(generatedValues);
    }

    /**
     * @return 已生成值的定价轨迹只读视图，按 {@link ItemKey} 升序排列
     */
    public Map<ItemKey, SolveTrace> traces() {
        return Collections.unmodifiableMap(traces);
    }

    /**
     * 开始新一轮迭代。求解器在每轮开始时调用，使后续 {@link #putGenerated} 记录的轮次正确。
     *
     * @param pass 轮次，必须 {@code >= 1}
     */
    public void enterPass(int pass) {
        if (pass < 1) {
            throw new IllegalArgumentException("轮次必须从 1 开始，实际为 " + pass);
        }
        this.currentPass = pass;
    }

    /**
     * @return 当前轮次
     */
    public int currentPass() {
        return currentPass;
    }

    /**
     * 发布一个生成值，并使用当前轮次、空备选成本记录轨迹。
     *
     * <p>求解器内部使用带轮次和备选成本的重载；这个便捷方法供调用方构造测试状态或诊断快照。</p>
     *
     * @param item     被定价的物品
     * @param value    单位 EMC，必须 {@code > 0}
     * @param recipeId 产生该值的配方
     * @param source   值来源
     */
    public void putGenerated(ItemKey item, long value, ResourceLocation recipeId, PriceSource source) {
        putGenerated(item, value, recipeId, source, currentPass, java.util.List.of());
    }

    /**
     * 发布一个生成值并写入完整轨迹。
     *
     * <p>只接受正值：0 或负值代表“无法支付”，如果写入会遮蔽已有 EMC，因此直接拒绝。是否允许覆盖
     * 更低的旧生成值由求解器根据 {@link SolveSettings#allowValueIncrease()} 决定，本方法只负责存储。</p>
     *
     * @param item             被定价的物品
     * @param value            单位 EMC，必须 {@code > 0}
     * @param recipeId         产生该值的配方
     * @param source           值来源
     * @param pass             写入轮次，必须 {@code >= 1}
     * @param alternativeCosts 同一轮中其它有效成本，可为空
     */
    public void putGenerated(ItemKey item, long value, ResourceLocation recipeId, PriceSource source,
                             int pass, java.util.List<Long> alternativeCosts) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(alternativeCosts, "alternativeCosts");
        if (value <= 0L) {
            throw new IllegalArgumentException("生成值必须 > 0，实际为 " + value);
        }
        if (pass < 1) {
            throw new IllegalArgumentException("轮次必须从 1 开始，实际为 " + pass);
        }
        generatedValues.put(item, value);
        traces.put(item, new SolveTrace(item, value, source, recipeId, pass, alternativeCosts));
    }
}
