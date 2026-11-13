package com.itzplana.sudoersemc.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.OptionalLong;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link VariantCache}, covering counting, LRU eviction and clear semantics.
 */
class VariantCacheTest {

    private static final ItemKey FIRST = ItemKey.of(new ResourceLocation("sudoersemc", "first"));
    private static final ItemKey SECOND = ItemKey.of(new ResourceLocation("sudoersemc", "second"));
    private static final ItemKey THIRD = ItemKey.of(new ResourceLocation("sudoersemc", "third"));
    private static final ItemKey FOURTH = ItemKey.of(new ResourceLocation("sudoersemc", "fourth"));

    @Test
    void hitAndMissCountersAreAccurate() {
        VariantCache cache = new VariantCache(4);

        assertTrue(cache.get(FIRST).isEmpty());
        assertEquals(1L, cache.missCount());
        assertEquals(0L, cache.hitCount());

        cache.put(FIRST, 7L);

        assertEquals(OptionalLong.of(7L), cache.get(FIRST));
        assertEquals(1L, cache.hitCount());
        assertEquals(1L, cache.missCount());
    }

    @Test
    void evictsTheLeastRecentlyUsedEntryAtCapacity() {
        VariantCache cache = new VariantCache(3);
        cache.put(FIRST, 1L);
        cache.put(SECOND, 2L);
        cache.put(THIRD, 3L);
        assertEquals(3, cache.size());

        cache.put(FOURTH, 4L);

        assertEquals(3, cache.size());
        assertTrue(cache.get(FIRST).isEmpty());
        assertTrue(cache.get(SECOND).isPresent());
        assertTrue(cache.get(THIRD).isPresent());
        assertTrue(cache.get(FOURTH).isPresent());
    }

    @Test
    void accessRefreshesRecency() {
        VariantCache cache = new VariantCache(2);
        cache.put(FIRST, 1L);
        cache.put(SECOND, 2L);

        cache.get(FIRST);
        cache.put(THIRD, 3L);

        assertTrue(cache.get(SECOND).isEmpty());
        assertTrue(cache.get(FIRST).isPresent());
        assertTrue(cache.get(THIRD).isPresent());
    }

    @Test
    void repeatedHitsDoNotGrowSize() {
        VariantCache cache = new VariantCache(2);
        cache.put(FIRST, 5L);

        for (int i = 0; i < 100; i++) {
            assertEquals(OptionalLong.of(5L), cache.get(FIRST));
        }

        assertEquals(1, cache.size());
        assertEquals(100L, cache.hitCount());
        assertEquals(0L, cache.missCount());
    }

    @Test
    void clearResetsSizeAndCounters() {
        VariantCache cache = new VariantCache(2);
        cache.put(FIRST, 1L);
        cache.get(FIRST);
        cache.get(SECOND);

        cache.clear();

        assertEquals(0, cache.size());
        assertEquals(0L, cache.hitCount());
        assertEquals(0L, cache.missCount());
        assertTrue(cache.get(FIRST).isEmpty());
    }

    @Test
    void invalidCapacityIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> new VariantCache(0));
        assertThrows(IllegalArgumentException.class, () -> new VariantCache(-5));
    }

    @Test
    void defaultCapacityConstantIsExposed() {
        VariantCache cache = new VariantCache(VariantCache.DEFAULT_MAX_ENTRIES);
        assertEquals(4096, cache.maxEntries());
    }
}
