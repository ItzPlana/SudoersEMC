package com.itzplana.sudoersemc.task;

/**
 * Pure decision component between fixed and adaptive batching.
 *
 * <p>This class depends on no Minecraft type and performs no scan work. It only recommends the next
 * batch size from the previous batch's actual elapsed time and the per-tick budget; after the real work
 * is done on the server thread, the actual batch size and elapsed time are written back via
 * {@link #recordBatch(int, long)}.</p>
 *
 * <h2>Adaptive algorithm</h2>
 * <ul>
 *   <li><strong>Fixed mode:</strong> {@link #nextBatchSize(long, long)} always returns the
 *       {@code configuredItemsPerTick} passed at construction, ignoring elapsed-time samples entirely.</li>
 *   <li><strong>No valid sample:</strong> in adaptive mode, when the previous batch elapsed time or
 *       budget is <= 0, the current batch size is kept to avoid a wrong adjustment on insufficient data.</li>
 *   <li><strong>Grow:</strong> when the previous batch elapsed time is at most 50% of the budget, grow by
 *       25% via {@code size * 5 / 4} (integer division truncates toward zero).</li>
 *   <li><strong>Shrink:</strong> when the previous batch elapsed time strictly exceeds 100% of the budget,
 *       shrink by 50% via {@code size / 2} (integer division truncates toward zero).</li>
 *   <li><strong>Hold:</strong> when elapsed time falls in {@code (50%, 100%]}, keep the current batch size.</li>
 *   <li><strong>Bounds:</strong> grow and shrink results are clamped to
 *       {@code [1, configuredItemsPerTick * 8]}. The lower bound prevents a zero batch stalling the scan;
 *       the upper bound prevents an over-long single-tick server pause. {@code configuredItemsPerTick * 8}
 *       is computed in 64 bits and saturates near {@link Integer#MAX_VALUE}.</li>
 * </ul>
 *
 * <p>{@link #nextBatchSize(long, long)} does not modify internal state; only
 * {@link #recordBatch(int, long)} advances the batch size and batch count. This separation lets callers
 * ask for a suggested batch, do the work, then record the actual result.</p>
 */
public final class BatchProcessor {

    /** Growth factor {@code 5 / 4}, i.e. a 25% increase. */
    private static final int GROWTH_NUMERATOR = 5;
    private static final int GROWTH_DENOMINATOR = 4;

    /** Upper-bound multiplier for the adaptive batch size. */
    private static final int MAX_MULTIPLIER = 8;

    private final int configuredItemsPerTick;
    private final boolean adaptive;

    private int currentBatchSize;
    private long batchesProcessed;
    private long lastBatchNanos;

    /**
     * @param configuredItemsPerTick configured base per-tick amount, must be >= 1
     * @param adaptive               whether to auto-adjust the batch size from elapsed-time samples
     * @throws IllegalArgumentException when {@code configuredItemsPerTick < 1}
     */
    public BatchProcessor(int configuredItemsPerTick, boolean adaptive) {
        if (configuredItemsPerTick < 1) {
            throw new IllegalArgumentException(
                    "configuredItemsPerTick 必须 >= 1，实际为 " + configuredItemsPerTick);
        }
        this.configuredItemsPerTick = configuredItemsPerTick;
        this.adaptive = adaptive;
        this.currentBatchSize = configuredItemsPerTick;
    }

    /**
     * Computes the suggested amount for the next batch.
     *
     * <p>Non-adaptive mode returns the configured value directly. Adaptive mode decides grow, shrink or
     * hold based on the ratio of {@code lastBatchNanos} to {@code budgetNanos}; see the class docs for the
     * exact rules. The method has no side effects — repeated calls in the same state yield the same
     * result; only {@link #recordBatch(int, long)} changes the base batch size for later calculations.</p>
     *
     * @param lastBatchNanos previous batch actual elapsed time (nanos); <= 0 means no valid sample
     * @param budgetNanos    time budget allowed for scanning this tick (nanos); <= 0 means budget unknown
     * @return suggested batch size, always in {@code [1, configuredItemsPerTick * 8]}
     */
    public int nextBatchSize(long lastBatchNanos, long budgetNanos) {
        if (!adaptive) {
            return configuredItemsPerTick;
        }
        if (lastBatchNanos <= 0L || budgetNanos <= 0L) {
            return currentBatchSize;
        }

        long maximum = maximumBatchSize();
        // Compare against budgetNanos / 2 to avoid overflowing lastBatchNanos * 2.
        if (lastBatchNanos <= budgetNanos / 2L) {
            // Grow by at least 1: under integer division size=1 gives 1*5/4 == 1, which would stop the adaptive batch ever growing.
            long grown = Math.max((long) currentBatchSize + 1L,
                    (long) currentBatchSize * GROWTH_NUMERATOR / GROWTH_DENOMINATOR);
            return (int) clamp(grown, maximum);
        }
        if (lastBatchNanos > budgetNanos) {
            long shrunk = currentBatchSize / 2L;
            return (int) clamp(shrunk, maximum);
        }
        return currentBatchSize;
    }

    /**
     * Records the actual result of one batch of work for later adaptive calculations.
     *
     * <p>The batch size is clamped to {@code [1, configuredItemsPerTick * 8]}; a negative elapsed time is
     * treated as 0. Each call increments {@link #batchesProcessed()} by one.</p>
     *
     * @param size         actual amount processed this batch
     * @param elapsedNanos actual elapsed time this batch (nanos)
     */
    public void recordBatch(int size, long elapsedNanos) {
        this.currentBatchSize = (int) clamp(size, maximumBatchSize());
        this.lastBatchNanos = Math.max(0L, elapsedNanos);
        this.batchesProcessed++;
    }

    /**
     * @return the current adaptive base batch size; equals the configured value before any batch is recorded
     */
    public int currentBatchSize() {
        return currentBatchSize;
    }

    /**
     * @return total number of batches recorded
     */
    public long batchesProcessed() {
        return batchesProcessed;
    }

    /**
     * @return the base amount configured at construction
     */
    public int configuredItemsPerTick() {
        return configuredItemsPerTick;
    }

    /**
     * @return whether adaptive batching is enabled
     */
    public boolean adaptive() {
        return adaptive;
    }

    /**
     * @return the most recently recorded batch elapsed time (nanos); 0 before any recording
     */
    public long lastBatchNanos() {
        return lastBatchNanos;
    }

    /**
     * Computes the adaptive batch upper bound, using a 64-bit intermediate to avoid overflowing
     * {@code configuredItemsPerTick * 8}.
     */
    private int maximumBatchSize() {
        long maximum = (long) configuredItemsPerTick * MAX_MULTIPLIER;
        return (int) Math.min(maximum, Integer.MAX_VALUE);
    }

    /**
     * Clamps the batch size to {@code [1, maximum]}.
     */
    private static long clamp(long value, long maximum) {
        return Math.max(1L, Math.min(maximum, value));
    }
}
