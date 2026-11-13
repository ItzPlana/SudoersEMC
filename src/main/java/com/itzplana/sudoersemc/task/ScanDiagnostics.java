package com.itzplana.sudoersemc.task;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * Lightweight diagnostics collector for a single scan.
 *
 * <p>Usage is "mark at phase end": each {@link #mark(String)} adds the elapsed time since the previous
 * mark to the given phase, so the same phase can be marked repeatedly and accumulate correctly. Time is
 * stored internally in nanos and converted to millis on read, avoiding many short phases being truncated
 * to zero one by one. Peak heap memory is actively sampled by {@link #recordMemory()}; without sampling
 * the report shows 0.0 MiB.</p>
 *
 * <p>Per-tick elapsed time is recorded via {@link #recordTick(long)}. P95 uses the nearest-rank method:
 * sort {@code n} samples ascending and take the {@code ceil(0.95 * n)}-th value (1-based). Thus 0 samples
 * return 0, 1 sample returns itself, and multiple values are not interpolated.</p>
 *
 * <p><strong>Threading:</strong> this class is for single-threaded use. The scan is driven by the server
 * thread and diagnostics reads usually happen on the same thread, so no extra synchronisation is done
 * for concurrent writes. No public method throws: a {@code null} or blank phase name is ignored, and
 * querying an unknown phase returns 0.</p>
 */
public final class ScanDiagnostics {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    /** P95 ratio used by the nearest-rank method. */
    private static final double P95_RATIO = 0.95D;

    /** Reuse one management-interface proxy; tick sampling only calls getHeapMemoryUsage(). */
    private static final MemoryMXBean MEMORY_MX_BEAN = resolveMemoryBean();

    /** Preserves first-appearance order for stable report output. */
    private final Map<String, Long> stageNanos = new LinkedHashMap<>();

    /** Per-tick elapsed samples in nanos, stored in call order. */
    private final List<Long> tickNanos = new ArrayList<>();

    private long lastMarkNanos;
    private long scanStartedNanos;
    private long totalTickNanos;
    private long maxTickNanos;
    private long peakHeapBytes;
    private long heapBytes;

    /**
     * Creates the diagnostics collector, using the current moment as the baseline for the first mark and
     * the whole-scan timing.
     */
    public ScanDiagnostics() {
        long now = System.nanoTime();
        this.lastMarkNanos = now;
        this.scanStartedNanos = now;
    }

    /**
     * Records the end of a phase, adding the elapsed time since the previous mark to that phase.
     *
     * <p>A {@code null}, empty or whitespace-only phase name is silently ignored and the timing baseline
     * does not advance, so an invalid mark cannot swallow real elapsed time. A single mark never produces
     * a negative duration.</p>
     *
     * @param stage phase name, usually {@link ScanPhase#label()} or a stable English identifier
     */
    public void mark(String stage) {
        if (stage == null || stage.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        long elapsed = Math.max(0L, now - lastMarkNanos);
        lastMarkNanos = now;
        stageNanos.merge(stage, elapsed, Long::sum);
    }

    /**
     * Queries a phase's accumulated elapsed time.
     *
     * @param stage phase name, may be {@code null}
     * @return accumulated millis; 0 for an unknown phase or {@code null}
     */
    public long stageMillis(String stage) {
        if (stage == null) {
            return 0L;
        }
        Long nanos = stageNanos.get(stage);
        return nanos == null ? 0L : nanos / NANOS_PER_MILLI;
    }

    /**
     * Returns a read-only snapshot of all phase elapsed times.
     *
     * <p>The returned map is ordered by each phase's first mark; modifying it throws
     * {@link UnsupportedOperationException}, so callers can safely pass it across methods.</p>
     *
     * @return immutable "phase name → millis" map
     */
    public Map<String, Long> stageMillis() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : stageNanos.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue() / NANOS_PER_MILLI);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * @return sum of accumulated millis across all recorded phases
     */
    public long totalMillis() {
        long totalNanos = 0L;
        for (long nanos : stageNanos.values()) {
            totalNanos += nanos;
        }
        return totalNanos / NANOS_PER_MILLI;
    }

    /**
     * Records one tick's actual elapsed time.
     *
     * <p>Negatives are treated as 0; the sample count has no hard cap — the scanner advances in tick
     * batches, so the actual sample count is bounded by scan duration. P95 sorts only on read and does not
     * affect the tick hot path.</p>
     *
     * @param elapsedNanos this tick's actual elapsed time (nanos)
     */
    public void recordTick(long elapsedNanos) {
        long sample = Math.max(0L, elapsedNanos);
        tickNanos.add(sample);
        totalTickNanos += sample;
        maxTickNanos = Math.max(maxTickNanos, sample);
    }

    /**
     * @return number of recorded tick samples
     */
    public int tickCount() {
        return tickNanos.size();
    }

    /**
     * @return accumulated elapsed time of all tick samples (nanos)
     */
    public long totalTickNanos() {
        return totalTickNanos;
    }

    /**
     * @return maximum tick elapsed time (nanos); 0 when there are no samples
     */
    public long maxTickNanos() {
        return maxTickNanos;
    }

    /**
     * @return average tick elapsed time (nanos); 0.0 when there are no samples
     */
    public double averageTickNanos() {
        return tickNanos.isEmpty() ? 0.0D : totalTickNanos / (double) tickNanos.size();
    }

    /**
     * Returns the P95 tick elapsed time (nanos, nearest-rank).
     *
     * <p>0 samples return 0; 1 sample returns that value; multiple values take the
     * {@code ceil(0.95 * n)}-th sample after ascending sort, with no linear interpolation.</p>
     *
     * @return P95 elapsed time (nanos)
     */
    public long p95TickNanos() {
        int size = tickNanos.size();
        if (size == 0) {
            return 0L;
        }
        long[] sorted = new long[size];
        for (int i = 0; i < size; i++) {
            sorted[i] = tickNanos.get(i);
        }
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(P95_RATIO * size);
        int index = Math.max(0, Math.min(size - 1, rank - 1));
        return sorted[index];
    }

    /**
     * @return maximum tick elapsed time (millis)
     */
    public long maxTickMillis() {
        return maxTickNanos / NANOS_PER_MILLI;
    }

    /**
     * @return average tick elapsed time (millis)
     */
    public double averageTickMillis() {
        return averageTickNanos() / NANOS_PER_MILLI;
    }

    /**
     * @return P95 tick elapsed time (millis)
     */
    public long p95TickMillis() {
        return p95TickNanos() / NANOS_PER_MILLI;
    }

    /**
     * Samples the currently used heap memory and updates the peak.
     *
     * <p>Uses the {@code used} field of {@link MemoryMXBean#getHeapMemoryUsage()}; this value comes from
     * the JVM memory management interface and is more direct than {@code Runtime}'s approximation. If the
     * management interface is unavailable, falls back to {@code totalMemory() - freeMemory()}, ensuring
     * diagnostics sampling never affects the scan hot path.</p>
     */
    public void recordMemory() {
        long used;
        try {
            MemoryMXBean bean = MEMORY_MX_BEAN;
            used = bean == null
                    ? Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                    : bean.getHeapMemoryUsage().getUsed();
        } catch (RuntimeException | LinkageError failure) {
            Runtime runtime = Runtime.getRuntime();
            used = runtime.totalMemory() - runtime.freeMemory();
        }
        used = Math.max(0L, used);
        heapBytes = used;
        peakHeapBytes = Math.max(peakHeapBytes, used);
    }

    @Nullable
    private static MemoryMXBean resolveMemoryBean() {
        try {
            return ManagementFactory.getMemoryMXBean();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /**
     * @return observed peak heap memory in bytes; 0 before any sampling
     */
    public long peakHeapBytes() {
        return peakHeapBytes;
    }

    /**
     * @return heap memory in bytes from the most recent {@link #recordMemory()} sample; 0 before any sampling
     */
    public long heapBytes() {
        return heapBytes;
    }

    /**
     * @return wall-clock millis from {@link #reset()} or construction until now
     */
    public long elapsedSinceResetMillis() {
        return Math.max(0L, (System.nanoTime() - scanStartedNanos) / NANOS_PER_MILLI);
    }

    /**
     * Produces a multi-line Chinese diagnostic report.
     *
     * <p>Outputs each phase's millis, the total, per-tick statistics (max/average/P95), and peak and
     * current heap MiB to one decimal place. Even with no phase or tick records the report still contains
     * fixed lines for easy log parsing.</p>
     *
     * @return newline-separated Chinese report text
     */
    public String formatReport() {
        StringBuilder report = new StringBuilder();
        report.append("扫描诊断报告\n");
        if (stageNanos.isEmpty()) {
            report.append("  无阶段记录\n");
        } else {
            for (Map.Entry<String, Long> entry : stageNanos.entrySet()) {
                report.append("  ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue() / NANOS_PER_MILLI)
                        .append(" ms\n");
            }
        }
        report.append("  总计: ").append(totalMillis()).append(" ms\n");
        if (tickNanos.isEmpty()) {
            report.append("  逐 tick: 无采样\n");
        } else {
            report.append("  逐 tick: 样本 ")
                    .append(tickCount())
                    .append("，最大 ")
                    .append(formatMillis(maxTickNanos))
                    .append("，平均 ")
                    .append(formatMillis(averageTickNanos()))
                    .append("，P95 ")
                    .append(formatMillis(p95TickNanos()))
                    .append('\n');
        }
        report.append("  峰值堆内存: ").append(formatMiB(peakHeapBytes)).append('\n');
        report.append("  报告时堆内存: ").append(formatMiB(heapBytes));
        return report.toString();
    }

    /**
     * Clears all phase elapsed times, tick samples and memory samples, and restarts timing from the
     * current moment.
     *
     * <p>Used to discard old data before reusing the same diagnostics instance for the next scan; after
     * the call {@link #stageMillis()} is empty and {@link #totalMillis()}, {@link #tickCount()} and
     * {@link #peakHeapBytes()} are all 0.</p>
     */
    public void reset() {
        stageNanos.clear();
        tickNanos.clear();
        totalTickNanos = 0L;
        maxTickNanos = 0L;
        peakHeapBytes = 0L;
        heapBytes = 0L;
        long now = System.nanoTime();
        lastMarkNanos = now;
        scanStartedNanos = now;
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0L, bytes) / BYTES_PER_MIB) + " MiB";
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", Math.max(0L, nanos) / 1_000_000.0) + " ms";
    }

    private static String formatMillis(double nanos) {
        return String.format(Locale.ROOT, "%.2f", Math.max(0.0D, nanos) / 1_000_000.0) + " ms";
    }
}
