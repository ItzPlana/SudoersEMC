package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.OverflowScope;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    private static ValuesBuilder base() {
        return new ValuesBuilder();
    }

    @Test
    void defaultValuesProduceNoErrorsAndNoWarnings() {
        ConfigValidationResult result = ConfigValidator.validate(base().build());

        assertTrue(result.valid());
        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());
        assertFalse(result.hasWarnings());
    }

    @Test
    void itemsPerTickBoundaryValues() {
        ConfigValidationResult valid = ConfigValidator.validate(base().itemsPerTick(1).build());
        assertTrue(valid.valid());

        ConfigValidationResult invalid = ConfigValidator.validate(base().itemsPerTick(0).build());
        assertEquals(List.of("itemsPerTick 必须 >= 1，当前为 0"), invalid.errors());
    }

    @Test
    void maxRecipePassesBoundaryValues() {
        assertTrue(ConfigValidator.validate(base().maxRecipePasses(1).build()).valid());
        assertEquals(
                List.of("maxRecipePasses 必须 >= 1，当前为 0"),
                ConfigValidator.validate(base().maxRecipePasses(0).build()).errors());
    }

    @Test
    void minEmcBoundaryValues() {
        assertTrue(ConfigValidator.validate(base().minEmc(0L).build()).valid());
        assertEquals(
                List.of("minEmc 必须 >= 0，当前为 -1"),
                ConfigValidator.validate(base().minEmc(-1L).build()).errors());
    }

    @Test
    void invalidOverwriteScopeIsRejected() {
        ConfigValidationResult result = ConfigValidator.validate(
                base().overwriteScope("custom").build());

        assertEquals(
                List.of("overwriteScope 只能是 owned_only 或 entire_custom_emc，当前为 custom"),
                result.errors());
    }

    @Test
    void overflowResetMinMustBeAtLeastOne() {
        ConfigValidationResult result = ConfigValidator.validate(base().resetMin(0L).build());

        assertEquals(List.of("overflow.resetMin 必须 >= 1，当前为 0"), result.errors());
    }

    @Test
    void overflowResetMinMustNotExceedResetMax() {
        ConfigValidationResult result = ConfigValidator.validate(
                base().resetMin(1_000L).resetMax(999L).build());

        assertEquals(List.of("overflow.resetMin 不能大于 resetMax"), result.errors());
    }

    @Test
    void overflowThresholdBoundaryValues() {
        ConfigValidationResult equalToResetMax = ConfigValidator.validate(
                base().overflowThreshold(999_000L).build());
        assertEquals(List.of("overflow.threshold 必须大于 resetMax"), equalToResetMax.errors());

        ConfigValidationResult aboveResetMax = ConfigValidator.validate(
                base().overflowThreshold(999_001L).build());
        assertTrue(aboveResetMax.valid());
    }

    @Test
    void resetMinMayEqualResetMax() {
        ConfigValidationResult result = ConfigValidator.validate(
                base().resetMin(500L).resetMax(500L).build());

        assertTrue(result.valid());
    }

    @Test
    void overflowBucketCountAndWeightCapAreValidated() {
        assertEquals(
                List.of("overflow.bucketCount 必须 >= 1，当前为 0"),
                ConfigValidator.validate(base().bucketCount(0).build()).errors());
        assertEquals(
                List.of("overflow.weightCap 必须 >= 1，当前为 0"),
                ConfigValidator.validate(base().weightCap(0).build()).errors());
        assertTrue(ConfigValidator.validate(base().bucketCount(1).build()).valid());
    }

    @Test
    void nbtVariantBoundaryValues() {
        assertTrue(ConfigValidator.validate(base().maxNbtVariantsPerItem(1).build()).valid());
        assertTrue(ConfigValidator.validate(base().maxNbtVariantsPerItem(1000).build()).valid());

        assertEquals(
                List.of("maxNbtVariantsPerItem 必须在 [1, 1000] 内，当前为 0"),
                ConfigValidator.validate(base().maxNbtVariantsPerItem(0).build()).errors());
        assertEquals(
                List.of("maxNbtVariantsPerItem 必须在 [1, 1000] 内，当前为 1001"),
                ConfigValidator.validate(base().maxNbtVariantsPerItem(1001).build()).errors());
    }

    @Test
    void dropRatioBoundaryAndSpecialValues() {
        assertTrue(ConfigValidator.validate(base().maxAllowedDropRatio(1.0D).build()).valid());

        assertEquals(
                List.of("maxAllowedDropRatio 必须在 (0.0, 1.0] 内，当前为 0.0"),
                ConfigValidator.validate(base().maxAllowedDropRatio(0.0D).build()).errors());
        assertEquals(
                List.of("maxAllowedDropRatio 必须在 (0.0, 1.0] 内，当前为 -0.1"),
                ConfigValidator.validate(base().maxAllowedDropRatio(-0.1D).build()).errors());
        assertEquals(
                List.of("maxAllowedDropRatio 必须在 (0.0, 1.0] 内，当前为 NaN"),
                ConfigValidator.validate(base().maxAllowedDropRatio(Double.NaN).build()).errors());
        assertEquals(
                List.of("maxAllowedDropRatio 必须在 (0.0, 1.0] 内，当前为 1.0001"),
                ConfigValidator.validate(base().maxAllowedDropRatio(1.0001D).build()).errors());
    }

    @Test
    void validCreativeProtectionEntriesProduceNoWarnings() {
        ConfigValidationResult result = ConfigValidator.validate(base()
                .creativeItemExclusions("minecraft:bedrock", "create:creative_crate")
                .creativeItemSpecialValues("minecraft:diamond=12345", "minecraft:emerald=1")
                .creativeNamespaces("create", "goety")
                .build());

        assertTrue(result.valid());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void malformedCreativeProtectionEntriesProduceWarnings() {
        ConfigValidationResult result = ConfigValidator.validate(base()
                .creativeItemExclusions("not a valid id", "minecraft:ok")
                .creativeItemSpecialValues("minecraft:diamond=0", "minecraft:emerald=abc",
                        "minecraft:gold_ingot=5")
                .creativeNamespaces("UPPERCASE", "create")
                .build());

        assertTrue(result.valid(), "非法条目只应警告，不应让整份配置失效");
        assertEquals(List.of(
                "safety.creativeItemExclusions 条目 'not a valid id' 不是合法的 namespace:path，将被忽略",
                "safety.creativeItemSpecialValues 条目 'minecraft:diamond=0' 必须为 namespace:path=正整数，将被忽略",
                "safety.creativeItemSpecialValues 条目 'minecraft:emerald=abc' 必须为 namespace:path=正整数，将被忽略",
                "safety.creativeNamespaces 条目 'UPPERCASE' 不是合法的命名空间，将被忽略"),
                result.warnings());
    }

    @Test
    void crossFieldValidationIncludesCreativeProtectionWarnings() {
        ConfigValidationResult result = ConfigValidator.validateCrossField(base()
                .creativeItemExclusions("missing_namespace")
                .build());

        assertTrue(result.valid());
        assertEquals(List.of(
                "safety.creativeItemExclusions 条目 'missing_namespace' 不是合法的 namespace:path，将被忽略"),
                result.warnings());
    }

    @Test
    void entireCustomEmcWithoutFileRewriteWarns() {
        ConfigValidationResult result = ConfigValidator.validate(
                base().overwriteScope("entire_custom_emc").build());

        assertTrue(result.valid());
        assertEquals(
                List.of("overwriteScope=entire_custom_emc 但 allowProjectEFileRewrite=false，"
                        + "ProjectE 文件改写将被跳过"),
                result.warnings());
    }

    @Test
    void entireCustomEmcWarningIgnoresCase() {
        ConfigValidationResult result = ConfigValidator.validate(
                base().overwriteScope("ENTIRE_CUSTOM_EMC").build());

        assertTrue(result.valid());
        assertEquals(1, result.warnings().size());
    }

    @Test
    void highItemsPerTickWarns() {
        ConfigValidationResult warning = ConfigValidator.validate(base().itemsPerTick(5_001).build());
        assertEquals(
                List.of("itemsPerTick 大于 5000，单 tick 处理量过大可能导致卡顿"),
                warning.warnings());

        assertTrue(ConfigValidator.validate(base().itemsPerTick(5_000).build()).warnings().isEmpty());
    }

    @Test
    void highMaxRecipePassesWarns() {
        ConfigValidationResult warning = ConfigValidator.validate(base().maxRecipePasses(101).build());
        assertEquals(
                List.of("maxRecipePasses 大于 100，扫描耗时可能显著增加"),
                warning.warnings());

        assertTrue(ConfigValidator.validate(base().maxRecipePasses(100).build()).warnings().isEmpty());
    }

    @Test
    void disabledOverflowSkipsRangeErrorsButStillWarns() {
        ConfigValidationResult result = ConfigValidator.validate(base()
                .overflowEnabled(false)
                .overflowThreshold(0L)
                .resetMin(0L)
                .resetMax(-5L)
                .bucketCount(0)
                .weightCap(0)
                .build());

        assertTrue(result.valid());
        assertEquals(List.of(), result.errors());
        assertEquals(List.of("溢出保护已关闭，超大 EMC 值不会被重置"), result.warnings());
    }

    @Test
    void errorOrderIsDeterministic() {
        ConfigValidationResult result = ConfigValidator.validate(base()
                .itemsPerTick(0)
                .maxRecipePasses(0)
                .minEmc(-1L)
                .overwriteScope("anything")
                .overflowThreshold(-10L)
                .resetMin(0L)
                .resetMax(-5L)
                .bucketCount(0)
                .weightCap(0)
                .maxNbtVariantsPerItem(0)
                .maxAllowedDropRatio(0.0D)
                .build());

        assertEquals(List.of(
                "itemsPerTick 必须 >= 1，当前为 0",
                "maxRecipePasses 必须 >= 1，当前为 0",
                "minEmc 必须 >= 0，当前为 -1",
                "overwriteScope 只能是 owned_only 或 entire_custom_emc，当前为 anything",
                "overflow.resetMin 必须 >= 1，当前为 0",
                "overflow.resetMin 不能大于 resetMax",
                "overflow.threshold 必须大于 resetMax",
                "overflow.bucketCount 必须 >= 1，当前为 0",
                "overflow.weightCap 必须 >= 1，当前为 0",
                "maxNbtVariantsPerItem 必须在 [1, 1000] 内，当前为 0",
                "maxAllowedDropRatio 必须在 (0.0, 1.0] 内，当前为 0.0"), result.errors());
    }

    @Test
    void warningOrderIsDeterministic() {
        ConfigValidationResult result = ConfigValidator.validate(base()
                .overwriteScope("entire_custom_emc")
                .itemsPerTick(5_001)
                .maxRecipePasses(101)
                .overflowEnabled(false)
                .build());

        assertEquals(List.of(
                "overwriteScope=entire_custom_emc 但 allowProjectEFileRewrite=false，"
                        + "ProjectE 文件改写将被跳过",
                "itemsPerTick 大于 5000，单 tick 处理量过大可能导致卡顿",
                "maxRecipePasses 大于 100，扫描耗时可能显著增加",
                "溢出保护已关闭，超大 EMC 值不会被重置"), result.warnings());
    }

    @Test
    void scopeValidationIsCaseInsensitiveAndRejectsBlankValues() {
        assertTrue(ConfigValidator.isValidScope("owned_only"));
        assertTrue(ConfigValidator.isValidScope("OWNED_ONLY"));
        assertTrue(ConfigValidator.isValidScope(" Entire_Custom_EMC "));
        assertFalse(ConfigValidator.isValidScope("custom"));
        assertFalse(ConfigValidator.isValidScope(null));
        assertFalse(ConfigValidator.isValidScope("   "));
    }

    @Test
    void scopeParsingFallsBackToOwnedOnly() {
        assertEquals(OverflowScope.OWNED_ONLY, ConfigValidator.parseScope("owned_only"));
        assertEquals(OverflowScope.OWNED_ONLY, ConfigValidator.parseScope("OWNED_ONLY"));
        assertEquals(
                OverflowScope.ENTIRE_CUSTOM_EMC,
                ConfigValidator.parseScope(" Entire_Custom_EMC "));
        assertEquals(OverflowScope.OWNED_ONLY, ConfigValidator.parseScope("unknown"));
        assertEquals(OverflowScope.OWNED_ONLY, ConfigValidator.parseScope(null));
    }

    @Test
    void currentConfigAdapterAcceptsValidValues() {
        ConfigValidationResult result = ConfigValidator.validateCurrent(() -> base().build());

        assertTrue(result.valid());
        assertEquals(List.of(), result.errors());
    }

    @Test
    void currentConfigAdapterRejectsThresholdNotAboveResetMax() {
        ConfigValidationResult result = ConfigValidator.validateCurrent(
                () -> base().overflowThreshold(999_000L).build());

        assertFalse(result.valid());
        assertEquals(List.of("overflow.threshold 必须大于 resetMax"), result.errors());
    }

    @Test
    void currentConfigAdapterRejectsResetMinAboveResetMax() {
        ConfigValidationResult result = ConfigValidator.validateCurrent(
                () -> base().resetMin(1_000L).resetMax(999L).build());

        assertFalse(result.valid());
        assertEquals(List.of("overflow.resetMin 不能大于 resetMax"), result.errors());
    }

    @Test
    void currentConfigAdapterRejectsZeroBucketCount() {
        ConfigValidationResult result = ConfigValidator.validateCurrent(
                () -> base().bucketCount(0).build());

        assertFalse(result.valid());
        assertEquals(List.of("overflow.bucketCount 必须 >= 1，当前为 0"), result.errors());
    }

    @Test
    void currentConfigAdapterConvertsReadFailureToInvalidResult() {
        ConfigValidationResult result = ConfigValidator.validateCurrent(() -> {
            throw new IllegalStateException("not loaded");
        });

        assertFalse(result.valid());
        assertEquals(List.of("无法读取当前配置：not loaded"), result.errors());
    }

    @Test
    void currentConfigAdapterRejectsNullSnapshot() {
        ConfigValidationResult result = ConfigValidator.validateCurrent(() -> null);

        assertFalse(result.valid());
        assertEquals(List.of("当前配置快照为空"), result.errors());
    }

    @Test
    void unknownRecipeSelectionStrategyProducesWarning() {
        ConfigValidationResult result = ConfigValidator.validate(
                base().recipeSelectionStrategy("nonsense").build());

        assertTrue(result.valid());
        assertTrue(result.warnings().stream()
                        .anyMatch(warning -> warning.contains("recipe.selectionStrategy")),
                "非法策略应回退默认并给出警告");
    }

    @Test
    void validRecipeSelectionStrategiesProduceNoWarning() {
        ConfigValidationResult result = ConfigValidator.validate(
                base().recipeSelectionStrategy("prefer_simple").build());

        assertTrue(result.valid());
        assertFalse(result.warnings().stream()
                .anyMatch(warning -> warning.contains("recipe.selectionStrategy")));
    }

    @Test
    void validDropExemptionsAndAutoDetectionEntriesProduceNoWarnings() {
        ConfigValidationResult result = ConfigValidator.validate(base()
                .dropProtectionExemptNamespaces("create", "goety")
                .dropProtectionExemptItems("minecraft:diamond", "create:cogwheel")
                .creativeOnlyItemPatterns("^minecraft:command_block$")
                .autoDetectCreativeOnlyNamespaces("example")
                .build());

        assertTrue(result.valid());
        assertEquals(List.of(), result.warnings());
    }

    @Test
    void malformedDropExemptionsAndAutoDetectionEntriesProduceWarnings() {
        ConfigValidationResult result = ConfigValidator.validate(base()
                .dropProtectionExemptNamespaces("UPPERCASE")
                .dropProtectionExemptItems("stone")
                .creativeOnlyItemPatterns("[")
                .autoDetectCreativeOnlyNamespaces("Bad NS")
                .build());

        assertTrue(result.valid());
        assertEquals(List.of(
                "safety.dropProtectionExemptNamespaces 条目 'UPPERCASE' 不是合法的命名空间，将被忽略",
                "safety.dropProtectionExemptItems 条目 'stone' 不是合法的 namespace:path，将被忽略",
                "safety.creativeOnlyItemPatterns 条目 '[' 不是合法的正则表达式，将被忽略",
                "safety.autoDetectCreativeOnlyNamespaces 条目 'Bad NS' 不是合法的命名空间，将被忽略"),
                result.warnings());
    }

    private static final class ValuesBuilder {

        private int itemsPerTick = 100;
        private int maxRecipePasses = 20;
        private long minEmc = 1L;
        private boolean overflowEnabled = true;
        private long overflowThreshold = 999_999L;
        private long resetMin = 100L;
        private long resetMax = 999_000L;
        private int bucketCount = 100;
        private int weightCap = 20;
        private String overwriteScope = "owned_only";
        private boolean allowProjectEFileRewrite = false;
        private int maxNbtVariantsPerItem = 100;
        private double maxAllowedDropRatio = 0.5D;
        private List<String> creativeItemExclusions = List.of();
        private List<String> creativeItemSpecialValues = List.of();
        private List<String> creativeNamespaces = List.of();
        private String recipeSelectionStrategy = "lowest_cost";
        private List<String> dropProtectionExemptNamespaces = List.of();
        private List<String> dropProtectionExemptItems = List.of();
        private List<String> creativeOnlyItemPatterns = List.of();
        private List<String> autoDetectCreativeOnlyNamespaces = List.of();

        ValuesBuilder itemsPerTick(int value) {
            this.itemsPerTick = value;
            return this;
        }

        ValuesBuilder maxRecipePasses(int value) {
            this.maxRecipePasses = value;
            return this;
        }

        ValuesBuilder minEmc(long value) {
            this.minEmc = value;
            return this;
        }

        ValuesBuilder overflowEnabled(boolean value) {
            this.overflowEnabled = value;
            return this;
        }

        ValuesBuilder overflowThreshold(long value) {
            this.overflowThreshold = value;
            return this;
        }

        ValuesBuilder resetMin(long value) {
            this.resetMin = value;
            return this;
        }

        ValuesBuilder resetMax(long value) {
            this.resetMax = value;
            return this;
        }

        ValuesBuilder bucketCount(int value) {
            this.bucketCount = value;
            return this;
        }

        ValuesBuilder weightCap(int value) {
            this.weightCap = value;
            return this;
        }

        ValuesBuilder overwriteScope(String value) {
            this.overwriteScope = value;
            return this;
        }

        ValuesBuilder allowProjectEFileRewrite(boolean value) {
            this.allowProjectEFileRewrite = value;
            return this;
        }

        ValuesBuilder maxNbtVariantsPerItem(int value) {
            this.maxNbtVariantsPerItem = value;
            return this;
        }

        ValuesBuilder maxAllowedDropRatio(double value) {
            this.maxAllowedDropRatio = value;
            return this;
        }

        ValuesBuilder creativeItemExclusions(String... values) {
            this.creativeItemExclusions = List.of(values);
            return this;
        }

        ValuesBuilder creativeItemSpecialValues(String... values) {
            this.creativeItemSpecialValues = List.of(values);
            return this;
        }

        ValuesBuilder creativeNamespaces(String... values) {
            this.creativeNamespaces = List.of(values);
            return this;
        }

        ValuesBuilder recipeSelectionStrategy(String value) {
            this.recipeSelectionStrategy = value;
            return this;
        }

        ValuesBuilder dropProtectionExemptNamespaces(String... values) {
            this.dropProtectionExemptNamespaces = List.of(values);
            return this;
        }

        ValuesBuilder dropProtectionExemptItems(String... values) {
            this.dropProtectionExemptItems = List.of(values);
            return this;
        }

        ValuesBuilder creativeOnlyItemPatterns(String... values) {
            this.creativeOnlyItemPatterns = List.of(values);
            return this;
        }

        ValuesBuilder autoDetectCreativeOnlyNamespaces(String... values) {
            this.autoDetectCreativeOnlyNamespaces = List.of(values);
            return this;
        }

        ConfigValidator.Values build() {
            return new ConfigValidator.Values(
                    itemsPerTick,
                    maxRecipePasses,
                    minEmc,
                    overflowEnabled,
                    overflowThreshold,
                    resetMin,
                    resetMax,
                    bucketCount,
                    weightCap,
                    overwriteScope,
                    allowProjectEFileRewrite,
                    maxNbtVariantsPerItem,
                    maxAllowedDropRatio,
                    creativeItemExclusions,
                    creativeItemSpecialValues,
                    creativeNamespaces,
                    recipeSelectionStrategy,
                    dropProtectionExemptNamespaces,
                    dropProtectionExemptItems,
                    creativeOnlyItemPatterns,
                    autoDetectCreativeOnlyNamespaces);
        }
    }
}
