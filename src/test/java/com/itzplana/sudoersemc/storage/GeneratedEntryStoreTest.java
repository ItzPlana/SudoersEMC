package com.itzplana.sudoersemc.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
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
 * Unit tests for {@link GeneratedEntryStore}. Forge is not bootstrapped; every test uses an
 * explicit {@link Path} so {@link SudoersemcPaths} is never resolved.
 */
class GeneratedEntryStoreTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");
    private static final ResourceLocation APPLE = new ResourceLocation("minecraft", "apple");

    @TempDir
    Path tempDir;

    @Test
    void roundTripPreservesEntriesAndOwnership() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);
        long timestamp = 1_730_000_000_000L;

        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 1L, PriceSource.RECIPE, STONE, true, timestamp)));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(DIRT, "variant-hash"), 5L, PriceSource.FALLBACK, null, false, timestamp + 1)));
        assertTrue(store.isDirty());

        store.save();
        assertFalse(store.isDirty());

        GeneratedEntryStore reloaded = new GeneratedEntryStore(file);
        reloaded.load();

        assertEquals(2, reloaded.size());
        assertEquals(1L, reloaded.snapshotOwnedValues().get(ItemKey.of(STONE)));
        assertFalse(reloaded.snapshotOwnedValues().containsKey(ItemKey.of(DIRT, "variant-hash")));

        GeneratedEmcEntry stone = reloaded.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(stone);
        assertEquals(1L, stone.value());
        assertEquals(PriceSource.RECIPE, stone.source());
        assertEquals(STONE, stone.recipeId());
        assertTrue(stone.owned());
        assertEquals(timestamp, stone.timestamp());

        GeneratedEmcEntry dirt = reloaded.snapshotAll().get(ItemKey.of(DIRT, "variant-hash"));
        assertNotNull(dirt);
        assertEquals(5L, dirt.value());
        assertEquals(PriceSource.FALLBACK, dirt.source());
        assertNull(dirt.recipeId());
        assertFalse(dirt.owned());
    }

    @Test
    void currentSchemaRoundTripPreservesAlternativeCosts() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);
        long timestamp = 1_730_000_000_000L;

        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 42L, PriceSource.RECIPE, STONE, true, timestamp,
                List.of(7L, 9L))));
        store.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(3, GeneratedEntryStore.SCHEMA_VERSION);
        assertTrue(json.contains("\"schemaVersion\": 3"));
        assertTrue(json.contains("\"alternatives\""));

        GeneratedEntryStore reloaded = new GeneratedEntryStore(file);
        reloaded.load();
        GeneratedEmcEntry entry = reloaded.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(entry);
        assertEquals(List.of(7L, 9L), entry.alternativeCosts());
    }

    @Test
    void schemaVersionOneLoadsWithoutAlternativeCosts() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 1,
                  "entries": [
                    {
                      "item": "minecraft:stone",
                      "variant": null,
                      "value": 7,
                      "source": "RECIPE",
                      "recipe": "minecraft:stone",
                      "owned": true,
                      "timestamp": 4
                    }
                  ]
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        GeneratedEntryStore store = new GeneratedEntryStore(file);
        assertDoesNotThrow(store::load);

        assertEquals(1, store.size());
        GeneratedEmcEntry stone = store.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(stone);
        assertEquals(7L, stone.value());
        assertTrue(stone.alternativeCosts().isEmpty(),
                "版本 1 文件必须按无备选成本加载");
    }

    @Test
    void invalidAlternativeCostsAreSkippedIndividually() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 2,
                  "entries": [
                    {
                      "item": "minecraft:stone",
                      "variant": null,
                      "value": 7,
                      "source": "RECIPE",
                      "recipe": null,
                      "owned": true,
                      "timestamp": 4,
                      "alternatives": [10, -1, "abc", null, 20]
                    }
                  ]
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        GeneratedEntryStore store = new GeneratedEntryStore(file);
        assertDoesNotThrow(store::load);

        GeneratedEmcEntry stone = store.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(stone);
        assertEquals(List.of(10L, 20L), stone.alternativeCosts());
    }

    @Test
    void corruptJsonYieldsEmptyStateAndRecoversOnNextSave() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        Files.writeString(file, "{ this is definitely not json ]", StandardCharsets.UTF_8);

        GeneratedEntryStore store = new GeneratedEntryStore(file);
        assertDoesNotThrow(store::load);
        assertTrue(store.snapshotAll().isEmpty());
        assertFalse(store.isDirty());

        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 9L, PriceSource.RECIPE, STONE, true, 42L)));
        assertDoesNotThrow(store::save);

        GeneratedEntryStore reloaded = new GeneratedEntryStore(file);
        reloaded.load();
        assertEquals(1, reloaded.size());
        assertEquals(9L, reloaded.snapshotOwnedValues().get(ItemKey.of(STONE)));

        try (Stream<Path> files = Files.list(tempDir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().endsWith(".bak")),
                    "corrupt original should be backed up before being replaced");
        }
    }

    @Test
    void unknownHigherSchemaVersionIsNeverDestroyed() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 99,
                  "entries": [
                    {
                      "item": "minecraft:stone",
                      "variant": null,
                      "value": 1,
                      "source": "RECIPE",
                      "recipe": null,
                      "owned": true,
                      "timestamp": 1
                    }
                  ]
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        GeneratedEntryStore store = new GeneratedEntryStore(file);
        assertDoesNotThrow(store::load);
        assertTrue(store.snapshotAll().isEmpty());
        assertFalse(store.isDirty());

        // save() must refuse to overwrite a document produced by a newer schema version
        assertDoesNotThrow(store::save);
        assertEquals(content, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void savedEntriesAreDeterministicallyOrdered() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);

        // Deliberately inserted out of order.
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 3L, PriceSource.FALLBACK, null, true, 1L)));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(DIRT), 2L, PriceSource.FALLBACK, null, true, 2L)));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(APPLE), 1L, PriceSource.FALLBACK, null, true, 3L)));
        store.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        int appleIndex = json.indexOf("minecraft:apple");
        int dirtIndex = json.indexOf("minecraft:dirt");
        int stoneIndex = json.indexOf("minecraft:stone");

        assertTrue(appleIndex >= 0, "apple entry must be present");
        assertTrue(appleIndex < dirtIndex, "apple must be saved before dirt");
        assertTrue(dirtIndex < stoneIndex, "dirt must be saved before stone");
    }

    @Test
    void getReturnsSingleEntryWithoutSnapshotting() {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);
        ItemKey key = ItemKey.of(STONE);
        GeneratedEmcEntry entry = new GeneratedEmcEntry(
                key, 12L, PriceSource.RECIPE, STONE, true, 1L);
        assertTrue(store.stage(entry));

        assertEquals(entry, store.get(key));
        assertNull(store.get(ItemKey.of(DIRT)));
        assertNull(store.get(null));
    }

    @Test
    void rejectsStagingOverNonOwnedEntry() {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);
        ItemKey key = ItemKey.of(STONE);

        GeneratedEmcEntry baseline = new GeneratedEmcEntry(
                key, 10L, PriceSource.EXTERNAL_BASELINE, null, false, 1L);
        assertTrue(store.stage(baseline));

        GeneratedEmcEntry generated = new GeneratedEmcEntry(
                key, 20L, PriceSource.RECIPE, STONE, true, 2L);
        assertFalse(store.stage(generated));
        assertEquals(10L, store.snapshotAll().get(key).value());
        assertFalse(store.isOwned(key));

        // Even a re-staged "owned" copy must not overwrite the manual value.
        assertFalse(store.stage(baseline.withOwned(true)));
        assertFalse(store.isOwned(key));
        assertEquals(10L, store.snapshotAll().get(key).value());
    }

    @Test
    void removeOwnedOnlyRemovesOwnedEntries() {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);
        ItemKey manualKey = ItemKey.of(DIRT);
        ItemKey ownedKey = ItemKey.of(STONE);

        assertTrue(store.stage(new GeneratedEmcEntry(
                manualKey, 4L, PriceSource.EXTERNAL_BASELINE, null, false, 1L)));
        assertFalse(store.removeOwned(manualKey));
        assertNotNull(store.snapshotAll().get(manualKey));

        assertTrue(store.stage(new GeneratedEmcEntry(
                ownedKey, 4L, PriceSource.RECIPE, null, true, 2L)));
        assertTrue(store.removeOwned(ownedKey));
        assertNull(store.snapshotAll().get(ownedKey));
        assertFalse(store.removeOwned(ownedKey));
    }

    @Test
    void skipsMalformedEntriesIndividually() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 1,
                  "entries": [
                    {"item": "invalid item id", "variant": null, "value": 1, "source": "RECIPE", "recipe": null, "owned": true, "timestamp": 1},
                    {"item": "minecraft:dirt", "variant": null, "value": -5, "source": "RECIPE", "recipe": null, "owned": true, "timestamp": 2},
                    {"item": "minecraft:apple", "variant": null, "value": 3, "source": "NOT_A_SOURCE", "recipe": null, "owned": true, "timestamp": 3},
                    {"item": "minecraft:stone", "variant": null, "value": 7, "source": "RECIPE", "recipe": "minecraft:stone", "owned": true, "timestamp": 4}
                  ]
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        GeneratedEntryStore store = new GeneratedEntryStore(file);
        assertDoesNotThrow(store::load);

        assertEquals(1, store.size());
        GeneratedEmcEntry stone = store.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(stone);
        assertEquals(7L, stone.value());
        assertEquals(STONE, stone.recipeId());
    }

    @Test
    void failedWriteKeepsDirtyAndRetriesWithBackup() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 1L, PriceSource.RECIPE, null, true, 1L)));
        store.save();
        assertFalse(store.isDirty());

        store.load(); // 文件已存在：下一次重写前必须生成 .bak
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(DIRT), 2L, PriceSource.FALLBACK, null, true, 2L)));

        blockTempFile(file);
        store.save();
        assertTrue(store.isDirty(), "写入失败后必须保留脏标记，saveIfDirty() 才能重试");

        unblockTempFile(file);
        Files.writeString(file, "\n/* external-change-marker */", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);

        store.saveIfDirty();

        assertFalse(store.isDirty());
        GeneratedEntryStore reloaded = new GeneratedEntryStore(file);
        reloaded.load();
        assertEquals(2, reloaded.size());
        assertTrue(anyBackupContains("external-change-marker"),
                "首次写入失败后，下一次成功写入仍必须重新生成 .bak");
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

    @Test
    void approvedSourceRoundTripsThroughDisk() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        GeneratedEntryStore store = new GeneratedEntryStore(file);
        long timestamp = 1_730_000_000_000L;

        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 10L, PriceSource.APPROVED, null, true, timestamp)));
        store.save();

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(json.contains("\"source\": \"APPROVED\""));
        assertTrue(json.contains("\"schemaVersion\": 3"),
                "APPROVED 来源要求当前 schema 为 3，旧构建必须整体拒绝加载");

        GeneratedEntryStore reloaded = new GeneratedEntryStore(file);
        reloaded.load();

        GeneratedEmcEntry stone = reloaded.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(stone);
        assertEquals(10L, stone.value());
        assertEquals(PriceSource.APPROVED, stone.source());
        assertTrue(stone.owned());
        assertTrue(stone.alternativeCosts().isEmpty());
    }

    @Test
    void legacyFileWithoutApprovedSourceStillLoads() throws IOException {
        Path file = tempDir.resolve(GeneratedEntryStore.FILE_NAME);
        String content = """
                {
                  "schemaVersion": 2,
                  "entries": [
                    {
                      "item": "minecraft:stone",
                      "variant": null,
                      "value": 7,
                      "source": "RECIPE",
                      "recipe": null,
                      "owned": true,
                      "timestamp": 4,
                      "alternatives": []
                    }
                  ]
                }
                """;
        Files.writeString(file, content, StandardCharsets.UTF_8);

        GeneratedEntryStore store = new GeneratedEntryStore(file);
        assertDoesNotThrow(store::load);

        GeneratedEmcEntry stone = store.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(stone);
        assertEquals(PriceSource.RECIPE, stone.source());
        assertEquals(7L, stone.value());
    }
}
