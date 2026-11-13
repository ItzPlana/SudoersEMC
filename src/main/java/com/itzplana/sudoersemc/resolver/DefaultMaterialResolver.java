package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Built-in NBT material resolver covering vanilla container contents.
 *
 * <p>Rules:</p>
 * <ul>
 *   <li>When {@code BlockEntityTag.Items} contains exactly one non-empty stack, the container value
 *       equals that item's unit EMC times {@code Count}; with multiple non-empty stacks no unique price
 *       can be given, so return empty.</li>
 *   <li>A damageable item's {@code Damage} inside the container is discounted by the same formula as
 *       ProjectE's {@code DamageProcessor}, avoiding pricing a damaged item at full durability.</li>
 *   <li>An EMC holder item's own {@code StoredEMC} inside the container is added with saturation after
 *       discounting, in the same order as ProjectE's {@code DamageProcessor} →
 *       {@code StoredEMCProcessor}. ProjectE does not run that processor for container contents, so it
 *       must be handled here. By default this only applies to the built-in ProjectE Klein Star
 *       whitelist, avoiding counting fake {@code StoredEMC} on non-holder items; other mods' EMC holders
 *       require the integrator to pass a predicate based on
 *       {@code PECapabilities.EMC_HOLDER_ITEM_CAPABILITY} via
 *       {@link #DefaultMaterialResolver(ToLongFunction, Predicate)}. {@code StoredEMC} on the container's
 *       root tag is still handled by ProjectE and is not added again here.</li>
 *   <li>The container variant key must keep the contents' nested {@code StoredEMC}, otherwise ProjectE's
 *       exact lookup on the full {@code ItemInfo} misses and this resolution result never takes effect.</li>
 * </ul>
 *
 * <p>The lookup function must return non-negative EMC, 0 meaning unknown. The resolution result is
 * always greater than 0, avoiding treating an unpriceable item as free.</p>
 */
public final class DefaultMaterialResolver implements NbtMaterialResolver {

    private static final String BLOCK_ENTITY_TAG = "BlockEntityTag";
    private static final String ITEMS = "Items";
    private static final String ID = "id";
    private static final String COUNT = "Count";
    private static final String ITEM_TAG = "tag";
    private static final String STORED_EMC = "StoredEMC";

    /**
     * In ProjectE 1.0.1 the only items implementing {@code IItemEmcHolder} are the Klein Star series.
     * Kept as a registry-id whitelist rather than referencing ProjectE classes directly, so this class
     * still loads when ProjectE is absent.
     */
    private static final Set<ResourceLocation> PROJECTE_EMC_HOLDER_IDS = Set.of(
            new ResourceLocation("projecte", "klein_star_ein"),
            new ResourceLocation("projecte", "klein_star_zwei"),
            new ResourceLocation("projecte", "klein_star_drei"),
            new ResourceLocation("projecte", "klein_star_vier"),
            new ResourceLocation("projecte", "klein_star_sphere"),
            new ResourceLocation("projecte", "klein_star_omega")
    );

    private final ToLongFunction<ItemKey> emcLookup;
    private final Predicate<ItemStack> storedEmcHolder;

    /**
     * Constructs a resolver using the built-in ProjectE EMC holder whitelist.
     *
     * <p>By default only the built-in ProjectE Klein Star's {@code StoredEMC} counts toward container
     * value; the same tag on other items is ignored to avoid fake-{@code StoredEMC} overvaluation. When
     * wiring other mods' EMC holders in Phase 7, use
     * {@link #DefaultMaterialResolver(ToLongFunction, Predicate)} to pass a precise predicate based on
     * {@code PECapabilities.EMC_HOLDER_ITEM_CAPABILITY}.</p>
     *
     * @param emcLookup function querying base variant EMC from an item key, 0 meaning unknown; must not be {@code null}.
     *                  The supplied value must not yet have run ProjectE's {@code DamageProcessor}/
     *                  {@code StoredEMCProcessor}; this resolver discounts and adds stored EMC itself.
     */
    public DefaultMaterialResolver(ToLongFunction<ItemKey> emcLookup) {
        this(emcLookup, DefaultMaterialResolver::isKnownProjectEEmcHolder);
    }

    /**
     * Allows the integration layer to inject the EMC holder predicate (e.g. a ProjectE item capability).
     *
     * @param emcLookup       function querying base variant EMC from an item key, 0 meaning unknown; must not be {@code null}
     * @param storedEmcHolder predicate deciding whether contents are an EMC holder; must not be {@code null}
     */
    public DefaultMaterialResolver(ToLongFunction<ItemKey> emcLookup, Predicate<ItemStack> storedEmcHolder) {
        this.emcLookup = Objects.requireNonNull(emcLookup, "emcLookup");
        this.storedEmcHolder = Objects.requireNonNull(storedEmcHolder, "storedEmcHolder");
    }

    @Override
    public boolean supports(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        return tag != null && containerItems(tag) != null;
    }

    @Override
    public OptionalLong resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return OptionalLong.empty();
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return OptionalLong.empty();
        }
        return resolveSingleItemContainer(tag);
    }

    /**
     * Reads container contents. A unique price can be given only when the list has exactly one entry
     * with {@code Count > 0}.
     */
    private OptionalLong resolveSingleItemContainer(CompoundTag tag) {
        ListTag items = containerItems(tag);
        if (items == null) {
            return OptionalLong.empty();
        }

        CompoundTag single = null;
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            if (entry.getByte(COUNT) <= 0) {
                continue;
            }
            if (single != null) {
                // Multiple non-empty stacks mean the container value cannot be represented by a single item.
                return OptionalLong.empty();
            }
            single = entry;
        }

        if (single == null) {
            return OptionalLong.empty();
        }

        ResourceLocation itemId = ResourceLocation.tryParse(single.getString(ID));
        if (itemId == null) {
            return OptionalLong.empty();
        }
        CompoundTag itemTag = single.contains(ITEM_TAG, Tag.TAG_COMPOUND)
                ? single.getCompound(ITEM_TAG)
                : null;
        long unitValue = containedItemValue(itemId, itemTag);
        if (unitValue <= 0L) {
            return OptionalLong.empty();
        }
        long count = single.getByte(COUNT);
        return OptionalLong.of(saturatedMultiply(unitValue, count));
    }

    /**
     * Computes the EMC of a single item inside the container. A damageable item is discounted by
     * remaining durability (formula matching ProjectE's {@code DamageProcessor}), then the item's own
     * stored {@code StoredEMC} is added with saturation (matching {@code StoredEMCProcessor} order). Only
     * items the {@code storedEmcHolder} predicate judges to be EMC holders are added, avoiding
     * fake-{@code StoredEMC} overvaluation. When the base value is unknown, returns {@code 0} so the whole
     * container stays unpriced, avoiding mistaking stored EMC for the full value or giving a low price.
     */
    private long containedItemValue(ResourceLocation itemId, @Nullable CompoundTag itemTag) {
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        long value = emcLookup.applyAsLong(VariantNormalizer.keyFor(itemId, itemTag, item));
        if (value <= 0L) {
            return value;
        }

        ItemStack contained = null;
        if (item != null) {
            contained = new ItemStack(item);
            if (itemTag != null) {
                contained.setTag(itemTag);
            }
        }

        if (item != null && item.canBeDepleted()) {
            int maxDamage = contained.getMaxDamage();
            int damage = contained.getDamageValue();
            // ProjectE gives up discounting when damage > maxDamage; stay consistent here.
            if (maxDamage > 0 && damage > 0 && damage <= maxDamage) {
                long remaining = maxDamage - damage + 1L;
                value = value > Long.MAX_VALUE / remaining
                        ? Long.MAX_VALUE
                        : value * remaining / maxDamage;
            }
        }

        long storedEmc = 0L;
        if (contained != null && itemTag != null && storedEmcHolder.test(contained)) {
            storedEmc = Math.max(0L, itemTag.getLong(STORED_EMC));
        }
        if (storedEmc == 0L) {
            return value;
        }
        if (value > Long.MAX_VALUE - storedEmc) {
            return Long.MAX_VALUE;
        }
        return value + storedEmc;
    }

    /**
     * Default EMC holder whitelist: in ProjectE 1.0.1 only the Klein Star series implements
     * {@code IItemEmcHolder}. Other mods must wire in the capability via the predicate overload.
     */
    static boolean isKnownProjectEEmcHolder(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        return isKnownProjectEEmcHolder(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }

    static boolean isKnownProjectEEmcHolder(@Nullable ResourceLocation itemId) {
        return itemId != null && PROJECTE_EMC_HOLDER_IDS.contains(itemId);
    }

    /**
     * Reads {@code BlockEntityTag.Items}; returns {@code null} when absent or of the wrong type.
     */
    private static @Nullable ListTag containerItems(CompoundTag tag) {
        if (!tag.contains(BLOCK_ENTITY_TAG, Tag.TAG_COMPOUND)) {
            return null;
        }
        CompoundTag blockEntity = tag.getCompound(BLOCK_ENTITY_TAG);
        if (!blockEntity.contains(ITEMS, Tag.TAG_LIST)) {
            return null;
        }
        return blockEntity.getList(ITEMS, Tag.TAG_COMPOUND);
    }

    /**
     * Saturating multiplication: avoids overflow to a negative number when multiplying EMC by a large
     * container count.
     */
    private static long saturatedMultiply(long value, long count) {
        if (value <= 0L || count <= 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / count) {
            return Long.MAX_VALUE;
        }
        return value * count;
    }
}
