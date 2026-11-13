package com.itzplana.sudoersemc.safety;

import java.util.Objects;

/**
 * 一次“是否允许应用某个降价”的判定结果。
 *
 * <p>{@code allowed=true} 表示调用方可以继续写入新值；{@code allowed=false} 表示该降价被
 * 隔离，需要管理员确认后才能应用。{@code dropRatio} 是相对降幅
 * {@code (previousValue - proposedValue) / previousValue}，只在真正参与阈值比较的路径上
 * 计算；未参与比较的路径（禁用、豁免、不变、涨价、没有历史值）统一记为 {@code 0.0}。</p>
 *
 * @param allowed       是否允许应用新值
 * @param previousValue 记录中的旧值，必须 {@code >= 0}
 * @param proposedValue 拟写入的新值，必须 {@code >= 0}
 * @param dropRatio     相对降幅，必须为有限且 {@code >= 0} 的数值
 * @param reason        机器可读的判定原因
 */
public record PriceDropDecision(
        boolean allowed,
        long previousValue,
        long proposedValue,
        double dropRatio,
        String reason
) {

    public PriceDropDecision {
        if (previousValue < 0L) {
            throw new IllegalArgumentException("previousValue must be >= 0, got " + previousValue);
        }
        if (proposedValue < 0L) {
            throw new IllegalArgumentException("proposedValue must be >= 0, got " + proposedValue);
        }
        if (!Double.isFinite(dropRatio) || dropRatio < 0.0) {
            throw new IllegalArgumentException("dropRatio must be finite and >= 0, got " + dropRatio);
        }
        Objects.requireNonNull(reason, "reason");
    }

    /** 允许应用新值。 */
    public static PriceDropDecision allowed(
            long previousValue, long proposedValue, double dropRatio, String reason) {
        return new PriceDropDecision(true, previousValue, proposedValue, dropRatio, reason);
    }

    /** 不允许应用新值，等待管理员审批。 */
    public static PriceDropDecision quarantined(
            long previousValue, long proposedValue, double dropRatio, String reason) {
        return new PriceDropDecision(false, previousValue, proposedValue, dropRatio, reason);
    }

    /**
     * 豁免本次降价检查。
     *
     * <p>豁免意味着“保护策略不介入”，因此结果仍然允许应用新值，只是原因与普通放行不同，
     * 便于诊断时区分。</p>
     */
    public static PriceDropDecision exempt(
            long previousValue, long proposedValue, double dropRatio, String reason) {
        return new PriceDropDecision(true, previousValue, proposedValue, dropRatio, reason);
    }

    public boolean changed() {
        return previousValue != proposedValue;
    }
}
