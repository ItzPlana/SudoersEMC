package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * Generates a deduplicated {@link ItemKey} list from observed NBT variants.
 *
 * <p>Each tag is first canonicalised by {@link VariantNormalizer#keyFor(ResourceLocation, CompoundTag)};
 * tags with no economic variant data (e.g. only a custom name, or only a damageable item's
 * {@code Damage}) are discarded. Identical canonical results keep a single key. A scan returns at most
 * {@code maxVariantsPerItem} keys, additionally bounded by {@link #HARD_VARIANT_CEILING}; when over the
 * limit the lexicographically smallest keys are kept, so the result is independent of input order and
 * world traversal order cannot affect EMC ownership. The result is sorted by
 * {@link ItemKey#compareTo(ItemKey)} and immutable.</p>
 */
public final class VariantScanner {

    /**
     * Hard per-item variant ceiling, preventing a corrupt data source from overwhelming the scan.
     */
    public static final int HARD_VARIANT_CEILING = 1000;

    /**
     * Hard ceiling on the total number of variants generated in one scan.
     *
     * <p>The per-item limit only constrains a single item; without a global budget, many distinct items
     * carrying NBT at once would still grow collection and persistence entries without bound. Once this
     * budget is reached, collection stops in ascending item-id order.</p>
     */
    public static final int HARD_TOTAL_VARIANT_CEILING = 10_000;

    /**
     * Scans and canonicalises observed variants.
     *
     * @param baseStack           base stack used to determine the item registry id, must not be empty
     * @param observedVariants    observed raw tag list, may be {@code null} or contain {@code null} elements
     * @param maxVariantsPerItem  maximum variants kept per item, must be >= 1
     * @return immutable list sorted by item key
     * @throws IllegalArgumentException when {@code maxVariantsPerItem < 1}, the base stack is empty, or the item is unregistered
     */
    public List<ItemKey> scan(ItemStack baseStack, List<CompoundTag> observedVariants, int maxVariantsPerItem) {
        Objects.requireNonNull(baseStack, "baseStack");
        if (baseStack.isEmpty()) {
            throw new IllegalArgumentException("baseStack must not be empty");
        }
        if (maxVariantsPerItem < 1) {
            throw new IllegalArgumentException("maxVariantsPerItem must be >= 1, got " + maxVariantsPerItem);
        }

        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(baseStack.getItem());
        if (itemId == null) {
            throw new IllegalArgumentException("baseStack item is not registered: " + baseStack.getItem());
        }
        if (observedVariants == null || observedVariants.isEmpty()) {
            return List.of();
        }

        int limit = Math.min(maxVariantsPerItem, HARD_VARIANT_CEILING);
        TreeSet<ItemKey> unique = new TreeSet<>();
        for (CompoundTag observed : observedVariants) {
            ItemKey key = VariantNormalizer.keyFor(itemId, observed);
            if (key.hasVariant()) {
                unique.add(key);
                if (unique.size() > limit) {
                    unique.pollLast();
                }
            }
        }
        return List.copyOf(unique);
    }
}
