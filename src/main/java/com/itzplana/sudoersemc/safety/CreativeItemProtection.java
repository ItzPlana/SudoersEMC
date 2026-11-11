package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    private final Settings settings;

    /**
     * @param settings 保护配置；不能为 {@code null}
     */
    public CreativeItemProtection(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
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
