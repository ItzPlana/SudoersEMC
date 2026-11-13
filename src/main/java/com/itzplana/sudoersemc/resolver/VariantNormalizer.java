package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NumericTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.Nullable;

/**
 * Canonicalises an item stack's NBT into a deterministic SNBT string for {@link ItemKey#variantHash()}.
 *
 * <p>Canonicalisation recursively removes pure display fields at every level ({@code display},
 * {@code RepairCost}, {@code HideFlags}, {@code CustomModelData}, {@code EnchantmentGlintOverride}), and
 * removes the root-level (the item's own) ProjectE {@code StoredEMC} plus non-standard {@code stored_emc}
 * at every level. Root-level {@code StoredEMC} is added dynamically at query time by ProjectE's
 * {@code StoredEMCProcessor} per {@code IItemEmcHolder} and must not enter the variant identity,
 * otherwise every stored value would produce a new variant.</p>
 *
 * <p>ProjectE {@code StoredEMC} inside container contents ({@code BlockEntityTag.Items[].tag}) is the
 * exception: ProjectE's processor only handles the queried item itself and does not read nested
 * contents, while {@code DefaultMaterialResolver} counts that value into the container price. ProjectE's
 * {@code EMCMappingHandler.getStoredEmcValue} does an exact lookup on the full {@code ItemInfo}, so the
 * container variant key must keep this nested field, otherwise the generated mapping never hits the real
 * container stack and the fix never takes effect. During scanning this value is an economically distinct
 * variant (different stored amounts mean different values), bounded and deterministically truncated by
 * {@code VariantScanner}'s {@code maxVariantsPerItem}/{@code HARD_VARIANT_CEILING}.</p>
 *
 * <p>Economically relevant data must be kept, including {@code Enchantments}, {@code StoredEnchantments},
 * {@code Potion}/{@code CustomPotionEffects}, {@code BlockEntityTag} (container contents), {@code Items}
 * and {@code SkullOwner}. {@link #keyFor(ResourceLocation, CompoundTag)} canonicalises a damageable
 * item's top-level {@code Damage} to {@code 0}: ProjectE's {@code DamageProcessor} discounts dynamically
 * by durability at query time, and enumerating every durability value as a separate variant would cause
 * variant explosion and double discounting; if economic fields such as enchantments remain after
 * cleaning, {@code {Damage:0,...}} is kept. <strong>Known limitation:</strong> when a real stack has
 * {@code Damage>0} plus other NBT, its exact {@code ItemInfo} is {@code {Damage:d,...}}, which is not
 * equal to the {@code {Damage:0,...}} generated here; ProjectE safely falls back to the no-NBT base price
 * and runs {@code DamageProcessor}, never misusing another variant's price. This is a limitation
 * intentionally accepted to avoid one-variant-per-durability, not an oversight. When the original tag has
 * no {@code Damage}, none is added, to match ProjectE's no-damage shape.</p>
 *
 * <p>{@link #normalize(CompoundTag)} does not know the item type, so it keeps {@code Damage} and is only
 * used for nested data whose item identity cannot be determined. All compound-tag keys are sorted
 * lexicographically, so identical tags yield exactly the same string regardless of insertion order. The
 * return value is valid SNBT and can be handed directly to {@code TagParser.parseTag}. When no fields
 * remain after cleaning, returns {@code null}, meaning a plain item.</p>
 *
 * <p>Known limitation: semantically unordered lists such as
 * {@code Enchantments}/{@code StoredEnchantments}/{@code AttributeModifiers} still participate in the hash
 * in their original order; equivalent items with different list order get different variants.</p>
 */
public final class VariantNormalizer {

    private static final Set<String> STRIPPED_KEYS = Set.of(
            "display",
            "RepairCost",
            "HideFlags",
            "CustomModelData",
            "EnchantmentGlintOverride",
            // Non-ProjectE lowercase convention; ProjectE only recognises root-level "StoredEMC",
            // and root-level StoredEMC is handled by StoredEMCProcessor at query time.
            "stored_emc"
    );

    private static final String ATTRIBUTE_MODIFIERS = "AttributeModifiers";
    private static final String DAMAGE = "Damage";
    private static final String STORED_EMC = "StoredEMC";
    private static final String UUID = "UUID";

    private VariantNormalizer() {
    }

