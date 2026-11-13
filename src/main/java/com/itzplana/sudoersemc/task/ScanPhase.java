package com.itzplana.sudoersemc.task;

/**
 * 一次扫描的生命周期阶段。
 *
 * <p>正常流程按 {@link #COLLECTING_SNAPSHOT} → {@link #SOLVING} → {@link #APPLYING} →
 * {@link #SAVING} → {@link #COMPLETED} 推进。{@link #CANCELLED} 与 {@link #FAILED} 是提前
 * 终止状态；{@link #IDLE} 表示尚未开始，或已被 {@code ScanManager#reset()} 重置。</p>
 *
 * <p>枚举顺序不代表比较关系；调用方应始终使用引用相等或 {@code switch} 判断阶段。</p>
 */
public enum ScanPhase {

    IDLE("空闲"),
    COLLECTING_SNAPSHOT("采集快照"),
    SOLVING("求解配方"),
    APPLYING("应用结果"),
    SAVING("持久化"),
    COMPLETED("已完成"),
    CANCELLED("已取消"),
    FAILED("失败");

    private final String label;

    ScanPhase(String label) {
        this.label = label;
    }

    /**
     * @return 用于命令输出、日志与进度界面的中文显示名称
     */
    public String label() {
        return label;
    }

    /**
     * 判断该阶段是否代表一次仍在推进的扫描。
     *
     * <p>只有采集、求解、应用与持久化四个中间阶段返回 {@code true}。空闲与三种终止状态
     * 均返回 {@code false}，调用方据此决定是否继续驱动 {@code ScanManager#tick()}，
     * 从而避免扫描结束后仍占用每个 tick 的预算。</p>
     *
     * @return 四个中间阶段返回 {@code true}，其余阶段返回 {@code false}
     */
    public boolean isActive() {
        return this == COLLECTING_SNAPSHOT
                || this == SOLVING
                || this == APPLYING
                || this == SAVING;
    }
}
