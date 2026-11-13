package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 一次扫描管线在采集阶段产出的不可变数据。
 *
 * <p>本类型只承载纯数据：物品快照、配方快照、已有 EMC 与扫描失败计数。它不持有任何注册表句柄，
 * 因此可以在服务器线程采集完成后安全地传递给后续的求解阶段，也便于单元测试直接构造。</p>
 *
 * <p>所有集合与映射在构造时都会做防御性复制。{@code existingValues} 使用按键升序的
 * {@link TreeMap} 复制，保证相同输入得到稳定的遍历顺序。记录中的三个失败计数必须非负，
 * 避免损坏的扫描结果被当成“数据缺失”静默接受。</p>
 *
 * @param items              本次扫描到的物品快照，保持扫描器返回的顺序
 * @param recipes            本次扫描到的可用配方快照，保持扫描器返回的顺序
 * @param existingValues     已有 EMC 的物品；值为扫描时观察到的旧值
 * @param unsupportedRecipes 当前构建无法评估的配方数量
 * @param invalidRecipes     缺少 id、输入或输出的配方数量
 * @param failedRecipes      在描述过程中抛出异常的配方数量
 * @param recipeEnumerationFailed 是否在枚举配方 id 时整体失败；为 {@code true} 时上面的
 *                                配方统计不可用，报告必须显示“未知”而不是伪造 0 或 1
 * @param failedRecipeDetails 失败配方的可读明细；报告层会再次做数量与长度截断
 */
public record PipelineSnapshot(
        List<ItemSnapshot> items,
        List<RecipeSnapshot> recipes,
        Map<ItemKey, Long> existingValues,
        int unsupportedRecipes,
        int invalidRecipes,
        int failedRecipes,
        boolean recipeEnumerationFailed,
        List<String> failedRecipeDetails
) {

    public PipelineSnapshot {
        items = List.copyOf(Objects.requireNonNull(items, "items"));
        recipes = List.copyOf(Objects.requireNonNull(recipes, "recipes"));
        Objects.requireNonNull(existingValues, "existingValues");
        existingValues = Collections.unmodifiableMap(new TreeMap<>(existingValues));
        requireNonNegative("unsupportedRecipes", unsupportedRecipes);
        requireNonNegative("invalidRecipes", invalidRecipes);
        requireNonNegative("failedRecipes", failedRecipes);
        List<String> details = new ArrayList<>();
        if (failedRecipeDetails != null) {
            for (String detail : failedRecipeDetails) {
                details.add(detail == null ? "" : detail);
            }
        }
        failedRecipeDetails = Collections.unmodifiableList(details);
    }

    /**
     * 兼容 Phase 22 早期调用点的构造器：不携带配方枚举失败标志。
     */
    public PipelineSnapshot(List<ItemSnapshot> items,
                            List<RecipeSnapshot> recipes,
                            Map<ItemKey, Long> existingValues,
                            int unsupportedRecipes,
                            int invalidRecipes,
                            int failedRecipes,
                            List<String> failedRecipeDetails) {
        this(items, recipes, existingValues, unsupportedRecipes, invalidRecipes, failedRecipes,
                false, failedRecipeDetails);
    }

    /**
     * 兼容 Phase 21 及更早调用点的构造器：不携带枚举失败标志与失败配方明细。
     */
    public PipelineSnapshot(List<ItemSnapshot> items,
                            List<RecipeSnapshot> recipes,
                            Map<ItemKey, Long> existingValues,
                            int unsupportedRecipes,
                            int invalidRecipes,
                            int failedRecipes) {
        this(items, recipes, existingValues, unsupportedRecipes, invalidRecipes, failedRecipes,
                false, List.of());
    }

    /**
     * @return 不含任何物品、配方与既有值的空快照
     */
    public static PipelineSnapshot empty() {
        return new PipelineSnapshot(List.of(), List.of(), Map.of(), 0, 0, 0, false, List.of());
    }

    /**
     * @return 物品快照数量
     */
    public int itemCount() {
        return items.size();
    }

    /**
     * @return 可用配方快照数量
     */
    public int recipeCount() {
        return recipes.size();
    }

    /**
     * 判断本次采集是否完全没有可处理内容。
     *
     * <p>只有物品与配方都为空时才返回 {@code true}；仅有既有 EMC 而没有物品快照仍视为空，
     * 因为没有物品身份就无法产生任何候选值。</p>
     *
     * @return 物品列表与配方列表是否同时为空
     */
    public boolean isEmpty() {
        return items.isEmpty() && recipes.isEmpty();
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " 必须 >= 0，实际为 " + value);
        }
    }
}
