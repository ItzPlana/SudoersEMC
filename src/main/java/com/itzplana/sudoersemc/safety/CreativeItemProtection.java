package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import net.minecraft.resources.ResourceLocation;

/**
 * 创造模式物品保护：阻止把明确的创造专属物品纳入自动 EMC 定价。
 *
 * <h2>未知不等于创造专属</h2>
 * <p>无法求解的物品（既没有配方，也没有可用的标签定价）仍然允许以 {@code 0} 兜底定价。
 * 只有三种明确信号会阻止定价：物品出现在 {@link Settings#explicitExclusions()} 中，物品
 * 的命名空间出现在 {@link Settings#creativeOnlyNamespaces()} 中，或物品命中
 * {@link Settings#creativeOnlyItemPatterns()} / {@link Settings#autoDetectCreativeOnlyNamespaces()}
 * 的保守自动识别规则。绝不能因为“算不出价值”就假定物品是创造专属的。</p>
 *
 * <p>判定顺序固定：显式排除、创造专属命名空间与自动识别（两者仅在保护启用时生效）、
 * 特殊固定值、保护禁用、无解兜底、普通物品。显式排除与自动识别都优先于特殊固定值，
 * 因此管理员无法用固定值让被排除或已被识别为创造专属的物品重新进入自动定价。</p>
 *
 * <p>显式排除与特殊固定值不随 {@code protectCreativeOnlyItems} 总开关关闭而失效：管理员
 * 关闭该开关时只放弃“创造专属命名空间”的自动识别，明确的排除项与固定值仍然生效。</p>
 */
public final class CreativeItemProtection {

    /** 合法命名空间：小写字母、数字、下划线、连字符与点。 */
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    /**
     * 默认的“已知创造专属”物品 id 正则。
     *
     * <p>默认集合只覆盖原版中只能通过创造模式或命令获得的命令/结构类物品；普通方块、
     * 刷怪笼、基岩等可能被整合包以生存方式提供的物品刻意不包含在内。管理员可以通过
     * {@code safety.creativeOnlyItemPatterns} 追加模式，或关闭自动识别。</p>
     */
    public static final List<String> DEFAULT_AUTO_DETECT_PATTERNS = List.of(
            "^minecraft:(barrier|chain_command_block|command_block|command_block_minecart|"
                    + "debug_stick|jigsaw|knowledge_book|light|repeating_command_block|"
                    + "structure_block|structure_void)$");

    /** 合法资源路径：小写字母、数字、下划线、连字符、点与斜杠。 */
    private static final Pattern PATH_PATTERN = Pattern.compile("[a-z0-9_./-]+");

    private final Settings settings;
    private final List<Pattern> autoDetectPatterns;

    /**
     * @param settings 保护配置；不能为 {@code null}
     */
    public CreativeItemProtection(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.autoDetectPatterns = compilePatterns(settings.creativeOnlyItemPatterns());
    }

    /**
     * @return 默认的已知创造专属物品正则列表
     */
    public static List<String> defaultAutoDetectPatterns() {
        return DEFAULT_AUTO_DETECT_PATTERNS;
    }

    /**
     * 原始配置解析结果。
     *
     * @param settings       解析后的保护配置
     * @param skippedEntries 无法解析、格式非法或特殊值非正数的原始条目，保持配置顺序
     */
    public record ParsedSettings(Settings settings, List<String> skippedEntries) {

        public ParsedSettings {
            Objects.requireNonNull(settings, "settings");
            skippedEntries = skippedEntries == null ? List.of() : List.copyOf(skippedEntries);
        }
    }

    /**
     * 把配置字符串解析为 {@link Settings}，并收集被忽略的非法条目。
     *
     * <p>解析规则：</p>
     * <ul>
     *   <li>排除项与命名空间条目必须是合法的 {@code namespace:path} / 命名空间；</li>
     *   <li>特殊值必须是 {@code namespace:path=正整数}，非正数按非法条目处理；</li>
     *   <li>非法条目不会中断解析，只会进入 {@link ParsedSettings#skippedEntries()}。</li>
     * </ul>
     *
     * <p>该方法不读取任何 Forge 配置对象，调用方负责读取原始字符串列表并汇总警告。</p>
     *
     * @param enabled               是否启用保护
     * @param rawExclusions         原始排除项列表，允许为 {@code null}
     * @param rawSpecialValues      原始特殊值列表，允许为 {@code null}
     * @param rawCreativeNamespaces 原始命名空间列表，允许为 {@code null}
     * @return 解析结果；永不为 {@code null}
     */
    public static ParsedSettings parseSettings(boolean enabled,
                                               List<? extends String> rawExclusions,
                                               List<? extends String> rawSpecialValues,
                                               List<? extends String> rawCreativeNamespaces) {
        return parseSettings(enabled, rawExclusions, rawSpecialValues, rawCreativeNamespaces,
                false, List.of(), List.of());
    }

