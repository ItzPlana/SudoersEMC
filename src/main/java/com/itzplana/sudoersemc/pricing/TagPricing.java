package com.itzplana.sudoersemc.pricing;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于材料标签的兜底定价。
 *
 * <p>标签只描述材料关系，并不代表两种物品价值相同：{@code forge:ingots/iron} 与
 * {@code forge:ingots/copper} 都映射到 {@link MaterialCategory#INGOT}，但真实价值可能
 * 相差数倍。因此本类为每个 {@link MaterialCategory} 配置一个保守的基准值，再允许按物品
 * 单独覆盖。</p>
 *
 * <p>本类只做纯计算，不读取 Minecraft 的标签注册表；标签路径由调用方解析后传入。</p>
 */
public final class TagPricing {

    private final Map<MaterialCategory, Long> baseValues;
    private final Map<ItemKey, Long> overrides;

    /**
     * @param baseValues 各材料类别的基准值，必须为非负数，允许 {@link MaterialCategory#UNKNOWN}
     *                   作为键存在，但查询时会被忽略
     * @param overrides  按物品覆盖的固定值，优先级高于类别基准值，必须为非负数
     */
    public TagPricing(Map<MaterialCategory, Long> baseValues, Map<ItemKey, Long> overrides) {
        Objects.requireNonNull(baseValues, "baseValues");
        Objects.requireNonNull(overrides, "overrides");

        EnumMap<MaterialCategory, Long> copiedBaseValues = new EnumMap<>(MaterialCategory.class);
        for (Map.Entry<MaterialCategory, Long> entry : baseValues.entrySet()) {
            MaterialCategory category = Objects.requireNonNull(entry.getKey(), "baseValues key");
            long value = requireNonNegative(entry.getValue(), "baseValues[" + category + "]");
            copiedBaseValues.put(category, value);
        }
        this.baseValues = Map.copyOf(copiedBaseValues);

        Map<ItemKey, Long> copiedOverrides = new LinkedHashMap<>();
        for (Map.Entry<ItemKey, Long> entry : overrides.entrySet()) {
            ItemKey key = Objects.requireNonNull(entry.getKey(), "overrides key");
            long value = requireNonNegative(entry.getValue(), "overrides[" + key + "]");
            copiedOverrides.put(key, value);
        }
        this.overrides = Map.copyOf(copiedOverrides);
    }

    /**
     * 保守的材料类别基准值。
     *
     * <p>默认值取低阶锭 {@code 64} 作为保守下限，并按 9 倍关系对齐粒与储存块：
     * {@code NUGGET = INGOT / 9}、{@code STORAGE_BLOCK = INGOT * 9}，避免在默认锚点内部
     * 产生“9 锭 → 1 块”的套利。其余类别同样按锭的分数或倍数取值，而不是直接采用铁系
     * 真实值 {@code 256/2304}。精确到材料的覆盖值后续由配置提供；默认不包含任何按物品覆盖。</p>
     *
     * <p><strong>风险提示：</strong>{@link MaterialCategory} 是跨材料共享的。廉价可再生的
     * 模组材料（例如铜、锡）仍可能相对 ProjectE 的真实值被高估；当 ProjectE 已为原料定价、
     * 但未为对应块定价时，仍存在“低价原料 → 高价块”的套利可能。生产环境启用前应通过按物品
     * 覆盖值或 ProjectE 手动值校正相关材料；本类目前没有按标签/材料的配置入口。</p>
     *
     * @return 使用默认类别基准值的标签定价器
     */
    public static TagPricing defaults() {
        EnumMap<MaterialCategory, Long> baseValues = new EnumMap<>(MaterialCategory.class);
        long ingot = 64L;
        baseValues.put(MaterialCategory.INGOT, ingot);
        baseValues.put(MaterialCategory.NUGGET, ingot / 9L);
        baseValues.put(MaterialCategory.STORAGE_BLOCK, ingot * 9L);
        baseValues.put(MaterialCategory.GEM, ingot * 2L);
        baseValues.put(MaterialCategory.DUST, ingot / 4L);
        baseValues.put(MaterialCategory.RAW_MATERIAL, ingot / 2L);
        baseValues.put(MaterialCategory.PLATE, ingot);
        baseValues.put(MaterialCategory.ROD, ingot / 2L);
        baseValues.put(MaterialCategory.ORE, ingot / 2L);
        return new TagPricing(baseValues, Map.of());
    }

    /**
     * 计算物品的标签基准价格。
     *
     * <p>先检查按物品配置的覆盖值；覆盖值与标签列表是否为空无关。没有覆盖值时，按传入顺序
     * 查找第一个“能被识别且配置了基准值”的类别。无法识别的类别（{@link MaterialCategory#UNKNOWN}）
     * 会被跳过。标签列表为空或为 {@code null} 时返回 {@link Optional#empty()}。本方法不会抛出异常。</p>
     *
     * @param item     待定价物品，为 {@code null} 时返回空
     * @param tagPaths 物品拥有的标签路径，按优先级排列
     * @return 命中的价格，未命中时为空
     */
    public Optional<Long> price(ItemKey item, List<String> tagPaths) {
        if (item == null) {
            return Optional.empty();
        }

        Long override = overrides.get(item);
        if (override != null) {
            return Optional.of(override);
        }

        if (tagPaths == null || tagPaths.isEmpty()) {
            return Optional.empty();
        }

        for (String tagPath : tagPaths) {
            if (tagPath == null || tagPath.isBlank()) {
                continue;
            }
            MaterialCategory category = MaterialCategory.fromTagPath(tagPath);
            if (category == MaterialCategory.UNKNOWN) {
                continue;
            }
            Long baseValue = baseValues.get(category);
            if (baseValue != null) {
                return Optional.of(baseValue);
            }
        }
        return Optional.empty();
    }

    /**
     * @return 不可变的类别基准值视图
     */
    public Map<MaterialCategory, Long> baseValues() {
        return baseValues;
    }

    /**
     * @return 不可变的按物品覆盖值视图
     */
    public Map<ItemKey, Long> overrides() {
        return overrides;
    }

    private static long requireNonNegative(Long value, String name) {
        Objects.requireNonNull(value, name);
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be >= 0, got " + value);
        }
        return value;
    }
}
