package com.itzplana.sudoersemc.command;

import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.diagnostic.PriceExplanation;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.ProjectEManualValues;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

/**
 * 命令层的纯逻辑实现，不依赖 Brigadier 或 Minecraft 命令类型。
 *
 * <p>本类把服务器状态转换为普通字符串行，使命令逻辑可以脱离运行中的服务器进行单元测试。
 * 所有协作者都可以为 {@code null}：缺失的服务只会让对应子命令返回中文失败提示，而不会
 * 让异常穿透到命令调度器。</p>
 *
 * <p>公开方法均不阻塞：只读取内存中的扫描进度、存储快照与最后一次报告文件，真正的扫描
 * 由 {@link ScanManager} 在服务器 tick 中分片推进。所有方法都捕获运行时异常并转换为
 * {@link CommandResult#failure(String)}，因此调用方无需再额外防御。</p>
 */
public final class CommandService {

    private static final String SCAN_UNAVAILABLE = "扫描服务不可用";
    private static final String REPORT_UNAVAILABLE = "报告服务不可用";
    private static final String STORAGE_UNAVAILABLE = "存储服务不可用";
    private static final String QUARANTINE_UNAVAILABLE = "隔离记录服务不可用";
    private static final String DUPLICATE_SCAN = "已有扫描在进行中，请稍后再试";

    /** {@code explain} 最多展示的备选成本数量。 */
    private static final int MAX_EXPLAIN_ALTERNATIVES = 5;

    private final @Nullable ScanManager scanManager;
    private final @Nullable ReportManager reportManager;
    private final @Nullable GeneratedEntryStore entryStore;
    private final @Nullable ScanStateStore scanStateStore;
    private final @Nullable ReloadAction reloadAction;
    private final @Nullable QuarantinedDropsStore quarantinedDropsStore;
    private final @Nullable EmcProvider emcProvider;
    private final @Nullable ProjectEManualValues manualValues;

    /**
     * 创建不执行真实配置重载的命令服务。
     *
     * <p>该构造函数保留给只查询状态或诊断信息的调用方；{@link #reloadConfig()} 在缺少
     * 重载动作时返回“配置服务不可用”。</p>
     *
     * @param scanManager    扫描状态机，可为 {@code null}
     * @param reportManager  诊断报告管理器，可为 {@code null}
     * @param entryStore     已生成条目的存储，可为 {@code null}
     * @param scanStateStore 上一次扫描状态的存储，可为 {@code null}
     */
    public CommandService(@Nullable ScanManager scanManager,
                          @Nullable ReportManager reportManager,
                          @Nullable GeneratedEntryStore entryStore,
                          @Nullable ScanStateStore scanStateStore) {
        this(scanManager, reportManager, entryStore, scanStateStore, null, null, null, null);
    }

    /**
     * @param scanManager    扫描状态机，可为 {@code null}
     * @param reportManager  诊断报告管理器，可为 {@code null}
     * @param entryStore     已生成条目的存储，可为 {@code null}
     * @param scanStateStore 上一次扫描状态的存储，可为 {@code null}
     * @param reloadAction   真实配置重载动作，可为 {@code null}（视为不可用）
     */
    public CommandService(@Nullable ScanManager scanManager,
                          @Nullable ReportManager reportManager,
                          @Nullable GeneratedEntryStore entryStore,
                          @Nullable ScanStateStore scanStateStore,
                          @Nullable ReloadAction reloadAction) {
        this(scanManager, reportManager, entryStore, scanStateStore, reloadAction, null, null, null);
    }

    /**
     * 便于只测试审批与解释、不涉及配置重载的构造器。
     *
     * @param scanManager           扫描状态机，可为 {@code null}
     * @param reportManager         诊断报告管理器，可为 {@code null}
     * @param entryStore            已生成条目的存储，可为 {@code null}
     * @param scanStateStore        上一次扫描状态的存储，可为 {@code null}
     * @param reloadAction          真实配置重载动作，可为 {@code null}
     * @param quarantinedDropsStore 隔离记录存储，可为 {@code null}
     */
    public CommandService(@Nullable ScanManager scanManager,
                          @Nullable ReportManager reportManager,
                          @Nullable GeneratedEntryStore entryStore,
                          @Nullable ScanStateStore scanStateStore,
                          @Nullable ReloadAction reloadAction,
                          @Nullable QuarantinedDropsStore quarantinedDropsStore) {
        this(scanManager, reportManager, entryStore, scanStateStore,
                reloadAction, quarantinedDropsStore, null, null);
    }

