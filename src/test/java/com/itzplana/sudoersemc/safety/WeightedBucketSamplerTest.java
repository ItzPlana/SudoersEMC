package com.itzplana.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class WeightedBucketSamplerTest {

    private static final long MIN = 100L;
    private static final long MAX = 999_000L;
    private static final int BUCKETS = 100;
    private static final int WEIGHT_CAP = 20;

    private static WeightedBucketSampler defaultSampler(long seed) {
        return new WeightedBucketSampler(MIN, MAX, BUCKETS, WEIGHT_CAP, seed);
    }

    @Test
    void weightsAreClampedAndEveryBucketHasPositiveWeight() {
        WeightedBucketSampler sampler = defaultSampler(123456789L);
        int[] weights = sampler.weights();

        assertEquals(BUCKETS, weights.length);
        for (int bucket = 0; bucket < weights.length; bucket++) {
            assertTrue(weights[bucket] >= 1,
                    "bucket " + bucket + " weight must be >= 1, was " + weights[bucket]);
            assertTrue(weights[bucket] <= WEIGHT_CAP,
                    "bucket " + bucket + " weight must be <= " + WEIGHT_CAP + ", was " + weights[bucket]);
        }
        assertEquals(WEIGHT_CAP, weights[0], "lowest bucket should carry the full cap");
        assertEquals(1, weights[weights.length - 1], "highest bucket should carry the minimum weight");
    }

    @Test
    void everyBucketIsReachable() {
        WeightedBucketSampler sampler = defaultSampler(987654321L);
        boolean[] seen = new boolean[sampler.bucketCount()];

        for (int sample = 0; sample < 50_000; sample++) {
            long value = sampler.sample();
            assertTrue(value >= MIN && value <= MAX,
                    "sample " + value + " escaped [" + MIN + ", " + MAX + "]");
            seen[sampler.bucketIndex(value)] = true;
        }

        for (int bucket = 0; bucket < seen.length; bucket++) {
            assertTrue(seen[bucket], "bucket " + bucket + " was never sampled");
        }
    }

    @Test
    void noBucketDominatesTheDistribution() {
        WeightedBucketSampler sampler = defaultSampler(42424242L);
        int sampleCount = 50_000;
        int[] counts = new int[sampler.bucketCount()];

        for (int sample = 0; sample < sampleCount; sample++) {
            counts[sampler.bucketIndex(sampler.sample())]++;
        }

        double allowedFrequency = (double) WEIGHT_CAP / (double) sampler.totalWeight() + 0.15D;
        for (int bucket = 0; bucket < counts.length; bucket++) {
            double frequency = (double) counts[bucket] / (double) sampleCount;
            assertTrue(frequency <= allowedFrequency,
                    "bucket " + bucket + " frequency " + frequency + " exceeded " + allowedFrequency);
        }

        double topBucketFrequency = (double) counts[counts.length - 1] / (double) sampleCount;
        assertTrue(topBucketFrequency < 0.40D,
                "top bucket frequency " + topBucketFrequency + " is too dominant");
        assertTrue(counts[counts.length - 1] > 0, "top bucket must remain reachable");
    }

    @Test
    void maxValueIsNeverTheOnlyResult() {
        WeightedBucketSampler sampler = defaultSampler(13579L);
        int sampleCount = 10_000;
        int maxHits = 0;

        for (int sample = 0; sample < sampleCount; sample++) {
            if (sampler.sample() == MAX) {
                maxHits++;
            }
        }

        assertTrue(maxHits < sampleCount * 0.40D,
                "max value was returned " + maxHits + " times; it must not dominate");
    }

    @Test
    void sameSeedProducesIdenticalSequence() {
        WeightedBucketSampler first = defaultSampler(2024L);
        WeightedBucketSampler second = defaultSampler(2024L);

        assertEquals(first.seed(), second.seed());
        for (int sample = 0; sample < 1_000; sample++) {
            assertEquals(first.sample(), second.sample());
        }
    }

    @Test
    void differentSeedsProduceDifferentSequences() {
        WeightedBucketSampler first = defaultSampler(1L);
        WeightedBucketSampler second = defaultSampler(2L);
        long[] firstValues = new long[128];
        long[] secondValues = new long[128];

        for (int sample = 0; sample < firstValues.length; sample++) {
            firstValues[sample] = first.sample();
            secondValues[sample] = second.sample();
        }

        assertFalse(Arrays.equals(firstValues, secondValues),
                "different seeds should not produce identical sequences");
    }

    @Test
    void zeroSeedIsResolvedToAConcreteSeed() {
        WeightedBucketSampler sampler = defaultSampler(0L);

        assertNotEquals(0L, sampler.seed());
        assertNotEquals(0L, defaultSampler(0L).seed());
    }

    @Test
    void seedIsExposedUnchangedForDeterministicMode() {
        assertEquals(314159L, defaultSampler(314159L).seed());
    }

    @Test
    void singleBucketIsSupported() {
        WeightedBucketSampler sampler = new WeightedBucketSampler(MIN, MAX, 1, WEIGHT_CAP, 7L);

        assertEquals(1, sampler.bucketCount());
        assertEquals(1, sampler.weights().length);
        assertEquals(WEIGHT_CAP, sampler.weights()[0]);
        assertEquals(0, sampler.bucketIndex(MIN));
        assertEquals(0, sampler.bucketIndex(MAX));

        for (int sample = 0; sample < 10_000; sample++) {
            long value = sampler.sample();
            assertTrue(value >= MIN && value <= MAX);
        }
    }

    @Test
    void minEqualsMaxCollapsesToSingleValue() {
        WeightedBucketSampler sampler = new WeightedBucketSampler(500L, 500L, BUCKETS, WEIGHT_CAP, 11L);

        assertEquals(1, sampler.bucketCount());
        assertEquals(500L, sampler.bucketStart(0));
        assertEquals(500L, sampler.bucketEnd(0));
        for (int sample = 0; sample < 1_000; sample++) {
            assertEquals(500L, sampler.sample());
        }
    }

    @Test
    void bucketsAreContiguousAndCoverTheWholeRange() {
        WeightedBucketSampler sampler = defaultSampler(99L);

        assertEquals(MIN, sampler.bucketStart(0));
        assertEquals(MAX, sampler.bucketEnd(sampler.bucketCount() - 1));
        for (int bucket = 0; bucket < sampler.bucketCount(); bucket++) {
            assertTrue(sampler.bucketStart(bucket) <= sampler.bucketEnd(bucket),
                    "bucket " + bucket + " must not be empty");
            if (bucket > 0) {
                assertEquals(sampler.bucketEnd(bucket - 1) + 1L, sampler.bucketStart(bucket),
                        "buckets must be contiguous");
            }
        }
    }

    @Test
    void invalidConfigurationsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedBucketSampler(10L, 9L, 10, 20, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedBucketSampler(1L, 10L, 0, 20, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new WeightedBucketSampler(1L, 10L, 10, 0, 1L));
    }
}
