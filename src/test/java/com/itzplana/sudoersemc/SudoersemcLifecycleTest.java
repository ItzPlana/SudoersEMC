package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
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
}
