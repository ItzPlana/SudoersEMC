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
import com.itzplana.sudoersemc.pipeline.ScanCollectionPipeline;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanManager;
import com.mojang.logging.LogUtils;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SudoersEMC 模组入口。
 *
 * <p>负责组装各层：配置注册、持久化加载、ProjectE 集成探测、扫描调度器、诊断与命令服务。
 * 具体的扫描、求解与定价逻辑分散在各自包中，本类只做接线，不含业务规则。</p>
 *
 * <p><strong>扫描接线：</strong>服务器启动时构建 {@link ScanCollectionPipeline}，把分片采集器、
 * 生产指纹提供器、跨 tick 结果应用器与异步持久化回调注入 {@link ScanManager}；服务器 tick
 * 驱动 {@link ScanManager#tick()}，纯求解与文件写入在管线 worker 上执行。
 * {@link AddReloadListenerEvent} 与 {@link TagsUpdatedEvent} 会递增管线的 reload 代次，
 * 避免把基于旧配方的价格写入 EMC。</p>
 *
 * <p><strong>命令接线：</strong>{@link CommandService} 绑定当前扫描调度器与存储；服务器启动或
 * 配置重载后重新注入，停服时清空，防止命令继续持有旧世界的组件。</p>
 */
@Mod(Sudoersemc.MODID)
public final class Sudoersemc {

    public static final String MODID = "sudoersemc";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 单 tick 分配给扫描工作的时间预算，1 毫秒。 */
    private static final long TICK_BUDGET_NANOS = 1_000_000L;

    private static volatile GeneratedEntryStore entryStore;
    private static volatile ScanStateStore scanStateStore;
    private static volatile ScanCollectionPipeline scanPipeline;
    private static volatile ScanManager scanManager;
    private static volatile ReportManager reportManager;
    private static volatile CommandService commandService;
    private static volatile MinecraftServer currentServer;
    private static volatile boolean pendingReload;

    public Sudoersemc() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);

        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(this::onAddReloadListener);
        MinecraftForge.EVENT_BUS.addListener(this::onTagsUpdated);
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
            // 服务器尚未启动，先创建占位调度器，让命令层在 common setup 后即可查询状态。
            // 服务器启动时 startScanRuntime 会把它替换为完整的采集/求解/落盘管线。
            scanManager = createScanManagerIfEnabled(SudoersemcConfig.GENERAL_ENABLED::get);
            refreshCommandService();

            LOGGER.info("SudoersEMC common setup complete ({} generated entries loaded)", store.size());
        });
    }

    /**
     * 创建不依赖服务器状态的占位扫描调度器。
     *
     * <p>真实管线需要服务器实例，只有在 {@link ServerStartedEvent} 才能构建；common setup
     * 阶段先提供空闲调度器，保证命令层始终有可查询的状态机。生产扫描由
     * {@link #startScanRuntime(MinecraftServer)} 替换。</p>
     */
    private static ScanManager createScanManager() {
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
                emptyCollector(),
                data -> LOGGER.debug("SudoersEMC 扫描应用阶段：{}", data.label()),
                TICK_BUDGET_NANOS);
    }

    /**
     * 占位采集器：立即报告完成且不访问任何游戏状态。
     *
     * <p>只用于服务器启动前给命令层提供可查询的空闲状态机；真实采集由
     * {@link #startScanRuntime(MinecraftServer)} 注入。</p>
     */
    private static ScanManager.SnapshotCollector emptyCollector() {
        return () -> new ScanManager.CollectSession() {

            @Override
            public int itemCount() {
                return 0;
            }

            @Override
            public int recipeCount() {
                return 0;
            }

            @Override
            public int processedItems() {
                return 0;
            }

            @Override
            public int processedRecipes() {
                return 0;
            }

            @Override
            public boolean isDone() {
                return true;
            }

            @Override
            public ScanManager.StepResult step(int maxItems) {
                return new ScanManager.StepResult(0, true);
            }

            @Override
            public ScanManager.SnapshotData finish() {
                return new ScanManager.SnapshotData("待接入真实扫描", 0, 0);
            }
        };
    }

    /**
     * 按 {@code [general] enabled} 决定是否创建扫描调度器。
     *
     * <p>配置尚未加载或读取失败时沿用与 {@link #createScanManager()} 相同的容错策略：
     * 记录警告并按启用处理，绝不因为读取开关失败而让模组失去扫描能力。</p>
     *
     * @param enabledSupplier 开关读取器；测试可注入固定值
     * @return 启用时返回新的调度器，关闭时返回 {@code null}
     */
    static ScanManager createScanManagerIfEnabled(BooleanSupplier enabledSupplier) {
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
        return createScanManager();
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
     * 从磁盘重读配置，校验通过后重建扫描调度器与命令服务。
     *
     * <p>配置读取或跨字段校验失败时不会替换任何运行时组件，返回
     * {@link ReloadDecision#REJECT}；成功时按当前是否处于服务器运行期重建完整扫描管线或
     * 占位调度器，并重新注入 {@link CommandService}，同时清理旧调度器，避免残留取消标志。</p>
     */
    private static synchronized ReloadDecision applyConfigReload() {
        pendingReload = false;
        ConfigValidationResult validation = SudoersemcConfig.reloadFromDisk();
        if (!validation.valid()) {
            LOGGER.warn("SudoersEMC 配置重载被拒绝，保留上一份有效配置：\n{}", validation.format());
            return ReloadDecision.REJECT;
        }

        try {
            MinecraftServer server = currentServer;
            if (server != null) {
                // 服务器运行中：重建完整扫描管线，避免把生产调度器降级成占位实现。
                startScanRuntime(server);
            } else {
                ScanManager previous = scanManager;
                scanManager = createScanManagerIfEnabled(SudoersemcConfig.GENERAL_ENABLED::get);
                if (previous != null) {
                    previous.cancel();
                    previous.reset();
                }
            }
            refreshCommandService();
            LOGGER.info("SudoersEMC 配置已重新加载并生效");
            return ReloadDecision.APPLY_NOW;
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 应用新配置失败，保留旧运行时组件", failure);
            return ReloadDecision.REJECT;
        }
    }

    /**
     * 用当前运行时组件重新创建命令服务并重新注入命令树。
     *
     * <p>服务器启动和配置重载都会替换 {@link ScanManager}，命令服务必须跟着重建，
     * 否则 {@code /sudoemc status} 等命令会继续读取旧调度器。</p>
     */
    private static void refreshCommandService() {
        CommandService service = new CommandService(
                scanManager,
                reportManager,
                entryStore,
                scanStateStore,
                Sudoersemc::reloadConfig);
        commandService = service;
        SudoemcCommands.setService(service);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(SudoemcCommands.register());
        LOGGER.debug("SudoersEMC registered /sudoemc command tree");
    }

    private void onServerStarted(ServerStartedEvent event) {
        currentServer = event.getServer();
        // 服务器启动后构建真实扫描管线；它会按 general.enabled 决定是否启用，并按
        // general.alwaysRescan / 输入指纹决定是否立即请求扫描。
        startScanRuntime(event.getServer());
        // 集成服务器停止后会清空命令服务；新世界启动时必须绑定新的调度器重新注入。
        refreshCommandService();

        GeneratedEntryStore store = entryStore;
        if (store != null) {
            LOGGER.info("SudoersEMC active: {} generated entries, ProjectE {}",
                    store.size(),
                    OptionalIntegrationManager.isProjectEAvailable() ? "detected" : "absent");
        }
        SmokeTestHook.onServerStarted(event);
    }

    private void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ScanManager manager = scanManager;
        if (manager != null) {
            manager.tick();
        }
        if (pendingReload) {
            ScanManager current = scanManager;
            if (current == null || !current.isRunning()) {
                applyConfigReload();
            }
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ScanManager manager = scanManager;
        if (manager != null) {
            manager.cancel();
            manager.reset();
        }
        ScanCollectionPipeline pipeline = scanPipeline;
        if (pipeline != null) {
            pipeline.persist();
        }
        scanManager = null;
        scanPipeline = null;
        currentServer = null;

        GeneratedEntryStore store = entryStore;
        if (store != null) {
            store.saveIfDirty();
        }
        ScanStateStore stateStore = scanStateStore;
        if (stateStore != null) {
            stateStore.saveIfDirty();
        }

        pendingReload = false;
        // 不保留指向旧世界的命令服务；下一次 ServerStartedEvent 会重新注入。
        SudoemcCommands.setService(null);
    }

    /**
     * datapack/reload 开始：递增扫描管线的 reload 代次，使正在 APPLYING 的扫描立即停止
     * 使用旧配方价格。该事件可能在资源加载线程触发，管线内部使用原子计数，线程安全。
     */
    private void onAddReloadListener(AddReloadListenerEvent event) {
        recordDatapackReload();
    }

    /**
     * 资源重载完成（服务端数据加载）：再次递增代次，覆盖“扫描在 reload 进行中开始”的窗口。
     * 只处理服务端数据加载，忽略客户端同步包触发的事件。
     */
    private void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.SERVER_DATA_LOAD) {
            recordDatapackReload();
        }
    }

    private static void recordDatapackReload() {
        ScanCollectionPipeline pipeline = scanPipeline;
        if (pipeline != null) {
            pipeline.onDatapackReload();
        }
    }

    /**
     * 构建并启动 Phase 7 扫描运行时。
     *
     * <p>读取 {@code performance.itemsPerTick} 与
     * {@code performance.adaptiveBatchSizing} 构造 {@link BatchProcessor}；把真实分片扫描器、
     * 生产指纹提供器、结果应用器与持久化回调注入 {@link ScanManager}。服务器启动时按
     * {@code general.alwaysRescan} 或指纹变化决定是否立即扫描。</p>
     */
    private static synchronized void startScanRuntime(MinecraftServer server) {
        ScanManager previous = scanManager;
        if (previous != null) {
            previous.cancel();
            previous.reset();
        }
        scanManager = null;
        scanPipeline = null;

        GeneratedEntryStore store = entryStore;
        if (store == null) {
            LOGGER.warn("SudoersEMC scan runtime not started: generated entry store is not ready");
            return;
        }

        boolean enabled = readBoolean(SudoersemcConfig.GENERAL_ENABLED, true);
        if (!enabled) {
            LOGGER.info("SudoersEMC [general] enabled = false: scan runtime disabled");
            return;
        }

        int itemsPerTick = readInt(SudoersemcConfig.PERFORMANCE_ITEMS_PER_TICK, 100);
        boolean adaptive = readBoolean(SudoersemcConfig.PERFORMANCE_ADAPTIVE_BATCH_SIZING, true);
        boolean alwaysRescan = shouldRequestScanOnStart(SudoersemcConfig.GENERAL_ALWAYS_RESCAN::get);

        ScanCollectionPipeline pipeline = new ScanCollectionPipeline(
                store, () -> requestProjectERemap(server));
        ScanManager manager = ScanManager.staged(
                new BatchProcessor(Math.max(1, itemsPerTick), adaptive),
                pipeline.collector(server),
                pipeline.stagedApplier(),
                TICK_BUDGET_NANOS,
                pipeline.fingerprintSupplier(server),
                pipeline.stagedSaveHandler());

        scanPipeline = pipeline;
        scanManager = manager;
        LOGGER.info("SudoersEMC scan runtime wired: itemsPerTick={}, adaptive={}",
                itemsPerTick, adaptive);

        if (pipeline.shouldScan(server, alwaysRescan)) {
            if (manager.requestScan()) {
                LOGGER.info("SudoersEMC startup scan requested");
            } else {
                LOGGER.info("SudoersEMC startup scan not requested: another scan is already active");
            }
        } else {
            LOGGER.info("SudoersEMC scan inputs unchanged since the last successful scan; "
                    + "skipping startup scan (set general.alwaysRescan=true to force)");
        }
    }

    /**
     * 在全部生成值 staging 完成后请求 EMC 后端重映射。
     *
     * <p>生成值此时已经写入 {@link GeneratedEntryStore}；重映射失败只记录警告，已经暂存的
     * 值仍会在下一次 remap 或重启后生效。该回调由扫描管线在服务器线程的 APPLYING 阶段末尾
     * 调用，不在文件写入或求解工作线程上执行。</p>
     */
    private static void requestProjectERemap(MinecraftServer server) {
        try {
            OptionalIntegrationManager.provider()
                    .ifPresent(provider -> provider.requestRemap(server));
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 请求 ProjectE 重映射失败，已生成条目仍会保存", e);
        }
    }

    private static int readInt(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC could not read an integer performance config; using {}", fallback, e);
            return fallback;
        }
    }

    private static boolean readBoolean(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC could not read a boolean config; using {}", fallback, e);
            return fallback;
        }
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
     * @return 当前生效的扫描调度器；服务器启动前是占位调度器，停服后为 {@code null}
     */
    public static ScanManager scanManager() {
        return scanManager;
    }

    /**
     * @return 当前生效的扫描管线，服务器启动前/停服后为 {@code null}
     */
    public static ScanCollectionPipeline scanPipeline() {
        return scanPipeline;
    }
}
