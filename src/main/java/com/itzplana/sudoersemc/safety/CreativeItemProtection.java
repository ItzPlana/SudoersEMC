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
import net.minecraft.resources.ResourceLocation;

/**
 * 创造模式物品保护：阻止把明确的创造专属物品纳入自动 EMC 定价。
 *
 * <h2>未知不等于创造专属</h2>
 * <p>无法求解的物品（既没有配方，也没有可用的标签定价）仍然允许以 {@code 0} 兜底定价。
 * 只有两种明确信号会阻止定价：物品出现在 {@link Settings#explicitExclusions()} 中，或物品
 * 的命名空间出现在 {@link Settings#creativeOnlyNamespaces()} 中。绝不能因为“算不出价值”就
 * 假定物品是创造专属的。</p>
 *
 * <p>判定顺序固定：保护禁用、显式排除、创造专属命名空间、特殊固定值、无解兜底、普通物品。
 * 显式排除优先于特殊固定值，因此管理员可以用排除规则覆盖误配置的固定值。</p>
 */
public final class CreativeItemProtection {

    /** 合法命名空间：小写字母、数字、下划线、连字符与点。 */
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    private final Settings settings;

    /**
     * @param settings 保护配置；不能为 {@code null}
     */
    public CreativeItemProtection(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
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
     * <p>解析规则：排除项与命名空间条目必须是合法的 {@code namespace:path} / 命名空间；
     * 特殊值必须是 {@code namespace:path=正整数}，非正数按非法条目处理；非法条目不会中断
     * 解析，只会进入 {@link ParsedSettings#skippedEntries()}。</p>
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

        Settings settings = new Settings(enabled, exclusions, specialValues, creativeNamespaces);
        return new ParsedSettings(settings, skippedEntries);
    }

    private static ResourceLocation parseItemId(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        return value.isEmpty() ? null : ResourceLocation.tryParse(value);
    }

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

    private static boolean isValidNamespace(String namespace) {
        return namespace != null
                && !namespace.isEmpty()
                && NAMESPACE_PATTERN.matcher(namespace).matches();
    }

    private record ParsedSpecialValue(ResourceLocation itemId, long value) {
    }

    /**
     * 创造物品保护配置。
     *
     * @param enabled                是否启用保护
     * @param explicitExclusions     明确禁止定价的完整物品 id
     * @param specialValues          绕过配方/标签求解的固定 EMC 值，键为完整物品 id
     * @param creativeOnlyNamespaces 整体视为创造专属、禁止自动定价的命名空间
     */
    public record Settings(
            boolean enabled,
            Set<ResourceLocation> explicitExclusions,
            Map<ResourceLocation, Long> specialValues,
            Set<String> creativeOnlyNamespaces
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
        }
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
        if (!settings.enabled()) {
            return new Decision(true, 0L, "protection-disabled");
        }
        ResourceLocation itemId = item.itemId();
        if (settings.explicitExclusions().contains(itemId)) {
            return new Decision(false, 0L, "explicit-exclusion");
        }
        if (settings.creativeOnlyNamespaces().contains(itemId.getNamespace())) {
            return new Decision(false, 0L, "creative-only-namespace");
        }
        Long specialValue = settings.specialValues().get(itemId);
        if (specialValue != null) {
            return new Decision(true, specialValue, "special-value");
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
