package com.itzplana.sudoersemc.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.task.ScanFingerprint;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ScanStateStore} 的单元测试。
 *
 * <p>不启动 Forge；每个测试都通过显式临时路径构造存储，因此不会解析
 * {@link SudoersemcPaths} 的真实数据目录。</p>
 */
class ScanStateStoreTest {

    @TempDir
    Path tempDir;

    private static ScanFingerprint fingerprint() {
        return ScanFingerprint.of(
                List.of("item-input"),
                List.of("recipe-input"),
                List.of("tag-input"),
                List.of("config-input"),
                List.of("baseline-input"));
    }

    @Test
    void missingFileYieldsEmptyState() {
        ScanStateStore store = new ScanStateStore(tempDir.resolve(ScanStateStore.FILE_NAME));

        assertDoesNotThrow(store::load);

        assertTrue(store.fingerprint().isEmpty());
        assertFalse(store.matches(fingerprint()));
        assertFalse(store.isDirty());
        assertEquals(0L, store.lastScanEpochMillis());
        assertEquals(0L, store.lastScanDurationMillis());
        assertEquals(0, store.lastSolvedCount());
        assertEquals(0, store.lastUnresolvedCount());
    }

    @Test
    void updateSaveAndReloadRoundTrip() throws IOException {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        ScanStateStore store = new ScanStateStore(file);
        ScanFingerprint fingerprint = fingerprint();

        store.update(fingerprint, 1234L, 100, 5);
        assertTrue(store.isDirty());

        store.save();
        assertFalse(store.isDirty());

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"lastScanDurationMillis\": 1234"));
        assertTrue(json.contains("\"lastSolvedCount\": 100"));
        assertTrue(json.contains("\"lastUnresolvedCount\": 5"));

        ScanStateStore reloaded = new ScanStateStore(file);
        reloaded.load();

        assertTrue(reloaded.matches(fingerprint));
        assertEquals(fingerprint, reloaded.fingerprint());
        assertEquals(1234L, reloaded.lastScanDurationMillis());
        assertEquals(100, reloaded.lastSolvedCount());
        assertEquals(5, reloaded.lastUnresolvedCount());
        assertTrue(reloaded.lastScanEpochMillis() > 0L);
        assertFalse(reloaded.isDirty());
    }

    @Test
    void matchesOnlyWhenStoredFingerprintEqualsCandidate() {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        ScanStateStore store = new ScanStateStore(file);
        ScanFingerprint fingerprint = fingerprint();
        store.update(fingerprint, 10L, 1, 0);
        store.save();

        ScanStateStore reloaded = new ScanStateStore(file);
        reloaded.load();

        ScanFingerprint sameContent = ScanFingerprint.of(
                List.of("item-input"),
                List.of("recipe-input"),
                List.of("tag-input"),
                List.of("config-input"),
                List.of("baseline-input"));
        ScanFingerprint differentItems = ScanFingerprint.of(
                List.of("other-item"),
                List.of("recipe-input"),
                List.of("tag-input"),
                List.of("config-input"),
                List.of("baseline-input"));

        assertTrue(reloaded.matches(sameContent));
        assertFalse(reloaded.matches(differentItems));
        assertFalse(reloaded.matches(null));
    }

    @Test
    void corruptJsonYieldsEmptyStateWithoutThrowing() throws IOException {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        Files.writeString(file, "{ 这不是合法 JSON ][", StandardCharsets.UTF_8);

        ScanStateStore store = new ScanStateStore(file);

        assertDoesNotThrow(store::load);
        assertTrue(store.fingerprint().isEmpty());
        assertFalse(store.isDirty());
        assertFalse(store.matches(fingerprint()));
        assertEquals(0L, store.lastScanDurationMillis());
    }

    @Test
    void unsupportedHigherSchemaVersionIsNeverOverwritten() throws IOException {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 99,
                  "fingerprint": {
                    "item": "a",
                    "recipe": "b",
                    "tag": "c",
                    "config": "d",
                    "baseline": "e"
                  },
                  "lastScanEpochMillis": 1,
                  "lastScanDurationMillis": 2,
                  "lastSolvedCount": 3,
                  "lastUnresolvedCount": 4
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        ScanStateStore store = new ScanStateStore(file);
        assertDoesNotThrow(store::load);

        // 更高版本的内容一律不加载。
        assertTrue(store.fingerprint().isEmpty());
        assertFalse(store.matches(fingerprint()));

        // 即使内存中产生了新状态，save() 也必须拒绝覆盖文件。
        store.update(fingerprint(), 5L, 1, 0);
        assertDoesNotThrow(store::save);

        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void saveIfDirtyWritesPendingUpdate() {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        ScanStateStore store = new ScanStateStore(file);

        store.update(fingerprint(), 1L, 2, 3);
        assertTrue(store.isDirty());

        store.saveIfDirty();

        assertFalse(store.isDirty());
        assertTrue(Files.isRegularFile(file));
    }

    @Test
    void compareReportsChangedChannels() {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        ScanStateStore store = new ScanStateStore(file);
        ScanFingerprint stored = fingerprint();

        ScanStateStore.FingerprintComparison empty = store.compare(stored);
        assertFalse(empty.stored(), "没有保存过指纹时 stored=false");
        assertFalse(empty.matches());

        store.update(stored, 1L, 1, 0);

        assertTrue(store.compare(stored).matches());
        assertEquals(List.of(), store.compare(stored).changedChannels());

        ScanFingerprint changed = ScanFingerprint.of(
                List.of("item-input"),
                List.of("recipe-input"),
                List.of("tag-input"),
                List.of("config-input", "changed"),
                List.of("baseline-input"));
        ScanStateStore.FingerprintComparison comparison = store.compare(changed);
        assertTrue(comparison.stored());
        assertFalse(comparison.matches());
        assertEquals(List.of("config"), comparison.changedChannels());
    }

    @Test
    void emptyFileIsBackedUpAndRewritten() throws IOException {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        Files.writeString(file, "", StandardCharsets.UTF_8);

        ScanStateStore store = new ScanStateStore(file);
        assertDoesNotThrow(store::load);
        assertTrue(store.fingerprint().isEmpty());
        assertFalse(store.isDirty());

        store.update(fingerprint(), 42L, 7, 0);
        store.save();

        assertFalse(store.isDirty());
        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertTrue(json.contains("\"lastScanDurationMillis\": 42"));
        assertEquals(1L, backupCount(), "首次重写空文件前也应生成 .bak");
    }

    @Test
    void lowerSchemaVersionIsLoadedAndRewritten() throws IOException {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        ScanFingerprint expected = fingerprint();
        String content = """
                {
                  "schemaVersion": 0,
                  "fingerprint": {
                    "item": "%s",
                    "recipe": "%s",
                    "tag": "%s",
                    "config": "%s",
                    "baseline": "%s"
                  },
                  "lastScanEpochMillis": 11,
                  "lastScanDurationMillis": 22,
                  "lastSolvedCount": 3,
                  "lastUnresolvedCount": 4
                }
                """.formatted(
                expected.itemFingerprint(),
                expected.recipeFingerprint(),
                expected.tagFingerprint(),
                expected.configFingerprint(),
                expected.baselineFingerprint());
        Files.writeString(file, content, StandardCharsets.UTF_8);

        ScanStateStore store = new ScanStateStore(file);
        store.load();

        // 低版本按当前字段加载，而不是被当作“更高的未知版本”拒绝。
        assertTrue(store.matches(expected));
        assertEquals(22L, store.lastScanDurationMillis());
        assertEquals(3, store.lastSolvedCount());

        store.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"), "保存时应升级到当前 schemaVersion");
        assertFalse(store.isDirty());
        assertEquals(1L, backupCount());
    }

    @Test
    void missingSchemaVersionIsTreatedAsLegacy() throws IOException {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        Files.writeString(file, """
                {
                  "lastScanDurationMillis": 55,
                  "lastSolvedCount": 6
                }
                """, StandardCharsets.UTF_8);

        ScanStateStore store = new ScanStateStore(file);
        store.load();

        assertEquals(55L, store.lastScanDurationMillis());
        assertEquals(6, store.lastSolvedCount());

        store.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"schemaVersion\": 1"));
        assertEquals(1L, backupCount());
    }

    @Test
    void failedWriteKeepsDirtyAndRetriesWithBackup() throws IOException {
        Path file = tempDir.resolve(ScanStateStore.FILE_NAME);
        ScanStateStore store = new ScanStateStore(file);
        store.update(fingerprint(), 1L, 1, 0);
        store.save();
        assertFalse(store.isDirty());

        store.load(); // 文件已存在：下一次重写前必须生成 .bak
        store.update(fingerprint(), 222L, 2, 1);

        blockTempFile(file);
        store.save();
        assertTrue(store.isDirty(), "写入失败后必须保留脏标记，saveIfDirty() 才能重试");

        unblockTempFile(file);
        // 只有失败后的下一次成功写入仍然生成 .bak，才能在新备份中看到这个标记。
        Files.writeString(file, "\n/* external-change-marker */", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        store.saveIfDirty();

        assertFalse(store.isDirty());
        assertTrue(Files.readString(file, StandardCharsets.UTF_8)
                .contains("\"lastScanDurationMillis\": 222"));
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