    /**
     * Canonicalises an item NBT tag.
     *
     * <p>This method does not modify the supplied tag; the returned string is safe to store long-term.
     * Nested compound tags and lists are also cleaned recursively, so a custom name on an item inside
     * container contents does not affect the container variant's identity.</p>
     *
     * <p>This method does not know the item type, so it does not strip non-default {@code Damage}; but
     * when only the default {@code Damage:0} remains after cleaning it is treated as no economic data and
     * returns {@code null}. For non-damageable items with no damage semantics, if they happen to carry
     * only {@code {Damage:0}}, the same lenient policy treats them as a plain item; a caller needing
     * strict alignment with ProjectE's {@code isDamageableItem()} semantics should restrict by item type
     * before calling. Use {@link #keyFor(ResourceLocation, CompoundTag)} to build a damageable item's
     * variant identity.</p>
     *
     * @param tag the raw item tag, may be {@code null}
     * @return canonicalised SNBT; {@code null} when no economically relevant data remains after cleaning
     */
    public static @Nullable String normalize(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        CompoundTag sanitized = sanitizeCompound(tag, false, true);
        if (sanitized.isEmpty() || isDefaultDamageOnly(sanitized)) {
            return null;
        }
        StringBuilder out = new StringBuilder();
        writeCompound(sanitized, out);
        return out.toString();
    }

    /**
     * Whether the tag contains only {@code Damage} with a numeric value of exactly 0 (default damage).
     *
     * <p>Recipe outputs often write {@code {Damage:0}} back as "no NBT"; the default damage value carries
     * no economic information, and keeping it would generate a fake variant
     * {@code variantHash={Damage:0}} for a plain item. Only when the tag has solely this field with value
     * 0 is it treated as empty; if economic fields such as enchantments or container contents remain,
     * {@code Damage} is kept, maintaining the same variant identity as older versions.</p>
     *
     * <p>The numeric check uses {@link NumericTag#getAsDouble()} rather than {@code getInt()}, avoiding
     * non-zero values such as {@code 4294967296L} or {@code 0.5d} being integer-truncated to 0 and wrongly
     * normalised. This method is shared by {@link #normalize(CompoundTag)} and recipe-output
     * pre-normalisation so all entry points share the same decision.</p>
     *
     * @param tag the raw or already-cleaned tag, may be {@code null}
     * @return {@code true} only when the tag contains solely {@code Damage} with a numeric value of exactly 0
     */
    public static boolean isDefaultDamageOnly(@Nullable CompoundTag tag) {
        if (tag == null || tag.size() != 1) {
            return false;
        }
        Tag damage = tag.get(DAMAGE);
        return damage instanceof NumericTag numeric && numeric.getAsDouble() == 0.0D;
    }

    /**
     * Builds an item key with a canonicalised variant hash for the given item.
     *
     * <p>A damageable item's (e.g. sword, tool) top-level {@code Damage} is canonicalised to {@code 0}:
     * ProjectE's {@code DamageProcessor} discounts by remaining durability at EMC query time. Writing
     * every durability value as a separate variant into the mapping would both fill the variant ceiling
     * and be discounted again at query time. If other economic fields such as enchantments remain after
     * cleaning, {@code {Damage:0,...}} is kept. When the original tag has no {@code Damage}, ProjectE's
     * no-damage shape is preserved.</p>
     *
     * <p><strong>Intentional limitation:</strong> a stack with actual {@code Damage>0} plus other NBT has
     * an exact {@code ItemInfo} unequal to {@code {Damage:0,...}}, so the generated mapping does not hit;
     * ProjectE falls back to the base price and runs {@code DamageProcessor}. This avoids
     * one-variant-per-durability and never wrongly merges different damage levels into one variant price;
     * the cost is that such stacks cannot use a variant premium.</p>
     *
     * <p>Nested {@code StoredEMC} in container contents is kept (root-level {@code StoredEMC} is still
     * delegated to ProjectE), so the container mapping key is exactly equal to the real stack's full
     * {@code ItemInfo}.</p>
     *
     * @param itemId the item registry id
     * @param tag    the raw item tag, may be {@code null}
     * @return an item key with a variant hash; a plain item key when there is no data to keep
     */
    public static ItemKey keyFor(ResourceLocation itemId, @Nullable CompoundTag tag) {
        Objects.requireNonNull(itemId, "itemId");
        return keyFor(itemId, tag, BuiltInRegistries.ITEM.getOptional(itemId).orElse(null));
    }

    /**
     * Overload with an already-resolved {@link Item}, so the same call chain can reuse the registry
     * lookup result.
     */
    static ItemKey keyFor(ResourceLocation itemId, @Nullable CompoundTag tag, @Nullable Item item) {
        Objects.requireNonNull(itemId, "itemId");
        if (item == null || !item.canBeDepleted()) {
            return ItemKey.of(itemId, normalize(tag));
        }
        boolean hadDamage = tag != null && tag.contains(DAMAGE);
        CompoundTag sanitized = tag == null || tag.isEmpty()
                ? new CompoundTag()
                : sanitizeCompound(tag, true, true);
        if (sanitized.isEmpty()) {
            return ItemKey.of(itemId);
        }
        // ProjectE's ItemInfo matches on full NBT exactly: a damageable item with other NBT that originally
        // had Damage has the default shape {Damage:0,...}; if it originally had no Damage, the no-damage shape must be kept.
        // Normalising Damage to 0 only removes "one variant per durability"; it never changes the ItemInfo shape out of nothing.
        if (hadDamage) {
            sanitized.putInt(DAMAGE, 0);
        }
        if (isDefaultDamageOnly(sanitized)) {
            return ItemKey.of(itemId);
        }
        StringBuilder out = new StringBuilder();
        writeCompound(sanitized, out);
        return ItemKey.of(itemId, out.toString());
    }

