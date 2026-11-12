package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger LOGGER =
            LoggerFactory.getLogger("sudoersemc/safety/LargeDropProtection");

    private final Settings settings;
    private final Object lock = new Object();
    private final List<QuarantinedDecision> quarantine = new ArrayList<>();
    private final Set<ItemKey> approvedItems = new LinkedHashSet<>();

    /** 可选持久化存储；生产环境注入后每次隔离都会同步记录。 */
    @Nullable
    private final QuarantinedDropsStore quarantineStore;

    /**
     * @param settings 保护配置；不能为 {@code null}
     */
    public LargeDropProtection(Settings settings) {
        this(settings, null);
    }

    /**
     * @param settings        保护配置；不能为 {@code null}
     * @param quarantineStore 隔离记录持久化存储；{@code null} 时只保留内存状态
     */
    public LargeDropProtection(Settings settings, @Nullable QuarantinedDropsStore quarantineStore) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.quarantineStore = quarantineStore;
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
                quarantine.removeIf(entry -> entry.item().equals(item));
                quarantine.add(new QuarantinedDecision(item, decision));
            }
            recordQuarantine(item, decision);
            return decision;
        }
        return PriceDropDecision.allowed(
                previousValue, proposedValue, dropRatio, "drop-within-threshold");
    }

    /**
     * 把一次隔离写入可选的持久化存储。
     *
     * <p>持久化失败只记录警告，不影响本次判定与内存隔离列表；存储自身已经对输入做校验并
     * 对同一物品去重。</p>
     */
    private void recordQuarantine(ItemKey item, PriceDropDecision decision) {
        QuarantinedDropsStore store = quarantineStore;
        if (store == null) {
            return;
        }
        try {
            store.record(new QuarantinedDropsStore.QuarantinedDrop(
                    item,
                    decision.previousValue(),
                    decision.proposedValue(),
                    decision.dropRatio(),
                    decision.reason(),
                    System.currentTimeMillis(),
                    false));
        } catch (RuntimeException failure) {
            // 存储层已经承诺不抛出异常；这里仍然兜底，避免未来实现变化阻断扫描。
            LOGGER.warn("写入隔离记录失败（物品 {}）", item, failure);
        }
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
