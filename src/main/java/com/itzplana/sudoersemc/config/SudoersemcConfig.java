package com.itzplana.sudoersemc.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.itzplana.sudoersemc.Sudoersemc;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ConfigFileTypeHandler;
import net.minecraftforge.fml.config.ConfigTracker;
import net.minecraftforge.fml.config.IConfigEvent;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

/**
 * Common Forge configuration for SudoersEMC.
 *
 * <p>The mod entry point only needs to call {@link #register()} once during construction. The
 * configuration file name is derived by Forge from the mod id ({@code config/sudoersemc-common.toml}
 * in the usual Forge layout).</p>
 */
public final class SudoersemcConfig {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    // Field declarations. Values are defined in the static initializer below so that the TOML
    // sections are emitted in the documented order.
    public static final ForgeConfigSpec.BooleanValue GENERAL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue GENERAL_ALWAYS_RESCAN;

    public static final ForgeConfigSpec.IntValue PERFORMANCE_ITEMS_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue PERFORMANCE_ADAPTIVE_BATCH_SIZING;

    public static final ForgeConfigSpec.BooleanValue RECIPE_SOLVE_RECIPES;
    public static final ForgeConfigSpec.IntValue RECIPE_MAX_RECIPE_PASSES;
    public static final ForgeConfigSpec.BooleanValue RECIPE_ALLOW_RECIPE_VALUE_INCREASE;

    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_TAG_PRICING;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_RARITY_PRICING;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES;

    public static final ForgeConfigSpec.BooleanValue SAFETY_LARGE_DROP_PROTECTION;
    public static final ForgeConfigSpec.DoubleValue SAFETY_MAX_ALLOWED_DROP_RATIO;
    public static final ForgeConfigSpec.BooleanValue SAFETY_PROTECT_CREATIVE_ONLY_ITEMS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>>
            SAFETY_CREATIVE_ITEM_EXCLUSIONS;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>>
            SAFETY_CREATIVE_ITEM_SPECIAL_VALUES;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>>
            SAFETY_CREATIVE_NAMESPACES;

    public static final ForgeConfigSpec.BooleanValue OVERFLOW_ENABLED;
    public static final ForgeConfigSpec.LongValue OVERFLOW_THRESHOLD;
    public static final ForgeConfigSpec.LongValue OVERFLOW_RESET_MIN;
    public static final ForgeConfigSpec.LongValue OVERFLOW_RESET_MAX;
    public static final ForgeConfigSpec.IntValue OVERFLOW_BUCKET_COUNT;
    public static final ForgeConfigSpec.IntValue OVERFLOW_WEIGHT_CAP;
    public static final ForgeConfigSpec.ConfigValue<String> OVERFLOW_OVERWRITE_SCOPE;
    public static final ForgeConfigSpec.BooleanValue OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE;
    public static final ForgeConfigSpec.LongValue OVERFLOW_RANDOM_SEED;

    public static final ForgeConfigSpec.BooleanValue NBT_SCAN_NBT_VARIANTS;
    public static final ForgeConfigSpec.IntValue NBT_MAX_NBT_VARIANTS_PER_ITEM;

    public static final ForgeConfigSpec.BooleanValue DIAGNOSTIC_DEBUG_LOGGING;
    public static final ForgeConfigSpec.BooleanValue DIAGNOSTIC_GENERATE_REPORTS;

    public static final ForgeConfigSpec.IntValue SCHEMA_VERSION;

