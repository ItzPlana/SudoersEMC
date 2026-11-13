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
 * <p>Tags and rarity are frozen once during collection; later solving and fallback pricing only read
 * the immutable snapshot and never access the game registry again.</p>
 *
 * @param key            item identity, including a variant hash when the scanner priced a variant
 * @param registryId     registry id of the item
 * @param namespace      registry namespace, kept separately to make ignore rules cheap
 * @param path           registry path
 * @param hasExistingEmc whether the EMC backend already assigns a value
 * @param existingValue  the existing value, or {@code 0} when none
 * @param tagPaths       tag paths the item carries ({@code null} and blanks filtered), immutable and never {@code null}
 * @param rarity         item rarity; {@code null} is normalised to {@link Rarity#COMMON}
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
     * Backward-compatible constructor: with no tag information, treated as an empty list and
     * {@link Rarity#COMMON}.
     *
     * @param key            item identity
     * @param registryId     registry id
     * @param namespace      namespace
     * @param path           registry path
     * @param hasExistingEmc whether EMC already exists
     * @param existingValue  existing EMC value, {@code 0} meaning none
     */
    public ItemSnapshot(ItemKey key, ResourceLocation registryId, String namespace, String path,
                        boolean hasExistingEmc, long existingValue) {
        this(key, registryId, namespace, path, hasExistingEmc, existingValue,
                List.of(), Rarity.COMMON);
    }

    /**
     * Creates a snapshot without tag information; rarity treated as {@link Rarity#COMMON}.
     *
     * @param key            item identity
     * @param hasExistingEmc whether EMC already exists
     * @param existingValue  existing EMC value, {@code 0} meaning none
     * @return immutable snapshot
     */
    public static ItemSnapshot of(ItemKey key, boolean hasExistingEmc, long existingValue) {
        return of(key, hasExistingEmc, existingValue, List.of(), Rarity.COMMON);
    }

    /**
     * Creates a snapshot with tag and rarity information.
     *
     * @param key            item identity
     * @param hasExistingEmc whether EMC already exists
     * @param existingValue  existing EMC value, {@code 0} meaning none
     * @param tagPaths       tag path list, may be {@code null}
     * @param rarity         item rarity, may be {@code null}
     * @return immutable snapshot
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
