package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.pipeline.PipelineResult;
import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.SharedConstants;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 入口层生命周期开关的单元测试。
 *
 * <p>这些用例只覆盖不依赖 Forge 运行时的纯决策与清理逻辑，确保
 * {@code [general] enabled} / {@code alwaysRescan} 不会再次变成零消费配置。</p>
 */
class SudoersemcLifecycleTest {

    @TempDir
    Path tempDir;

    private static ScanManager activeManager() {
        ScanManager manager = new ScanManager(
                new BatchProcessor(1, false),
                ScanManager.oneShot(new ScanManager.SnapshotData("测试快照", 10, 2)),
                data -> { },
                1_000_000L);
        assertTrue(manager.requestScan());
        return manager;
    }

    @Test
    void disabledGeneralSwitchSkipsManagerCreation() {
        assertNull(Sudoersemc.createScanManagerIfEnabled(() -> false),
                "general.enabled=false 时不得创建扫描调度器");
    }

    @Test
    void enabledGeneralSwitchCreatesIdleManager() {
        ScanManager manager = Sudoersemc.createScanManagerIfEnabled(() -> true);

        assertNotNull(manager, "general.enabled=true 时应创建扫描调度器");
        assertFalse(manager.isRunning(), "新建调度器应处于空闲状态");
    }

    @Test
    void unreadableGeneralSwitchFallsBackToEnabled() {
        ScanManager manager = Sudoersemc.createScanManagerIfEnabled(() -> {
            throw new IllegalStateException("配置尚未加载");
        });

        assertNotNull(manager, "读取开关失败时按启用处理，避免模组失去扫描能力");
    }

    @Test
    void alwaysRescanFollowsConfiguredValue() {
        assertTrue(Sudoersemc.shouldRequestScanOnStart(() -> true));
        assertFalse(Sudoersemc.shouldRequestScanOnStart(() -> false));
    }

    @Test
    void unreadableAlwaysRescanFallsBackToNoRescan() {
        assertFalse(Sudoersemc.shouldRequestScanOnStart(() -> {
            throw new IllegalStateException("配置尚未加载");
        }), "读取开关失败时应保守地不触发启动扫描");
    }

    @Test
    void shutdownResetClearsActiveScan() {
        ScanManager manager = activeManager();
        assertTrue(manager.isRunning());

        Sudoersemc.resetScanManager(manager);

        assertEquals(ScanPhase.IDLE, manager.progress().phase());
        assertFalse(manager.isRunning(), "停服清理后不得继续 tick 旧扫描");
        assertTrue(manager.requestScan(), "停服清理后应允许下一次启动重新请求扫描");
    }

