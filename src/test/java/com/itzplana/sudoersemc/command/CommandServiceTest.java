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
import com.itzplana.sudoersemc.integration.ProjectEManualValues;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
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
                () -> ScanManager.oneShot(new ScanManager.SnapshotData("主世界", 0, 0)).begin(),
                snapshot -> { },
                1_000_000L);
    }

    private QuarantinedDropsStore quarantineStoreWith(ResourceLocation itemId) {
        return quarantineStoreWith(itemId, 100L, 10L, false, false);
    }

    private QuarantinedDropsStore quarantineStoreWith(ResourceLocation itemId,
                                                      long previousValue,
                                                      long proposedValue,
                                                      boolean approved,
                                                      boolean applied) {
        QuarantinedDropsStore store = new QuarantinedDropsStore(
                tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        store.record(new QuarantinedDropsStore.QuarantinedDrop(
                ItemKey.of(itemId), previousValue, proposedValue, 0.9D,
                "drop-exceeds-threshold", 1_730_000_000_000L, approved, applied));
        return store;
    }

    private ProjectEManualValues noManualValues() {
        return new ProjectEManualValues(
                tempDir.resolve("ProjectE").resolve("custom_emc.json"));
    }

    private ProjectEManualValues manualValuesWith(ResourceLocation itemId, long value) throws Exception {
        Path file = tempDir.resolve("manual-custom_emc.json");
        Files.writeString(file, "{\"entries\":[{\"item\":\"" + itemId
                + "\",\"emc\":" + value + "}]}", StandardCharsets.UTF_8);
        return new ProjectEManualValues(file);
    }

    private static CommandService approvalService(GeneratedEntryStore entries,
                                                  QuarantinedDropsStore quarantine,
                                                  ProjectEManualValues manual,
                                                  boolean applyEnabled,
                                                  Predicate<ItemKey> exclusion,
                                                  long minimumEmc) {
        return approvalService(entries, quarantine, manual, applyEnabled, exclusion,
                minimumEmc, Long.MAX_VALUE);
    }

    private static CommandService approvalService(GeneratedEntryStore entries,
                                                  QuarantinedDropsStore quarantine,
                                                  ProjectEManualValues manual,
                                                  boolean applyEnabled,
                                                  Predicate<ItemKey> exclusion,
                                                  long minimumEmc,
                                                  long overflowThreshold) {
        return new CommandService(
                null, null, entries, null, null, quarantine, null, manual,
                applyEnabled,
                exclusion == null ? null : () -> exclusion,
                () -> minimumEmc,
                () -> overflowThreshold);
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
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, key -> false, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("已应用 10"));
        assertEquals(1, quarantineStore.size());
        assertTrue(quarantineStore.entries().get(0).approved());
        assertFalse(quarantineStore.isDirty(), "审批后必须立即落盘");
        assertTrue(Files.isRegularFile(quarantineStore.file()), "隔离记录文件必须存在");
        assertFalse(entryStore.isDirty(), "应用后生成条目必须立即落盘");
        assertTrue(Files.isRegularFile(entryStore.file()), "生成条目文件必须存在");
    }

    @Test
    void approveWritesProposedValueWithApprovedSource() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, key -> false, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("来源 APPROVED"));
        GeneratedEmcEntry applied = entryStore.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(applied);
        assertEquals(10L, applied.value());
        assertEquals(PriceSource.APPROVED, applied.source());
        assertTrue(applied.owned());
        assertTrue(applied.alternativeCosts().isEmpty());
        assertEquals(List.of(), applied.alternativeCosts());
    }

    @Test
    void approveTwiceDoesNotOverwriteAppliedValue() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, key -> false, 1L);

        assertTrue(service.approve("minecraft:stone").success());
        assertEquals(1, entryStore.size());

        // 模拟后续扫描更新同一个 owned 条目；第二次审批不得覆盖它。
        assertTrue(entryStore.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 999L, PriceSource.RECIPE, null, true, 2L)));

        CommandResult second = service.approve("minecraft:stone");

        assertTrue(second.success());
        assertTrue(second.joinLines().contains("未重复应用"));
        assertEquals(1, entryStore.size());
        GeneratedEmcEntry current = entryStore.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(current);
        assertEquals(999L, current.value());
        assertEquals(PriceSource.RECIPE, current.source());
        assertEquals(1, quarantineStore.size());
        assertTrue(quarantineStore.entries().get(0).approved());
    }

    @Test
    void approveRefusesManualValueAndLeavesStoreUnchanged() throws Exception {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, manualValuesWith(STONE, 123L), true, null, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertFalse(result.success());
        assertTrue(result.joinLines().contains("手动值"));
        assertTrue(entryStore.snapshotAll().isEmpty(), "手动值物品不得写入生成条目");
        assertFalse(quarantineStore.entries().get(0).approved(),
                "拒绝应用时不得把记录标记为已批准");
    }

    @Test
    void approveRefusesExcludedItemAndLeavesStoreUnchanged() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        Predicate<ItemKey> exclusion = key -> key.equals(ItemKey.of(STONE));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, exclusion, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertFalse(result.success());
        assertTrue(result.joinLines().contains("排除"));
        assertTrue(entryStore.snapshotAll().isEmpty(), "排除项不得写入生成条目");
        assertFalse(quarantineStore.entries().get(0).approved(),
                "拒绝应用时不得把记录标记为已批准");
    }

    @Test
    void approveWithApplyDisabledMarksApprovedWithoutApplying() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), false, null, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertTrue(result.success(), "审计模式下的批准必须成功");
        String text = result.joinLines();
        assertTrue(text.contains("仅记录审批状态"), "消息必须说明未修改 EMC: " + text);
        assertTrue(text.contains("approveAppliesImmediately=false"));
        assertTrue(quarantineStore.entries().get(0).approved(),
                "审计模式仍必须把记录标记为已批准");
        assertFalse(quarantineStore.isDirty(), "审批状态必须立即落盘");
        assertEquals(0, entryStore.size(), "审计模式不得写入生成条目");
        assertTrue(entryStore.snapshotAll().isEmpty());
        assertFalse(entryStore.isDirty());
    }

    @Test
    void approveWithApplyDisabledIsIdempotent() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), false, null, 1L);

        assertTrue(service.approve("minecraft:stone").success());
        CommandResult second = service.approve("minecraft:stone");

        assertTrue(second.success());
        assertTrue(second.joinLines().contains("仅记录审批状态"),
                "审计模式重复批准必须说明只记录状态: " + second.joinLines());
        assertTrue(quarantineStore.entries().get(0).approved());
        assertFalse(quarantineStore.entries().get(0).applied());
        assertEquals(0, entryStore.size());
    }

    @Test
    void approveHistoricalApprovedButNotAppliedRecordApplies() {
        QuarantinedDropsStore quarantineStore =
                quarantineStoreWith(STONE, 100L, 10L, true, false);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, key -> false, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertTrue(result.success(), "Phase 21 遗留的 approved=true 记录必须可应用: "
                + result.joinLines());
        GeneratedEmcEntry applied = entryStore.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(applied);
        assertEquals(10L, applied.value());
        assertEquals(PriceSource.APPROVED, applied.source());
        assertTrue(quarantineStore.entries().get(0).approved());
        assertTrue(quarantineStore.entries().get(0).applied());
    }

    @Test
    void approveAfterAuditModeCanApplyWhenReenabled() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService auditService = approvalService(
                entryStore, quarantineStore, noManualValues(), false, null, 1L);

        assertTrue(auditService.approve("minecraft:stone").success());
        assertFalse(quarantineStore.entries().get(0).applied());
        assertEquals(0, entryStore.size());

        // 模拟重启后重新加载隔离记录，再把开关切回 true。
        QuarantinedDropsStore reloaded = new QuarantinedDropsStore(quarantineStore.file());
        reloaded.load();
        CommandService applyService = approvalService(
                entryStore, reloaded, noManualValues(), true, key -> false, 1L);

        CommandResult result = applyService.approve("minecraft:stone");

        assertTrue(result.success(), "审计模式记录在开关恢复后必须可应用: " + result.joinLines());
        assertTrue(result.joinLines().contains("来源 APPROVED"));
        GeneratedEmcEntry applied = entryStore.snapshotAll().get(ItemKey.of(STONE));
        assertNotNull(applied);
        assertEquals(10L, applied.value());
        assertTrue(reloaded.entries().get(0).applied());
    }

    @Test
    void approveReconcilesAppliedFlagWhenEntryAlreadyPersisted() {
        QuarantinedDropsStore quarantineStore =
                quarantineStoreWith(STONE, 100L, 10L, true, false);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        assertTrue(entryStore.stage(new GeneratedEmcEntry(
                ItemKey.of(STONE), 10L, PriceSource.APPROVED, null, true, 1L)));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, key -> false, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertTrue(result.success());
        assertTrue(result.joinLines().contains("未重复应用"));
        assertEquals(10L, entryStore.snapshotAll().get(ItemKey.of(STONE)).value());
        assertTrue(quarantineStore.entries().get(0).applied(),
                "崩溃窗口后再次 approve 必须补齐 applied 标记");
    }

    @Test
    void approveRefusesValueAboveOverflowThreshold() {
        QuarantinedDropsStore quarantineStore =
                quarantineStoreWith(STONE, 1_000_000L, 1_000L, false, false);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, key -> false, 1L, 999L);

        CommandResult result = service.approve("minecraft:stone");

        assertFalse(result.success());
        assertTrue(result.joinLines().contains("溢出阈值"), result.joinLines());
        assertTrue(entryStore.snapshotAll().isEmpty());
        assertFalse(quarantineStore.entries().get(0).approved());
    }

    @Test
    void approveDoesNotMarkApprovedWhenEntrySaveFails() throws Exception {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        // 让目标文件本身是目录：save() 无法原子替换，dirty 会保持 true。
        Path blockedFile = tempDir.resolve("blocked-generated-entries");
        Files.createDirectories(blockedFile);
        Files.writeString(blockedFile.resolve("blocker"), "x", StandardCharsets.UTF_8);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(blockedFile);
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, key -> false, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertFalse(result.success());
        assertTrue(result.joinLines().contains("写入磁盘失败"), result.joinLines());
        assertTrue(entryStore.snapshotAll().isEmpty(), "写入失败后必须回滚内存中的条目");
        assertFalse(quarantineStore.entries().get(0).approved(),
                "数值未落盘时绝不能标记批准");
        assertFalse(quarantineStore.entries().get(0).applied());
    }

    @Test
    void approveRefusesWhenExclusionDetectorUnavailable() {
        QuarantinedDropsStore quarantineStore = quarantineStoreWith(STONE);
        GeneratedEntryStore entryStore = new GeneratedEntryStore(
                tempDir.resolve(GeneratedEntryStore.FILE_NAME));
        // exclusion == null 会传入 null supplier，应用器必须 fail-closed 拒绝。
        CommandService service = approvalService(
                entryStore, quarantineStore, noManualValues(), true, null, 1L);

        CommandResult result = service.approve("minecraft:stone");

        assertFalse(result.success());
        assertTrue(result.joinLines().contains("排除规则服务不可用"));
        assertTrue(entryStore.snapshotAll().isEmpty(), "排除规则不可用时不得写入生成条目");
        assertFalse(quarantineStore.entries().get(0).approved(),
                "拒绝应用时不得把记录标记为已批准");
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