    static {
        // [general]
        BUILDER.comment("General operation switches.").push("general");
        GENERAL_ENABLED = BUILDER.comment(
                        "Master switch for the scan service.",
                        "When false no ScanManager is created: startup auto scanning and manual",
                        "/sudoemc rescan-all are both unavailable.")
                .define("enabled", true);
        GENERAL_ALWAYS_RESCAN = BUILDER.comment(
                        "Rescan all items on every server start instead of reusing cached state.",
                        "Only takes effect when [general] enabled is true.")
                .define("alwaysRescan", false);
        BUILDER.pop();

        // [performance]
        BUILDER.comment("Scanning and processing performance.").push("performance");
        PERFORMANCE_ITEMS_PER_TICK = BUILDER.comment(
                        "Maximum items processed on the server thread per tick.")
                .defineInRange("itemsPerTick", 100, 1, Integer.MAX_VALUE);
        PERFORMANCE_ADAPTIVE_BATCH_SIZING = BUILDER.comment(
                        "Adjust batch sizes based on observed processing time.")
                .define("adaptiveBatchSizing", true);
        BUILDER.pop();

        // [recipe]
        BUILDER.comment("Recipe-derived pricing.").push("recipe");
        RECIPE_SOLVE_RECIPES = BUILDER.comment("Derive EMC values from crafting recipes.")
                .define("solveRecipes", true);
        RECIPE_MAX_RECIPE_PASSES = BUILDER.comment(
                        "Maximum number of recipe solving passes per scan.")
                .defineInRange("maxRecipePasses", 20, 1, Integer.MAX_VALUE);
        RECIPE_ALLOW_RECIPE_VALUE_INCREASE = BUILDER.comment(
                        "Allow a recipe-derived value to be higher than the sum of its inputs.")
                .define("allowRecipeValueIncrease", false);
        BUILDER.pop();

        // [fallback]
        BUILDER.comment("Fallback pricing sources.").push("fallback");
        FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE = BUILDER.comment(
                        "Use a generic fallback value for items no other source can price.")
                .define("useFallbackForUnsolvable", true);
        FALLBACK_USE_TAG_PRICING = BUILDER.comment(
                        "Derive values from item tags and material relationships.",
                        "Category anchors are conservative low-tier floors (INGOT 64, STORAGE_BLOCK 576),",
                        "not per-material values; cheap renewable materials can still be overvalued",
                        "relative to ProjectE values. Use ProjectE custom values for exact pricing.")
                .define("useTagPricing", true);
        FALLBACK_USE_RARITY_PRICING = BUILDER.comment("Derive values from item rarity.")
                .define("useRarityPricing", true);
        FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES = BUILDER.comment(
                        "Use ProjectE integration baselines when available.",
                        "Phase 12 records this switch but has no baseline data source yet; the",
                        "baseline reader is wired in Phase 25. False explicitly passes 0.")
                .define("useProjectEIntegrationBaselines", true);
        BUILDER.pop();

        // [safety]
        BUILDER.comment("Safety guards for generated values.").push("safety");
        SAFETY_LARGE_DROP_PROTECTION = BUILDER.comment(
                        "Protect against unexpectedly large decreases in generated values.")
                .define("largeDropProtection", true);
        SAFETY_MAX_ALLOWED_DROP_RATIO = BUILDER.comment(
                        "Largest allowed relative drop in a generated value, in (0, 1].",
                        "0.0 is rejected because it would quarantine every nonzero drop.")
                .defineInRange("maxAllowedDropRatio", 0.5D, Double.MIN_VALUE, 1.0D);
        SAFETY_PROTECT_CREATIVE_ONLY_ITEMS = BUILDER.comment(
                        "Do not generate survival-obtainable values for creative-only items.")
                .define("protectCreativeOnlyItems", true);
        SAFETY_CREATIVE_ITEM_EXCLUSIONS = BUILDER.comment(
                        "Exact item ids that must never be auto-priced (format: namespace:path).")
                .defineListAllowEmpty("creativeItemExclusions", List.of(),
                        entry -> entry instanceof String);
        SAFETY_CREATIVE_ITEM_SPECIAL_VALUES = BUILDER.comment(
                        "Fixed EMC values that override normal solving (format: namespace:path=positive long).")
                .defineListAllowEmpty("creativeItemSpecialValues", List.of(),
                        entry -> entry instanceof String);
        SAFETY_CREATIVE_NAMESPACES = BUILDER.comment(
                        "Namespaces whose items are treated as creative-only and never auto-priced.")
                .defineListAllowEmpty("creativeNamespaces", List.of(),
                        entry -> entry instanceof String);
        BUILDER.pop();

        // [overflow]
        BUILDER.comment("Economic ceiling and weighted reset behavior.").push("overflow");
        OVERFLOW_ENABLED = BUILDER.comment(
                        "Replace values above the economic ceiling with weighted random resets.")
                .define("enabled", true);
        OVERFLOW_THRESHOLD = BUILDER.comment(
                        "Values strictly greater than this threshold are reset; must be > resetMax.")
                .defineInRange("threshold", 999_999L, 1L, Long.MAX_VALUE);
        OVERFLOW_RESET_MIN = BUILDER.comment(
                        "Inclusive lower bound of the reset range; must be >= 1.")
                .defineInRange("resetMin", 100L, 1L, Long.MAX_VALUE);
        OVERFLOW_RESET_MAX = BUILDER.comment(
                        "Inclusive upper bound of the reset range; must be >= resetMin and < threshold.")
                .defineInRange("resetMax", 999_000L, 1L, Long.MAX_VALUE);
        OVERFLOW_BUCKET_COUNT = BUILDER.comment(
                        "Number of buckets used by the weighted reset sampler; must be >= 1.")
                .defineInRange("bucketCount", 100, 1, Integer.MAX_VALUE);
        OVERFLOW_WEIGHT_CAP = BUILDER.comment(
                        "Maximum weight of a single sampler bucket; must be >= 1.")
                .defineInRange("weightCap", 20, 1, Integer.MAX_VALUE);
        OVERFLOW_OVERWRITE_SCOPE = BUILDER.comment(
                        "Which values overflow protection may reset.",
                        "owned_only: only entries generated and owned by SudoersEMC",
                        "entire_custom_emc: also scan ProjectE's config/ProjectE/custom_emc.json for entries owned by SudoersEMC",
                        "Administrator-configured (non-owned) entries are never rewritten.",
                        "Unknown values fall back to owned_only.")
                .define("overwriteScope", "owned_only");
        OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE = BUILDER.comment(
                        "Explicit opt-in required before SudoersEMC-owned entries in ProjectE's custom_emc.json may be rewritten;",
                        "administrator-configured (non-owned) entries are never rewritten.")
                .define("allowProjectEFileRewrite", false);
        OVERFLOW_RANDOM_SEED = BUILDER.comment(
                        "Sampler seed; 0 requests a fresh random seed at startup.")
                .defineInRange("randomSeed", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        BUILDER.pop();

        // [nbt]
        BUILDER.comment("ItemStack NBT variant scanning.").push("nbt");
        NBT_SCAN_NBT_VARIANTS = BUILDER.comment(
                        "Scan economically relevant NBT variants of items.")
                .define("scanNbtVariants", true);
        // Upper bound mirrors VariantScanner.HARD_VARIANT_CEILING; a larger spec value would be
        // silently clamped by the consumer, so reject it at the config boundary instead.
        NBT_MAX_NBT_VARIANTS_PER_ITEM = BUILDER.comment(
                        "Maximum number of NBT variants scanned per item, in [1, 1000].")
                .defineInRange("maxNbtVariantsPerItem", 100, 1, 1000);
        BUILDER.pop();

        // [diagnostic]
        BUILDER.comment("Diagnostic logging and reports.").push("diagnostic");
        DIAGNOSTIC_DEBUG_LOGGING = BUILDER.comment("Enable verbose diagnostic logging.")
                .define("debugLogging", false);
        DIAGNOSTIC_GENERATE_REPORTS = BUILDER.comment("Write diagnostic reports after scans.")
                .define("generateReports", true);
        BUILDER.pop();

        // Root-level schema version. A missing key is treated as legacy v1 by
        // ConfigFileMigration; newly generated files start at the current version.
        SCHEMA_VERSION = BUILDER.comment(
                        "配置 schema 版本，请勿手动修改。当前构建支持 "
                                + ConfigMigrations.SCHEMA_VERSION + "。")
                .defineInRange("schemaVersion",
                        ConfigMigrations.SCHEMA_VERSION,
                        0,
                        Integer.MAX_VALUE);
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private static final ConfigWatcher CONFIG_WATCHER = new ConfigWatcher();

    /** Built-in conservative settings used until the first valid config snapshot is applied. */
    private static final OverflowProtection.Settings SAFE_DEFAULT_OVERFLOW_SETTINGS =
            new OverflowProtection.Settings();

    private static volatile OverflowProtection.Settings activeOverflowSettings =
            SAFE_DEFAULT_OVERFLOW_SETTINGS;

    private static volatile ConfigValidator.Values activeValues;

    private static volatile ConfigValidationResult lastValidationResult = ConfigValidationResult.ok();

    static {
        CONFIG_WATCHER.register(result -> refreshActiveSnapshots());
    }

    private static boolean registered;

    private SudoersemcConfig() {
    }

    /**
     * Registers the common configuration spec and the load/reload validation hook.
     *
     * <p>Safe to call more than once; only the first call performs registration.</p>
     */
    public static void register() {
        if (registered) {
            return;
        }
        registered = true;
        // Forge parses the TOML after registerConfig(); migrate legacy schema before that.
        ConfigFileMigration.migrate(commonConfigPath());
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(SudoersemcConfig::onConfigLoading);
        modEventBus.addListener(SudoersemcConfig::onConfigReloading);
    }

    /**
     * Returns the last validated overflow settings snapshot.
     *
     * <p>The snapshot is rebuilt only when a config load/reload passes
     * {@link ConfigValidator#validate(ConfigValidator.Values)}. Invalid reloads keep the previous
     * snapshot, and an invalid first load keeps the built-in conservative defaults. Runtime
     * components should use this method instead of reading raw Forge values, otherwise they could
     * observe a configuration that failed validation.</p>
     *
     * @throws IllegalStateException when the config has not been loaded yet
     */
    public static OverflowProtection.Settings overflowSettings() {
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("SudoersemcConfig is not loaded yet");
        }
        return activeOverflowSettings;
    }

    /**
     * Builds a plain-Java validation snapshot from the current Forge values.
     *
     * <p>{@code minEmc} is not a configuration key in this phase: the solver lower bound is the
     * hard-coded {@link SolveSettings#DEFAULT_MINIMUM_EMC}. Exposing it as a config option is a
     * later-phase concern; the validator field stays so the rule can be reused once it is wired.</p>
     *
     * @throws IllegalStateException when the config has not been loaded yet
     */
    public static ConfigValidator.Values validationValues() {
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("SudoersemcConfig is not loaded yet");
        }
        return new ConfigValidator.Values(
                PERFORMANCE_ITEMS_PER_TICK.get(),
                RECIPE_MAX_RECIPE_PASSES.get(),
                SolveSettings.DEFAULT_MINIMUM_EMC,
                OVERFLOW_ENABLED.get(),
                OVERFLOW_THRESHOLD.get(),
                OVERFLOW_RESET_MIN.get(),
                OVERFLOW_RESET_MAX.get(),
                OVERFLOW_BUCKET_COUNT.get(),
                OVERFLOW_WEIGHT_CAP.get(),
                OVERFLOW_OVERWRITE_SCOPE.get(),
                OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get(),
                NBT_MAX_NBT_VARIANTS_PER_ITEM.get(),
                SAFETY_MAX_ALLOWED_DROP_RATIO.get());
    }

