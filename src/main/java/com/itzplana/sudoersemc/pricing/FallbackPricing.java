package com.itzplana.sudoersemc.pricing;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.world.item.Rarity;

/**
 * 兜底定价链：当配方求解无法给出数值时，按固定优先级为物品选择价格来源。
 *
 * <p>优先级从高到低（必须与此顺序完全一致）：</p>
 * <ol>
 *   <li>{@link PriceSource#EXISTING}：物品已有 EMC，且大于 0；</li>
 *   <li>排除检查：层级规则明确排除的物品直接终止，不参与后续任何定价；</li>
 *   <li>{@link PriceSource#EXTERNAL_BASELINE}：外部基线值大于 0；</li>
 *   <li>{@link PriceSource#TAG}：命中材料标签基准值（受 {@link PricingSettings#useTagPricing()} 控制）；</li>
 *   <li>{@link PriceSource#TIER}：命中层级固定值（受 {@link PricingSettings#useTierPricing()} 控制）；</li>
 *   <li>{@link PriceSource#RARITY}：按稀有度取值（受 {@link PricingSettings#useRarityPricing()} 控制）；</li>
 *   <li>{@link PriceSource#FALLBACK}：通用兜底值（受
 *       {@link PricingSettings#useFallbackForUnsolvable()} 控制）。</li>
 * </ol>
 *
 * <p>层级规则返回 {@code Optional.of(0L)} 表示“明确排除”。此时本类直接返回
 * {@link Optional#empty()}，绝不把被排除的物品当成免费物品。任何情况下都不会返回负值。</p>
 *
 * <p>层级规则的纯倍率规则不会单独产生价格：{@link TierPricing#price} 对它返回空，定价链会
 * 继续尝试稀有度与通用兜底值。需要缩放的调用方应自行使用 {@link TierPricing#multiplierFor}。</p>
 */
public final class FallbackPricing {

    /**
     * 一次兜底定价请求。
     *
     * <p>标签列表会被复制并过滤 {@code null} 元素；负数的既有值与外部基线会被视为 0，
     * 以保证定价链不会返回负值。</p>
     *
     * @param item             待定价物品
     * @param tagPaths         物品拥有的标签路径
     * @param rarity           物品稀有度，允许为 {@code null}
     * @param existingValue    物品当前已有的 EMC，{@code 0} 表示没有
     * @param externalBaseline 外部基线值，{@code 0} 表示没有
     */
    public record PricingRequest(
            ItemKey item,
            List<String> tagPaths,
            Rarity rarity,
            long existingValue,
            long externalBaseline
    ) {

        public PricingRequest {
            Objects.requireNonNull(item, "item");
            tagPaths = copyTagPaths(tagPaths);
            existingValue = Math.max(0L, existingValue);
            externalBaseline = Math.max(0L, externalBaseline);
        }

        private static List<String> copyTagPaths(List<String> tagPaths) {
            if (tagPaths == null || tagPaths.isEmpty()) {
                return List.of();
            }
            List<String> copy = new ArrayList<>(tagPaths.size());
            for (String tagPath : tagPaths) {
                if (tagPath != null) {
                    copy.add(tagPath);
                }
            }
            return List.copyOf(copy);
        }
    }

    /**
     * 一次兜底定价的结果。
     *
     * @param item   被定价的物品
     * @param value  非负价格
     * @param source 价格来源
     * @param reason 供诊断使用的简短原因标记
     */
    public record PricedValue(ItemKey item, long value, PriceSource source, String reason) {

        public PricedValue {
            Objects.requireNonNull(item, "item");
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(reason, "reason");
            value = Math.max(0L, value);
        }
    }

    private final TagPricing tagPricing;
    private final RarityPricing rarityPricing;
    private final TierPricing tierPricing;
    private final PricingSettings settings;

