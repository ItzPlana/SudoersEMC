package com.itzplana.sudoersemc.scanner;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Log helper rate-limited by "level + key".
 *
 * <p>The same key emits at most once per level, avoiding a broken third-party integration flooding the
 * log on every scan or every {@code rescan-all}. The key set uses a concurrent collection, so a static
 * instance can be safely shared across reader instances.</p>
 *
 * <p>This class serves only internal scanner diagnostics; tests can verify rate-limiting via
 * {@link #emittedCount()} and {@link #hasEmitted(String, String)}.</p>
 */
final class OnceLogger {

    private final Logger logger;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * @param logger the SLF4J logger that actually emits
     */
    OnceLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * Emits the given key at warn level the first time; subsequent repeated calls emit nothing.
     *
     * @param key     stable event key
     * @param message log content
     * @param cause   failure cause, may be {@code null}
     * @return whether this call actually emitted
     */
    boolean warn(String key, String message, @Nullable Throwable cause) {
        if (!seen.add("W|" + key)) {
            return false;
        }
        logger.warn(message, cause);
        return true;
    }

    /**
     * Emits the given key at debug level the first time; subsequent repeated calls emit nothing.
     *
     * @param key     stable event key
     * @param message log content
     * @param cause   failure cause, may be {@code null}
     * @return whether this call actually emitted
     */
    boolean debug(String key, String message, @Nullable Throwable cause) {
        if (!seen.add("D|" + key)) {
            return false;
        }
        logger.debug(message, cause);
        return true;
    }

    /**
     * @param level level marker, {@code "W"} or {@code "D"}
     * @param key   event key
     * @return whether this level+key combination has already emitted
     */
    boolean hasEmitted(String level, String key) {
        return seen.contains(level + "|" + key);
    }

    /**
     * @return the number of distinct "level + key" pairs recorded
     */
    int emittedCount() {
        return seen.size();
    }

    /**
     * Clears rate-limit state. For unit-test isolation only.
     */
    void reset() {
        seen.clear();
    }
}
