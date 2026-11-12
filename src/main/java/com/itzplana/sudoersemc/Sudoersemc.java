package com.itzplana.sudoersemc;

import com.itzplana.sudoersemc.command.CommandService;
import com.itzplana.sudoersemc.command.SudoemcCommands;
import com.itzplana.sudoersemc.config.ConfigValidationResult;
import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.config.SudoersemcConfig;
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
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.AutoScanPolicy;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanManager;
import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
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
    private static volatile OverflowResetLog overflowResetLog;
    private static volatile ReportManager reportManager;
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
            OverflowResetLog overflowLog = new OverflowResetLog();
            overflowResetLog = overflowLog;

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
            ScanPipeline pipeline = createScanPipeline(store, stateStore, reportManager, overflowLog);
            scanPipeline = pipeline;

            scanManager = createScanManagerIfEnabled(pipeline, SudoersemcConfig.GENERAL_ENABLED::get);
            commandService = new CommandService(
                    scanManager,
                    reportManager,
                    store,
                    stateStore,
                    Sudoersemc::reloadConfig);
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
     * @param store         生成值存储
     * @param stateStore    扫描状态存储
     * @param reportManager 诊断报告管理器
     * @param overflowLog   溢出审计日志；{@code null} 时新建一个仅本次使用的实例
     * @return 可直接交给扫描调度器使用的管线
     */
    private static ScanPipeline createScanPipeline(GeneratedEntryStore store,
                                                   ScanStateStore stateStore,
                                                   ReportManager reportManager,
                                                   @Nullable OverflowResetLog overflowLog) {
        OverflowProtection.Settings overflowSettings;
        try {
            overflowSettings = SudoersemcConfig.overflowSettings();
        } catch (RuntimeException e) {
            // 配置尚未加载或跨字段校验失败时，使用自带默认值。
            LOGGER.warn("SudoersEMC 无法读取溢出保护配置，使用默认值", e);
            overflowSettings = new OverflowProtection.Settings();
        }
        OverflowResetLog auditLog = overflowLog == null ? new OverflowResetLog() : overflowLog;
        OverflowPipeline overflowPipeline = new OverflowPipeline(
                new OverflowProtection(overflowSettings),
                auditLog);

        LargeDropProtection.Settings dropSettings;
        try {
            dropSettings = new LargeDropProtection.Settings(
                    SudoersemcConfig.SAFETY_LARGE_DROP_PROTECTION.get(),
                    SudoersemcConfig.SAFETY_MAX_ALLOWED_DROP_RATIO.get(),
                    Set.of(),
                    Set.of());
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取大幅降价保护配置，使用默认值", e);
            dropSettings = LargeDropProtection.Settings.defaults();
        }
        LargeDropProtection dropProtection = new LargeDropProtection(dropSettings);

        boolean protectCreativeOnly;
        try {
            protectCreativeOnly = SudoersemcConfig.SAFETY_PROTECT_CREATIVE_ONLY_ITEMS.get();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取创造物品保护配置，默认启用保护", e);
            protectCreativeOnly = true;
        }
        CreativeItemProtection creativeProtection = new CreativeItemProtection(
                new CreativeItemProtection.Settings(
                        protectCreativeOnly, Set.of(), Map.of(), Set.of()));

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

        // 使用已经通过 ConfigValidator 校验的不可变快照，避免二次读取原始配置造成
        // 事实来源漂移；配置未加载时该快照已是安全默认值（OWNED_ONLY + false）。
        pipeline.setOverflowScope(overflowSettings.scope());
        pipeline.setProjectEFileRewriteEnabled(overflowSettings.allowProjectEFileRewrite());
        return pipeline;
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
        return new ScanManager(
                processor,
                () -> collectSnapshot(pipeline),
                data -> applySnapshot(pipeline),
                TICK_BUDGET_NANOS);
    }

    /**
     * 采集阶段回调：读取当前服务器并生成不可变快照。
     *
     * <p>服务器尚未就绪时返回空快照，避免调度器因为空指针进入失败状态。</p>
     */
    private static ScanManager.SnapshotData collectSnapshot(ScanPipeline pipeline) {
        if (pipeline == null) {
            return new ScanManager.SnapshotData("扫描管线未初始化", 0, 0);
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            LOGGER.warn("SudoersEMC 扫描请求到达时服务器尚未就绪，本轮采集空快照");
            PipelineSnapshot empty = PipelineSnapshot.empty();
            pipeline.stash(empty);
            return new ScanManager.SnapshotData("服务器未就绪", 0, 0);
        }

        PipelineSnapshot snapshot = pipeline.collect(server);
        pipeline.stash(snapshot);
        return new ScanManager.SnapshotData(
                "真实扫描管线", snapshot.itemCount(), snapshot.recipeCount());
    }

    /**
     * 应用阶段回调：求解暂存快照、请求 ProjectE 重映射并落盘。
     *
     * <p>所有步骤都在服务器线程执行。重映射失败只记录警告，不会阻止已经生成的条目写入磁盘。</p>
     */
    private static void applySnapshot(ScanPipeline pipeline) {
        if (pipeline != null) {
            pipeline.applyStashed();
        }

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            try {
                OptionalIntegrationManager.provider()
                        .ifPresent(provider -> provider.requestRemap(server));
            } catch (RuntimeException e) {
                LOGGER.warn("SudoersEMC 请求 ProjectE 重映射失败，已生成条目仍会保存", e);
            }
        }

        GeneratedEntryStore store = entryStore;
        if (store != null) {
            store.saveIfDirty();
        }
        ScanStateStore stateStore = scanStateStore;
        if (stateStore != null) {
            stateStore.saveIfDirty();
        }
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
            manager.cancel();
            manager.reset();
        }
    }

    /**
     * 服务器启动后按 {@code [general] alwaysRescan} 请求扫描。
     *
     * <p>只有扫描调度器存在（即 {@code [general] enabled = true}）时才会真正提交请求；
     * 已有扫描在进行中时保持现状。</p>
     */
    private void requestStartupScanIfConfigured() {
        if (!shouldRequestScanOnStart(SudoersemcConfig.GENERAL_ALWAYS_RESCAN::get)) {
            return;
        }
        ScanManager manager = scanManager;
        if (manager == null) {
            LOGGER.info("SudoersEMC [general] alwaysRescan = true，但扫描调度器未启用，跳过启动扫描");
            return;
        }
        if (manager.requestScan()) {
            LOGGER.info("SudoersEMC [general] alwaysRescan = true：已请求启动扫描");
        } else {
            LOGGER.info("SudoersEMC [general] alwaysRescan = true，但已有扫描在进行中，跳过启动扫描");
        }
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
                    entryStore, scanStateStore, reportManager, overflowResetLog);
            ScanManager rebuilt = createScanManagerIfEnabled(
                    rebuiltPipeline, SudoersemcConfig.GENERAL_ENABLED::get);
            CommandService rebuiltService = new CommandService(
                    rebuilt,
                    reportManager,
                    entryStore,
                    scanStateStore,
                    Sudoersemc::reloadConfig);
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

        boolean enabled;
        boolean alwaysRescan;
        try {
            enabled = SudoersemcConfig.GENERAL_ENABLED.get();
            alwaysRescan = SudoersemcConfig.GENERAL_ALWAYS_RESCAN.get();
        } catch (RuntimeException e) {
            // 配置尚未加载或读取失败时退回安全默认值，绝不阻止服务器启动。
            LOGGER.warn("SudoersEMC 无法读取自动扫描配置，使用默认值（启用自动扫描、不强制重扫）", e);
            enabled = true;
            alwaysRescan = false;
        }

        ScanStateStore stateStore = scanStateStore;
        long lastScanEpochMillis = stateStore == null ? 0L : stateStore.lastScanEpochMillis();
        int generatedEntryCount = store == null ? 0 : store.size();

        if (!enabled) {
            LOGGER.info("SudoersEMC 自动扫描已禁用（general.enabled=false）");
        } else if (AutoScanPolicy.shouldScan(
                enabled, alwaysRescan, lastScanEpochMillis, generatedEntryCount)) {
            if (manager == null) {
                LOGGER.warn("SudoersEMC 自动扫描已请求，但扫描调度器尚未就绪（common setup 未完成）");
            } else if (manager.requestScan()) {
                LOGGER.info("SudoersEMC 自动扫描已请求（alwaysRescan={}，上次扫描={}，已生成条目={}）",
                        alwaysRescan, lastScanEpochMillis, generatedEntryCount);
            } else {
                LOGGER.info("SudoersEMC 自动扫描被拒绝：已有扫描正在运行");
            }
        } else {
            LOGGER.info("SudoersEMC 自动扫描被拒绝（已有缓存状态：上次扫描={}，已生成条目={}）",
                    lastScanEpochMillis, generatedEntryCount);
        }

        SmokeTestHook.onServerStarted(event);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        resetScanManager(scanManager);
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
