package com.itzplana.sudoersemc.command;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link CommandService} 的纯逻辑测试。
 *
 * <p>测试不启动 Forge 服务器：扫描状态机使用真实的 {@link ScanManager} 但只读取空闲进度，
 * 存储与报告使用指向 {@link TempDir} 的真实实现，从而同时覆盖空协作者与正常路径。</p>
 */
class CommandServiceTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");

    @TempDir
    Path tempDir;

    private static ScanManager idleManager() {
        return new ScanManager(
                new BatchProcessor(1, false),
                ScanManager.oneShot(new ScanManager.SnapshotData("主世界", 0, 0)),
                snapshot -> { },
                1_000_000L);
    }

    private static DiagnosticReport sampleReport() {
        return new DiagnosticReport(
                12L, 3L, 4L, 2L, 1L, 1L, 1L, 0L, 0L, 0L, 500L, 1024L,
                Map.of("自定义计数", 2L));
    }

    @Test
    void statusWithoutScanManagerReportsUnavailable() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult result = service.status();

        assertTrue(result.success());
        assertEquals(List.of("扫描服务不可用"), result.lines());
    }

    @Test
    void statusWithIdleManagerShowsIdlePhase() {
        CommandService service = new CommandService(idleManager(), null, null, null);

        CommandResult result = service.status();

        assertTrue(result.success());
        assertEquals(List.of(
                "扫描状态: 空闲",
                "进度    : 0/0 (0%)",
                "运行中  : 否",
                "上次扫描: 无记录",
                "上次结果: 无记录"), result.lines());
        assertTrue(result.joinLines().contains("空闲"));
        assertTrue(result.joinLines().contains("运行中  : 否"));
    }

    @Test
    void statusUsesScanStateStoreForLastScanSummary() {
        ScanStateStore stateStore = new ScanStateStore(tempDir.resolve(ScanStateStore.FILE_NAME));
        stateStore.update(ScanFingerprint.of(null, null, null, null, null), 1234L, 56, 7);
        CommandService service = new CommandService(idleManager(), null, null, stateStore);

        CommandResult result = service.status();

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("上次扫描: 1234 ms"));
        assertTrue(result.joinLines().contains("上次结果: 已定价 56 项，未解析 7 项"));
    }

    @Test
    void statusShowsRunningDetail() {
        ScanManager manager = idleManager();
        assertTrue(manager.requestScan());
        CommandService service = new CommandService(manager, null, null, null);

        CommandResult result = service.status();

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("详情    : 等待采集快照"));
    }

    @Test
    void statusShowsFailureDetail() {
        ScanManager failing = new ScanManager(
                new BatchProcessor(1, false),
                () -> {
                    throw new IllegalStateException("快照采集失败");
                },
                snapshot -> { },
                1_000_000L);
        assertTrue(failing.requestScan());
        failing.tick();
        CommandService service = new CommandService(failing, null, null, null);

        CommandResult result = service.status();

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("详情    : 快照采集失败"));
    }

    @Test
    void rescanAllSucceedsOnceThenRejectsDuplicateRequest() {
        ScanManager manager = idleManager();
        CommandService service = new CommandService(manager, null, null, null);

        CommandResult first = service.rescanAll();
        CommandResult second = service.rescanAll();

        assertTrue(first.success());
        assertEquals(List.of("已请求全量重新扫描"), first.lines());
        assertFalse(second.success());
        assertEquals(List.of("已有扫描在进行中，请稍后再试"), second.lines());
        assertTrue(manager.isRunning());
    }

    @Test
    void rescanAllWithoutScanManagerFails() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult result = service.rescanAll();

        assertFalse(result.success());
        assertEquals(List.of("扫描服务不可用"), result.lines());
    }

    @Test
    void reloadConfigWithoutActionFails() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult result = assertDoesNotThrow(service::reloadConfig);

        assertFalse(result.success());
        assertEquals(List.of("配置服务不可用"), result.lines());
    }

    @Test
    void reloadConfigInvokesActionOnceAndReportsApplied() {
        AtomicInteger invocations = new AtomicInteger();
        CommandService service = new CommandService(
                null, null, null, null,
                () -> {
                    invocations.incrementAndGet();
                    return ReloadDecision.APPLY_NOW;
                });

        CommandResult result = service.reloadConfig();

        assertTrue(result.success());
        assertEquals(1, invocations.get(), "reload action must run exactly once");
        assertEquals(List.of("配置已重新加载并生效"), result.lines());
    }

    @Test
    void reloadConfigDeferredDuringScanReturnsSuccess() {
        CommandService service = new CommandService(
                null, null, null, null, () -> ReloadDecision.DEFER);

        CommandResult result = service.reloadConfig();

        assertTrue(result.success());
        assertEquals(List.of("扫描进行中，配置将在扫描结束后生效"), result.lines());
    }

    @Test
    void reloadConfigRejectedKeepsPreviousConfig() {
        CommandService service = new CommandService(
                null, null, null, null, () -> ReloadDecision.REJECT);

        CommandResult result = service.reloadConfig();

        assertFalse(result.success());
        assertEquals(List.of("配置校验失败，已保留上一份有效配置"), result.lines());
    }

    @Test
    void reloadConfigRejectsNullDecision() {
        CommandService service = new CommandService(null, null, null, null, () -> null);

        CommandResult result = service.reloadConfig();

        assertFalse(result.success());
        assertEquals(List.of("配置校验失败，已保留上一份有效配置"), result.lines());
    }

    @Test
    void approveQuarantineWithoutActionFails() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult result = service.approveQuarantine("minecraft:stone");

        assertFalse(result.success());
        assertEquals(List.of("隔离审批服务不可用"), result.lines());
    }

    @Test
    void approveQuarantineInvokesInjectedActionWithTrimmedId() {
        AtomicReference<String> seen = new AtomicReference<>();
        CommandService service = new CommandService(
                null, null, null, null, null,
                item -> {
                    seen.set(item);
                    return CommandResult.ok("已批准 " + item);
                });

        CommandResult result = service.approveQuarantine("  minecraft:stone  ");

        assertTrue(result.success());
        assertEquals("minecraft:stone", seen.get());
        assertEquals(List.of("已批准 minecraft:stone"), result.lines());
    }

    @Test
    void reloadConfigCatchesActionFailure() {
        CommandService service = new CommandService(null, null, null, null, () -> {
            throw new IllegalStateException("boom");
        });

        CommandResult result = assertDoesNotThrow(service::reloadConfig);

        assertFalse(result.success());
        assertEquals(List.of("命令执行失败: IllegalStateException"), result.lines());
    }

    @Test
    void reportWithoutManagerFails() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult result = service.report();

        assertFalse(result.success());
        assertEquals(List.of("报告服务不可用"), result.lines());
    }

    @Test
    void reportWithoutFileFails() {
        ReportManager reportManager = new ReportManager(tempDir);
        CommandService service = new CommandService(null, reportManager, null, null);

        CommandResult result = service.report();

        assertFalse(result.success());
        assertEquals(List.of("尚无诊断报告，请先执行一次扫描"), result.lines());
    }

    @Test
    void reportAfterWritingMatchesStoredContent() {
        ReportManager reportManager = new ReportManager(tempDir);
        DiagnosticReport report = sampleReport();
        reportManager.writeReport(report);
        CommandService service = new CommandService(null, reportManager, null, null);

        CommandResult result = service.report();

        assertTrue(result.success());
        assertEquals(List.of(report.formatText().split("\n")), result.lines());
        assertEquals(report.formatText(), result.joinLines());
    }

    @Test
    void explainRejectsNullAndBlankArguments() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult nullResult = service.explain(null);
        CommandResult blankResult = service.explain("   ");

        assertFalse(nullResult.success());
        assertEquals(List.of("请提供物品 id，例如 minecraft:stone"), nullResult.lines());
        assertFalse(blankResult.success());
        assertEquals(List.of("请提供物品 id，例如 minecraft:stone"), blankResult.lines());
    }

    @Test
    void explainRejectsInvalidItemId() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = new CommandService(null, null, store, null);

        CommandResult result = service.explain("minecraft:stone with space");

        assertFalse(result.success());
        assertEquals(List.of("无效的物品 id: minecraft:stone with space"), result.lines());
    }

    @Test
    void explainWithoutEntryStoreFails() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult result = service.explain("minecraft:stone");

        assertFalse(result.success());
        assertEquals(List.of("存储服务不可用"), result.lines());
    }

    @Test
    void explainUnknownItemFails() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = new CommandService(null, null, store, null);

        CommandResult result = service.explain("minecraft:stone");

        assertFalse(result.success());
        assertEquals(List.of("未找到 minecraft:stone 的定价记录"), result.lines());
    }

    @Test
    void explainKnownItemShowsValueAndGeneratedOwnership() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 42L, PriceSource.RECIPE, STONE, true, 123L)));
        CommandService service = new CommandService(null, null, store, null);

        CommandResult result = service.explain("minecraft:stone");

        assertTrue(result.success());
        String text = result.joinLines();
        assertTrue(text.contains("物品: minecraft:stone"));
        assertTrue(text.contains("EMC : 42"));
        assertTrue(text.contains("来源: RECIPE"));
        assertTrue(text.contains("归属: 由 SudoersEMC 生成"));
        assertTrue(text.contains("备选成本: 无"));
        assertTrue(text.contains("备注: 无"));
    }

    @Test
    void explainNonOwnedItemShowsOtherOwnership() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(DIRT), 5L, PriceSource.EXTERNAL_BASELINE, null, false, 1L)));
        CommandService service = new CommandService(null, null, store, null);

        CommandResult result = service.explain("minecraft:dirt");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("归属: 非 SudoersEMC 所有"));
    }

    @Test
    void cancelWithoutManagerFails() {
        CommandService service = new CommandService(null, null, null, null);

        CommandResult result = service.cancel();

        assertFalse(result.success());
        assertEquals(List.of("扫描服务不可用"), result.lines());
    }

    @Test
    void cancelWhenIdleFails() {
        CommandService service = new CommandService(idleManager(), null, null, null);

        CommandResult result = service.cancel();

        assertFalse(result.success());
        assertEquals(List.of("当前没有进行中的扫描"), result.lines());
    }

    @Test
    void cancelWhenRunningSucceeds() {
        ScanManager manager = idleManager();
        assertTrue(manager.requestScan());
        CommandService service = new CommandService(manager, null, null, null);

        CommandResult result = service.cancel();

        assertTrue(result.success());
        assertEquals(List.of("已请求取消扫描"), result.lines());
        assertTrue(manager.isCancelRequested());
    }

    @Test
    void allMethodsTolerateAllNullCollaborators() {
        CommandService service = new CommandService(null, null, null, null);

        assertDoesNotThrow(() -> {
            CommandResult status = service.status();
            CommandResult rescan = service.rescanAll();
            CommandResult reload = service.reloadConfig();
            CommandResult report = service.report();
            CommandResult explain = service.explain("minecraft:stone");
            CommandResult cancel = service.cancel();

            assertNotNull(status);
            assertNotNull(rescan);
            assertNotNull(reload);
            assertNotNull(report);
            assertNotNull(explain);
            assertNotNull(cancel);
            assertFalse(rescan.success());
            assertFalse(reload.success());
            assertFalse(report.success());
            assertFalse(explain.success());
            assertFalse(cancel.success());
        });
    }
}
