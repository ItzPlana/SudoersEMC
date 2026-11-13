package com.itzplana.sudoersemc.task;

import java.util.Objects;

/**
 * 扫描进度的一次不可变快照。
 *
 * <p>{@code processed} 与 {@code total} 描述当前阶段的完成度。{@code total} 为 0 表示总量
 * 尚未知（例如快照尚未采集），此时 {@link #fraction()} 返回 0。{@code startedAtMillis}
 * 在整次扫描期间保持不变，便于调用方显示从请求扫描开始计算的总耗时；
 * {@code lastUpdateMillis} 则随每次 {@link #advance} 更新。</p>
 *
 * <p>本记录本身不可变，但不负责线程安全发布。{@link ScanManager} 通过 {@code volatile}
 * 字段保存最新实例，读取方因此总能看到字段完整的进度对象，而不会观察到写了一半的状态。</p>
 *
 * @param phase            当前阶段
 * @param processed        当前阶段已处理数量，构造时会被夹到合法范围
 * @param total            当前阶段总数量；小于等于 0 表示未知
 * @param startedAtMillis  扫描开始时的 {@link System#currentTimeMillis()}，不得为负
 * @param lastUpdateMillis 最近一次进度更新的时间戳，不得为负
 * @param detail           面向用户的中文细节说明；{@code null} 会被归一化为空串
 */
public record ScanProgress(
        ScanPhase phase,
        int processed,
        int total,
        long startedAtMillis,
        long lastUpdateMillis,
        String detail
) {

    public ScanProgress {
        Objects.requireNonNull(phase, "phase");
        if (startedAtMillis < 0L) {
            throw new IllegalArgumentException("startedAtMillis 必须 >= 0，实际为 " + startedAtMillis);
        }
        if (lastUpdateMillis < 0L) {
            throw new IllegalArgumentException("lastUpdateMillis 必须 >= 0，实际为 " + lastUpdateMillis);
        }
        if (total > 0) {
            // 已知总量时，已处理数量必须落在 [0, total] 内。
            processed = Math.max(0, Math.min(total, processed));
        } else {
            // 总量未知时仍不允许出现负的完成数量。
            processed = Math.max(0, processed);
        }
        detail = detail == null ? "" : detail;
    }

    /**
     * 返回当前阶段完成比例。
     *
     * @return 总量未知时返回 0.0；否则返回 {@code min(1.0, processed / total)}
     */
    public double fraction() {
        return total <= 0 ? 0.0 : Math.min(1.0, processed / (double) total);
    }

    /**
     * 返回从扫描开始到最近一次更新的耗时。
     *
     * @return 非负毫秒数；若时间戳顺序异常则返回 0
     */
    public long elapsedMillis() {
        return Math.max(0L, lastUpdateMillis - startedAtMillis);
    }

    /**
     * @return 当前阶段是否仍在推进，等价于 {@link ScanPhase#isActive()}
     */
    public boolean isRunning() {
        return phase.isActive();
    }

    /**
     * 基于当前进度生成下一个进度快照。
     *
     * <p>{@code startedAtMillis} 原样保留，{@code lastUpdateMillis} 取当前系统时间。
     * 新对象的 {@code processed}/{@code total} 会由构造器重新校验并夹取，因此调用方
     * 可以传入尚未夹取的累计值。</p>
     *
     * @param phase     下一阶段
     * @param processed 下一阶段的已处理数量
     * @param total     下一阶段的总数量
     * @param detail    中文细节说明，可为 {@code null}
     * @return 全新的进度实例，绝不修改当前实例
     */
    public ScanProgress advance(ScanPhase phase, int processed, int total, String detail) {
        return new ScanProgress(phase, processed, total, startedAtMillis,
                System.currentTimeMillis(), detail);
    }

    /**
     * 创建空闲状态下的初始进度。
     *
     * @return 阶段为 {@link ScanPhase#IDLE}、进度为零且不具备运行语义的实例
     */
    public static ScanProgress idle() {
        return new ScanProgress(ScanPhase.IDLE, 0, 0, 0L, 0L, "");
    }
}
