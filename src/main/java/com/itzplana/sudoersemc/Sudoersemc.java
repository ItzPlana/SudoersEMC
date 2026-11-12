package com.itzplana.sudoersemc;

import com.itzplana.sudoersemc.command.CommandService;
import com.itzplana.sudoersemc.command.SudoemcCommands;
import com.itzplana.sudoersemc.config.ConfigTomlMigration;
import com.itzplana.sudoersemc.config.ConfigValidationResult;
import com.itzplana.sudoersemc.config.ConfigValidator;
import com.itzplana.sudoersemc.config.ConfigWatcher;
import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.config.SudoersemcConfig;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.diagnostic.DebugLog;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.diagnostic.SmokeTestHook;
import com.itzplana.sudoersemc.integration.ExternalBaselineSource;
import com.itzplana.sudoersemc.integration.GeneratedEmcMapper;
import com.itzplana.sudoersemc.integration.OptionalIntegrationManager;
import com.itzplana.sudoersemc.integration.ProjectEIntegrationBaselineSource;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.PricingSettings;
import com.itzplana.sudoersemc.resolver.ResolverRegistry;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.AutoScanPolicy;
import com.itzplana.sudoersemc.task.BatchProcessor;
import com.itzplana.sudoersemc.task.ScanCancellation;
import com.itzplana.sudoersemc.task.ScanDiagnostics;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.itzplana.sudoersemc.task.ScanManager;
import com.itzplana.sudoersemc.task.ScanPhase;
import com.itzplana.sudoersemc.task.SolveWorker;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
import net.minecraftforge.fml.loading.FMLPaths;
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

    /** Forge 为 COMMON 配置生成的默认文件名。 */
    private static final String COMMON_CONFIG_FILE_NAME = "sudoersemc-common.toml";

    /** 单 tick 分配给扫描工作的时间预算，1 毫秒。 */
    private static final long TICK_BUDGET_NANOS = 1_000_000L;

    /** 配置事件与命令共用的重载协调器；失败摘要与冲突警告由它去重。 */
    private static final ConfigWatcher CONFIG_WATCHER = new ConfigWatcher();

    /**
     * 进程级 resolver 注册表。第三方模组在构造器/common setup 注册的解析器必须跨配置重建存活，
     * 因此绝不能每次重建都新建实例。
     */
    private static final ResolverRegistry RESOLVER_REGISTRY = new ResolverRegistry();

    /** 进程级 ProjectE-Integration 外部基线来源；配置关闭时改用不可用实例。 */
    private static final ExternalBaselineSource PROJECTE_INTEGRATION_BASELINE =
            new ProjectEIntegrationBaselineSource();

    private static final AtomicBoolean BASELINE_CONFIG_WARNED = new AtomicBoolean(false);

    static {
        CONFIG_WATCHER.register(result -> rebuildRuntimeFromConfig("config-event"));
    }

    private static volatile GeneratedEntryStore entryStore;
    private static volatile QuarantinedDropsStore quarantinedDropsStore;
    private static volatile ScanStateStore scanStateStore;
    private static volatile ReportManager reportManager;
    private static volatile ScanManager scanManager;
    private static volatile SolveWorker solveWorker;
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

        // 真正的“解析前迁移”：Forge 会在 ModConfigEvent 之前按 spec 重写 TOML（补 schemaVersion、
        // 丢注释与未知键）。因此在 register() 之前直接对磁盘文件做一次幂等迁移。文件不存在时
        // SKIPPED，绝不抢先创建。事件路径保留作为 reload/兜底。
        migrateConfigBeforeForgeLoads();

        // 必须在构造阶段注册，Forge 之后才会加载配置。
        SudoersemcConfig.register();

        LOGGER.info("SudoersEMC is loading");
    }

    /**
     * 在 Forge 解析/重写 TOML 之前迁移磁盘上的 COMMON 配置文件。
     *
     * <p>Forge 会在 {@code ModConfigEvent} 之前按 spec 补键并重写文件，可能丢失注释与未知键；
     * 这个入口是唯一能保留原始文件内容的时机。迁移是幂等的：文件已是最新版本时返回
     * {@code NO_OP}，文件不存在时返回 {@code SKIPPED}，绝不抢先创建。</p>
     */
    private static void migrateConfigBeforeForgeLoads() {
        try {
            Path configPath = FMLPaths.CONFIGDIR.get().resolve(COMMON_CONFIG_FILE_NAME);
            ConfigTomlMigration.MigrationOutcome outcome =
                    ConfigTomlMigration.migrate(configPath);
            if (outcome.status() == ConfigTomlMigration.Status.MIGRATED) {
                LOGGER.info("SudoersEMC 已在 Forge 解析前完成配置迁移：schema {} → {}，{}",
                        outcome.fromVersion(), outcome.toVersion(), outcome.notes());
            } else if (outcome.status() == ConfigTomlMigration.Status.REFUSED_NEWER) {
                LOGGER.error("SudoersEMC 配置版本高于当前构建，解析前迁移已拒绝：{}",
                        outcome.notes());
            } else if (outcome.status() == ConfigTomlMigration.Status.FAILED_RETAINED) {
                LOGGER.warn("SudoersEMC 解析前配置迁移失败，保留原文件：{}", outcome.notes());
            } else {
                LOGGER.debug("SudoersEMC 解析前配置迁移无需执行：{}", outcome.status());
            }
        } catch (Throwable failure) {
            LOGGER.warn("SudoersEMC 解析前配置迁移异常，继续交给 Forge 处理", failure);
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            GeneratedEntryStore store = new GeneratedEntryStore();
            store.load();
            entryStore = store;

            ScanStateStore stateStore = new ScanStateStore();
            stateStore.load();
            scanStateStore = stateStore;

            QuarantinedDropsStore quarantineStore = new QuarantinedDropsStore();
            quarantineStore.load();
            quarantinedDropsStore = quarantineStore;

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
                        () -> requestReload("command"),
                        quarantineStore,
                        OptionalIntegrationManager.provider().orElse(null),
                        OptionalIntegrationManager.manualValues(),
                        approveAppliesImmediately(),
                        exclusionDetectorSupplier(),
                        Sudoersemc::minimumEmcForApproval);
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
        QuarantinedDropsStore quarantineStore = quarantinedDropsStore;
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
        boolean scanRunning = scanManager != null && scanManager.isRunning()
                && solveWorker != null && !solveWorker.isShutdown();
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
            RuntimeComponents components =
                    createRuntimeComponents(store, quarantineStore, stateStore, reports);
            SolveWorker previousWorker = solveWorker;
            fallbackPricing = components.fallbackPricing();
            solveSettings = components.solveSettings();
            overflowPipeline = components.overflowPipeline();
            largeDropProtection = components.dropProtection();
            creativeItemProtection = components.creativeProtection();
            scanPipeline = components.pipeline();
            solveWorker = components.solveWorker();
            scanManager = components.scanManager();
            commandService = components.commandService();
            SudoemcCommands.setService(commandService);
            // 旧 worker 只在所有新组件都构建并发布成功后关闭，避免半更新状态。
            if (previousWorker != null && previousWorker != components.solveWorker()) {
                previousWorker.shutdownNow();
            }
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
                                                             @Nullable QuarantinedDropsStore quarantineStore,
                                                             ScanStateStore stateStore,
                                                             ReportManager reports) {
        OverflowPipeline newOverflowPipeline = createOverflowPipeline(overflowResetLog);
        LargeDropProtection newDropProtection = createDropProtection();
        CreativeItemProtection newCreativeProtection = createCreativeProtection();
        FallbackPricing newFallbackPricing = createFallbackPricing();
        SolveSettings newSolveSettings = createSolveSettings();
        ScanPipeline pipeline = createScanPipeline(
                store,
                quarantineStore,
                stateStore,
                reports,
                newOverflowPipeline,
                newDropProtection,
                newCreativeProtection,
                newFallbackPricing,
                newSolveSettings);
        SolveWorker worker = new SolveWorker(pipeline::solveOnly);
        ScanManager manager;
        CommandService service;
        try {
            manager = createScanManager(pipeline, worker);
            service = new CommandService(
                    manager,
                    reports,
                    store,
                    stateStore,
                    () -> requestReload("command"),
                    quarantineStore,
                    OptionalIntegrationManager.provider().orElse(null),
                    OptionalIntegrationManager.manualValues(),
                    approveAppliesImmediately(),
                    () -> pipeline::isExcludedFromPricing,
                    Sudoersemc::minimumEmcForApproval);
        } catch (RuntimeException failure) {
            // 构建失败时关闭刚创建的线程池，避免配置重建路径泄漏守护线程。
            worker.shutdownNow();
            throw failure;
        }
        return new RuntimeComponents(
                newFallbackPricing,
                newSolveSettings,
                newOverflowPipeline,
                newDropProtection,
                newCreativeProtection,
                pipeline,
                manager,
                service,
                worker);
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
            LargeDropProtection.ParsedSettings parsed = LargeDropProtection.parseSettings(
                    SudoersemcConfig.SAFETY_LARGE_DROP_PROTECTION.get(),
                    SudoersemcConfig.SAFETY_MAX_ALLOWED_DROP_RATIO.get(),
                    List.copyOf(SudoersemcConfig.SAFETY_DROP_PROTECTION_EXEMPT_NAMESPACES.get()),
                    List.copyOf(SudoersemcConfig.SAFETY_DROP_PROTECTION_EXEMPT_ITEMS.get()));
            if (!parsed.skippedEntries().isEmpty()) {
                LOGGER.warn("SudoersEMC 降价豁免配置有 {} 条无效条目已被忽略：{}",
                        parsed.skippedEntries().size(), parsed.skippedEntries());
            }
            settings = parsed.settings();
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
                    List.copyOf(SudoersemcConfig.SAFETY_CREATIVE_NAMESPACES.get()),
                    SudoersemcConfig.SAFETY_AUTO_DETECT_CREATIVE_ONLY_ITEMS.get(),
                    List.copyOf(SudoersemcConfig.SAFETY_CREATIVE_ONLY_ITEM_PATTERNS.get()),
                    List.copyOf(SudoersemcConfig.SAFETY_AUTO_DETECT_CREATIVE_ONLY_NAMESPACES.get()));
            if (!parsed.skippedEntries().isEmpty()) {
                LOGGER.warn("SudoersEMC 创造物品保护配置有 {} 条无效条目已被忽略：{}",
                        parsed.skippedEntries().size(), parsed.skippedEntries());
            }
            return new CreativeItemProtection(parsed.settings());
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取创造物品保护配置，默认启用保护", e);
            return new CreativeItemProtection(new CreativeItemProtection.Settings(
                    true, Set.of(), Map.of(), Set.of(),
                    true, Set.copyOf(CreativeItemProtection.defaultAutoDetectPatterns()), Set.of()));
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
                    SolveSettings.DEFAULT_MAX_ALTERNATIVE_COSTS,
                    SudoersemcConfig.parseRecipeSelectionStrategy(
                            SudoersemcConfig.RECIPE_SELECTION_STRATEGY.get()));
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
                                                   @Nullable QuarantinedDropsStore quarantineStore,
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
        pipeline.setQuarantinedDropsStore(quarantineStore);
        pipeline.setConfigFingerprintSupplier(SudoersemcConfig::scanFingerprintEntries);
        pipeline.setBaselineFingerprintSupplier(Sudoersemc::baselineFingerprintEntries);
        pipeline.setExternalBaselineSource(externalBaselineSource());
        pipeline.setResolverRegistry(RESOLVER_REGISTRY);
        applyDebugLogging(readBoolean(
                "diagnostic.debugLogging", SudoersemcConfig.DIAGNOSTIC_DEBUG_LOGGING, false));

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
        pipeline.setScanContainerRemaindersEnabled(readBoolean(
                "recipe.scanContainerRemainders",
                SudoersemcConfig.RECIPE_SCAN_CONTAINER_REMAINDERS, true));
        pipeline.setCreditContainerRemaindersEnabled(readBoolean(
                "recipe.creditContainerRemainders",
                SudoersemcConfig.RECIPE_CREDIT_CONTAINER_REMAINDERS, true));
        pipeline.setPriceSecondaryOutputsEnabled(readBoolean(
                "recipe.priceSecondaryOutputs",
                SudoersemcConfig.RECIPE_PRICE_SECONDARY_OUTPUTS, false));
        pipeline.setPreserveExternallyModifiedValues(readBoolean(
                "safety.preserveExternallyModifiedValues",
                SudoersemcConfig.SAFETY_PRESERVE_EXTERNALLY_MODIFIED_VALUES, true));
        pipeline.setManualValueDetectorSupplier(
                () -> OptionalIntegrationManager.manualValues().snapshotPredicate());
        return pipeline;
    }

    /**
     * 应用调试日志开关。包内可见，便于验证“重建跟随开关”的接线。
     *
     * @param enabled 是否输出诊断调试日志
     */
    static void applyDebugLogging(boolean enabled) {
        DebugLog.setEnabled(enabled);
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
     * 读取“批准后立即应用”开关；读取失败时使用安全默认值 {@code true}（与配置默认值一致）。
     */
    private static boolean approveAppliesImmediately() {
        return readBoolean("safety.approveAppliesImmediately",
                SudoersemcConfig.SAFETY_APPROVE_APPLIES_IMMEDIATELY, true);
    }

    /**
     * 读取审批应用的最小 EMC；读取失败时回退到 1。
     */
    private static long minimumEmcForApproval() {
        try {
            return Math.max(0L, SudoersemcConfig.FALLBACK_MIN_EMC.get());
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取 fallback.minEmc 配置，审批应用使用默认值 1", e);
            return 1L;
        }
    }

    /**
     * 构造审批时使用的定价排除判定器提供者；扫描管线尚未建立时返回 {@code null}。
     */
    private static @Nullable Supplier<Predicate<ItemKey>> exclusionDetectorSupplier() {
        ScanPipeline pipeline = scanPipeline;
        return pipeline == null ? null : () -> pipeline::isExcludedFromPricing;
    }

    /**
     * 创建分片扫描调度器并接入真实管线。
     *
     * <p>采集回调在服务器线程创建增量会话；求解通过 {@link SolveWorker} 在
     * {@code sudoersemc-solver} 线程执行纯计算；应用回调仍在服务器线程完成安全层、存储写入、
     * ProjectE 重映射与落盘。</p>
     *
     * @param pipeline 真实扫描管线
     * @param worker   纯求解工作线程
     * @return 已接线的扫描调度器
     */
    private static ScanManager createScanManager(ScanPipeline pipeline, SolveWorker worker) {
        int itemsPerTick;
        boolean adaptive;
        boolean solveOffThread;
        try {
            itemsPerTick = SudoersemcConfig.PERFORMANCE_ITEMS_PER_TICK.get();
            adaptive = SudoersemcConfig.PERFORMANCE_ADAPTIVE_BATCH_SIZING.get();
            solveOffThread = SudoersemcConfig.PERFORMANCE_SOLVE_OFF_THREAD.get();
        } catch (RuntimeException e) {
            // 配置尚未加载时退回保守默认值，绝不因为读取配置失败而阻止模组启动。
            LOGGER.warn("SudoersEMC 无法读取性能配置，使用默认值 100 / 自适应开启 / 工作线程开启", e);
            itemsPerTick = 100;
            adaptive = true;
            solveOffThread = true;
        }
        BatchProcessor processor = new BatchProcessor(Math.max(1, itemsPerTick), adaptive);
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        pipeline.setDiagnostics(diagnostics);
        // 调度器与管线共享同一个取消令牌：cancel() 后采集/求解/应用循环都能在检查点提前退出。
        ScanCancellation cancellation = new ScanCancellation();
        pipeline.setCancellationCheck(cancellation::isRequested);
        return new ScanManager(
                processor,
                () -> pipeline.beginCollect(ServerLifecycleHooks.getCurrentServer()),
                (snapshot, outcome) -> {
                    int applied = pipeline.applySolved(snapshot, outcome).applied();
                    requestRemapAndSave();
                    return applied;
                },
                TICK_BUDGET_NANOS,
                diagnostics,
                (phase, progress, telemetry) -> {
                    if (phase == ScanPhase.CANCELLED) {
                        pipeline.writeCancelledReport(telemetry);
                    } else if (phase == ScanPhase.FAILED) {
                        pipeline.writeFailedReport(progress.detail(), telemetry);
                    }
                },
                cancellation,
                worker,
                solveOffThread);
    }

    /**
     * 按当前配置选择外部基线来源。配置关闭或读取失败时返回共享的不可用实例。
     */
    private static ExternalBaselineSource externalBaselineSource() {
        try {
            return SudoersemcConfig.FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES.get()
                    ? PROJECTE_INTEGRATION_BASELINE
                    : ExternalBaselineSource.unavailable();
        } catch (RuntimeException failure) {
            if (BASELINE_CONFIG_WARNED.compareAndSet(false, true)) {
                LOGGER.warn("SudoersEMC 无法读取外部基线开关，按禁用处理", failure);
            }
            return ExternalBaselineSource.unavailable();
        }
    }

    /**
     * 构造外部基线的指纹条目：ProjectE 手动 EMC 物品值、手动标签、外部基线来源与 resolver
     * 注册表组成。
     *
     * <p>来源不可用时仍追加固定标记，确保安装/移除 PEI 会改变指纹并触发一次重扫。读取失败
     * 按空处理，绝不影响启动。</p>
     */
    private static List<String> baselineFingerprintEntries() {
        try {
            List<String> entries = new ArrayList<>();
            var manualValues = OptionalIntegrationManager.manualValues();
            for (Map.Entry<com.itzplana.sudoersemc.core.ItemKey, Long> entry
                    : manualValues.manualValues().entrySet()) {
                entries.add("item:" + entry.getKey() + "=" + entry.getValue());
            }
            for (var tagId : manualValues.manualTagIds()) {
                entries.add("tag:#" + tagId);
            }
            entries.add("projecte=" + OptionalIntegrationManager.isProjectEAvailable());

            ExternalBaselineSource source = externalBaselineSource();
            entries.add("external-baseline-id=" + source.id());
            entries.add("external-baseline-available=" + source.isAvailable());
            entries.addAll(source.fingerprintEntries());
            entries.addAll(RESOLVER_REGISTRY.fingerprintEntries());
            return entries;
        } catch (RuntimeException failure) {
            LOGGER.warn("SudoersEMC 读取外部基线指纹失败，按空基线处理", failure);
            return List.of("external-baseline=fingerprint-error");
        }
    }

    /**
     * 应用阶段收尾：请求 ProjectE 重映射并落盘存储与扫描状态。
     *
     * <p>所有步骤都在服务器线程执行。重映射失败只记录警告，不会阻止已经生成的条目写入磁盘。</p>
     */
    private static void requestRemapAndSave() {
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
        // 迁移必须发生在运行时重建之前：Loading 与 Reloading 共用同一入口。迁移自身保证
        // 幂等、高版本拒绝、损坏文件保留与内存值同步。
        ConfigTomlMigration.MigrationOutcome migration =
                ConfigTomlMigration.migrate(event.getConfig());
        if (migration.status() == ConfigTomlMigration.Status.REFUSED_NEWER) {
            LOGGER.error("SudoersEMC 配置版本高于当前构建，已拒绝迁移：{}", migration.notes());
        } else if (migration.status() == ConfigTomlMigration.Status.FAILED_RETAINED) {
            LOGGER.warn("SudoersEMC 配置迁移失败，继续使用当前配置：{}", migration.notes());
        } else if (migration.status() == ConfigTomlMigration.Status.MIGRATED) {
            LOGGER.info("SudoersEMC 配置已从 schema {} 迁移到 {}：{}",
                    migration.fromVersion(), migration.toVersion(), migration.notes());
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
        // 服务器停止时关闭了求解线程池；同一 JVM 再次开服时重建一份，避免复用已关闭的 worker。
        SolveWorker worker = solveWorker;
        if (worker == null || worker.isShutdown()) {
            // 旧 manager 可能仍停留在 SOLVING/COLLECTING；必须先清理，否则重建会被 DEFER 永久阻塞。
            resetScanManagerForShutdown(scanManager);
            rebuildRuntimeFromConfig("server-start-worker");
        }
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

        boolean fingerprintsMatch = false;
        if (enabled && !alwaysRescan && lastScanEpochMillis > 0L && generatedEntryCount > 0
                && stateStore != null && scanPipeline != null) {
            ScanFingerprint current = null;
            try {
                current = scanPipeline.collectFingerprint(event.getServer());
            } catch (RuntimeException failure) {
                // 指纹计算失败一律退化为“需要扫描”，绝不阻止服务器启动。
                LOGGER.warn("SudoersEMC 启动指纹计算异常，退化为需要扫描", failure);
            }
            if (current != null) {
                ScanStateStore.FingerprintComparison comparison = stateStore.compare(current);
                fingerprintsMatch = comparison.matches();
                if (!fingerprintsMatch) {
                    LOGGER.info("SudoersEMC 扫描输入已变化，通道={}",
                            comparison.changedChannels());
                }
            }
        }

        if (!enabled) {
            LOGGER.info("SudoersEMC 自动扫描已禁用（general.enabled=false）");
        } else if (AutoScanPolicy.shouldScan(
                enabled, alwaysRescan, lastScanEpochMillis, generatedEntryCount, fingerprintsMatch)) {
            ScanManager manager = scanManager;
            if (manager == null) {
                LOGGER.warn("SudoersEMC 自动扫描已请求，但扫描调度器尚未就绪（common setup 未完成）");
            } else if (manager.requestScan()) {
                LOGGER.info("SudoersEMC 自动扫描已请求（alwaysRescan={}，上次扫描={}，已生成条目={}，指纹一致={}）",
                        alwaysRescan, lastScanEpochMillis, generatedEntryCount, fingerprintsMatch);
            } else {
                LOGGER.info("SudoersEMC 自动扫描被拒绝：已有扫描正在运行");
            }
        } else {
            LOGGER.info("SudoersEMC 自动扫描被拒绝（输入指纹未变化且已有缓存：上次扫描={}，已生成条目={}）",
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
        QuarantinedDropsStore quarantineStore = quarantinedDropsStore;
        if (quarantineStore != null) {
            quarantineStore.saveIfDirty();
        }
        OverflowResetLog resetLog = overflowResetLog;
        if (resetLog != null) {
            resetLog.save();
        }
        // 关闭纯求解工作线程；下一次开服会由 onServerStarted 重建运行时组件。
        // 先中止并重置调度器：否则旧 manager 会停留在 SOLVING，导致重开服时 rebuild 被永久 DEFER。
        resetScanManagerForShutdown(scanManager);
        SolveWorker worker = solveWorker;
        solveWorker = null;
        if (worker != null) {
            worker.shutdownNow();
        }
    }

    /**
     * 停止并重置扫描调度器，供停服与“worker 已关闭后重开服”两条路径复用。
     *
     * <p>{@link ScanManager#reset()} 会取消未完成的求解 future、丢弃采集会话并把进度恢复为
     * {@link ScanPhase#IDLE}。这样孪生防御确保旧 manager 不会以活动状态阻塞下一次
     * {@code rebuildRuntimeFromConfig}。</p>
     *
     * @param manager 扫描调度器，允许为 {@code null}
     */
    static void resetScanManagerForShutdown(@Nullable ScanManager manager) {
        if (manager == null) {
            return;
        }
        boolean wasRunning = manager.isRunning();
        manager.cancel();
        manager.reset();
        if (wasRunning) {
            LOGGER.info("SudoersEMC 停服/重建前已中止并重置活动扫描，避免复用关闭的求解线程");
        } else {
            LOGGER.debug("SudoersEMC 停服/重建前已重置扫描调度器状态");
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
     * 第三方模组注册 NBT 材料解析器的进程级入口。
     *
     * <p>注册表跨配置重建存活；解析器会在服务器线程被调用，不得阻塞或执行重计算。</p>
     *
     * @return 共享注册表，永不为 {@code null}
     */
    public static ResolverRegistry resolverRegistry() {
        return RESOLVER_REGISTRY;
    }

    /**
     * @return 隔离降价记录存储，common setup 完成前为 {@code null}
     */
    public static QuarantinedDropsStore quarantinedDropsStore() {
        return quarantinedDropsStore;
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
            CommandService commandService,
            SolveWorker solveWorker
    ) {
    }
}
