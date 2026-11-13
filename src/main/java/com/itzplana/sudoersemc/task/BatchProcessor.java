package com.itzplana.sudoersemc.task;

/**
 * 固定批量与自适应批量之间的纯决策组件。
 *
 * <p>本类不依赖 Minecraft，也不执行任何扫描工作。它只根据上一批的实际耗时与每 tick 预算
 * 推荐下一批的大小；真实工作由服务器线程完成后，再通过
 * {@link #recordBatch(int, long)} 回写实际批量与耗时。</p>
 *
 * <h2>自适应算法</h2>
 * <ul>
 *   <li><strong>固定模式：</strong>{@link #nextBatchSize(long, long)} 永远返回构造时传入的
 *       {@code configuredItemsPerTick}，完全忽略耗时样本。</li>
 *   <li><strong>没有有效样本：</strong>自适应模式下，上一批耗时或预算小于等于 0 时，
 *       直接沿用当前批量，避免在数据不足时做出错误调整。</li>
 *   <li><strong>扩容：</strong>上一批耗时不超过预算的 50% 时，按
 *       {@code max(size + 1, size * 5 / 4)} 扩大（至少 +1，避免 size=1 时被整数除法吞掉）。</li>
 *   <li><strong>缩容：</strong>上一批耗时严格超过预算的 100% 时，按 {@code size / 2}
 *       缩小 50%（整数除法向零截断）。</li>
 *   <li><strong>保持：</strong>耗时落在 {@code (50%, 100%]} 区间时维持当前批量。</li>
 *   <li><strong>上下界：</strong>扩容与缩容的结果都会被夹到
 *       {@code [1, configuredItemsPerTick * 8]}。下界防止批量归零导致扫描停滞；上界防止
 *       单 tick 的服务器停顿过长。{@code configuredItemsPerTick * 8} 使用 64 位计算，
 *       并在接近 {@link Integer#MAX_VALUE} 时饱和。</li>
 * </ul>
 *
 * <p>{@link #nextBatchSize(long, long)} 不修改内部状态；只有
 * {@link #recordBatch(int, long)} 会推进批量与批次计数。这一分离使调用方可以先询问建议
 * 批量、执行工作，再记录实际结果。</p>
 */
public final class BatchProcessor {

    /** 扩容系数 {@code 5 / 4}，即增长 25%。 */
    private static final int GROWTH_NUMERATOR = 5;
    private static final int GROWTH_DENOMINATOR = 4;

    /** 自适应批量的上限倍率。 */
    private static final int MAX_MULTIPLIER = 8;

    private final int configuredItemsPerTick;
    private final boolean adaptive;

    private int currentBatchSize;
    private long batchesProcessed;
    private long lastBatchNanos;

    /**
     * @param configuredItemsPerTick 配置的每 tick 基础处理量，必须大于等于 1
     * @param adaptive               是否根据耗时样本自动调整批量
     * @throws IllegalArgumentException 当 {@code configuredItemsPerTick < 1} 时
     */
    public BatchProcessor(int configuredItemsPerTick, boolean adaptive) {
        if (configuredItemsPerTick < 1) {
            throw new IllegalArgumentException(
                    "configuredItemsPerTick 必须 >= 1，实际为 " + configuredItemsPerTick);
        }
        this.configuredItemsPerTick = configuredItemsPerTick;
        this.adaptive = adaptive;
        this.currentBatchSize = configuredItemsPerTick;
    }

    /**
     * 计算下一批建议处理量。
     *
     * <p>非自适应模式直接返回配置值。自适应模式根据 {@code lastBatchNanos} 占
     * {@code budgetNanos} 的比例决定扩容、缩容或保持，具体规则见类文档。方法没有副作用，
     * 同一状态下重复调用会得到相同结果；只有 {@link #recordBatch(int, long)} 会改变
     * 后续计算的基准批量。</p>
     *
     * @param lastBatchNanos 上一批实际耗时（纳秒）；小于等于 0 表示没有有效样本
     * @param budgetNanos    本 tick 允许用于扫描的时间预算（纳秒）；小于等于 0 表示预算未知
     * @return 建议批量，范围始终为 {@code [1, configuredItemsPerTick * 8]}
     */
    public int nextBatchSize(long lastBatchNanos, long budgetNanos) {
        if (!adaptive) {
            return configuredItemsPerTick;
        }
        if (lastBatchNanos <= 0L || budgetNanos <= 0L) {
            return currentBatchSize;
        }

        long maximum = maximumBatchSize();
        // 用 budgetNanos / 2 比较，避免 lastBatchNanos * 2 溢出。
        if (lastBatchNanos <= budgetNanos / 2L) {
            long grown = Math.max((long) currentBatchSize + 1L,
                    (long) currentBatchSize * GROWTH_NUMERATOR / GROWTH_DENOMINATOR);
            return (int) clamp(grown, maximum);
        }
        if (lastBatchNanos > budgetNanos) {
            long shrunk = currentBatchSize / 2L;
            return (int) clamp(shrunk, maximum);
        }
        return currentBatchSize;
    }

    /**
     * 记录一批工作的实际结果，供后续自适应计算使用。
     *
     * <p>批量会被夹到 {@code [1, configuredItemsPerTick * 8]}；耗时负数按 0 处理。
     * 每次调用都会让 {@link #batchesProcessed()} 加一。</p>
     *
     * @param size         本批实际处理量
     * @param elapsedNanos 本批实际耗时（纳秒）
     */
    public void recordBatch(int size, long elapsedNanos) {
        this.currentBatchSize = (int) clamp(size, maximumBatchSize());
        this.lastBatchNanos = Math.max(0L, elapsedNanos);
        this.batchesProcessed++;
    }

    /**
     * @return 当前自适应基准批量；尚未记录任何批次时等于配置值
     */
    public int currentBatchSize() {
        return currentBatchSize;
    }

    /**
     * @return 已记录的批次总数
     */
    public long batchesProcessed() {
        return batchesProcessed;
    }

    /**
     * @return 构造时配置的基础处理量
     */
    public int configuredItemsPerTick() {
        return configuredItemsPerTick;
    }

    /**
     * @return 是否启用自适应批量
     */
    public boolean adaptive() {
        return adaptive;
    }

    /**
     * @return 最近一次记录的批次耗时（纳秒）；尚未记录时为 0
     */
    public long lastBatchNanos() {
        return lastBatchNanos;
    }

    /**
     * 计算自适应批量上限，使用 64 位中间值避免 {@code configuredItemsPerTick * 8} 溢出。
     */
    private int maximumBatchSize() {
        long maximum = (long) configuredItemsPerTick * MAX_MULTIPLIER;
        return (int) Math.min(maximum, Integer.MAX_VALUE);
    }

    /**
     * 将批量夹到 {@code [1, maximum]}。
     */
    private static long clamp(long value, long maximum) {
        return Math.max(1L, Math.min(maximum, value));
    }
}
