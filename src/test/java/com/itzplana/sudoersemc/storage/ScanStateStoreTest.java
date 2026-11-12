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
import java.util.List;
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
}
