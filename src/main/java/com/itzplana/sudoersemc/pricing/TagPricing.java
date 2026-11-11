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
     * <p>这些数值只是让未定价物品落入合理量级的锚点，并不代表同一类别内所有材料价值相同；
     * 精确到材料的覆盖值后续由配置提供。默认不包含任何按物品覆盖。</p>
     *
     * @return 使用默认类别基准值的标签定价器
     */
    public static TagPricing defaults() {
        EnumMap<MaterialCategory, Long> baseValues = new EnumMap<>(MaterialCategory.class);
        baseValues.put(MaterialCategory.INGOT, 256L);
        baseValues.put(MaterialCategory.NUGGET, 28L);
        baseValues.put(MaterialCategory.STORAGE_BLOCK, 2304L);
        baseValues.put(MaterialCategory.GEM, 512L);
        baseValues.put(MaterialCategory.DUST, 64L);
        baseValues.put(MaterialCategory.RAW_MATERIAL, 128L);
        baseValues.put(MaterialCategory.PLATE, 256L);
        baseValues.put(MaterialCategory.ROD, 128L);
        baseValues.put(MaterialCategory.ORE, 128L);
        return new TagPricing(baseValues, Map.of());
    }

    /**
     * 计算物品的标签基准价格。
     *
     * <p>先检查按物品配置的覆盖值；没有覆盖值时，按传入顺序查找第一个“能被识别且配置了基准值”
     * 的类别。无法识别的类别（{@link MaterialCategory#UNKNOWN}）会被跳过。列表为空或为
     * {@code null} 时返回 {@link Optional#empty()}。本方法不会抛出异常。</p>
     *
     * @param item     待定价物品，为 {@code null} 时返回空
     * @param tagPaths 物品拥有的标签路径，按优先级排列
     * @return 命中的价格，未命中时为空
     */
    public Optional<Long> price(ItemKey item, List<String> tagPaths) {
        if (item == null || tagPaths == null || tagPaths.isEmpty()) {
            return Optional.empty();
        }

        Long override = overrides.get(item);
        if (override != null) {
            return Optional.of(override);
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