    /**
     * @return the most recent validation result; starts as a valid, warning-free result
     */
    public static ConfigValidationResult lastValidationResult() {
        return lastValidationResult;
    }

    /**
     * @return the last snapshot that passed validation and became active; {@code null} before the
     *         first valid load
     */
    public static ConfigValidator.Values activeValues() {
        return activeValues;
    }

    /**
     * Validates the values currently held by the loaded spec.
     *
     * @return validation result; never throws
     */
    public static ConfigValidationResult validateCurrent() {
        try {
            return ConfigValidator.validate(validationValues());
        } catch (RuntimeException failure) {
            return ConfigValidationResult.failed(
                    List.of("无法读取当前配置: " + describe(failure)), List.of());
        }
    }

    /**
     * Re-reads the common config file from disk and applies it to the live spec.
     *
     * <p>This mirrors Forge's own {@code ConfigFileTypeHandler.ConfigWatcher}: the existing
     * {@link CommentedFileConfig} bound to the spec is reloaded, corrected when necessary,
     * accepted by the spec and followed by {@code afterReload()}. A
     * {@code ModConfigEvent.Reloading} event is dispatched on the mod event bus so listeners
     * observe the same lifecycle as a file-watch reload. Runtime components are not rebuilt
     * here; the caller decides whether the new values are safe to apply.</p>
     *
     * @return validation result for the values read from disk; never throws
     */
    public static ConfigValidationResult reloadFromDisk() {
        if (!SPEC.isLoaded()) {
            return ConfigValidationResult.failed(List.of("配置尚未加载"), List.of());
        }
        ModConfig config = findConfig();
        if (config == null) {
            return ConfigValidationResult.failed(List.of("配置尚未注册"), List.of());
        }

        try {
            CommentedConfig data = config.getConfigData();
            if (data == null) {
                return ConfigValidationResult.failed(List.of("配置文件尚未读取"), List.of());
            }
            if (data instanceof CommentedFileConfig fileConfig) {
                fileConfig.load();
                if (!SPEC.isCorrect(fileConfig)) {
                    LOGGER.warn("[config] {} 不满足当前配置结构，已备份并修正", fileConfig.getFile());
                    ConfigFileTypeHandler.backUpConfig(fileConfig);
                    SPEC.correct(fileConfig);
                    fileConfig.save();
                }
            }
            SPEC.acceptConfig(data);
            SPEC.afterReload();
            ConfigValidationResult result = validateCurrent();
            if (result.valid()) {
                dispatchReloading(config);
            }
            return result;
        } catch (RuntimeException failure) {
            LOGGER.warn("[config] 重新读取配置文件失败，继续使用上一份有效配置", failure);
            return ConfigValidationResult.failed(
                    List.of("配置重载失败: " + describe(failure)), List.of());
        }
    }

