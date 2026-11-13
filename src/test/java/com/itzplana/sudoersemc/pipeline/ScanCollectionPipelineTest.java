package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.scanner.IngredientOption;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeKind;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7 生产管线：求解结果写入、归属/安全、指纹内容、跨 tick staging 与持久化验证。
 */
class ScanCollectionPipelineTest {

    private static final ItemKey IRON_INGOT = ItemKey.of(new ResourceLocation("minecraft", "iron_ingot"));
    private static final ItemKey IRON_BLOCK = ItemKey.of(new ResourceLocation("minecraft", "iron_block"));
    private static final ResourceLocation BLOCK_RECIPE_ID =
            new ResourceLocation("sudoersemc_test", "iron_block_from_ingots");

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
    void recipeFingerprintChangesWhenSameRecipeIdChangesContent() {
        String original = ScanFingerprint.hash(
                ScanCollectionPipeline.recipeEntries(List.of(blockRecipe(9))));
        String sameContent = ScanFingerprint.hash(
                ScanCollectionPipeline.recipeEntries(List.of(blockRecipe(9))));
        String changedContent = ScanFingerprint.hash(
                ScanCollectionPipeline.recipeEntries(List.of(blockRecipe(4))));

        assertEquals(original, sameContent, "同内容必须得到相同指纹");
        assertNotEquals(original, changedContent,
                "配方 id 不变但输入数量变化时，指纹必须变化");
    }

    @Test
    void tagFingerprintChangesWhenMembersChange() {
        Map<String, List<String>> original = Map.of(
                "c:ingots", List.of("minecraft:iron_ingot", "minecraft:gold_ingot"));
        Map<String, List<String>> reordered = Map.of(
                "c:ingots", List.of("minecraft:gold_ingot", "minecraft:iron_ingot"));
        Map<String, List<String>> changed = Map.of(
                "c:ingots", List.of("minecraft:iron_ingot"));

        assertEquals(
                ScanFingerprint.hash(ScanCollectionPipeline.tagEntries(original)),
                ScanFingerprint.hash(ScanCollectionPipeline.tagEntries(reordered)),
                "标签成员顺序不应影响指纹");
        assertNotEquals(
                ScanFingerprint.hash(ScanCollectionPipeline.tagEntries(original)),
                ScanFingerprint.hash(ScanCollectionPipeline.tagEntries(changed)),
                "同名标签的成员变化必须改变指纹");
    }

    @Test
    void rescanUpdatesOwnedValuesInsteadOfTreatingThemAsSeeds() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        store.stage(new GeneratedEmcEntry(
                IRON_BLOCK, 20L, PriceSource.RECIPE, BLOCK_RECIPE_ID, true, 0L));
        ScanCollectionPipeline pipeline = pipeline(store);

