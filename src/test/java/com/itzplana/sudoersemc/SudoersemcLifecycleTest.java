package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.pipeline.PipelineResult;
import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
