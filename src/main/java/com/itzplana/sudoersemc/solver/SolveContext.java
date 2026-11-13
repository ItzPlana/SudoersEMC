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
 * <p>状态分为三层：{@code knownValues} 是运行开始前就存在的 EMC 快照，整个运行期间只读；
 * {@code provisionalValues} 是为自有叶子条目预置的兜底定价，只参与输入查找、不会阻止配方覆盖；
 * {@code generatedValues} 是本次运行产出的值。查找时生成值优先于预置值，预置值优先于已有值，
 * 但在发布阶段求解器会先检查 {@link #hasSeedValue(ItemKey)}，因此已有 EMC 永远不会被覆盖。</p>
 *
 * <p>该类不做线程安全承诺；求解在单个线程内完成。物品映射使用 {@link TreeMap}，保证诊断输出顺序
 * 只取决于物品身份，而不依赖哈希表迭代顺序。</p>
 */
public final class SolveContext implements EmcLookup {

    private final Map<ItemKey, Long> knownValues;
    private final NavigableMap<ItemKey, Long> provisionalValues = new TreeMap<>();
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
     * 使用已有 EMC 快照与“本次运行开始时已由本模组拥有”的生成值创建上下文。
     *
     * <p>owned 值放入 {@code generatedValues} 而不是 {@code knownValues}：它们仍是本次求解的
     * 生成值，求解器可以在新配方成本更低时替换它们，也不会被 {@link #hasSeedValue(ItemKey)}
     * 当成永远胜出的种子。旧值到新值的降价/溢出保护由调用方在写入前执行。</p>
     *
     * @param knownValues             运行开始前由外部来源提供的 EMC；允许为空
     * @param initialGeneratedValues  运行开始前由本模组拥有、允许本次更新的生成值；允许为空
     */
    public SolveContext(Map<ItemKey, Long> knownValues,
                        Map<ItemKey, Long> initialGeneratedValues) {
        this(knownValues);
        Objects.requireNonNull(initialGeneratedValues, "initialGeneratedValues");
        for (Map.Entry<ItemKey, Long> entry : initialGeneratedValues.entrySet()) {
            ItemKey item = Objects.requireNonNull(
                    entry.getKey(), "initialGeneratedValues 的键不能为 null");
            Long value = Objects.requireNonNull(
                    entry.getValue(), "initialGeneratedValues 的值不能为 null");
            if (value > 0L) {
                generatedValues.put(item, value);
            }
        }
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
        Long provisional = provisionalValues.get(item);
        if (provisional != null) {
            return provisional;
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
     * 预置一个仅供输入查找使用的兜底值。
     *
     * <p>预置值与 {@code knownValues} 的区别：它不会让 {@link #hasSeedValue(ItemKey)} 返回
     * {@code true}，因此求解器仍可为同一物品发布配方值并覆盖预置值；它也不会出现在
     * {@link #generatedValues()} 中，最终候选仍由调用方按兜底定价链生成。该能力用于把自有
     * 叶子条目的新鲜兜底价传给同一轮的多级配方链。</p>
     *
     * @param item  物品身份，不能为 {@code null}
     * @param value 兜底值，必须 {@code > 0}
     */
    public void putProvisional(ItemKey item, long value) {
        Objects.requireNonNull(item, "item");
        if (value <= 0L) {
            throw new IllegalArgumentException("预置值必须 > 0，实际为 " + value);
        }
        provisionalValues.put(item, value);
    }

    /**
     * @return 预置值的只读视图，按 {@link ItemKey} 升序排列
     */
    public Map<ItemKey, Long> provisionalValues() {
        return Collections.unmodifiableMap(provisionalValues);
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
