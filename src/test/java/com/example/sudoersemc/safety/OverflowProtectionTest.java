package com.example.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.sudoersemc.core.OverflowScope;
import org.junit.jupiter.api.Test;

class OverflowProtectionTest {

    private static final long THRESHOLD = 999_999L;
    private static final long RESET_MIN = 100L;
    private static final long RESET_MAX = 999_000L;

    private static OverflowProtection.Settings settings(long seed) {
        return new OverflowProtection.Settings(
                true,
                THRESHOLD,
                RESET_MIN,
                RESET_MAX,
                100,
                20,
                OverflowScope.OWNED_ONLY,
                false,
                seed);
    }

    @Test
    void valueAtThresholdIsUntouched() {
        OverflowProtection protection = new OverflowProtection(settings(1L));

        assertEquals(THRESHOLD, protection.apply(THRESHOLD));
        assertEquals(THRESHOLD - 1L, protection.apply(THRESHOLD - 1L));
        assertEquals(0L, protection.apply(0L));
        assertEquals(42L, protection.apply(42L));
    }

    @Test
    void valueAboveThresholdIsReset() {
        OverflowProtection protection = new OverflowProtection(settings(1L));

        long reset = protection.apply(THRESHOLD + 1L);

        assertTrue(reset >= RESET_MIN, "reset " + reset + " below resetMin");
        assertTrue(reset <= RESET_MAX, "reset " + reset + " above resetMax");
        assertTrue(reset <= THRESHOLD);
        assertFalse(reset == THRESHOLD + 1L, "overflowing value must actually be replaced");
    }

    @Test
    void resetResultsAlwaysStayInsideResetRange() {
        OverflowProtection protection = new OverflowProtection(settings(987654321L));

        for (int sample = 0; sample < 10_000; sample++) {
            long reset = protection.apply(THRESHOLD + 1L + sample);
            assertTrue(reset >= RESET_MIN,
                    "reset " + reset + " below resetMin on sample " + sample);
            assertTrue(reset <= RESET_MAX,
                    "reset " + reset + " above resetMax on sample " + sample);
        }
    }

    @Test
    void idempotentApplyDoesNotRerandomizeResetValues() {
        OverflowProtection protection = new OverflowProtection(settings(2L));
        long reset = protection.apply(THRESHOLD + 100L);

        assertTrue(reset >= RESET_MIN && reset <= RESET_MAX);
        assertEquals(reset, protection.applyIdempotent(reset));
        assertEquals(reset, protection.applyIdempotent(reset));
        assertEquals(RESET_MIN, protection.applyIdempotent(RESET_MIN));
        assertEquals(RESET_MAX, protection.applyIdempotent(RESET_MAX));
        assertEquals(THRESHOLD, protection.applyIdempotent(THRESHOLD));

        long outsideRange = protection.applyIdempotent(THRESHOLD + 1L);
        assertTrue(outsideRange >= RESET_MIN && outsideRange <= RESET_MAX);
    }

    @Test
    void idempotentApplyIsStableAcrossManyRuns() {
        OverflowProtection protection = new OverflowProtection(settings(3L));
        long value = protection.apply(THRESHOLD + 1L);

        for (int run = 0; run < 1_000; run++) {
            long next = protection.applyIdempotent(value);
            assertEquals(value, next, "applyIdempotent must be stable after the first reset");
        }
    }

    @Test
    void disabledProtectionLeavesOverflowValuesUntouched() {
        OverflowProtection.Settings disabled = new OverflowProtection.Settings(
                false,
                THRESHOLD,
                RESET_MIN,
                RESET_MAX,
                100,
                20,
                OverflowScope.OWNED_ONLY,
                false,
                1L);
        OverflowProtection protection = new OverflowProtection(disabled);

        assertEquals(Long.MAX_VALUE, protection.apply(Long.MAX_VALUE));
        assertEquals(Long.MAX_VALUE, protection.applyIdempotent(Long.MAX_VALUE));
    }

    @Test
    void isOverflowUsesStrictThreshold() {
        OverflowProtection protection = new OverflowProtection(settings(1L));

        assertFalse(protection.isOverflow(THRESHOLD - 1L));
        assertFalse(protection.isOverflow(THRESHOLD));
        assertTrue(protection.isOverflow(THRESHOLD + 1L));
    }

    @Test
    void sameSeedProducesSameResetSequence() {
        OverflowProtection first = new OverflowProtection(settings(1234L));
        OverflowProtection second = new OverflowProtection(settings(1234L));

        for (int sample = 0; sample < 1_000; sample++) {
            long value = THRESHOLD + 1L + sample;
            assertEquals(first.apply(value), second.apply(value));
        }
    }

    @Test
    void differentSeedsProduceDifferentResetSequences() {
        OverflowProtection first = new OverflowProtection(settings(1L));
        OverflowProtection second = new OverflowProtection(settings(2L));
        boolean sawDifference = false;

        for (int sample = 0; sample < 128 && !sawDifference; sample++) {
            long value = THRESHOLD + 1L + sample;
            if (first.apply(value) != second.apply(value)) {
                sawDifference = true;
            }
        }

        assertTrue(sawDifference, "different seeds should produce different reset sequences");
    }

    @Test
    void defaultSettingsMatchDocumentedDefaults() {
        OverflowProtection.Settings defaults = new OverflowProtection.Settings();

        assertTrue(defaults.enabled());
        assertEquals(999_999L, defaults.threshold());
        assertEquals(100L, defaults.resetMin());
        assertEquals(999_000L, defaults.resetMax());
        assertEquals(100, defaults.bucketCount());
        assertEquals(20, defaults.weightCap());
        assertEquals(OverflowScope.OWNED_ONLY, defaults.scope());
        assertFalse(defaults.allowProjectEFileRewrite());
        assertEquals(0L, defaults.randomSeed());
    }

    @Test
    void invalidSettingsAreRejected() {
        IllegalArgumentException thresholdNotAboveResetMax = assertThrows(
                IllegalArgumentException.class,
                () -> new OverflowProtection.Settings(
                        true, RESET_MAX, RESET_MIN, RESET_MAX, 100, 20,
                        OverflowScope.OWNED_ONLY, false, 1L));
        assertTrue(thresholdNotAboveResetMax.getMessage().contains("threshold"));

        assertThrows(IllegalArgumentException.class,
                () -> new OverflowProtection.Settings(
                        true, THRESHOLD, 0L, RESET_MAX, 100, 20,
                        OverflowScope.OWNED_ONLY, false, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new OverflowProtection.Settings(
                        true, THRESHOLD, 500L, 100L, 100, 20,
                        OverflowScope.OWNED_ONLY, false, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new OverflowProtection.Settings(
                        true, THRESHOLD, RESET_MIN, RESET_MAX, 0, 20,
                        OverflowScope.OWNED_ONLY, false, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new OverflowProtection.Settings(
                        true, THRESHOLD, RESET_MIN, RESET_MAX, 100, 0,
                        OverflowScope.OWNED_ONLY, false, 1L));
    }
}