    public FallbackPricing(TagPricing tagPricing, RarityPricing rarityPricing, TierPricing tierPricing,
                           PricingSettings settings) {
        this.tagPricing = Objects.requireNonNull(tagPricing, "tagPricing");
        this.rarityPricing = Objects.requireNonNull(rarityPricing, "rarityPricing");
        this.tierPricing = Objects.requireNonNull(tierPricing, "tierPricing");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * 返回定价链使用的层级规则。
     *
     * <p>扫描管线需要复用 {@link TierPricing#price} 的排除语义（{@code Optional.of(0L)}），
     * 以便在被排除物品存在配方时也能提前拒绝定价，而不是只能依赖兜底链。</p>
     *
     * @return 当前实例使用的层级定价器，永不为 {@code null}
     */
    public TierPricing tierPricing() {
        return tierPricing;
    }

    /**
     * 使用默认标签锚点、默认稀有度定价与默认层级规则创建定价链。
     *
     * @param settings 定价开关与通用兜底值
     * @return 可直接使用的兜底定价链
     */
    public static FallbackPricing withDefaults(PricingSettings settings) {
        return new FallbackPricing(
                TagPricing.defaults(),
                RarityPricing.defaults(),
                TierPricing.defaults(),
                settings
        );
    }

    /**
     * 按 {@code EXISTING → 排除检查 → EXTERNAL_BASELINE → TAG → TIER → RARITY → FALLBACK}
     * 的顺序定价。
     *
     * <p>排除检查先于外部基线：外部基线只是“另一个定价来源”，绝不能把刷怪蛋、命令方块等
     * 明确排除的物品变成可交易资源。层级规则的排除标记（{@code 0L}）会立即终止整个定价链
     * 并返回 {@link Optional#empty()}。纯倍率层级规则返回空，因此不会阻断后续的稀有度定价。</p>
     *
     * @param request 定价请求
     * @return 命中的价格与来源，未定价时为空
     */
    public Optional<PricedValue> price(PricingRequest request) {
        Objects.requireNonNull(request, "request");
        ItemKey item = request.item();

        long existingValue = request.existingValue();
        if (existingValue > 0L) {
            return Optional.of(new PricedValue(item, existingValue, PriceSource.EXISTING, "existing-value"));
        }

        // 排除语义与价格优先级是两件事：排除表示「拒绝定价」，不是「价格来源」。
        // 稀有度定价对任何输入都会返回值，若把排除检查放在它之后，层级排除规则将永远不可达，
        // 导致 spawn egg、命令方块等物品被误定价。因此排除检查必须先于所有定价步骤。
        if (settings.useTierPricing() && isExcluded(item)) {
            return Optional.empty();
        }

        long externalBaseline = request.externalBaseline();
        if (externalBaseline > 0L) {
            return Optional.of(new PricedValue(
                    item, externalBaseline, PriceSource.EXTERNAL_BASELINE, "external-baseline"));
        }

        if (settings.useTagPricing()) {
            Optional<Long> tagValue = tagPricing.price(item, request.tagPaths());
            if (tagValue.isPresent()) {
                return Optional.of(new PricedValue(item, tagValue.get(), PriceSource.TAG, "tag-category"));
            }
        }

        if (settings.useTierPricing()) {
            Optional<Long> tierValue = tierPricing.price(item);
            if (tierValue.isPresent()) {
                long value = tierValue.get();
                if (value <= 0L) {
                    // 明确的排除规则：宁可不定价，也不要把物品变成免费物品
                    return Optional.empty();
                }
                return Optional.of(new PricedValue(item, value, PriceSource.TIER, "tier-rule"));
            }
        }

        if (settings.useRarityPricing()) {
            long rarityValue = rarityPricing.price(request.rarity());
            String rarityName = request.rarity() == null ? "UNKNOWN" : request.rarity().name();
            return Optional.of(new PricedValue(
                    item, rarityValue, PriceSource.RARITY, "rarity:" + rarityName));
        }

        if (settings.useFallbackForUnsolvable()) {
            return Optional.of(new PricedValue(
                    item, settings.genericFallbackValue(), PriceSource.FALLBACK, "generic-fallback"));
        }

        return Optional.empty();
    }

    /**
     * 判断物品是否被层级规则明确排除。
     *
     * <p>{@link TierPricing#price} 用 {@code Optional.of(0L)} 表示「明确排除」。这里单独抽出来，
     * 是因为排除与定价是两种语义：排除必须在任何定价步骤之前生效，否则会被总会返回值的
     * 稀有度定价遮蔽。</p>
     */
    private boolean isExcluded(ItemKey item) {
        Optional<Long> tierValue = tierPricing.price(item);
        return tierValue.isPresent() && tierValue.get() <= 0L;
    }
}
