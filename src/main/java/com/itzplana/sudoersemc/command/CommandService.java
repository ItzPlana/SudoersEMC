package com.itzplana.sudoersemc.command;

import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.diagnostic.PriceExplanation;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 命令层的纯逻辑实现，不依赖 Brigadier 或 Minecraft 命令类型。
 *
 * <p>本类把服务器状态转换为普通字符串行，使命令逻辑可以脱离运行中的服务器进行单元测试。
 * 所有协作者都可以为 {@code null}：缺失的服务只会让对应子命令返回中文失败提示，而不会
 * 让异常穿透到命令调度器。</p>
 *
 * <p>状态查询与 {@code explain} 只访问内存；{@code report} 优先返回最近一次写入的缓存；
 * {@code reload} 由管理员显式触发，会同步重读配置文件。真正的扫描由 {@link ScanManager}
 * 在服务器 tick 中分片推进。所有方法都捕获运行时异常并转换为
 * {@link CommandResult#failure(String)}，因此调用方无需再额外防御。</p>
 */
public final class CommandService {

    private static final String SCAN_UNAVAILABLE = "扫描服务不可用";
    private static final String REPORT_UNAVAILABLE = "报告服务不可用";
    private static final String STORAGE_UNAVAILABLE = "存储服务不可用";
    private static final String DUPLICATE_SCAN = "已有扫描在进行中，请稍后再试";

    private final @Nullable ScanManager scanManager;
    private final @Nullable ReportManager reportManager;
    private final @Nullable GeneratedEntryStore entryStore;
    private final @Nullable ScanStateStore scanStateStore;
    private final @Nullable ReloadAction reloadAction;
    private final @Nullable QuarantineApprovalAction quarantineApprovalAction;

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
        this(scanManager, reportManager, entryStore, scanStateStore, null, null);
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
        this(scanManager, reportManager, entryStore, scanStateStore, reloadAction, null);
    }

    /**
     * @param scanManager             扫描状态机，可为 {@code null}
     * @param reportManager           诊断报告管理器，可为 {@code null}
     * @param entryStore              已生成条目的存储，可为 {@code null}
     * @param scanStateStore          上一次扫描状态的存储，可为 {@code null}
     * @param reloadAction            真实配置重载动作，可为 {@code null}（视为不可用）
     * @param quarantineApprovalAction 隔离降价审批动作，可为 {@code null}（视为不可用）
     */
    public CommandService(@Nullable ScanManager scanManager,
                          @Nullable ReportManager reportManager,
                          @Nullable GeneratedEntryStore entryStore,
                          @Nullable ScanStateStore scanStateStore,
                          @Nullable ReloadAction reloadAction,
                          @Nullable QuarantineApprovalAction quarantineApprovalAction) {
        this.scanManager = scanManager;
        this.reportManager = reportManager;
        this.entryStore = entryStore;
        this.scanStateStore = scanStateStore;
        this.reloadAction = reloadAction;
        this.quarantineApprovalAction = quarantineApprovalAction;
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
            List<String> lines = new ArrayList<>(6);
            lines.add("扫描状态: " + progress.phase().label());
            lines.add("进度    : " + progress.processed() + "/" + progress.total() + " (" + percent + "%)");
            lines.add("运行中  : " + (progress.isRunning() ? "是" : "否"));
            if (!progress.detail().isBlank()) {
                lines.add("详情    : " + progress.detail());
            }
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
     * <p>重载由注入的 {@link ReloadAction} 执行：立即生效、扫描结束后生效以及校验失败
     * 分别对应成功、延后与失败三种回执。缺少重载动作或重载抛出运行时异常时返回中文
     * 失败提示，绝不向命令调度器传播异常，也不再把未执行的动作报告为成功。</p>
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
     * 解释某个物品当前的 EMC 定价。
     *
     * <p>命令参数只包含物品 id，因此这里查找的是无 NBT 变体的普通条目；带变体的条目需要
     * 由后续命令扩展提供变体信息。已生成条目中没有保存备选成本，所以解释中的备选成本固定
     * 为空列表，并在备注中写明“无”。</p>
     *
     * @param itemId 物品 id 原文，例如 {@code minecraft:stone}
     * @return 七行解释文本，或中文失败提示；永不抛出异常
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
            GeneratedEmcEntry entry = entryStore.get(key);
            if (entry == null) {
                return CommandResult.failure("未找到 " + trimmed + " 的定价记录");
            }

            boolean owned = entryStore.isOwned(key);
            String ownership = owned ? "由 SudoersEMC 生成" : "非 SudoersEMC 所有";
            PriceExplanation explanation = new PriceExplanation(
                    entry.item(),
                    entry.value(),
                    entry.source(),
                    entry.recipeId(),
                    owned,
                    ownership,
                    List.of(),
                    "无");
            return CommandResult.ok(splitLines(explanation.formatText()));
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 批准并应用某个物品当前的隔离降价。
     *
     * <p>这是“隔离 → 审批 → 应用/回读”闭环的生产入口；具体动作由模组入口注入。缺少审批动作
     * 或动作抛出异常时返回中文失败提示，绝不向命令调度器传播异常。</p>
     *
     * @param itemId 物品 id 原文，例如 {@code minecraft:stone}
     * @return 审批回执；永不抛出异常
     */
    public CommandResult approveQuarantine(String itemId) {
        try {
            if (quarantineApprovalAction == null) {
                return CommandResult.failure("隔离审批服务不可用");
            }
            if (itemId == null || itemId.isBlank()) {
                return CommandResult.failure("请提供物品 id，例如 minecraft:stone");
            }
            CommandResult result = quarantineApprovalAction.approve(itemId.trim());
            return result == null
                    ? CommandResult.failure("隔离审批失败: NullPointerException")
                    : result;
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
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