    @Test
    void ownedEntryCountIgnoresAdoptedEntries(@TempDir Path tempDir) {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_entries.json"));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(new ResourceLocation("minecraft", "stone")), 1L,
                PriceSource.TAG, null, true, 1L)));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(new ResourceLocation("minecraft", "dirt")), 2L,
                PriceSource.EXISTING, null, false, 2L)));

        assertEquals(2, store.size());
        assertEquals(1, Sudoersemc.countOwnedGeneratedEntries(store),
                "non-owned adopt/人工条目不应被算作自动扫描缓存");
        assertEquals(0, Sudoersemc.countOwnedGeneratedEntries(null));
    }

    @Test
    void startupScanSettingsReadConfiguredValues() {
        Sudoersemc.StartupScanSettings settings =
                Sudoersemc.StartupScanSettings.read(() -> true, () -> true);

        assertTrue(settings.enabled());
        assertTrue(settings.alwaysRescan());
    }

    @Test
    void unreadableStartupScanSettingsFailClosed() {
        Sudoersemc.StartupScanSettings enabledFailure = Sudoersemc.StartupScanSettings.read(() -> {
            throw new IllegalStateException("配置尚未加载");
        }, () -> true);
        assertFalse(enabledFailure.enabled());
        assertFalse(enabledFailure.alwaysRescan());

        Sudoersemc.StartupScanSettings rescanFailure = Sudoersemc.StartupScanSettings.read(
                () -> true,
                () -> {
                    throw new IllegalStateException("配置尚未加载");
                });
        assertFalse(rescanFailure.enabled());
        assertFalse(rescanFailure.alwaysRescan());
    }

    @Test
    void shutdownResetToleratesMissingManager() {
        assertDoesNotThrow(() -> Sudoersemc.resetScanManager(null));
    }

    @Test
    void zeroDropRatioIsMappedToFailClosedValue() {
        assertEquals(Double.MIN_VALUE, Sudoersemc.sanitizeMaxAllowedDropRatio(0.0D),
                "0.0 不得静默退回默认 0.5");
        assertEquals(Double.MIN_VALUE, Sudoersemc.sanitizeMaxAllowedDropRatio(-1.0D));
        assertEquals(Double.MIN_VALUE, Sudoersemc.sanitizeMaxAllowedDropRatio(1.5D));
        assertEquals(Double.MIN_VALUE, Sudoersemc.sanitizeMaxAllowedDropRatio(Double.NaN));
    }

    @Test
    void validDropRatioIsPreserved() {
        assertEquals(0.5D, Sudoersemc.sanitizeMaxAllowedDropRatio(0.5D));
        assertEquals(Double.MIN_VALUE,
                Sudoersemc.sanitizeMaxAllowedDropRatio(Double.MIN_VALUE));
    }

    /**
     * 回归：配置快照必须真正接线到扫描管线。
     *
     * <p>历史上 phase-14 的合并曾把 {@code createScanPipeline} 中的
     * {@code setOverflowScope}/{@code setProjectEFileRewriteEnabled} 覆盖掉，导致
     * {@code overwriteScope} 与 {@code allowProjectEFileRewrite} 变成零消费配置。
     * 本用例通过真实扫描应用路径验证两个开关都从同一份快照生效。</p>
     */
    @Test
    void validatedOverflowSettingsAreAppliedToPipeline(@TempDir Path tempDir) throws IOException {
        Path customEmc = tempDir.resolve("custom_emc.json");
        Files.writeString(customEmc, """
                { "entries": [ { "item": "sudoersemc:owned_overflow", "emc": 5000 } ] }
                """);
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated.json"));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(new ResourceLocation("sudoersemc", "owned_overflow")), 5L,
                PriceSource.RECIPE, null, true, 0L)));

        Path auditFile = tempDir.resolve("overflow_resets.json");
        OverflowResetLog resetLog = new OverflowResetLog(auditFile);
        OverflowProtection.Settings optIn = new OverflowProtection.Settings(
                true, 1_000L, 100L, 900L, 10, 5,
                OverflowScope.ENTIRE_CUSTOM_EMC, true, 42L);
        ScanPipeline pipeline = new ScanPipeline(
                store, null, null, null, null,
                new OverflowPipeline(new OverflowProtection(optIn), resetLog),
                null, null);
        pipeline.setProjectECustomEmcFile(customEmc);

        // 保守快照：必须把管线固定在 OWNED_ONLY + 禁止文件改写。
        Sudoersemc.applyOverflowSettings(pipeline, new OverflowProtection.Settings());
        PipelineResult conservative = pipeline.solveAndApply(PipelineSnapshot.empty());
        assertEquals(0, conservative.overflowResets(), "默认配置快照不得改写 ProjectE 文件");
        assertFalse(Files.exists(auditFile));

        // 显式 opt-in 快照：两个开关必须同时生效，否则该配置仍是零消费配置。
        Sudoersemc.applyOverflowSettings(pipeline, optIn);
        PipelineResult optedIn = pipeline.solveAndApply(PipelineSnapshot.empty());
        assertEquals(1, optedIn.overflowResets(),
                "entire_custom_emc + allowProjectEFileRewrite 必须真正接线到管线");
        assertEquals(1, resetLog.entries().size());
        assertEquals(OverflowScope.ENTIRE_CUSTOM_EMC, resetLog.entries().get(0).scope());
        assertTrue(Files.isRegularFile(auditFile), "写盘成功后必须立即落盘审计");
    }

    @Test
    void cacheIsReusableOnlyWhenFingerprintMatches() {
        ScanStateStore stateStore = new ScanStateStore(tempDir.resolve("scan_state.json"));
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_entries.json"));
        ScanFingerprint saved = ScanFingerprint.of(
                List.of("minecraft:stone"), List.of("minecraft:stick"),
                List.of("forge:ingots"), List.of("config=1"), List.of("phase-12"));
        ScanFingerprint changed = ScanFingerprint.of(
                List.of("minecraft:stone"), List.of("minecraft:stick", "minecraft:planks"),
                List.of("forge:ingots"), List.of("config=1"), List.of("phase-12"));

        assertFalse(Sudoersemc.hasReusableScanCache(stateStore, store, saved),
                "没有生成条目与扫描时间时不得复用缓存");

        store.stage(new GeneratedEmcEntry(
                ItemKey.of(new ResourceLocation("minecraft", "stone")),
                1L, PriceSource.FALLBACK, null, true, 0L));
        assertFalse(Sudoersemc.hasReusableScanCache(stateStore, store, saved),
                "没有成功扫描时间时不得复用缓存");

        stateStore.update(saved, 10L, 1, 0);
        assertTrue(Sudoersemc.hasReusableScanCache(stateStore, store, saved),
                "完整指纹一致时才允许复用启动缓存");
        assertFalse(Sudoersemc.hasReusableScanCache(stateStore, store, changed),
                "配方通道变化后必须重新扫描");
        assertFalse(Sudoersemc.hasReusableScanCache(stateStore, store, null),
                "无法计算当前指纹时必须保守重扫");
    }

    @Test
    void computeFailurePropagatesToFailedPhaseInsteadOfCompleted() throws InterruptedException {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve("generated_compute_failure.json"));
        ScanPipeline pipeline = new ScanPipeline(
                store, null, null, null, null, null, null, null);
        ItemKey item = ItemKey.of(new ResourceLocation("sudoersemc", "corrupt_snapshot"));
        Map<ItemKey, Long> corruptExistingValues = new HashMap<>();
        corruptExistingValues.put(item, null);
        PipelineSnapshot corruptSnapshot = new PipelineSnapshot(
                List.of(ItemSnapshot.of(item, false, 0L)),
                List.of(),
                corruptExistingValues,
                0, 0, 0);
        pipeline.stash(corruptSnapshot);

        ScanManager manager = new ScanManager(
                new BatchProcessor(10, false),
                ScanManager.oneShot(new ScanManager.SnapshotData("compute-failure", 1, 0)),
                new Sudoersemc.PipelineApplier(pipeline),
                1_000_000L);
        assertTrue(manager.requestScan());
        for (int tick = 0; tick < 200 && manager.isRunning(); tick++) {
            manager.tick();
            Thread.sleep(2L);
        }

        assertEquals(ScanPhase.FAILED, manager.progress().phase(),
                "后台 compute 抛异常时扫描必须进入 FAILED");
        assertTrue(manager.progress().detail().contains("后台求解失败"),
                "失败详情应保留可诊断原因，实际为 " + manager.progress().detail());
        assertEquals(0, store.size(),
                "compute 失败不得写入任何生成值");
    }

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void cacheIsRejectedWhenRecipeContentChangesWithoutIdChange() {
        ResourceLocation recipeId = new ResourceLocation("sudoersemc_test", "same_id_recipe");
        RecipeManager before = new FixedRecipeManager(
                shapedRecipe(recipeId, Items.DIRT, Items.DIAMOND));
        RecipeManager after = new FixedRecipeManager(
                shapedRecipe(recipeId, Items.STONE, Items.DIAMOND));

        List<String> beforeRecipes = Sudoersemc.recipeContentEntries(before, null);
        List<String> afterRecipes = Sudoersemc.recipeContentEntries(after, null);

        assertEquals(1, beforeRecipes.size());
        assertEquals(1, afterRecipes.size());
        assertTrue(beforeRecipes.get(0).startsWith(recipeId + "|"));
        assertTrue(afterRecipes.get(0).startsWith(recipeId + "|"),
                "配方 id 必须保持不变，变化的是内容");
        assertNotEquals(beforeRecipes, afterRecipes, "同 id 配方内容变化必须改变配方通道");

        ScanFingerprint saved = ScanFingerprint.of(
                List.of("minecraft:diamond"), beforeRecipes, List.of("forge:ingots"),
                List.of("config=1"), List.of("baseline=1"));
        ScanFingerprint changed = ScanFingerprint.of(
                List.of("minecraft:diamond"), afterRecipes, List.of("forge:ingots"),
                List.of("config=1"), List.of("baseline=1"));

        ScanStateStore stateStore = new ScanStateStore(tempDir.resolve("content_state.json"));
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("content_entries.json"));
        store.stage(new GeneratedEmcEntry(
                ItemKey.of(new ResourceLocation("minecraft", "diamond")),
                1L, PriceSource.FALLBACK, null, true, 0L));
        stateStore.update(saved, 10L, 1, 0);

        assertTrue(Sudoersemc.hasReusableScanCache(stateStore, store, saved),
                "内容相同的指纹应允许复用");
        assertFalse(Sudoersemc.hasReusableScanCache(stateStore, store, changed),
                "同 id 配方内容变化后不得复用启动缓存");
    }

    @Test
    void cacheIsRejectedWhenTagMembershipChangesWithoutTagNameChange() {
        TagKey<Item> tag = TagKey.create(Registries.ITEM,
                new ResourceLocation("sudoersemc_test", "same_tag"));
        List<String> before = Sudoersemc.tagContentEntries(
                Stream.of(tag), ignored -> List.of(Items.DIRT));
        List<String> after = Sudoersemc.tagContentEntries(
                Stream.of(tag), ignored -> List.of(Items.DIRT, Items.STONE));

        assertEquals(1, before.size());
        assertEquals(1, after.size());
        assertTrue(before.get(0).startsWith(tag.location() + "|"));
        assertTrue(after.get(0).startsWith(tag.location() + "|"));
        assertNotEquals(before, after, "同 id 标签成员变化必须改变标签通道");
    }

    private static ShapedRecipe shapedRecipe(ResourceLocation id, Item ingredient, Item result) {
        NonNullList<Ingredient> ingredients = NonNullList.withSize(1, Ingredient.EMPTY);
        ingredients.set(0, Ingredient.of(ingredient));
        return new ShapedRecipe(id, "", CraftingBookCategory.MISC, 1, 1, ingredients,
                new ItemStack(result));
    }

    private static final class FixedRecipeManager extends RecipeManager {
        private final Recipe<?> recipe;

        private FixedRecipeManager(Recipe<?> recipe) {
            this.recipe = recipe;
        }

        @Override
        public Stream<ResourceLocation> getRecipeIds() {
            return Stream.of(recipe.getId());
        }

        @Override
        public Optional<? extends Recipe<?>> byKey(ResourceLocation id) {
            return recipe.getId().equals(id) ? Optional.of(recipe) : Optional.empty();
        }
    }
}
