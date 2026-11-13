package com.itzplana.sudoersemc.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable collection of one scan's input fingerprints.
 *
 * <p>The five fields cover items, recipes, tags, config and baseline sources respectively. Each field is
 * a digest of its input set obtained by "filter nulls → sort ascending → join with {@code \u0000} →
 * SHA-256 → take the first 32 hex chars". Sorting makes the same input set independent of traversal
 * order; the null-byte separator avoids {@code ["ab","c"]} and {@code ["a","bc"]} producing the same
 * digest.</p>
 *
 * <p>This class only provides fingerprint computation and comparison; persistence and startup cache
 * reuse are decided by {@code ScanStateStore} and the entry layer. Both Phase 7's
 * {@code ScanCollectionPipeline} and Phase 12's {@code ScanPipeline} write the fingerprint to
 * {@code scan_state.json} and compare it at server startup to skip unchanged scans. This class itself does
 * not access disk; callers must ensure each channel covers the input content that actually affects
 * pricing (including recipe/tag content and external baselines). The fingerprint describes only input
 * content — no timestamps, random numbers or object identity — so the same input set yields the same
 * result in any process.</p>
 *
 * @param itemFingerprint     item registry input digest
 * @param recipeFingerprint   recipe input digest
 * @param tagFingerprint      tag input digest
 * @param configFingerprint   digest of config affecting pricing
 * @param baselineFingerprint external baseline source digest
 */
public record ScanFingerprint(
        String itemFingerprint,
        String recipeFingerprint,
        String tagFingerprint,
        String configFingerprint,
        String baselineFingerprint
) {

    /**
     * Fixed digest for an empty input set. A fixed string rather than an empty string is used so logs can
     * distinguish "no input" from "digest computation failed".
     */
    public static final String EMPTY = "empty";

    /** Number of characters kept from the SHA-256 hex digest. */
    private static final int HASH_LENGTH = 32;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    public ScanFingerprint {
        itemFingerprint = normalize(itemFingerprint);
        recipeFingerprint = normalize(recipeFingerprint);
        tagFingerprint = normalize(tagFingerprint);
        configFingerprint = normalize(configFingerprint);
        baselineFingerprint = normalize(baselineFingerprint);
    }

    /**
     * Computes a stable digest for a set of string inputs.
     *
     * <p>{@code null} elements are skipped; the rest are sorted ascending in natural order then joined
     * with a null byte. Returns {@link #EMPTY} when the input is empty, contains only {@code null}, or
     * {@code values} itself is {@code null}. Duplicates are not deduplicated, because duplication is itself
     * part of the input content.</p>
     *
     * @param values input value set, may be {@code null}
     * @return a 32-char lowercase hex digest, or {@link #EMPTY}
     */
    public static String hash(Iterable<String> values) {
        if (values == null) {
            return EMPTY;
        }

        List<String> collected = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                collected.add(value);
            }
        }
        if (collected.isEmpty()) {
            return EMPTY;
        }

        Collections.sort(collected);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < collected.size(); i++) {
            if (i > 0) {
                joined.append('\u0000');
            }
            joined.append(collected.get(i));
        }

        byte[] digest = sha256(joined.toString());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(HEX_DIGITS[(value >>> 4) & 0x0F]);
            hex.append(HEX_DIGITS[value & 0x0F]);
        }
        return hex.substring(0, HASH_LENGTH);
    }

    /**
     * Builds the fingerprints for the five channels.
     *
     * @param items    item inputs
     * @param recipes  recipe inputs
     * @param tags     tag inputs
     * @param config   config inputs
     * @param baseline baseline inputs
     * @return the fingerprint collection with each channel digested separately
     */
    public static ScanFingerprint of(Iterable<String> items,
                                     Iterable<String> recipes,
                                     Iterable<String> tags,
                                     Iterable<String> config,
                                     Iterable<String> baseline) {
        return new ScanFingerprint(
                hash(items),
                hash(recipes),
                hash(tags),
                hash(config),
                hash(baseline));
    }

    /**
     * Whether two fingerprints describe the same set of scan inputs.
     *
     * @param other the object to compare, may be {@code null}
     * @return {@code true} when all five fields are equal; {@code false} when {@code other} is {@code null}
     */
    public boolean equalsFingerprint(ScanFingerprint other) {
        return other != null
                && Objects.equals(itemFingerprint, other.itemFingerprint)
                && Objects.equals(recipeFingerprint, other.recipeFingerprint)
                && Objects.equals(tagFingerprint, other.tagFingerprint)
                && Objects.equals(configFingerprint, other.configFingerprint)
                && Objects.equals(baselineFingerprint, other.baselineFingerprint);
    }

    /**
     * Returns the names of channels that changed compared to another fingerprint.
     *
     * <p>Names are fixed as {@code item}, {@code recipe}, {@code tag}, {@code config}, {@code baseline} for
     * stable parsing in logs and diagnostics. When {@code other} is {@code null}, all five channels are
     * treated as changed.</p>
     *
     * @param other the fingerprint to compare, may be {@code null}
     * @return list of changed channel names; empty when fully equal
     */
    public List<String> changedChannels(ScanFingerprint other) {
        if (other == null) {
            return List.of("item", "recipe", "tag", "config", "baseline");
        }
        List<String> changed = new ArrayList<>(5);
        if (!Objects.equals(itemFingerprint, other.itemFingerprint)) {
            changed.add("item");
        }
        if (!Objects.equals(recipeFingerprint, other.recipeFingerprint)) {
            changed.add("recipe");
        }
        if (!Objects.equals(tagFingerprint, other.tagFingerprint)) {
            changed.add("tag");
        }
        if (!Objects.equals(configFingerprint, other.configFingerprint)) {
            changed.add("config");
        }
        if (!Objects.equals(baselineFingerprint, other.baselineFingerprint)) {
            changed.add("baseline");
        }
        return List.copyOf(changed);
    }

    /**
     * @return {@code true} when all five channels equal {@link #EMPTY}
     */
    public boolean isEmpty() {
        return EMPTY.equals(itemFingerprint)
                && EMPTY.equals(recipeFingerprint)
                && EMPTY.equals(tagFingerprint)
                && EMPTY.equals(configFingerprint)
                && EMPTY.equals(baselineFingerprint);
    }

    /**
     * Normalises {@code null} to the empty digest; an empty string is kept as-is, since it is not a value
     * produced by {@link #hash}.
     */
    private static String normalize(String value) {
        return value == null ? EMPTY : value;
    }

    /**
     * Computes SHA-256. The JDK must provide this algorithm; its absence means a broken runtime, so it is
     * converted to {@link IllegalStateException} rather than a checked exception.
     */
    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
