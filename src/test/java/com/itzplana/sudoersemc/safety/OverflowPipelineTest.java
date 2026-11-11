package com.itzplana.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link OverflowPipeline} 的单元测试，使用真实 {@link OverflowProtection} 与指向
 * {@link TempDir} 的 {@link OverflowResetLog}，重点验证严格阈值、回落区间、审计条数与幂等性。
 */
class OverflowPipelineTest {

    private static final long THRESHOLD = 999_999L;
    private static final long RESET_MIN = 100L;
    private static final long RESET_MAX = 999_000L;
    private static final ItemKey ITEM =
            ItemKey.of(new ResourceLocation("sudoersemc", "compressed_cobblestone"));

    @TempDir
    Path tempDir;

    private OverflowProtection protection;
    private OverflowResetLog log;
    private OverflowPipeline pipeline;

    @BeforeEach
    void setUp() {
        protection = new OverflowProtection(new OverflowProtection.Settings(
                true,
                THRESHOLD,
                RESET_MIN,
                RESET_MAX,
                100,
                20,
                OverflowScope.OWNED_ONLY,
                false,
                12345L));
        log = new OverflowResetLog(tempDir.resolve(OverflowResetLog.FILE_NAME));
        pipeline = new OverflowPipeline(protection, log);
    }

    @Test
    void valueBelowThresholdIsUnchangedAndNotAudited() {
        long result = pipeline.applyAndAudit(ITEM, THRESHOLD - 1L, OverflowScope.OWNED_ONLY);

        assertEquals(THRESHOLD - 1L, result);
        assertEquals(0, pipeline.auditCount());
        assertEquals(0, pipeline.resetsApplied());
        assertTrue(log.entries().isEmpty());
    }

    @Test
    void valueExactlyAtThresholdIsUnchangedAndNotAudited() {
        long result = pipeline.applyAndAudit(ITEM, THRESHOLD, OverflowScope.OWNED_ONLY);

        assertEquals(THRESHOLD, result);
        assertEquals(0, pipeline.auditCount());
        assertEquals(0, pipeline.resetsApplied());
        assertTrue(log.entries().isEmpty());
    }

    @Test
    void valueAboveThresholdIsResetIntoRangeAndAudited() {
        long result = pipeline.applyAndAudit(ITEM, 1_500_000L, OverflowScope.OWNED_ONLY);

        assertTrue(result >= RESET_MIN, "reset " + result + " below resetMin");
        assertTrue(result <= RESET_MAX, "reset " + result + " above resetMax");
        assertEquals(1, pipeline.auditCount());
        assertEquals(1, pipeline.resetsApplied());

        List<OverflowReset> entries = log.entries();
        assertEquals(1, entries.size());
        OverflowReset audit = entries.get(0);
        assertEquals(ITEM, audit.item());
        assertEquals(1_500_000L, audit.originalValue());
        assertEquals(result, audit.resetValue());
        assertEquals(THRESHOLD, audit.threshold());
        assertEquals(OverflowScope.OWNED_ONLY, audit.scope());
        assertTrue(audit.timestamp() > 0L);
    }

    @Test
    void reapplyingResetValueDoesNotCreateAnotherAuditEntry() {
        long reset = pipeline.applyAndAudit(ITEM, 1_500_000L, OverflowScope.OWNED_ONLY);
        assertEquals(1, pipeline.auditCount());

        long second = pipeline.applyAndAudit(ITEM, reset, OverflowScope.OWNED_ONLY);

        assertEquals(reset, second);
        assertEquals(1, pipeline.auditCount());
        assertEquals(1, pipeline.resetsApplied());
        assertEquals(1, log.entries().size());
    }

    @Test
    void multipleOverflowsAccumulateAuditEntries() {
        List<Long> values = List.of(1_000_000L, 1_100_000L, 1_200_000L);

        for (long value : values) {
            long reset = pipeline.applyAndAudit(ITEM, value, OverflowScope.OWNED_ONLY);
            assertTrue(reset >= RESET_MIN && reset <= RESET_MAX);
        }

        assertEquals(3, pipeline.auditCount());
        assertEquals(3, pipeline.resetsApplied());
        assertEquals(3, log.entries().size());
        for (OverflowReset audit : log.entries()) {
            assertEquals(ITEM, audit.item());
            assertEquals(OverflowScope.OWNED_ONLY, audit.scope());
        }
    }

    @Test
    void scopeIsPropagatedIntoTheAuditRecord() {
        long result = pipeline.applyAndAudit(ITEM, 1_500_000L, OverflowScope.ENTIRE_CUSTOM_EMC);

        assertTrue(result >= RESET_MIN && result <= RESET_MAX);
        assertEquals(1, log.entries().size());
        assertEquals(OverflowScope.ENTIRE_CUSTOM_EMC, log.entries().get(0).scope());
    }

    @Test
    void disabledProtectionLeavesValueUntouchedWithoutAudit() {
        OverflowProtection disabled = new OverflowProtection(new OverflowProtection.Settings(
                false,
                THRESHOLD,
                RESET_MIN,
                RESET_MAX,
                100,
                20,
                OverflowScope.OWNED_ONLY,
                false,
                12345L));
        OverflowPipeline disabledPipeline = new OverflowPipeline(disabled, log);

        long result = disabledPipeline.applyAndAudit(ITEM, 2_000_000L, OverflowScope.OWNED_ONLY);

        assertEquals(2_000_000L, result);
        assertEquals(0, disabledPipeline.auditCount());
        assertEquals(0, disabledPipeline.resetsApplied());
        assertTrue(log.entries().isEmpty());
    }
}
