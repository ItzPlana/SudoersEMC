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
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.ProjectEManualValues;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
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

    @BeforeAll
    static void bootstrapMinecraft() {
        // explain 的外部后端分支需要 BuiltInRegistries.ITEM 已初始化。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static ScanManager idleManager() {
        return new ScanManager(
                new BatchProcessor(1, false),
                () -> new ScanManager.SnapshotData("主世界", 0, 0),
                snapshot -> { },
                1_000_000L);
    }

    private QuarantinedDropsStore quarantineStoreWith(ResourceLocation itemId) {
        QuarantinedDropsStore store = new QuarantinedDropsStore(
                tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        store.record(new QuarantinedDropsStore.QuarantinedDrop(
                ItemKey.of(itemId), 100L, 10L, 0.9D,
                "drop-exceeds-threshold", 1_730_000_000_000L, false));
        return store;
    }

    private static DiagnosticReport sampleReport() {
        return new DiagnosticReport(
                12L, 3L, 4L, 2L, 1L, 1L, 1L, 0L, 0L, 0L, 500L, 1024L,
                Map.of("自定义计数", 2L));
    }

    private static EmcProvider provider(boolean available, boolean hasValue, long value) {
        return new EmcProvider() {
            @Override
            public boolean isAvailable() {
                return available;
            }

            @Override
            public boolean hasValue(ItemStack stack) {
                return hasValue;
            }

            @Override
            public long getValue(ItemStack stack) {
                return value;
            }

            @Override
            public boolean stageGeneratedValue(
                    ItemKey key, long newValue, PriceSource source, ResourceLocation recipeId) {
                return false;
            }

            @Override
            public void requestRemap(MinecraftServer server) {
                // 测试桩不执行重映射。
            }
        };
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
    void reloadConfigAppliedReturnsSuccess() {
        CommandService service = new CommandService(
                null, null, null, null, () -> ReloadDecision.APPLY_NOW);

        CommandResult result = service.reloadConfig();

        assertTrue(result.success());
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
    void explainMissingShowsProjectEManualValue() throws IOException {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        Path manualFile = tempDir.resolve("custom_emc.json");
        Files.writeString(manualFile,
                "{\"entries\":[{\"item\":\"minecraft:stone\",\"emc\":123}]}",
                StandardCharsets.UTF_8);
        ProjectEManualValues manualValues = new ProjectEManualValues(manualFile);
        CommandService service = new CommandService(
                null, null, store, null, null, null, null, manualValues);

        CommandResult result = service.explain("minecraft:stone");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("ProjectE 手动值: 123"));
        assertTrue(result.joinLines().contains("custom_emc.json"));
    }

    @Test
    void explainMissingShowsExternalProviderValue() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = new CommandService(
                null, null, store, null, null, null, provider(true, true, 456L), null);

        CommandResult result = service.explain("minecraft:stone");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("ProjectE 当前值: 456（来自外部后端）"));
    }

    @Test
    void explainMissingExplainsUnavailableProviderValue() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = new CommandService(
                null, null, store, null, null, null, provider(true, false, 0L), null);

        CommandResult result = service.explain("minecraft:stone");

        assertFalse(result.success());
        assertEquals(List.of(
                "未找到 minecraft:stone 的定价记录",
                "ProjectE 后端不可用或该物品没有 EMC 值"), result.lines());
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
    void approveMarksRecordAndPersistsIt() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        CommandService service = new CommandService(
                null, null, null, null, null, quarantineStore);

        CommandResult result = service.approve("minecraft:stone");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("已批准"));
        assertEquals(1, quarantineStore.size());
        assertTrue(quarantineStore.entries().get(0).approved());
        assertFalse(quarantineStore.isDirty(), "审批后必须立即落盘");
        assertTrue(Files.isRegularFile(quarantineStore.file()), "隔离记录文件必须存在");
    }

    @Test
    void approveMissingRecordFails() {
        QuarantinedDropsStore quarantineStore = new QuarantinedDropsStore(
                tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        CommandService service = new CommandService(
                null, null, null, null, null, quarantineStore);

        CommandResult result = service.approve("minecraft:stone");

        assertFalse(result.success());
        assertEquals(List.of("未找到 minecraft:stone 的隔离降价记录"), result.lines());
    }

    @Test
    void approveRejectsInvalidItemId() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        CommandService service = new CommandService(
                null, null, null, null, null, quarantineStore);

        CommandResult result = service.approve("minecraft:stone with space");

        assertFalse(result.success());
        assertEquals(List.of("无效的物品 id: minecraft:stone with space"), result.lines());
        assertFalse(quarantineStore.entries().get(0).approved(),
                "非法 id 不得影响已有隔离记录");
    }

    @Test
    void approveReportsPersistenceFailure() throws IOException {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        // 用同名 .tmp 目录阻塞原子写入，模拟磁盘写入失败。
        Path blockedTemp = tempDir.resolve(QuarantinedDropsStore.FILE_NAME + ".tmp");
        Files.createDirectories(blockedTemp);
        Files.writeString(blockedTemp.resolve("blocker"), "x", StandardCharsets.UTF_8);
        CommandService service = new CommandService(
                null, null, null, null, null, quarantineStore);

        CommandResult result = service.approve("minecraft:stone");

        assertFalse(result.success());
        assertTrue(result.joinLines().contains("保存到磁盘失败"));
        assertTrue(quarantineStore.isDirty(), "落盘失败后必须保留脏标记");
        assertTrue(quarantineStore.entries().get(0).approved(), "内存中的审批状态仍然有效");
    }

    @Test
    void explainShowsPersistedAlternativeCosts() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 42L, PriceSource.RECIPE, STONE, true, 123L,
                List.of(7L, 9L))));
        CommandService service = new CommandService(null, null, store, null);

        CommandResult result = service.explain("minecraft:stone");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("备选成本: 7, 9"));
    }

    @Test
    void explainLimitsAlternativesToFiveAndExplainsTruncation() {
        GeneratedEntryStore store = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        assertTrue(store.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 42L, PriceSource.RECIPE, STONE, true, 123L,
                List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L))));
        CommandService service = new CommandService(null, null, store, null);

        CommandResult result = service.explain("minecraft:stone");

        assertTrue(result.success());
        String text = result.joinLines();
        assertTrue(text.contains("备选成本: 1, 2, 3, 4, 5"));
        assertTrue(text.contains("共 7 条"));
        assertTrue(!text.contains("6, 7"));
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
            assertFalse(report.success());
            assertFalse(explain.success());
            assertFalse(cancel.success());
        });
    }
}