    /**
     * 把配置字符串解析为 {@link Settings}，并收集被忽略的非法条目。
     *
     * <p>解析规则：</p>
     * <ul>
     *   <li>排除项与命名空间条目必须是合法的 {@code namespace:path} / 命名空间；</li>
     *   <li>特殊值必须是 {@code namespace:path=正整数}，非正数按非法条目处理；</li>
     *   <li>自动识别模式必须是可编译的正则表达式；</li>
     *   <li>非法条目不会中断解析，只会进入 {@link ParsedSettings#skippedEntries()}。</li>
     * </ul>
     *
     * <p>该方法不读取任何 Forge 配置对象，调用方负责读取原始字符串列表并汇总警告。</p>
     *
     * @param enabled                  是否启用保护
     * @param rawExclusions            原始排除项列表，允许为 {@code null}
     * @param rawSpecialValues         原始特殊值列表，允许为 {@code null}
     * @param rawCreativeNamespaces    原始命名空间列表，允许为 {@code null}
     * @param autoDetectCreativeOnly   是否启用已知创造专属自动识别
     * @param rawCreativeOnlyPatterns  原始自动识别正则列表，允许为 {@code null}
     * @param rawAutoDetectNamespaces  原始自动识别命名空间列表，允许为 {@code null}
     * @return 解析结果；永不为 {@code null}
     */
    public static ParsedSettings parseSettings(boolean enabled,
                                               List<? extends String> rawExclusions,
                                               List<? extends String> rawSpecialValues,
                                               List<? extends String> rawCreativeNamespaces,
                                               boolean autoDetectCreativeOnly,
                                               List<? extends String> rawCreativeOnlyPatterns,
                                               List<? extends String> rawAutoDetectNamespaces) {
        Set<ResourceLocation> exclusions = new LinkedHashSet<>();
        List<String> skippedEntries = new ArrayList<>();

        if (rawExclusions != null) {
            for (String raw : rawExclusions) {
                ResourceLocation id = parseItemId(raw);
                if (id == null) {
                    skippedEntries.add(raw == null ? "" : raw);
                } else {
                    exclusions.add(id);
                }
            }
        }

        Map<ResourceLocation, Long> specialValues = new LinkedHashMap<>();
        if (rawSpecialValues != null) {
            for (String raw : rawSpecialValues) {
                ParsedSpecialValue parsed = parseSpecialValue(raw);
                if (parsed == null) {
                    skippedEntries.add(raw == null ? "" : raw);
                } else {
                    specialValues.put(parsed.itemId(), parsed.value());
                }
            }
        }

        Set<String> creativeNamespaces = new LinkedHashSet<>();
        if (rawCreativeNamespaces != null) {
            for (String raw : rawCreativeNamespaces) {
                String namespace = raw == null ? "" : raw.trim();
                if (isValidNamespace(namespace)) {
                    creativeNamespaces.add(namespace);
                } else {
                    skippedEntries.add(raw == null ? "" : raw);
                }
            }
        }

        Set<String> creativeOnlyPatterns = new LinkedHashSet<>();
        if (rawCreativeOnlyPatterns != null) {
            for (String raw : rawCreativeOnlyPatterns) {
                String pattern = raw == null ? "" : raw.trim();
                if (isValidPattern(pattern)) {
                    creativeOnlyPatterns.add(pattern);
                } else {
                    skippedEntries.add(raw == null ? "" : raw);
                }
            }
        }

        Set<String> autoDetectNamespaces = new LinkedHashSet<>();
        if (rawAutoDetectNamespaces != null) {
            for (String raw : rawAutoDetectNamespaces) {
                String namespace = raw == null ? "" : raw.trim();
                if (isValidNamespace(namespace)) {
                    autoDetectNamespaces.add(namespace);
                } else {
                    skippedEntries.add(raw == null ? "" : raw);
                }
            }
        }

        Settings settings = new Settings(enabled, exclusions, specialValues, creativeNamespaces,
                autoDetectCreativeOnly, creativeOnlyPatterns, autoDetectNamespaces);
        return new ParsedSettings(settings, skippedEntries);
    }

