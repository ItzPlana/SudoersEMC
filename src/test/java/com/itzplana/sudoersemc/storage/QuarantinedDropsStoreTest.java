package com.itzplana.sudoersemc.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * {@link QuarantinedDropsStore} 的单元测试。
 *
 * <p>覆盖写入/读取、同物品替换、审批、容量上限、损坏文件容错与非法记录校验。</p>
 */
class QuarantinedDropsStoreTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");

    @TempDir
    Path tempDir;

    private static QuarantinedDropsStore.QuarantinedDrop drop(
            ResourceLocation itemId, long previousValue, long proposedValue, long timestamp) {
        double ratio = previousValue <= 0L
                ? 0.0
                : Math.max(0.0, (previousValue - proposedValue) / (double) previousValue);
        return new QuarantinedDropsStore.QuarantinedDrop(
                ItemKey.of(itemId),
                previousValue,
                proposedValue,
                ratio,
                "drop-exceeds-threshold",
                timestamp,
                false);
    }

    @Test
    void recordAndRoundTrip() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        QuarantinedDropsStore.QuarantinedDrop drop = new QuarantinedDropsStore.QuarantinedDrop(
                ItemKey.of(STONE),
                100_000L,
                100L,
                0.999,
                "drop-exceeds-threshold",
                1_730_000_000_000L,
                false);

        store.record(drop);
        assertTrue(store.isDirty());
        store.save();
        assertFalse(store.isDirty());

        QuarantinedDropsStore reloaded = new QuarantinedDropsStore(file);
        reloaded.load();

        assertEquals(1, reloaded.size());
        assertEquals(drop, reloaded.entries().get(0));
        assertFalse(reloaded.isDirty());
    }

    @Test
    void recordingSameItemTwiceKeepsOneEntry() {
        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));

        store.record(drop(STONE, 100_000L, 100L, 1L));
        store.record(drop(STONE, 100_000L, 50L, 2L));

        assertEquals(1, store.size());
        List<QuarantinedDropsStore.QuarantinedDrop> entries = store.entries();
        assertEquals(1, entries.size());
        assertEquals(50L, entries.get(0).proposedValue());
        assertEquals(2L, entries.get(0).timestamp());
    }

    @Test
    void approveFlipsFlagAndRejectsUnknown() {
        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        store.record(drop(STONE, 100_000L, 100L, 1L));

        assertFalse(store.approve(ItemKey.of(DIRT)));
        assertFalse(store.approve(null));
        assertTrue(store.approve(ItemKey.of(STONE)));
        assertTrue(store.entries().get(0).approved());
        assertFalse(store.entries().get(0).applied(), "仅批准不得被视为已应用");
        // 已批准的条目再次审批仍然认为是“存在”，返回 true。
        assertTrue(store.approve(ItemKey.of(STONE)));
    }

    @Test
    void markAppliedPersistsAcrossRestart() {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        store.record(drop(STONE, 100_000L, 100L, 1L));

        assertFalse(store.markApplied(ItemKey.of(DIRT)), "不存在的记录不能被标记为已应用");
        assertTrue(store.markApplied(ItemKey.of(STONE)));
        QuarantinedDropsStore.QuarantinedDrop marked = store.entries().get(0);
        assertTrue(marked.approved(), "标记已应用时必须同时视为已批准");
        assertTrue(marked.applied());
        store.save();

        QuarantinedDropsStore reloaded = new QuarantinedDropsStore(file);
        reloaded.load();
        assertTrue(reloaded.entries().get(0).approved());
        assertTrue(reloaded.entries().get(0).applied());
    }

    @Test
    void legacyApprovedRecordLoadsAsNotApplied() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        Files.writeString(file, """
                {
                  "schemaVersion": 1,
                  "entries": [
                    {
                      "item": "minecraft:stone",
                      "variant": null,
                      "previousValue": 1000,
                      "proposedValue": 10,
                      "dropRatio": 0.99,
                      "reason": "test",
                      "timestamp": 123,
                      "approved": true
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        store.load();

        assertEquals(1, store.size());
        assertTrue(store.entries().get(0).approved(), "旧版本 approved 标记必须保留");
        assertFalse(store.entries().get(0).applied(),
                "旧版本没有 applied 字段，必须按“仅批准未应用”加载");
    }

    @Test
    void retentionCapDropsOldestEntries() {
        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));

        for (int i = 0; i <= QuarantinedDropsStore.DEFAULT_MAX_ENTRIES; i++) {
            ResourceLocation itemId = new ResourceLocation("minecraft", "item_" + i);
            store.record(drop(itemId, 1000L, 10L, 1_730_000_000_000L + i));
        }

        assertEquals(QuarantinedDropsStore.DEFAULT_MAX_ENTRIES, store.size());
        List<QuarantinedDropsStore.QuarantinedDrop> entries = store.entries();
        assertEquals(QuarantinedDropsStore.DEFAULT_MAX_ENTRIES, entries.size());

        ItemKey oldest = ItemKey.of(new ResourceLocation("minecraft", "item_0"));
        ItemKey newest = ItemKey.of(new ResourceLocation("minecraft", "item_"
                + QuarantinedDropsStore.DEFAULT_MAX_ENTRIES));
        assertTrue(entries.stream().noneMatch(entry -> entry.key().equals(oldest)),
                "时间戳最旧的条目应当被淘汰");
        assertTrue(entries.stream().anyMatch(entry -> entry.key().equals(newest)),
                "最新写入的条目必须保留");
    }

    @Test
    void corruptFileIsTolerated() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        Files.writeString(file, "{ 这不是合法 JSON ][", StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        assertDoesNotThrow(store::load);

        assertEquals(0, store.size());
        assertFalse(store.isDirty());
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void invalidDropsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), -1L, 10L, 0.5, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, -1L, 0.5, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, 1L, Double.NaN, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, 1L, Double.POSITIVE_INFINITY, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, 1L, 0.5, "reason", -1L, false));

        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        assertDoesNotThrow(() -> store.record(null));
        assertEquals(0, store.size());
        assertFalse(store.isDirty());
    }

    @Test
    void emptyFileIsBackedUpAndRewritten() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        Files.writeString(file, "", StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        assertDoesNotThrow(store::load);
        assertEquals(0, store.size());
        assertFalse(store.isDirty());

        store.record(drop(STONE, 1000L, 10L, 1L));
        store.save();

        assertFalse(store.isDirty());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"schemaVersion\": 2"));
        assertEquals(1L, backupCount(), "首次重写空文件前也应生成 .bak");
    }

    @Test
    void lowerSchemaVersionIsLoadedAndRewritten() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        Files.writeString(file, """
                {
                  "schemaVersion": 0,
                  "entries": [
                    {
                      "item": "minecraft:stone",
                      "variant": null,
                      "previousValue": 1000,
                      "proposedValue": 10,
                      "dropRatio": 0.99,
                      "reason": "test",
                      "timestamp": 123,
                      "approved": false
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        store.load();

        assertEquals(1, store.size());
        assertEquals(123L, store.entries().get(0).timestamp());

        store.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 2"), "保存时应升级到当前 schemaVersion");
        assertEquals(1L, backupCount());
    }

    @Test
    void missingSchemaVersionIsTreatedAsLegacy() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        Files.writeString(file, """
                {
                  "entries": [
                    {
                      "item": "minecraft:dirt",
                      "variant": null,
                      "previousValue": 500,
                      "proposedValue": 5,
                      "dropRatio": 0.99,
                      "reason": "test",
                      "timestamp": 456,
                      "approved": true
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        store.load();

        assertEquals(1, store.size());
        assertTrue(store.entries().get(0).approved());

        store.save();

        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("\"schemaVersion\": 2"));
        assertEquals(1L, backupCount());
    }

    @Test
    void unknownHigherSchemaVersionIsNeverDestroyed() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 99,
                  "entries": [
                    {
                      "item": "minecraft:stone",
                      "variant": null,
                      "previousValue": 1000,
                      "proposedValue": 10,
                      "dropRatio": 0.99,
                      "reason": "test",
                      "timestamp": 1,
                      "approved": false
                    }
                  ]
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        assertDoesNotThrow(store::load);
        assertEquals(0, store.size());

        store.record(drop(STONE, 1000L, 10L, 2L));
        assertDoesNotThrow(store::save);

        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void failedWriteKeepsDirtyAndRetriesWithBackup() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        store.record(drop(STONE, 100_000L, 100L, 1L));
        store.save();
        assertFalse(store.isDirty());

        store.load(); // 文件已存在：下一次重写前必须生成 .bak
        store.record(drop(DIRT, 50_000L, 50L, 2L));

        blockTempFile(file);
        store.save();
        assertTrue(store.isDirty(), "写入失败后必须保留脏标记，saveIfDirty() 才能重试");

        unblockTempFile(file);
        Files.writeString(file, "\n/* external-change-marker */", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        store.saveIfDirty();

        assertFalse(store.isDirty());
        QuarantinedDropsStore reloaded = new QuarantinedDropsStore(file);
        reloaded.load();
        assertEquals(2, reloaded.size());
        assertTrue(anyBackupContains("external-change-marker"),
                "首次写入失败后，下一次成功写入仍必须重新生成 .bak");
    }

    @Test
    void oversizedFileIsTrimmedOnLoad() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        int total = QuarantinedDropsStore.DEFAULT_MAX_ENTRIES + 10;
        StringBuilder json = new StringBuilder("{\"schemaVersion\":1,\"entries\":[");
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"item\":\"minecraft:item_").append(i)
                    .append("\",\"variant\":null,\"previousValue\":1000,\"proposedValue\":10,")
                    .append("\"dropRatio\":0.99,\"reason\":\"test\",\"timestamp\":").append(i)
                    .append(",\"approved\":false}");
        }
        json.append("]}");
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        store.load();

        assertEquals(QuarantinedDropsStore.DEFAULT_MAX_ENTRIES, store.size());
        List<QuarantinedDropsStore.QuarantinedDrop> entries = store.entries();
        assertEquals(10L, entries.stream()
                .mapToLong(QuarantinedDropsStore.QuarantinedDrop::timestamp)
                .min()
                .orElseThrow(), "应按时间戳淘汰最旧的 10 条记录");
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
