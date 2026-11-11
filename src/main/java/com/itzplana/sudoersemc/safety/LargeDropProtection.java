package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;

/**
 * 大幅降价保护：当新值相对旧值下跌超过配置比例时，把这次变更放入隔离区，等待管理员确认。
 *
 * <p>只有降价才可能触发隔离；持平与涨价永远放行。阈值比较使用严格大于：降幅恰好等于
 * {@link Settings#maxAllowedDropRatio()} 时仍然放行。</p>
 *
 * <p>本类只做决策，不保存任何 EMC 数值。{@link #approve(ItemKey)} 只会移除该物品当前的
 * 隔离记录，不会修改、放宽或重写任何已存储的数值。</p>
 */
public final class LargeDropProtection {

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
     * @param exemptItems          按物品精确豁免的条目
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
     * 判定一次数值变更。
     *
     * <p>判定顺序固定：保护禁用、物品豁免、命名空间豁免、数值不变、数值上涨、没有历史值，
     * 最后才计算降幅并与阈值比较。降价路径之外的结果 {@code dropRatio} 均为 {@code 0.0}。</p>
     *
     * @param item          物品身份，用于检查豁免规则
     * @param previousValue 记录中的旧值，必须 {@code >= 0}
     * @param proposedValue 拟写入的新值，必须 {@code >= 0}
     * @return 判定结果；超过阈值的降价会同时写入隔离列表
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
        if (settings.exemptItems().contains(item)) {
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
                quarantine.add(new QuarantinedDecision(item, decision));
            }
            return decision;
        }
        return PriceDropDecision.allowed(
                previousValue, proposedValue, dropRatio, "drop-within-threshold");
    }

    /**
     * @return 自构造以来被隔离的全部决策的不可变副本，按隔离发生的先后顺序排列
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
     * <p>批准只会移除该物品当前的全部隔离记录并把物品加入 {@link #approvedItems()}；它不会
     * 修改任何已存储的 EMC 值，也不会让未来的降价自动获得豁免——每次新的降价仍会独立判定。</p>
     *
     * @param item 被批准物品；{@code null} 会被忽略
     */
    public void approve(ItemKey item) {
        if (item == null) {
            return;
        }
        synchronized (lock) {
            approvedItems.add(item);
            quarantine.removeIf(entry -> entry.item().equals(item));
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

    /** @return 该物品是否曾被管理员显式批准 */
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
