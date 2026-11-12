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
import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
}
