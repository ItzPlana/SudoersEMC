package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        assertFalse(task.isDone(), "最后一批写入后必须等待 ScanManager 的提交门，不能自行 commit");
        assertTrue(task.isStagingComplete(), "全部条目应已写入 staging");
        task.commit();
        assertTrue(task.isDone(), "提交后任务才完成");
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
    void incrementalFingerprintMatchesFullReference() {
        List<String> items = List.of(
                "minecraft:stone",
                "minecraft:air",
                "minecraft:iron_ingot",
                "minecraft:stone");
        List<RecipeSnapshot> recipes = List.of(
                blockRecipe(9),
                blockRecipe(4),
                blockRecipe(9));
        Map<String, List<String>> tags = Map.of(
                "c:ingots", List.of("minecraft:iron_ingot", "minecraft:gold_ingot"),
                "c:stones", List.of("minecraft:stone"),
                "c:empty", List.of());
        List<String> config = List.of("general.enabled=true", "performance.itemsPerTick=100");
        List<String> baseline = List.of("projecte.available=false");

        ScanFingerprint reference = ScanFingerprint.of(
                items,
                ScanCollectionPipeline.recipeEntries(recipes),
                ScanCollectionPipeline.tagEntries(tags),
                config,
                baseline);

        ScanCollectionPipeline.FingerprintAccumulator accumulator =
                new ScanCollectionPipeline.FingerprintAccumulator();
        // 故意用与参考实现相反的输入顺序，证明增量累加仍会得到同一排序结果。
        for (int index = items.size() - 1; index >= 0; index--) {
            accumulator.addItem(items.get(index));
        }
        for (int index = recipes.size() - 1; index >= 0; index--) {
            accumulator.addRecipe(recipes.get(index));
        }
        List<Map.Entry<String, List<String>>> tagEntries = new ArrayList<>(tags.entrySet());
        for (int index = tagEntries.size() - 1; index >= 0; index--) {
            Map.Entry<String, List<String>> entry = tagEntries.get(index);
            accumulator.addTag(entry.getKey(), entry.getValue());
        }

        assertEquals(reference, accumulator.finish(config, baseline).join(),
                "增量指纹必须与旧全量实现逐字节等价（含重复值与空通道）");

        ScanCollectionPipeline.FingerprintAccumulator empty =
                new ScanCollectionPipeline.FingerprintAccumulator();
        assertEquals(ScanFingerprint.of(List.of(), List.of(), List.of(), config, baseline),
                empty.finish(config, baseline).join(),
                "空指纹通道必须保持 EMPTY 语义");
    }

    @Test
    void reloadDuringFinalStagingBatchRollsBackUncommittedValues() throws InterruptedException {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("entries-final-reload.json"));
        AtomicInteger afterApplyCalls = new AtomicInteger();
        AtomicReference<Runnable> reloadTrigger = new AtomicReference<>();

        // 8 条配方，最后一条 provider 写入时模拟 /reload。
        List<RecipeSnapshot> recipes = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            ItemKey output = ItemKey.of(
                    new ResourceLocation("sudoersemc_test", "final_out_" + index));
            recipes.add(RecipeSnapshot.of(
                    new ResourceLocation("sudoersemc_test", "final_recipe_" + index),
                    RecipeKind.SHAPELESS,
                    List.of(new RecipeInput(IngredientOption.of(IRON_INGOT), 1)),
                    List.of(RecipeOutput.of(output))));
        }
        TriggeringProvider provider = new TriggeringProvider(
                store, 8, () -> reloadTrigger.get().run());
        ScanCollectionPipeline pipeline = new ScanCollectionPipeline(
                store, null, null, stateFile(), provider,
                dropProtection(false), overflowPipeline(false, 999_999L, 100L, 999_000L),
                afterApplyCalls::incrementAndGet);
        reloadTrigger.set(pipeline::onDatapackReload);

        // 真实 ApplyJob：所有 8 条会在一个 staging 批次内写完。
        ScanManager.ApplyTask realTask = pipeline.beginApplySnapshot(
                snapshot(fingerprint("minecraft:iron_ingot"),
                        List.of(ingotSnapshot(1L)), recipes));

        ScanManager manager = ScanManager.staged(
                new BatchProcessor(8, false),
                completedCollector(1, recipes.size()),
                data -> realTask,
                50_000_000L,
                pipeline.fingerprintSupplier(() -> fingerprint("minecraft:iron_ingot")),
                null);

        assertTrue(manager.requestScan());
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (manager.isRunning() && System.nanoTime() < deadline) {
            manager.tick();
            if (manager.progress().phase() == ScanPhase.SOLVING) {
                Thread.sleep(1L);
            }
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase(),
                "最后一批内部 reload 必须让扫描失败");
        assertEquals(8, provider.accepted(),
                "reload 必须在第 8 条（最后一条）写入后触发，确保覆盖最后一批窗口");
        assertTrue(realTask.isStagingComplete(),
                "失败前最后一批必须已经全部写入 staging");
        assertEquals(0, store.size(),
                "尚未 commit 的最后一批必须被 rollbackPartialStaging 回滚");
        assertFalse(realTask.isDone(), "后置校验失败时不得提交任务");
        assertNull(pipeline.lastAppliedFingerprint(),
                "后置校验失败时不得写入 lastAppliedFingerprint");
        assertEquals(0, afterApplyCalls.get(), "后置校验失败时不得执行 afterApply");
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

    private static ScanManager.SnapshotCollector completedCollector(int items, int recipes) {
        return () -> new ScanManager.CollectSession() {
            @Override
            public int itemCount() {
                return items;
            }

            @Override
            public int recipeCount() {
                return recipes;
            }

            @Override
            public int processedItems() {
                return items;
            }

            @Override
            public int processedRecipes() {
                return recipes;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public ScanManager.StepResult step(int maxItems) {
                return new ScanManager.StepResult(0, true);
            }

            @Override
            public ScanManager.SnapshotData finish() {
                return new ScanManager.SnapshotData("测试快照", items, recipes);
            }
        };
    }

    /**
     * 把生成值写入 store，并在第 {@code triggerAt} 次成功写入时触发一次 reload。
     */
    private static final class TriggeringProvider implements EmcProvider {

        private final GeneratedEntryStore sink;
        private final int triggerAt;
        private final Runnable trigger;
        private int accepted;

        TriggeringProvider(GeneratedEntryStore sink, int triggerAt, Runnable trigger) {
            this.sink = sink;
            this.triggerAt = triggerAt;
            this.trigger = trigger;
        }

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
            boolean staged = sink.stage(new GeneratedEmcEntry(
                    key, value, source, recipeId, true, 0L));
            if (staged && ++accepted == triggerAt) {
                trigger.run();
            }
            return staged;
        }

        @Override
        public void requestRemap(MinecraftServer server) {
        }

        int accepted() {
            return accepted;
        }
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
