package com.itzplana.sudoersemc.core;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Builds the SNBT list required for ProjectE's exact-NBT registration.
 *
 * <p>{@link ItemKey#variantHash()} stores only the canonicalised economic identity: display fields and,
 * for damageable items, {@code Damage} etc. are stripped. ProjectE's {@code EMCMappingHandler} matches
 * exactly on the full {@code ItemInfo(item, nbt)}, so the registration side must also keep the observed
 * raw SNBT; the canonical SNBT is used only for deduplication, ownership and persistence identity.</p>
 *
 * <p>One economic variant may correspond to several raw SNBTs (e.g. differing only by custom name). Raw
 * values are stably sorted, deduplicated and capped here so anomalous data cannot bloat the persistence
 * file.</p>
 */
public final class VariantRegistration {

    /**
     * Maximum number of raw SNBTs kept per economic variant.
     *
     * <p>Only affects the coverage of ProjectE exact-NBT registration; the economic identity itself is
     * still deduplicated by the canonical hash.</p>
     */
    public static final int MAX_OBSERVED_NBT_PER_VARIANT = 64;

    private VariantRegistration() {
    }

    /**
     * Cleans observed raw SNBT: filters blanks, sorts, deduplicates and truncates to
     * {@link #MAX_OBSERVED_NBT_PER_VARIANT}.
     *
     * @param observed raw SNBT list, may be {@code null}
     * @return an immutable, stable list
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
     * Builds the SNBT list to register with ProjectE.
     *
     * <p>The returned order is fixed as "canonical SNBT, then raw SNBTs in lexicographic order", with
     * exact string deduplication. A base item has no NBT and returns an empty list; the caller registers
     * it as a no-NBT stack.</p>
     *
     * @param key               economic identity
     * @param observedVariantNbts observed raw SNBT, may be {@code null}
     * @return immutable SNBT list; empty for a base item
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
