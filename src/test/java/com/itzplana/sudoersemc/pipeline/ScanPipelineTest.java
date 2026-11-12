package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itzplana.sudoersemc.command.CommandResult;
import com.itzplana.sudoersemc.command.CommandService;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.ProjectEManualValues;
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
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.ScanDiagnostics;
import com.itzplana.sudoersemc.task.ScanFingerprint;
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

    private static final ItemKey INGOT = ItemKey.of(INGOT_ID);
    private static final ItemKey RESULT = ItemKey.of(RESULT_ID);
    private static final ItemKey DUST = ItemKey.of(DUST_ID);
    private static final ItemKey STONE = ItemKey.of(STONE_ID);
    private static final ItemKey BEDROCK = ItemKey.of(BEDROCK_ID);
    private static final ItemKey ORE = ItemKey.of(ORE_ID);
    private static final ItemKey GEAR = ItemKey.of(GEAR_ID);

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
    void approvedDropBecomesBaselineOnNextScan() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_approve_rescan.json"));
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

        PipelineResult first = pipeline.solveAndApply(snapshot);
        assertEquals(1, first.quarantined());
        assertEquals(1_000L, store.get(RESULT).value(), "隔离后旧值必须保留");

        CommandService service = new CommandService(
                null, null, store, null, null, quarantineStore,
                null,
                new ProjectEManualValues(tempDir.resolve("ProjectE").resolve("custom_emc.json")),
                true, () -> key -> false, () -> 1L, () -> 999_999L);
        CommandResult approved = service.approve(RESULT_ID.toString());

        assertTrue(approved.success(), approved.joinLines());
        GeneratedEmcEntry approvedEntry = store.get(RESULT);
        assertNotNull(approvedEntry);
        assertEquals(100L, approvedEntry.value());
        assertEquals(PriceSource.APPROVED, approvedEntry.source());
        assertTrue(quarantineStore.entries().get(0).applied());

        PipelineResult second = pipeline.solveAndApply(snapshot);
        assertEquals(0, second.quarantined(), "同值候选不应被再次隔离");
        assertEquals(1, second.applied());
        assertEquals(1L, second.extraCounter(PriceSource.APPROVED.name(), 0L),
                "重扫必须统计到上一轮落盘的 APPROVED 条目");
        assertEquals(100L, store.get(RESULT).value());
        assertEquals(PriceSource.RECIPE, store.get(RESULT).source(),
                "重扫会按正常定价重新求解并覆盖 APPROVED 来源（已知边界）");
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
        OverflowResetLog resetLog = new OverflowResetLog(tempDir.resolve("overflow_resets.json"));
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

        ScanFingerprint expected = ScanPipeline.fingerprintFor(
                snapshot.items(), snapshot.recipes(), List.of(), List.of());
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
    void approvedEntriesAreReportedInDiagnostics() throws IOException {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_approved.json"));
        assertTrue(store.stage(new GeneratedEmcEntry(
                RESULT, 10L, PriceSource.APPROVED, null, true, 0L)));
        ReportManager reports = new ReportManager(tempDir);
        // 让 RESULT 作为已有 EMC 保留，避免被陈旧条目清理删除。
        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 10L)), List.of(), Map.of(RESULT, 10L));
        ScanPipeline pipeline = pipeline(
                store, null, reports, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1L, result.extraCounters().get(PriceSource.APPROVED.name()),
                "诊断计数器必须记录扫描开始时仍生效的 APPROVED 条目");
        assertEquals(0L, result.sourceTotal(),
                "APPROVED 是上一轮遗留来源，不属于本轮求解来源计数");
        String report = Files.readString(reports.reportFile());
        assertTrue(report.lines().anyMatch(
                        line -> line.startsWith("已批准应用") && line.endsWith(": 1")),
                "报告必须输出已批准应用计数:\n" + report);
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
    void externallyModifiedOwnedEntryIsReleasedAndPreserved() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_external_drift.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 5L, PriceSource.RECIPE, null, true, 0L));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 7L)), List.of(), Map.of(RESULT, 7L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, genericFallback(3L));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1L, result.extraCounter("ownershipExternalModifications", -1L));
        assertEquals(1L, result.extraCounter("ownershipReleasedEntries", -1L));
        assertEquals(0, result.applied(), "外部值必须阻止兜底重新定价");
        GeneratedEmcEntry entry = store.snapshotAll().get(RESULT);
        assertNotNull(entry);
        assertFalse(entry.owned());
        assertEquals(5L, entry.value(), "释放所有权不得修改持久化数值");
    }

    @Test
    void manualValueIsProtectedEvenWithoutObservedBackendValue() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_manual_protected.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 5L, PriceSource.RECIPE, null, true, 0L));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 0L)), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, genericFallback(3L));
        pipeline.setManualValueDetector(key -> key.equals(RESULT));

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1L, result.extraCounter("ownershipExternalModifications", -1L));
        assertEquals(0, result.applied(), "ProjectE 手工值必须阻止自动定价");
        GeneratedEmcEntry entry = store.snapshotAll().get(RESULT);
        assertNotNull(entry);
        assertFalse(entry.owned());
        assertEquals(5L, entry.value(), "没有后端观测值时保留最后已知值");
    }

    @Test
    void disablingExternalModificationPreservationRestoresRefreshSemantics() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_refresh_owned.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 5L, PriceSource.RECIPE, null, true, 0L));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(RESULT, 7L)), List.of(), Map.of(RESULT, 7L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, genericFallback(3L));
        pipeline.setPreserveExternallyModifiedValues(false);

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0L, result.extraCounter("ownershipExternalModifications", -1L));
        assertEquals(1, result.applied());
        GeneratedEmcEntry entry = store.snapshotAll().get(RESULT);
        assertNotNull(entry);
        assertTrue(entry.owned(), "关闭保护后应恢复 Phase 14 的自有刷新语义");
        assertEquals(3L, entry.value());
    }

    @Test
    void recipeClassificationCountersAreReported() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_recipe_kinds.json"));
        RecipeSnapshot shaped = recipe(INGOT, RESULT);
        RecipeSnapshot shapeless = new RecipeSnapshot(
                new ResourceLocation("sudoersemc", "shapeless_with_byproduct"),
                RecipeKind.SHAPELESS,
                List.of(RecipeInput.of(IngredientOption.of(INGOT))),
                List.of(RecipeOutput.of(RESULT), new RecipeOutput(DUST, 2)),
                List.of(RecipeOutput.of(INGOT)), false, Map.of());
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 0L)), List.of(shaped, shapeless), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(1L, result.extraCounter("shapedRecipes", -1L));
        assertEquals(1L, result.extraCounter("shapelessRecipes", -1L));
        assertEquals(1L, result.extraCounter("multiOutputRecipes", -1L));
        assertEquals(1L, result.extraCounter("containerRemainderRecipes", -1L));
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
        assertEquals(PriceSource.TIER, staged.source());
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
    void entireScopeWithRewriteEnabledRewritesAboveThresholdEntries() throws IOException {
        Path customEmc = writeCustomEmc("enabled_custom_emc.json");
        GeneratedEntryStore store = customEmcStore();
        OverflowResetLog resetLog = new OverflowResetLog(
                tempDir.resolve("overflow_resets_enabled.json"));
        ScanPipeline pipeline = pipeline(store, null, null, null, null,
                customEmcOverflowPipeline(resetLog), disabledFallback());
        pipeline.setOverflowScope(OverflowScope.ENTIRE_CUSTOM_EMC);
        pipeline.setProjectEFileRewriteEnabled(true);
        pipeline.setProjectECustomEmcFile(customEmc);

        PipelineResult result = pipeline.solveAndApply(PipelineSnapshot.empty());

        assertEquals(2, result.overflowResets());
        assertEquals(2, resetLog.entries().size());
        for (OverflowReset reset : resetLog.entries()) {
            assertEquals(OverflowScope.ENTIRE_CUSTOM_EMC, reset.scope());
            assertEquals(1_000L, reset.threshold());
            assertTrue(reset.originalValue() > reset.threshold());
            assertTrue(reset.resetValue() >= 100L && reset.resetValue() <= 900L);
        }

        JsonObject root = JsonParser.parseString(Files.readString(customEmc)).getAsJsonObject();
        assertEquals("keep", root.get("note").getAsString());
        JsonArray entries = root.getAsJsonArray("entries");
        long owned = entries.get(0).getAsJsonObject().get("emc").getAsLong();
        long manual = entries.get(1).getAsJsonObject().get("emc").getAsLong();
        long below = entries.get(2).getAsJsonObject().get("emc").getAsLong();
        assertTrue(owned >= 100L && owned <= 900L, "自有超限条目必须回落");
        assertTrue(manual >= 100L && manual <= 900L,
                "ENTIRE_CUSTOM_EMC 是显式 opt-in，非自有超限条目也应改写");
        assertEquals(1_000L, below, "等于阈值的条目不得改写");
        assertEquals(1L, countBackups(customEmc));
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
    void staleOwnedEntryIsRetiredWhenNoCandidateIsProduced() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_stale_owned.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.RECIPE, null, true, 0L));

        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.applied());
        assertNull(store.snapshotAll().get(RESULT), "不再参与定价的自有条目必须被清理");
        assertEquals(0, store.size());
    }

    @Test
    void manualEntryIsNotRetiredWhenNoCandidateIsProduced() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_manual_kept.json"));
        store.stage(new GeneratedEmcEntry(RESULT, 128L, PriceSource.EXISTING, null, false, 0L));

        PipelineSnapshot snapshot = snapshot(List.of(item(RESULT, 0L)), List.of(), Map.of());
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.applied());
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
    void fingerprintIncludesAllFiveChannels() {
        ItemSnapshot item = ItemSnapshot.of(INGOT, false, 0L,
                List.of("forge:ingots"), Rarity.COMMON);
        RecipeSnapshot recipe = recipe(INGOT, RESULT);

        ScanFingerprint base = ScanPipeline.fingerprintFor(
                List.of(item), List.of(recipe), List.of("cfg=1"), List.of("base=1"));
        ScanFingerprint configChanged = ScanPipeline.fingerprintFor(
                List.of(item), List.of(recipe), List.of("cfg=2"), List.of("base=1"));
        ScanFingerprint baselineChanged = ScanPipeline.fingerprintFor(
                List.of(item), List.of(recipe), List.of("cfg=1"), List.of("base=2"));
        ScanFingerprint tagChanged = ScanPipeline.fingerprintFor(
                List.of(ItemSnapshot.of(INGOT, false, 0L, List.of("forge:blocks"), Rarity.COMMON)),
                List.of(recipe), List.of("cfg=1"), List.of("base=1"));

        assertFalse(base.equalsFingerprint(configChanged));
        assertFalse(base.equalsFingerprint(baselineChanged));
        assertFalse(base.equalsFingerprint(tagChanged));
        assertEquals(List.of("config"), base.changedChannels(configChanged));
        assertEquals(List.of("baseline"), base.changedChannels(baselineChanged));
        assertEquals(List.of("tag"), base.changedChannels(tagChanged));
    }

    @Test
    void cancelledSolveDoesNotUpdateStateAndWritesPartialReport() throws IOException {
        ReportManager reportManager = new ReportManager(tempDir.resolve("cancel_reports"));
        ScanStateStore stateStore = new ScanStateStore(tempDir.resolve("cancel_state.json"));
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("cancel_generated.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, stateStore, reportManager, null, null, null, disabledFallback());
        pipeline.setCancellationCheck(() -> true);

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertEquals(0, result.applied());
        assertEquals(0, store.size(), "取消时不得写入任何条目");
        assertFalse(stateStore.isDirty(), "取消时不得把未完成结果写成扫描指纹");
        String report = Files.readString(reportManager.reportFile());
        assertTrue(report.contains("部分报告（扫描已取消）"));
        assertTrue(report.lines().anyMatch(line -> line.contains("scanCancelled: 1")));
    }

    @Test
    void itemScannerHonorsCancellationCheckpoint() {
        ItemScanner scanner = new ItemScanner(new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(net.minecraft.world.item.ItemStack stack) {
                return false;
            }

            @Override
            public long getValue(net.minecraft.world.item.ItemStack stack) {
                return 0L;
            }
        }, Set.of(), Set.of());

        assertTrue(scanner.scan(() -> true).isEmpty(),
                "取消信号在首个检查点生效，不应继续遍历物品注册表");
    }

    @Test
    void collectFingerprintReturnsNullWithoutServer() {
        ScanPipeline pipeline = pipeline(null, null, null, null, null, null, disabledFallback());

        assertNull(pipeline.collectFingerprint(null));
    }

    @Test
    void cancelledVariantCollectionStopsAtCheckpoint() {
        ItemSnapshot base = ItemSnapshot.of(STONE, false, 0L,
                List.of("minecraft:stone"), Rarity.COMMON);
        List<ItemSnapshot> variants = ScanPipeline.collectVariantSnapshots(
                List.of(base), List.of(recipeWithNbt(STONE, "{Display:{Name:'x'}}")),
                true, 100, () -> true);

        assertTrue(variants.isEmpty(), "取消后不应继续生成 NBT 变体");
    }

    @Test
    void solverCacheCountersAreExposedInResult() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("cache_generated.json"));
        PipelineSnapshot snapshot = snapshot(
                List.of(item(INGOT, 5L), item(RESULT, 0L)),
                List.of(recipe(INGOT, RESULT)),
                Map.of(INGOT, 5L));
        ScanPipeline pipeline = pipeline(
                store, null, null, null, null, null, disabledFallback());

        PipelineResult result = pipeline.solveAndApply(snapshot);

        assertTrue(result.extraCounters().containsKey("solverCacheHits"));
        assertTrue(result.extraCounters().containsKey("solverCacheMisses"));
        assertTrue(result.extraCounters().get("solverCacheMisses") > 0L,
                "首次扫描至少应有一次真实评估");
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
        return RecipeSnapshot.of(
                RECIPE_ID,
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
