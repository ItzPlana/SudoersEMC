package com.itzplana.sudoersemc.task;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.annotations.Nullable;

/**
 * 单次扫描的轻量诊断收集器。
 *
 * <p>用法是“阶段结束打点”：每次 {@link #mark(String)} 会把距离上一次打点的耗时累加到
 * 指定阶段，因此同一阶段可以重复标记并正确累加。内部以纳秒保存，读取时再换算为毫秒，
 * 避免多次短阶段被逐次截断为零。峰值堆内存由 {@link #recordMemory()} 主动采样，
 * 未采样时报告显示 0.0 MiB。</p>
 *
 * <p>逐 tick 耗时通过 {@link #recordTick(long)} 记录。P95 使用最近秩法（nearest-rank）：
 * 对 {@code n} 个样本升序排序后取第 {@code ceil(0.95 * n)} 个值（1 基序号）。因此
 * 0 个样本返回 0，1 个样本返回该样本本身，多值不会做插值。</p>
 *
 * <p><strong>线程约定：</strong>本类面向单线程使用。扫描由服务器线程驱动，诊断读取通常
 * 也在同一线程完成，因此没有为并发写入做额外同步。所有公开方法都不会抛出异常：
 * {@code null} 或空白阶段名会被忽略，查询未知阶段返回 0。</p>
 */
public final class ScanDiagnostics {

    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    /** 最近秩法使用的 P95 比例。 */
    private static final double P95_RATIO = 0.95D;

    /** 复用同一个管理接口代理，tick 采样只调用 getHeapMemoryUsage()。 */
    private static final MemoryMXBean MEMORY_MX_BEAN = resolveMemoryBean();

    /** 按首次出现顺序保序，便于报告稳定输出。 */
    private final Map<String, Long> stageNanos = new LinkedHashMap<>();

    /** 每个 tick 的耗时样本，单位纳秒；按调用顺序保存。 */
    private final List<Long> tickNanos = new ArrayList<>();

    private long lastMarkNanos;
    private long scanStartedNanos;
    private long totalTickNanos;
    private long maxTickNanos;
    private long peakHeapBytes;
    private long heapBytes;

