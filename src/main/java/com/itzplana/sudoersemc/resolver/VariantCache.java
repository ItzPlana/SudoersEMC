package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Bounded LRU variant-price cache.
 *
 * <p>The cache holds at most {@code maxEntries} {@link ItemKey}s. When full, the least-recently-used
 * entry is evicted. All methods that touch mutable state synchronise on the same lock, so it is safe to
 * access from multiple threads; hit/miss counters count only {@link #get(ItemKey)} calls, and
 * {@link #clear()} resets the counters too.</p>
 *
 * <p>Capacity is counted by entries, not by the byte length of an individual {@code variantHash};
 * callers must still cap variant-hash length so oversized container NBT does not consume too much
 * memory.</p>
 */
public final class VariantCache {

    /**
     * Default entry limit used when no capacity is specified explicitly.
     */
    public static final int DEFAULT_MAX_ENTRIES = 4096;

    private final int maxEntries;
    private final LinkedHashMap<ItemKey, Long> entries;
    private long hits;
    private long misses;

    /**
     * @param maxEntries maximum cached entries, must be >= 1
     * @throws IllegalArgumentException when {@code maxEntries < 1}
     */
    public VariantCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ItemKey, Long> eldest) {
                return size() > VariantCache.this.maxEntries;
            }
        };
    }

    /**
     * Looks up the cache and updates hit/miss counters.
     *
     * @param key item key
     * @return the cached EMC; empty when absent
     */
    public synchronized OptionalLong get(ItemKey key) {
        Objects.requireNonNull(key, "key");
        Long value = entries.get(key);
        if (value == null) {
            misses++;
            return OptionalLong.empty();
        }
        hits++;
        return OptionalLong.of(value);
    }

    /**
     * Writes or updates an entry.
     *
     * @param key   item key
     * @param value non-negative EMC
     */
    public synchronized void put(ItemKey key, long value) {
        Objects.requireNonNull(key, "key");
        entries.put(key, value);
    }

    /**
     * @return current cached entry count, never above {@link #maxEntries()}
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * Clears the cache and zeroes the hit/miss counters.
     */
    public synchronized void clear() {
        entries.clear();
        hits = 0L;
        misses = 0L;
    }

    /**
     * @return successful hits since the last clear
     */
    public synchronized long hitCount() {
        return hits;
    }

    /**
     * @return misses since the last clear
     */
    public synchronized long missCount() {
        return misses;
    }

    /**
     * @return the capacity limit given at construction
     */
    public int maxEntries() {
        return maxEntries;
    }
}
