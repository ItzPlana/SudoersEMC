package com.itzplana.sudoersemc;

import com.itzplana.sudoersemc.command.CommandService;
import com.itzplana.sudoersemc.command.SudoemcCommands;
import com.itzplana.sudoersemc.config.ConfigValidationResult;
import com.itzplana.sudoersemc.config.ConfigWatcher;
import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.config.RuntimeConfigSnapshot;
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
import net.minecraftforge.fml.event.config.ModConfigEvent;
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

    /**
     * 运行时组件替换与扫描请求共用的锁。
     *
     * <p>重建在“检查扫描状态 → 发布新组件 → 退役旧调度器”期间持有本锁；
     * {@link #requestScanSynchronized()} 在同一把锁内完成“检查当前调度器 → 请求扫描”，
     * 因此不会出现命令线程在旧 manager 上启动扫描后，重建把旧 manager 遗弃的窗口。</p>
     */
    private static final Object RUNTIME_LOCK = new Object();

    /** 配置快照监听器；只在完整配置通过校验后触发。 */
    private static final ConfigWatcher CONFIG_WATCHER = SudoersemcConfig.configWatcher();

    static {
        CONFIG_WATCHER.register(result -> scheduleRuntimeRebuild("config-event"));
    }

    private static volatile boolean pendingReload;
    private static volatile boolean pendingReloadFromCommand;

    /** 已应用到运行时组件的快照代次，用于去重同一配置事件产生的多次重建请求。 */
    private static volatile long appliedSnapshotGeneration;

    private static volatile ReloadDecision lastReloadDecision = ReloadDecision.REJECT;

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
        // 配置类的 loading/reloading 监听器先捕获快照并更新校验结果；本监听器再把
        // 运行时重建调度回服务器线程。
        modEventBus.addListener(Sudoersemc::onModConfigEvent);

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
            ReloadDecision initial = rebuildRuntimeFromConfig("common-setup");
            if (initial == ReloadDecision.REJECT) {
                LOGGER.warn("SudoersEMC 初始配置未通过校验，已使用保守默认值启动；"
                        + "修正配置后可通过 /sudoemc reload 或配置事件热重载");
            }

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
    private static RuntimeComponents createRuntimeComponents(GeneratedEntryStore store,
                                                              ScanStateStore stateStore,
                                                              ReportManager reports,
                                                              @Nullable OverflowResetLog overflowLog,
                                                              RuntimeConfigSnapshot snapshot) {
        OverflowResetLog auditLog = overflowLog == null ? new OverflowResetLog() : overflowLog;
        OverflowPipeline overflowPipeline = new OverflowPipeline(
                new OverflowProtection(snapshot.overflowSettings()),
                auditLog);
        LargeDropProtection dropProtection =
                new LargeDropProtection(snapshot.dropProtectionSettings());
        CreativeItemProtection creativeProtection =
                new CreativeItemProtection(snapshot.creativeProtectionSettings());
        FallbackPricing fallbackPricing = FallbackPricing.withDefaults(snapshot.pricingSettings());

        ScanPipeline pipeline = new ScanPipeline(
                store,
                stateStore,
                reports,
                dropProtection,
                creativeProtection,
                overflowPipeline,
                fallbackPricing,
                snapshot.solveSettings());
        pipeline.setEmcProvider(OptionalIntegrationManager.provider().orElse(null));

        applyOverflowSettings(pipeline, snapshot.overflowSettings());
        applyRuntimeSwitches(pipeline, snapshot.solveRecipes(), snapshot.generateReports());

        ScanManager manager;
        if (snapshot.generalEnabled()) {
            manager = createScanManager(
                    pipeline, snapshot.itemsPerTick(), snapshot.adaptiveBatchSizing());
        } else {
            LOGGER.info("SudoersEMC [general] enabled = false：不创建扫描调度器，自动扫描已关闭");
            manager = null;
        }
        CommandService service = new CommandService(
                manager,
                reports,
                store,
                stateStore,
                Sudoersemc::reloadConfig,
                Sudoersemc::requestScanSynchronized);
        return new RuntimeComponents(pipeline, manager, service);
    }

    /**
     * 一组尚未发布的运行时组件。调用方在全部构建成功后一次性发布。
     */
    private record RuntimeComponents(ScanPipeline pipeline,
                                     @Nullable ScanManager scanManager,
                                     CommandService commandService) {
    }

    /**
     * 把已校验的溢出配置快照应用到扫描管线。
     *
     * <p>作用域与 ProjectE 文件改写开关必须同时从同一个配置快照读取，避免运行期出现
     * “作用域已扩大但改写开关仍是旧值”的组合。生产接线只能通过本方法设置这两个值，
     * 不得绕过配置快照直接读取 Forge 原始配置。</p>
     *
     * @param pipeline 扫描管线，不能为 {@code null}
     * @param settings 已通过 ConfigValidator 校验的溢出配置快照，不能为 {@code null}
     */
    static void applyOverflowSettings(ScanPipeline pipeline,
                                      OverflowProtection.Settings settings) {
        pipeline.setOverflowScope(settings.scope());
        pipeline.setProjectEFileRewriteEnabled(settings.allowProjectEFileRewrite());
    }

    /**
     * 把 phase-16 的运行时开关应用到新构建的扫描管线。
     *
     * <p>溢出作用域与 ProjectE 文件改写开关由
     * {@link #applyOverflowSettings(ScanPipeline, OverflowProtection.Settings)}
     * 从同一份已校验快照设置；本方法只负责配方求解与诊断报告两个开关，避免重复读取原始
     * 配置造成事实来源漂移。抽成独立方法便于在没有 Forge 配置环境的单元测试中验证映射，
     * 避免它们再次退化成“只定义不消费”的配置键。</p>
     *
     * @param pipeline        目标管线，不能为 {@code null}
     * @param solveRecipes    是否启用配方求解
     * @param generateReports 是否写出诊断报告
     */
    static void applyRuntimeSwitches(ScanPipeline pipeline,
                                     boolean solveRecipes,
                                     boolean generateReports) {
        pipeline.setSolveRecipesEnabled(solveRecipes);
        pipeline.setGenerateReportsEnabled(generateReports);
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
     * 用已校验快照中的性能参数创建扫描调度器并接入真实管线。
     *
     * <p>接线采用“采集暂存、应用取出”的方式：采集回调在服务器线程完成注册表与配方读取后把
     * {@link PipelineSnapshot} 暂存到管线中，应用回调再取出求解并写入存储。这样既满足
     * {@link ScanManager} 跨 tick 的阶段划分，也避免闭包数组带来的隐式状态。</p>
     *
     * @param pipeline      真实扫描管线
     * @param itemsPerTick  已校验的每 tick 处理上限
     * @param adaptive      已校验的自适应批量开关
     * @return 已接线的扫描调度器
     */
    private static ScanManager createScanManager(ScanPipeline pipeline,
                                                 int itemsPerTick,
                                                 boolean adaptive) {
        BatchProcessor processor = new BatchProcessor(Math.max(1, itemsPerTick), adaptive);
        return new ScanManager(
                processor,
                () -> ScanManager.oneShot(collectSnapshot(pipeline)).begin(),
                data -> applySnapshot(pipeline),
                TICK_BUDGET_NANOS);
    }

    /**
     * 采集阶段回调：读取当前服务器并生成不可变快照。
     *
     * <p>服务器或管线尚未就绪时抛出 {@link IllegalStateException}，由 {@link ScanManager} 转为
     * {@code FAILED}；绝不在失败时暂存空快照，避免调度器把失败扫描记录为成功的空扫描。</p>
     */
    private static ScanManager.SnapshotData collectSnapshot(ScanPipeline pipeline) {
        if (pipeline == null) {
            throw new IllegalStateException("扫描采集失败：扫描管线未初始化");
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            LOGGER.warn("SudoersEMC 扫描请求到达时服务器尚未就绪，本次扫描失败");
            throw new IllegalStateException("扫描采集失败：服务器尚未就绪");
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
     * <p>配置尚未加载或读取失败时按启用处理，并使用保守的性能默认值创建调度器；
     * 绝不因为读取开关失败而让模组失去扫描能力。</p>
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
        return createScanManager(pipeline, 100, true);
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
     * 启动自动扫描所需的配置值。
     *
     * @param enabled      是否启用启动自动扫描
     * @param alwaysRescan 是否忽略缓存强制重扫
     */
    record StartupScanSettings(boolean enabled, boolean alwaysRescan) {

        /**
         * 读取启动自动扫描配置。任一读取失败都按禁用处理（fail-closed），避免在无法确认
         * 管理员意图时自动写入经济数据。
         *
         * @param enabledSupplier      {@code [general] enabled} 读取器
         * @param alwaysRescanSupplier {@code [general] alwaysRescan} 读取器
         * @return 启动自动扫描配置；读取失败时为 {@code enabled=false, alwaysRescan=false}
         */
        static StartupScanSettings read(BooleanSupplier enabledSupplier,
                                        BooleanSupplier alwaysRescanSupplier) {
            try {
                return new StartupScanSettings(
                        enabledSupplier.getAsBoolean(), alwaysRescanSupplier.getAsBoolean());
            } catch (RuntimeException e) {
                LOGGER.warn("SudoersEMC 无法读取自动扫描配置，自动扫描按禁用处理", e);
                return new StartupScanSettings(false, false);
            }
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
     * 统计 SudoersEMC 拥有的生成条目数量。
     *
     * <p>{@link GeneratedEntryStore#size()} 包含 adopt/人工条目（{@code owned=false}），
     * 不能用来判断“是否已有自动扫描缓存”。只有 owned 条目代表本模组实际生成的值。</p>
     *
     * @param store 生成值存储，允许为 {@code null}
     * @return owned 条目数量；存储缺失或统计失败时为 0
     */
    static int countOwnedGeneratedEntries(GeneratedEntryStore store) {
        if (store == null) {
            return 0;
        }
        try {
            return store.snapshotOwnedValues().size();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法统计已生成条目，按 0 处理", e);
            return 0;
        }
    }

    /**
     * 服务器启动后按 {@code [general] alwaysRescan} 请求扫描。
     *
     * <p>只有扫描调度器存在（即 {@code [general] enabled = true}）时才会真正提交请求；
     * 已有扫描在进行中时保持现状。</p>
     */
    private void requestStartupScanIfConfigured() {
        RuntimeConfigSnapshot active = SudoersemcConfig.activeSnapshot();
        boolean alwaysRescan = active != null
                ? active.alwaysRescan()
                : shouldRequestScanOnStart(SudoersemcConfig.GENERAL_ALWAYS_RESCAN::get);
        if (!alwaysRescan) {
            return;
        }
        ScanManager manager = scanManager;
        if (manager == null) {
            LOGGER.info("SudoersEMC [general] alwaysRescan = true，但扫描调度器未启用，跳过启动扫描");
            return;
        }
        if (requestScanSynchronized()) {
            LOGGER.info("SudoersEMC [general] alwaysRescan = true：已请求启动扫描");
        } else {
            LOGGER.info("SudoersEMC [general] alwaysRescan = true，但已有扫描在进行中，跳过启动扫描");
        }
    }

    /**
     * 处理 {@code /sudoemc reload}：重读配置文件并重建受配置影响的运行时组件。
     *
     * <p>扫描进行中时不读取文件，只登记延迟重载；扫描结束后由服务器 tick 调用
     * {@link #applyConfigReload()}。命令入口与重建共享 {@link #RUNTIME_LOCK}，不再仅依赖
     * 方法级 {@code synchronized}。</p>
     *
     * @return 命令层用于生成回执的处理决策
     */
    private static ReloadDecision reloadConfig() {
        synchronized (RUNTIME_LOCK) {
            ScanManager manager = scanManager;
            if (manager != null && manager.isRunning()) {
                pendingReload = true;
                pendingReloadFromCommand = true;
                lastReloadDecision = ReloadDecision.DEFER;
                LOGGER.info("SudoersEMC 扫描进行中，配置重载已延后到扫描结束");
                return ReloadDecision.DEFER;
            }
        }
        return applyConfigReload();
    }

    /**
     * 从磁盘重读配置，校验通过后使用单一快照重建扫描管线、调度器与命令服务。
     *
     * <p>配置读取、跨字段校验或快照捕获失败时不会替换任何运行时组件，返回
     * {@link ReloadDecision#REJECT}；成功时 {@link #rebuildRuntimeFromConfig(String)} 在
     * {@link #RUNTIME_LOCK} 内一次性发布新组件并退役旧调度器。</p>
     */
    private static ReloadDecision applyConfigReload() {
        ConfigValidationResult validation = SudoersemcConfig.reloadFromDisk();
        if (!validation.valid()) {
            pendingReload = false;
            pendingReloadFromCommand = false;
            lastReloadDecision = ReloadDecision.REJECT;
            LOGGER.warn("SudoersEMC 配置重载被拒绝，保留上一份有效配置：\n{}", validation.format());
            return ReloadDecision.REJECT;
        }
        ReloadDecision decision = rebuildRuntimeFromConfig("command");
        if (decision == ReloadDecision.REJECT) {
            pendingReload = false;
            pendingReloadFromCommand = false;
        }
        return decision;
    }

    /**
     * 依据当前已校验快照重建全部运行时组件。
     *
     * <p>调用者可以是 common setup、命令线程或 {@code server.execute(...)} 派发的服务器
     * 线程任务。组件先在锁外从不可变快照构建；随后在 {@link #RUNTIME_LOCK} 内重新检查扫描
     * 状态并一次性发布。扫描进行中时只登记延迟重建，绝不替换正在 tick 的调度器。</p>
     *
     * @param reason 触发原因，仅用于日志
     * @return 立即生效、延迟到扫描结束或拒绝
     */
    static ReloadDecision rebuildRuntimeFromConfig(String reason) {
        String label = reason == null || reason.isBlank() ? "unknown" : reason;
        GeneratedEntryStore store = entryStore;
        ScanStateStore stateStore = scanStateStore;
        ReportManager reports = reportManager;
        if (store == null || stateStore == null || reports == null) {
            LOGGER.debug("SudoersEMC 配置重建跳过（{}）：common setup 尚未完成", label);
            lastReloadDecision = ReloadDecision.REJECT;
            return ReloadDecision.REJECT;
        }

        ResolvedSnapshot resolved = resolveSnapshot(label);
        RuntimeConfigSnapshot snapshot = resolved.snapshot();

        RuntimeComponents components;
        try {
            components = createRuntimeComponents(
                    store, stateStore, reports, overflowResetLog, snapshot);
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 构建运行时组件失败（{}），保留旧组件", label, failure);
            lastReloadDecision = ReloadDecision.REJECT;
            return ReloadDecision.REJECT;
        }

        synchronized (RUNTIME_LOCK) {
            ScanManager current = scanManager;
            if (current != null && current.isRunning()) {
                pendingReload = true;
                lastReloadDecision = ReloadDecision.DEFER;
                LOGGER.info("SudoersEMC 扫描进行中，配置重建已延后（{}），将在扫描结束后应用", label);
                return ReloadDecision.DEFER;
            }
            if (scanPipeline != null && snapshot.generation() <= appliedSnapshotGeneration) {
                LOGGER.debug("SudoersEMC 配置快照 generation={} 不新于已应用 generation={}，"
                                + "跳过重复或过期重建（{}）",
                        snapshot.generation(), appliedSnapshotGeneration, label);
                pendingReload = false;
                pendingReloadFromCommand = false;
                return lastReloadDecision == ReloadDecision.REJECT
                        ? ReloadDecision.REJECT : ReloadDecision.APPLY_NOW;
            }

            scanPipeline = components.pipeline();
            scanManager = components.scanManager();
            commandService = components.commandService();
            SudoemcCommands.setService(commandService);
            appliedSnapshotGeneration = snapshot.generation();
            pendingReload = false;
            pendingReloadFromCommand = false;
            if (current != null) {
                current.retire();
            }
            lastReloadDecision = resolved.valid()
                    ? ReloadDecision.APPLY_NOW : ReloadDecision.REJECT;
            if (resolved.valid()) {
                LOGGER.info("SudoersEMC 运行时组件已重建（{}，generation={}）",
                        label, snapshot.generation());
            } else {
                LOGGER.warn("SudoersEMC 初始配置无效，已使用保守默认快照构建运行时组件（{}）", label);
            }
            return lastReloadDecision;
        }
    }

    /**
     * 配置事件到达后的重建入口。
     *
     * <p>此方法可能运行在 Forge 配置加载线程，只负责把重建请求投递到服务器线程；
     * 服务器不存在时设置挂起标记，由首次 tick 处理。</p>
     */
    private static void scheduleRuntimeRebuild(String reason) {
        String label = reason == null || reason.isBlank() ? "config-event" : reason;
        if (entryStore == null || scanStateStore == null || reportManager == null) {
            LOGGER.debug("SudoersEMC 配置事件早于 common setup（{}），跳过重建", label);
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            pendingReload = true;
            pendingReloadFromCommand = false;
            LOGGER.info("SudoersEMC 服务器尚未就绪，配置重建已挂起（{}）", label);
            return;
        }
        server.execute(() -> {
            if (!server.isRunning()) {
                LOGGER.debug("SudoersEMC 服务器已停止，跳过挂起的配置重建（{}）", label);
                return;
            }
            try {
                rebuildRuntimeFromConfig(label);
            } catch (RuntimeException failure) {
                LOGGER.warn("SudoersEMC 服务器线程配置重建失败（{}），保留旧组件", label, failure);
            }
        });
    }

    /**
     * 处理 {@code ModConfigEvent.Loading/Reloading}。
     *
     * <p>{@link SudoersemcConfig} 的监听器先完成快照捕获与校验；这里只读取校验结果并在
     * 通过时调度服务器线程重建。配置事件本身永远不会直接替换运行时组件。</p>
     */
    private static void onModConfigEvent(ModConfigEvent event) {
        if (event == null || event.getConfig() == null
                || event.getConfig().getSpec() != SudoersemcConfig.SPEC) {
            return;
        }
        if (!SudoersemcConfig.lastValidationResult().valid()) {
            LOGGER.warn("SudoersEMC 配置事件校验未通过，保留现有运行时组件");
            return;
        }
        scheduleRuntimeRebuild("config-event");
    }

    /**
     * 在扫描结束后执行一次延迟重建。
     *
     * <p>命令触发的延迟重载需要重新读取磁盘；配置事件触发的延迟重载直接使用已经捕获并
     * 校验过的快照。</p>
     */
    private static void processPendingReload() {
        if (!pendingReload) {
            return;
        }
        ScanManager manager = scanManager;
        if (manager != null && manager.isRunning()) {
            return;
        }
        ReloadDecision decision = pendingReloadFromCommand
                ? applyConfigReload()
                : rebuildRuntimeFromConfig("pending-scan-end");
        if (decision == ReloadDecision.REJECT) {
            pendingReload = false;
            pendingReloadFromCommand = false;
            LOGGER.warn("SudoersEMC 延迟配置重载失败，已清除待处理标记并继续使用旧组件");
        }
    }

    /**
     * 在运行时锁内请求一次扫描。
     *
     * <p>重建在发布新组件期间持有同一把锁，因此本方法不会在即将被退役的旧 manager 上启动
     * 扫描；旧 manager 的 {@link ScanManager#retire()} 也会拒绝之后到达的请求。</p>
     *
     * @return 成功启动返回 {@code true}；没有调度器或已有扫描返回 {@code false}
     */
    static boolean requestScanSynchronized() {
        synchronized (RUNTIME_LOCK) {
            ScanManager current = scanManager;
            if (current == null || current.isRunning()) {
                return false;
            }
            boolean started = current.requestScan();
            if (started && scanManager != current) {
                current.cancel();
                return false;
            }
            return started;
        }
    }

    /**
     * 解析本次重建应使用的单一快照。
     *
     * <p>优先使用配置事件已经发布的有效快照；首次加载尚未产生快照时尝试现场捕获一次，
     * 仍失败则退回保守默认值。无论哪条路径，调用方拿到的都是一个完整不可变快照。</p>
     */
    private static ResolvedSnapshot resolveSnapshot(String reason) {
        RuntimeConfigSnapshot snapshot = SudoersemcConfig.activeSnapshot();
        if (snapshot != null) {
            return new ResolvedSnapshot(snapshot, true);
        }
        SudoersemcConfig.SnapshotCapture capture = SudoersemcConfig.captureSnapshot();
        if (capture.hasSnapshot()) {
            return new ResolvedSnapshot(capture.snapshot(), true);
        }
        LOGGER.warn("SudoersEMC 没有可用配置快照（{}），使用保守默认值构建运行时组件", reason);
        return new ResolvedSnapshot(RuntimeConfigSnapshot.safeDefaults(), false);
    }

    /**
     * 一次快照解析结果；{@code valid=false} 表示使用了保守默认值。
     */
    private record ResolvedSnapshot(RuntimeConfigSnapshot snapshot, boolean valid) {
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
                processPendingReload();
            }
        }
    }

    private void onServerStarted(ServerStartedEvent event) {
        // 配置事件可能早于服务器就绪；先应用挂起重载，再用新组件决定是否自动扫描。
        if (pendingReload) {
            processPendingReload();
        }
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

        RuntimeConfigSnapshot active = SudoersemcConfig.activeSnapshot();
        StartupScanSettings scanSettings = active == null
                ? StartupScanSettings.read(
                        SudoersemcConfig.GENERAL_ENABLED::get,
                        SudoersemcConfig.GENERAL_ALWAYS_RESCAN::get)
                : new StartupScanSettings(active.generalEnabled(), active.alwaysRescan());
        boolean enabled = scanSettings.enabled();
        boolean alwaysRescan = scanSettings.alwaysRescan();

        ScanStateStore stateStore = scanStateStore;
        long lastScanEpochMillis = stateStore == null ? 0L : stateStore.lastScanEpochMillis();
        int generatedEntryCount = countOwnedGeneratedEntries(store);

        if (!enabled) {
            LOGGER.info("SudoersEMC 自动扫描已禁用（general.enabled=false）");
        } else if (AutoScanPolicy.shouldScan(
                enabled, alwaysRescan, lastScanEpochMillis, generatedEntryCount)) {
            if (manager == null) {
                LOGGER.warn("SudoersEMC 自动扫描已请求，但扫描调度器尚未就绪（common setup 未完成）");
            } else if (requestScanSynchronized()) {
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
        pendingReloadFromCommand = false;
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
