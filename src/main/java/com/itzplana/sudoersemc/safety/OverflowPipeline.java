package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把溢出保护策略与审计日志串联起来的流水线。
 *
 * <p>数值小于或等于阈值时原样返回且不写审计；严格大于阈值时使用
 * {@link OverflowProtection#applyIdempotent(long)} 生成回落值，并记录一条
 * {@link OverflowReset}。</p>
 *
 * <h2>幂等性</h2>
 * <p>{@link OverflowProtection.Settings} 强制要求 {@code threshold > resetMax}，因此回落值
 * 总是落在 {@code [resetMin, resetMax]} 内，再次经过
 * {@link OverflowProtection#isOverflow(long)} 时必然为 {@code false}。对同一个已回落的值重复
 * 调用本流水线不会产生新的审计记录。</p>
 *
 * <p>保护被禁用时 {@code applyIdempotent} 返回原值；这种“策略没有实际改变数值”的情况不算
 * 一次重置，因此同样不写审计。</p>
 *
 * <h2>失败行为</h2>
 * <p>策略调用或日志写入抛出运行时异常时，异常会被捕获并记录警告。日志写入失败不会回滚
 * 已经计算出的回落值，因此返回值始终是策略结果；本类的方法不会向调用方抛出异常。</p>
 */
public final class OverflowPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/safety/OverflowPipeline");

    private final OverflowProtection protection;
    private final OverflowResetLog log;
    private final AtomicInteger resetsApplied = new AtomicInteger();
    private final AtomicInteger recordedResets = new AtomicInteger();

    /**
     * @param protection 溢出策略；不能为 {@code null}
     * @param log        审计日志；不能为 {@code null}
     */
    public OverflowPipeline(OverflowProtection protection, OverflowResetLog log) {
        this.protection = Objects.requireNonNull(protection, "protection");
        this.log = Objects.requireNonNull(log, "log");
    }

    /**
     * 按需应用溢出保护并写入审计。
     *
     * <p>只有“严格超过阈值且策略确实改变了数值”的调用才会产生审计记录。物品或作用域为
     * {@code null} 时仍然返回策略结果，但跳过审计写入，因为缺少归属信息或作用域的记录没有
     * 审计价值。</p>
     *
     * @param item  被处理的物品
     * @param value 待处理数值
     * @param scope 本次处理适用的作用域
     * @return 原值（未溢出或策略未改变数值）或回落后的值
     */
    public long applyAndAudit(ItemKey item, long value, OverflowScope scope) {
        boolean overflow;
        try {
            overflow = protection.isOverflow(value);
        } catch (RuntimeException e) {
            LOGGER.warn("检查溢出阈值失败，保持原值 {}（物品 {}）", value, item, e);
            return value;
        }
        if (!overflow) {
            return value;
        }

        long reset;
        try {
            reset = protection.applyIdempotent(value);
        } catch (RuntimeException e) {
            LOGGER.warn("应用溢出保护失败，保持原值 {}（物品 {}）", value, item, e);
            return value;
        }
        if (reset == value) {
            // 保护禁用或策略未改变数值：这不是一次实际重置，不写审计。
            return value;
        }

        if (item == null || scope == null) {
            LOGGER.warn("跳过溢出审计记录：item={} scope={}", item, scope);
            resetsApplied.incrementAndGet();
            return reset;
        }

        try {
            OverflowReset audit = new OverflowReset(
                    item,
                    value,
                    reset,
                    protection.settings().threshold(),
                    scope,
                    System.currentTimeMillis());
            log.record(audit);
            recordedResets.incrementAndGet();
        } catch (RuntimeException e) {
            LOGGER.warn("记录溢出重置审计失败（物品 {}）", item, e);
        }
        resetsApplied.incrementAndGet();
        return reset;
    }

    /**
     * 记录一条来自 ProjectE {@code custom_emc.json} 受控改写的重置。
     *
     * <p>与 {@link #applyAndAudit(ItemKey, long, OverflowScope)} 不同，数值已经由
     * {@code ProjectECustomEmcFile} 计算并写回，这里只负责生成作用域为
     * {@link OverflowScope#ENTIRE_CUSTOM_EMC} 的审计记录。审计写入失败不会抛出异常。</p>
     *
     * @param item          被改写的物品
     * @param originalValue 改写前的值，必须严格大于阈值
     * @param resetValue    写回的新值
     */
    public void recordCustomEmcReset(ItemKey item, long originalValue, long resetValue) {
        if (item == null) {
            return;
        }
        try {
            OverflowReset audit = new OverflowReset(
                    item,
                    originalValue,
                    resetValue,
                    protection.settings().threshold(),
                    OverflowScope.ENTIRE_CUSTOM_EMC,
                    System.currentTimeMillis());
            log.record(audit);
            recordedResets.incrementAndGet();
            resetsApplied.incrementAndGet();
        } catch (RuntimeException e) {
            LOGGER.warn("记录 ProjectE custom_emc.json 溢出重置审计失败（物品 {}）", item, e);
        }
    }

    /**
     * @return 本流水线使用的溢出策略，供文件改写等外围接线读取阈值与回落区间
     */
    public OverflowProtection protection() {
        return protection;
    }

    /**
     * @return 本流水线实例实际改变数值的次数；审计写入失败但值确实被重置时也计入
     */
    public int resetsApplied() {
        return resetsApplied.get();
    }

    /**
     * @return 审计日志当前保留的记录数（可能包含启动时从磁盘加载的历史记录）；读取日志失败时
     *         退回本实例成功记录的条数
     */
    public int auditCount() {
        try {
            return log.entries().size();
        } catch (RuntimeException e) {
            LOGGER.warn("读取溢出审计日志条目数失败", e);
            return recordedResets.get();
        }
    }
}
