package com.example.sudoersemc.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.core.OverflowScope;
import com.example.sudoersemc.safety.OverflowReset;
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
}
