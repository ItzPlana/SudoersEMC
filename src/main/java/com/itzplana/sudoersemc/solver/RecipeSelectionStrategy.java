package com.itzplana.sudoersemc.solver;

import org.jetbrains.annotations.Nullable;

/**
 * 同一物品存在多条可行配方时的取舍策略。
 *
 * <p><strong>经济安全：</strong>所有策略都以成本为第一排序键，只改变“成本相同时”的
 * 选择顺序。因此任何策略都不会让求解器接受高于最低可行成本的配方。</p>
 *
 * <ul>
 *   <li>{@link #LOWEST_COST}：成本相同时按配方 id 字典序，行为与 Phase 22 及更早版本一致，
 *       也是默认值；</li>
 *   <li>{@link #PREFER_SIMPLE}：成本相同时优先输入槽更少、输出更少、容器返还更少且非催化剂的
 *       配方，最后仍以配方 id 兜底，便于管理员偏好简单配方；</li>
 *   <li>{@link #STABLE_ID}：成本相同时显式按配方 id 字典序选择，保证跨运行与跨版本的稳定性。</li>
 * </ul>
 */
public enum RecipeSelectionStrategy {

    /** 最低成本，成本相同时按配方 id 字典序（默认）。 */
    LOWEST_COST("lowest_cost"),

    /** 最低成本，成本相同时优先更简单的配方，最后按配方 id 字典序。 */
    PREFER_SIMPLE("prefer_simple"),

    /** 最低成本，成本相同时显式按配方 id 字典序。 */
    STABLE_ID("stable_id");

    /** 默认策略，保持 Phase 22 的行为。 */
    public static final RecipeSelectionStrategy DEFAULT = LOWEST_COST;

    private final String configValue;

    RecipeSelectionStrategy(String configValue) {
        this.configValue = configValue;
    }

    /**
     * @return 配置文件使用的稳定小写名称
     */
    public String configValue() {
        return configValue;
    }

    /**
     * 解析配置值。比较时忽略首尾空白与大小写。
     *
     * @param rawValue 原始配置值，可为 {@code null}
     * @return 解析后的策略；无法识别时返回 {@code null}，由调用方决定回退与告警
     */
    @Nullable
    public static RecipeSelectionStrategy parse(@Nullable String rawValue) {
        if (rawValue == null) {
            return null;
        }
        String normalized = rawValue.trim().toLowerCase(java.util.Locale.ROOT);
        for (RecipeSelectionStrategy strategy : values()) {
            if (strategy.configValue.equals(normalized)) {
                return strategy;
            }
        }
        return null;
    }

    /**
     * @param rawValue 原始配置值
     * @return 是否为可识别的策略名称（忽略大小写与首尾空白）
     */
    public static boolean isValid(@Nullable String rawValue) {
        return parse(rawValue) != null;
    }
}
