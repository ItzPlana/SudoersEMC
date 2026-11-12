package com.itzplana.sudoersemc;

import com.itzplana.sudoersemc.command.CommandResult;
import com.itzplana.sudoersemc.command.CommandService;
import com.itzplana.sudoersemc.command.SudoemcCommands;
import com.itzplana.sudoersemc.config.ConfigValidationResult;
import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.config.SudoersemcConfig;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.diagnostic.SmokeTestHook;
import com.itzplana.sudoersemc.integration.GeneratedEmcMapper;
import com.itzplana.sudoersemc.integration.OptionalIntegrationManager;
import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.PricingSettings;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * SudoersEMC 模组入口。
 *
 * <p>负责组装各层：配置注册、持久化加载、ProjectE 集成探测、扫描调度器与命令服务。
 * 具体的扫描、求解与定价逻辑分散在各自包中，本类只做接线，不含业务规则。</p>
 */
@Mod(Sudoersemc.MODID)
public final class Sudoersemc {

    public static final String MODID = "sudoersemc";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 单 tick 分配给扫描工作的时间预算，1 毫秒。 */
    private static final long TICK_BUDGET_NANOS = 1_000_000L;

    private static volatile GeneratedEntryStore entryStore;
    private static volatile ScanStateStore scanStateStore;
    private static volatile ScanManager scanManager;
    private static volatile ScanPipeline scanPipeline;
    private static volatile ReportManager reportManager;
    private static volatile OverflowResetLog overflowResetLog;
    private static volatile QuarantinedDropsStore quarantinedDropsStore;
    private static volatile CommandService commandService;
    private static volatile boolean pendingReload;