    private static ModConfig findConfig() {
        for (ModConfig candidate : ConfigTracker.INSTANCE.configSets()
                .getOrDefault(ModConfig.Type.COMMON, Set.of())) {
            if (candidate.getSpec() == SPEC) {
                return candidate;
            }
        }
        return null;
    }

    private static void dispatchReloading(ModConfig config) {
        ModList.get().getModContainerById(Sudoersemc.MODID)
                .ifPresent(container -> container.dispatchConfigEvent(IConfigEvent.reloading(config)));
    }

    private static String describe(RuntimeException failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : message;
    }

    /**
     * Parses the {@code overflow.overwriteScope} string. Unknown values fall back to
     * {@link OverflowScope#OWNED_ONLY} and log a warning.
     */
    public static OverflowScope parseOverflowScope(String rawValue) {
        if (rawValue != null) {
            String normalized = rawValue.trim().toLowerCase(Locale.ROOT);
            if ("owned_only".equals(normalized)) {
                return OverflowScope.OWNED_ONLY;
            }
            if ("entire_custom_emc".equals(normalized)) {
                return OverflowScope.ENTIRE_CUSTOM_EMC;
            }
            LOGGER.warn(
                    "[overflow] Unknown overwriteScope '{}'; expected 'owned_only' or 'entire_custom_emc'. "
                            + "Falling back to 'owned_only'.",
                    rawValue);
        }
        return OverflowScope.OWNED_ONLY;
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            handleConfigEvent("loading");
        }
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            handleConfigEvent("reloading");
        }
    }

    /**
     * Validates the freshly loaded values and hands the result to {@link ConfigWatcher}.
     *
     * <p>Forge may fire these events on a config-loading thread. This method only reads the spec
     * and swaps immutable volatile snapshots; it never touches the server, a level, or scan state.
     * Listeners run only for a valid result, so a rejected reload keeps the previous snapshot.
     * If even the first load is invalid, the built-in safe defaults stay active.</p>
     */
    private static void handleConfigEvent(String phase) {
        if (!SPEC.isLoaded()) {
            return;
        }

        ConfigValidationResult result;
        try {
            result = ConfigValidator.validate(validationValues());
        } catch (RuntimeException failure) {
            String detail = failure.getMessage();
            String reason = detail == null || detail.isBlank()
                    ? failure.getClass().getSimpleName()
                    : detail;
            result = ConfigValidationResult.failed(
                    List.of("无法读取当前配置：" + reason), List.of());
            LOGGER.warn("[config] 无法读取配置（{}），保留上一份有效配置", phase, failure);
        }

        lastValidationResult = result;
        CONFIG_WATCHER.fireReload(result);
    }

    /**
     * {@link ConfigWatcher} activation callback. Only validated configurations reach this point.
     *
     * <p>If snapshot construction still fails, the previous snapshot stays active. This is the
     * fail-closed path promised by the README: a rejected reload never rebuilds components from
     * partially valid values.</p>
     */
    private static void refreshActiveSnapshots() {
        try {
            ConfigValidator.Values values = validationValues();
            OverflowProtection.Settings settings = buildOverflowSettings(values);
            activeValues = values;
            activeOverflowSettings = settings;
            LOGGER.debug("[config] 已应用新的配置快照");
        } catch (RuntimeException failure) {
            LOGGER.warn("[config] 构建配置快照失败，保留上一份有效配置", failure);
        }
    }

    private static OverflowProtection.Settings buildOverflowSettings(ConfigValidator.Values values) {
        return new OverflowProtection.Settings(
                values.overflowEnabled(),
                values.overflowThreshold(),
                values.resetMin(),
                values.resetMax(),
                values.bucketCount(),
                values.weightCap(),
                ConfigValidator.parseScope(values.overwriteScope()),
                values.allowProjectEFileRewrite(),
                OVERFLOW_RANDOM_SEED.get());
    }

    private static Path commonConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("sudoersemc-common.toml");
    }
}
