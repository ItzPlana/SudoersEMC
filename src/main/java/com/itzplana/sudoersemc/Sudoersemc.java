package com.itzplana.sudoersemc;

import com.itzplana.sudoersemc.config.SudoersemcConfig;
import com.itzplana.sudoersemc.diagnostic.SmokeTestHook;
import com.itzplana.sudoersemc.integration.GeneratedEmcMapper;
import com.itzplana.sudoersemc.integration.OptionalIntegrationManager;
import com.itzplana.sudoersemc.pipeline.ScanCollectionPipeline;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanManager;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SudoersEMC entry point.
 *
 * <p>Phase 1 wiring: persistence, ProjectE integration detection, and the ProjectE
 * {@code @EMCMapper} that publishes owned values. Scanning is wired in Phase 7; pricing and
 * solving pipelines are expanded in later phases.</p>
 *
 * <p><strong>Phase 7 wiring:</strong> the scan state machine is constructed and ticked here. On
 * {@link ServerStartedEvent} this class builds a {@link ScanCollectionPipeline}, injects its
 * sharded {@code ItemScanner}/{@code RecipeScanner} sessions, production fingerprint supplier,
 * result applier and persistence callback into {@link ScanManager}, then requests an initial scan
 * when the input fingerprint changed (or {@code general.alwaysRescan} is enabled). The
 * {@link TickEvent.ServerTickEvent} listener drives {@link ScanManager#tick()} on the server
 * thread.</p>
 */
@Mod(Sudoersemc.MODID)
public final class Sudoersemc {

    public static final String MODID = "sudoersemc";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 单 tick 分配给扫描工作的时间预算，1 毫秒。 */
    private static final long TICK_BUDGET_NANOS = 1_000_000L;

    private static volatile GeneratedEntryStore entryStore;
    private static volatile ScanCollectionPipeline scanPipeline;
    private static volatile ScanManager scanManager;

    public Sudoersemc() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);

        MinecraftForge.EVENT_BUS.addListener(this::onServerTick);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(SmokeTestHook::onServerTick);

        // Must be registered during mod construction, before configs are loaded.
        SudoersemcConfig.register();

        LOGGER.info("SudoersEMC is loading");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            GeneratedEntryStore store = new GeneratedEntryStore();
            store.load();
            entryStore = store;

            // The sink must exist before the integration manager can create a provider.
            OptionalIntegrationManager.setSink(store);
            OptionalIntegrationManager.init();

            // GeneratedEmcMapper implements a ProjectE interface, so its class may only be
            // initialized when ProjectE is actually present. The guard is what keeps the mod
            // loadable on servers that do not have ProjectE installed.
            if (OptionalIntegrationManager.isProjectEAvailable()) {
                GeneratedEmcMapper.setSource(store);
                GeneratedEmcMapper.setManualValues(OptionalIntegrationManager.manualValues());
                LOGGER.info("SudoersEMC wired its generated-value mapper into ProjectE");
            }

            LOGGER.info("SudoersEMC common setup complete ({} generated entries loaded)", store.size());
        });
    }

    private void onServerStarted(ServerStartedEvent event) {
        GeneratedEntryStore store = entryStore;
        if (store != null) {
            LOGGER.info("SudoersEMC active: {} generated entries, ProjectE {}",
                    store.size(),
                    OptionalIntegrationManager.isProjectEAvailable() ? "detected" : "absent");
        }
        startScanRuntime(event.getServer());
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

        GeneratedEntryStore store = entryStore;
        if (store != null) {
            store.saveIfDirty();
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
        boolean alwaysRescan = readBoolean(SudoersemcConfig.GENERAL_ALWAYS_RESCAN, false);

        ScanCollectionPipeline pipeline = new ScanCollectionPipeline(store);
        ScanManager manager = new ScanManager(
                new BatchProcessor(Math.max(1, itemsPerTick), adaptive),
                pipeline.collector(server),
                data -> applyScanResult(pipeline, server, data),
                TICK_BUDGET_NANOS,
                pipeline.fingerprintSupplier(server),
                pipeline::persist);

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
     * 应用扫描结果并请求 EMC 后端重映射。
     *
     * <p>生成值先写入 {@link GeneratedEntryStore}，随后请求 ProjectE 重新计算 EMC；重映射
     * 失败只记录警告，已经暂存的值仍会在下一次 remap 或重启后生效。</p>
     */
    private static void applyScanResult(ScanCollectionPipeline pipeline,
                                        MinecraftServer server,
                                        ScanManager.SnapshotData data) {
        pipeline.apply(data);
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
     * @return the live entry store, or {@code null} before common setup has completed
     */
    public static GeneratedEntryStore entryStore() {
        return entryStore;
    }

    /**
     * @return the active scan state machine, or {@code null} before server start / after stop
     */
    public static ScanManager scanManager() {
        return scanManager;
    }

    /**
     * @return the active scan pipeline, or {@code null} before server start / after stop
     */
    public static ScanCollectionPipeline scanPipeline() {
        return scanPipeline;
    }
}