    private static CompoundTag sanitizeCompound(CompoundTag source, boolean stripTopLevelDamage,
                                                boolean stripTopLevelStoredEmc) {
        CompoundTag result = new CompoundTag();
        for (String key : source.getAllKeys()) {
            if (STRIPPED_KEYS.contains(key)
                    || (stripTopLevelStoredEmc && STORED_EMC.equals(key))
                    || (stripTopLevelDamage && DAMAGE.equals(key))) {
                continue;
            }
            Tag value = source.get(key);
            if (value == null) {
                continue;
            }
            if (ATTRIBUTE_MODIFIERS.equals(key)) {
                result.put(key, sanitizeAttributeModifiers(value));
            } else {
                result.put(key, sanitizeTag(value));
            }
        }
        return result;
    }

    /**
     * An attribute modifier's UUID is only a random identity and takes no part in economic value
     * comparison.
     *
     * <p>Vanilla and most mods store modifiers in a list, so {@code UUID} is removed from each compound
     * tag in the list; a few mods use a single compound tag directly, which is handled here too. Other
     * fields are kept as-is.</p>
     */
    private static Tag sanitizeAttributeModifiers(Tag tag) {
        if (tag instanceof ListTag list) {
            ListTag result = new ListTag();
            for (int i = 0; i < list.size(); i++) {
                Tag element = list.get(i);
                if (element instanceof CompoundTag entry) {
                    CompoundTag sanitized = sanitizeCompound(entry, false, false);
                    sanitized.remove(UUID);
                    result.add(sanitized);
                } else {
                    result.add(sanitizeTag(element));
                }
            }
            return result;
        }
        if (tag instanceof CompoundTag compound) {
            CompoundTag sanitized = sanitizeCompound(compound, false, false);
            sanitized.remove(UUID);
            return sanitized;
        }
        return sanitizeTag(tag);
    }

    private static Tag sanitizeTag(Tag tag) {
        if (tag instanceof CompoundTag compound) {
            return sanitizeCompound(compound, false, false);
        }
        if (tag instanceof ListTag list) {
            ListTag result = new ListTag();
            for (int i = 0; i < list.size(); i++) {
                result.add(sanitizeTag(list.get(i)));
            }
            return result;
        }
        // Primitives and arrays are never modified, so they can be reused directly.
        return tag;
    }

    private static void writeCompound(CompoundTag compound, StringBuilder out) {
        Map<String, Tag> sorted = new TreeMap<>();
        for (String key : compound.getAllKeys()) {
            Tag value = compound.get(key);
            if (value != null) {
                sorted.put(key, value);
            }
        }

        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Tag> entry : sorted.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendKey(entry.getKey(), out);
            out.append(':');
            writeTag(entry.getValue(), out);
        }
        out.append('}');
    }

    private static void writeList(ListTag list, StringBuilder out) {
        out.append('[');
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            writeTag(list.get(i), out);
        }
        out.append(']');
    }

    private static void writeTag(Tag tag, StringBuilder out) {
        if (tag instanceof CompoundTag compound) {
            writeCompound(compound, out);
        } else if (tag instanceof ListTag list) {
            writeList(list, out);
        } else if (tag instanceof StringTag stringTag) {
            // StringTag#getAsString returns the raw value without surrounding quotes; it must be escaped explicitly.
            out.append(StringTag.quoteAndEscape(stringTag.getAsString()));
        } else {
            // Primitives and arrays use the vanilla accessor to emit valid SNBT.
            out.append(tag.getAsString());
        }
    }

    private static void appendKey(String key, StringBuilder out) {
        if (isSimpleKey(key)) {
            out.append(key);
        } else {
            out.append(StringTag.quoteAndEscape(key));
        }
    }

    /**
     * Whether a key can be written to SNBT without quotes. The allowed character set matches
     * {@code TagParser}'s unquoted-key rules.
     */
    private static boolean isSimpleKey(String key) {
        if (key.isEmpty()) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean allowed = (c >= '0' && c <= '9')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= 'a' && c <= 'z')
                    || c == '_' || c == '-' || c == '+' || c == '.';
            if (!allowed) {
                return false;
            }
        }
        return true;
    }
}
