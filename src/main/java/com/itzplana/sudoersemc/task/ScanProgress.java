package com.itzplana.sudoersemc.task;

import java.util.Objects;

/**
 * Immutable snapshot of scan progress.
 *
 * <p>{@code processed} and {@code total} describe the current phase's completion. {@code total} of 0
 * means the total is not yet known (e.g. the snapshot has not been collected), in which case
 * {@link #fraction()} returns 0. {@code startedAtMillis} stays constant for the whole scan so callers can
 * show total elapsed time since the scan was requested; {@code lastUpdateMillis} updates on every
 * {@link #advance}.</p>
 *
 * <p>This record is itself immutable but does not handle thread-safe publication. {@link ScanManager}
 * stores the latest instance in a {@code volatile} field, so readers always see a fully-populated
 * progress object rather than a half-written state.</p>
 *
 * @param phase            current phase
 * @param processed        processed count in the current phase, clamped to a legal range at construction
 * @param total            total count in the current phase; <= 0 means unknown
 * @param startedAtMillis  {@link System#currentTimeMillis()} at scan start, must not be negative
 * @param lastUpdateMillis timestamp of the most recent progress update, must not be negative
 * @param detail           user-facing Chinese detail text; {@code null} is normalised to an empty string
 */
public record ScanProgress(
        ScanPhase phase,
        int processed,
        int total,
        long startedAtMillis,
        long lastUpdateMillis,
        String detail
) {

    public ScanProgress {
        Objects.requireNonNull(phase, "phase");
        if (startedAtMillis < 0L) {
            throw new IllegalArgumentException("startedAtMillis 必须 >= 0，实际为 " + startedAtMillis);
        }
        if (lastUpdateMillis < 0L) {
            throw new IllegalArgumentException("lastUpdateMillis 必须 >= 0，实际为 " + lastUpdateMillis);
        }
        if (total > 0) {
            // When the total is known, the processed count must lie within [0, total].
            processed = Math.max(0, Math.min(total, processed));
        } else {
            // Even when the total is unknown, a negative completed count is not allowed.
            processed = Math.max(0, processed);
        }
        detail = detail == null ? "" : detail;
    }

    /**
     * Returns the current phase's completion fraction.
     *
     * @return 0.0 when the total is unknown; otherwise {@code min(1.0, processed / total)}
     */
    public double fraction() {
        return total <= 0 ? 0.0 : Math.min(1.0, processed / (double) total);
    }

    /**
     * Returns the elapsed time from scan start to the most recent update.
     *
     * @return non-negative milliseconds; 0 if the timestamp order is anomalous
     */
    public long elapsedMillis() {
        return Math.max(0L, lastUpdateMillis - startedAtMillis);
    }

    /**
     * @return whether the current phase is still in progress, equivalent to {@link ScanPhase#isActive()}
     */
    public boolean isRunning() {
        return phase.isActive();
    }

    /**
     * Produces the next progress snapshot from the current one.
     *
     * <p>{@code startedAtMillis} is preserved as-is and {@code lastUpdateMillis} takes the current system
     * time. The new object's {@code processed}/{@code total} are re-validated and clamped by the
     * constructor, so callers may pass un-clamped accumulated values.</p>
     *
     * @param phase     the next phase
     * @param processed processed count for the next phase
     * @param total     total count for the next phase
     * @param detail    Chinese detail text, may be {@code null}
     * @return a brand-new progress instance; never modifies the current instance
     */
    public ScanProgress advance(ScanPhase phase, int processed, int total, String detail) {
        return new ScanProgress(phase, processed, total, startedAtMillis,
                System.currentTimeMillis(), detail);
    }

    /**
     * Creates the initial progress for the idle state.
     *
     * @return an instance with phase {@link ScanPhase#IDLE}, zero progress and no running semantics
     */
    public static ScanProgress idle() {
        return new ScanProgress(ScanPhase.IDLE, 0, 0, 0L, 0L, "");
    }
}
