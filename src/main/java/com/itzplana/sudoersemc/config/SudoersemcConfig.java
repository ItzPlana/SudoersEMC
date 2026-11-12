package com.itzplana.sudoersemc.config;

import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.solver.RecipeSelectionStrategy;
import com.mojang.logging.LogUtils;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
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

    /**
     * 扫描指纹/定价语义版本。
     *
     * <p>任何会改变生成价格的算法、解析或缓存格式变更都必须递增该值；它参与配置通道指纹，
     * 确保升级后即使注册表与配置未变，也不会静默复用旧算法生成的价格。</p>
     */
    public static final int SCAN_FINGERPRINT_VERSION = 1;

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    /** 非法配方选择策略只警告一次，避免热重载时刷屏。 */
    private static final AtomicBoolean RECIPE_SELECTION_STRATEGY_WARNED = new AtomicBoolean(false);

    // Field declarations. Values are defined in the static initializer below so that the TOML
    // sections are emitted in the documented order.
    public static final ForgeConfigSpec.BooleanValue GENERAL_ENABLED;
    public static final ForgeConfigSpec.BooleanValue GENERAL_ALWAYS_RESCAN;

    public static final ForgeConfigSpec.IntValue PERFORMANCE_ITEMS_PER_TICK;
    public static final ForgeConfigSpec.BooleanValue PERFORMANCE_ADAPTIVE_BATCH_SIZING;

    public static final ForgeConfigSpec.BooleanValue RECIPE_SOLVE_RECIPES;
    public static final ForgeConfigSpec.IntValue RECIPE_MAX_RECIPE_PASSES;
    public static final ForgeConfigSpec.BooleanValue RECIPE_ALLOW_RECIPE_VALUE_INCREASE;
    public static final ForgeConfigSpec.ConfigValue<String> RECIPE_SELECTION_STRATEGY;

    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_TAG_PRICING;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_RARITY_PRICING;
    public static final ForgeConfigSpec.BooleanValue FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES;
    public static final ForgeConfigSpec.LongValue FALLBACK_MIN_EMC;

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
        RECIPE_SELECTION_STRATEGY = BUILDER.comment(
                        "Recipe selection strategy when multiple recipes produce the same item.",
                        "lowest_cost: lowest cost, ties broken by recipe id (default)",
                        "prefer_simple: lowest cost, ties broken by fewer inputs/outputs/remainders",
                        "stable_id: lowest cost, ties explicitly broken by recipe id",
                        "Unknown values fall back to lowest_cost with one warning.")
                .define("selectionStrategy", "lowest_cost");
        BUILDER.pop();

        // [fallback]
        BUILDER.comment("Fallback pricing sources.").push("fallback");
        FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE = BUILDER.comment(
                        "Use a generic fallback value for items no other source can price.")
                .define("useFallbackForUnsolvable", true);
        FALLBACK_USE_TAG_PRICING = BUILDER.comment(
                        "Derive values from item tags and material relationships.",
                        "Category anchors are iron-based; cheap renewable materials may be overvalued",
                        "when ProjectE has no explicit value for the matching block/ingot.")
                .define("useTagPricing", true);
        FALLBACK_USE_RARITY_PRICING = BUILDER.comment("Derive values from item rarity.")
                .define("useRarityPricing", true);
        FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES = BUILDER.comment(
                        "Use ProjectE integration baselines when available.",
                        "Reserved for Phase 25; this Phase 16 build does not read this key yet.")
                .define("useProjectEIntegrationBaselines", true);
        FALLBACK_MIN_EMC = BUILDER.comment(
                        "Minimum EMC value for fallback pricing; lower candidates are discarded.",
                        "Also passed to the recipe solver as its minimum output value.")
                .defineInRange("minEmc", 1L, 0L, Long.MAX_VALUE);
        BUILDER.pop();

        // [safety]
        BUILDER.comment("Safety guards for generated values.").push("safety");
        SAFETY_LARGE_DROP_PROTECTION = BUILDER.comment(
                        "Protect against unexpectedly large decreases in generated values.")
                .define("largeDropProtection", true);
        SAFETY_MAX_ALLOWED_DROP_RATIO = BUILDER.comment(
                        "Largest allowed relative drop in a generated value, in [0, 1].")
                .defineInRange("maxAllowedDropRatio", 0.5D, 0.0D, 1.0D);
        SAFETY_PROTECT_CREATIVE_ONLY_ITEMS = BUILDER.comment(
                        "Do not generate survival-obtainable values for creative-only items.",
                        "Disabling this only turns off creativeNamespaces; creativeItemExclusions",
                        "and creativeItemSpecialValues still apply.")
                .define("protectCreativeOnlyItems", true);
        SAFETY_CREATIVE_ITEM_EXCLUSIONS = BUILDER.comment(
                        "Exact item ids that must never be auto-priced (format: namespace:path).",
                        "Applies to both recipe solving and fallback pricing.")
                .defineListAllowEmpty("creativeItemExclusions", List.of(),
                        entry -> entry instanceof String);
        SAFETY_CREATIVE_ITEM_SPECIAL_VALUES = BUILDER.comment(
                        "Fixed EMC values that override normal solving (format: namespace:path=positive long).",
                        "Existing external/manual EMC values still take priority.")
                .defineListAllowEmpty("creativeItemSpecialValues", List.of(),
                        entry -> entry instanceof String);
        SAFETY_CREATIVE_NAMESPACES = BUILDER.comment(
                        "Namespaces whose items are treated as creative-only and never auto-priced.",
                        "Only effective while protectCreativeOnlyItems is true.")
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
        NBT_MAX_NBT_VARIANTS_PER_ITEM = BUILDER.comment(
                        "Maximum number of NBT variants scanned per item; must be >= 1.")
                .defineInRange("maxNbtVariantsPerItem", 100, 1, Integer.MAX_VALUE);
        BUILDER.pop();

        // [diagnostic]
        BUILDER.comment("Diagnostic logging and reports.").push("diagnostic");
        DIAGNOSTIC_DEBUG_LOGGING = BUILDER.comment("Enable verbose diagnostic logging.",
                        "Reserved for Phase 25; this Phase 16 build does not read this key yet.")
                .define("debugLogging", false);
        DIAGNOSTIC_GENERATE_REPORTS = BUILDER.comment("Write diagnostic reports after scans.")
                .define("generateReports", true);
        BUILDER.pop();
    }

    public static final ForgeConfigSpec SPEC = BUILDER.build();

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
        // 在 Forge 解析 TOML 之前执行 v1 -> v2 迁移；失败时保留原文件。
        ConfigFileMigration.migrate(commonConfigPath());
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, SPEC);

        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(SudoersemcConfig::onConfigLoading);
        modEventBus.addListener(SudoersemcConfig::onConfigReloading);
    }

    /**
     * Builds an {@link OverflowProtection.Settings} snapshot from the current config values.
     *
     * @throws IllegalArgumentException when the current values violate the overflow invariants
     * @throws IllegalStateException    when the config has not been loaded yet
     */
    public static OverflowProtection.Settings overflowSettings() {
        if (!SPEC.isLoaded()) {
            throw new IllegalStateException("SudoersemcConfig is not loaded yet");
        }

        OverflowProtection.Settings settings = new OverflowProtection.Settings(
                OVERFLOW_ENABLED.get(),
                OVERFLOW_THRESHOLD.get(),
                OVERFLOW_RESET_MIN.get(),
                OVERFLOW_RESET_MAX.get(),
                OVERFLOW_BUCKET_COUNT.get(),
                OVERFLOW_WEIGHT_CAP.get(),
                parseOverflowScope(OVERFLOW_OVERWRITE_SCOPE.get()),
                OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get(),
                OVERFLOW_RANDOM_SEED.get());
        return settings;
    }

    /**
     * Builds a validation snapshot from the current config values.
     *
     * <p>The returned record contains plain Java values only, so the cross-field rules in
     * {@link ConfigValidator} can run without exposing Forge types to the validator.</p>
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
                FALLBACK_MIN_EMC.get(),
                OVERFLOW_ENABLED.get(),
                OVERFLOW_THRESHOLD.get(),
                OVERFLOW_RESET_MIN.get(),
                OVERFLOW_RESET_MAX.get(),
                OVERFLOW_BUCKET_COUNT.get(),
                OVERFLOW_WEIGHT_CAP.get(),
                OVERFLOW_OVERWRITE_SCOPE.get(),
                OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get(),
                NBT_MAX_NBT_VARIANTS_PER_ITEM.get(),
                SAFETY_MAX_ALLOWED_DROP_RATIO.get(),
                List.copyOf(SAFETY_CREATIVE_ITEM_EXCLUSIONS.get()),
                List.copyOf(SAFETY_CREATIVE_ITEM_SPECIAL_VALUES.get()),
                List.copyOf(SAFETY_CREATIVE_NAMESPACES.get()),
                RECIPE_SELECTION_STRATEGY.get());
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

    /**
     * Parses the {@code recipe.selectionStrategy} string.
     *
     * <p>未知值回退到 {@link RecipeSelectionStrategy#DEFAULT}，并在整个进程内只记录一次警告。</p>
     *
     * @param rawValue 原始配置值，可为 {@code null}
     * @return 解析后的策略，永不为 {@code null}
     */
    public static RecipeSelectionStrategy parseRecipeSelectionStrategy(String rawValue) {
        RecipeSelectionStrategy parsed = RecipeSelectionStrategy.parse(rawValue);
        if (parsed != null) {
            return parsed;
        }
        if (RECIPE_SELECTION_STRATEGY_WARNED.compareAndSet(false, true)) {
            LOGGER.warn(
                    "[recipe] Unknown selectionStrategy '{}'; expected 'lowest_cost', 'prefer_simple' "
                            + "or 'stable_id'. Falling back to '{}'.",
                    rawValue, RecipeSelectionStrategy.DEFAULT.configValue());
        }
        return RecipeSelectionStrategy.DEFAULT;
    }

    /**
     * 构造影响扫描结果的配置指纹条目。
     *
     * <p>条目为 {@code key=value} 形式；列表值按字符串排序后逐个展开。读取失败时返回单个固定
     * 标记，调用方会把它当成“配置指纹变化”，从而退化为需要扫描，而不是阻止启动。</p>
     *
     * @return 稳定、已排序的配置条目；配置未加载时返回固定标记
     */
    public static List<String> scanFingerprintEntries() {
        List<String> entries = new ArrayList<>();
        try {
            // 指纹/定价语义版本的显式盐：算法或格式变化必须递增该常量，
            // 否则升级后会复用旧算法生成的价格。
            addEntry(entries, "fingerprint.version", SCAN_FINGERPRINT_VERSION);
            addEntry(entries, "recipe.solveRecipes", RECIPE_SOLVE_RECIPES.get());
            addEntry(entries, "recipe.maxRecipePasses", RECIPE_MAX_RECIPE_PASSES.get());
            addEntry(entries, "recipe.allowRecipeValueIncrease", RECIPE_ALLOW_RECIPE_VALUE_INCREASE.get());
            addEntry(entries, "recipe.selectionStrategy", RECIPE_SELECTION_STRATEGY.get());
            addEntry(entries, "fallback.useFallbackForUnsolvable",
                    FALLBACK_USE_FALLBACK_FOR_UNSOLVABLE.get());
            addEntry(entries, "fallback.useTagPricing", FALLBACK_USE_TAG_PRICING.get());
            addEntry(entries, "fallback.useRarityPricing", FALLBACK_USE_RARITY_PRICING.get());
            addEntry(entries, "fallback.useProjectEIntegrationBaselines",
                    FALLBACK_USE_PROJECTE_INTEGRATION_BASELINES.get());
            addEntry(entries, "fallback.minEmc", FALLBACK_MIN_EMC.get());
            addEntry(entries, "safety.largeDropProtection", SAFETY_LARGE_DROP_PROTECTION.get());
            addEntry(entries, "safety.maxAllowedDropRatio", SAFETY_MAX_ALLOWED_DROP_RATIO.get());
            addEntry(entries, "safety.protectCreativeOnlyItems",
                    SAFETY_PROTECT_CREATIVE_ONLY_ITEMS.get());
            addListEntries(entries, "safety.creativeItemExclusions",
                    SAFETY_CREATIVE_ITEM_EXCLUSIONS.get());
            addListEntries(entries, "safety.creativeItemSpecialValues",
                    SAFETY_CREATIVE_ITEM_SPECIAL_VALUES.get());
            addListEntries(entries, "safety.creativeNamespaces",
                    SAFETY_CREATIVE_NAMESPACES.get());
            addEntry(entries, "overflow.enabled", OVERFLOW_ENABLED.get());
            addEntry(entries, "overflow.threshold", OVERFLOW_THRESHOLD.get());
            addEntry(entries, "overflow.resetMin", OVERFLOW_RESET_MIN.get());
            addEntry(entries, "overflow.resetMax", OVERFLOW_RESET_MAX.get());
            addEntry(entries, "overflow.bucketCount", OVERFLOW_BUCKET_COUNT.get());
            addEntry(entries, "overflow.weightCap", OVERFLOW_WEIGHT_CAP.get());
            addEntry(entries, "overflow.overwriteScope", OVERFLOW_OVERWRITE_SCOPE.get());
            addEntry(entries, "overflow.allowProjectEFileRewrite",
                    OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get());
            addEntry(entries, "overflow.randomSeed", OVERFLOW_RANDOM_SEED.get());
            addEntry(entries, "nbt.scanNbtVariants", NBT_SCAN_NBT_VARIANTS.get());
            addEntry(entries, "nbt.maxNbtVariantsPerItem", NBT_MAX_NBT_VARIANTS_PER_ITEM.get());
        } catch (RuntimeException failure) {
            return List.of("config-unavailable");
        }
        Collections.sort(entries);
        return List.copyOf(entries);
    }

    private static void addEntry(List<String> entries, String key, Object value) {
        entries.add(key + "=" + value);
    }

    private static void addListEntries(List<String> entries, String key,
                                       List<? extends String> values) {
        List<String> sorted = new ArrayList<>(values == null ? List.of() : values);
        Collections.sort(sorted);
        for (String value : sorted) {
            entries.add(key + "[]=" + value);
        }
    }

    private static void onConfigLoading(ModConfigEvent.Loading event) {
        if (event.getConfig().getSpec() == SPEC) {
            validateLoadedConfig();
        }
    }

    private static void onConfigReloading(ModConfigEvent.Reloading event) {
        if (event.getConfig().getSpec() == SPEC) {
            validateLoadedConfig();
        }
    }

    /**
     * Validates cross-field overflow invariants after the config is loaded or reloaded.
     *
     * <p>Individual numeric ranges are enforced by {@link ForgeConfigSpec}. This method adds the
     * checks Forge cannot express per-entry (for example {@code threshold > resetMax}) and warns
     * about combinations that would silently disable part of the requested behavior.</p>
     */
    private static void validateLoadedConfig() {
        if (!SPEC.isLoaded()) {
            return;
        }

        long threshold = OVERFLOW_THRESHOLD.get();
        long resetMin = OVERFLOW_RESET_MIN.get();
        long resetMax = OVERFLOW_RESET_MAX.get();
        int bucketCount = OVERFLOW_BUCKET_COUNT.get();
        int weightCap = OVERFLOW_WEIGHT_CAP.get();
        OverflowScope scope = parseOverflowScope(OVERFLOW_OVERWRITE_SCOPE.get());

        if (threshold <= resetMax) {
            LOGGER.warn(
                    "[overflow] Invalid configuration: threshold ({}) must be greater than resetMax ({}). "
                            + "OverflowProtection.Settings will reject these values.",
                    threshold,
                    resetMax);
        }
        if (resetMin < 1L) {
            LOGGER.warn("[overflow] Invalid configuration: resetMin ({}) must be >= 1.", resetMin);
        }
        if (resetMin > resetMax) {
            LOGGER.warn(
                    "[overflow] Invalid configuration: resetMin ({}) must be <= resetMax ({}).",
                    resetMin,
                    resetMax);
        }
        if (bucketCount < 1) {
            LOGGER.warn("[overflow] Invalid configuration: bucketCount ({}) must be >= 1.", bucketCount);
        }
        if (weightCap < 1) {
            LOGGER.warn("[overflow] Invalid configuration: weightCap ({}) must be >= 1.", weightCap);
        }
        if (scope == OverflowScope.ENTIRE_CUSTOM_EMC && !OVERFLOW_ALLOW_PROJECTE_FILE_REWRITE.get()) {
            LOGGER.warn(
                    "[overflow] Conflicting configuration: overwriteScope = entire_custom_emc but "
                            + "allowProjectEFileRewrite = false. ProjectE's custom_emc.json will not be rewritten.");
        }
    }

    private static Path commonConfigPath() {
        return FMLPaths.CONFIGDIR.get().resolve("sudoersemc-common.toml");
    }

    /**
     * 兼容旧命令路径：配置未加载时明确失败；已加载时校验当前快照。
     */
    public static ConfigValidationResult reloadFromDisk() {
        if (!SPEC.isLoaded()) {
            return ConfigValidationResult.failed(List.of("配置尚未加载"), List.of());
        }
        return ConfigValidator.validateCurrent();
    }
}