    public Sudoersemc() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);

        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(SmokeTestHook::onServerTick);

        // 必须在构造阶段注册，Forge 之后才会加载配置。
        SudoersemcConfig.register();

        LOGGER.info("SudoersEMC is loading");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            GeneratedEntryStore store = new GeneratedEntryStore();
            store.load();
            entryStore = store;

            ScanStateStore stateStore = new ScanStateStore();
            stateStore.load();
            scanStateStore = stateStore;

            OverflowResetLog resetLog = new OverflowResetLog();
            overflowResetLog = resetLog;
            QuarantinedDropsStore dropsStore = new QuarantinedDropsStore();
            dropsStore.load();
            quarantinedDropsStore = dropsStore;

            // 落盘文件必须就绪，集成管理器才能创建 provider。
            OptionalIntegrationManager.setSink(store);
            OptionalIntegrationManager.init();

            // GeneratedEmcMapper 实现了 ProjectE 接口，只有确认 ProjectE 存在才能初始化它的类。
            // 这个守卫是「未安装 ProjectE 也能正常启动」的关键。
            if (OptionalIntegrationManager.isProjectEAvailable()) {
                GeneratedEmcMapper.setSource(store);
                GeneratedEmcMapper.setManualValues(OptionalIntegrationManager.manualValues());
                LOGGER.info("SudoersEMC wired its generated-value mapper into ProjectE");
            }

            reportManager = new ReportManager();
            ScanPipeline pipeline = createScanPipeline(store, stateStore, reportManager,
                    resetLog, dropsStore);
            scanPipeline = pipeline;

            scanManager = createScanManagerIfEnabled(pipeline, SudoersemcConfig.GENERAL_ENABLED::get);
            commandService = new CommandService(
                    scanManager,
                    reportManager,
                    store,
                    stateStore,
                    Sudoersemc::reloadConfig,
                    Sudoersemc::approveQuarantined);
            SudoemcCommands.setService(commandService);

            LOGGER.info("SudoersEMC common setup complete ({} generated entries loaded)", store.size());
        });
    }

    /**
     * 创建真实扫描管线。
     *
     * <p>所有安全层与定价策略都从配置构造；任意配置读取失败都会退回该组件的安全默认值，绝不阻止
     * 模组启动。ProjectE 后端通过 {@link OptionalIntegrationManager} 探测，未安装时管线仍然可以
     * 仅依靠配方与兜底定价工作。</p>
     *
     * @param store                生成值存储
     * @param stateStore           扫描状态存储
     * @param reportManager        诊断报告管理器
     * @param resetLog             溢出审计日志；{@code null} 时新建一个仅本次使用的实例
     * @param dropsStore           隔离记录存储；{@code null} 时新建一个仅本次使用的实例
     * @return 可直接交给扫描调度器使用的管线
     */
    private static ScanPipeline createScanPipeline(GeneratedEntryStore store,
                                                   ScanStateStore stateStore,
                                                   ReportManager reportManager,
                                                   @Nullable OverflowResetLog resetLog,
                                                   @Nullable QuarantinedDropsStore dropsStore) {
        OverflowProtection.Settings overflowSettings;
        try {
            overflowSettings = SudoersemcConfig.overflowSettings();
        } catch (RuntimeException e) {
            // 配置尚未加载或跨字段校验失败时，使用自带默认值。
            LOGGER.warn("SudoersEMC 无法读取溢出保护配置，使用默认值", e);
            overflowSettings = new OverflowProtection.Settings();
        }
        OverflowResetLog auditLog = resetLog == null ? new OverflowResetLog() : resetLog;
        OverflowPipeline overflowPipeline = new OverflowPipeline(
                new OverflowProtection(overflowSettings), auditLog);

        LargeDropProtection.Settings dropSettings;
        try {
            dropSettings = new LargeDropProtection.Settings(
                    SudoersemcConfig.SAFETY_LARGE_DROP_PROTECTION.get(),
                    sanitizeMaxAllowedDropRatio(
                            SudoersemcConfig.SAFETY_MAX_ALLOWED_DROP_RATIO.get()),
                    Set.of(),
                    Set.of());
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取大幅降价保护配置，使用默认值", e);
            dropSettings = LargeDropProtection.Settings.defaults();
        }
        LargeDropProtection dropProtection = new LargeDropProtection(dropSettings, dropsStore);

        CreativeItemProtection creativeProtection = createCreativeProtection();

        PricingSettings pricingSettings;
        try {
            pricingSettings = new PricingSettings(
                    SudoersemcConfig.FALLBACK_USE_TAG_PRICING.get(),
                    SudoersemcConfig.FALLBACK_USE_RARITY_PRICING.get(),
                    true,
                    SudoersemcConfig.FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE.get(),
                    PricingSettings.DEFAULT_GENERIC_FALLBACK_VALUE);
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取兜底定价配置，使用默认值", e);
            pricingSettings = PricingSettings.defaults();
        }
        FallbackPricing fallbackPricing = FallbackPricing.withDefaults(pricingSettings);

        SolveSettings solveSettings;
        try {
            solveSettings = new SolveSettings(
                    Math.max(1, SudoersemcConfig.RECIPE_MAX_RECIPE_PASSES.get()),
                    SudoersemcConfig.RECIPE_ALLOW_RECIPE_VALUE_INCREASE.get(),
                    SolveSettings.DEFAULT_MINIMUM_EMC,
                    SolveSettings.DEFAULT_MAX_ALTERNATIVE_COSTS);
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取配方求解配置，使用默认值", e);
            solveSettings = SolveSettings.defaults();
        }

        ScanPipeline pipeline = new ScanPipeline(
                store,
                stateStore,
                reportManager,
                dropProtection,
                creativeProtection,
                overflowPipeline,
                fallbackPricing,
                solveSettings);
        pipeline.setEmcProvider(OptionalIntegrationManager.provider().orElse(null));
        pipeline.setInputFingerprintSupplier(Sudoersemc::currentScanFingerprint);
        applyPipelineConfig(pipeline);
        return pipeline;
    }

    /**
     * 把管线相关配置开关应用到刚构造的实例。
     *
     * <p>任一开关读取失败都只记录警告，不阻止启动；管线保留自身安全默认值。</p>
     */
    private static void applyPipelineConfig(ScanPipeline pipeline) {
        try {
            pipeline.setSolveRecipesEnabled(SudoersemcConfig.RECIPE_SOLVE_RECIPES.get());
            pipeline.setGenerateReportsEnabled(SudoersemcConfig.DIAGNOSTIC_GENERATE_REPORTS.get());
            pipeline.setUseProjectEIntegrationBaselines(
                    SudoersemcConfig.FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES.get());
            pipeline.setOverflowScope(SudoersemcConfig.parseOverflowScope(
                    SudoersemcConfig.OVERFLOW_OVERWRITE_SCOPE.get()));
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取管线配置开关，使用管线默认值", e);
        }
    }

    /**
     * 创建创造物品保护。
     *
     * <p>{@code safety.creativeItemExclusions} / {@code creativeItemSpecialValues} /
     * {@code creativeNamespaces} 三个列表键由 {@link CreativeItemProtection#parseSettings}
     * 容错解析：非法条目会被跳过并汇总为一条警告，不会阻止模组启动。</p>
     */
    private static CreativeItemProtection createCreativeProtection() {
        try {
            boolean enabled = SudoersemcConfig.SAFETY_PROTECT_CREATIVE_ONLY_ITEMS.get();
            CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                    enabled,
                    List.copyOf(SudoersemcConfig.SAFETY_CREATIVE_ITEM_EXCLUSIONS.get()),
                    List.copyOf(SudoersemcConfig.SAFETY_CREATIVE_ITEM_SPECIAL_VALUES.get()),
                    List.copyOf(SudoersemcConfig.SAFETY_CREATIVE_NAMESPACES.get()));
            if (!parsed.skippedEntries().isEmpty()) {
                LOGGER.warn("SudoersEMC 创造物品保护配置有 {} 条无效条目已被忽略：{}",
                        parsed.skippedEntries().size(), parsed.skippedEntries());
            }
            return new CreativeItemProtection(parsed.settings());
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取创造物品保护配置，默认启用保护", e);
            return new CreativeItemProtection(new CreativeItemProtection.Settings(
                    true, Set.of(), Map.of(), Set.of()));
        }
    }

    /**
     * 把配置中的最大允许降幅夹到 {@link LargeDropProtection.Settings} 接受的 {@code (0.0, 1.0]} 区间。
     *
     * <p>配置边界已经拒绝 0.0，但旧配置文件、手工编辑或未来迁移仍可能绕过边界检查。这里再做一次
     * 防御：任何非有限、非正或大于 1 的值都按 {@link Double#MIN_VALUE} 处理，语义是“任何正降幅
     * 都隔离”。绝不静默退回默认的 0.5，避免管理员以为关闭了降价却被允许 50% 降价。</p>
     *
     * @param configured 配置读取到的最大允许降幅
     * @return 位于 {@code (0.0, 1.0]} 的可用值
     */
    static double sanitizeMaxAllowedDropRatio(double configured) {
        if (!Double.isFinite(configured) || configured <= 0.0D || configured > 1.0D) {
            return Double.MIN_VALUE;
        }
        return configured;
    }

    /**
     * 创建扫描调度器并接入真实管线。
     *
     * <p>接线采用“采集暂存、应用取出”的方式：采集回调在服务器线程完成注册表与配方读取后把
     * {@link PipelineSnapshot} 暂存到管线中，应用回调再取出求解并写入存储。这样既满足
     * {@link ScanManager} 跨 tick 的阶段划分，也避免闭包数组带来的隐式状态。</p>
     *
     * @param pipeline 真实扫描管线
     * @return 已接线的扫描调度器
     */
    private static ScanManager createScanManager(ScanPipeline pipeline) {
        int itemsPerTick;
        boolean adaptive;
        try {
            itemsPerTick = SudoersemcConfig.PERFORMANCE_ITEMS_PER_TICK.get();
            adaptive = SudoersemcConfig.PERFORMANCE_ADAPTIVE_BATCH_SIZING.get();
        } catch (RuntimeException e) {
            // 配置尚未加载时退回保守默认值，绝不因为读取配置失败而阻止模组启动。
            LOGGER.warn("SudoersEMC 无法读取性能配置，使用默认值 100 / 自适应开启", e);
            itemsPerTick = 100;
            adaptive = true;
        }
        BatchProcessor processor = new BatchProcessor(Math.max(1, itemsPerTick), adaptive);
        PipelineApplier applier = new PipelineApplier(pipeline);
        return new ScanManager(
                processor,
                () -> {
                    if (pipeline == null) {
                        throw new IllegalStateException("扫描采集失败：扫描管线未初始化");
                    }
                    MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                    if (server == null) {
                        LOGGER.warn("SudoersEMC 扫描请求到达时服务器尚未就绪，本次扫描失败");
                        throw new IllegalStateException("扫描采集失败：服务器尚未就绪");
                    }
                    return pipeline.beginCollection(server);
                },
                applier,
                TICK_BUDGET_NANOS,
                Sudoersemc::currentScanFingerprint);
    }

    /**
     * 应用阶段协调器：在服务器线程启动异步求解、轮询完成，然后重新回到服务器线程做重映射与落盘。
     *
     * <p>求解本身只读取不可变快照与定价规则，不接触注册表、RecipeManager、ProjectE API 或文件
     * 系统；{@link ScanPipeline#applyCompletedSolve()} 负责安全判定、存储写入与报告落盘。</p>
     */
    static final class PipelineApplier implements ScanManager.ResultApplier {

        @Nullable
        private final ScanPipeline pipeline;

        private boolean started;
        private boolean finished;

        PipelineApplier(@Nullable ScanPipeline pipeline) {
            this.pipeline = pipeline;
        }

        @Override
        public void apply(ScanManager.SnapshotData data) {
            if (finished) {
                return;
            }
            if (pipeline == null) {
                throw new IllegalStateException("扫描应用失败：扫描管线未初始化");
            }
            if (!started) {
                started = true;
                if (!pipeline.startStashedSolve()) {
                    throw new IllegalStateException("扫描应用失败：没有可用的暂存快照");
                }
                return;
            }
            if (!pipeline.isSolveComplete()) {
                return;
            }
            pipeline.applyCompletedSolve();
            flushRuntimeStoresAndRemap();
            finished = true;
        }

        @Override
        public boolean isComplete(ScanManager.SnapshotData data) {
            return finished;
        }

        @Override
        public boolean usesAsyncSolve() {
            return true;
        }

        @Override
        public boolean isReadyToApply(ScanManager.SnapshotData data) {
            return pipeline != null && pipeline.isSolveComplete();
        }

        @Override
        public void reset() {
            if (pipeline != null) {
                pipeline.cancelPendingSolve();
            }
            started = false;
            finished = false;
        }
    }

    /**
     * 请求 ProjectE 重映射并刷新所有运行时存储。必须在服务器线程调用。
     */
    private static void flushRuntimeStoresAndRemap() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            try {
                OptionalIntegrationManager.provider()
                        .ifPresent(provider -> provider.requestRemap(server));
            } catch (RuntimeException e) {
                LOGGER.warn("SudoersEMC 请求 ProjectE 重映射失败，已生成条目仍会保存", e);
            }
        }
        flushRuntimeStoresOnServerThread();
    }

    /**
     * 在服务器线程刷新所有运行时存储。
     *
     * <p>存储自身保证失败时不抛出、保留脏标记等待下次重试；这里仍然按组件逐个隔离，避免
     * 一个存储的意外问题阻止其它存储落盘。</p>
     */
    private static void flushRuntimeStoresOnServerThread() {
        GeneratedEntryStore store = entryStore;
        if (store != null) {
            store.saveIfDirty();
        }
        ScanStateStore stateStore = scanStateStore;
        if (stateStore != null) {
            stateStore.saveIfDirty();
        }
        OverflowResetLog resetLog = overflowResetLog;
        if (resetLog != null) {
            resetLog.save();
        }
        QuarantinedDropsStore dropsStore = quarantinedDropsStore;
        if (dropsStore != null) {
            dropsStore.saveIfDirty();
        }
    }

    /**
     * 批准并应用某个物品的隔离降价。
     *
     * <p>生产闭环：读取 {@link QuarantinedDropsStore} 中的持久化记录 → 设置 {@code approved}
     * → 调用 {@link ScanPipeline#approveQuarantined(ItemKey)} 清理内存隔离列表 → 把记录中的
     * {@code proposedValue} 写入 {@link GeneratedEntryStore} → 落盘并请求 ProjectE 重映射。
     * 因此 {@code QuarantinedDropsStore.approve} 与 {@code LargeDropProtection.approve} 都有
     * 生产调用方；即使服务器重启后内存隔离列表为空，也能根据磁盘记录完成审批。</p>
     *
     * @param itemId 物品 id 原文
     * @return 命令回执
     */
    private static CommandResult approveQuarantined(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return CommandResult.failure("请提供物品 id，例如 minecraft:stone");
        }
        ResourceLocation itemIdValue = ResourceLocation.tryParse(itemId.trim());
        if (itemIdValue == null) {
            return CommandResult.failure("无效的物品 id: " + itemId);
        }
        ItemKey key = ItemKey.of(itemIdValue);

        QuarantinedDropsStore dropsStore = quarantinedDropsStore;
        if (dropsStore == null) {
            return CommandResult.failure("隔离记录服务不可用");
        }
        QuarantinedDropsStore.QuarantinedDrop drop = null;
        for (QuarantinedDropsStore.QuarantinedDrop candidate : dropsStore.entries()) {
            if (candidate.key().equals(key)) {
                drop = candidate;
                break;
            }
        }
        if (drop == null) {
            return CommandResult.failure("未找到 " + itemIdValue + " 的隔离记录");
        }

        GeneratedEntryStore store = entryStore;
        if (store != null) {
            GeneratedEmcEntry existing = store.get(key);
            if (existing != null && !existing.owned()) {
                return CommandResult.failure(
                        "无法应用 " + itemIdValue + "：现有条目不属于 SudoersEMC");
            }
        }
        if (!dropsStore.approve(key)) {
            return CommandResult.failure("批准隔离记录失败: " + itemIdValue);
        }

        ScanPipeline pipeline = scanPipeline;
        int removedFromMemory = pipeline == null ? 0 : pipeline.approveQuarantined(key).size();

        if (store == null) {
            dropsStore.saveIfDirty();
            return CommandResult.ok(
                    "已批准 " + itemIdValue + " 的隔离记录",
                    "生成值存储不可用，本次未应用数值");
        }
        boolean staged = store.stage(new GeneratedEmcEntry(
                key,
                drop.proposedValue(),
                PriceSource.APPROVED,
                null,
                true,
                System.currentTimeMillis()));
        if (!staged) {
            dropsStore.saveIfDirty();
            return CommandResult.failure("无法写入已批准的数值: " + itemIdValue);
        }

        flushRuntimeStoresAndRemap();
        return CommandResult.ok(
                "已批准 " + itemIdValue + " 的隔离降价",
                "旧值: " + drop.previousValue() + " -> 新值: " + drop.proposedValue(),
                "已从内存隔离列表移除 " + removedFromMemory + " 条记录");
    }

    /**
     * 按 {@code [general] enabled} 决定是否创建扫描调度器。
     *
     * <p>配置尚未加载或读取失败时沿用与 {@link #createScanManager(ScanPipeline)} 相同的容错策略：
     * 记录警告并按启用处理，绝不因为读取开关失败而让模组失去扫描能力。</p>
     *
     * @param enabledSupplier 开关读取器；测试可注入固定值
     * @return 启用时返回新的调度器，关闭时返回 {@code null}
     */
    static ScanManager createScanManagerIfEnabled(BooleanSupplier enabledSupplier) {
        return createScanManagerIfEnabled(null, enabledSupplier);
    }

    /**
     * 按 {@code [general] enabled} 决定是否用给定管线创建扫描调度器。
     *
     * @param pipeline        真实扫描管线，可为 {@code null}（测试用调度器）
     * @param enabledSupplier 开关读取器；测试可注入固定值
     * @return 启用时返回新的调度器，关闭时返回 {@code null}
     */
    static ScanManager createScanManagerIfEnabled(ScanPipeline pipeline, BooleanSupplier enabledSupplier) {
        boolean enabled = true;
        try {
            enabled = enabledSupplier.getAsBoolean();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取 [general] enabled，按启用处理", e);
        }
        if (!enabled) {
            LOGGER.info("SudoersEMC [general] enabled = false：不创建扫描调度器，自动扫描已关闭");
            return null;
        }
        return createScanManager(pipeline);
    }

    /**
     * 按 {@code [general] alwaysRescan} 决定服务器启动时是否请求一次全量扫描。
     *
     * <p>读取失败时保守地按“不重扫”处理，避免配置异常导致意外的全量扫描。</p>
     *
     * @param alwaysRescanSupplier 开关读取器；测试可注入固定值
     * @return 需要请求启动扫描时返回 {@code true}
     */
    static boolean shouldRequestScanOnStart(BooleanSupplier alwaysRescanSupplier) {
        try {
            return alwaysRescanSupplier.getAsBoolean();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取 [general] alwaysRescan，按不重扫处理", e);
            return false;
        }
    }

    /**
     * 停服时清理扫描调度器。未完成的扫描会被重置为空闲，避免下一个世界继续 tick
     * 旧快照或旧回调。
     *
     * @param manager 当前调度器，可为 {@code null}
     */
    static void resetScanManager(ScanManager manager) {
        if (manager != null) {
            manager.reset();
        }
    }

    /**
     * 服务器启动后按 {@code [general] alwaysRescan} 与缓存状态请求扫描。
     *
     * <p>判定顺序：{@code alwaysRescan=true} 时无条件重扫；否则上次扫描时刻或已生成条目
     * 任一缺失时仍然请求扫描；两者都存在时视为已有可用缓存并跳过，避免每次启动重复全量
     * 求解。只有扫描调度器存在（即 {@code [general] enabled = true}）时才会真正提交请求。</p>
     */
    private void requestStartupScanIfConfigured() {
        boolean alwaysRescan = shouldRequestScanOnStart(SudoersemcConfig.GENERAL_ALWAYS_RESCAN::get);
        ScanManager manager = scanManager;
        if (manager == null) {
            if (alwaysRescan) {
                LOGGER.info("SudoersEMC [general] alwaysRescan = true，但扫描调度器未启用，跳过启动扫描");
            }
            return;
        }

        ScanStateStore stateStore = scanStateStore;
        GeneratedEntryStore store = entryStore;
        long lastScanEpochMillis = stateStore == null ? 0L : stateStore.lastScanEpochMillis();
        int generatedEntryCount = store == null ? 0 : store.size();
        ScanFingerprint currentFingerprint;
        try {
            currentFingerprint = currentScanFingerprint();
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 无法计算当前扫描输入指纹，启动扫描将保守执行", failure);
            currentFingerprint = null;
        }
        if (!alwaysRescan && hasReusableScanCache(stateStore, store, currentFingerprint)) {
            LOGGER.info("SudoersEMC 已有可用扫描缓存（上次扫描={}，已生成条目={}，指纹匹配），跳过启动扫描",
                    lastScanEpochMillis, generatedEntryCount);
            return;
        }

        if (manager.requestScan()) {
            LOGGER.info("SudoersEMC 已请求启动扫描（alwaysRescan={}，上次扫描={}，已生成条目={}）",
                    alwaysRescan, lastScanEpochMillis, generatedEntryCount);
        } else {
            LOGGER.info("SudoersEMC 已有扫描在进行中，跳过启动扫描");
        }
    }

    /**
     * 判断启动缓存是否可以复用。
     *
     * <p>只有“已记录成功扫描时间 + 存在生成条目 + 当前完整输入指纹与持久化指纹一致”三个条件
     * 同时满足时才允许跳过启动扫描。指纹覆盖物品、配方、标签与影响定价的配置通道，因此模组、
     * 配方或配置变化后不会误用旧缓存。</p>
     *
     * @param stateStore 扫描状态存储，可为 {@code null}
     * @param store      生成值存储，可为 {@code null}
     * @param fingerprint 当前输入指纹，无法计算时为 {@code null}
     * @return 可以复用缓存时返回 {@code true}
     */
    static boolean hasReusableScanCache(@Nullable ScanStateStore stateStore,
                                        @Nullable GeneratedEntryStore store,
                                        @Nullable ScanFingerprint fingerprint) {
        if (stateStore == null || store == null || fingerprint == null) {
            return false;
        }
        if (store.size() <= 0 || stateStore.lastScanEpochMillis() <= 0L) {
            return false;
        }
        return stateStore.matches(fingerprint);
    }

    /**
     * 计算当前服务器世界可观察到的完整扫描输入指纹。
     *
     * <p>必须在服务器线程调用。物品 id、配方 id、物品标签与影响定价的配置值共同组成指纹；
     * 任何一个通道变化都会让启动缓存与采集一致性校验失败，从而触发重新扫描或拒绝过期结果。
     * 服务器未就绪时抛出异常，由调用方选择保守路径。</p>
     *
     * @return 当前输入指纹
     * @throws IllegalStateException 服务器或配方管理器尚未就绪时抛出
     */
    static ScanFingerprint currentScanFingerprint() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("服务器尚未就绪，无法计算扫描指纹");
        }
        if (server.getRecipeManager() == null) {
            throw new IllegalStateException("配方管理器尚未就绪，无法计算扫描指纹");
        }
        return captureScanFingerprint(server);
    }

    /**
     * 从给定服务器实例捕获输入指纹。物品与标签来自注册表，配方来自当前 RecipeManager。
     */
    static ScanFingerprint captureScanFingerprint(@Nullable MinecraftServer server) {
        List<String> itemIds = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            itemIds.add(id.toString());
        }

        List<String> recipeIds = new ArrayList<>();
        if (server != null && server.getRecipeManager() != null) {
            for (ResourceLocation id : server.getRecipeManager().getRecipeIds().toList()) {
                recipeIds.add(id.toString());
            }
        }

        List<String> tagIds = new ArrayList<>();
        for (TagKey<Item> tag : BuiltInRegistries.ITEM.getTagNames().toList()) {
            tagIds.add(tag.location().toString());
        }

        return ScanFingerprint.of(itemIds, recipeIds, tagIds, configFingerprintValues(),
                List.of("phase-12"));
    }

    /**
     * 收集影响定价结果的配置值。读取失败时返回固定降级标记，绝不把空配置误判为“未变化”。
     */
    private static List<String> configFingerprintValues() {
        List<String> values = new ArrayList<>();
        try {
            values.add("general.enabled=" + SudoersemcConfig.GENERAL_ENABLED.get());
            values.add("general.alwaysRescan=" + SudoersemcConfig.GENERAL_ALWAYS_RESCAN.get());
            values.add("recipe.solveRecipes=" + SudoersemcConfig.RECIPE_SOLVE_RECIPES.get());
            values.add("recipe.maxPasses=" + SudoersemcConfig.RECIPE_MAX_RECIPE_PASSES.get());
            values.add("recipe.allowIncrease=" + SudoersemcConfig.RECIPE_ALLOW_RECIPE_VALUE_INCREASE.get());
            values.add("fallback.unsolvable=" + SudoersemcConfig.FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE.get());
            values.add("fallback.tag=" + SudoersemcConfig.FALLBACK_USE_TAG_PRICING.get());
            values.add("fallback.rarity=" + SudoersemcConfig.FALLBACK_USE_RARITY_PRICING.get());
            values.add("fallback.baselines=" + SudoersemcConfig.FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES.get());
            values.add("safety.largeDrop=" + SudoersemcConfig.SAFETY_LARGE_DROP_PROTECTION.get());
            values.add("safety.maxDropRatio=" + SudoersemcConfig.SAFETY_MAX_ALLOWED_DROP_RATIO.get());
            values.add("safety.creative=" + SudoersemcConfig.SAFETY_PROTECT_CREATIVE_ONLY_ITEMS.get());
            values.add("safety.creativeExclusions=" + SudoersemcConfig.SAFETY_CREATIVE_ITEM_EXCLUSIONS.get());
            values.add("safety.creativeSpecialValues="
                    + SudoersemcConfig.SAFETY_CREATIVE_ITEM_SPECIAL_VALUES.get());
            values.add("safety.creativeNamespaces=" + SudoersemcConfig.SAFETY_CREATIVE_NAMESPACES.get());
            values.add("overflow.enabled=" + SudoersemcConfig.OVERFLOW_ENABLED.get());
            values.add("overflow.threshold=" + SudoersemcConfig.OVERFLOW_THRESHOLD.get());
            values.add("overflow.resetMin=" + SudoersemcConfig.OVERFLOW_RESET_MIN.get());
            values.add("overflow.resetMax=" + SudoersemcConfig.OVERFLOW_RESET_MAX.get());
            values.add("overflow.scope=" + SudoersemcConfig.OVERFLOW_OVERWRITE_SCOPE.get());
            values.add("nbt.variants=" + SudoersemcConfig.NBT_SCAN_NBT_VARIANTS.get());
            values.add("nbt.maxVariants=" + SudoersemcConfig.NBT_MAX_NBT_VARIANTS_PER_ITEM.get());
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 读取配置指纹失败，使用降级标记", failure);
            values.add("config-unavailable");
        }
        return List.copyOf(values);
    }

    /**
     * 处理 {@code /sudoemc reload}：重读配置文件并重建受配置影响的运行时组件。
     *
     * <p>扫描进行中时不读取文件，只登记延迟重载；扫描结束后由服务器 tick 调用
     * {@link #applyConfigReload()}。这样可以避免在扫描中途替换 {@link ScanManager}
     * 导致进度或取消标志丢失。</p>
     *
     * @return 命令层用于生成回执的处理决策
     */
    private static ReloadDecision reloadConfig() {
        ScanManager manager = scanManager;
        if (manager != null && manager.isRunning()) {
            pendingReload = true;
            LOGGER.info("SudoersEMC 扫描进行中，配置重载已延后到扫描结束");
            return ReloadDecision.DEFER;
        }
        return applyConfigReload();
    }

    /**
     * 从磁盘重读配置，校验通过后重建扫描管线、调度器与命令服务。
     *
     * <p>配置读取或跨字段校验失败时不会替换任何运行时组件，返回
     * {@link ReloadDecision#REJECT}；成功时使用新配置重建 {@link ScanPipeline}、{@link ScanManager}
     * 并重新注入 {@link CommandService}，同时清理旧调度器，避免残留取消标志。</p>
     */
    private static synchronized ReloadDecision applyConfigReload() {
        pendingReload = false;
        ConfigValidationResult validation = SudoersemcConfig.reloadFromDisk();
        if (!validation.valid()) {
            LOGGER.warn("SudoersEMC 配置重载被拒绝，保留上一份有效配置：\n{}", validation.format());
            return ReloadDecision.REJECT;
        }

        try {
            ScanPipeline rebuiltPipeline = createScanPipeline(
                    entryStore, scanStateStore, reportManager, overflowResetLog, quarantinedDropsStore);
            ScanManager rebuilt = createScanManagerIfEnabled(
                    rebuiltPipeline, SudoersemcConfig.GENERAL_ENABLED::get);
            CommandService rebuiltService = new CommandService(
                    rebuilt,
                    reportManager,
                    entryStore,
                    scanStateStore,
                    Sudoersemc::reloadConfig,
                    Sudoersemc::approveQuarantined);
            ScanManager previous = scanManager;
            scanPipeline = rebuiltPipeline;
            scanManager = rebuilt;
            commandService = rebuiltService;
            SudoemcCommands.setService(rebuiltService);
            if (previous != null) {
                previous.cancel();
                previous.reset();
            }
            LOGGER.info("SudoersEMC 配置已重新加载并生效");
            return ReloadDecision.APPLY_NOW;
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 应用新配置失败，保留旧运行时组件", failure);
            return ReloadDecision.REJECT;
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(SudoemcCommands.register());
        LOGGER.debug("SudoersEMC registered /sudoemc command tree");
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ScanManager manager = scanManager;
        if (manager != null && manager.isRunning()) {
            manager.tick();
        }
        if (pendingReload) {
            ScanManager current = scanManager;
            if (current == null || !current.isRunning()) {
                applyConfigReload();
            }
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        ScanManager manager = scanManager;
        if (manager != null) {
            manager.reset();
        }
        // 集成服务器停止后会清空命令服务；新世界启动时必须重新注入。
        SudoemcCommands.setService(commandService);

        GeneratedEntryStore store = entryStore;
        if (store != null) {
            LOGGER.info("SudoersEMC active: {} generated entries, ProjectE {}",
                    store.size(),
                    OptionalIntegrationManager.isProjectEAvailable() ? "detected" : "absent");
        }
        SmokeTestHook.onServerStarted(event);
        requestStartupScanIfConfigured();
    }

    private void onServerStopping(ServerStoppingEvent event) {
        resetScanManager(scanManager);
        ScanManager manager = scanManager;
        if (manager != null) {
            manager.cancel();
            manager.reset();
        }
        flushRuntimeStoresOnServerThread();
        pendingReload = false;
        // 不保留指向旧世界的命令服务；下一次 ServerStartedEvent 会重新注入。
        SudoemcCommands.setService(null);
    }

    /**
     * @return 当前生效的生成条目存储，common setup 完成前为 {@code null}
     */
    public static GeneratedEntryStore entryStore() {
        return entryStore;
    }

    /**
     * @return 扫描状态存储，common setup 完成前为 {@code null}
     */
    public static ScanStateStore scanStateStore() {
        return scanStateStore;
    }

    /**
     * @return 扫描调度器，common setup 完成前为 {@code null}
     */
    public static ScanManager scanManager() {
        return scanManager;
    }

    /**
     * @return 真实扫描管线，common setup 完成前为 {@code null}
     */
    public static ScanPipeline scanPipeline() {
        return scanPipeline;
    }
}
