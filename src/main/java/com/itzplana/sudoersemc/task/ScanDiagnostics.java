package com.itzplana.sudoersemc.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 单次扫描的轻量诊断收集器。
 *
 * <p>用法是“阶段结束打点”：每次 {@link #mark(String)} 会把距离上一次打点的耗时累加到
 * 指定阶段，因此同一阶段可以重复标记并正确累加。内部以纳秒保存，读取时再换算为毫秒，
 * 避免多次短阶段被逐次截断为零。峰值堆内存由 {@link #recordMemory()} 主动采样，
 * 未采样时报告显示 0.0 MiB。</p>
 *
 * <p><strong>线程约定：</strong>本类面向单线程使用。扫描由服务器线程驱动，诊断读取通常
 * 也在同一线程完成，因此没有为并发写入做额外同步。所有公开方法都不会抛出异常：
 * {@code null} 或空白阶段名会被忽略，查询未知阶段返回 0。</p>
 */
public final class ScanDiagnostics {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    /** 按首次出现顺序保序，便于报告稳定输出。 */
    private final Map<String, Long> stageNanos = new LinkedHashMap<>();

    private long lastMarkNanos;
    private long peakHeapBytes;

    /**
     * 创建诊断收集器，并以当前时刻作为第一次打点的计时基准。
     */
    public ScanDiagnostics() {
        this.lastMarkNanos = System.nanoTime();
    }

    /**
     * 记录一个阶段的结束，并把距离上一次打点的耗时累加到该阶段。
     *
     * <p>{@code null}、空串或仅含空白的阶段名会被静默忽略，计时基准不会前移，从而避免
     * 无效打点吞掉真实耗时。单次打点永远不会产生负时长。</p>
     *
     * @param stage 阶段名称，通常使用 {@link ScanPhase#label()} 或稳定的英文标识
     */
    public void mark(String stage) {
        if (stage == null || stage.isBlank()) {
            return;
        }
        long now = System.nanoTime();
        long elapsed = Math.max(0L, now - lastMarkNanos);
        lastMarkNanos = now;
        stageNanos.merge(stage, elapsed, Long::sum);
    }

    /**
     * 查询指定阶段的累计耗时。
     *
     * @param stage 阶段名称，可为 {@code null}
     * @return 累计毫秒数；未知阶段或 {@code null} 返回 0
     */
    public long stageMillis(String stage) {
        if (stage == null) {
            return 0L;
        }
        Long nanos = stageNanos.get(stage);
        return nanos == null ? 0L : nanos / NANOS_PER_MILLI;
    }

    /**
     * 返回所有阶段耗时的只读快照。
     *
     * <p>返回的映射按阶段首次打点顺序排列，修改它会抛出
     * {@link UnsupportedOperationException}，因此调用方可以安全地跨方法传递。</p>
     *
     * @return 不可变的“阶段名 → 毫秒”映射
     */
    public Map<String, Long> stageMillis() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : stageNanos.entrySet()) {
            snapshot.put(entry.getKey(), entry.getValue() / NANOS_PER_MILLI);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * @return 所有已记录阶段的累计毫秒数之和
     */
    public long totalMillis() {
        long totalNanos = 0L;
        for (long nanos : stageNanos.values()) {
            totalNanos += nanos;
        }
        return totalNanos / NANOS_PER_MILLI;
    }

    /**
     * 采样当前已使用的堆内存，并更新峰值。
     *
     * <p>使用 {@code totalMemory() - freeMemory()} 作为近似值；该值包含尚未回收的对象，
     * 因此只适合观察趋势，不适合作为精确内存计量。</p>
     */
    public void recordMemory() {
        Runtime runtime = Runtime.getRuntime();
        long used = runtime.totalMemory() - runtime.freeMemory();
        peakHeapBytes = Math.max(peakHeapBytes, Math.max(0L, used));
    }

    /**
     * @return 已观察到的峰值堆内存字节数；尚未采样时为 0
     */
    public long peakHeapBytes() {
        return peakHeapBytes;
    }

    /**
     * 生成多行中文诊断报告。
     *
     * <p>依次输出每个阶段的毫秒耗时、总计耗时，以及保留一位小数的峰值堆内存 MiB。
     * 即使没有任何阶段记录，报告仍然包含总计与峰值两行，方便日志解析。</p>
     *
     * @return 以换行分隔的中文报告文本
     */
    public String formatReport() {
        StringBuilder report = new StringBuilder();
        report.append("扫描诊断报告\n");
        if (stageNanos.isEmpty()) {
            report.append("  无阶段记录\n");
        } else {
            for (Map.Entry<String, Long> entry : stageNanos.entrySet()) {
                report.append("  ")
                        .append(entry.getKey())
                        .append(": ")
                        .append(entry.getValue() / NANOS_PER_MILLI)
                        .append(" ms\n");
            }
        }
        report.append("  总计: ").append(totalMillis()).append(" ms\n");
        report.append("  峰值堆内存: ")
                .append(String.format(Locale.ROOT, "%.1f", peakHeapBytes / BYTES_PER_MIB))
                .append(" MiB");
        return report.toString();
    }

    /**
     * 清空所有阶段耗时与峰值内存，并以当前时刻重新开始计时。
     *
     * <p>用于在复用同一个诊断实例开始下一次扫描前丢弃旧数据；调用后
     * {@link #stageMillis()} 为空，{@link #totalMillis()} 与 {@link #peakHeapBytes()}
     * 均为 0。</p>
     */
    public void reset() {
        stageNanos.clear();
        peakHeapBytes = 0L;
        lastMarkNanos = System.nanoTime();
    }
}
