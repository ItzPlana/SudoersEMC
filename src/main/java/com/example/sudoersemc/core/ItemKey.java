package com.example.sudoersemc.core;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Stable identity of an economically distinct item.
 *
 * <p>Minecraft 1.20.1 stores item-specific data in {@code ItemStack} NBT. A {@code null}
 * {@code variantHash} means "the plain item", which is the same identity ProjectE uses when a
 * stack has no NBT.</p>
 *
 * <p>{@code variantHash} must be a deterministic, canonical NBT representation. Never store
 * display-only data such as custom names or lore in it unless the value is genuinely economic.</p>
 */
public record ItemKey(ResourceLocation itemId, @Nullable String variantHash) implements Comparable<ItemKey> {

    public ItemKey {
        Objects.requireNonNull(itemId, "itemId");
        if (variantHash != null && variantHash.isEmpty()) {
            variantHash = null;
        }
    }

    public static ItemKey of(ResourceLocation itemId) {
        return new ItemKey(itemId, null);
    }

    public static ItemKey of(ResourceLocation itemId, @Nullable String variantHash) {
        return new ItemKey(itemId, variantHash);
    }

    public boolean hasVariant() {
        return variantHash != null;
    }

    @Override
    public int compareTo(ItemKey other) {
        int byId = itemId.compareTo(other.itemId);
        if (byId != 0) {
            return byId;
        }
        if (variantHash == null) {
            return other.variantHash == null ? 0 : -1;
        }
        return other.variantHash == null ? 1 : variantHash.compareTo(other.variantHash);
    }

    @Override
    public String toString() {
        return variantHash == null ? itemId.toString() : itemId + variantHash;
    }
}
