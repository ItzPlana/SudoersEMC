package com.itzplana.sudoersemc.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * {@link DebugLog} 的开关与限流测试。使用固定时钟避免跨越真实秒窗口造成抖动。
 */
class DebugLogTest {

    @AfterEach
    void tearDown() {
        DebugLog.resetForTesting();
    }

    @Test
    void debugDisabledDoesNotEvaluateSupplier() {
        DebugLog.setEnabled(false);
        AtomicInteger calls = new AtomicInteger();

        DebugLog.log("test", () -> {
            calls.incrementAndGet();
            return "expensive";
        });
        DebugLog.sampled("test", 0L, 1L, () -> {
            calls.incrementAndGet();
            return "sampled";
        });
        DebugLog.headOrSampled("test", 0L, 20L, 500L, () -> {
            calls.incrementAndGet();
            return "head";
        });

        assertEquals(0, calls.get());
        assertEquals("", DebugLog.summary());
    }

    @Test
    void debugRateLimitCapsBurst() {
        DebugLog.resetForTesting();
        DebugLog.setNanoClockForTesting(() -> 1_000_000_000L);
        DebugLog.setEnabled(true);

        for (int i = 0; i < 1000; i++) {
            DebugLog.log("test-rate", "message");
        }

        assertEquals(DebugLog.MAX_PER_SECOND, DebugLog.emittedCount("test-rate"));
        assertTrue(DebugLog.suppressedCount("test-rate") > 0L);
        assertEquals(1000L, DebugLog.totalCount("test-rate"));
        String summary = DebugLog.summary();
        assertTrue(summary.contains("category=test-rate"), summary);
        assertTrue(summary.contains("suppressed="), summary);
    }
}
