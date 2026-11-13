package com.itzplana.sudoersemc.pricing;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * 基于层级规则的兜底定价。
 *
 * <p>规则严格按照注册顺序求值，第一个匹配且真正生效的规则决定结果：</p>
 * <ol>
 *   <li>{@code exclude == true}：返回 {@code Optional.of(0L)}，表示“明确排除，不要定价”；</li>
 *   <li>{@code fixedValue != null}：返回该固定值，即使同一条规则同时配置了倍率；</li>
 *   <li>{@code multiplier != 1.0}：{@link #price} 返回空，调用方应通过 {@link #multiplierFor}
 *       取得倍率并缩放自己的基准值。</li>
 * </ol>
 *
 * <p>引擎不得内置大型模组物品清单。{@link #defaults()} 只包含少量与原版机制相关的通用规则，
 * 其余内容应由配置提供。</p>
 */
public final class TierPricing {

    /** 规则匹配方式。 */
    public enum Type {
        /** 按命名空间匹配，例如 {@code minecraft}。 */
        NAMESPACE,
        /** 按完整物品 id 匹配，例如 {@code minecraft:iron_ingot}。 */
        EXACT_ID,
        /** 按物品路径包含的子串匹配，例如 {@code _ore}。 */
        PATH_CONTAINS
    }

    /**
     * 一条层级规则。
     *
     * @param type       匹配方式
     * @param pattern    匹配模式，构造时会去除首尾空白并转为小写
     * @param multiplier 倍率，仅在未配置固定值且未排除时通过 {@link #multiplierFor} 暴露
     * @param fixedValue 固定价格；为 {@code null} 表示不指定固定价格
     * @param exclude    是否明确排除该物品
     */
    public record TierRule(Type type, String pattern, double multiplier, Long fixedValue, boolean exclude) {

        public TierRule {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(pattern, "pattern");
            String normalized = pattern.trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("pattern must not be blank");
            }
            if (fixedValue != null && fixedValue < 0L) {
                throw new IllegalArgumentException("fixedValue must be >= 0, got " + fixedValue);
            }
            if (Double.isNaN(multiplier) || Double.isInfinite(multiplier) || multiplier < 0.0D) {
                throw new IllegalArgumentException("multiplier must be finite and >= 0, got " + multiplier);
            }
            pattern = normalized;
        }
    }

    private final List<TierRule> rules;

    /**
     * @param rules 按优先级排列的规则列表，允许为空但不可为 {@code null}
     */
    public TierPricing(List<TierRule> rules) {
        Objects.requireNonNull(rules, "rules");
        this.rules = List.copyOf(rules);
    }

    /**
     * 按注册顺序计算物品价格。
     *
     * <p>排除规则返回 {@code Optional.of(0L)}；固定值规则返回其固定值；纯倍率规则返回空，
     * 因为倍率必须作用在调用方的基准值上。物品为 {@code null} 或没有规则命中时返回
     * {@link Optional#empty()}。本方法不会抛出异常。</p>
     *
     * @param item 待定价物品
     * @return 固定价格、排除标记（{@code 0L}）或空
     */
    public Optional<Long> price(ItemKey item) {
        if (item == null) {
            return Optional.empty();
        }
        for (TierRule rule : rules) {
            if (!matches(rule, item)) {
                continue;
            }
            if (rule.exclude()) {
                return Optional.of(0L);
            }
            if (rule.fixedValue() != null) {
                return Optional.of(rule.fixedValue());
            }
            if (rule.multiplier() != 1.0D) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /**
     * 返回第一个命中的纯倍率规则的倍率，供调用方缩放自己的基准值。
     *
     * <p>如果先命中排除规则或固定值规则，则倍率不再有意义，返回 {@code 1.0}。
     * 没有任何规则命中时同样返回 {@code 1.0}。</p>
     *
     * @param item 待查询物品
     * @return 倍率，默认 {@code 1.0}
     */
    public double multiplierFor(ItemKey item) {
        if (item == null) {
            return 1.0D;
        }
        for (TierRule rule : rules) {
            if (!matches(rule, item)) {
                continue;
            }
            if (rule.exclude() || rule.fixedValue() != null) {
                return 1.0D;
            }
            if (rule.multiplier() != 1.0D) {
                return rule.multiplier();
            }
        }
        return 1.0D;
    }

    /**
     * @return 不可变的规则列表视图
     */
    public List<TierRule> rules() {
        return rules;
    }

    /**
     * 通用默认规则：只排除没有生存获取途径或没有经济意义的原版物品，不包含任何模组专属清单。
     *
     * <p>这些物品要么只能在创造模式获得（基岩、屏障、命令方块、结构方块、刷怪笼），
     * 要么根本不存在于正常游戏中（空气、调试棒）。给它们定价会凭空创造 EMC，
     * 因此必须排除。具体模组的物品清单应由配置提供，引擎不得内置。</p>
     *
     * @return 默认层级定价器
     */
    public static TierPricing defaults() {
        return new TierPricing(List.of(
                new TierRule(Type.EXACT_ID, "minecraft:air", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:cave_air", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:void_air", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:bedrock", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:barrier", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:light", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:structure_void", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:structure_block", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:jigsaw", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:command_block", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:chain_command_block", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:repeating_command_block", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:command_block_minecart", 1.0D, null, true),
                new TierRule(Type.EXACT_ID, "minecraft:spawner", 1.0D, null, true),
                // 调试棒与知识之书任何命名空间下都没有经济意义，用路径包含匹配更稳健。
                new TierRule(Type.PATH_CONTAINS, "debug_stick", 1.0D, null, true),
                new TierRule(Type.PATH_CONTAINS, "knowledge_book", 1.0D, null, true),
                // 刷怪蛋没有生存获取途径，给它们定价会让刷怪变成资源来源。
                new TierRule(Type.PATH_CONTAINS, "spawn_egg", 1.0D, null, true),
                // 正在生长的紫水晶簇只能用精准采集获得，无法作为材料流转。
                new TierRule(Type.EXACT_ID, "minecraft:budding_amethyst", 1.0D, null, true)
        ));
    }

    private static boolean matches(TierRule rule, ItemKey item) {
        return switch (rule.type()) {
            case NAMESPACE -> item.itemId().getNamespace().equals(rule.pattern());
            case EXACT_ID -> item.itemId().toString().equals(rule.pattern());
            case PATH_CONTAINS -> item.itemId().getPath().contains(rule.pattern());
        };
    }
}
