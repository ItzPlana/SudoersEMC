package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * {@link BatchProcessor} 的批量决策验证。
 *
 * <p>覆盖固定模式、自适应扩容、自适应缩容、上下界夹取、区间保持、记账与非法构造参数。
 * 所有断言都使用整数结果，避免浮点误差。</p>
 */
class BatchProcessorTest {

    @Test
    void fixedModeAlwaysReturnsConfiguredSize() {
        BatchProcessor processor = new BatchProcessor(25, false);

        assertEquals(25, processor.nextBatchSize(0L, 0L), "固定模式忽略无效样本");
        assertEquals(25, processor.nextBatchSize(1L, 1_000L), "固定模式不做扩容");
        assertEquals(25, processor.nextBatchSize(1_000_000L, 1_000L), "固定模式不做缩容");

        processor.recordBatch(7, 500L);
        assertEquals(25, processor.nextBatchSize(1L, 1_000L), "记录批次后仍返回配置值");
        assertEquals(25, processor.configuredItemsPerTick());
        assertFalse(processor.adaptive());
    }

    @Test
    void adaptiveGrowsByTwentyFivePercentWhenUnderHalfBudget() {
        BatchProcessor processor = new BatchProcessor(20, true);

        // 20 * 5 / 4 = 25
        assertEquals(25, processor.nextBatchSize(100L, 1_000L));
        // 恰好 50% 仍属于“不超过一半”，继续扩容。
        assertEquals(25, processor.nextBatchSize(500L, 1_000L));

        processor.recordBatch(40, 100L);
        assertEquals(50, processor.nextBatchSize(100L, 1_000L), "按最近记录的批量继续扩容");
    }

    @Test
    void adaptiveGrowthFromSizeOneAlwaysIncreases() {
        BatchProcessor processor = new BatchProcessor(1, true);

        // 1 * 5 / 4 == 1；必须至少 +1，否则自适应批量永远卡在 1。
        assertEquals(2, processor.nextBatchSize(100L, 1_000L));

        processor.recordBatch(2, 100L);
        assertEquals(3, processor.nextBatchSize(100L, 1_000L));

        processor.recordBatch(3, 100L);
        assertEquals(4, processor.nextBatchSize(100L, 1_000L));
    }

    @Test
    void adaptiveShrinksByHalfWhenOverBudget() {
        BatchProcessor processor = new BatchProcessor(40, true);

        assertEquals(20, processor.nextBatchSize(1_001L, 1_000L), "严格超过预算才缩容");
        assertEquals(20, processor.nextBatchSize(2_000L, 1_000L));
    }

    @Test
    void growthIsClampedAtEightTimesConfigured() {
        BatchProcessor processor = new BatchProcessor(10, true);
        processor.recordBatch(80, 10L);

        // 80 * 5 / 4 = 100，应夹到上限 10 * 8 = 80。
        assertEquals(80, processor.nextBatchSize(1L, 1_000L));
    }

    @Test
    void shrinkIsClampedAtOne() {
        BatchProcessor processor = new BatchProcessor(10, true);
        processor.recordBatch(1, 10L);

        // 1 / 2 = 0，应夹到下限 1。
        assertEquals(1, processor.nextBatchSize(10_000L, 1_000L));
    }

    @Test
    void adaptiveKeepsSizeBetweenHalfAndFullBudget() {
        BatchProcessor processor = new BatchProcessor(20, true);

        assertEquals(20, processor.nextBatchSize(501L, 1_000L), "刚超过一半不扩容");
        assertEquals(20, processor.nextBatchSize(1_000L, 1_000L), "恰好用满预算不缩容");
    }

    @Test
    void adaptiveKeepsCurrentWhenTimingSampleIsMissing() {
        BatchProcessor processor = new BatchProcessor(10, true);
        processor.recordBatch(40, 1L);

        assertEquals(40, processor.nextBatchSize(0L, 1_000L));
        assertEquals(40, processor.nextBatchSize(-5L, 1_000L));
        assertEquals(40, processor.nextBatchSize(100L, 0L));
        assertEquals(40, processor.nextBatchSize(100L, -1L));
    }

    @Test
    void recordBatchUpdatesBookkeeping() {
        BatchProcessor processor = new BatchProcessor(10, true);
        assertEquals(0L, processor.batchesProcessed());
        assertEquals(10, processor.currentBatchSize());

        processor.recordBatch(30, 123L);
        processor.recordBatch(12, 456L);

        assertEquals(2L, processor.batchesProcessed());
        assertEquals(12, processor.currentBatchSize());
        assertEquals(456L, processor.lastBatchNanos());
    }

    @Test
    void recordBatchClampsOversizedBatchAndNegativeElapsed() {
        BatchProcessor processor = new BatchProcessor(10, true);

        processor.recordBatch(10_000, -5L);

        assertEquals(80, processor.currentBatchSize(), "记录值同样遵守八倍上限");
        assertEquals(0L, processor.lastBatchNanos(), "负耗时按 0 处理");
    }

    @Test
    void constructorRejectsNonPositiveConfiguredSize() {
        assertThrows(IllegalArgumentException.class, () -> new BatchProcessor(0, true));
        assertThrows(IllegalArgumentException.class, () -> new BatchProcessor(-3, false));
    }
}
