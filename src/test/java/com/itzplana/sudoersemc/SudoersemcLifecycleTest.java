package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.command.SudoemcCommands;
import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.config.RuntimeConfigSnapshot;
import com.itzplana.sudoersemc.config.SudoersemcConfig;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.pipeline.PipelineResult;
import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 入口层生命周期开关的单元测试。
 *
 * <p>这些用例只覆盖不依赖 Forge 运行时的纯决策与清理逻辑，确保
 * {@code [general] enabled} / {@code alwaysRescan} 不会再次变成零消费配置。</p>
 */
class SudoersemcLifecycleTest {

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

    /**
     * 回归：配置重建与扫描请求必须真正串行化，而不是只在命令入口加 {@code synchronized}。
     *
     * <p>测试同时覆盖确定性交错与并发交错：扫描进行中重建必须返回 {@code DEFER} 且保留旧
     * manager；重建成功替换后旧 manager 必须退役且不再运行。并发循环中无论哪一方先获得
     * {@code RUNTIME_LOCK}，都不允许出现“旧 manager 被替换但仍保留进行中扫描”的遗弃状态。</p>
     */
    @Test
    void configReloadAndRequestScanAreSerializedWithoutAbandoningOldManager(@TempDir Path tempDir)
            throws Exception {
        Object oldEntryStore = getStaticField(Sudoersemc.class, "entryStore");
        Object oldStateStore = getStaticField(Sudoersemc.class, "scanStateStore");
        Object oldReportManager = getStaticField(Sudoersemc.class, "reportManager");
        Object oldOverflowLog = getStaticField(Sudoersemc.class, "overflowResetLog");
        Object oldScanManager = getStaticField(Sudoersemc.class, "scanManager");
        Object oldScanPipeline = getStaticField(Sudoersemc.class, "scanPipeline");
        Object oldCommandService = getStaticField(Sudoersemc.class, "commandService");
        Object oldAppliedGeneration = getStaticField(Sudoersemc.class, "appliedSnapshotGeneration");
        Object oldActiveSnapshot = getStaticField(SudoersemcConfig.class, "activeSnapshot");
        Object oldSudoemcService = getStaticField(SudoemcCommands.class, "service");
        ExecutorService pool = null;

        try {
            setStaticField(Sudoersemc.class, "entryStore",
                    new GeneratedEntryStore(tempDir.resolve("generated.json")));
            setStaticField(Sudoersemc.class, "scanStateStore",
                    new ScanStateStore(tempDir.resolve("scan_state.json")));
            setStaticField(Sudoersemc.class, "reportManager", new ReportManager(tempDir));
            setStaticField(Sudoersemc.class, "overflowResetLog",
                    new OverflowResetLog(tempDir.resolve("overflow_resets.json")));
            setStaticField(Sudoersemc.class, "scanManager", null);
            setStaticField(Sudoersemc.class, "scanPipeline", null);
            setStaticField(Sudoersemc.class, "appliedSnapshotGeneration", 0L);

            installSnapshot(RuntimeConfigSnapshot.safeDefaults().withGeneration(1L));
            assertEquals(ReloadDecision.APPLY_NOW,
                    Sudoersemc.rebuildRuntimeFromConfig("test-initial"));
            ScanManager initial = Sudoersemc.scanManager();
            assertNotNull(initial, "快照重建必须发布新的扫描调度器");

            // 确定性交错 1：扫描先启动，重建必须在同一把锁下观察到并延迟。
            assertTrue(Sudoersemc.requestScanSynchronized());
            assertTrue(initial.isRunning());
            installSnapshot(RuntimeConfigSnapshot.safeDefaults().withGeneration(2L));
            assertEquals(ReloadDecision.DEFER,
                    Sudoersemc.rebuildRuntimeFromConfig("scan-running"));
            assertSame(initial, Sudoersemc.scanManager(),
                    "扫描进行中不得替换当前 manager");
            assertFalse(initial.isRetired(), "延迟重建不得提前退役旧 manager");

            // 确定性交错 2：扫描结束后应用同一快照；旧 manager 必须退役且不保留扫描。
            initial.cancel();
            initial.reset();
            assertEquals(ReloadDecision.APPLY_NOW,
                    Sudoersemc.rebuildRuntimeFromConfig("after-scan"));
            ScanManager afterDefer = Sudoersemc.scanManager();
            assertNotSame(initial, afterDefer);
            assertTrue(initial.isRetired(), "被替换的旧 manager 必须退役");
            assertFalse(initial.isRunning(), "旧 manager 不得保留进行中的扫描");
            assertFalse(initial.requestScan(), "退役 manager 必须拒绝新的扫描请求");

            // 乱序完成的旧重建不得用更老的 generation 覆盖新组件。
            installSnapshot(RuntimeConfigSnapshot.safeDefaults().withGeneration(1L));
            assertEquals(ReloadDecision.APPLY_NOW,
                    Sudoersemc.rebuildRuntimeFromConfig("stale-generation"));
            assertSame(afterDefer, Sudoersemc.scanManager(),
                    "过期 generation 不得替换已发布的新 manager");

            // 并发交错：requestScan 与 rebuild 竞争同一把运行时锁。
            pool = Executors.newFixedThreadPool(2);
            for (int generation = 3; generation <= 32; generation++) {
                ScanManager current = Sudoersemc.scanManager();
                if (current != null && current.isRunning()) {
                    current.cancel();
                    current.reset();
                }
                final long nextGeneration = generation;
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> request = pool.submit(() -> {
                    start.await();
                    return Sudoersemc.requestScanSynchronized();
                });
                Future<ReloadDecision> reload = pool.submit(() -> {
                    start.await();
                    installSnapshot(RuntimeConfigSnapshot.safeDefaults()
                            .withGeneration(nextGeneration));
                    return Sudoersemc.rebuildRuntimeFromConfig("concurrent");
                });
                start.countDown();

                request.get(10, TimeUnit.SECONDS);
                ReloadDecision decision = reload.get(10, TimeUnit.SECONDS);
                ScanManager after = Sudoersemc.scanManager();
                if (after != current) {
                    assertNotNull(current);
                    assertTrue(current.isRetired(), "旧 manager 被替换后必须退役");
                    assertFalse(current.isRunning(), "旧 manager 不得保留进行中的扫描");
                } else {
                    assertEquals(ReloadDecision.DEFER, decision,
                            "请求先启动扫描时重建必须延迟而不是替换旧 manager");
                    assertFalse(current.isRetired(), "延迟重建不得提前退役旧 manager");
                }
            }
        } finally {
            if (pool != null) {
                pool.shutdownNow();
            }
            ScanManager current = (ScanManager) getStaticField(Sudoersemc.class, "scanManager");
            if (current != null) {
                current.retire();
            }
            setStaticField(Sudoersemc.class, "entryStore", oldEntryStore);
            setStaticField(Sudoersemc.class, "scanStateStore", oldStateStore);
            setStaticField(Sudoersemc.class, "reportManager", oldReportManager);
            setStaticField(Sudoersemc.class, "overflowResetLog", oldOverflowLog);
            setStaticField(Sudoersemc.class, "scanManager", oldScanManager);
            setStaticField(Sudoersemc.class, "scanPipeline", oldScanPipeline);
            setStaticField(Sudoersemc.class, "commandService", oldCommandService);
            setStaticField(Sudoersemc.class, "appliedSnapshotGeneration", oldAppliedGeneration);
            setStaticField(SudoersemcConfig.class, "activeSnapshot", oldActiveSnapshot);
            setStaticField(SudoemcCommands.class, "service", oldSudoemcService);
        }
    }

    private static void installSnapshot(RuntimeConfigSnapshot snapshot) throws Exception {
        setStaticField(SudoersemcConfig.class, "activeSnapshot", snapshot);
    }

    private static Object getStaticField(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    private static void setStaticField(Class<?> owner, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, value);
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

    @Test
    void runtimeConfigurationSwitchesAreAppliedToPipeline() throws Exception {
        ScanPipeline pipeline = new ScanPipeline(null, null, null, null, null, null, null, null);

        Sudoersemc.applyRuntimeSwitches(pipeline, false, false);

        assertEquals(Boolean.FALSE, readField(pipeline, "solveRecipesEnabled"),
                "recipe.solveRecipes=false 必须关闭管线配方求解");
        assertEquals(Boolean.FALSE, readField(pipeline, "generateReportsEnabled"),
                "diagnostic.generateReports=false 必须关闭诊断报告");
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
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
}
