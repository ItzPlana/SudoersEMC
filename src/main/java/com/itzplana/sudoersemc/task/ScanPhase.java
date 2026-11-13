package com.itzplana.sudoersemc.task;

/**
 * Lifecycle phases of one scan.
 *
 * <p>The normal flow advances {@link #COLLECTING_SNAPSHOT} → {@link #SOLVING} → {@link #APPLYING} →
 * {@link #SAVING} → {@link #COMPLETED}. {@link #CANCELLED} and {@link #FAILED} are early-termination
 * states; {@link #IDLE} means not yet started, or reset by {@code ScanManager#reset()}.</p>
 *
 * <p>Enum order does not imply a comparison relation; callers should always use reference equality or
 * {@code switch} to test the phase.</p>
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
     * @return the Chinese display name used in command output, logs and the progress UI
     */
    public String label() {
        return label;
    }

    /**
     * Whether this phase represents a scan still in progress.
     *
     * <p>Only the four intermediate phases — collecting, solving, applying and saving — return
     * {@code true}. Idle and the three terminal states all return {@code false}; the caller uses this to
     * decide whether to keep driving {@code ScanManager#tick()}, avoiding consuming a per-tick budget
     * after the scan has finished.</p>
     *
     * @return {@code true} for the four intermediate phases, {@code false} otherwise
     */
    public boolean isActive() {
        return this == COLLECTING_SNAPSHOT
                || this == SOLVING
                || this == APPLYING
                || this == SAVING;
    }
}
