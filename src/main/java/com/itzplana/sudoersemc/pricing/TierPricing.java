package com.itzplana.sudoersemc.pricing;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Tier-rule-based fallback pricing.
 *
 * <p>Rules are evaluated strictly in registration order; the first matching rule that actually takes
 * effect decides the result:</p>
 * <ol>
 *   <li>{@code exclude == true}: returns {@code Optional.of(0L)}, meaning "explicitly excluded, do not price";</li>
 *   <li>{@code fixedValue != null}: returns that fixed value, even if the same rule also configures a multiplier;</li>
 *   <li>{@code multiplier != 1.0}: {@link #price} returns empty; the caller should obtain the multiplier
 *       via {@link #multiplierFor} and scale its own base value.</li>
 * </ol>
 *
 * <p>The engine must not embed large mod item lists. {@link #defaults()} contains only a few generic
 * rules tied to vanilla mechanics; everything else should come from configuration.</p>
 */
public final class TierPricing {

    /** How a rule matches. */
    public enum Type {
        /** Match by namespace, e.g. {@code minecraft}. */
        NAMESPACE,
        /** Match by full item id, e.g. {@code minecraft:iron_ingot}. */
        EXACT_ID,
        /** Match by a substring contained in the item path, e.g. {@code _ore}. */
        PATH_CONTAINS
    }

    /**
     * One tier rule.
     *
     * @param type       match mode
     * @param pattern    match pattern; trimmed and lowercased at construction
     * @param multiplier multiplier, exposed via {@link #multiplierFor} only when no fixed value is set and not excluded
     * @param fixedValue fixed price; {@code null} means no fixed price specified
     * @param exclude    whether to explicitly exclude the item
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
     * @param rules rules in priority order; may be empty but not {@code null}
     */
    public TierPricing(List<TierRule> rules) {
        Objects.requireNonNull(rules, "rules");
        this.rules = List.copyOf(rules);
    }

    /**
     * Computes an item's price in registration order.
     *
     * <p>An exclusion rule returns {@code Optional.of(0L)}; a fixed-value rule returns its fixed value; a
     * pure-multiplier rule returns empty, because the multiplier must apply to the caller's base value.
     * Returns {@link Optional#empty()} when the item is {@code null} or no rule matches. This method never
     * throws.</p>
     *
     * @param item the item to price
     * @return a fixed price, an exclusion marker ({@code 0L}), or empty
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
     * Returns the multiplier of the first matching pure-multiplier rule, for the caller to scale its own
     * base value.
     *
     * <p>If an exclusion rule or fixed-value rule matches first, the multiplier is no longer meaningful
     * and {@code 1.0} is returned. {@code 1.0} is also returned when no rule matches.</p>
     *
     * @param item the item to query
     * @return the multiplier, default {@code 1.0}
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
     * @return immutable view of the rule list
     */
    public List<TierRule> rules() {
        return rules;
    }

    /**
     * Generic default rules: only exclude vanilla items with no survival obtainment path or no economic
     * meaning; contains no mod-specific list.
     *
     * <p>These items are either creative-only (bedrock, barrier, command block, structure block, spawner)
     * or do not exist in normal gameplay at all (air, debug stick). Pricing them would create EMC out of
     * nothing, so they must be excluded. Mod-specific item lists should come from configuration; the
     * engine must not embed them.</p>
     *
     * @return the default tier pricer
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
                // The debug stick and knowledge book have no economic meaning under any namespace; path-contains matching is more robust.
                new TierRule(Type.PATH_CONTAINS, "debug_stick", 1.0D, null, true),
                new TierRule(Type.PATH_CONTAINS, "knowledge_book", 1.0D, null, true),
                // Spawn eggs have no survival obtainment path; pricing them would make mob spawning a resource source.
                new TierRule(Type.PATH_CONTAINS, "spawn_egg", 1.0D, null, true),
                // A growing amethyst cluster can only be obtained with Silk Touch and cannot circulate as a material.
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
