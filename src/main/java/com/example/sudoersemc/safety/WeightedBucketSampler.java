package com.example.sudoersemc.safety;

import java.util.SplittableRandom;

/**
 * Deterministic, weighted sampler over an inclusive numeric range.
 *
 * <p>The range is split into {@code bucketCount} contiguous buckets. Bucket {@code i} of
 * {@code n} (where {@code 0} is the lowest bucket) gets the integer weight</p>
 *
 * <pre>{@code
 * clamp(round(1 + (weightCap - 1) * (1 - i / (n - 1))^2), 1, weightCap)
 * }</pre>
 *
 * <p>so the distribution is biased toward conservative (lower) reset values while every bucket
 * remains reachable. A bucket is selected by integer weight and a value is then sampled uniformly
 * inside the selected bucket's inclusive bounds.</p>
 *
 * <p>The weighted curve uses the <em>effective</em> bucket count. When the range contains fewer
 * distinct values than the requested bucket count, the effective count is reduced to the number of
 * distinct values so that no bucket is empty and every reported bucket is reachable. This also
 * makes {@code min == max} a single bucket instead of a division by zero.</p>
 *
 * <p>This class is pure Java (no Minecraft imports) so it stays unit-testable. Instances are
 * <strong>not</strong> thread-safe.</p>
 */
public final class WeightedBucketSampler {

    private final long min;
    private final long max;
    private final int bucketCount;
    private final int weightCap;
    private final long seed;
    private final int[] weights;
    private final long[] bucketStarts;
    private final long[] bucketEnds;
    private final long totalWeight;
    private final SplittableRandom random;

    /**
     * Creates a sampler.
     *
     * @param min         inclusive lower bound
     * @param max         inclusive upper bound, must be {@code >= min}
     * @param bucketCount number of contiguous buckets, must be {@code >= 1}
     * @param weightCap   maximum weight of a single bucket, must be {@code >= 1}
     * @param seed        deterministic seed; {@code 0} requests a fresh random seed
     * @throws IllegalArgumentException if the bounds, bucket count or weight cap are invalid
     */
    public WeightedBucketSampler(long min, long max, int bucketCount, int weightCap, long seed) {
        if (min > max) {
            throw new IllegalArgumentException("min (" + min + ") must be <= max (" + max + ")");
        }
        if (bucketCount < 1) {
            throw new IllegalArgumentException("bucketCount must be >= 1, got " + bucketCount);
        }
        if (weightCap < 1) {
            throw new IllegalArgumentException("weightCap must be >= 1, got " + weightCap);
        }

        final long span;
        try {
            span = Math.addExact(Math.subtractExact(max, min), 1L);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(
                    "range [" + min + ", " + max + "] is too large to sample", overflow);
        }

        this.min = min;
        this.max = max;
        this.weightCap = weightCap;
        this.bucketCount = (int) Math.min((long) bucketCount, span);

        long resolvedSeed = seed;
        if (resolvedSeed == 0L) {
            do {
                resolvedSeed = new SplittableRandom().nextLong();
            } while (resolvedSeed == 0L);
        }
        this.seed = resolvedSeed;
        this.random = new SplittableRandom(resolvedSeed);

        this.weights = new int[this.bucketCount];
        this.bucketStarts = new long[this.bucketCount];
        this.bucketEnds = new long[this.bucketCount];

        // Distribute the span without multiplying two longs, which could overflow.
        long cursor = min;
        long baseSize = span / this.bucketCount;
        long remainder = span % this.bucketCount;
        long runningTotal = 0L;
        for (int i = 0; i < this.bucketCount; i++) {
            long size = baseSize + (i < remainder ? 1L : 0L);
            long start = cursor;
            long end = start + (size - 1L);
            this.bucketStarts[i] = start;
            this.bucketEnds[i] = end;
            if (i + 1 < this.bucketCount) {
                cursor = end + 1L;
            }

            int weight = weightForBucket(i, this.bucketCount, weightCap);
            this.weights[i] = weight;
            runningTotal += weight;
        }
        this.totalWeight = runningTotal;
    }

    /**
     * Computes the raw integer weight for a bucket.
     *
     * <p>For a single effective bucket there is no {@code i / (n - 1)} ratio to evaluate, so the
     * bucket receives the full cap. Otherwise the raw curve value is rounded and clamped into
     * {@code [1, weightCap]}.</p>
     */
    private static int weightForBucket(int index, int count, int cap) {
        if (count <= 1) {
            return cap;
        }
        double position = (double) index / (double) (count - 1);
        double distanceFromTop = 1.0 - position;
        long raw = Math.round(1.0 + (double) (cap - 1) * distanceFromTop * distanceFromTop);
        if (raw < 1L) {
            return 1;
        }
        if (raw > cap) {
            return cap;
        }
        return (int) raw;
    }

    /**
     * Samples one value from the inclusive range.
     *
     * @return a value in {@code [min, max]}
     */
    public long sample() {
        long ticket = random.nextLong(totalWeight);
        int bucket = bucketCount - 1;
        long cumulative = 0L;
        for (int i = 0; i < bucketCount; i++) {
            cumulative += weights[i];
            if (ticket < cumulative) {
                bucket = i;
                break;
            }
        }

        long start = bucketStarts[bucket];
        long width = bucketEnds[bucket] - start + 1L;
        long offset = random.nextLong(width);
        return start + offset;
    }

    /**
     * @return the effective bucket weights, always in {@code [1, weightCap]}
     */
    public int[] weights() {
        return weights.clone();
    }

    /**
     * @return the actual seed in use; never {@code 0}
     */
    public long seed() {
        return seed;
    }

    public long min() {
        return min;
    }

    public long max() {
        return max;
    }

    /**
     * @return the effective bucket count, which may be lower than the requested count when the
     *         range contains fewer distinct values than requested
     */
    public int bucketCount() {
        return bucketCount;
    }

    public int weightCap() {
        return weightCap;
    }

    public long totalWeight() {
        return totalWeight;
    }

    public long bucketStart(int bucketIndex) {
        checkBucketIndex(bucketIndex);
        return bucketStarts[bucketIndex];
    }

    public long bucketEnd(int bucketIndex) {
        checkBucketIndex(bucketIndex);
        return bucketEnds[bucketIndex];
    }

    /**
     * Maps a value in the range to its bucket index.
     *
     * @throws IllegalArgumentException if {@code value} is outside {@code [min, max]}
     */
    public int bucketIndex(long value) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(
                    "value " + value + " is outside [" + min + ", " + max + "]");
        }
        int low = 0;
        int high = bucketCount - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            if (value < bucketStarts[middle]) {
                high = middle - 1;
            } else if (value > bucketEnds[middle]) {
                low = middle + 1;
            } else {
                return middle;
            }
        }
        // Buckets are contiguous and non-empty, so this is unreachable.
        throw new IllegalStateException("value " + value + " did not map to a bucket");
    }

    private void checkBucketIndex(int bucketIndex) {
        if (bucketIndex < 0 || bucketIndex >= bucketCount) {
            throw new IndexOutOfBoundsException(
                    "bucket index " + bucketIndex + " outside [0, " + bucketCount + ")");
        }
    }
}
