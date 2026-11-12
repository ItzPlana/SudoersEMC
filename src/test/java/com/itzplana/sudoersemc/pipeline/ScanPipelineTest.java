package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.PricingSettings;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
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
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Rarity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ScanPipeline} 的核心行为测试。
 *
 * <p>测试全部通过手工构造的 {@link PipelineSnapshot} 驱动，不启动服务器，也不访问物品注册表或
 * 配方管理器。存储、报告与审计日志使用 JUnit 的临时目录，确保测试之间没有磁盘状态泄漏。</p>
 */
class ScanPipelineTest {

    private static final ResourceLocation INGOT_ID =
            new ResourceLocation("sudoersemc", "iron_ingot");
    private static final ResourceLocation RESULT_ID =
            new ResourceLocation("sudoersemc", "iron_block");
    private static final ResourceLocation DUST_ID =
            new ResourceLocation("sudoersemc", "iron_dust");
    private static final ResourceLocation RAW_ID =
            new ResourceLocation("sudoersemc", "raw_iron");
    private static final ResourceLocation RECIPE_ID =
            new ResourceLocation("sudoersemc", "iron_block_from_ingot");
    private static final ResourceLocation RAW_TO_INGOT_ID =
            new ResourceLocation("sudoersemc", "ingot_from_raw");
    private static final ResourceLocation INGOT_TO_BLOCK_ID =
            new ResourceLocation("sudoersemc", "iron_block_from_ingot_chain");

    private static final ItemKey INGOT = ItemKey.of(INGOT_ID);
    private static final ItemKey RESULT = ItemKey.of(RESULT_ID);
    private static final ItemKey DUST = ItemKey.of(DUST_ID);
    private static final ItemKey RAW = ItemKey.of(RAW_ID);

    @TempDir
    Path tempDir;

