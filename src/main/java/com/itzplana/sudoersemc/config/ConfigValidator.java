package com.itzplana.sudoersemc.config;

import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.solver.RecipeSelectionStrategy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 纯 Java 的配置校验逻辑。
 *
 * <p>本类刻意不依赖 Forge 的 {@code ForgeConfigSpec}，所有输入都通过 {@link Values}
 * 以普通值的形式传入，因此无需启动游戏即可做单元测试。校验结果分为两类：
 * {@link ConfigValidationResult#errors()} 表示必须拒绝的配置，调用方应保留旧组件；
 * {@link ConfigValidationResult#warnings()} 表示配置可用但存在风险或行为冲突。</p>
 *
 * <p>溢出保护关闭时，只跳过与重置区间、阈值、桶数量相关的范围校验；
 * {@code maxNbtVariantsPerItem} 与 {@code maxAllowedDropRatio} 仍然始终校验。</p>
 */
public final class ConfigValidator {

    private static final String SCOPE_OWNED_ONLY = "owned_only";
    private static final String SCOPE_ENTIRE_CUSTOM_EMC = "entire_custom_emc";

    private ConfigValidator() {
    }

    /**
     * 待校验的配置快照。
     *
     * <p>组件顺序与配置文件中的阅读顺序一致，便于调用方逐项对照。所有值都是普通
     * Java 类型，不携带任何 Forge 配置对象。</p>
     *
     * @param itemsPerTick             服务端线程每 tick 最多处理的物品数
     * @param maxRecipePasses          单次扫描的配方求解遍数上限
     * @param minEmc                   生成 EMC 值的下限，允许为 0
     * @param overflowEnabled          是否启用溢出保护
     * @param overflowThreshold        严格大于该值的 EMC 会被重置
     * @param resetMin                 重置区间下界（含）
     * @param resetMax                 重置区间上界（含）
     * @param bucketCount              加权采样桶数量
     * @param weightCap                单个桶的权重上限
     * @param overwriteScope           改写范围字符串
     * @param allowProjectEFileRewrite 是否允许改写 ProjectE 的 custom_emc.json
     * @param maxNbtVariantsPerItem    每个物品最多扫描的 NBT 变体数
     * @param creativeItemExclusions   禁止自动定价的完整物品 id 原始列表
     * @param creativeItemSpecialValues 固定 EMC 值原始列表，格式为 {@code namespace:path=正整数}
     * @param creativeNamespaces       整体视为创造专属的命名空间原始列表
     * @param recipeSelectionStrategy  同一物品多条同成本配方时的取舍策略原始字符串
     */
    public record Values(
            int itemsPerTick,
            int maxRecipePasses,
            long minEmc,
            boolean overflowEnabled,
            long overflowThreshold,
            long resetMin,
            long resetMax,
            int bucketCount,
            int weightCap,
            String overwriteScope,
            boolean allowProjectEFileRewrite,
            int maxNbtVariantsPerItem,
            double maxAllowedDropRatio,
            List<String> creativeItemExclusions,
            List<String> creativeItemSpecialValues,
            List<String> creativeNamespaces,
            String recipeSelectionStrategy
    ) {

        public Values {
            creativeItemExclusions = copyList(creativeItemExclusions);
            creativeItemSpecialValues = copyList(creativeItemSpecialValues);
            creativeNamespaces = copyList(creativeNamespaces);
            recipeSelectionStrategy = recipeSelectionStrategy == null
                    ? "lowest_cost" : recipeSelectionStrategy;
        }

        /**
         * 兼容 Phase 22 及更早测试/调用点的构造器：默认选择策略为 {@code lowest_cost}。
         */
        public Values(int itemsPerTick,
                      int maxRecipePasses,
                      long minEmc,
                      boolean overflowEnabled,
                      long overflowThreshold,
                      long resetMin,
                      long resetMax,
                      int bucketCount,
                      int weightCap,
                      String overwriteScope,
                      boolean allowProjectEFileRewrite,
                      int maxNbtVariantsPerItem,
                      double maxAllowedDropRatio,
                      List<String> creativeItemExclusions,
                      List<String> creativeItemSpecialValues,
                      List<String> creativeNamespaces) {
            this(itemsPerTick, maxRecipePasses, minEmc, overflowEnabled, overflowThreshold,
                    resetMin, resetMax, bucketCount, weightCap, overwriteScope,
                    allowProjectEFileRewrite, maxNbtVariantsPerItem, maxAllowedDropRatio,
                    creativeItemExclusions, creativeItemSpecialValues, creativeNamespaces,
                    "lowest_cost");
        }

        private static List<String> copyList(List<String> source) {
            return source == null ? List.of() : List.copyOf(source);
        }
    }

    /**
     * 校验配置快照。
     *
     * <p>错误按固定顺序收集：处理性能、配方遍数、EMC 下限、改写范围、溢出区间、
     * NBT 变体上限、跌幅比例；警告顺序为：改写范围冲突、处理量过大、遍数过多、
     * 溢出保护关闭。该顺序是稳定契约，日志与测试可以依赖它。</p>
     *
     * @param values 待校验配置，不能为 {@code null}
     * @return 校验结果；存在错误时 {@link ConfigValidationResult#valid()} 为 {@code false}
     * @throws NullPointerException 当 {@code values} 为 {@code null} 时
     */
    public static ConfigValidationResult validate(Values values) {
        Objects.requireNonNull(values, "values");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (values.itemsPerTick() < 1) {
            errors.add("itemsPerTick 必须 >= 1，当前为 " + values.itemsPerTick());
        }
        if (values.maxRecipePasses() < 1) {
            errors.add("maxRecipePasses 必须 >= 1，当前为 " + values.maxRecipePasses());
        }
        if (values.minEmc() < 0L) {
            errors.add("minEmc 必须 >= 0，当前为 " + values.minEmc());
        }
        if (!isValidScope(values.overwriteScope())) {
            errors.add("overwriteScope 只能是 owned_only 或 entire_custom_emc，当前为 "
                    + values.overwriteScope());
        }
        appendOverflowErrors(values, errors);
        if (values.maxNbtVariantsPerItem() < 1 || values.maxNbtVariantsPerItem() > 1000) {
            errors.add("maxNbtVariantsPerItem 必须在 [1, 1000] 内，当前为 "
                    + values.maxNbtVariantsPerItem());
        }
        if (!(values.maxAllowedDropRatio() > 0.0D && values.maxAllowedDropRatio() <= 1.0D)) {
            errors.add("maxAllowedDropRatio 必须在 (0.0, 1.0] 内，当前为 "
                    + values.maxAllowedDropRatio());
        }
        if (!RecipeSelectionStrategy.isValid(values.recipeSelectionStrategy())) {
            warnings.add("recipe.selectionStrategy='" + values.recipeSelectionStrategy()
                    + "' 不是可识别值，将回退 lowest_cost");
        }

        appendScopeWarning(values, warnings);
        if (values.itemsPerTick() > 5_000) {
            warnings.add("itemsPerTick 大于 5000，单 tick 处理量过大可能导致卡顿");
        }
        if (values.maxRecipePasses() > 100) {
            warnings.add("maxRecipePasses 大于 100，扫描耗时可能显著增加");
        }
        if (!values.overflowEnabled()) {
            warnings.add("溢出保护已关闭，超大 EMC 值不会被重置");
        }
        appendCreativeProtectionWarnings(values, warnings);

        return errors.isEmpty()
                ? ConfigValidationResult.ok(warnings)
                : ConfigValidationResult.failed(errors, warnings);
    }

    /**
     * 只执行运行时重建真正依赖的跨字段校验。
     *
     * <p>单项数值范围由 {@code ForgeConfigSpec} 在加载配置时强制，本方法不重复检查；
     * 它复用 {@link #validate(Values)} 中同一组溢出区间规则与改写范围冲突警告，
     * 因此不会出现两套规则文本。适用于 {@code ModConfigEvent} 与命令触发的运行时
     * 重载：只要这些跨字段不变量成立，现有组件就可以安全替换。</p>
     *
     * @param values 待校验配置，不能为 {@code null}
     * @return 校验结果；存在跨字段错误时 {@link ConfigValidationResult#valid()} 为 {@code false}
     * @throws NullPointerException 当 {@code values} 为 {@code null} 时
     */
    public static ConfigValidationResult validateCrossField(Values values) {
        Objects.requireNonNull(values, "values");

        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        appendOverflowErrors(values, errors);
        appendScopeWarning(values, warnings);
        appendCreativeProtectionWarnings(values, warnings);

        return errors.isEmpty()
                ? ConfigValidationResult.ok(warnings)
                : ConfigValidationResult.failed(errors, warnings);
    }

    /**
     * 收集创造物品保护列表的格式警告。
     *
     * <p>非法条目不会让整份配置失效：运行时解析会跳过它们，因此这里只给出警告，
     * 让管理员知道哪些配置项没有生效。校验与运行时解析使用相同的格式规则。</p>
     */
    private static void appendCreativeProtectionWarnings(Values values, List<String> warnings) {
        for (String raw : values.creativeItemExclusions()) {
            if (!isValidItemId(raw)) {
                warnings.add("safety.creativeItemExclusions 条目 '" + display(raw)
                        + "' 不是合法的 namespace:path，将被忽略");
            }
        }
        for (String raw : values.creativeItemSpecialValues()) {
            if (!isValidSpecialValue(raw)) {
                warnings.add("safety.creativeItemSpecialValues 条目 '" + display(raw)
                        + "' 必须为 namespace:path=正整数，将被忽略");
            }
        }
        for (String raw : values.creativeNamespaces()) {
            if (!isValidNamespace(raw)) {
                warnings.add("safety.creativeNamespaces 条目 '" + display(raw)
                        + "' 不是合法的命名空间，将被忽略");
            }
        }
    }

    /**
     * @return 是否为合法的 {@code namespace:path} 物品 id
     */
    private static boolean isValidItemId(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            return false;
        }
        return isValidNamespace(value.substring(0, separator))
                && isValidPath(value.substring(separator + 1));
    }

    /**
     * @return 是否为合法的 {@code namespace:path=正整数} 特殊值
     */
    private static boolean isValidSpecialValue(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        int separator = value.indexOf('=');
        if (separator <= 0 || separator != value.lastIndexOf('=')) {
            return false;
        }
        if (!isValidItemId(value.substring(0, separator))) {
            return false;
        }
        try {
            return Long.parseLong(value.substring(separator + 1).trim()) > 0L;
        } catch (NumberFormatException failure) {
            return false;
        }
    }

    /**
     * @return 是否为合法的命名空间
     */
    private static boolean isValidNamespace(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean valid = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '-' || character == '.';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return 是否为合法的资源路径（允许小写字母、数字、下划线、连字符、点与斜杠）
     */
    private static boolean isValidPath(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            boolean valid = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '-' || character == '.'
                    || character == '/';
            if (!valid) {
                return false;
            }
        }
        return true;
    }

    /**
     * @return 用于警告文本的条目展示形式；空白条目显示为空引号
     */
    private static String display(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /**
     * 收集溢出区间相关的跨字段错误。
     *
     * <p>{@code OverflowProtection.Settings} 即使 {@code enabled=false} 也会构造采样器并强制
     * 这些不变量，因此校验器不能因开关关闭而放行非法区间。</p>
     */
    private static void appendOverflowErrors(Values values, List<String> errors) {
        if (values.resetMin() < 1L) {
            errors.add("overflow.resetMin 必须 >= 1，当前为 " + values.resetMin());
        }
        if (values.resetMin() > values.resetMax()) {
            errors.add("overflow.resetMin 不能大于 resetMax");
        }
        if (values.overflowThreshold() <= values.resetMax()) {
            errors.add("overflow.threshold 必须大于 resetMax");
        }
        if (values.bucketCount() < 1) {
            errors.add("overflow.bucketCount 必须 >= 1，当前为 " + values.bucketCount());
        }
        if (values.weightCap() < 1) {
            errors.add("overflow.weightCap 必须 >= 1，当前为 " + values.weightCap());
        }
    }

    /**
     * 收集改写范围冲突警告；这是警告而非错误，配置仍然可用。
     */
    private static void appendScopeWarning(Values values, List<String> warnings) {
        if (isEntireCustomEmc(values.overwriteScope()) && !values.allowProjectEFileRewrite()) {
            warnings.add("overwriteScope=entire_custom_emc 但 allowProjectEFileRewrite=false，"
                    + "ProjectE 文件改写将被跳过");
        }
    }

    /**
     * 使用给定快照来源执行运行时跨字段校验。
     *
     * <p>该重载把“读取当前配置”与“校验规则”解耦：生产环境通过无参版本读取 Forge
     * 配置，单元测试可以直接注入普通值快照。来源抛出运行时异常或返回 {@code null}
     * 时转换为带单条错误的结果，调用方因此可以统一按“校验失败”处理。</p>
     *
     * @param valuesSupplier 配置快照提供者，不能为 {@code null}
     * @return 校验结果；读取失败时 {@link ConfigValidationResult#valid()} 为 {@code false}
     * @throws NullPointerException 当 {@code valuesSupplier} 为 {@code null} 时
     */
    public static ConfigValidationResult validateCurrent(Supplier<Values> valuesSupplier) {
        Objects.requireNonNull(valuesSupplier, "valuesSupplier");

        Values values;
        try {
            values = valuesSupplier.get();
        } catch (RuntimeException failure) {
            String detail = failure.getMessage();
            String reason = detail == null || detail.isBlank()
                    ? failure.getClass().getSimpleName()
                    : detail;
            return ConfigValidationResult.failed(
                    List.of("无法读取当前配置：" + reason), List.of());
        }

        if (values == null) {
            return ConfigValidationResult.failed(List.of("当前配置快照为空"), List.of());
        }
        return validateCrossField(values);
    }

    /**
     * 校验当前 Forge 配置。
     *
     * <p>配置尚未加载或读取失败时返回失败结果，不向调用方抛出异常。跨字段规则由
     * {@link #validateCrossField(Values)} 统一实现，本方法只负责读取快照。</p>
     *
     * @return 校验结果；配置不可读时 {@link ConfigValidationResult#valid()} 为 {@code false}
     */
    public static ConfigValidationResult validateCurrent() {
        return validateCurrent(SudoersemcConfig::validationValues);
    }

    /**
     * 判断改写范围字符串是否合法。
     *
     * <p>比较时忽略首尾空白与大小写；{@code null}、空白串以及未知取值都返回
     * {@code false}。</p>
     *
     * @param scope 原始改写范围字符串
     * @return 是否为 {@code owned_only} 或 {@code entire_custom_emc}
     */
    public static boolean isValidScope(String scope) {
        if (scope == null) {
            return false;
        }
        String normalized = scope.trim().toLowerCase(Locale.ROOT);
        return SCOPE_OWNED_ONLY.equals(normalized) || SCOPE_ENTIRE_CUSTOM_EMC.equals(normalized);
    }

    /**
     * 把改写范围字符串解析为枚举。
     *
     * <p>比较时忽略首尾空白与大小写；空值或未知取值一律回退到
     * {@link OverflowScope#OWNED_ONLY}。本方法不记录日志，日志由调用方决定。</p>
     *
     * @param scope 原始改写范围字符串
     * @return 解析后的改写范围，永不为 {@code null}
     */
    public static OverflowScope parseScope(String scope) {
        if (scope != null
                && SCOPE_ENTIRE_CUSTOM_EMC.equals(scope.trim().toLowerCase(Locale.ROOT))) {
            return OverflowScope.ENTIRE_CUSTOM_EMC;
        }
        return OverflowScope.OWNED_ONLY;
    }

    private static boolean isEntireCustomEmc(String scope) {
        return scope != null
                && SCOPE_ENTIRE_CUSTOM_EMC.equals(scope.trim().toLowerCase(Locale.ROOT));
    }
}
