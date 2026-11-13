package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Rate-limiting semantics tests for {@link OnceLogger}.
 */
class OnceLoggerTest {

    private final OnceLogger logger =
            new OnceLogger(LoggerFactory.getLogger("sudoersemc/test/OnceLogger"));

    @Test
    void sameWarnKeyIsEmittedOnlyOnce() {
        assertTrue(logger.warn("k", "message", null));
        assertFalse(logger.warn("k", "message", null));

        assertEquals(1, logger.emittedCount());
        assertTrue(logger.hasEmitted("W", "k"));
    }

    @Test
    void differentWarnKeysAreEmittedIndependently() {
        assertTrue(logger.warn("a", "message", null));
        assertTrue(logger.warn("b", "message", null));

        assertEquals(2, logger.emittedCount());
    }

    @Test
    void warnAndDebugForSameKeyAreIndependent() {
        assertTrue(logger.warn("k", "message", null));
        assertTrue(logger.debug("k", "message", null));

        assertEquals(2, logger.emittedCount());
        assertTrue(logger.hasEmitted("W", "k"));
        assertTrue(logger.hasEmitted("D", "k"));
    }

    @Test
    void resetAllowsEmissionAgain() {
        logger.warn("k", "message", null);
        logger.reset();

        assertEquals(0, logger.emittedCount());
        assertTrue(logger.warn("k", "message", null));
    }
}
