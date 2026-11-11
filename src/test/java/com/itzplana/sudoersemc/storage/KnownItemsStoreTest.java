package com.itzplana.sudoersemc.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link KnownItemsStore} 的单元测试。
 *
 * <p>所有测试使用临时目录，不依赖 Forge 的配置目录解析。</p>
 */
class KnownItemsStoreTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");
    private static final ResourceLocation APPLE = new ResourceLocation("minecraft", "apple");

    @TempDir
    Path tempDir;

    @Test
    void recordSeenFirstTimeSetsBothTimestamps() {
        KnownItemsStore store = new KnownItemsStore(tempDir.resolve(KnownItemsStore.FILE_NAME));

        store.recordSeen(ItemKey.of(STONE));

        assertEquals(1, store.size());
        assertTrue(store.contains(ItemKey.of(STONE)));
        assertTrue(store.isDirty());

        KnownItemsStore.KnownItem item = store.snapshot().get(0);
        assertTrue(item.firstSeen() > 0L);
        assertEquals(item.firstSeen(), item.lastSeen());
    }

    @Test
    void secondRecordSeenBumpsOnlyLastSeen() throws InterruptedException {
        KnownItemsStore store = new KnownItemsStore(tempDir.resolve(KnownItemsStore.FILE_NAME));
        ItemKey key = ItemKey.of(STONE);

        store.recordSeen(key);
        KnownItemsStore.KnownItem first = store.snapshot().get(0);

        // 系统时钟通常只有毫秒精度，等待一小段时间才能观察到 lastSeen 前进。
        Thread.sleep(20L);
        store.recordSeen(key);
        KnownItemsStore.KnownItem second = store.snapshot().get(0);

        assertEquals(first.firstSeen(), second.firstSeen());
        assertTrue(second.lastSeen() > first.lastSeen(),
                "第二次记录应当只推进 lastSeen");
        assertEquals(1, store.size());
    }

    @Test
    void roundTripPersistsItems() throws IOException {
        Path file = tempDir.resolve(KnownItemsStore.FILE_NAME);
        KnownItemsStore store = new KnownItemsStore(file);

        store.recordSeen(ItemKey.of(STONE));
        store.recordSeen(ItemKey.of(DIRT, "variant-hash"));
        store.save();
        assertFalse(store.isDirty());

        KnownItemsStore reloaded = new KnownItemsStore(file);
        reloaded.load();

        assertEquals(2, reloaded.size());
        assertTrue(reloaded.contains(ItemKey.of(STONE)));
        assertTrue(reloaded.contains(ItemKey.of(DIRT, "variant-hash")));
        assertEquals(store.snapshot(), reloaded.snapshot());
        assertFalse(reloaded.isDirty());
    }

    @Test
    void snapshotIsSortedByItemKey() {
        KnownItemsStore store = new KnownItemsStore(tempDir.resolve(KnownItemsStore.FILE_NAME));

        // 故意打乱记录顺序，快照仍必须按 ItemKey 自然顺序输出。
        store.recordSeen(ItemKey.of(STONE));
        store.recordSeen(ItemKey.of(DIRT, "variant-hash"));
        store.recordSeen(ItemKey.of(APPLE));
        store.recordSeen(ItemKey.of(DIRT));

        List<ItemKey> keys = store.snapshot().stream()
                .map(KnownItemsStore.KnownItem::key)
                .toList();

        assertEquals(List.of(
                ItemKey.of(APPLE),
                ItemKey.of(DIRT),
                ItemKey.of(DIRT, "variant-hash"),
                ItemKey.of(STONE)), keys);
    }

    @Test
    void corruptFileIsTolerated() throws IOException {
        Path file = tempDir.resolve(KnownItemsStore.FILE_NAME);
        Files.writeString(file, "{ 这不是合法 JSON ][", StandardCharsets.UTF_8);

        KnownItemsStore store = new KnownItemsStore(file);
        assertDoesNotThrow(store::load);

        assertEquals(0, store.size());
        assertFalse(store.isDirty());
        assertFalse(store.contains(ItemKey.of(STONE)));
    }

    @Test
    void clearEmptiesAndMarksDirty() {
        KnownItemsStore store = new KnownItemsStore(tempDir.resolve(KnownItemsStore.FILE_NAME));
        store.recordSeen(ItemKey.of(STONE));
        store.save();
        assertFalse(store.isDirty());

        store.clear();

        assertEquals(0, store.size());
        assertFalse(store.contains(ItemKey.of(STONE)));
        assertTrue(store.isDirty());
    }
}
