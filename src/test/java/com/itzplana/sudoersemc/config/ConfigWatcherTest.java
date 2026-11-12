package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ConfigWatcherTest {

    @Test
    void listenerIsFiredOnValidResult() {
        ConfigWatcher watcher = new ConfigWatcher();
        AtomicInteger calls = new AtomicInteger();
        List<ConfigValidationResult> received = new ArrayList<>();
        watcher.register(result -> {
            calls.incrementAndGet();
            received.add(result);
        });

        ConfigValidationResult valid = ConfigValidationResult.ok(List.of("注意"));
        watcher.fireReload(valid);

        assertEquals(1, calls.get());
        assertEquals(1, received.size());
        assertSame(valid, received.get(0));
        assertSame(valid, watcher.lastResult());
        assertEquals(1, watcher.listenerCount());
    }

    @Test
    void listenerIsNotFiredOnInvalidResult() {
        ConfigWatcher watcher = new ConfigWatcher();
        AtomicInteger calls = new AtomicInteger();
        watcher.register(result -> calls.incrementAndGet());

        ConfigValidationResult invalid = ConfigValidationResult.failed(List.of("boom"), List.of());
        watcher.fireReload(invalid);

        assertEquals(0, calls.get());
        assertSame(invalid, watcher.lastResult());
        assertFalse(watcher.lastResult().valid());
    }

    @Test
    void duplicateWarningsAreLoggedOnce() {
        ConfigWatcher watcher = new ConfigWatcher();
        ConfigValidationResult result = ConfigValidationResult.ok(List.of("w1", "w2", "w1"));

        watcher.logConflictWarnings(result);
        assertEquals(2, watcher.loggedWarningCount());

        watcher.logConflictWarnings(result);
        assertEquals(2, watcher.loggedWarningCount());

        watcher.logConflictWarnings(ConfigValidationResult.ok(List.of("w3")));
        assertEquals(3, watcher.loggedWarningCount());
    }

    @Test
    void lastResultDefaultsToValidEmptyResult() {
        ConfigWatcher watcher = new ConfigWatcher();

        assertTrue(watcher.lastResult().valid());
        assertTrue(watcher.lastResult().errors().isEmpty());
        assertTrue(watcher.lastResult().warnings().isEmpty());
        assertEquals(0, watcher.listenerCount());
    }

    @Test
    void listenerExceptionDoesNotPropagateOrBlockOtherListeners() {
        ConfigWatcher watcher = new ConfigWatcher();
        AtomicInteger secondCalls = new AtomicInteger();
        watcher.register(result -> {
            throw new IllegalStateException("listener failure");
        });
        watcher.register(result -> secondCalls.incrementAndGet());

        assertDoesNotThrow(() -> watcher.fireReload(ConfigValidationResult.ok()));

        assertEquals(1, secondCalls.get());
    }

    @Test
    void nullListenerIsRejected() {
        ConfigWatcher watcher = new ConfigWatcher();

        assertThrows(NullPointerException.class, () -> watcher.register(null));
        assertEquals(0, watcher.listenerCount());
    }

    @Test
    void repeatedIdenticalInvalidResultsAreLoggedOnce() {
        ConfigWatcher watcher = new ConfigWatcher();
        ConfigValidationResult invalid = ConfigValidationResult.failed(
                List.of("boom"), List.of("warn"));

        watcher.fireReload(invalid);
        watcher.fireReload(invalid);

        assertEquals(1, watcher.loggedWarningCount());

        watcher.fireReload(ConfigValidationResult.failed(List.of("other"), List.of()));
        assertEquals(2, watcher.loggedWarningCount());
    }
}
