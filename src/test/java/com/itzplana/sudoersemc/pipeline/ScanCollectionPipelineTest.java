package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.scanner.IngredientOption;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeKind;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7 生产管线：求解结果写入、扫描状态持久化与增量跳过逻辑验证。
 */
class ScanCollectionPipelineTest {

    private static final ItemKey IRON_INGOT = ItemKey.of(new ResourceLocation("minecraft", "iron_ingot"));
    private static final ItemKey IRON_BLOCK = ItemKey.of(new ResourceLocation("minecraft", "iron_block"));

    @TempDir
    Path tempDir;

    @Test
    void applySnapshotStagesRecipeDerivedValues() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        ScanCollectionPipeline pipeline = pipeline(store);

        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"), 9L));

        assertEquals(81L, store.snapshotOwnedValues().get(IRON_BLOCK).longValue());
        assertEquals(1L, pipeline.lastSolvedCount());
        assertEquals(0L, pipeline.lastUnresolvedCount());
    }

    @Test
    void persistWritesStateAndSkipsUnchangedFingerprint() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        ScanCollectionPipeline pipeline = pipeline(store);
        ScanFingerprint fingerprint = fingerprint("minecraft:iron_ingot");
        pipeline.applySnapshot(snapshot(fingerprint, 9L));
        pipeline.persist();

        Path stateFile = tempDir.resolve(ScanCollectionPipeline.STATE_FILE_NAME);
        assertTrue(Files.isRegularFile(stateFile), "persist 必须写出 scan_state.json");

        ScanCollectionPipeline reloaded = new ScanCollectionPipeline(null, null, null, stateFile);
        assertFalse(reloaded.shouldScan(fingerprint, false),
                "指纹未变化且 alwaysRescan=false 时必须跳过扫描");
        assertTrue(reloaded.shouldScan(fingerprint, true),
                "alwaysRescan=true 必须强制扫描");
        assertTrue(reloaded.shouldScan(fingerprint("minecraft:gold_ingot"), false),
                "指纹变化时必须重新扫描");
    }

    @Test
    void persistWithoutAppliedScanDoesNothing() {
        ScanCollectionPipeline pipeline = pipeline(null);

        pipeline.persist();

        assertFalse(Files.exists(tempDir.resolve(ScanCollectionPipeline.STATE_FILE_NAME)));
    }

    @Test
    void applyWithoutStashedSnapshotFails() {
        ScanCollectionPipeline pipeline = pipeline(null);

        assertThrows(IllegalStateException.class,
                () -> pipeline.apply(new ScanManager.SnapshotData("空", 0, 0)));
    }

    @Test
    void collectedSnapshotRejectsNegativeCounters() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanCollectionPipeline.CollectedSnapshot(
                        List.of(), List.of(), -1, 0, 0, fingerprint("x")));
    }

    private ScanCollectionPipeline pipeline(GeneratedEntryStore store) {
        return new ScanCollectionPipeline(store, null, null,
                tempDir.resolve(ScanCollectionPipeline.STATE_FILE_NAME));
    }

    private static ScanCollectionPipeline.CollectedSnapshot snapshot(ScanFingerprint fingerprint,
                                                                     long ingotValue) {
        ItemSnapshot ingot = new ItemSnapshot(IRON_INGOT, IRON_INGOT.itemId(), "minecraft",
                "iron_ingot", true, ingotValue);
        ItemSnapshot block = new ItemSnapshot(IRON_BLOCK, IRON_BLOCK.itemId(), "minecraft",
                "iron_block", false, 0L);
        RecipeSnapshot recipe = RecipeSnapshot.of(
                new ResourceLocation("sudoersemc_test", "iron_block_from_ingots"),
                RecipeKind.SHAPED,
                List.of(new RecipeInput(IngredientOption.of(IRON_INGOT), 9)),
                List.of(RecipeOutput.of(IRON_BLOCK)));
        return new ScanCollectionPipeline.CollectedSnapshot(
                List.of(ingot, block), List.of(recipe), 0, 0, 0, fingerprint);
    }

    private static ScanFingerprint fingerprint(String itemId) {
        return ScanFingerprint.of(
                List.of(itemId),
                List.of("sudoersemc_test:iron_block_from_ingots"),
                List.of(),
                List.of(),
                List.of());
    }
}
