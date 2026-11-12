package com.itzplana.sudoersemc;

import com.itzplana.sudoersemc.command.CommandService;
import com.itzplana.sudoersemc.command.SudoemcCommands;
import com.itzplana.sudoersemc.config.ConfigValidationResult;
import com.itzplana.sudoersemc.config.ConfigValidator;
import com.itzplana.sudoersemc.config.ConfigWatcher;
import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.config.SudoersemcConfig;
import com.itzplana.sudoersemc.core.OverflowScope;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.ForgeConfigSpec;
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
 *
 * <p>所有运行时组件都可以由 {@link #rebuildRuntimeFromConfig(String)} 从当前配置重建。
 * 重建是全有或全无的：配置校验失败时不会替换任何字段，旧组件继续服务。扫描进行中收到
 * 的重建请求会标记为延迟，在扫描结束后的第一次服务器 tick 执行。</p>
 */
@Mod(Sudoersemc.MODID)
public final class Sudoersemc {

    public static final String MODID = "sudoersemc";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** 单 tick 分配给扫描工作的时间预算，1 毫秒。 */
    private static final long TICK_BUDGET_NANOS = 1_000_000L;

    /** 配置事件与命令共用的重载协调器；失败摘要与冲突警告由它去重。 */
    private static final ConfigWatcher CONFIG_WATCHER = new ConfigWatcher();

    static {
        CONFIG_WATCHER.register(result -> rebuildRuntimeFromConfig("config-event"));
    }

    private static volatile GeneratedEntryStore entryStore;
    private static volatile ScanStateStore scanStateStore;
    private static volatile ReportManager reportManager;
    private static volatile ScanManager scanManager;
    private static volatile ScanPipeline scanPipeline;
    private static volatile OverflowResetLog overflowResetLog;
    private static volatile FallbackPricing fallbackPricing;
    private static volatile SolveSettings solveSettings;
    private static volatile OverflowPipeline overflowPipeline;
    private static volatile LargeDropProtection largeDropProtection;
    private static volatile CreativeItemProtection creativeItemProtection;
    private static volatile CommandService commandService;
    private static volatile boolean pendingReload;
    private static volatile ReloadDecision lastReloadDecision = ReloadDecision.REJECT;

    public Sudoersemc() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);
        modEventBus.addListener(Sudoersemc::onModConfigEvent);

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

            overflowResetLog = new OverflowResetLog();
            reportManager = new ReportManager();

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

            if (!rebuildRuntimeFromConfig("common-setup")) {
                // 即使初始配置非法，也安装一个只带重载动作的命令服务，便于管理员修正配置后
                // 通过 /sudoemc reload 或配置文件热重载恢复；扫描相关子命令会返回服务不可用。
                commandService = new CommandService(
                        scanManager,
                        reportManager,
                        store,
                        stateStore,
                        () -> requestReload("command"));
                SudoemcCommands.setService(commandService);
                LOGGER.warn("SudoersEMC 初始运行时组件构建失败，已保留可重载的命令服务，等待配置修正");
            }

            LOGGER.info("SudoersEMC common setup complete ({} generated entries loaded)", store.size());
        });
    }

    /**
     * 依据当前配置重建全部运行时组件。
     *
     * <p>重建前先执行跨字段校验；校验失败、公共初始化尚未完成或构建过程抛出运行时异常时
     * 返回 {@code false}，并且不替换任何静态字段。扫描进行中时不立即重建，而是设置
     * {@code pendingReload} 并返回 {@code true}，由服务器 tick 在扫描结束后执行一次。</p>
     *
     * <p>存储、扫描状态存储、诊断报告管理器与溢出审计日志在重建之间保持不变，因此已加载的
     * 数据与审计记录不会丢失。</p>
     *
     * @param reason 触发原因，仅用于日志
     * @return 立即重建成功或已成功登记延迟重建时返回 {@code true}
     */
    private static synchronized boolean rebuildRuntimeFromConfig(String reason) {
        String label = reason == null || reason.isBlank() ? "unknown" : reason;
        GeneratedEntryStore store = entryStore;
        ScanStateStore stateStore = scanStateStore;
        ReportManager reports = reportManager;
        if (store == null || stateStore == null || reports == null) {
            lastReloadDecision = ReloadDecision.REJECT;
            LOGGER.debug("SudoersEMC 配置重建跳过（{}）：common setup 尚未完成", label);
            return false;
        }

        ConfigValidationResult validation;
        try {
            validation = ConfigValidator.validateCurrent();
        } catch (RuntimeException failure) {
            lastReloadDecision = ReloadDecision.REJECT;
            LOGGER.warn("SudoersEMC 配置重建校验异常（{}），保留上一份有效配置", label, failure);
            return false;
        }
        boolean scanRunning = scanManager != null && scanManager.isRunning();
        ReloadDecision decision = ReloadDecision.decide(scanRunning, validation.valid());
        lastReloadDecision = decision;

        if (decision == ReloadDecision.REJECT) {
            // fireReload 负责输出一条去重后的汇总警告，其中已包含全部警告文本。
            CONFIG_WATCHER.fireReload(validation);
            return false;
        }
        CONFIG_WATCHER.logConflictWarnings(validation);
        if (decision == ReloadDecision.DEFER) {
            pendingReload = true;
            LOGGER.info("SudoersEMC 扫描进行中，配置重建已延后（{}），将在扫描结束后应用", label);
            return true;
        }

        try {
            RuntimeComponents components = createRuntimeComponents(store, stateStore, reports);
            fallbackPricing = components.fallbackPricing();
            solveSettings = components.solveSettings();
            overflowPipeline = components.overflowPipeline();
            largeDropProtection = components.dropProtection();
            creativeItemProtection = components.creativeProtection();
            scanPipeline = components.pipeline();
            scanManager = components.scanManager();
            commandService = components.commandService();
            SudoemcCommands.setService(commandService);
            pendingReload = false;
            LOGGER.info("SudoersEMC 运行时组件已重建（{}）", label);
            return true;
        } catch (RuntimeException failure) {
            lastReloadDecision = ReloadDecision.REJECT;
            LOGGER.warn("SudoersEMC 重建运行时组件失败（{}），保留上一份有效配置", label, failure);
            return false;
        }
    }

    /**
     * 把布尔重建结果细分为命令层需要的三种决策。
     *
     * @param reason 触发原因，仅用于日志
     * @return 立即生效、延迟到扫描结束或拒绝
     */
    private static ReloadDecision requestReload(String reason) {
        boolean rebuilt = rebuildRuntimeFromConfig(reason);
        if (!rebuilt) {
            return ReloadDecision.REJECT;
        }
        return lastReloadDecision;
    }

    /**
     * 在扫描结束后执行一次延迟重建。
     *
     * <p>只由服务器 tick 调用。重建失败时清除待处理标记并记录警告，避免每 tick 重复尝试
     * 非法配置；成功路径由 {@link #rebuildRuntimeFromConfig(String)} 自行清除标记。</p>
     */
    private static void processPendingReload() {
        if (!pendingReload) {
            return;
        }
        ScanManager manager = scanManager;
        if (manager != null && manager.isRunning()) {
            return;
        }
        if (!rebuildRuntimeFromConfig("pending-scan-end")) {
            pendingReload = false;
            LOGGER.warn("SudoersEMC 扫描结束后的延迟配置重建失败，已清除待处理标记并继续使用旧组件");
        }
    }

    /**
     * 构建一组全新的运行时组件，不修改任何静态字段。
     *
     * <p>调用方在全部构建成功后再一次性发布，保证重建的“全有或全无”语义。</p>
     */
    private static RuntimeComponents createRuntimeComponents(GeneratedEntryStore store,
                                                             ScanStateStore stateStore,
                                                             ReportManager reports) {
        OverflowPipeline newOverflowPipeline = createOverflowPipeline(overflowResetLog);
        LargeDropProtection newDropProtection = createDropProtection();
        CreativeItemProtection newCreativeProtection = createCreativeProtection();
        FallbackPricing newFallbackPricing = createFallbackPricing();
        SolveSettings newSolveSettings = createSolveSettings();
        ScanPipeline pipeline = createScanPipeline(
                store,
                stateStore,
                reports,
                newOverflowPipeline,
                newDropProtection,
                newCreativeProtection,
                newFallbackPricing,
                newSolveSettings);
        ScanManager manager = createScanManager(pipeline);
        CommandService service = new CommandService(
                manager,
                reports,
                store,
                stateStore,
                () -> requestReload("command"));
        return new RuntimeComponents(
                newFallbackPricing,
                newSolveSettings,
                newOverflowPipeline,
                newDropProtection,
                newCreativeProtection,
                pipeline,
                manager,
                service);
    }

    /**
     * 创建溢出保护管线，复用传入的审计日志实例。
     *
     * <p>配置读取失败时退回该组件的安全默认值，绝不阻止模组启动。</p>
     *
     * @param overflowLog 跨重建保留的审计日志；{@code null} 时新建仅供本次使用的实例
     */
    private static OverflowPipeline createOverflowPipeline(@Nullable OverflowResetLog overflowLog) {
        OverflowProtection.Settings overflowSettings;
        try {
            overflowSettings = SudoersemcConfig.overflowSettings();
        } catch (RuntimeException e) {
            // 配置尚未加载或跨字段校验失败时，使用自带默认值。
            LOGGER.warn("SudoersEMC 无法读取溢出保护配置，使用默认值", e);
            overflowSettings = new OverflowProtection.Settings();
        }
        OverflowResetLog auditLog = overflowLog == null ? new OverflowResetLog() : overflowLog;
        return new OverflowPipeline(new OverflowProtection(overflowSettings), auditLog);
    }

    /**
     * 创建大幅降价保护；配置读取失败时使用组件默认值。
     */
    private static LargeDropProtection createDropProtection() {
        LargeDropProtection.Settings settings;
        try {
            settings = new LargeDropProtection.Settings(
                    SudoersemcConfig.SAFETY_LARGE_DROP_PROTECTION.get(),
                    SudoersemcConfig.SAFETY_MAX_ALLOWED_DROP_RATIO.get(),
                    Set.of(),
                    Set.of());
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取大幅降价保护配置，使用默认值", e);
            settings = LargeDropProtection.Settings.defaults();
        }
        return new LargeDropProtection(settings);
    }

    /**
     * 创建创造物品保护；配置读取失败时默认启用保护。
     *
     * <p>三个列表键由 {@link CreativeItemProtection#parseSettings} 容错解析：无法识别的条目
     * 会被跳过，并在最后汇总为一条警告，避免单条拼写错误阻止模组启动或静默失效。</p>
     */
    private static CreativeItemProtection createCreativeProtection() {
        try {
            boolean protectCreativeOnly = SudoersemcConfig.SAFETY_PROTECT_CREATIVE_ONLY_ITEMS.get();
            CreativeItemProtection.ParsedSettings parsed = CreativeItemProtection.parseSettings(
                    protectCreativeOnly,
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
     * 创建兜底定价链；配置读取失败时使用默认定价开关。
     */
    private static FallbackPricing createFallbackPricing() {
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
        return FallbackPricing.withDefaults(pricingSettings);
    }

    /**
     * 创建配方求解参数，并把 {@code fallback.minEmc} 接入 {@link SolveSettings#minimumEmc()}。
     */
    private static SolveSettings createSolveSettings() {
        try {
            return new SolveSettings(
                    Math.max(1, SudoersemcConfig.RECIPE_MAX_RECIPE_PASSES.get()),
                    SudoersemcConfig.RECIPE_ALLOW_RECIPE_VALUE_INCREASE.get(),
                    Math.max(0L, SudoersemcConfig.FALLBACK_MIN_EMC.get()),
                    SolveSettings.DEFAULT_MAX_ALTERNATIVE_COSTS);
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取配方求解配置，使用默认值", e);
            return SolveSettings.defaults();
        }
    }

    /**
     * 创建真实扫描管线并应用与配置相关的运行开关。
     *
     * <p>所有安全层、定价策略与求解参数都由调用方构造；本方法只负责组合并设置
     * 每次重建后需要立即生效的开关：配方求解、诊断报告、溢出作用域与 ProjectE 文件改写。</p>
     */
    private static ScanPipeline createScanPipeline(GeneratedEntryStore store,
                                                   ScanStateStore stateStore,
                                                   ReportManager reports,
                                                   OverflowPipeline newOverflowPipeline,
                                                   LargeDropProtection newDropProtection,
                                                   CreativeItemProtection newCreativeProtection,
                                                   FallbackPricing newFallbackPricing,
                                                   SolveSettings newSolveSettings) {
        ScanPipeline pipeline = new ScanPipeline(
                store,
                stateStore,
                reports,
                newDropProtection,
                newCreativeProtection,
                newOverflowPipeline,
                newFallbackPricing,
                newSolveSettings);
        pipeline.setEmcProvider(OptionalIntegrationManager.provider().orElse(null));

        OverflowScope overflowScope = OverflowScope.OWNED_ONLY;
        boolean allowProjectEFileRewrite = false;
        try {
            overflowScope = SudoersemcConfig.parseOverflowScope(
                    SudoersemcConfig.OVERFLOW_OVERWRITE_SCOPE.get());
            allowProjectEFileRewrite = SudoersemcConfig.OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get();
        } catch (RuntimeException e) {
            // 配置尚未加载或读取失败时回退到最保守的组合，绝不阻止模组启动。
            LOGGER.warn("SudoersEMC 无法读取溢出作用域配置，回退 owned_only 并禁用 ProjectE 文件改写", e);
        }
        pipeline.setOverflowScope(overflowScope);
        pipeline.setProjectEFileRewriteEnabled(allowProjectEFileRewrite);
        pipeline.setSolveRecipesEnabled(readBoolean(
                "recipe.solveRecipes", SudoersemcConfig.RECIPE_SOLVE_RECIPES, true));
        pipeline.setGenerateReportsEnabled(readBoolean(
                "diagnostic.generateReports", SudoersemcConfig.DIAGNOSTIC_GENERATE_REPORTS, true));
        pipeline.setNbtVariantsEnabled(readBoolean(
                "nbt.scanNbtVariants", SudoersemcConfig.NBT_SCAN_NBT_VARIANTS, true));
        pipeline.setMaxNbtVariantsPerItem(readInt(
                "nbt.maxNbtVariantsPerItem", SudoersemcConfig.NBT_MAX_NBT_VARIANTS_PER_ITEM, 100));
        return pipeline;
    }

    /**
     * 读取一个布尔配置开关；读取失败时记录警告并返回给定默认值。
     */
    private static boolean readBoolean(String key, ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取 {} 配置，使用默认值 {}", key, fallback, e);
            return fallback;
        }
    }

    /**
     * 读取一个整数配置值；读取失败时记录警告并返回给定默认值。
     */
    private static int readInt(String key, ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取 {} 配置，使用默认值 {}", key, fallback, e);
            return fallback;
        }
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
        pipeline.applyStashed();

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

    private static void onModConfigEvent(ModConfigEvent event) {
        if (event == null || event.getConfig() == null
                || event.getConfig().getSpec() != SudoersemcConfig.SPEC) {
            return;
        }
        if (entryStore == null || reportManager == null) {
            // 配置可能在 common setup 之前加载；此时没有可重建的组件，只记录调试信息。
            LOGGER.debug("SudoersEMC 配置事件早于 common setup，跳过运行时重建");
            return;
        }

        ConfigValidationResult result;
        try {
            result = ConfigValidator.validateCurrent();
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 配置事件校验失败，保留现有组件", failure);
            return;
        }
        CONFIG_WATCHER.fireReload(result);
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
        processPendingReload();
    }

    private void onServerStarted(ServerStartedEvent event) {
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
            ScanManager manager = scanManager;
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

    /**
     * 一次重建产生的全部运行时组件。
     *
     * <p>只有全部组件都构建成功后，调用方才把这些引用一次性发布到静态字段，避免出现
     * “新管线搭配旧调度器”之类的半更新状态。</p>
     */
    private record RuntimeComponents(
            FallbackPricing fallbackPricing,
            SolveSettings solveSettings,
            OverflowPipeline overflowPipeline,
            LargeDropProtection dropProtection,
            CreativeItemProtection creativeProtection,
            ScanPipeline pipeline,
            ScanManager scanManager,
            CommandService commandService
    ) {
    }
}
