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
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
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

    @Test
    void emptyFileIsBackedUpAndRewritten() throws IOException {
        Path file = tempDir.resolve(KnownItemsStore.FILE_NAME);
        Files.writeString(file, "", StandardCharsets.UTF_8);

        KnownItemsStore store = new KnownItemsStore(file);
        assertDoesNotThrow(store::load);
        assertEquals(0, store.size());
        assertFalse(store.isDirty());

        store.recordSeen(ItemKey.of(STONE));
        store.save();

        assertFalse(store.isDirty());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"schemaVersion\": 1"));
        assertEquals(1L, backupCount(), "首次重写空文件前也应生成 .bak");
    }

    @Test
    void lowerSchemaVersionIsLoadedAndRewritten() throws IOException {
        Path file = tempDir.resolve(KnownItemsStore.FILE_NAME);
        Files.writeString(file, """
                {
                  "schemaVersion": 0,
                  "items": [
                    {"item": "minecraft:stone", "variant": null, "firstSeen": 10, "lastSeen": 20},
                    {"item": "minecraft:dirt", "variant": "variant-hash", "firstSeen": 30, "lastSeen": 40}
                  ]
                }
                """, StandardCharsets.UTF_8);

        KnownItemsStore store = new KnownItemsStore(file);
        store.load();

        assertEquals(2, store.size());
        assertTrue(store.contains(ItemKey.of(STONE)));
        assertTrue(store.contains(ItemKey.of(DIRT, "variant-hash")));

        store.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"), "保存时应升级到当前 schemaVersion");
        assertEquals(1L, backupCount());
    }

    @Test
    void missingSchemaVersionIsTreatedAsLegacy() throws IOException {
        Path file = tempDir.resolve(KnownItemsStore.FILE_NAME);
        Files.writeString(file, """
                {
                  "items": [
                    {"item": "minecraft:apple", "variant": null, "firstSeen": 1, "lastSeen": 2}
                  ]
                }
                """, StandardCharsets.UTF_8);

        KnownItemsStore store = new KnownItemsStore(file);
        store.load();

        assertEquals(1, store.size());
        assertTrue(store.contains(ItemKey.of(APPLE)));

        store.save();

        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"schemaVersion\": 1"));
        assertEquals(1L, backupCount());
    }

    @Test
    void unknownHigherSchemaVersionIsNeverDestroyed() throws IOException {
        Path file = tempDir.resolve(KnownItemsStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 99,
                  "items": [
                    {"item": "minecraft:stone", "variant": null, "firstSeen": 1, "lastSeen": 2}
                  ]
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        KnownItemsStore store = new KnownItemsStore(file);
        assertDoesNotThrow(store::load);
        assertEquals(0, store.size());

        store.recordSeen(ItemKey.of(STONE));
        assertDoesNotThrow(store::save);

        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void failedWriteKeepsDirtyAndRetriesWithBackup() throws IOException {
        Path file = tempDir.resolve(KnownItemsStore.FILE_NAME);
        KnownItemsStore store = new KnownItemsStore(file);
        store.recordSeen(ItemKey.of(STONE));
        store.save();
        assertFalse(store.isDirty());

        store.load(); // 文件已存在：下一次重写前必须生成 .bak
        store.recordSeen(ItemKey.of(DIRT));

        blockTempFile(file);
        store.save();
        assertTrue(store.isDirty(), "写入失败后必须保留脏标记，saveIfDirty() 才能重试");

        unblockTempFile(file);
        Files.writeString(file, "\n/* external-change-marker */", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        store.saveIfDirty();

        assertFalse(store.isDirty());
        KnownItemsStore reloaded = new KnownItemsStore(file);
        reloaded.load();
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.contains(ItemKey.of(DIRT)));
        assertTrue(anyBackupContains("external-change-marker"),
                "首次写入失败后，下一次成功写入仍必须重新生成 .bak");
    }

    private long backupCount() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.filter(path -> path.getFileName().toString().endsWith(".bak")).count();
        }
    }

    private boolean anyBackupContains(String marker) throws IOException {
        List<Path> backups;
        try (Stream<Path> files = Files.list(tempDir)) {
            backups = files.filter(path -> path.getFileName().toString().endsWith(".bak")).toList();
        }
        for (Path backup : backups) {
            if (Files.readString(backup, StandardCharsets.UTF_8).contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static void blockTempFile(Path file) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectories(temp);
        Files.writeString(temp.resolve("blocker"), "x", StandardCharsets.UTF_8);
    }

    private static void unblockTempFile(Path file) throws IOException {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.deleteIfExists(temp.resolve("blocker"));
        Files.deleteIfExists(temp);
    }
}
