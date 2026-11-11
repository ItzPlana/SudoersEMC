package com.example.sudoersemc.scanner;

import com.example.sudoersemc.core.ItemKey;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Immutable description of one registered item as seen by the scanner.
 *
 * @param key            item identity, including a variant hash when the scanner priced a variant
 * @param registryId     registry id of the item
 * @param namespace      registry namespace, kept separately to make ignore rules cheap
 * @param path           registry path
 * @param hasExistingEmc whether the EMC backend already assigns a value
 * @param existingValue  the existing value, or {@code 0} when none
 */
public record ItemSnapshot(
        ItemKey key,
        ResourceLocation registryId,
        String namespace,
        String path,
        boolean hasExistingEmc,
        long existingValue
) {

    public ItemSnapshot {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(registryId, "registryId");
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (existingValue < 0) {
            throw new IllegalArgumentException("existingValue must be >= 0, got " + existingValue);
        }
    }

    public static ItemSnapshot of(ItemKey key, boolean hasExistingEmc, long existingValue) {
        ResourceLocation id = key.itemId();
        return new ItemSnapshot(key, id, id.getNamespace(), id.getPath(), hasExistingEmc, existingValue);
    }
}