    /**
     * 完整构造器。
     *
     * @param scanManager           扫描状态机，可为 {@code null}
     * @param reportManager         诊断报告管理器，可为 {@code null}
     * @param entryStore            已生成条目的存储，可为 {@code null}
     * @param scanStateStore        上一次扫描状态的存储，可为 {@code null}
     * @param reloadAction          真实配置重载动作，可为 {@code null}
     * @param quarantinedDropsStore 隔离记录存储，可为 {@code null}
     * @param emcProvider           ProjectE（或其它后端）的 EMC 查询接口，可为 {@code null}
     * @param manualValues          ProjectE 手动值视图，可为 {@code null}
     */
    public CommandService(@Nullable ScanManager scanManager,
                          @Nullable ReportManager reportManager,
                          @Nullable GeneratedEntryStore entryStore,
                          @Nullable ScanStateStore scanStateStore,
                          @Nullable ReloadAction reloadAction,
                          @Nullable QuarantinedDropsStore quarantinedDropsStore,
                          @Nullable EmcProvider emcProvider,
                          @Nullable ProjectEManualValues manualValues) {
        this.scanManager = scanManager;
        this.reportManager = reportManager;
        this.entryStore = entryStore;
        this.scanStateStore = scanStateStore;
        this.reloadAction = reloadAction;
        this.quarantinedDropsStore = quarantinedDropsStore;
        this.emcProvider = emcProvider;
        this.manualValues = manualValues;
    }

