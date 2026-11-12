package com.itzplana.sudoersemc.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.safety.OverflowReset;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link OverflowResetLog}. Forge is not bootstrapped; every test uses an explicit
 * {@link Path} so {@link SudoersemcPaths} is never resolved.
 */
class OverflowResetLogTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");

    @TempDir
    Path tempDir;

    @Test
    void roundTripPreservesRecords() {
        Path file = tempDir.resolve(OverflowResetLog.FILE_NAME);
        OverflowResetLog log = new OverflowResetLog(file);

        OverflowReset stoneReset = new OverflowReset(
                ItemKey.of(STONE), 1_000_000L, 123_456L, 999_999L, OverflowScope.OWNED_ONLY, 1_730_000_000_000L);
        OverflowReset dirtReset = new OverflowReset(
                ItemKey.of(DIRT, "variant"), 2_000_000L, 42L, 1_500_000L, OverflowScope.ENTIRE_CUSTOM_EMC, 1_730_000_000_001L);

        log.record(stoneReset);
        log.record(dirtReset);
        log.save();

        OverflowResetLog reloaded = new OverflowResetLog(file);
        assertEquals(List.of(stoneReset, dirtReset), reloaded.entries());
    }

    @Test
    void capDropsOldestRecordsAndSurvivesRoundTrip() {
        Path file = tempDir.resolve(OverflowResetLog.FILE_NAME);
        int cap = 3;
        OverflowResetLog log = new OverflowResetLog(file, cap);

        for (int i = 0; i < 5; i++) {
            log.record(new OverflowReset(
                    ItemKey.of(STONE), 1_000L + i, 10L + i, 500L, OverflowScope.OWNED_ONLY, i));
        }

        List<OverflowReset> kept = log.entries();
        assertEquals(cap, kept.size());
        assertEquals(1_002L, kept.get(0).originalValue());
        assertEquals(1_004L, kept.get(2).originalValue());
        assertEquals(cap, log.entries().size());

        log.save();

        OverflowResetLog reloaded = new OverflowResetLog(file, cap);
        assertEquals(kept, reloaded.entries());
    }

    @Test
    void corruptFileYieldsEmptyLogWithoutThrowing() throws IOException {
        Path file = tempDir.resolve(OverflowResetLog.FILE_NAME);
        Files.writeString(file, "this is not json at all", StandardCharsets.UTF_8);

        OverflowResetLog log = assertDoesNotThrow(() -> new OverflowResetLog(file));
        assertTrue(log.entries().isEmpty());

        // A later save repairs the file instead of crashing.
        OverflowReset reset = new OverflowReset(
                ItemKey.of(STONE), 1_000_000L, 1_000L, 999_999L, OverflowScope.OWNED_ONLY, 7L);
        log.record(reset);
        assertDoesNotThrow(log::save);

        OverflowResetLog reloaded = new OverflowResetLog(file);
        assertEquals(List.of(reset), reloaded.entries());
    }

    @Test
    void unknownHigherSchemaVersionIsNeverDestroyed() throws IOException {
        Path file = tempDir.resolve(OverflowResetLog.FILE_NAME);
        String content = "{\"schemaVersion\": 42, \"resets\": []}";
        Files.writeString(file, content, StandardCharsets.UTF_8);

        OverflowResetLog log = assertDoesNotThrow(() -> new OverflowResetLog(file));
        assertTrue(log.entries().isEmpty());

        log.record(new OverflowReset(
                ItemKey.of(STONE), 1_000_000L, 1_000L, 999_999L, OverflowScope.OWNED_ONLY, 7L));
        assertDoesNotThrow(log::save);
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void saveIfDirtyWritesOnlyWhenRecordsChanged() throws IOException {
        Path file = tempDir.resolve(OverflowResetLog.FILE_NAME);
        OverflowResetLog log = new OverflowResetLog(file);
        assertFalse(log.isDirty());
        assertFalse(Files.exists(file));

        OverflowReset reset = new OverflowReset(
                ItemKey.of(STONE), 1_000L, 100L, 999L, OverflowScope.OWNED_ONLY, 1L);
        log.record(reset);
        assertTrue(log.isDirty());

        log.saveIfDirty();
        assertFalse(log.isDirty());
        assertTrue(Files.isRegularFile(file));
        assertEquals(List.of(reset), new OverflowResetLog(file).entries());

        Files.delete(file);
        log.saveIfDirty();
        assertFalse(Files.exists(file), "没有新增记录时 saveIfDirty 不得重写文件");
    }

    @Test
    void failedSaveKeepsDirtyAndRetries() throws IOException {
        Path file = tempDir.resolve(OverflowResetLog.FILE_NAME);
        Files.createDirectories(file);
        Files.writeString(file.resolve("blocker"), "x", StandardCharsets.UTF_8);

        OverflowResetLog log = new OverflowResetLog(file);
        log.record(new OverflowReset(
                ItemKey.of(STONE), 1_000L, 100L, 999L, OverflowScope.OWNED_ONLY, 1L));
        assertTrue(log.isDirty());

        log.saveIfDirty();
        assertTrue(log.isDirty(), "写入失败必须保留 dirty 标记以便下一轮重试");
        assertTrue(Files.isDirectory(file), "失败的原子写不得破坏原路径");

        Files.delete(file.resolve("blocker"));
        Files.delete(file);
        log.saveIfDirty();
        assertFalse(log.isDirty());
        assertEquals(1, new OverflowResetLog(file).entries().size());
    }

    @Test
    void corruptFileIsBackedUpBeforeFirstRewrite() throws IOException {
        Path file = tempDir.resolve(OverflowResetLog.FILE_NAME);
        String corrupt = "this is not json at all";
        Files.writeString(file, corrupt, StandardCharsets.UTF_8);

        OverflowResetLog log = new OverflowResetLog(file);
        assertTrue(log.entries().isEmpty());
        log.record(new OverflowReset(
                ItemKey.of(STONE), 1_000L, 100L, 999L, OverflowScope.OWNED_ONLY, 1L));
        log.saveIfDirty();

        Path backup;
        try (var files = Files.list(tempDir)) {
            backup = files.filter(path -> path.getFileName().toString().endsWith(".bak"))
                    .findFirst()
                    .orElse(null);
        }
        assertNotNull(backup, "覆盖损坏文件前必须保留 .bak 现场");
        assertEquals(corrupt, Files.readString(backup, StandardCharsets.UTF_8));
    }
}
