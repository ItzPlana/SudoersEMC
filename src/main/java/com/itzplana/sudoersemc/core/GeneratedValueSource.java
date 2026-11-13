package com.itzplana.sudoersemc.core;

import java.util.List;
import java.util.Map;

/**
 * Read-only view of the EMC values SudoersEMC currently owns.
 *
 * <p>Implemented by the persistence layer and consumed by the ProjectE integration so the two
 * layers stay independent of each other's concrete classes.</p>
 */
@FunctionalInterface
public interface GeneratedValueSource {

    /**
     * @return an immutable snapshot of owned values keyed by item identity
     */
    Map<ItemKey, Long> snapshotOwnedValues();

    /**
     * 返回每个自有变体条目观测到的原始 SNBT，供 ProjectE 精确 NBT 注册使用。
     *
     * <p>{@link ItemKey#variantHash()} 是规范化后的经济身份，可能已剥离展示字段；
     * ProjectE 按完整 NBT 精确匹配，因此注册侧需要原始值。默认实现返回空映射，
     * 仅持久化存储需要覆盖。</p>
     *
     * @return 变体身份到原始 SNBT 列表的不可变快照
     */
    default Map<ItemKey, List<String>> snapshotOwnedVariantNbts() {
        return Map.of();
    }
}
