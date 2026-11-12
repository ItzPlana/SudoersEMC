package com.itzplana.sudoersemc.command;

import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.diagnostic.PriceExplanation;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.OptionalIntegrationManager;
import com.itzplana.sudoersemc.integration.ProjectEManualValues;
import com.itzplana.sudoersemc.safety.ApprovedDropApplier;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanProgress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/command/CommandService");

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
    private final boolean approveAppliesImmediately;
    private final @Nullable Supplier<Predicate<ItemKey>> pricingExclusionSupplier;
    private final @Nullable LongSupplier minimumEmcSupplier;

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
     * 兼容构造器：默认启用“批准后立即应用”，排除检测不可用（视为不排除），最小 EMC 为 1。
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
        this(scanManager, reportManager, entryStore, scanStateStore, reloadAction,
                quarantinedDropsStore, emcProvider, manualValues, true, null, null);
    }

    /**
     * 完整构造器。
     *
     * @param scanManager               扫描状态机，可为 {@code null}
     * @param reportManager             诊断报告管理器，可为 {@code null}
     * @param entryStore                已生成条目的存储，可为 {@code null}
     * @param scanStateStore            上一次扫描状态的存储，可为 {@code null}
     * @param reloadAction              真实配置重载动作，可为 {@code null}
     * @param quarantinedDropsStore     隔离记录存储，可为 {@code null}
     * @param emcProvider               ProjectE（或其它后端）的 EMC 查询接口，可为 {@code null}
     * @param manualValues              ProjectE 手动值视图，可为 {@code null}
     * @param approveAppliesImmediately 配置开关：批准后是否立即写入降价值
     * @param pricingExclusionSupplier  定价排除判定器提供者；{@code null} 视为不排除
     * @param minimumEmcSupplier        允许应用的最小 EMC 提供者；{@code null} 使用 1
     */
    public CommandService(@Nullable ScanManager scanManager,
                          @Nullable ReportManager reportManager,
                          @Nullable GeneratedEntryStore entryStore,
                          @Nullable ScanStateStore scanStateStore,
                          @Nullable ReloadAction reloadAction,
                          @Nullable QuarantinedDropsStore quarantinedDropsStore,
                          @Nullable EmcProvider emcProvider,
                          @Nullable ProjectEManualValues manualValues,
                          boolean approveAppliesImmediately,
                          @Nullable Supplier<Predicate<ItemKey>> pricingExclusionSupplier,
                          @Nullable LongSupplier minimumEmcSupplier) {
        this.scanManager = scanManager;
        this.reportManager = reportManager;
        this.entryStore = entryStore;
        this.scanStateStore = scanStateStore;
        this.reloadAction = reloadAction;
        this.quarantinedDropsStore = quarantinedDropsStore;
        this.emcProvider = emcProvider;
        this.manualValues = manualValues;
        this.approveAppliesImmediately = approveAppliesImmediately;
        this.pricingExclusionSupplier = pricingExclusionSupplier;
        this.minimumEmcSupplier = minimumEmcSupplier;
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
     * 批准某个物品当前被隔离的降价记录，并在配置允许时立即应用降价值。
     *
     * <p>审批会先经过 {@link ApprovedDropApplier} 的全部安全规则：已批准记录不重复应用、
     * ProjectE 手动值 fail-closed 拒绝、定价排除项拒绝、低于最小 EMC 拒绝。应用成功后写入
     * {@link GeneratedEntryStore}（来源 {@code APPROVED}）并请求 ProjectE 重映射；本方法
     * 绝不直接改写 ProjectE 的 {@code custom_emc.json}。参数必须是普通物品 id；带 NBT 的
     * 变体暂不支持。</p>
     *
     * @param itemId 物品 id 原文，例如 {@code minecraft:stone}
     * @return 明确的中文结果：已应用、已批准过、拒绝原因、无此记录或 id 非法；永不抛出异常
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
            QuarantinedDropsStore store = quarantinedDropsStore;
            if (store == null) {
                return CommandResult.failure(QUARANTINE_UNAVAILABLE);
            }

            ItemKey key = ItemKey.of(parsed);
            Optional<QuarantinedDropsStore.QuarantinedDrop> found = store.find(key);
            if (found.isEmpty()) {
                return CommandResult.failure("未找到 " + trimmed + " 的隔离降价记录");
            }
            QuarantinedDropsStore.QuarantinedDrop drop = found.get();
            if (drop.approved()) {
                return CommandResult.ok(
                        "已批准过 " + trimmed + " 的隔离降价记录（未重复应用）");
            }

            ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                    drop,
                    entryStore,
                    approveAppliesImmediately,
                    resolveMinimumEmc(),
                    manualDetector(),
                    resolveExclusionDetector());

            if (result.status() == ApprovedDropApplier.Status.APPLIED) {
                if (!store.markApproved(key)) {
                    return CommandResult.failure("隔离记录在应用过程中消失，未标记批准");
                }
                store.saveIfDirty();
                if (entryStore != null) {
                    entryStore.saveIfDirty();
                }
                boolean remapRequested = requestProjectERemap();
                String appliedMessage = result.message() == null
                        ? "已应用 " + drop.proposedValue() + "（来源 APPROVED）"
                        : result.message();
                return CommandResult.ok(appliedMessage + (remapRequested
                        ? "，已请求 ProjectE 重映射"
                        : "，ProjectE 不可用，将在下次启动注入"));
            }

            if (result.status() == ApprovedDropApplier.Status.ALREADY_APPLIED) {
                return CommandResult.ok(
                        "已批准过 " + trimmed + " 的隔离降价记录（未重复应用）");
            }

            String reason = result.message() == null
                    ? result.status().name()
                    : result.message();
            return CommandResult.failure(reason);
        } catch (RuntimeException e) {
            return unexpectedFailure(e);
        }
    }

    /**
     * 构造 ProjectE 手动值判定器；没有手动值视图时返回 {@code null}，由应用器 fail-closed。
     */
    private @Nullable Predicate<ItemKey> manualDetector() {
        ProjectEManualValues values = manualValues;
        return values == null ? null : values::isManuallySet;
    }

    /**
     * 解析定价排除判定器。提供者缺失时返回 {@code null}（视为不排除）；提供者抛异常时
     * 返回恒真判定器，宁可拒绝应用也不写入可能被下一次扫描清理的值。
     */
    private @Nullable Predicate<ItemKey> resolveExclusionDetector() {
        Supplier<Predicate<ItemKey>> supplier = pricingExclusionSupplier;
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("读取定价排除判定器失败，按全部排除处理", failure);
            return key -> true;
        }
    }

    /**
     * 读取允许应用的最小 EMC；缺失或读取失败时回退到 1。
     */
    private long resolveMinimumEmc() {
        LongSupplier supplier = minimumEmcSupplier;
        if (supplier == null) {
            return 1L;
        }
        try {
            return Math.max(0L, supplier.getAsLong());
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("读取最小 EMC 配置失败，使用默认值 1", failure);
            return 1L;
        }
    }

    /**
     * 请求 ProjectE 重映射。失败只记录日志，不影响已经落盘的生成条目。
     *
     * @return 是否真正向可用的 ProjectE 后端发出了重映射请求
     */
    private static boolean requestProjectERemap() {
        try {
            Optional<EmcProvider> available = OptionalIntegrationManager.provider()
                    .filter(EmcProvider::isAvailable);
            if (available.isEmpty()) {
                return false;
            }
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                return false;
            }
            available.get().requestRemap(server);
            return true;
        } catch (RuntimeException | LinkageError failure) {
            LOGGER.warn("请求 ProjectE 重映射失败；已应用的值仍保存在生成条目存储中", failure);
            return false;
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