    @Test
    void emptySnapshotProducesZeroResult() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_empty.json"));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(0, result.solvedByRecipe());
        assertEquals(0, result.solvedByFallback());
        assertEquals(0, result.applied());
        assertEquals(0, result.quarantined());
        assertEquals(0, result.overflowResets());
        assertEquals(0, result.unresolved());
        assertEquals(0L, result.sourceTotal());
        assertTrue(result.appliedToStore());
        assertEquals(0, store.size());
        assertFalse(store.isDirty());
    }

    @Test
    void collectWithoutServerFailsInsteadOfReturningEmptySnapshot() {
        ScanPipeline pipeline = pipeline(null, null, null, null, null, null, disabledFallback());

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> pipeline.collect(null));

        assertTrue(failure.getMessage().contains("扫描采集失败"),
                "采集失败必须带可诊断信息，不能静默返回空快照");
    }

    @Test
    void failedCollectionDoesNotRecordCompletedScanState() {
        ScanStateStore stateStore = new ScanStateStore(tempDir.resolve("scan_state_failure.json"));
        ScanPipeline pipeline = pipeline(null, stateStore, null, null, null, null, disabledFallback());
        ScanManager manager = new ScanManager(
                new BatchProcessor(10, false),
                () -> {
                    PipelineSnapshot snapshot = pipeline.collect(null);
                    pipeline.stash(snapshot);
                    return ScanManager.oneShot(new ScanManager.SnapshotData(
                            "测试采集", snapshot.itemCount(), snapshot.recipeCount())).begin();
                },
                data -> pipeline.applyStashed(),
                1_000_000L);

        assertTrue(manager.requestScan());
        manager.tick();

        assertEquals(ScanPhase.FAILED, manager.progress().phase(),
                "采集失败必须让扫描进入 FAILED");
        assertEquals(0L, stateStore.lastScanEpochMillis(),
                "失败采集不得写入扫描完成时间");
        assertFalse(stateStore.isDirty(),
                "失败采集不得把空指纹标记为待保存状态");
    }

    @Test
    void itemWithExistingEmcIsNotPriced() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir);
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_existing.json"));
        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 50L)), List.of(), Map.of(RESULT, 50L));
        ScanPipeline pipeline = pipeline(store, null, reportManager, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByRecipe());
        assertEquals(0, result.solvedByFallback());
        assertEquals(0, result.applied());
        assertEquals(0, store.size());

        String report = Files.readString(reportManager.reportFile());
        assertTrue(hasCounterLine(report, "已有 EMC", 1L),
                "诊断报告应记录一个已有 EMC 的物品");
    }

    @Test
    void recipeValueIsStagedAsRecipeSource() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_recipe.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByRecipe());
        assertEquals(0, result.solvedByFallback());
        assertEquals(1, result.applied());

        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged, "配方输出应被写入存储");
        assertEquals(5L, staged.value());
        assertEquals(PriceSource.RECIPE, staged.source());
        assertEquals(RECIPE_ID, staged.recipeId());
        assertTrue(staged.owned());
        assertNull(store.snapshotAll().get(INGOT), "已有 EMC 的输入物品不应被重新写入");
    }

    @Test
    void unsolvableItemUsesFallbackPricing() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_fallback.json"));
        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, genericFallback(7L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByRecipe());
        assertEquals(1, result.solvedByFallback());
        assertEquals(1, result.applied());

        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(7L, staged.value());
        assertEquals(PriceSource.FALLBACK, staged.source());
        assertNull(staged.recipeId());
    }

    @Test
    void creativeExclusionPreventsPricing() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_creative.json"));
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(true, Set.of(RESULT_ID), Map.of(), Set.of()));
        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null, genericFallback(7L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByFallback());
        assertEquals(0, result.applied());
        assertEquals(0, store.size());
    }

    @Test
    void largePriceDropIsQuarantined() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_drop.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 1_000L, PriceSource.RECIPE, null, true, 0L));
        LargeDropProtection dropProtection =
                new LargeDropProtection(LargeDropProtection.Settings.defaults());

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 100L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 100L));
        ScanPipeline pipeline = pipeline(
                store, null, null, dropProtection, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByRecipe());
        assertEquals(1, result.quarantined());
        assertEquals(0, result.applied());
        assertEquals(1, dropProtection.quarantineCount());
        assertEquals(1_000L, store.snapshotAll().get(RESULT).value(),
                "被隔离的降价不得覆盖旧值");
    }

    @Test
    void priceDropWithinThresholdIsApplied() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_drop_ok.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 1_000L, PriceSource.RECIPE, null, true, 0L));
        LargeDropProtection dropProtection =
                new LargeDropProtection(LargeDropProtection.Settings.defaults());

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 800L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 800L));
        ScanPipeline pipeline = pipeline(
                store, null, null, dropProtection, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.quarantined());
        assertEquals(1, result.applied());
        assertEquals(800L, store.snapshotAll().get(RESULT).value());
    }

    @Test
    void overflowValueIsResetAndAudited() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_overflow.json"));
        OverflowProtection.Settings overflowSettings = new OverflowProtection.Settings(
                true, 100L, 1L, 10L, 10, 5, OverflowScope.OWNED_ONLY, false, 42L);
        Path auditFile = tempDir.resolve(OverflowResetLog.FILE_NAME);
        OverflowResetLog resetLog = new OverflowResetLog(auditFile);
        OverflowPipeline overflowPipeline =
                new OverflowPipeline(new OverflowProtection(overflowSettings), resetLog);

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 1_000L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 1_000L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, overflowPipeline, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.overflowResets());
        assertEquals(1, result.applied());
        assertEquals(1, overflowPipeline.resetsApplied());
        assertEquals(1, overflowPipeline.auditCount());

        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertTrue(staged.value() >= 1L && staged.value() <= 10L,
                "溢出回落值必须落在 [resetMin, resetMax] 内，实际为 " + staged.value());

        assertTrue(Files.isRegularFile(auditFile), "扫描应用后审计日志必须已原子落盘");
        OverflowResetLog persisted = new OverflowResetLog(auditFile);
        assertEquals(1, persisted.entries().size(), "本轮溢出重置必须在扫描结束时持久化");
    }

    @Test
    void nullStoreRunsInDryRunMode() {
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(null, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByRecipe());
        assertEquals(1, result.applied());
        assertEquals(1L, result.sourceTotal());
        assertFalse(result.appliedToStore());
    }

    @Test
    void diagnosticReportIsWritten() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir);
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_report.json"));
        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, reportManager, null, null, null, genericFallback(7L));

        pipeline.solveAndApply(snapshot);

        Path reportFile = reportManager.reportFile();
        assertTrue(Files.isRegularFile(reportFile), "求解完成后必须写出诊断报告");
        String report = Files.readString(reportFile);
        assertTrue(report.contains("SudoersEMC 诊断报告"));
        assertTrue(hasCounterLine(report, "兜底定价", 1L));
        assertTrue(hasCounterLine(report, "未解析物品", 0L));
    }

    @Test
    void scanStateIsUpdatedAndPersisted() {
        Path stateFile = tempDir.resolve("scan_state.json");
        ScanStateStore stateStore = new ScanStateStore(stateFile);
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_state.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(store, stateStore, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        ScanFingerprint expected = ScanFingerprint.of(
                List.of(INGOT_ID.toString(), RESULT_ID.toString()),
                List.of(RECIPE_ID.toString()),
                List.of(), List.of(), List.of());
        assertTrue(stateStore.matches(expected));
        assertEquals(result.applied(), stateStore.lastSolvedCount());
        assertEquals(0, stateStore.lastUnresolvedCount());
        assertTrue(stateStore.isDirty());

        stateStore.saveIfDirty();
        ScanStateStore reloaded = new ScanStateStore(stateFile);
        reloaded.load();
        assertTrue(reloaded.matches(expected));
        assertEquals(result.applied(), reloaded.lastSolvedCount());
    }

    @Test
    void sourceBreakdownMatchesStagedEntries() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_sources.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L), item(DUST, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, genericFallback(7L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(2, result.applied());
        assertEquals(2, store.size());
        assertEquals(2L, result.sourceTotal());
        assertEquals(1L, result.sourceBreakdown().get(PriceSource.RECIPE.name()));
        assertEquals(1L, result.sourceBreakdown().get(PriceSource.FALLBACK.name()));
    }

    @Test
    void stashedSnapshotIsAppliedOnlyOnce() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_stash.json"));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, genericFallback(7L));
        pipeline.stash(snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of()));

        PipelineResult first = pipeline.applyStashed();
        PipelineResult second = pipeline.applyStashed();

        assertEquals(1, first.applied());
        assertEquals(0, second.applied());
        assertEquals(0L, second.sourceTotal());
        assertEquals(1, store.size());
    }

    @Test
    void existingEmcWinsOverRecipeValue() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_seed_wins.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 50L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L, RESULT, 50L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByRecipe());
        assertEquals(0, result.applied());
        assertEquals(0, store.size());
    }

    @Test
    void ownershipSeparationRefreshesOwnedEntriesOnly() {
        ItemSnapshot owned = ItemSnapshot.of(RESULT, true, 5L,
                List.of("forge:ingots/iron"), Rarity.RARE);
        ItemSnapshot manual = ItemSnapshot.of(DUST, true, 50L,
                List.of("forge:dusts"), Rarity.UNCOMMON);
        ItemSnapshot clean = ItemSnapshot.of(INGOT, false, 0L);

        ScanPipeline.OwnershipSeparation separation = ScanPipeline.separateOwnedEntries(
                List.of(owned, manual, clean), key -> key.equals(RESULT));

        assertEquals(1, separation.refreshedOwned());
        assertEquals(List.of(RESULT, DUST, INGOT),
                separation.items().stream().map(ItemSnapshot::key).toList());

        ItemSnapshot refreshed = separation.items().get(0);
        assertFalse(refreshed.hasExistingEmc());
        assertEquals(0L, refreshed.existingValue());
        assertEquals(owned.tagPaths(), refreshed.tagPaths());
        assertEquals(Rarity.RARE, refreshed.rarity());

        assertEquals(manual, separation.items().get(1), "非自有条目必须保持原样");
        assertEquals(clean, separation.items().get(2), "无既有值条目必须保持原样");
        assertFalse(separation.existingValues().containsKey(RESULT), "自有值必须从既有值中移除");
        assertEquals(50L, separation.existingValues().get(DUST));
        assertFalse(separation.existingValues().containsKey(INGOT));
    }

    @Test
    void ownedRecipeValueIsRefreshedFromRecipe() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_owned_recipe.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 5L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 10L), item(RESULT, 5L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 10L, RESULT, 5L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByRecipe());
        assertEquals(1, result.applied());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(10L, staged.value(), "自有值必须随配方输入刷新");
        assertEquals(PriceSource.RECIPE, staged.source());
        assertTrue(staged.owned());
    }

    @Test
    void ownedFallbackValueIsRefreshed() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_owned_fallback.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 5L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 5L)), List.of(), Map.of(RESULT, 5L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, genericFallback(7L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByFallback());
        assertEquals(1, result.applied());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(7L, staged.value(), "自有值必须允许兜底定价刷新");
        assertEquals(PriceSource.FALLBACK, staged.source());
        assertTrue(staged.owned());
    }

    @Test
    void ownedMultiLevelChainRefreshesFromOwnedLeafFallback() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir);
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_owned_chain.json"));
        store.stage(new GeneratedEmcEntry(RAW, 5L, PriceSource.TAG, null, true, 0L));
        store.stage(new GeneratedEmcEntry(INGOT, 5L, PriceSource.RECIPE, null, true, 0L));
        store.stage(new GeneratedEmcEntry(RESULT, 5L, PriceSource.RECIPE, null, true, 0L));

        ItemSnapshot raw = ItemSnapshot.of(RAW, true, 5L,
                List.of("forge:raw_materials/iron"), Rarity.COMMON);
        PipelineSnapshot snapshot = snapshot(
                List.of(raw, item(INGOT, 5L), item(RESULT, 5L)),
                List.of(recipe(RAW_TO_INGOT_ID, RAW, INGOT),
                        recipe(INGOT_TO_BLOCK_ID, INGOT, RESULT)),
                Map.of(RAW, 5L, INGOT, 5L, RESULT, 5L));
        ScanPipeline pipeline = pipeline(store, null, reportManager, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(2, result.solvedByRecipe());
        assertEquals(1, result.solvedByFallback());
        assertEquals(3, result.applied());
        assertEquals(0, result.unresolved());

        GeneratedEmcEntry rawEntry = store.snapshotAll().get(RAW);
        assertNotNull(rawEntry);
        assertEquals(128L, rawEntry.value(), "自有原料应按 RAW_MATERIAL 标签刷新");
        assertEquals(PriceSource.TAG, rawEntry.source());

        GeneratedEmcEntry ingotEntry = store.snapshotAll().get(INGOT);
        assertNotNull(ingotEntry);
        assertEquals(128L, ingotEntry.value(), "一级产物必须使用原料的新兜底值");
        assertEquals(PriceSource.RECIPE, ingotEntry.source());

        GeneratedEmcEntry blockEntry = store.snapshotAll().get(RESULT);
        assertNotNull(blockEntry);
        assertEquals(128L, blockEntry.value(), "二级产物必须在同一轮沿链条刷新");
        assertEquals(PriceSource.RECIPE, blockEntry.source());

        String report = Files.readString(reportManager.reportFile());
        assertTrue(hasCounterLine(report, "已有 EMC", 0L),
                "分离后标准字段只统计非自有值");
        assertTrue(hasCounterLine(report, "ownedRefreshed", 3L),
                "诊断报告必须单独暴露本轮刷新的自有条目数");
    }

    @Test
    void ownedLargeDropRemainsQuarantined() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_owned_drop.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 1_000L, PriceSource.RECIPE, null, true, 0L));
        LargeDropProtection dropProtection =
                new LargeDropProtection(LargeDropProtection.Settings.defaults());

        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 1_000L)), List.of(), Map.of(RESULT, 1_000L));
        ScanPipeline pipeline = pipeline(
                store, null, null, dropProtection, null, null, genericFallback(7L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByFallback());
        assertEquals(1, result.quarantined());
        assertEquals(0, result.applied());
        assertEquals(1_000L, store.snapshotAll().get(RESULT).value(),
                "自有条目的大幅降价仍应被隔离，旧值不得被覆盖");
    }

    @Test
    void externalValueRemainsProtected() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_external.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 50L, PriceSource.EXISTING, null, false, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 50L)), List.of(), Map.of(RESULT, 50L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, genericFallback(7L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByFallback());
        assertEquals(0, result.applied());
        assertEquals(1, store.size());
        GeneratedEmcEntry existing = store.snapshotAll().get(RESULT);
        assertNotNull(existing);
        assertEquals(50L, existing.value(), "非自有值不得被兜底定价覆盖");
        assertFalse(existing.owned());
    }

    @Test
    void taggedSnapshotProducesTagCandidate() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_tag.json"));
        ItemSnapshot tagged = ItemSnapshot.of(RESULT, false, 0L,
                List.of("forge:ingots/iron"), Rarity.COMMON);
        PipelineSnapshot snapshot = snapshot(List.of(tagged), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByFallback());
        assertEquals(1, result.applied());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(256L, staged.value(), "forge:ingots/iron 应命中默认 INGOT 锚点");
        assertEquals(PriceSource.TAG, staged.source());
    }

    @Test
    void rareSnapshotWithoutTagsProducesRarityCandidate() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_rarity.json"));
        ItemSnapshot rare = ItemSnapshot.of(RESULT, false, 0L, List.of(), Rarity.RARE);
        PipelineSnapshot snapshot = snapshot(List.of(rare), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByFallback());
        assertEquals(1, result.applied());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(256L, staged.value(), "RARE 稀有度应使用默认 256 基准值");
        assertEquals(PriceSource.RARITY, staged.source());
    }

    /**
     * 创建使用默认求解参数的管线，未提供的协作者由管线自行采用安全默认值。
     */
    private static ScanPipeline pipeline(GeneratedEntryStore store,
                                         ScanStateStore stateStore,
                                         ReportManager reportManager,
                                         LargeDropProtection dropProtection,
                                         CreativeItemProtection creativeProtection,
                                         OverflowPipeline overflowPipeline,
                                         FallbackPricing fallbackPricing) {
        return new ScanPipeline(
                store,
                stateStore,
                reportManager,
                dropProtection,
                creativeProtection,
                overflowPipeline,
                fallbackPricing,
                null);
    }

    /**
     * 关闭所有兜底来源的定价链，用于只验证配方求解路径的测试。
     */
    private static FallbackPricing disabledFallback() {
        return FallbackPricing.withDefaults(
                new PricingSettings(false, false, false, false, 0L));
    }

    /**
     * 只启用通用兜底值的定价链。
     */
    private static FallbackPricing genericFallback(long value) {
        return FallbackPricing.withDefaults(
                new PricingSettings(false, false, false, true, value));
    }

    private static ItemSnapshot item(ItemKey key, long existingValue) {
        return ItemSnapshot.of(key, existingValue > 0L, existingValue);
    }

    private static PipelineSnapshot snapshot(List<ItemSnapshot> items,
                                             List<RecipeSnapshot> recipes,
                                             Map<ItemKey, Long> existingValues) {
        return new PipelineSnapshot(items, recipes, existingValues, 0, 0, 0);
    }

    /**
     * 构造一条单输入单输出的确定性配方。
     */
    private static RecipeSnapshot recipe(ItemKey input, ItemKey output) {
        return recipe(RECIPE_ID, input, output);
    }

    /**
     * 构造带指定 id 的单输入单输出配方，便于测试多级链条。
     */
    private static RecipeSnapshot recipe(ResourceLocation id, ItemKey input, ItemKey output) {
        return RecipeSnapshot.of(
                id,
                RecipeKind.SHAPED,
                List.of(RecipeInput.of(IngredientOption.of(input))),
                List.of(RecipeOutput.of(output)));
    }

    /**
     * 判断报告文本中是否存在给定标签且计数匹配的行。
     */
    private static boolean hasCounterLine(String report, String label, long value) {
        String suffix = ": " + value;
        return report.lines().anyMatch(line -> line.startsWith(label) && line.endsWith(suffix));
    }
}
