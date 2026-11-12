package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import com.itzplana.sudoersemc.safety.OverflowReset;
import com.itzplana.sudoersemc.scanner.IngredientOption;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeKind;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeScanner;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanDiagnostics;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
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
    private static final ResourceLocation NBT_RECIPE_ID =
            new ResourceLocation("sudoersemc", "stone_with_nbt");
    private static final ResourceLocation STONE_ID =
            new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation SPAWN_EGG_ID =
            new ResourceLocation("minecraft", "bedrock_spawn_egg");
    private static final ResourceLocation BEDROCK_ID =
            new ResourceLocation("minecraft", "bedrock");
    private static final ResourceLocation ORE_ID =
            new ResourceLocation("sudoersemc", "test_ore");
    private static final ResourceLocation GEAR_ID =
            new ResourceLocation("sudoersemc", "test_gear");
    private static final ResourceLocation ORE_TO_INGOT_RECIPE_ID =
            new ResourceLocation("sudoersemc", "ingot_from_test_ore");
    private static final ResourceLocation GEAR_RECIPE_ID =
            new ResourceLocation("sudoersemc", "test_gear_from_ingot");
    private static final ResourceLocation RAW_TO_INGOT_ID =
            new ResourceLocation("sudoersemc", "ingot_from_raw");
    private static final ResourceLocation INGOT_TO_BLOCK_ID =
            new ResourceLocation("sudoersemc", "iron_block_from_ingot_chain");

    private static final ItemKey INGOT = ItemKey.of(INGOT_ID);
    private static final ItemKey RESULT = ItemKey.of(RESULT_ID);
    private static final ItemKey DUST = ItemKey.of(DUST_ID);
    private static final ItemKey STONE = ItemKey.of(STONE_ID);
    private static final ItemKey BEDROCK = ItemKey.of(BEDROCK_ID);
    private static final ItemKey ORE = ItemKey.of(ORE_ID);
    private static final ItemKey GEAR = ItemKey.of(GEAR_ID);
    private static final ItemKey RAW = ItemKey.of(RAW_ID);

    private static final ResourceLocation OWNED_OVERFLOW_ID =
            new ResourceLocation("sudoersemc", "owned_overflow");
    private static final ResourceLocation MANUAL_OVERFLOW_ID =
            new ResourceLocation("sudoersemc", "manual_overflow");

    private static final ItemKey OWNED_OVERFLOW = ItemKey.of(OWNED_OVERFLOW_ID);
    private static final ItemKey MANUAL_OVERFLOW = ItemKey.of(MANUAL_OVERFLOW_ID);

    static {
        // 变体扫描需要 BuiltInRegistries.ITEM，因此在测试类初始化时完成原版引导。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

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
        pipeline.writePendingReport();

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
    void recipeAlternativeCostsArePersistedInGeneratedEntry() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_alternatives.json"));
        ResourceLocation expensiveRecipeId =
                new ResourceLocation("sudoersemc", "iron_block_from_two_ingots");
        RecipeSnapshot cheap = recipe(INGOT, RESULT);
        RecipeSnapshot expensive = RecipeSnapshot.of(
                expensiveRecipeId,
                RecipeKind.SHAPED,
                List.of(new RecipeInput(IngredientOption.of(INGOT), 2)),
                List.of(RecipeOutput.of(RESULT)));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(cheap, expensive),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByRecipe());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(5L, staged.value());
        assertEquals(List.of(10L), staged.alternativeCosts(),
                "另一条配方的成本必须随条目持久化");
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
    void largePriceDropIsRecordedInPersistentQuarantineStore() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_drop_persist.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 1_000L, PriceSource.RECIPE, null, true, 0L));
        QuarantinedDropsStore quarantineStore = new QuarantinedDropsStore(
                tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        LargeDropProtection dropProtection =
                new LargeDropProtection(LargeDropProtection.Settings.defaults());

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 100L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 100L));
        ScanPipeline pipeline = pipeline(
                store, null, null, dropProtection, null, null, disabledFallback());
        pipeline.setQuarantinedDropsStore(quarantineStore);

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.quarantined());
        assertEquals(1, quarantineStore.size(), "隔离记录存储必须新增一条记录");
        QuarantinedDropsStore.QuarantinedDrop drop = quarantineStore.entries().get(0);
        assertEquals(RESULT, drop.key());
        assertEquals(1_000L, drop.previousValue());
        assertEquals(100L, drop.proposedValue());
        assertEquals(0.9D, drop.dropRatio(), 1.0E-9D);
        assertEquals("drop-exceeds-threshold", drop.reason());
        assertFalse(drop.approved());
        assertTrue(drop.timestamp() > 0L);
        assertTrue(quarantineStore.isDirty(), "记录后必须等待统一落盘");
    }

    @Test
    void approvedQuarantineIsPreservedWhenSameDropIsReevaluated() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_drop_approved.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 1_000L, PriceSource.RECIPE, null, true, 0L));
        QuarantinedDropsStore quarantineStore = new QuarantinedDropsStore(
                tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        LargeDropProtection dropProtection =
                new LargeDropProtection(LargeDropProtection.Settings.defaults());

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 100L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 100L));
        ScanPipeline pipeline = pipeline(
                store, null, null, dropProtection, null, null, disabledFallback());
        pipeline.setQuarantinedDropsStore(quarantineStore);

        assertEquals(1, pipeline.solveAndApply(snapshot).quarantined());
        assertTrue(quarantineStore.approve(RESULT));

        PipelineResult second = pipeline.solveAndApply(snapshot);

        assertEquals(1, second.quarantined(), "审批只做审计，下一次扫描仍执行降价保护");
        assertTrue(quarantineStore.entries().get(0).approved(),
                "相同经济判定再次隔离时不得清除审批标记");
        assertEquals(1_000L, store.snapshotAll().get(RESULT).value(), "审批不得修改 EMC");
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
    void overflowResetDropsSolverAlternativeCosts() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_overflow_alternatives.json"));
        OverflowProtection.Settings settings = new OverflowProtection.Settings(
                true, 50L, 1L, 10L, 10, 5, OverflowScope.OWNED_ONLY, false, 42L);
        OverflowResetLog resetLog = new OverflowResetLog(tempDir.resolve("overflow_resets.json"));
        OverflowPipeline overflowPipeline =
                new OverflowPipeline(new OverflowProtection(settings), resetLog);
        ResourceLocation expensiveRecipeId =
                new ResourceLocation("sudoersemc", "iron_block_from_two_ingots");
        RecipeSnapshot cheap = recipe(INGOT, RESULT);
        RecipeSnapshot expensive = RecipeSnapshot.of(
                expensiveRecipeId,
                RecipeKind.SHAPED,
                List.of(new RecipeInput(IngredientOption.of(INGOT), 2)),
                List.of(RecipeOutput.of(RESULT)));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 1_000L), item(RESULT, 0L)),
                List.of(cheap, expensive),
                Map.of(INGOT, 1_000L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, overflowPipeline, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.overflowResets());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertTrue(staged.value() >= 1L && staged.value() <= 10L,
                "溢出回落值必须落在 [1, 10] 内，实际为 " + staged.value());
        assertTrue(staged.alternativeCosts().isEmpty(),
                "溢出改变数值后备选成本不再可比，必须丢弃");
    }

    @Test
    void scanStartClearsInMemoryQuarantineHistory() {
        LargeDropProtection dropProtection =
                new LargeDropProtection(LargeDropProtection.Settings.defaults());
        dropProtection.evaluate(GEAR, 1_000L, 100L);
        assertEquals(1, dropProtection.quarantineCount());

        ScanPipeline pipeline = pipeline(
                null, null, null, dropProtection, null, null, disabledFallback());
        pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(0, dropProtection.quarantineCount(),
                "持久化存储接管后，每轮扫描必须清空内存隔离列表");
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
        pipeline.writePendingReport();

        Path reportFile = reportManager.reportFile();
        assertTrue(Files.isRegularFile(reportFile), "完成回调必须写出诊断报告");
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

        // Phase 22：完整报告延迟到扫描完成回调写出。
        pipeline.writePendingReport();
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

    @Test
    void fallbackSeedPropagatesThroughRecipeChain() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_chain.json"));
        ItemSnapshot ore = ItemSnapshot.of(ORE, false, 0L, List.of("forge:ores/test"), Rarity.COMMON);
        PipelineSnapshot snapshot = snapshot(
                List.of(ore, item(INGOT, 0L), item(GEAR, 0L)),
                List.of(oreToIngotRecipe(), gearRecipe()),
                Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, tagOnlyFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(2, result.solvedByRecipe(), "锭与齿轮都应由配方定价");
        assertEquals(1, result.solvedByFallback(), "矿石应由标签兜底定价");
        assertEquals(3, result.applied());
        assertTrue(pipeline.lastSolverRoundCount() > 1
                        && pipeline.lastSolverRoundCount() <= ScanPipeline.MAX_SOLVE_ROUNDS,
                "兜底种子链路应在轮数上限内收敛，实际轮数为 "
                        + pipeline.lastSolverRoundCount());

        GeneratedEmcEntry ingot = store.snapshotAll().get(INGOT);
        GeneratedEmcEntry gear = store.snapshotAll().get(GEAR);
        assertNotNull(ingot);
        assertNotNull(gear);
        assertEquals(PriceSource.RECIPE, ingot.source());
        assertEquals(PriceSource.RECIPE, gear.source());
        assertEquals(128L, ingot.value(), "forge:ores/test 应命中默认 ORE 锚点");
        assertEquals(4L * ingot.value(), gear.value(),
                "齿轮值必须等于 4 倍锭值，证明兜底值已沿链传播");
    }

    @Test
    void fallbackSeedPropagatesThroughRecipeChainWithDefaultPricing() throws IOException {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_chain_default.json"));
        ScanStateStore stateStore = new ScanStateStore(
                tempDir.resolve("scan_state_chain_default.json"));
        ReportManager reportManager = new ReportManager(tempDir);
        // 无标签的 COMMON 矿石在默认配置下命中 RARITY=16，正是 reviewer 指出的“锭/齿轮停留在 16”场景。
        ItemSnapshot ore = ItemSnapshot.of(ORE, false, 0L, List.of(), Rarity.COMMON);
        PipelineSnapshot snapshot = snapshot(
                List.of(ore, item(INGOT, 0L), item(GEAR, 0L)),
                List.of(oreToIngotRecipe(), gearRecipe()),
                Map.of());
        ScanPipeline pipeline = pipeline(
                store, stateStore, reportManager, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(2, result.solvedByRecipe(), "默认配置下锭与齿轮仍应由配方定价");
        assertEquals(1, result.solvedByFallback(), "矿石应由稀有度兜底定价");
        assertEquals(3, result.applied());
        GeneratedEmcEntry ingot = store.snapshotAll().get(INGOT);
        GeneratedEmcEntry gear = store.snapshotAll().get(GEAR);
        assertNotNull(ingot);
        assertNotNull(gear);
        assertEquals(PriceSource.RECIPE, ingot.source());
        assertEquals(PriceSource.RECIPE, gear.source());
        assertEquals(16L, ingot.value(), "锭必须继承矿石的 RARITY 兜底价，而不是停留在兜底值");
        assertEquals(4L * ingot.value(), gear.value(),
                "配方输出不得被第一轮兜底种子抢占");
        assertTrue(pipeline.lastSolverRoundCount() > 1,
                "兜底种子传播需要多轮收敛，实际轮数为 " + pipeline.lastSolverRoundCount());
        assertTrue(stateStore.lastSolverRounds() > 1, "扫描状态必须记录实际求解轮数");

        // Phase 22：完整报告延迟到扫描完成回调写出。
        pipeline.writePendingReport();
        String report = Files.readString(reportManager.reportFile());
        assertTrue(hasCounterLine(report, "solverRounds", pipeline.lastSolverRoundCount()),
                "last_report.txt 应记录多轮收敛轮数");
    }

    @Test
    void creativeExclusionBlocksRecipePricingAndDownstream() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_recipe_exclusion.json"));
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(true, Set.of(INGOT_ID), Map.of(), Set.of()));
        ItemSnapshot ore = ItemSnapshot.of(ORE, false, 0L, List.of("forge:ores/test"), Rarity.COMMON);
        PipelineSnapshot snapshot = snapshot(
                List.of(ore, item(INGOT, 0L), item(GEAR, 0L)),
                List.of(oreToIngotRecipe(), gearRecipe()),
                Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null, tagOnlyFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByFallback(), "只有矿石应通过标签兜底定价");
        assertEquals(0, result.solvedByRecipe(), "被排除物品的配方值不得发布");
        assertFalse(store.snapshotAll().containsKey(INGOT), "排除项不得获得 RECIPE 值");
        assertFalse(store.snapshotAll().containsKey(GEAR),
                "被排除物品也不得作为下游配方的输入");
        assertNotNull(store.snapshotAll().get(ORE));
    }

    @Test
    void creativeSpecialValueOverridesRecipePricing() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_recipe_special.json"));
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(
                        true, Set.of(), Map.of(INGOT_ID, 777L), Set.of()));
        ItemSnapshot ore = ItemSnapshot.of(ORE, false, 0L, List.of("forge:ores/test"), Rarity.COMMON);
        PipelineSnapshot snapshot = snapshot(
                List.of(ore, item(INGOT, 0L), item(GEAR, 0L)),
                List.of(oreToIngotRecipe(), gearRecipe()),
                Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null, tagOnlyFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        GeneratedEmcEntry ingot = store.snapshotAll().get(INGOT);
        GeneratedEmcEntry gear = store.snapshotAll().get(GEAR);
        assertNotNull(ingot);
        assertNotNull(gear);
        assertEquals(777L, ingot.value(), "固定值必须覆盖配方求解值");
        assertEquals(PriceSource.SPECIAL_VALUE, ingot.source());
        assertEquals(4L * 777L, gear.value(), "固定值必须作为下游配方的输入");
        assertEquals(PriceSource.RECIPE, gear.source());
        assertEquals(2, result.solvedByFallback(), "矿石兜底与固定值都计入非配方来源统计");
        assertEquals(1, result.solvedByRecipe());
        assertEquals(1L, result.sourceBreakdown().get(PriceSource.SPECIAL_VALUE.name()),
                "固定值必须记录为独立来源");
    }

    @Test
    void creativeSpecialValueDoesNotOverrideExternalExistingEmc() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_special_external.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 50L, PriceSource.EXISTING, null, false, 0L));
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(
                        true, Set.of(), Map.of(RESULT_ID, 64L), Set.of()));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 50L)), List.of(), Map.of(RESULT, 50L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.applied(), "外部/手动既有值优先于配置固定值");
        GeneratedEmcEntry existing = store.snapshotAll().get(RESULT);
        assertNotNull(existing);
        assertEquals(50L, existing.value());
        assertFalse(existing.owned());
    }

    @Test
    void zeroValuedExistingEmcIsStillPriced() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_zero_existing.json"));
        ItemSnapshot zero = ItemSnapshot.of(RESULT, true, 0L);
        PipelineSnapshot snapshot = snapshot(
                List.of(zero), List.of(), Map.of(RESULT, 0L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, genericFallback(7L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByFallback(), "0 值既有 EMC 等同于没有值");
        assertEquals(1, result.applied());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(7L, staged.value());
        assertEquals(PriceSource.FALLBACK, staged.source());
    }

    @Test
    void emptyWorldStopsAfterSingleRoundWithZeroResult() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_empty_rounds.json"));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(1, pipeline.lastSolverRoundCount(), "空世界第一轮无新增值后必须立即退出");
        assertEquals(0, result.solvedByRecipe());
        assertEquals(0, result.solvedByFallback());
        assertEquals(0, result.applied());
        assertEquals(0, result.quarantined());
        assertEquals(0, result.overflowResets());
        assertEquals(0, result.unresolved());
        assertEquals(0L, result.sourceTotal());
        assertEquals(0, store.size());
    }

    @Test
    void sameItemKeepsRecipeSourceWhenFallbackAlsoMatches() {
        ScanPipeline.Candidate recipe = new ScanPipeline.Candidate(
                RESULT, 5L, PriceSource.RECIPE, RECIPE_ID);
        ScanPipeline.Candidate fallback = new ScanPipeline.Candidate(
                RESULT, 7L, PriceSource.FALLBACK, null);

        List<ScanPipeline.Candidate> selected = ScanPipeline.selectFinalCandidates(
                Map.of(RESULT, recipe), Map.of(RESULT, fallback), 1L);

        assertEquals(1, selected.size(), "同一物品只允许保留一条候选");
        assertEquals(PriceSource.RECIPE, selected.get(0).source());
        assertEquals(5L, selected.get(0).value());
        assertEquals(RECIPE_ID, selected.get(0).recipeId());
    }

    @Test
    void creativeSpecialValueWinsOverRarityPricing() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_special_value.json"));
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(
                        true, Set.of(), Map.of(RESULT_ID, 64L), Set.of()));
        ItemSnapshot rare = ItemSnapshot.of(RESULT, false, 0L, List.of(), Rarity.RARE);
        PipelineSnapshot snapshot = snapshot(List.of(rare), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByFallback());
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(64L, staged.value(), "特殊值必须优先于 RARE 稀有度的默认值");
        assertEquals(PriceSource.SPECIAL_VALUE, staged.source());
    }

    @Test
    void defaultScopeDoesNotRewriteCustomEmcFile() throws IOException {
        Path customEmc = writeCustomEmc("default_custom_emc.json");
        GeneratedEntryStore store = customEmcStore();
        OverflowResetLog resetLog = new OverflowResetLog(
                tempDir.resolve("overflow_resets_default.json"));
        ScanPipeline pipeline = pipeline(store, null, null, null, null,
                customEmcOverflowPipeline(resetLog), disabledFallback());
        pipeline.setProjectECustomEmcFile(customEmc);

        String before = Files.readString(customEmc);
        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(0, result.overflowResets());
        assertEquals(before, Files.readString(customEmc), "OWNED_ONLY 不得改写 ProjectE 文件");
        assertTrue(resetLog.entries().isEmpty());
    }

    @Test
    void entireScopeWithoutRewriteOptInDoesNotTouchCustomEmc() throws IOException {
        Path customEmc = writeCustomEmc("blocked_custom_emc.json");
        GeneratedEntryStore store = customEmcStore();
        OverflowResetLog resetLog = new OverflowResetLog(
                tempDir.resolve("overflow_resets_blocked.json"));
        ScanPipeline pipeline = pipeline(store, null, null, null, null,
                customEmcOverflowPipeline(resetLog), disabledFallback());
        pipeline.setOverflowScope(OverflowScope.ENTIRE_CUSTOM_EMC);
        pipeline.setProjectECustomEmcFile(customEmc);

        String before = Files.readString(customEmc);
        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(0, result.overflowResets());
        assertEquals(before, Files.readString(customEmc),
                "allowProjectEFileRewrite 未开启时不得改写 ProjectE 文件");
        assertTrue(resetLog.entries().isEmpty());
    }

    @Test
    void entireScopeWithRewriteEnabledRewritesOnlyOwnedEntries() throws IOException {
        Path customEmc = writeCustomEmc("enabled_custom_emc.json");
        GeneratedEntryStore store = customEmcStore();
        Path auditFile = tempDir.resolve("overflow_resets_enabled.json");
        OverflowResetLog resetLog = new OverflowResetLog(auditFile);
        ScanPipeline pipeline = pipeline(store, null, null, null, null,
                customEmcOverflowPipeline(resetLog), disabledFallback());
        pipeline.setOverflowScope(OverflowScope.ENTIRE_CUSTOM_EMC);
        pipeline.setProjectEFileRewriteEnabled(true);
        pipeline.setProjectECustomEmcFile(customEmc);

        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(1, result.overflowResets(), "只有自有超限条目会被改写");
        assertEquals(1, resetLog.entries().size());
        OverflowReset reset = resetLog.entries().get(0);
        assertEquals(OverflowScope.ENTIRE_CUSTOM_EMC, reset.scope());
        assertEquals(1_000L, reset.threshold());
        assertTrue(reset.originalValue() > reset.threshold());
        assertTrue(reset.resetValue() >= 100L && reset.resetValue() <= 900L);

        JsonObject root = JsonParser.parseString(Files.readString(customEmc)).getAsJsonObject();
        assertEquals("keep", root.get("note").getAsString());
        JsonArray entries = root.getAsJsonArray("entries");
        long owned = entries.get(0).getAsJsonObject().get("emc").getAsLong();
        long manual = entries.get(1).getAsJsonObject().get("emc").getAsLong();
        long below = entries.get(2).getAsJsonObject().get("emc").getAsLong();
        assertTrue(owned >= 100L && owned <= 900L, "自有超限条目必须回落");
        assertEquals(4_000L, manual, "管理员手动条目永不改写");
        assertEquals(1_000L, below, "等于阈值的条目不得改写");
        assertEquals(1L, countBackups(customEmc));

        assertTrue(Files.isRegularFile(auditFile), "写盘成功后必须立即落盘审计");
        assertTrue(Files.readString(auditFile).contains("ENTIRE_CUSTOM_EMC"));
    }

    @Test
    void ownedScopeWithRewriteEnabledStillDoesNotTouchCustomEmc() throws IOException {
        Path customEmc = writeCustomEmc("owned_scope_with_rewrite.json");
        GeneratedEntryStore store = customEmcStore();
        OverflowResetLog resetLog = new OverflowResetLog(
                tempDir.resolve("overflow_resets_owned_with_rewrite.json"));
        ScanPipeline pipeline = pipeline(store, null, null, null, null,
                customEmcOverflowPipeline(resetLog), disabledFallback());
        pipeline.setProjectEFileRewriteEnabled(true);
        pipeline.setProjectECustomEmcFile(customEmc);

        String before = Files.readString(customEmc);
        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(0, result.overflowResets());
        assertEquals(before, Files.readString(customEmc),
                "OWNED_ONLY 下即使开启文件改写开关也不得改写 ProjectE 文件");
        assertTrue(resetLog.entries().isEmpty());
        assertEquals(0L, countBackups(customEmc));
    }

    @Test
    void entireScopeWithRewriteEnabledSkipsWhenOnlyNonOwnedEntriesOverflow() throws IOException {
        Path customEmc = tempDir.resolve("only_manual_custom_emc.json");
        Files.writeString(customEmc, """
                { "entries": [ { "item": "sudoersemc:manual_overflow", "emc": 4000 } ] }
                """);
        GeneratedEntryStore store = customEmcStore();
        Path auditFile = tempDir.resolve("overflow_resets_only_manual.json");
        OverflowResetLog resetLog = new OverflowResetLog(auditFile);
        ScanPipeline pipeline = pipeline(store, null, null, null, null,
                customEmcOverflowPipeline(resetLog), disabledFallback());
        pipeline.setOverflowScope(OverflowScope.ENTIRE_CUSTOM_EMC);
        pipeline.setProjectEFileRewriteEnabled(true);
        pipeline.setProjectECustomEmcFile(customEmc);

        String before = Files.readString(customEmc);
        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(0, result.overflowResets());
        assertEquals(before, Files.readString(customEmc),
                "只有非自有超限条目时不得触碰文件");
        assertEquals(0L, countBackups(customEmc));
        assertTrue(resetLog.entries().isEmpty());
        assertFalse(Files.exists(auditFile), "未发生写盘时不应产生新的审计文件");
    }

    @Test
    void dryRunDoesNotRewriteCustomEmcFile() throws IOException {
        Path customEmc = writeCustomEmc("dry_run_custom_emc.json");
        OverflowResetLog resetLog = new OverflowResetLog(
                tempDir.resolve("overflow_resets_dry_run.json"));
        ScanPipeline pipeline = pipeline(null, null, null, null, null,
                customEmcOverflowPipeline(resetLog), disabledFallback());
        pipeline.setOverflowScope(OverflowScope.ENTIRE_CUSTOM_EMC);
        pipeline.setProjectEFileRewriteEnabled(true);
        pipeline.setProjectECustomEmcFile(customEmc);

        String before = Files.readString(customEmc);
        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(0, result.overflowResets());
        assertEquals(before, Files.readString(customEmc), "干跑模式不得写入 ProjectE 文件");
        assertTrue(resetLog.entries().isEmpty());
    }

    @Test
    void solveRecipesDisabledSkipsRecipeSource() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_solve_disabled.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, genericFallback(7L));
        pipeline.setSolveRecipesEnabled(false);

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByRecipe());
        assertEquals(1, result.solvedByFallback());
        assertEquals(1, result.applied());
        assertFalse(result.sourceBreakdown().containsKey(PriceSource.RECIPE.name()));
        GeneratedEmcEntry staged = store.snapshotAll().get(RESULT);
        assertNotNull(staged);
        assertEquals(7L, staged.value());
        assertEquals(PriceSource.FALLBACK, staged.source());
    }

    @Test
    void generateReportsDisabledSkipsReportFile() {
        ReportManager reportManager = new ReportManager(tempDir);
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_report_disabled.json"));
        ScanPipeline pipeline = pipeline(
                store, null, reportManager, null, null, null, genericFallback(7L));
        pipeline.setGenerateReportsEnabled(false);

        PipelineResult result = pipeline.solveAndApply(
                snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of()));
        pipeline.writePendingReport();

        assertEquals(1, result.applied());
        assertFalse(Files.exists(reportManager.reportFile()), "禁用后不得写出 last_report.txt");
    }

    @Test
    void minimumEmcFiltersFallbackCandidatesBelowLowerBound() {
        SolveSettings minEmcTen = new SolveSettings(20, false, 10L, 8);

        GeneratedEntryStore filteredStore = new GeneratedEntryStore(
                tempDir.resolve("generated_min_emc_filtered.json"));
        ScanPipeline filteredPipeline = pipeline(
                filteredStore, null, null, null, null, null, genericFallback(7L), minEmcTen);
        PipelineResult filtered = filteredPipeline.solveAndApply(
                snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of()));

        assertEquals(0, filtered.solvedByFallback());
        assertEquals(0, filtered.applied());
        assertEquals(0, filteredStore.size());

        GeneratedEntryStore acceptedStore = new GeneratedEntryStore(
                tempDir.resolve("generated_min_emc_accepted.json"));
        ScanPipeline acceptedPipeline = pipeline(
                acceptedStore, null, null, null, null, null, genericFallback(10L), minEmcTen);
        PipelineResult accepted = acceptedPipeline.solveAndApply(
                snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of()));

        assertEquals(1, accepted.solvedByFallback());
        assertEquals(1, accepted.applied());
        assertEquals(10L, acceptedStore.snapshotAll().get(RESULT).value());
    }

    @Test
    void nbtEnabledGeneratesVariantEntries() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_nbt_enabled.json"));
        ItemSnapshot stoneBase = ItemSnapshot.of(STONE, false, 0L);
        RecipeSnapshot nbtRecipe = recipeWithNbt(STONE, "{Damage:5}");

        List<ItemSnapshot> variants = ScanPipeline.collectVariantSnapshots(
                List.of(stoneBase), List.of(nbtRecipe), true, 100);

        assertEquals(1, variants.size());
        ItemKey variantKey = variants.get(0).key();
        assertTrue(variantKey.hasVariant());
        assertEquals("{Damage:5}", variantKey.variantHash());
        assertFalse(variants.get(0).hasExistingEmc());
        assertEquals(0L, variants.get(0).existingValue());

        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));
        PipelineResult result = pipeline.solveAndApply(
                snapshot(itemsWithVariants(stoneBase, variants), List.of(), Map.of()));

        assertTrue(result.applied() >= 2, "基础物品与 NBT 变体都应被定价");
        assertNotNull(store.snapshotAll().get(STONE), "基础物品条目仍然必须生成");
        assertTrue(store.snapshotAll().keySet().stream().anyMatch(ItemKey::hasVariant),
                "应写入带 variantHash 的条目");
    }

    @Test
    void nbtDisabledProducesNoVariants() {
        ItemSnapshot stoneBase = ItemSnapshot.of(STONE, false, 0L);
        RecipeSnapshot nbtRecipe = recipeWithNbt(STONE, "{Damage:5}");

        assertTrue(ScanPipeline.collectVariantSnapshots(
                List.of(stoneBase), List.of(nbtRecipe), false, 100).isEmpty(),
                "禁用变体扫描时不得生成任何快照");

        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_nbt_disabled.json"));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));
        PipelineResult result = pipeline.solveAndApply(
                snapshot(List.of(stoneBase), List.of(), Map.of()));

        assertEquals(1, result.applied());
        assertTrue(store.snapshotAll().keySet().stream().noneMatch(ItemKey::hasVariant),
                "禁用变体扫描后不得出现 variantHash 条目");
    }

    @Test
    void maxNbtVariantsPerItemIsRespected() {
        List<RecipeSnapshot> recipes = new ArrayList<>();
        for (int damage = 1; damage <= 5; damage++) {
            recipes.add(recipeWithNbt(STONE, "{Damage:" + damage + "}"));
        }

        List<ItemSnapshot> variants = ScanPipeline.collectVariantSnapshots(
                List.of(ItemSnapshot.of(STONE, false, 0L)), recipes, true, 2);

        assertEquals(2, variants.size(), "观测到 5 个变体时上限 2 必须生效");
        for (ItemSnapshot variant : variants) {
            assertTrue(variant.key().hasVariant());
        }
    }

    @Test
    void excludedItemVariantIsNotPriced() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_variant_excluded.json"));
        ItemKey excludedKey = ItemKey.of(SPAWN_EGG_ID, "{Damage:1}");
        ItemSnapshot excluded = ItemSnapshot.of(
                excludedKey, false, 0L, List.of(), Rarity.RARE);

        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));
        PipelineResult result = pipeline.solveAndApply(
                snapshot(List.of(excluded), List.of(), Map.of()));

        assertEquals(0, result.solvedByFallback(),
                "TierPricing 排除规则必须先于稀有度定价生效");
        assertEquals(0, result.applied());
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void dryRunCountsVariantsWithoutWriting() {
        ItemSnapshot stoneBase = ItemSnapshot.of(STONE, false, 0L);
        List<ItemSnapshot> variants = ScanPipeline.collectVariantSnapshots(
                List.of(stoneBase), List.of(recipeWithNbt(STONE, "{Damage:5}")), true, 100);

        ScanPipeline pipeline = pipeline(
                null, null, null, null, null, null,
                FallbackPricing.withDefaults(PricingSettings.defaults()));
        PipelineResult result = pipeline.solveAndApply(
                snapshot(itemsWithVariants(stoneBase, variants), List.of(), Map.of()));

        assertTrue(result.applied() >= 2, "干跑模式仍需统计基础物品与变体");
        assertFalse(result.appliedToStore());
    }

    @Test
    void emptyNbtRecipeOutputProducesNoVariantEntries() {
        ItemSnapshot stoneBase = ItemSnapshot.of(STONE, false, 0L);
        RecipeSnapshot emptyNbtRecipe = recipeWithNbt(STONE, "{}");

        assertNull(emptyNbtRecipe.outputs().get(0).outputNbt(),
                "空 SNBT 必须在 RecipeOutput 构造时归一为 null");
        assertTrue(ScanPipeline.collectVariantSnapshots(
                        List.of(stoneBase), List.of(emptyNbtRecipe), true, 100).isEmpty(),
                "空 NBT 配方不得产生 variantHash 快照");

        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_empty_nbt_recipe.json"));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, genericFallback(7L));
        PipelineResult result = pipeline.solveAndApply(
                snapshot(List.of(stoneBase), List.of(), Map.of()));

        assertEquals(1, result.applied());
        assertTrue(store.snapshotAll().keySet().stream().noneMatch(ItemKey::hasVariant),
                "空 NBT 配方不得写入带 variantHash 的条目");
    }

    @Test
    void defaultZeroDamageRecipeOutputProducesNoVariantEntries() {
        ItemSnapshot stoneBase = ItemSnapshot.of(STONE, false, 0L);
        RecipeSnapshot zeroDamageRecipe = recipeWithNbt(STONE, "{Damage:0}");

        assertEquals("{Damage:0}", zeroDamageRecipe.outputs().get(0).outputNbt(),
                "非空 SNBT 必须原样保留，归一化交给 VariantNormalizer");
        assertTrue(ScanPipeline.collectVariantSnapshots(
                        List.of(stoneBase), List.of(zeroDamageRecipe), true, 100).isEmpty(),
                "仅含默认 Damage:0 的标签不得产生伪变体");
    }

    @Test
    void creativeExclusionPreventsRecipeCandidate() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_recipe_excluded.json"));
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(true, Set.of(RESULT_ID), Map.of(), Set.of()));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByRecipe(), "被排除物品不得产生 RECIPE 候选");
        assertEquals(0, result.applied());
        assertNull(store.snapshotAll().get(RESULT), "被排除物品不得写入 store");
    }

    @Test
    void tierDefaultExclusionPreventsRecipeCandidate() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_bedrock_recipe.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(BEDROCK, 0L)),
                List.of(recipe(INGOT, BEDROCK)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.solvedByRecipe(), "TierPricing 默认排除项不得产生配方候选");
        assertEquals(0, result.applied());
        assertNull(store.snapshotAll().get(BEDROCK), "默认排除项不得写入 store");
    }

    @Test
    void staleOwnedEntryIsRetiredWhenOtherItemsArePriced() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_stale_owned.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(GEAR, 0L), item(RESULT, 0L)),
                List.of(recipe(INGOT, GEAR)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.applied(), "本轮仍需为其它物品生成候选");
        assertNull(store.snapshotAll().get(RESULT), "不再参与定价的自有条目必须被清理");
        assertNotNull(store.snapshotAll().get(GEAR), "本轮参与定价的物品必须保留");
    }

    @Test
    void staleOwnedEntryIsKeptWhenNoCandidateIsProduced() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_stale_no_candidate.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.applied());
        assertNotNull(store.snapshotAll().get(RESULT),
                "本轮没有任何候选时不得把自有条目当作陈旧条目清理");
    }

    @Test
    void staleOwnedEntryIsKeptWhenSolveRecipesDisabled() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_stale_solve_disabled.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, GEAR)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());
        pipeline.setSolveRecipesEnabled(false);

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.applied());
        assertNotNull(store.snapshotAll().get(RESULT),
                "配方求解禁用时本轮没有参与定价不代表自有条目陈旧");
    }

    @Test
    void staleOwnedEntryIsKeptWhenSnapshotHasNoItems() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_stale_empty_snapshot.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(), List.of(recipe(INGOT, GEAR)), Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.applied(), "配方求解仍可为其它物品生成候选");
        assertNotNull(store.snapshotAll().get(RESULT),
                "空物品快照不得触发自有条目清理");
    }

    @Test
    void ownedEntryListedInExistingValuesIsKept() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_owned_existing.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(GEAR, 0L), item(RESULT, 0L)),
                List.of(recipe(INGOT, GEAR)),
                Map.of(INGOT, 5L, RESULT, 64L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.applied());
        GeneratedEmcEntry kept = store.snapshotAll().get(RESULT);
        assertNotNull(kept, "既在 existingValues 中的自有条目不得被清理");
        assertEquals(128L, kept.value());
    }

    @Test
    void specialValueOverridesTierExclusionInFallbackPricing() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_special_value.json"));
        ItemKey spawnEgg = ItemKey.of(SPAWN_EGG_ID);
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(
                        true, Set.of(), Map.of(SPAWN_EGG_ID, 42L), Set.of()));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(
                snapshot(List.of(item(spawnEgg, 0L)), List.of(), Map.of()));

        assertEquals(1, result.solvedByFallback(),
                "safety.creativeItemSpecialValues 必须优先于层级排除");
        assertEquals(1, result.applied());
        GeneratedEmcEntry written = store.snapshotAll().get(spawnEgg);
        assertNotNull(written);
        assertEquals(42L, written.value());
    }

    @Test
    void explicitExclusionStillOverridesSpecialValue() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_explicit_over_special.json"));
        CreativeItemProtection protection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(
                        true, Set.of(RESULT_ID), Map.of(RESULT_ID, 42L), Set.of()));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, protection, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(
                snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of()));

        assertEquals(0, result.solvedByFallback());
        assertEquals(0, result.applied());
        assertTrue(store.snapshotAll().isEmpty(),
                "显式排除必须仍然优先于特殊固定值");
    }

    @Test
    void manualEntryIsNotRetiredWhenOtherItemsArePriced() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_manual_kept.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.EXISTING, null, false, 0L));

        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(GEAR, 0L), item(RESULT, 0L)),
                List.of(recipe(INGOT, GEAR)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.applied());
        GeneratedEmcEntry kept = store.snapshotAll().get(RESULT);
        assertNotNull(kept, "非自有条目绝不能被清理");
        assertEquals(128L, kept.value());
        assertFalse(kept.owned());
    }

    @Test
    void diagnosticReportIncludesRecipeStatsAndExtraCounters() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir);
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_phase22_report.json"));
        store.stage(new GeneratedEmcEntry(
                RESULT, 1_000L, PriceSource.RECIPE, null, true, 0L));
        PipelineSnapshot snapshot = new PipelineSnapshot(
                List.of(item(RESULT, 0L)), List.of(), Map.of(), 2, 1, 3,
                List.of("bad_recipe: boom"));
        ScanPipeline pipeline = pipeline(
                store, null, reportManager, null, null, null, genericFallback(7L));
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        diagnostics.recordTick(2_000_000L);
        diagnostics.recordTick(6_000_000L);
        diagnostics.recordMemory();
        pipeline.setDiagnostics(diagnostics);

        PipelineResult result = pipeline.solveAndApply(snapshot);
        pipeline.writePendingReport();

        assertEquals(1, result.quarantined());
        assertEquals(6L, result.extraCounter("totalRecipes", -1L),
                "总配方数应等于可用 + 不支持 + 无效 + 失败");
        assertEquals(1L, result.extraCounter("quarantinedDrops", -1L));
        assertEquals(1L, result.extraCounter("failedRecipeDetailsRecorded", -1L));
        assertEquals(2L, result.extraCounter("scanTicks", -1L));

        String report = Files.readString(reportManager.reportFile());
        assertTrue(hasCounterLine(report, "配方扫描总数", 6L));
        assertTrue(hasCounterLine(report, "可用配方", 0L));
        assertTrue(hasCounterLine(report, "不支持配方", 2L));
        assertTrue(hasCounterLine(report, "无效配方", 1L));
        assertTrue(hasCounterLine(report, "失败配方", 3L));
        assertTrue(hasCounterLine(report, "扫描失败配方", 3L),
                "配方扫描区块必须使用不重复的标签");
        assertTrue(report.contains("bad_recipe: boom"), "失败配方明细必须写入报告");
        assertTrue(report.lines().anyMatch(line -> line.contains("quarantinedDrops: 1")),
                "PipelineResult.extraCounters 中的计数器必须全部输出");
        assertTrue(report.lines().anyMatch(line -> line.contains("retiredEntries: 0")));
        assertTrue(report.contains("tick 采样数"));
        assertTrue(report.contains("6.00 ms"), "最大 tick 耗时必须按 ms 渲染");

        pipeline.writeCancelledReport(diagnostics);
        String afterCancel = Files.readString(reportManager.reportFile());
        assertFalse(afterCancel.contains("部分报告"),
                "完整报告已写出时不得被后续部分报告覆盖");
        assertTrue(afterCancel.contains("bad_recipe: boom"));
    }

    @Test
    void cancelledAndFailedReportsAreWritten() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir.resolve("partial_reports"));
        ScanPipeline pipeline = pipeline(
                null, null, reportManager, null, null, null, disabledFallback());
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        diagnostics.recordTick(4_000_000L);
        diagnostics.recordMemory();

        pipeline.writeCancelledReport(diagnostics);
        String cancelled = Files.readString(reportManager.reportFile());
        assertTrue(cancelled.contains("部分报告（扫描已取消）"));
        assertTrue(cancelled.lines().anyMatch(line -> line.contains("scanCancelled: 1")));
        assertTrue(cancelled.lines().anyMatch(line -> line.contains("partialReport: 1")));
        assertTrue(cancelled.contains("tick 采样数"));

        pipeline.writeFailedReport("boom", diagnostics);
        String failed = Files.readString(reportManager.reportFile());
        assertTrue(failed.contains("部分报告（扫描失败）"));
        assertTrue(failed.lines().anyMatch(line -> line.contains("scanFailed: 1")));
        assertTrue(failed.lines().anyMatch(line -> line.contains("scanAborts: 1")));
    }

    @Test
    void completionReportIncludesApplyingAndSavingTicks() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir.resolve("completion_report"));
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_completion_report.json"));
        ScanPipeline pipeline = pipeline(
                store, null, reportManager, null, null, null, genericFallback(7L));
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        pipeline.setDiagnostics(diagnostics);
        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of());

        ScanManager manager = new ScanManager(
                new BatchProcessor(10, false),
                () -> {
                    pipeline.stash(snapshot);
                    return ScanManager.oneShot(
                            new ScanManager.SnapshotData("测试快照", snapshot.itemCount(), 0)).begin();
                },
                new ScanManager.ResultApplier() {
                    @Override
                    public void apply(ScanManager.SnapshotData data) {
                        pipeline.applyStashed();
                    }

                    @Override
                    public void onCompleted() {
                        pipeline.writePendingReport();
                    }
                },
                1_000_000L,
                diagnostics,
                null);

        assertTrue(manager.requestScan());
        int ticks = 0;
        while (manager.isRunning() && ticks < 20) {
            manager.tick();
            ticks++;
        }

        assertEquals(ScanPhase.COMPLETED, manager.progress().phase());
        String report = Files.readString(reportManager.reportFile());
        assertTrue(hasCounterLine(report, "tick 采样数", ticks),
                "完整报告必须包含 APPLYING/SAVING tick，期望 " + ticks + " 个样本，实际报告:\n" + report);
    }

    @Test
    void collectFailureProducesFailedReportInsteadOfSilentEmptyScan() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir.resolve("collect_failure_report"));
        ScanPipeline pipeline = pipeline(
                null, null, reportManager, null, null, null, disabledFallback());

        // phase-17 契约：采集失败必须抛出，由 ScanManager 转入 FAILED；
        // phase-22 契约：失败原因必须可见，并能写出 scanFailed=1 的部分报告。
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> pipeline.collect(null));
        assertTrue(failure.getMessage().contains("扫描采集失败"));
        assertNotNull(pipeline.lastCollectFailure(), "采集失败必须保留原因");
        pipeline.writeFailedReport(failure.getMessage(), new ScanDiagnostics());

        String report = Files.readString(reportManager.reportFile());
        assertTrue(report.contains("部分报告（扫描失败）"),
                "采集失败不得伪装成正常空扫描");
        assertTrue(report.lines().anyMatch(line -> line.contains("scanFailed: 1")));
    }

    @Test
    void applyStashedWithoutSnapshotWritesFailedReport() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir.resolve("missing_snapshot_report"));
        ScanPipeline pipeline = pipeline(
                null, null, reportManager, null, null, null, disabledFallback());

        PipelineResult result = pipeline.applyStashed();

        assertEquals(0, result.applied());
        String report = Files.readString(reportManager.reportFile());
        assertTrue(report.contains("部分报告（扫描失败）"));
        assertTrue(report.lines().anyMatch(line -> line.contains("scanFailed: 1")));
    }

    @Test
    void recipeEnumerationFailureShowsUnknownTotal() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir.resolve("enumeration_failure_report"));
        ScanPipeline pipeline = pipeline(
                null, null, reportManager, null, null, null, disabledFallback());
        PipelineSnapshot snapshot = new PipelineSnapshot(
                List.of(item(RESULT, 0L)), List.of(), Map.of(), 0, 0, 0, true,
                List.of("recipe-manager: boom"));

        pipeline.solveAndApply(snapshot);
        pipeline.writePendingReport();

        String report = Files.readString(reportManager.reportFile());
        assertTrue(report.contains("不可用（配方枚举失败）"),
                "枚举失败时报告必须明确标注统计不可用");
        assertFalse(report.lines().anyMatch(line -> line.startsWith("配方扫描总数")),
                "枚举失败时不得输出伪造的配方总数");
        assertTrue(report.lines().anyMatch(line -> line.contains("recipeEnumerationFailed: 1")));
        assertTrue(report.contains("recipe-manager: boom"));
    }

    @Test
    void collectSnapshotDataReadsPipelineVariantConfig() {
        ScanPipeline pipeline = pipeline(null, null, null, null, null, null, disabledFallback());
        ItemSnapshot stoneBase = ItemSnapshot.of(STONE, false, 0L);
        List<RecipeSnapshot> recipes = new ArrayList<>();
        for (int damage = 1; damage <= 5; damage++) {
            recipes.add(recipeWithNbt(STONE, "{Damage:" + damage + "}"));
        }
        RecipeScanner.ScanResult scanResult = new RecipeScanner.ScanResult(recipes, 0, 0, 0);

        pipeline.setNbtVariantsEnabled(false);
        PipelineSnapshot disabled = pipeline.collectSnapshotData(List.of(stoneBase), scanResult, null);
        assertTrue(disabled.items().stream().noneMatch(item -> item.key().hasVariant()),
                "nbt.scanNbtVariants=false 必须经由管线字段阻止变体生成");

        pipeline.setNbtVariantsEnabled(true);
        pipeline.setMaxNbtVariantsPerItem(2);
        PipelineSnapshot limited = pipeline.collectSnapshotData(List.of(stoneBase), scanResult, null);
        assertEquals(3, limited.items().size(), "基础物品 + 两个变体");
        assertEquals(2, limited.items().stream().filter(item -> item.key().hasVariant()).count(),
                "nbt.maxNbtVariantsPerItem 必须经由管线字段生效");
    }

    @Test
    void collectSnapshotDataQueriesExactNbtEmcForVariants() {
        ItemSnapshot stoneBase = ItemSnapshot.of(STONE, false, 0L);
        RecipeSnapshot recipe = recipeWithNbt(STONE, "{Damage:3}");
        ItemScanner.EmcQuery query = new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(ItemStack stack) {
                return false;
            }

            @Override
            public long getValue(ItemStack stack) {
                return 0L;
            }

            @Override
            public long getExactValue(ItemStack stack) {
                return stack.getTag() != null && stack.getTag().getInt("Damage") == 3 ? 50L : 0L;
            }
        };
        ScanPipeline pipeline = pipeline(null, null, null, null, null, null, disabledFallback());

        PipelineSnapshot snapshot = pipeline.collectSnapshotData(
                List.of(stoneBase), new RecipeScanner.ScanResult(List.of(recipe), 0, 0, 0), query);

        ItemKey variantKey = ItemKey.of(STONE_ID, "{Damage:3}");
        ItemSnapshot variant = snapshot.items().stream()
                .filter(item -> item.key().equals(variantKey))
                .findFirst()
                .orElseThrow();
        assertTrue(variant.hasExistingEmc(), "该 NBT 自身已有精确 EMC 时必须标记既有值");
        assertEquals(50L, variant.existingValue());
        assertEquals(50L, snapshot.existingValues().get(variantKey));
    }

    @Test
    void variantRecipeValueUsesVariantIdentityAndPersistsRawNbt() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_variant_recipe.json"));
        ItemKey variant = ItemKey.of(STONE_ID, "{Marker:7}");
        String raw = "{Marker:7,display:{Name:\"only display\"}}";
        RecipeSnapshot recipe = recipeWithNbt(variant, raw);
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), ItemSnapshot.of(variant, false, 0L)),
                List.of(recipe),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1, result.solvedByRecipe(), "求解器必须直接看到带 variantHash 的输出键");
        assertEquals(1, result.applied());
        GeneratedEmcEntry entry = store.snapshotAll().get(variant);
        assertNotNull(entry, "变体必须按其规范化身份持久化");
        assertEquals(5L, entry.value());
        assertEquals(List.of(raw), store.snapshotOwnedVariantNbts().get(variant),
                "原始 SNBT 必须随条目保存，供 ProjectE 精确匹配");

        store.save();
        GeneratedEntryStore reloaded = new GeneratedEntryStore(store.file());
        reloaded.load();
        assertEquals(List.of(raw), reloaded.snapshotOwnedVariantNbts().get(variant),
                "重启后仍必须保留原始 NBT 注册信息");
    }

    @Test
    void disabledVariantScanDoesNotPriceVariantRecipeOutputsEndToEnd() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_variant_disabled_e2e.json"));
        ItemKey variant = ItemKey.of(STONE_ID, "{Damage:5}");
        RecipeSnapshot recipe = recipeWithNbt(variant, "{Damage:5}");
        ScanPipeline pipeline = pipeline(store, null, null, null, null, null, disabledFallback());
        pipeline.setNbtVariantsEnabled(false);

        PipelineSnapshot collected = pipeline.collectSnapshotData(
                List.of(item(INGOT, 5L), ItemSnapshot.of(STONE, false, 0L)),
                new RecipeScanner.ScanResult(List.of(recipe), 0, 0, 0),
                null);
        PipelineResult result = pipeline.solveAndApply(collected);

        assertEquals(0, result.solvedByRecipe(), "关闭变体扫描后，带 NBT 的配方输出不得绕过采集门禁");
        assertTrue(store.snapshotAll().keySet().stream().noneMatch(ItemKey::hasVariant),
                "关闭变体扫描后不得写入任何 variantHash 条目");
    }

    /**
     * 构造带 NBT 输出的单输入单输出配方。
     */
    private static RecipeSnapshot recipeWithNbt(ItemKey output, String outputNbt) {
        return RecipeSnapshot.of(
                NBT_RECIPE_ID,
                RecipeKind.SHAPED,
                List.of(RecipeInput.of(IngredientOption.of(INGOT))),
                List.of(RecipeOutput.of(output, 1, outputNbt)));
    }

    /**
     * 把变体快照追加到基础物品之后，保持与生产管线一致的顺序。
     */
    private static List<ItemSnapshot> itemsWithVariants(ItemSnapshot base,
                                                        List<ItemSnapshot> variants) {
        List<ItemSnapshot> items = new ArrayList<>(1 + variants.size());
        items.add(base);
        items.addAll(variants);
        return items;
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
        return pipeline(store, stateStore, reportManager, dropProtection, creativeProtection,
                overflowPipeline, fallbackPricing, null);
    }

    /**
     * 创建使用指定求解参数的管线，其余未提供的协作者使用安全默认值。
     */
    private static ScanPipeline pipeline(GeneratedEntryStore store,
                                         ScanStateStore stateStore,
                                         ReportManager reportManager,
                                         LargeDropProtection dropProtection,
                                         CreativeItemProtection creativeProtection,
                                         OverflowPipeline overflowPipeline,
                                         FallbackPricing fallbackPricing,
                                         SolveSettings solveSettings) {
        return new ScanPipeline(
                store,
                stateStore,
                reportManager,
                dropProtection,
                creativeProtection,
                overflowPipeline,
                fallbackPricing,
                solveSettings);
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

    /**
     * 构造启用 ENTIRE_CUSTOM_EMC 语义的溢出管线，阈值与回落区间与测试数据对应。
     */
    private static OverflowPipeline customEmcOverflowPipeline(OverflowResetLog resetLog) {
        return new OverflowPipeline(
                new OverflowProtection(new OverflowProtection.Settings(
                        true,
                        1_000L,
                        100L,
                        900L,
                        10,
                        5,
                        OverflowScope.ENTIRE_CUSTOM_EMC,
                        true,
                        42L)),
                resetLog);
    }

    /**
     * 构造一个自有条目与一个非自有条目的存储，用于验证 custom_emc.json 的归属判定。
     */
    private GeneratedEntryStore customEmcStore() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_custom_emc.json"));
        store.stage(new GeneratedEmcEntry(
                OWNED_OVERFLOW, 5L, PriceSource.RECIPE, null, true, 0L));
        store.stage(new GeneratedEmcEntry(
                MANUAL_OVERFLOW, 6L, PriceSource.EXISTING, null, false, 0L));
        return store;
    }

    /**
     * 写出一份包含自有、非自有与等于阈值条目的 ProjectE 风格 custom_emc.json。
     */
    private Path writeCustomEmc(String name) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, """
                {
                  "entries": [
                    { "item": "sudoersemc:owned_overflow", "emc": 5000 },
                    { "item": "sudoersemc:manual_overflow", "emc": 4000 },
                    { "item": "sudoersemc:below_threshold", "emc": 1000 }
                  ],
                  "note": "keep"
                }
                """);
        return file;
    }

    /**
     * 统计目标文件对应的时间戳备份数量。
     */
    private static long countBackups(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        String prefix = file.getFileName() + ".";
        try (Stream<Path> files = Files.list(parent)) {
            return files.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".bak");
            }).count();
        }
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
     * 构造链式夹具中的“矿石 → 锭”配方。
     */
    private static RecipeSnapshot oreToIngotRecipe() {
        return RecipeSnapshot.of(
                ORE_TO_INGOT_RECIPE_ID,
                RecipeKind.SHAPED,
                List.of(RecipeInput.of(IngredientOption.of(ORE))),
                List.of(RecipeOutput.of(INGOT)));
    }

    /**
     * 构造链式夹具中的“4 锭 → 1 齿轮”配方。
     */
    private static RecipeSnapshot gearRecipe() {
        return RecipeSnapshot.of(
                GEAR_RECIPE_ID,
                RecipeKind.SHAPED,
                List.of(new RecipeInput(IngredientOption.of(INGOT), 4)),
                List.of(RecipeOutput.of(GEAR)));
    }

    /**
     * 只启用标签定价的兜底链：稀有度与通用兜底关闭，确保中间产物必须由配方定价。
     */
    private static FallbackPricing tagOnlyFallback() {
        return FallbackPricing.withDefaults(
                new PricingSettings(true, false, false, false, 0L));
    }

    /**
     * 判断报告文本中是否存在给定标签且计数匹配的行。
     */
    private static boolean hasCounterLine(String report, String label, long value) {
        String suffix = ": " + value;
        return report.lines().anyMatch(line -> line.startsWith(label) && line.endsWith(suffix));
    }
}