    /**
     * 解析一个完整的物品 id。
     *
     * @return 合法 id；空白或格式错误时返回 {@code null}
     */
    private static ResourceLocation parseItemId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')
                || separator == value.length() - 1) {
            return null;
        }
        String namespace = value.substring(0, separator);
        String path = value.substring(separator + 1);
        if (!isValidNamespace(namespace) || !isValidPath(path)) {
            return null;
        }
        return ResourceLocation.tryParse(value);
    }

    /**
     * 解析 {@code namespace:path=正整数} 形式的特殊值。
     *
     * @return 解析结果；格式错误或数值非正时返回 {@code null}
     */
    private static ParsedSpecialValue parseSpecialValue(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int separator = value.indexOf('=');
        if (separator <= 0 || separator != value.lastIndexOf('=')) {
            return null;
        }
        ResourceLocation itemId = parseItemId(value.substring(0, separator));
        if (itemId == null) {
            return null;
        }
        long parsedValue;
        try {
            parsedValue = Long.parseLong(value.substring(separator + 1).trim());
        } catch (NumberFormatException failure) {
            return null;
        }
        if (parsedValue <= 0L) {
            return null;
        }
        return new ParsedSpecialValue(itemId, parsedValue);
    }

    /**
     * @return 是否为合法的 Minecraft 命名空间
     */
    private static boolean isValidNamespace(String namespace) {
        return namespace != null
                && !namespace.isEmpty()
                && NAMESPACE_PATTERN.matcher(namespace).matches();
    }

    /**
     * @return 是否为可编译的正则表达式
     */
    private static boolean isValidPattern(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        try {
            Pattern.compile(pattern);
            return true;
        } catch (PatternSyntaxException failure) {
            return false;
        }
    }

    /**
     * @return 是否为合法的资源路径（与 {@code ConfigValidator} 的规则保持一致）
     */
    private static boolean isValidPath(String path) {
        return path != null
                && !path.isEmpty()
                && PATH_PATTERN.matcher(path).matches();
    }

    /**
     * 特殊值解析中间结果。
     */
    private record ParsedSpecialValue(ResourceLocation itemId, long value) {
    }

    /**
     * 创造物品保护配置。
     *
     * @param enabled                          是否启用保护
     * @param explicitExclusions               明确禁止定价的完整物品 id
     * @param specialValues                    绕过配方/标签求解的固定 EMC 值，键为完整物品 id
     * @param creativeOnlyNamespaces           整体视为创造专属、禁止自动定价的命名空间
     * @param autoDetectCreativeOnly           是否启用已知创造专属自动识别
     * @param creativeOnlyItemPatterns         自动识别的完整物品 id 正则集合
     * @param autoDetectCreativeOnlyNamespaces 自动识别的命名空间集合
     */
    public record Settings(
            boolean enabled,
            Set<ResourceLocation> explicitExclusions,
            Map<ResourceLocation, Long> specialValues,
            Set<String> creativeOnlyNamespaces,
            boolean autoDetectCreativeOnly,
            Set<String> creativeOnlyItemPatterns,
            Set<String> autoDetectCreativeOnlyNamespaces
    ) {

        public Settings {
            explicitExclusions = explicitExclusions == null ? Set.of() : Set.copyOf(explicitExclusions);
            specialValues = specialValues == null ? Map.of() : Map.copyOf(specialValues);
            for (Map.Entry<ResourceLocation, Long> entry : specialValues.entrySet()) {
                if (entry.getValue() == null || entry.getValue() <= 0L) {
                    throw new IllegalArgumentException(
                            "specialValues must be positive for " + entry.getKey()
                                    + ", got " + entry.getValue());
                }
            }
            creativeOnlyNamespaces = creativeOnlyNamespaces == null
                    ? Set.of()
                    : Set.copyOf(creativeOnlyNamespaces);
            creativeOnlyItemPatterns = creativeOnlyItemPatterns == null
                    ? Set.of()
                    : Set.copyOf(creativeOnlyItemPatterns);
            autoDetectCreativeOnlyNamespaces = autoDetectCreativeOnlyNamespaces == null
                    ? Set.of()
                    : Set.copyOf(autoDetectCreativeOnlyNamespaces);
        }

        /**
         * 兼容 Phase 23 及更早调用点的构造器：关闭自动识别，保持旧行为。
         */
        public Settings(boolean enabled,
                        Set<ResourceLocation> explicitExclusions,
                        Map<ResourceLocation, Long> specialValues,
                        Set<String> creativeOnlyNamespaces) {
            this(enabled, explicitExclusions, specialValues, creativeOnlyNamespaces,
                    false, Set.of(), Set.of());
        }
    }

    /**
     * @return 自动识别模式是否命中该物品 id
     */
    private boolean isAutoDetectedCreativeOnly(ResourceLocation itemId) {
        if (!settings.autoDetectCreativeOnly()) {
            return false;
        }
        if (settings.autoDetectCreativeOnlyNamespaces().contains(itemId.getNamespace())) {
            return true;
        }
        String fullId = itemId.toString();
        for (Pattern pattern : autoDetectPatterns) {
            if (pattern.matcher(fullId).matches()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 编译配置中的正则；非法模式在运行时静默跳过。
     *
     * <p>配置解析路径会先把非法模式汇总到 {@link ParsedSettings#skippedEntries()}，因此这里
     * 只作为直接构造 {@link Settings} 时的防御性兜底。</p>
     */
    private static List<Pattern> compilePatterns(Set<String> rawPatterns) {
        if (rawPatterns == null || rawPatterns.isEmpty()) {
            return List.of();
        }
        List<Pattern> compiled = new ArrayList<>(rawPatterns.size());
        for (String raw : rawPatterns) {
            try {
                compiled.add(Pattern.compile(raw));
            } catch (PatternSyntaxException ignored) {
                // 直接构造 Settings 时忽略非法模式；配置路径会给出汇总警告。
            }
        }
        return List.copyOf(compiled);
    }

    /**
     * 单个物品的定价判定结果。
     *
     * @param priceable    是否允许自动定价
     * @param specialValue 需要使用的固定值；{@code priceable=false} 或没有固定值时为 {@code 0}
     * @param reason       机器可读的判定原因
     */
    public record Decision(boolean priceable, long specialValue, String reason) {

        public Decision {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /**
     * 判定物品是否可以自动定价。
     *
     * <p>{@code hasRecipe} 与 {@code hasTagPricing} 只影响“无解兜底”这一条路径：两者都为
     * {@code false} 时仍然允许定价，只是原因为 {@code unsolvable-fallback}。</p>
     *
     * @param item          物品身份
     * @param hasRecipe     是否存在可用于求解的配方
     * @param hasTagPricing 是否存在可用的标签定价规则
     * @return 定价判定；固定值只在 {@code special-value} 路径上非零
     */
    public Decision decide(ItemKey item, boolean hasRecipe, boolean hasTagPricing) {
        Objects.requireNonNull(item, "item");
        ResourceLocation itemId = item.itemId();
        if (settings.explicitExclusions().contains(itemId)) {
            return new Decision(false, 0L, "explicit-exclusion");
        }
        if (settings.enabled() && settings.creativeOnlyNamespaces().contains(itemId.getNamespace())) {
            return new Decision(false, 0L, "creative-only-namespace");
        }
        if (settings.enabled() && isAutoDetectedCreativeOnly(itemId)) {
            return new Decision(false, 0L, "auto-detected-creative-only");
        }
        Long specialValue = settings.specialValues().get(itemId);
        if (specialValue != null) {
            return new Decision(true, specialValue, "special-value");
        }
        if (!settings.enabled()) {
            return new Decision(true, 0L, "protection-disabled");
        }
        if (!hasRecipe && !hasTagPricing) {
            return new Decision(true, 0L, "unsolvable-fallback");
        }
        return new Decision(true, 0L, "normal");
    }

    /**
     * {@link #decide(ItemKey, boolean, boolean)} 的便捷布尔版本。
     */
    public boolean isPriceable(ItemKey item, boolean hasRecipe, boolean hasTagPricing) {
        return decide(item, hasRecipe, hasTagPricing).priceable();
    }
}
