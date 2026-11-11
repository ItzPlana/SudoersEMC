package com.example.sudoersemc.safety;

import com.example.sudoersemc.core.OverflowScope;
import java.util.Objects;

/**
 * Pure-Java overflow policy: values strictly above a configured threshold are replaced by a
 * weighted random value from a conservative reset range.
 *
 * <p>This class performs no persistence and has no Minecraft dependencies. File rewrites and
 * reset auditing are handled by other layers; {@link OverflowReset} is the audit record those
 * layers should emit.</p>
 *
 * <p>{@link #apply(long)} is the raw policy operation. When values may be fed back into the
 * protection (for example when reprocessing persisted entries), prefer
 * {@link #applyIdempotent(long)}, which leaves values already inside the reset range untouched.</p>
 */
public final class OverflowProtection {

    public static final long DEFAULT_THRESHOLD = 999_999L;
    public static final long DEFAULT_RESET_MIN = 100L;
    public static final long DEFAULT_RESET_MAX = 999_000L;
    public static final int DEFAULT_BUCKET_COUNT = 100;
    public static final int DEFAULT_WEIGHT_CAP = 20;

    private final Settings settings;
    private final WeightedBucketSampler sampler;

    public OverflowProtection(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.sampler = new WeightedBucketSampler(
                settings.resetMin(),
                settings.resetMax(),
                settings.bucketCount(),
                settings.weightCap(),
                settings.randomSeed());
    }

    public Settings settings() {
        return settings;
    }

    /**
     * Applies the overflow policy.
     *
     * <p>Returns {@code currentValue} unchanged when protection is disabled or the value is at or
     * below the threshold. Otherwise returns a sampled value inside
     * {@code [resetMin, resetMax]}.</p>
     *
     * <p>This method intentionally does not guard against values that are already reset values;
     * callers that reprocess stored values must use {@link #applyIdempotent(long)} instead (or
     * otherwise avoid feeding reset values back through this method).</p>
     */
    public long apply(long currentValue) {
        if (!settings.enabled() || currentValue <= settings.threshold()) {
            return currentValue;
        }
        return sampler.sample();
    }

    /**
     * Idempotent variant of {@link #apply(long)}.
     *
     * <p>Any value already inside {@code [resetMin, resetMax]} is returned unchanged, so running
     * overflow handling repeatedly over its own output is a no-op. Values outside the reset range
     * follow the normal {@link #apply(long)} policy.</p>
     */
    public long applyIdempotent(long currentValue) {
        if (currentValue >= settings.resetMin() && currentValue <= settings.resetMax()) {
            return currentValue;
        }
        return apply(currentValue);
    }

    /**
     * @return {@code true} when {@code value} is strictly above the configured threshold
     *
     * <p>This is a pure threshold predicate and deliberately does not consider
     * {@link Settings#enabled()}; {@link #apply(long)} makes the enablement decision.</p>
     */
    public boolean isOverflow(long value) {
        return value > settings.threshold();
    }

    /**
     * Overflow configuration.
     *
     * @param enabled                whether overflow protection is active
     * @param threshold              values strictly above this threshold are reset
     * @param resetMin               inclusive lower bound of the reset range
     * @param resetMax               inclusive upper bound of the reset range
     * @param bucketCount            number of buckets used by the reset sampler
     * @param weightCap              maximum weight of any single bucket
     * @param scope                  which entries may be reset
     * @param allowProjectEFileRewrite whether ProjectE's custom EMC file may be rewritten
     * @param randomSeed             sampler seed; {@code 0} requests a fresh random seed
     */
    public record Settings(
            boolean enabled,
            long threshold,
            long resetMin,
            long resetMax,
            int bucketCount,
            int weightCap,
            OverflowScope scope,
            boolean allowProjectEFileRewrite,
            long randomSeed
    ) {

        public Settings {
            Objects.requireNonNull(scope, "scope");
            if (threshold <= resetMax) {
                throw new IllegalArgumentException(
                        "threshold (" + threshold + ") must be greater than resetMax (" + resetMax + ")");
            }
            if (resetMin < 1L) {
                throw new IllegalArgumentException("resetMin (" + resetMin + ") must be >= 1");
            }
            if (resetMin > resetMax) {
                throw new IllegalArgumentException(
                        "resetMin (" + resetMin + ") must be <= resetMax (" + resetMax + ")");
            }
            if (bucketCount < 1) {
                throw new IllegalArgumentException("bucketCount (" + bucketCount + ") must be >= 1");
            }
            if (weightCap < 1) {
                throw new IllegalArgumentException("weightCap (" + weightCap + ") must be >= 1");
            }
        }

        /**
         * Default policy: enabled, 999999 ceiling, 100..999000 reset range, 100 buckets,
         * weight cap 20, owned-only scope, ProjectE file rewrite disabled, random seed.
         */
        public Settings() {
            this(
                    true,
                    DEFAULT_THRESHOLD,
                    DEFAULT_RESET_MIN,
                    DEFAULT_RESET_MAX,
                    DEFAULT_BUCKET_COUNT,
                    DEFAULT_WEIGHT_CAP,
                    OverflowScope.OWNED_ONLY,
                    false,
                    0L);
        }

        public Settings withRandomSeed(long newRandomSeed) {
            return new Settings(
                    enabled,
                    threshold,
                    resetMin,
                    resetMax,
                    bucketCount,
                    weightCap,
                    scope,
                    allowProjectEFileRewrite,
                    newRandomSeed);
        }
    }
}
