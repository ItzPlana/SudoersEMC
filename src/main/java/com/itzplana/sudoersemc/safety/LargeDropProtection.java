package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;

/**
 * 大幅降价保护：当新值相对旧值下跌超过配置比例时，把这次变更放入隔离区，等待管理员确认。
 *
 * <p>只有降价才可能触发隔离；持平与涨价永远放行。阈值比较使用严格大于：降幅恰好等于
 * {@link Settings#maxAllowedDropRatio()} 时仍然放行。</p>
 *
 * <p>本类只做决策，不保存任何 EMC 数值。{@link #approve(ItemKey)} 返回本次被移除的具体决策，
 * 调用方只能应用这些决策；{@link #approvedItems()} / {@link #isApproved(ItemKey)} 仅用于
 * 历史诊断，绝不能作为放行未来降价的依据。</p>
 */
public final class LargeDropProtection {

    /** 合法命名空间：小写字母、数字、下划线、连字符与点。 */
    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");

    private final Settings settings;
    private final Object lock = new Object();
    private final List<QuarantinedDecision> quarantine = new ArrayList<>();
    private final Set<ItemKey> approvedItems = new LinkedHashSet<>();

    /**
     * @param settings 保护配置；不能为 {@code null}
     */
    public LargeDropProtection(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /**
     * 大幅降价保护配置。
     *
     * @param enabled              是否启用保护
     * @param maxAllowedDropRatio  允许的最大降幅，必须在 {@code (0.0, 1.0]} 内；降幅严格大于
     *                             该值才会隔离
     * @param exemptNamespaces     按命名空间整体豁免的物品来源；集合元素的 {@code path} 部分
     *                             会被忽略，只有 {@code namespace} 参与匹配
     * @param exemptItems          按物品 id 豁免的条目；匹配时忽略变体，覆盖该物品的全部 NBT 变体
     */
    public record Settings(
            boolean enabled,
            double maxAllowedDropRatio,
            Set<ResourceLocation> exemptNamespaces,
            Set<ItemKey> exemptItems
    ) {

        public Settings {
            if (!(maxAllowedDropRatio > 0.0 && maxAllowedDropRatio <= 1.0)) {
                throw new IllegalArgumentException(
                        "maxAllowedDropRatio must be finite and in (0.0, 1.0], got "
                                + maxAllowedDropRatio);
            }
            exemptNamespaces = exemptNamespaces == null ? Set.of() : Set.copyOf(exemptNamespaces);
            exemptItems = exemptItems == null ? Set.of() : Set.copyOf(exemptItems);
        }

        /** 默认配置：启用保护、最大降幅 50%、没有任何豁免。 */
        public static Settings defaults() {
            return new Settings(true, 0.5, Set.of(), Set.of());
        }
    }

    /**
     * 原始配置解析结果。
     *
     * @param settings       解析后的保护配置
     * @param skippedEntries 无法解析的命名空间或物品条目，保持配置顺序
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
     * <p>命名空间条目必须是小写的合法命名空间；物品条目必须是完整的
     * {@code namespace:path}。非法条目不会中断解析，只会进入
     * {@link ParsedSettings#skippedEntries()}，由调用方汇总为一条警告。</p>
     *
     * <p>解析顺序固定为命名空间在前、物品在后，因此非法条目的报告顺序与配置书写顺序
     * 一致。</p>
     *
     * @param enabled       是否启用大幅降价保护
     * @param maxDropRatio  允许的最大降幅，必须在 {@code (0.0, 1.0]} 内
     * @param rawNamespaces 原始命名空间豁免列表，允许为 {@code null}
     * @param rawItems      原始物品豁免列表，允许为 {@code null}
     * @return 解析结果；永不为 {@code null}
     */
    public static ParsedSettings parseSettings(boolean enabled, double maxDropRatio,
                                               List<? extends String> rawNamespaces,
                                               List<? extends String> rawItems) {
        Set<ResourceLocation> namespaces = new LinkedHashSet<>();
        List<String> skippedEntries = new ArrayList<>();

        if (rawNamespaces != null) {
            for (String raw : rawNamespaces) {
                String namespace = raw == null ? "" : raw.trim();
                if (isValidNamespace(namespace)) {
                    // 只有命名空间部分参与匹配；path 使用固定占位值。
                    namespaces.add(new ResourceLocation(namespace, "all_items"));
                } else {
                    skippedEntries.add(raw == null ? "" : raw);
                }
            }
        }

        Set<ItemKey> items = new LinkedHashSet<>();
        if (rawItems != null) {
            for (String raw : rawItems) {
                ItemKey item = parseItemKey(raw);
                if (item == null) {
                    skippedEntries.add(raw == null ? "" : raw);
                } else {
                    items.add(item);
                }
            }
        }

        return new ParsedSettings(
                new Settings(enabled, maxDropRatio, namespaces, items), skippedEntries);
    }

    /**
     * @return 合法 {@code namespace:path} 对应的物品身份；格式错误时返回 {@code null}
     */
    private static ItemKey parseItemKey(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        int separator = value.indexOf(':');
        if (separator <= 0 || separator != value.lastIndexOf(':')) {
            return null;
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        return id == null ? null : ItemKey.of(id);
    }

    /**
     * @return 是否为合法的小写命名空间
     */
    private static boolean isValidNamespace(String namespace) {
        return namespace != null
                && !namespace.isEmpty()
                && NAMESPACE_PATTERN.matcher(namespace).matches();
    }

    /**
     * 判定一次数值变更。
     *
     * <p>判定顺序固定：保护禁用、物品豁免、命名空间豁免、数值不变、数值上涨、没有历史值，
     * 最后才计算降幅并与阈值比较。降价路径之外的结果 {@code dropRatio} 均为 {@code 0.0}。</p>
     *
     * @param item          物品身份，用于检查豁免规则
     * @param previousValue 记录中的旧值，必须 {@code >= 0}
     * @param proposedValue 拟写入的新值，必须 {@code >= 0}
     * @return 判定结果；超过阈值的降价会同时写入隔离列表。同一物品只保留最新一次隔离决策，
     *         重复判定不会使列表增长
     */
    public PriceDropDecision evaluate(ItemKey item, long previousValue, long proposedValue) {
        Objects.requireNonNull(item, "item");
        if (previousValue < 0L) {
            throw new IllegalArgumentException("previousValue must be >= 0, got " + previousValue);
        }
        if (proposedValue < 0L) {
            throw new IllegalArgumentException("proposedValue must be >= 0, got " + proposedValue);
        }

        if (!settings.enabled()) {
            return PriceDropDecision.allowed(
                    previousValue, proposedValue, 0.0, "protection-disabled");
        }
        // 配置只接受无变体的 namespace:path，而运行时物品键可能携带 variantHash。
        // 因此精确豁免必须按物品 id 匹配，才能同时覆盖基础物品与其全部 NBT 变体。
        if (settings.exemptItems().contains(item)
                || settings.exemptItems().contains(ItemKey.of(item.itemId()))) {
            return PriceDropDecision.exempt(previousValue, proposedValue, 0.0, "exempt-item");
        }
        if (isNamespaceExempt(item)) {
            return PriceDropDecision.exempt(
                    previousValue, proposedValue, 0.0, "exempt-namespace");
        }
        if (proposedValue == previousValue) {
            return PriceDropDecision.allowed(
                    previousValue, proposedValue, 0.0, "unchanged");
        }
        if (proposedValue > previousValue) {
            return PriceDropDecision.allowed(
                    previousValue, proposedValue, 0.0, "increase");
        }
        if (previousValue <= 0L) {
            // 当前校验与判定顺序下该分支不可达（previous=0 且 proposed>=0 时只能是持平或涨价），
            // 保留它是为了在将来放宽数值校验时仍然安全放行。
            return PriceDropDecision.allowed(
                    previousValue, proposedValue, 0.0, "no-previous-value");
        }

        double dropRatio = (previousValue - proposedValue) / (double) previousValue;
        if (dropRatio > settings.maxAllowedDropRatio()) {
            PriceDropDecision decision = PriceDropDecision.quarantined(
                    previousValue, proposedValue, dropRatio, "drop-exceeds-threshold");
            synchronized (lock) {
                quarantine.removeIf(entry -> entry.item().equals(item));
                quarantine.add(new QuarantinedDecision(item, decision));
            }
            return decision;
        }
        return PriceDropDecision.allowed(
                previousValue, proposedValue, dropRatio, "drop-within-threshold");
    }

    /**
     * @return 每个物品最新一次被隔离的决策的不可变副本，按最近一次隔离发生的先后顺序排列
     */
    public List<PriceDropDecision> quarantined() {
        synchronized (lock) {
            List<PriceDropDecision> result = new ArrayList<>(quarantine.size());
            for (QuarantinedDecision entry : quarantine) {
                result.add(entry.decision());
            }
            return List.copyOf(result);
        }
    }

    /** @return 当前隔离条目数量 */
    public int quarantineCount() {
        synchronized (lock) {
            return quarantine.size();
        }
    }

    /**
     * 清空隔离列表。
     *
     * <p>只清空待处理的隔离记录；管理员审批标记 {@link #approvedItems()} 会保留下来，供诊断
     * 与审计继续查询。</p>
     */
    public void clearQuarantine() {
        synchronized (lock) {
            quarantine.clear();
        }
    }

    /**
     * 管理员显式批准某个物品。
     *
     * <p>批准只会移除该物品当前的最新隔离记录，并把被移除的决策返回给调用方；它不会修改
     * 任何已存储的 EMC 值。调用方必须只应用返回列表中的决策（列表为空表示没有待批准决策）。
     * {@link #approvedItems()} 仅记录审批历史，未来的降价仍会独立判定，绝不能据此自动放行。</p>
     *
     * @param item 被批准物品；{@code null} 会被忽略
     * @return 本次被移除的决策，按加入隔离区的顺序排列；没有待批准决策时为空列表
     */
    public List<PriceDropDecision> approve(ItemKey item) {
        if (item == null) {
            return List.of();
        }
        synchronized (lock) {
            List<PriceDropDecision> approved = new ArrayList<>();
            for (QuarantinedDecision entry : quarantine) {
                if (entry.item().equals(item)) {
                    approved.add(entry.decision());
                }
            }
            quarantine.removeIf(entry -> entry.item().equals(item));
            approvedItems.add(item);
            return List.copyOf(approved);
        }
    }

    /**
     * @return 已被管理员显式批准过的物品集合的不可变副本
     */
    public Set<ItemKey> approvedItems() {
        synchronized (lock) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(approvedItems));
        }
    }

    /** @return 该物品是否曾被管理员显式批准；仅用于诊断，绝不能作为放行条件 */
    public boolean isApproved(ItemKey item) {
        if (item == null) {
            return false;
        }
        synchronized (lock) {
            return approvedItems.contains(item);
        }
    }
    /**
     * 判断物品的命名空间是否在豁免集合中。
     *
     * <p>{@link Settings#exemptNamespaces()} 的类型是 {@code ResourceLocation}，但语义是
     * “命名空间豁免”，因此只比较命名空间部分；配置项中的路径仅用于让集合元素保持合法的
     * {@code ResourceLocation} 形式，不参与匹配。</p>
     */
    private boolean isNamespaceExempt(ItemKey item) {
        String namespace = item.itemId().getNamespace();
        for (ResourceLocation exempt : settings.exemptNamespaces()) {
            if (exempt.getNamespace().equals(namespace)) {
                return true;
            }
        }
        return false;
    }

    /** 隔离条目与物品身份的配对，用于让 {@link #approve(ItemKey)} 精确移除。 */
    private record QuarantinedDecision(ItemKey item, PriceDropDecision decision) {
    }
}