    /**
     * 创建诊断收集器，并以当前时刻作为第一次打点与整次扫描计时的基准。
     */
    public ScanDiagnostics() {
        long now = System.nanoTime();
        this.lastMarkNanos = now;
        this.scanStartedNanos = now;
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
     * 记录一个 tick 的实际耗时。
     *
     * <p>负数按 0 处理，样本数量不设硬上限；扫描器按 tick 批量推进，实际样本量受扫描
     * 时长约束。P95 只在读取时排序，不影响 tick 主路径。</p>
     *
     * @param elapsedNanos 本 tick 实际耗时（纳秒）
     */
    public void recordTick(long elapsedNanos) {
        long sample = Math.max(0L, elapsedNanos);
        tickNanos.add(sample);
        totalTickNanos += sample;
        maxTickNanos = Math.max(maxTickNanos, sample);
    }

    /**
     * @return 已记录的 tick 样本数量
     */
    public int tickCount() {
        return tickNanos.size();
    }

    /**
     * @return 所有 tick 样本的累计耗时（纳秒）
     */
    public long totalTickNanos() {
        return totalTickNanos;
    }

    /**
     * @return 最大 tick 耗时（纳秒）；没有样本时为 0
     */
    public long maxTickNanos() {
        return maxTickNanos;
    }

    /**
     * @return 平均 tick 耗时（纳秒）；没有样本时为 0.0
     */
    public double averageTickNanos() {
        return tickNanos.isEmpty() ? 0.0D : totalTickNanos / (double) tickNanos.size();
    }

    /**
     * 返回 P95 tick 耗时（纳秒，最近秩法）。
     *
     * <p>0 个样本返回 0；1 个样本返回该值；多值取升序后的
     * {@code ceil(0.95 * n)} 个样本，不做线性插值。</p>
     *
     * @return P95 耗时（纳秒）
     */
    public long p95TickNanos() {
        int size = tickNanos.size();
        if (size == 0) {
            return 0L;
        }
        long[] sorted = new long[size];
        for (int i = 0; i < size; i++) {
            sorted[i] = tickNanos.get(i);
        }
        Arrays.sort(sorted);
        int rank = (int) Math.ceil(P95_RATIO * size);
        int index = Math.max(0, Math.min(size - 1, rank - 1));
        return sorted[index];
    }

    /**
     * @return 最大 tick 耗时（毫秒）
     */
    public long maxTickMillis() {
        return maxTickNanos / NANOS_PER_MILLI;
    }

    /**
     * @return 平均 tick 耗时（毫秒）
     */
    public double averageTickMillis() {
        return averageTickNanos() / NANOS_PER_MILLI;
    }

    /**
     * @return P95 tick 耗时（毫秒）
     */
    public long p95TickMillis() {
        return p95TickNanos() / NANOS_PER_MILLI;
    }

    /**
     * 采样当前已使用的堆内存，并更新峰值。
     *
     * <p>使用 {@link MemoryMXBean#getHeapMemoryUsage()} 的 {@code used} 字段；该值来自
     * JVM 内存管理接口，比 {@code Runtime} 的近似计算更直接。若管理接口不可用，退回
     * {@code totalMemory() - freeMemory()}，保证诊断采样永远不会影响扫描主路径。</p>
     */
    public void recordMemory() {
        long used;
        try {
            MemoryMXBean bean = MEMORY_MX_BEAN;
            used = bean == null
                    ? Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                    : bean.getHeapMemoryUsage().getUsed();
        } catch (RuntimeException | LinkageError failure) {
            Runtime runtime = Runtime.getRuntime();
            used = runtime.totalMemory() - runtime.freeMemory();
        }
        used = Math.max(0L, used);
        heapBytes = used;
        peakHeapBytes = Math.max(peakHeapBytes, used);
    }

    @Nullable
    private static MemoryMXBean resolveMemoryBean() {
        try {
            return ManagementFactory.getMemoryMXBean();
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
    }

    /**
     * @return 已观察到的峰值堆内存字节数；尚未采样时为 0
     */
    public long peakHeapBytes() {
        return peakHeapBytes;
    }

    /**
     * @return 最近一次 {@link #recordMemory()} 采样到的堆内存字节数；尚未采样时为 0
     */
    public long heapBytes() {
        return heapBytes;
    }

    /**
     * @return 从 {@link #reset()} 或构造时刻到现在的墙钟耗时毫秒数
     */
    public long elapsedSinceResetMillis() {
        return Math.max(0L, (System.nanoTime() - scanStartedNanos) / NANOS_PER_MILLI);
    }

    /**
     * 生成多行中文诊断报告。
     *
     * <p>依次输出每个阶段的毫秒耗时、总计耗时、逐 tick 统计（最大/平均/P95），以及保留
     * 一位小数的峰值与当前堆内存 MiB。即使没有任何阶段或 tick 记录，报告仍然包含固定行，
     * 方便日志解析。</p>
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
        if (tickNanos.isEmpty()) {
            report.append("  逐 tick: 无采样\n");
        } else {
            report.append("  逐 tick: 样本 ")
                    .append(tickCount())
                    .append("，最大 ")
                    .append(formatMillis(maxTickNanos))
                    .append("，平均 ")
                    .append(formatMillis(averageTickNanos()))
                    .append("，P95 ")
                    .append(formatMillis(p95TickNanos()))
                    .append('\n');
        }
        report.append("  峰值堆内存: ").append(formatMiB(peakHeapBytes)).append('\n');
        report.append("  报告时堆内存: ").append(formatMiB(heapBytes));
        return report.toString();
    }

    /**
     * 清空所有阶段耗时、tick 样本与内存采样，并以当前时刻重新开始计时。
     *
     * <p>用于在复用同一个诊断实例开始下一次扫描前丢弃旧数据；调用后
     * {@link #stageMillis()} 为空，{@link #totalMillis()}、{@link #tickCount()} 与
     * {@link #peakHeapBytes()} 均为 0。</p>
     */
    public void reset() {
        stageNanos.clear();
        tickNanos.clear();
        totalTickNanos = 0L;
        maxTickNanos = 0L;
        peakHeapBytes = 0L;
        heapBytes = 0L;
        long now = System.nanoTime();
        lastMarkNanos = now;
        scanStartedNanos = now;
    }

    private static String formatMiB(long bytes) {
        return String.format(Locale.ROOT, "%.1f", Math.max(0L, bytes) / BYTES_PER_MIB) + " MiB";
    }

    private static String formatMillis(long nanos) {
        return String.format(Locale.ROOT, "%.2f", Math.max(0L, nanos) / 1_000_000.0) + " ms";
    }

    private static String formatMillis(double nanos) {
        return String.format(Locale.ROOT, "%.2f", Math.max(0.0D, nanos) / 1_000_000.0) + " ms";
    }
}
