package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Rarity;

/**
 * Immutable description of one registered item as seen by the scanner.
 *
 * <p>标签与稀有度在采集阶段一次性固化，后续求解与兜底定价只读取不可变快照，不再访问游戏注册表。</p>
 *
 * @param key            item identity, including a variant hash when the scanner priced a variant
 * @param registryId     registry id of the item
 * @param namespace      registry namespace, kept separately to make ignore rules cheap
 * @param path           registry path
 * @param hasExistingEmc whether the EMC backend already assigns a value
 * @param existingValue  the existing value, or {@code 0} when none
 * @param tagPaths       物品携带的标签路径（已过滤 {@code null} 与空白），不可变且不为 {@code null}
 * @param rarity         物品稀有度；{@code null} 会归一为 {@link Rarity#COMMON}
 */
public record ItemSnapshot(
        ItemKey key,
        ResourceLocation registryId,
        String namespace,
        String path,
        boolean hasExistingEmc,
        long existingValue,
        List<String> tagPaths,
        Rarity rarity
) {

    public ItemSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(registryId, "registryId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (existingValue < 0) {
            throw new IllegalArgumentException("existingValue must be >= 0, got " + existingValue);
        }
        tagPaths = copyTagPaths(tagPaths);
        rarity = rarity == null ? Rarity.COMMON : rarity;
    }

    /**
     * 向后兼容构造函数：没有标签信息时按空列表与 {@link Rarity#COMMON} 处理。
     */
    public ItemSnapshot(ItemKey key, ResourceLocation registryId, String namespace, String path,
                        boolean hasExistingEmc, long existingValue) {
        this(key, registryId, namespace, path, hasExistingEmc, existingValue,
                List.of(), Rarity.COMMON);
    }

    /**
     * 创建不带标签信息的快照，稀有度按 {@link Rarity#COMMON} 处理。
     */
    public static ItemSnapshot of(ItemKey key, boolean hasExistingEmc, long existingValue) {
        return of(key, hasExistingEmc, existingValue, List.of(), Rarity.COMMON);
    }

    /**
     * 创建带标签与稀有度信息的快照。
     */
    public static ItemSnapshot of(ItemKey key, boolean hasExistingEmc, long existingValue,
                                  List<String> tagPaths, Rarity rarity) {
        ResourceLocation id = key.itemId();
        return new ItemSnapshot(key, id, id.getNamespace(), id.getPath(),
                hasExistingEmc, existingValue, tagPaths, rarity);
    }

    private static List<String> copyTagPaths(List<String> tagPaths) {
        if (tagPaths == null || tagPaths.isEmpty()) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(tagPaths.size());
        for (String tagPath : tagPaths) {
            if (tagPath != null && !tagPath.isBlank()) {
                copy.add(tagPath);
            }
        }
        return List.copyOf(copy);
    }
}
