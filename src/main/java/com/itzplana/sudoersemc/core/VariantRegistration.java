package com.itzplana.sudoersemc.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * 构造 ProjectE 精确 NBT 注册所需的 SNBT 列表。
 *
 * <p>{@link ItemKey#variantHash()} 只保存规范化后的经济身份：展示字段、可损坏物品的
 * {@code Damage} 等会被剥离。ProjectE 的 {@code EMCMappingHandler} 以完整
 * {@code ItemInfo(item, nbt)} 做精确匹配，因此注册侧必须同时保留观测到的原始 SNBT；
 * 规范 SNBT 只用于去重、归属与持久化身份。</p>
 *
 * <p>同一经济变体可能对应多个原始 SNBT（例如仅自定义名称不同）。这里对原始值做稳定排序、
 * 去重与数量封顶，避免异常数据把持久化文件撑大。</p>
 */
public final class VariantRegistration {

    /**
     * 单个经济变体最多保留的原始 SNBT 数量。
     *
     * <p>仅影响 ProjectE 精确 NBT 注册的覆盖面；经济身份本身仍由规范 hash 去重。</p>
     */
    public static final int MAX_OBSERVED_NBT_PER_VARIANT = 64;

    private VariantRegistration() {
    }

    /**
     * 清洗观测到的原始 SNBT：过滤空值、排序、去重并截断到
     * {@link #MAX_OBSERVED_NBT_PER_VARIANT}。
     *
     * @param observed 原始 SNBT 列表，允许为 {@code null}
     * @return 不可变的稳定列表
     */
    public static List<String> sanitizeObserved(List<String> observed) {
        if (observed == null || observed.isEmpty()) {
            return List.of();
        }
        TreeSet<String> unique = new TreeSet<>();
        for (String snbt : observed) {
            if (snbt == null || snbt.isBlank()) {
                continue;
            }
            unique.add(snbt);
            if (unique.size() > MAX_OBSERVED_NBT_PER_VARIANT) {
                unique.pollLast();
            }
        }
        return List.copyOf(unique);
    }

    /**
     * 构造需要注册到 ProjectE 的 SNBT 列表。
     *
     * <p>返回顺序固定为“规范 SNBT，然后按字典序排列的原始 SNBT”，并做精确字符串去重。
     * 基础物品没有 NBT，返回空列表，由调用方按无 NBT 栈注册。</p>
     *
     * @param key               经济身份
     * @param observedVariantNbts 观测到的原始 SNBT，允许为 {@code null}
     * @return 不可变的 SNBT 列表；基础物品返回空列表
     */
    public static List<String> registrationNbts(ItemKey key, List<String> observedVariantNbts) {
        Objects.requireNonNull(key, "key");
        if (!key.hasVariant()) {
            return List.of();
        }
        LinkedHashSet<String> registrations = new LinkedHashSet<>();
        String canonical = key.variantHash();
        if (canonical != null && !canonical.isBlank()) {
            registrations.add(canonical);
        }
        registrations.addAll(sanitizeObserved(observedVariantNbts));
        return List.copyOf(registrations);
    }
}
