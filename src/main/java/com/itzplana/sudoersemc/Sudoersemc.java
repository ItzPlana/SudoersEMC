package com.itzplana.sudoersemc;

import com.itzplana.sudoersemc.command.CommandService;
import com.itzplana.sudoersemc.command.SudoemcCommands;
import com.itzplana.sudoersemc.config.ConfigTomlMigration;
import com.itzplana.sudoersemc.config.ConfigValidationResult;
import com.itzplana.sudoersemc.config.ConfigValidator;
import com.itzplana.sudoersemc.config.ConfigWatcher;
import com.itzplana.sudoersemc.config.ReloadDecision;
import com.itzplana.sudoersemc.config.RuntimeConfigSnapshot;
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
import com.itzplana.sudoersemc.pipeline.PipelineSnapshot;
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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Function;
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

    /** 跨实例共享的重映射确认状态；配置重载会重建管线，失败保护不能随旧实例丢失。 */
    private static final ScanPipeline.RemapTracker REMAP_TRACKER = new ScanPipeline.RemapTracker();

    /** 运行时组件替换与扫描请求共用的锁。 */
    private static final Object RUNTIME_LOCK = new Object();

    /** 已应用到运行时组件的配置快照代次，用于去重同一代次的重建请求。 */
    private static volatile long appliedSnapshotGeneration;

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

            if (rebuildRuntimeFromConfig("common-setup") == ReloadDecision.REJECT) {
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
                        Sudoersemc::minimumEmcForApproval,
                        Sudoersemc::overflowThresholdForApproval);
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
    static synchronized ReloadDecision rebuildRuntimeFromConfig(String reason) {
        String label = reason == null || reason.isBlank() ? "unknown" : reason;
        GeneratedEntryStore store = entryStore;
        QuarantinedDropsStore quarantineStore = quarantinedDropsStore;
        ScanStateStore stateStore = scanStateStore;
        ReportManager reports = reportManager;
        if (store == null || stateStore == null || reports == null) {
            lastReloadDecision = ReloadDecision.REJECT;
            LOGGER.debug("SudoersEMC 配置重建跳过（{}）：common setup 尚未完成", label);
            return ReloadDecision.REJECT;
        }

        ConfigValidationResult validation;
        try {
            // 已发布的配置快照优先：配置事件路径会把同一份快照交给重建，
            // 此时再读取“当前 Forge 配置”既冗余又可能在测试/重载窗口中不一致。
            validation = SudoersemcConfig.activeSnapshot() != null
                    ? ConfigValidationResult.ok()
                    : ConfigValidator.validateCurrent();
        } catch (RuntimeException failure) {
            lastReloadDecision = ReloadDecision.REJECT;
            LOGGER.warn("SudoersEMC 配置重建校验异常（{}），保留上一份有效配置", label, failure);
            return ReloadDecision.REJECT;
        }
        RuntimeConfigSnapshot activeSnapshot = SudoersemcConfig.activeSnapshot();
        long generation = activeSnapshot == null ? 0L : activeSnapshot.generation();
        if (scanPipeline != null && generation > 0L && generation <= appliedSnapshotGeneration) {
            LOGGER.debug("SudoersEMC 配置快照 generation={} 不新于已应用 generation={}，跳过重复重建（{}）",
                    generation, appliedSnapshotGeneration, label);
            return lastReloadDecision == ReloadDecision.REJECT
                    ? ReloadDecision.REJECT : ReloadDecision.APPLY_NOW;
        }

        boolean scanRunning = scanManager != null && scanManager.isRunning();
        ReloadDecision decision = ReloadDecision.decide(scanRunning, validation.valid());
        lastReloadDecision = decision;

        if (decision == ReloadDecision.REJECT) {
            // fireReload 负责输出一条去重后的汇总警告，其中已包含全部警告文本。
            CONFIG_WATCHER.fireReload(validation);
            return ReloadDecision.REJECT;
        }
        CONFIG_WATCHER.logConflictWarnings(validation);
        if (decision == ReloadDecision.DEFER) {
            pendingReload = true;
            LOGGER.info("SudoersEMC 扫描进行中，配置重建已延后（{}），将在扫描结束后应用", label);
            return ReloadDecision.DEFER;
        }

        try {
            RuntimeComponents components =
                    createRuntimeComponents(store, quarantineStore, stateStore, reports);
            fallbackPricing = components.fallbackPricing();
            solveSettings = components.solveSettings();
            overflowPipeline = components.overflowPipeline();
            largeDropProtection = components.dropProtection();
            creativeItemProtection = components.creativeProtection();
            appliedSnapshotGeneration = generation;
            ScanManager previousManager = scanManager;
            scanPipeline = components.pipeline();
            scanManager = components.scanManager();
            commandService = components.commandService();
            SudoemcCommands.setService(commandService);
            pendingReload = false;
            if (previousManager != null && previousManager != scanManager) {
                // 旧调度器被替换后必须退役，避免遗留引用继续推进状态机。
                previousManager.retire();
            }
            LOGGER.info("SudoersEMC 运行时组件已重建（{}）", label);
            return ReloadDecision.APPLY_NOW;
        } catch (RuntimeException failure) {
            lastReloadDecision = ReloadDecision.REJECT;
            LOGGER.warn("SudoersEMC 重建运行时组件失败（{}），保留上一份有效配置", label, failure);
            return ReloadDecision.REJECT;
        }
    }

    /**
     * 在锁内检查当前调度器并请求扫描，避免与配置重建交错。
     *
     * @return 成功启动返回 {@code true}
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
     * 把布尔重建结果细分为命令层需要的三种决策。
     *
     * @param reason 触发原因，仅用于日志
     * @return 立即生效、延迟到扫描结束或拒绝
     */
    private static ReloadDecision requestReload(String reason) {
        return rebuildRuntimeFromConfig(reason);
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
        if (rebuildRuntimeFromConfig("pending-scan-end") == ReloadDecision.REJECT) {
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
        boolean scanEnabled = readBoolean("general.enabled", SudoersemcConfig.GENERAL_ENABLED, true);
        ScanManager manager = scanEnabled ? createScanManager(pipeline) : null;
        CommandService service = new CommandService(
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
                Sudoersemc::minimumEmcForApproval,
                Sudoersemc::overflowThresholdForApproval);
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
        // Phase 23/24：五通道指纹与跨实例重映射确认必须随每次重建注入。
        pipeline.setInputFingerprintSupplier(Sudoersemc::currentScanFingerprint);
        pipeline.setRemapTracker(REMAP_TRACKER);
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
     * 读取审批应用允许的最高 EMC。
     *
     * <p>溢出保护关闭时返回 {@link Long#MAX_VALUE}（与扫描行为一致，不再限制）；读取失败时
     * 回退到内置阈值 {@link OverflowProtection#DEFAULT_THRESHOLD}，宁可拒绝也不绕过保护。</p>
     */
    private static long overflowThresholdForApproval() {
        try {
            OverflowProtection.Settings settings = SudoersemcConfig.overflowSettings();
            return settings.enabled() ? settings.threshold() : Long.MAX_VALUE;
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取 overflow 配置，审批应用使用默认阈值 "
                    + OverflowProtection.DEFAULT_THRESHOLD, e);
            return OverflowProtection.DEFAULT_THRESHOLD;
        }
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
        ScanDiagnostics diagnostics = new ScanDiagnostics();
        ScanCancellation cancellation = new ScanCancellation();
        if (pipeline != null) {
            pipeline.setDiagnostics(diagnostics);
            // 调度器与管线共享同一个取消令牌：cancel() 后采集/求解/应用循环都能在检查点提前退出。
            pipeline.setCancellationCheck(cancellation::isRequested);
        }
        return new ScanManager(
                processor,
                () -> ScanManager.oneShot(collectSnapshot(pipeline)).begin(),
                data -> applySnapshot(pipeline),
                TICK_BUDGET_NANOS,
                diagnostics,
                (phase, progress, telemetry) -> {
                    if (phase == ScanPhase.CANCELLED) {
                        pipeline.writeCancelledReport(telemetry);
                    } else if (phase == ScanPhase.FAILED) {
                        pipeline.writeFailedReport(progress.detail(), telemetry);
                    }
                },
                cancellation);
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
     * 计算本次扫描的完整输入指纹（五通道）。
     *
     * @return 非空指纹
     * @throws IllegalStateException 服务器、配方管理器或扫描管线尚未就绪时抛出
     */
    static ScanFingerprint currentScanFingerprint() {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            throw new IllegalStateException("服务器尚未就绪，无法计算扫描指纹");
        }
        if (server.getRecipeManager() == null) {
            throw new IllegalStateException("配方管理器尚未就绪，无法计算扫描指纹");
        }
        ScanPipeline pipeline = scanPipeline;
        if (pipeline == null) {
            throw new IllegalStateException("扫描管线尚未就绪，无法计算扫描指纹");
        }
        ScanFingerprint fingerprint = pipeline.collectFingerprint(server);
        if (fingerprint == null) {
            throw new IllegalStateException("无法计算扫描指纹");
        }
        return fingerprint;
    }

    /**
     * @param enabled      是否启用自动扫描
     * @param alwaysRescan 是否总是重扫
     */
    record StartupScanSettings(boolean enabled, boolean alwaysRescan) {

        /**
         * 读取启动自动扫描配置。任一读取失败都按禁用处理（fail-closed）。
         *
         * @param enabledSupplier      读取器
         * @param alwaysRescanSupplier 读取器
         * @return 启动自动扫描配置
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
     * 把已校验的溢出配置快照应用到扫描管线。
     *
     * @param pipeline 扫描管线，不能为 {@code null}
     * @param settings 已校验的溢出配置
     */
    static void applyOverflowSettings(ScanPipeline pipeline,
                                      OverflowProtection.Settings settings) {
        pipeline.setOverflowScope(settings.scope());
        pipeline.setProjectEFileRewriteEnabled(settings.allowProjectEFileRewrite());
    }

    /**
     * 把配方求解与诊断报告开关应用到管线。
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
     * 判断启动缓存是否可以复用。
     *
     * @param stateStore  扫描状态存储，可为 {@code null}
     * @param store       生成值存储，可为 {@code null}
     * @param fingerprint 当前完整输入指纹，可为 {@code null}
     * @return 可以跳过启动扫描时返回 {@code true}
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
     * 把配置中的最大允许降幅夹到 {@link LargeDropProtection.Settings} 接受的 {@code (0.0, 1.0]} 区间。
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
     * 统计归 SudoersEMC 所有的已生成条目数量。
     *
     * @param store 生成值存储，可为 {@code null}
     * @return 自有条目数量；读取失败时按 0 处理
     */
    static int countOwnedGeneratedEntries(@Nullable GeneratedEntryStore store) {
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
     * 兼容测试与命令入口的开关判断：关闭时返回 {@code null}。
     */
    static ScanManager createScanManagerIfEnabled(BooleanSupplier enabledSupplier) {
        boolean enabled = true;
        try {
            enabled = enabledSupplier.getAsBoolean();
        } catch (RuntimeException e) {
            LOGGER.warn("SudoersEMC 无法读取 [general] enabled，按启用处理", e);
        }
        return enabled ? createScanManager(null) : null;
    }

    /**
     * 兼容测试的启动扫描开关判断。
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
     * 停服时取消并重置调度器。
     */
    static void resetScanManager(ScanManager manager) {
        if (manager != null) {
            manager.cancel();
            manager.reset();
        }
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
        resetScanManager(scanManager);
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
            CommandService commandService
    ) {
    }

    /**
     * 与生产接线一致的异步应用器：提交暂存求解并轮询完成。
     */
    public static final class PipelineApplier implements ScanManager.ResultApplier {

        @Nullable
        private final ScanPipeline pipeline;
        private boolean started;
        private boolean finished;

        PipelineApplier(@Nullable ScanPipeline pipeline) {
            this.pipeline = pipeline;
        }

        @Override
        public void apply(ScanManager.SnapshotData data) {
            if (finished || pipeline == null) {
                return;
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
        public void onCompleted() {
            if (pipeline != null) {
                pipeline.writePendingReport();
            }
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
     * 配方内容通道：每个配方一条稳定字符串（id + 类型 + 输入候选）。
     *
     * @param manager    配方管理器，可为 {@code null}
     * @param registries 注册表访问，可为 {@code null}
     * @return 每个配方一条稳定字符串
     */
    static List<String> recipeContentEntries(@Nullable RecipeManager manager,
                                             @Nullable net.minecraft.core.RegistryAccess registries) {
        if (manager == null) {
            return List.of();
        }
        List<String> entries = new ArrayList<>();
        for (ResourceLocation id : manager.getRecipeIds().sorted().toList()) {
            Optional<? extends Recipe<?>> maybeRecipe = manager.byKey(id);
            if (maybeRecipe.isEmpty()) {
                entries.add(id + "|missing");
                continue;
            }
            Recipe<?> recipe = maybeRecipe.get();
            StringBuilder entry = new StringBuilder(id.toString())
                    .append('|').append(recipe.getClass().getName());
            for (Ingredient ingredient : recipe.getIngredients()) {
                entry.append('|');
                if (ingredient == null || ingredient.isEmpty()) {
                    entry.append('-');
                    continue;
                }
                List<String> candidates = new ArrayList<>();
                for (ItemStack stack : ingredient.getItems()) {
                    if (stack.isEmpty()) {
                        continue;
                    }
                    ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                    if (key != null) {
                        candidates.add(key.toString());
                    }
                }
                java.util.Collections.sort(candidates);
                entry.append(String.join(",", candidates));
            }
            entries.add(entry.toString());
        }
        return List.copyOf(entries);
    }

    /**
     * 标签成员通道：每个标签一条稳定字符串（标签 id + 排序后的成员 id）。
     *
     * @param tags    标签流
     * @param members 标签成员读取器
     * @return 每个标签一条稳定字符串
     */
    static List<String> tagContentEntries(Stream<TagKey<Item>> tags,
                                          Function<TagKey<Item>, List<Item>> members) {
        List<String> entries = new ArrayList<>();
        tags.forEach(tag -> {
            List<String> ids = new ArrayList<>();
            for (Item item : members.apply(tag)) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null) {
                    ids.add(id.toString());
                }
            }
            java.util.Collections.sort(ids);
            entries.add(tag.location() + "|" + String.join(",", ids));
        });
        return List.copyOf(entries);
    }
}
