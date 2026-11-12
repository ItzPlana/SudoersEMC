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
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanManager;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
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
            ScanManager manager = createScanManager();
            scanManager = manager;
            commandService = new CommandService(
                    manager,
                    reportManager,
                    store,
                    stateStore,
                    Sudoersemc::reloadConfig);
            SudoemcCommands.setService(commandService);

            LOGGER.info("SudoersEMC common setup complete ({} generated entries loaded)", store.size());
        });
    }

    /**
     * 创建扫描调度器。
     *
     * <p>采集与应用回调目前只统计数量，真实管线在后续集成阶段接入求解器与定价链路。
     * 这样做的目的是让调度骨架先跑通 tick 驱动与进度上报，避免一次性引入大量未验证逻辑。</p>
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
                () -> new ScanManager.SnapshotData("待接入真实扫描", 0, 0),
                data -> LOGGER.debug("SudoersEMC 扫描应用阶段：{}", data.label()),
                TICK_BUDGET_NANOS);
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
     * {@link ReloadDecision#REJECT}；成功时使用新配置重建 {@link ScanManager} 并重新注入
     * {@link CommandService}，同时清理旧调度器，避免残留取消标志。</p>
     */
    private static synchronized ReloadDecision applyConfigReload() {
        pendingReload = false;
        ConfigValidationResult validation = SudoersemcConfig.reloadFromDisk();
        if (!validation.valid()) {
            LOGGER.warn("SudoersEMC 配置重载被拒绝，保留上一份有效配置：\n{}", validation.format());
            return ReloadDecision.REJECT;
        }

        try {
            ScanManager rebuilt = createScanManager();
            CommandService rebuiltService = new CommandService(
                    rebuilt,
                    reportManager,
                    entryStore,
                    scanStateStore,
                    Sudoersemc::reloadConfig);
            ScanManager previous = scanManager;
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
    }

    private void onServerStopping(ServerStoppingEvent event) {
        GeneratedEntryStore store = entryStore;
        if (store != null) {
            store.saveIfDirty();
        }
        ScanStateStore stateStore = scanStateStore;
        if (stateStore != null) {
            stateStore.saveIfDirty();
        }
        ScanManager manager = scanManager;
        if (manager != null) {
            manager.cancel();
            manager.reset();
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
}
