package com.itzplana.sudoersemc.pipeline;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Summary of one "solve and apply" run.
 *
 * <p>All counts describe the same pipeline run: {@code solvedByRecipe} and {@code solvedByFallback} are
 * candidate counts, while {@code applied} is the number finally written to the store (or that would have
 * been written in dry-run mode). {@code sourceBreakdown}'s keys are
 * {@link com.itzplana.sudoersemc.core.PriceSource#name()} and its values are the number actually applied
 * from that source; in non-dry-run mode its sum equals {@code applied}.</p>
 *
 * @param solvedByRecipe    number of items the recipe solver gave a positive value
 * @param solvedByFallback  number of items given a positive value via creative-item protection fixed values or the fallback pricing chain
 * @param applied           number of entries finally written to the store; in dry-run mode the number that would have been written
 * @param quarantined       number of candidates quarantined by large-drop protection and not written
 * @param overflowResets    number of overflow resets that actually occurred this run
 * @param unresolved        number of items still unresolved at the end of solving
 * @param durationMillis    this run's elapsed millis
 * @param appliedToStore    whether the persistent store was actually written; {@code false} in dry-run mode where the store is {@code null}
 * @param sourceBreakdown   source distribution of actually applied entries
 * @param extraCounters     extra named counters (e.g. retiredEntries, quarantine count, timing samples); must not contain null keys or negative values
 */
public record PipelineResult(
        int solvedByRecipe,
        int solvedByFallback,
        int applied,
        int quarantined,
        int overflowResets,
        int unresolved,
        long durationMillis,
        boolean appliedToStore,
        Map<String, Long> sourceBreakdown,
        Map<String, Long> extraCounters
) {

    public PipelineResult {
        requireNonNegative("solvedByRecipe", solvedByRecipe);
        requireNonNegative("solvedByFallback", solvedByFallback);
        requireNonNegative("applied", applied);
        requireNonNegative("quarantined", quarantined);
        requireNonNegative("overflowResets", overflowResets);
        requireNonNegative("unresolved", unresolved);
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis 必须 >= 0，实际为 " + durationMillis);
        }

        Map<String, Long> copied = new LinkedHashMap<>();
        if (sourceBreakdown != null) {
            for (Map.Entry<String, Long> entry : sourceBreakdown.entrySet()) {
                String key = entry.getKey();
                Long value = entry.getValue();
                if (key == null) {
                    throw new IllegalArgumentException("sourceBreakdown 不能包含 null 键");
                }
                if (value == null || value < 0L) {
                    throw new IllegalArgumentException(
                            "sourceBreakdown[" + key + "] 必须为非负数值，实际为 " + value);
                }
                if (value > 0L) {
                    copied.put(key, value);
                }
            }
        }
        sourceBreakdown = Collections.unmodifiableMap(copied);

        Map<String, Long> copiedCounters = new LinkedHashMap<>();
        if (extraCounters != null) {
            for (Map.Entry<String, Long> entry : extraCounters.entrySet()) {
                String key = entry.getKey();
                Long value = entry.getValue();
                if (key == null) {
                    throw new IllegalArgumentException("extraCounters 不能包含 null 键");
                }
                if (value == null || value < 0L) {
                    throw new IllegalArgumentException(
                            "extraCounters[" + key + "] 必须为非负数值，实际为 " + value);
                }
                // Unlike sourceBreakdown, zero-valued counters are kept too; the report needs the full counter set.
                copiedCounters.put(key, value);
            }
        }
        extraCounters = Collections.unmodifiableMap(copiedCounters);
    }

    /**
     * Compatibility constructor for Phase 21 and earlier callers: carries no extra counters.
     */
    public PipelineResult(int solvedByRecipe,
                          int solvedByFallback,
                          int applied,
                          int quarantined,
                          int overflowResets,
                          int unresolved,
                          long durationMillis,
                          boolean appliedToStore,
                          Map<String, Long> sourceBreakdown) {
        this(solvedByRecipe, solvedByFallback, applied, quarantined, overflowResets,
                unresolved, durationMillis, appliedToStore, sourceBreakdown, Map.of());
    }

    /**
     * Constructs a result with "no output at all".
     *
     * <p>Used for empty snapshots, a missing stashed snapshot, and internal pipeline exceptions.
     * {@code appliedToStore} is fixed to {@code false}, because this result promises no entry has been
     * persisted.</p>
     *
     * @param durationMillis this run's elapsed time; negatives are zeroed
     * @return a result with all counts zero
     */
    public static PipelineResult zeroed(long durationMillis) {
        return new PipelineResult(0, 0, 0, 0, 0, 0, Math.max(0L, durationMillis), false,
                Map.of(), Map.of());
    }

    /**
     * @return the sum of all counts in {@code sourceBreakdown}
     */
    public long sourceTotal() {
        long total = 0L;
        for (Long value : sourceBreakdown.values()) {
            total += value;
        }
        return total;
    }

    /**
     * Reads an extra counter; returns the given default when the name is missing or the value is {@code null}.
     *
     * @param name         counter name, may be {@code null}
     * @param defaultValue value returned when missing
     * @return the counter value or the default
     */
    public long extraCounter(@Nullable String name, long defaultValue) {
        if (name == null) {
            return defaultValue;
        }
        Long value = extraCounters.get(name);
        return value == null ? defaultValue : value;
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " 必须 >= 0，实际为 " + value);
        }
    }
}