    /**
     * 查询当前扫描状态与上一次扫描结果。
     *
     * <p>输出固定五个字段：阶段、当前阶段进度、是否运行中、上次耗时与上次结果。扫描服务
     * 缺失时只返回一行“扫描服务不可用”。</p>
     *
     * @return 状态文本；永不抛出异常
     */
    public CommandResult status() {
        try {
            if (scanManager == null) {
                return CommandResult.ok(SCAN_UNAVAILABLE);
            }
            ScanProgress progress = scanManager.progress();
            if (progress == null) {
                return CommandResult.ok(SCAN_UNAVAILABLE);
            }

            long percent = Math.round(progress.fraction() * 100.0D);
            List<String> lines = new ArrayList<>(5);
            lines.add("扫描状态: " + progress.phase().label());
            lines.add("进度    : " + progress.processed() + "/" + progress.total() + " (" + percent + "%)");
            lines.add("运行中  : " + (progress.isRunning() ? "是" : "否"));
            appendLastScanLines(lines);
            return CommandResult.ok(lines);
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 请求一次全量重新扫描。
     *
     * @return 成功提示或中文失败原因；永不抛出异常
     */
    public CommandResult rescanAll() {
        try {
            if (scanManager == null) {
                return CommandResult.failure(SCAN_UNAVAILABLE);
            }
            if (scanManager.isRunning()) {
                return CommandResult.failure(DUPLICATE_SCAN);
            }
            boolean started = scanManager.requestScan();
            if (!started) {
                return CommandResult.failure(DUPLICATE_SCAN);
            }
            return CommandResult.ok("已请求全量重新扫描");
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 提交配置重载请求并返回真实结果。
     *
     * <p>重建由注入的 {@link ReloadAction} 执行：立即生效、扫描结束后生效以及校验失败
     * 分别对应成功、延后与失败三种回执。缺少重载动作或重建抛出运行时异常时返回中文
     * 失败提示，绝不向命令调度器传播异常。</p>
     *
     * @return 真实的重载结果；永不抛出异常
     */
    public CommandResult reloadConfig() {
        try {
            if (reloadAction == null) {
                return CommandResult.failure("配置服务不可用");
            }
            ReloadDecision decision = reloadAction.reload();
            if (decision == null) {
                decision = ReloadDecision.REJECT;
            }
            return switch (decision) {
                case APPLY_NOW -> CommandResult.ok("配置已重新加载并生效");
                case DEFER -> CommandResult.ok("扫描进行中，配置将在扫描结束后生效");
                case REJECT -> CommandResult.failure("配置校验失败，已保留上一份有效配置");
            };
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 读取上一次生成的诊断报告。
     *
     * @return 报告文本按行拆分后的成功结果，或中文失败提示；永不抛出异常
     */
    public CommandResult report() {
        try {
            if (reportManager == null) {
                return CommandResult.failure(REPORT_UNAVAILABLE);
            }
            Optional<String> lastReport = reportManager.readLastReport();
            if (lastReport == null || lastReport.isEmpty()) {
                return CommandResult.failure("尚无诊断报告，请先执行一次扫描");
            }
            String text = lastReport.get();
            if (text == null) {
                return CommandResult.failure("尚无诊断报告，请先执行一次扫描");
            }
            return CommandResult.ok(splitLines(text));
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 批准某个物品当前被隔离的降价记录。
     *
     * <p>本操作只把隔离记录标记为已批准并立即落盘，不会修改 {@link GeneratedEntryStore} 中的
     * 任何 EMC 数值，也不会触发重扫。参数必须是普通物品 id；带 NBT 的变体暂不支持。</p>
     *
     * @param itemId 物品 id 原文，例如 {@code minecraft:stone}
     * @return 明确的中文结果：已批准、无此记录或 id 非法；永不抛出异常
     */
    public CommandResult approve(String itemId) {
        try {
            if (itemId == null || itemId.isBlank()) {
                return CommandResult.failure("请提供物品 id，例如 minecraft:stone");
            }
            String trimmed = itemId.trim();
            ResourceLocation parsed = ResourceLocation.tryParse(trimmed);
            if (parsed == null) {
                return CommandResult.failure("无效的物品 id: " + itemId);
            }
            if (quarantinedDropsStore == null) {
                return CommandResult.failure(QUARANTINE_UNAVAILABLE);
            }

            ItemKey key = ItemKey.of(parsed);
            if (!quarantinedDropsStore.approve(key)) {
                return CommandResult.failure("未找到 " + trimmed + " 的隔离降价记录");
            }
            // 审批语义只落盘标记，不修改生成值，也不请求重扫。
            quarantinedDropsStore.saveIfDirty();
            return CommandResult.ok(
                    "已批准 " + trimmed + " 的隔离降价记录（仅标记审批状态，不修改 EMC 数值）");
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 解释某个物品当前的 EMC 定价。
     *
     * <p>命令参数只包含物品 id，因此这里查找的是无 NBT 变体的普通条目；带变体的条目需要
     * 由后续命令扩展提供变体信息。条目存在时展示最多五条备选成本；条目不存在时尝试查询
     * ProjectE 手动值与外部后端值，并给出对应提示。</p>
     *
     * @param itemId 物品 id 原文，例如 {@code minecraft:stone}
     * @return 多行解释文本，或中文失败提示；永不抛出异常
     */
    public CommandResult explain(String itemId) {
        try {
            if (itemId == null || itemId.isBlank()) {
                return CommandResult.failure("请提供物品 id，例如 minecraft:stone");
            }
            if (entryStore == null) {
                return CommandResult.failure(STORAGE_UNAVAILABLE);
            }

            String trimmed = itemId.trim();
            ResourceLocation parsed = ResourceLocation.tryParse(trimmed);
            if (parsed == null) {
                return CommandResult.failure("无效的物品 id: " + itemId);
            }

            ItemKey key = ItemKey.of(parsed);
            Map<ItemKey, GeneratedEmcEntry> snapshot = entryStore.snapshotAll();
            GeneratedEmcEntry entry = snapshot == null ? null : snapshot.get(key);
            if (entry == null) {
                return explainMissing(parsed, trimmed);
            }

            boolean owned = entryStore.isOwned(key);
            String ownership = owned ? "由 SudoersEMC 生成" : "非 SudoersEMC 所有";
            List<Long> alternatives = entry.alternativeCosts();
            int shown = Math.min(MAX_EXPLAIN_ALTERNATIVES, alternatives.size());
            List<Long> displayed = List.copyOf(alternatives.subList(0, shown));
            String note = alternatives.size() > shown
                    ? "备选成本仅显示前 " + MAX_EXPLAIN_ALTERNATIVES + " 条（共 "
                            + alternatives.size() + " 条）"
                    : null;
            PriceExplanation explanation = new PriceExplanation(
                    entry.item(),
                    entry.value(),
                    entry.source(),
                    entry.recipeId(),
                    owned,
                    ownership,
                    displayed,
                    note);
            return CommandResult.ok(splitLines(explanation.formatText()));
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 生成“没有生成条目”时的解释结果。
     *
     * <p>优先检查人工固定的 ProjectE 手动值，其次查询外部 EMC 后端。两者都没有值且没有
     * 后端时，保持原有的“未找到定价记录”失败结果；后端存在但无法给出值时额外说明。</p>
     *
     * @param itemId  已解析的物品 id
     * @param trimmed 用户输入的原始 id
     * @return 带提示的成功结果或沿用原语义的失败结果
     */
    private CommandResult explainMissing(ResourceLocation itemId, String trimmed) {
        Long manual = lookupManualValue(itemId);
        if (manual != null) {
            return CommandResult.ok(
                    "未找到 " + trimmed + " 的生成记录",
                    "ProjectE 手动值: " + manual + "（来自 custom_emc.json，SudoersEMC 不会覆盖）");
        }
        Long providerValue = lookupProviderValue(itemId);
        if (providerValue != null) {
            return CommandResult.ok(
                    "未找到 " + trimmed + " 的生成记录",
                    "ProjectE 当前值: " + providerValue + "（来自外部后端）");
        }
        if (emcProvider != null) {
            return new CommandResult(false, List.of(
                    "未找到 " + trimmed + " 的定价记录",
                    "ProjectE 后端不可用或该物品没有 EMC 值"));
        }
        return CommandResult.failure("未找到 " + trimmed + " 的定价记录");
    }

    /**
     * 查询 ProjectE 手动值文件中的普通物品条目。
     */
    private @Nullable Long lookupManualValue(ResourceLocation itemId) {
        if (manualValues == null) {
            return null;
        }
        try {
            return manualValues.manualValues().get(ItemKey.of(itemId));
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /**
     * 查询外部 EMC 后端当前值。
     */
    private @Nullable Long lookupProviderValue(ResourceLocation itemId) {
        if (emcProvider == null) {
            return null;
        }
        try {
            if (!emcProvider.isAvailable()) {
                return null;
            }
            Item item = BuiltInRegistries.ITEM.get(itemId);
            if (item == null || item == Items.AIR) {
                return null;
            }
            ItemStack stack = new ItemStack(item);
            if (!emcProvider.hasValue(stack)) {
                return null;
            }
            long value = emcProvider.getValue(stack);
            return value > 0L ? value : null;
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /**
     * 请求取消当前扫描。
     *
     * @return 成功提示或中文失败原因；永不抛出异常
     */
    public CommandResult cancel() {
        try {
            if (scanManager == null) {
                return CommandResult.failure(SCAN_UNAVAILABLE);
            }
            if (!scanManager.isRunning()) {
                return CommandResult.failure("当前没有进行中的扫描");
            }
            scanManager.cancel();
            return CommandResult.ok("已请求取消扫描");
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 追加“上次扫描”相关的两行。没有状态存储或从未成功记录过扫描时统一显示“无记录”，
     * 避免把默认的 0 毫秒误读为一次真实扫描。
     */
    private void appendLastScanLines(List<String> lines) {
        if (scanStateStore == null || scanStateStore.lastScanEpochMillis() <= 0L) {
            lines.add("上次扫描: 无记录");
            lines.add("上次结果: 无记录");
            return;
        }
        lines.add("上次扫描: " + scanStateStore.lastScanDurationMillis() + " ms");
        lines.add("上次结果: 已定价 " + scanStateStore.lastSolvedCount()
                + " 项，未解析 " + scanStateStore.lastUnresolvedCount() + " 项");
    }

    /**
     * 按行拆分文本，保留末尾的空行，确保报告内容可以无损地重新拼接。
     */
    private static List<String> splitLines(String text) {
        if (text.isEmpty()) {
            return List.of("");
        }
        return List.of(text.split("\\R", -1));
    }

    /**
     * 把意外异常转换为统一的失败结果，异常类型名足以让管理员定位问题。
     */
    private static CommandResult unexpectedFailure(RuntimeException error) {
        String name = error.getClass().getSimpleName();
        if (name.isEmpty()) {
            name = error.getClass().getName();
        }
        return CommandResult.failure("命令执行失败: " + name);
    }
}
