package com.itzplana.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OwnedEntrySink;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * {@link OwnershipManager} 的单元测试。Forge 未启动，归属存储与当前值后端均使用测试内的
 * 纯内存实现，重点验证“只根据持久化记录判断归属”以及所有失败路径都不向外抛异常。
 */
class OwnershipManagerTest {

    private static final ResourceLocation IRON_ID = new ResourceLocation("minecraft", "iron_ingot");
    private static final ResourceLocation GOLD_ID = new ResourceLocation("minecraft", "gold_ingot");
    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");
    private static final ResourceLocation EMERALD_ID = new ResourceLocation("minecraft", "emerald");
    private static final ResourceLocation RECIPE_ID =
            new ResourceLocation("sudoersemc", "iron_from_blocks");
    private static final long TIMESTAMP = 1_730_000_000_000L;

    private static final ItemKey IRON = ItemKey.of(IRON_ID);
    private static final ItemKey GOLD = ItemKey.of(GOLD_ID);
    private static final ItemKey DIAMOND = ItemKey.of(DIAMOND_ID);
    private static final ItemKey EMERALD = ItemKey.of(EMERALD_ID);

    @Test
    void nullItemIsNotOwned() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();

        assertEquals(OwnershipState.NOT_OWNED, new OwnershipManager(sink, reader).classify(null));
    }

    @Test
    void unknownItemIsNotOwned() {
        OwnershipManager manager = new OwnershipManager(new InMemorySink(), new FakeReader());

        assertEquals(OwnershipState.NOT_OWNED, manager.classify(IRON));
    }

    @Test
    void matchingValueIsOwnedByUs() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        reader.set(IRON, 100L);

        assertEquals(OwnershipState.OWNED_BY_US, new OwnershipManager(sink, reader).classify(IRON));
    }

    @Test
    void driftedValueIsExternallyModified() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        reader.set(IRON, 90L);

        assertEquals(
                OwnershipState.EXTERNALLY_MODIFIED,
                new OwnershipManager(sink, reader).classify(IRON));
    }

    @Test
    void nonOwnedEntryIsNotOwnedEvenWhenValueMatches() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, false));
        reader.set(IRON, 100L);

        assertEquals(OwnershipState.NOT_OWNED, new OwnershipManager(sink, reader).classify(IRON));
    }

    @Test
    void unavailableReaderYieldsUnknown() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        reader.set(IRON, 100L);
        reader.setAvailable(false);

        assertEquals(OwnershipState.UNKNOWN, new OwnershipManager(sink, reader).classify(IRON));
    }

    @Test
    void readerExceptionYieldsUnknown() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        reader.failOnRead(true);

        assertEquals(OwnershipState.UNKNOWN, new OwnershipManager(sink, reader).classify(IRON));
    }

    @Test
    void detectExternalModificationsReturnsOnlyDriftedOwnedItemsSorted() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        sink.put(entry(GOLD, 200L, true));
        sink.put(entry(DIAMOND, 300L, true));
        sink.put(entry(EMERALD, 400L, false));
        reader.set(IRON, 90L);
        reader.set(GOLD, 200L);
        reader.set(DIAMOND, 350L);
        reader.set(EMERALD, 1L);

        List<ItemKey> drifted = new OwnershipManager(sink, reader).detectExternalModifications();

        assertEquals(List.of(DIAMOND, IRON), drifted);
    }

    @Test
    void detectExternalModificationsIsEmptyWhenReaderUnavailable() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        reader.set(IRON, 90L);
        reader.setAvailable(false);

        assertTrue(new OwnershipManager(sink, reader).detectExternalModifications().isEmpty());
    }

    @Test
    void releaseOwnershipFlipsFlagAndKeepsEveryOtherField() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));

        OwnershipManager manager = new OwnershipManager(sink, reader);
        assertTrue(manager.releaseOwnership(IRON));

        GeneratedEmcEntry released = sink.get(IRON);
        assertFalse(released.owned());
        assertEquals(100L, released.value());
        assertEquals(PriceSource.RECIPE, released.source());
        assertEquals(RECIPE_ID, released.recipeId());
        assertEquals(TIMESTAMP, released.timestamp());
    }

    @Test
    void releaseOwnershipReturnsFalseForNonOwnedEntry() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, false));

        OwnershipManager manager = new OwnershipManager(sink, reader);

        assertFalse(manager.releaseOwnership(IRON));
        assertFalse(sink.get(IRON).owned());
    }

    @Test
    void releaseOwnershipReturnsFalseForUnknownItemAndNull() {
        OwnershipManager manager = new OwnershipManager(new InMemorySink(), new FakeReader());

        assertFalse(manager.releaseOwnership(IRON));
        assertFalse(manager.releaseOwnership(null));
    }

    @Test
    void releaseOwnershipReturnsFalseWhenStageFails() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        sink.failOnStage(true);

        assertFalse(new OwnershipManager(sink, reader).releaseOwnership(IRON));
        assertTrue(sink.get(IRON).owned());
    }

    @Test
    void migrateOwnershipReleasesAllDriftedEntries() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        sink.put(entry(GOLD, 200L, true));
        sink.put(entry(DIAMOND, 300L, true));
        sink.put(entry(EMERALD, 400L, false));
        reader.set(IRON, 90L);
        reader.set(GOLD, 200L);
        reader.set(DIAMOND, 350L);
        reader.set(EMERALD, 1L);

        int released = new OwnershipManager(sink, reader).migrateOwnership();

        assertEquals(2, released);
        assertFalse(sink.get(IRON).owned());
        assertTrue(sink.get(GOLD).owned());
        assertFalse(sink.get(DIAMOND).owned());
        assertFalse(sink.get(EMERALD).owned());
        assertEquals(100L, sink.get(IRON).value(), "迁移所有权绝不能改变数值");
        assertEquals(300L, sink.get(DIAMOND).value(), "迁移所有权绝不能改变数值");
    }

    @Test
    void migrateOwnershipReturnsZeroWhenReaderUnavailable() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        reader.set(IRON, 90L);
        reader.setAvailable(false);

        int released = new OwnershipManager(sink, reader).migrateOwnership();

        assertEquals(0, released);
        assertTrue(sink.get(IRON).owned());
    }

    @Test
    void snapshotStatesCoversEveryRecordedItem() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        sink.put(entry(GOLD, 200L, true));
        sink.put(entry(DIAMOND, 300L, false));
        reader.set(IRON, 100L);
        reader.set(GOLD, 150L);
        reader.set(DIAMOND, 300L);

        Map<ItemKey, OwnershipState> states = new OwnershipManager(sink, reader).snapshotStates();

        assertEquals(3, states.size());
        assertEquals(OwnershipState.OWNED_BY_US, states.get(IRON));
        assertEquals(OwnershipState.EXTERNALLY_MODIFIED, states.get(GOLD));
        assertEquals(OwnershipState.NOT_OWNED, states.get(DIAMOND));
    }

    @Test
    void sinkFailureNeverPropagates() {
        InMemorySink sink = new InMemorySink();
        FakeReader reader = new FakeReader();
        sink.put(entry(IRON, 100L, true));
        reader.set(IRON, 90L);
        sink.failOnSnapshot(true);

        OwnershipManager manager = new OwnershipManager(sink, reader);

        assertEquals(OwnershipState.NOT_OWNED, manager.classify(IRON));
        assertTrue(manager.detectExternalModifications().isEmpty());
        assertFalse(manager.releaseOwnership(IRON));
        assertEquals(0, manager.migrateOwnership());
        assertTrue(manager.snapshotStates().isEmpty());
        assertTrue(sink.get(IRON).owned(), "存储失败时不得改动已有条目");
    }

    private static GeneratedEmcEntry entry(ItemKey item, long value, boolean owned) {
        return new GeneratedEmcEntry(item, value, PriceSource.RECIPE, RECIPE_ID, owned, TIMESTAMP);
    }

    private static final class InMemorySink implements OwnedEntrySink {

        private final Map<ItemKey, GeneratedEmcEntry> entries = new HashMap<>();
        private boolean failOnSnapshot;
        private boolean failOnStage;

        void put(GeneratedEmcEntry entry) {
            entries.put(entry.item(), entry);
        }

        GeneratedEmcEntry get(ItemKey item) {
            return entries.get(item);
        }

        void failOnSnapshot(boolean fail) {
            this.failOnSnapshot = fail;
        }

        void failOnStage(boolean fail) {
            this.failOnStage = fail;
        }

        @Override
        public boolean stage(GeneratedEmcEntry entry) {
            if (failOnStage) {
                throw new IllegalStateException("simulated stage failure");
            }
            GeneratedEmcEntry existing = entries.get(entry.item());
            if (existing != null && !existing.owned()) {
                return false;
            }
            entries.put(entry.item(), entry);
            return true;
        }

        @Override
        public Map<ItemKey, GeneratedEmcEntry> snapshotAll() {
            if (failOnSnapshot) {
                throw new IllegalStateException("simulated snapshot failure");
            }
            return Map.copyOf(entries);
        }

        @Override
        public boolean isOwned(ItemKey key) {
            if (failOnSnapshot) {
                throw new IllegalStateException("simulated snapshot failure");
            }
            GeneratedEmcEntry entry = entries.get(key);
            return entry != null && entry.owned();
        }
    }

    private static final class FakeReader implements OwnershipManager.EmcValueReader {

        private final Map<ItemKey, Long> values = new HashMap<>();
        private boolean available = true;
        private boolean failOnRead;

        void set(ItemKey item, long value) {
            values.put(item, value);
        }

        void setAvailable(boolean available) {
            this.available = available;
        }

        void failOnRead(boolean fail) {
            this.failOnRead = fail;
        }

        @Override
        public long currentValue(ItemKey item) {
            if (failOnRead) {
                throw new IllegalStateException("simulated read failure");
            }
            return values.getOrDefault(item, 0L);
        }

        @Override
        public boolean available() {
            return available;
        }
    }
}
