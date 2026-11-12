package com.itzplana.sudoersemc.pricing;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.world.item.Rarity;

/**
 * 基于物品稀有度的兜底定价。
 *
 * <p>稀有度是物品自带的属性，适合为“没有标签、没有配方、也没有外部基线”的物品提供一个
 * 保守的兜底价格。最终价格按 {@code 基准值 * 倍率} 计算，使用整数运算向下取整。</p>
 *
 * <p>这是本包中唯一依赖 Minecraft 类型的定价器，因为 {@link Rarity} 是原版枚举；
 * 其余逻辑仍然保持纯计算。</p>
 */
public final class RarityPricing {

    private final Map<Rarity, Long> baseValues;
    private final Map<Rarity, Double> multipliers;
    private final long fallbackValue;

    /**
     * @param baseValues    各稀有度的基准值，必须为非负数
     * @param multipliers   各稀有度的倍率，必须为有限且大于等于 0 的数值；缺失时按 {@code 1.0} 处理
     * @param fallbackValue 稀有度缺失或为 {@code null} 时使用的基准值，必须为非负数
     */
    public RarityPricing(Map<Rarity, Long> baseValues, Map<Rarity, Double> multipliers, long fallbackValue) {
        Objects.requireNonNull(baseValues, "baseValues");
        Objects.requireNonNull(multipliers, "multipliers");
        if (fallbackValue < 0L) {
            throw new IllegalArgumentException("fallbackValue must be >= 0, got " + fallbackValue);
        }

        EnumMap<Rarity, Long> copiedBaseValues = new EnumMap<>(Rarity.class);
        for (Map.Entry<Rarity, Long> entry : baseValues.entrySet()) {
            Rarity rarity = Objects.requireNonNull(entry.getKey(), "baseValues key");
            Long value = Objects.requireNonNull(entry.getValue(), "baseValues[" + rarity + "]");
            if (value < 0L) {
                throw new IllegalArgumentException("baseValues[" + rarity + "] must be >= 0, got " + value);
            }
            copiedBaseValues.put(rarity, value);
        }
        this.baseValues = Map.copyOf(copiedBaseValues);

        EnumMap<Rarity, Double> copiedMultipliers = new EnumMap<>(Rarity.class);
        for (Map.Entry<Rarity, Double> entry : multipliers.entrySet()) {
            Rarity rarity = Objects.requireNonNull(entry.getKey(), "multipliers key");
            Double multiplier = Objects.requireNonNull(entry.getValue(), "multipliers[" + rarity + "]");
            if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 0.0D) {
                throw new IllegalArgumentException(
                        "multipliers[" + rarity + "] must be finite and >= 0, got " + multiplier);
            }
            copiedMultipliers.put(rarity, multiplier);
        }
        this.multipliers = Map.copyOf(copiedMultipliers);
        this.fallbackValue = fallbackValue;
    }

    /**
     * 计算稀有度对应的价格。
     *
     * <p>结果为 {@code floor(base * multiplier)}，永远不会是负数。稀有度缺失或为 {@code null}
     * 时使用构造时提供的 {@code fallbackValue}，倍率按 {@code 1.0} 处理。结果超出 {@code long}
     * 范围时按 Java 窄化规则饱和到 {@code Long.MAX_VALUE}，不会回绕成负数；后续安全层仍会按
     * 溢出策略处置超过阈值的价格。</p>
     *
     * @param rarity 物品稀有度，允许为 {@code null}
     * @return 非负价格
     */
    public long price(Rarity rarity) {
        long base = rarity == null ? fallbackValue : baseValues.getOrDefault(rarity, fallbackValue);
        double multiplier = rarity == null ? 1.0D : multipliers.getOrDefault(rarity, 1.0D);
        if (base <= 0L || multiplier <= 0.0D) {
            return 0L;
        }
        double scaled = base * multiplier;
        return Math.max(0L, (long) Math.floor(scaled));
    }

    /**
     * 保守的默认配置：COMMON 16、UNCOMMON 64、RARE 256、EPIC 2048，所有倍率均为 {@code 1.0}。
     *
     * @return 默认稀有度定价器
     */
    public static RarityPricing defaults() {
        EnumMap<Rarity, Long> baseValues = new EnumMap<>(Rarity.class);
        baseValues.put(Rarity.COMMON, 16L);
        baseValues.put(Rarity.UNCOMMON, 64L);
        baseValues.put(Rarity.RARE, 256L);
        baseValues.put(Rarity.EPIC, 2048L);

        EnumMap<Rarity, Double> multipliers = new EnumMap<>(Rarity.class);
        for (Rarity rarity : Rarity.values()) {
            multipliers.put(rarity, 1.0D);
        }
        return new RarityPricing(baseValues, multipliers, 16L);
    }

    /**
     * @return 不可变的稀有度基准值视图
     */
    public Map<Rarity, Long> baseValues() {
        return baseValues;
    }

    /**
     * @return 不可变的稀有度倍率视图
     */
    public Map<Rarity, Double> multipliers() {
        return multipliers;
    }

    /**
     * @return 稀有度缺失时使用的基准值
     */
    public long fallbackValue() {
        return fallbackValue;
    }
}