        ItemSnapshot blockWithOwnedValue = new ItemSnapshot(
                IRON_BLOCK, IRON_BLOCK.itemId(), "minecraft", "iron_block", true, 20L);
        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"),
                List.of(ingotSnapshot(2L), blockWithOwnedValue), List.of(blockRecipe(9))));

        assertEquals(18L, store.snapshotOwnedValues().get(IRON_BLOCK).longValue(),
                "owned 值必须作为初始 generated 值被更低的新成本替换，而不是被当成 seed");
        assertEquals(1L, pipeline.lastSolvedCount());
        assertEquals(0L, pipeline.lastQuarantinedCount());
    }

    @Test
    void largeDropProtectionQuarantinesOwnedValueUpdates() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        store.stage(new GeneratedEmcEntry(
                IRON_BLOCK, 100L, PriceSource.RECIPE, BLOCK_RECIPE_ID, true, 0L));
        ScanCollectionPipeline pipeline = pipeline(store);

        ItemSnapshot blockWithOwnedValue = new ItemSnapshot(
                IRON_BLOCK, IRON_BLOCK.itemId(), "minecraft", "iron_block", true, 100L);
        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"),
                List.of(ingotSnapshot(2L), blockWithOwnedValue), List.of(blockRecipe(9))));

        assertEquals(100L, store.snapshotOwnedValues().get(IRON_BLOCK).longValue(),
                "超过阈值的降价必须被隔离而不是写入");
        assertEquals(1L, pipeline.lastQuarantinedCount());
        assertEquals(0L, pipeline.lastSolvedCount());
    }

    @Test
    void providerManualGuardRejectsStaging() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        RejectingProvider provider = new RejectingProvider();
        ScanCollectionPipeline pipeline = pipeline(store, provider,
                dropProtection(false), overflowPipeline(false, 999_999L, 100L, 999_000L));

        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"), 9L));

        assertEquals(0, store.size(), "后端拒绝时不得绕过 manual guard 直接写 store");
        assertEquals(1, provider.stagedCalls, "必须通过 EmcProvider.stageGeneratedValue 写入");
        assertEquals(0L, pipeline.lastSolvedCount());
    }

    @Test
    void overflowProtectionResetsValueBeforeStaging() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        ScanCollectionPipeline pipeline = pipeline(store, null,
                dropProtection(false), overflowPipeline(true, 100L, 1L, 10L));

        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"), 20L));

        long stored = store.snapshotOwnedValues().get(IRON_BLOCK);
        assertTrue(stored >= 1L && stored <= 10L,
                "溢出值必须被重置到配置区间，实际为 " + stored);
        assertEquals(1L, pipeline.lastOverflowResetCount());
        assertEquals(1L, pipeline.lastSolvedCount());
    }

    @Test
    void stagedApplyIsBoundedPerBatch() throws InterruptedException {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        ScanCollectionPipeline pipeline = pipeline(store);
        List<RecipeSnapshot> recipes = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            ItemKey output = ItemKey.of(new ResourceLocation("sudoersemc_test", "out_" + index));
            recipes.add(RecipeSnapshot.of(
                    new ResourceLocation("sudoersemc_test", "recipe_" + index),
                    RecipeKind.SHAPELESS,
                    List.of(new RecipeInput(IngredientOption.of(IRON_INGOT), 1)),
                    List.of(RecipeOutput.of(output))));
        }
        ScanManager.ApplyTask task = pipeline.beginApplySnapshot(
                snapshot(fingerprint("minecraft:iron_ingot"),
                        List.of(ingotSnapshot(1L)), recipes));
        awaitSolve(task);

        assertEquals(0, task.stage(5, System.nanoTime() - 1L),
                "已过期的 deadline 不得处理任何条目");
        assertEquals(5, task.stage(5, Long.MAX_VALUE));
        assertFalse(task.isDone());
        assertEquals(5, task.stage(5, Long.MAX_VALUE));
        assertFalse(task.isDone());
        assertEquals(2, task.stage(5, Long.MAX_VALUE));
        assertTrue(task.isDone());
        assertEquals(12, store.size());
    }

    @Test
    void cancelledStagingRollsBackPartialWrites() throws InterruptedException {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        ScanCollectionPipeline pipeline = pipeline(store);
        List<RecipeSnapshot> recipes = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ItemKey output = ItemKey.of(new ResourceLocation("sudoersemc_test", "cancel_out_" + index));
            recipes.add(RecipeSnapshot.of(
                    new ResourceLocation("sudoersemc_test", "cancel_recipe_" + index),
                    RecipeKind.SHAPELESS,
                    List.of(new RecipeInput(IngredientOption.of(IRON_INGOT), 1)),
                    List.of(RecipeOutput.of(output))));
        }
        ScanManager.ApplyTask task = pipeline.beginApplySnapshot(
                snapshot(fingerprint("minecraft:iron_ingot"),
                        List.of(ingotSnapshot(1L)), recipes));
        awaitSolve(task);

        assertEquals(3, task.stage(3, Long.MAX_VALUE));
        assertEquals(3, store.size(), "取消前应已写入部分条目");

        task.cancel();

        assertEquals(0, store.size(), "取消后必须回滚本任务已写入的条目");
        assertEquals(0, task.stage(3, Long.MAX_VALUE), "取消后不得继续 staging");
    }

    @Test
    void stagedPersistCompletesAsynchronously() throws InterruptedException {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        ScanCollectionPipeline pipeline = pipeline(store);
        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"), 9L));

        ScanManager.SaveTask save = pipeline.stagedSaveHandler().beginSave();
        long deadline = System.nanoTime() + 10_000_000_000L;
        boolean done = false;
        while (System.nanoTime() < deadline) {
            if (save.advance(deadline)) {
                done = true;
                break;
            }
            Thread.sleep(1L);
        }

        assertTrue(done, "后台持久化必须在超时前完成");
        assertTrue(Files.isRegularFile(stateFile()), "后台任务必须写出 scan_state.json");
    }

    @Test
    void persistWritesStateAndSkipsUnchangedFingerprint() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        ScanCollectionPipeline pipeline = pipeline(store);
        ScanFingerprint fingerprint = fingerprint("minecraft:iron_ingot");
        pipeline.applySnapshot(snapshot(fingerprint, 9L));
        pipeline.persist();

        assertTrue(Files.isRegularFile(stateFile()), "persist 必须写出 scan_state.json");

        ScanCollectionPipeline reloaded = new ScanCollectionPipeline(null, null, null, stateFile());
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

        assertFalse(Files.exists(stateFile()));
    }

    @Test
    void applyWithoutStashedSnapshotFails() {
        ScanCollectionPipeline pipeline = pipeline(null);

        assertThrows(IllegalStateException.class,
                () -> pipeline.apply(new ScanManager.SnapshotData("空", 0, 0)));
    }

    @Test
    void datapackReloadBumpsGeneration() {
        ScanCollectionPipeline pipeline = pipeline(null);

        long before = pipeline.reloadGeneration();
        pipeline.onDatapackReload();
        pipeline.onDatapackReload();

        assertEquals(before + 2L, pipeline.reloadGeneration(),
                "reload 开始与完成事件都必须推进代次");
    }

    @Test
    void fingerprintSupplierExposesReloadGeneration() {
        ScanCollectionPipeline pipeline = pipeline(null);
        ScanManager.FingerprintSupplier supplier =
                pipeline.fingerprintSupplier(() -> fingerprint("minecraft:iron_ingot"));

        assertTrue(supplier.tracksReloadGeneration(),
                "生产指纹提供器必须声明支持廉价 reload 代次");
        long before = supplier.reloadGeneration();
        pipeline.onDatapackReload();

        assertEquals(before + 1L, supplier.reloadGeneration(),
                "指纹提供器必须读取管线的 reload 代次");
        assertEquals(fingerprint("minecraft:iron_ingot"), supplier.current());
    }

    @Test
    void saveTaskSurfacesStateFileWriteFailure() throws Exception {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("entries.json"));
        Files.createDirectory(stateFile());
        ScanCollectionPipeline pipeline = pipeline(store);
        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"), 9L));

        RuntimeException failure = awaitSaveFailure(pipeline.stagedSaveHandler().beginSave());

        assertTrue(failure != null, "scan_state.json 写入失败必须通过 SaveTask.advance 抛出");
        assertTrue(failure.getMessage() != null && failure.getMessage().contains("持久化失败"));
    }

    @Test
    void saveTaskSurfacesStoreWriteFailure() throws Exception {
        Path storePath = tempDir.resolve("entries-as-directory");
        Files.createDirectory(storePath);
        GeneratedEntryStore store = new GeneratedEntryStore(storePath);
        ScanCollectionPipeline pipeline = pipeline(store);
        pipeline.applySnapshot(snapshot(fingerprint("minecraft:iron_ingot"), 9L));
        assertTrue(store.isDirty(), "staging 后 store 应处于待写状态");

        RuntimeException failure = awaitSaveFailure(pipeline.stagedSaveHandler().beginSave());

        assertTrue(failure != null, "generated_entries.json 写入失败必须通过 SaveTask.advance 抛出");
        assertTrue(failure.getMessage() != null && failure.getMessage().contains("持久化失败"));
    }

    @Test
    void collectedSnapshotRejectsNegativeCounters() {
        assertThrows(IllegalArgumentException.class,
                () -> new ScanCollectionPipeline.CollectedSnapshot(
                        List.of(), List.of(), -1, 0, 0, fingerprint("x")));
    }

    private Path stateFile() {
        return tempDir.resolve(ScanCollectionPipeline.STATE_FILE_NAME);
    }

    private ScanCollectionPipeline pipeline(GeneratedEntryStore store) {
        return new ScanCollectionPipeline(store, null, null, stateFile());
    }

    private ScanCollectionPipeline pipeline(GeneratedEntryStore store,
                                            EmcProvider provider,
                                            LargeDropProtection dropProtection,
                                            OverflowPipeline overflowPipeline) {
        return new ScanCollectionPipeline(store, null, null, stateFile(),
                provider, dropProtection, overflowPipeline);
    }

    private LargeDropProtection dropProtection(boolean enabled) {
        return new LargeDropProtection(new LargeDropProtection.Settings(
                enabled, 0.5D, Set.of(), Set.of()));
    }

    private OverflowPipeline overflowPipeline(boolean enabled,
                                              long threshold,
                                              long resetMin,
                                              long resetMax) {
        OverflowProtection.Settings settings = new OverflowProtection.Settings(
                enabled, threshold, resetMin, resetMax, 10, 5,
                OverflowScope.OWNED_ONLY, false, 1L);
        return new OverflowPipeline(
                new OverflowProtection(settings),
                new OverflowResetLog(tempDir.resolve("overflow_resets.json")));
    }

    private static void awaitSolve(ScanManager.ApplyTask task) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!task.isSolveComplete() && System.nanoTime() < deadline) {
            task.advanceSolve(deadline);
            if (!task.isSolveComplete()) {
                Thread.sleep(1L);
            }
        }
        assertTrue(task.isSolveComplete(), "后台求解未在 10 秒内完成");
    }

    /**
     * 轮询 SaveTask 直到完成或失败。
     *
     * @return 任务抛出的异常；在 10 秒内既未完成也未失败时返回 {@code null}
     */
    private static RuntimeException awaitSaveFailure(ScanManager.SaveTask task)
            throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            try {
                if (task.advance(deadline)) {
                    return null;
                }
            } catch (RuntimeException e) {
                return e;
            }
            Thread.sleep(1L);
        }
        return null;
    }

    private static ItemSnapshot ingotSnapshot(long value) {
        return new ItemSnapshot(IRON_INGOT, IRON_INGOT.itemId(), "minecraft",
                "iron_ingot", true, value);
    }

    private static ScanCollectionPipeline.CollectedSnapshot snapshot(ScanFingerprint fingerprint,
                                                                     long ingotValue) {
        ItemSnapshot block = new ItemSnapshot(IRON_BLOCK, IRON_BLOCK.itemId(), "minecraft",
                "iron_block", false, 0L);
        return snapshot(fingerprint, List.of(ingotSnapshot(ingotValue), block),
                List.of(blockRecipe(9)));
    }

    private static ScanCollectionPipeline.CollectedSnapshot snapshot(
            ScanFingerprint fingerprint,
            List<ItemSnapshot> items,
            List<RecipeSnapshot> recipes) {
        return new ScanCollectionPipeline.CollectedSnapshot(
                items, recipes, 0, 0, 0, fingerprint);
    }

    private static RecipeSnapshot blockRecipe(int ingotCount) {
        return RecipeSnapshot.of(
                BLOCK_RECIPE_ID,
                RecipeKind.SHAPED,
                List.of(new RecipeInput(IngredientOption.of(IRON_INGOT), ingotCount)),
                List.of(RecipeOutput.of(IRON_BLOCK)));
    }

    private static ScanFingerprint fingerprint(String itemId) {
        return ScanFingerprint.of(
                List.of(itemId),
                List.of("sudoersemc_test:iron_block_from_ingots"),
                List.of(),
                List.of(),
                List.of());
    }

    /**
     * 模拟 ProjectE 后端拒绝写入（例如管理员手工值）。
     */
    private static final class RejectingProvider implements EmcProvider {

        private int stagedCalls;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean hasValue(ItemStack stack) {
            return false;
        }

        @Override
        public long getValue(ItemStack stack) {
            return 0L;
        }

        @Override
        public boolean stageGeneratedValue(ItemKey key, long value, PriceSource source,
                                           ResourceLocation recipeId) {
            stagedCalls++;
            return false;
        }

        @Override
        public void requestRemap(MinecraftServer server) {
        }
    }
}
