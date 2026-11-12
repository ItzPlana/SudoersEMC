package com.itzplana.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * {@link LargeDropProtection} 的单元测试，重点验证严格大于阈值、仅降价触发隔离、豁免顺序以及
 * 管理员批准只清除隔离记录而不改数值。
 */
class LargeDropProtectionTest {

    private static final ResourceLocation DIAMOND_ID = new ResourceLocation("minecraft", "diamond");
    private static final ResourceLocation IRON_ID = new ResourceLocation("minecraft", "iron_ingot");
    private static final ResourceLocation EMERALD_ID = new ResourceLocation("minecraft", "emerald");
    private static final ResourceLocation COGWHEEL_ID = new ResourceLocation("create", "cogwheel");

    private static final ItemKey DIAMOND = ItemKey.of(DIAMOND_ID);
    private static final ItemKey IRON = ItemKey.of(IRON_ID);
    private static final ItemKey EMERALD = ItemKey.of(EMERALD_ID);
    private static final ItemKey COGWHEEL = ItemKey.of(COGWHEEL_ID);

    private static LargeDropProtection.Settings settings(boolean enabled, double ratio) {
        return new LargeDropProtection.Settings(enabled, ratio, Set.of(), Set.of());
    }

    @Test
    void dropBeyondThresholdIsQuarantined() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));

        PriceDropDecision decision = protection.evaluate(DIAMOND, 1_000L, 400L);

        assertFalse(decision.allowed());
        assertEquals(1_000L, decision.previousValue());
        assertEquals(400L, decision.proposedValue());
        assertEquals(0.6, decision.dropRatio(), 1e-9);
        assertEquals("drop-exceeds-threshold", decision.reason());
        assertTrue(decision.changed());
        assertEquals(1, protection.quarantineCount());
        assertEquals(List.of(decision), protection.quarantined());
    }

    @Test
    void dropExactlyAtThresholdIsAllowed() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));

        PriceDropDecision decision = protection.evaluate(DIAMOND, 1_000L, 500L);

        assertTrue(decision.allowed());
        assertEquals(0.5, decision.dropRatio(), 1e-9);
        assertEquals("drop-within-threshold", decision.reason());
        assertEquals(0, protection.quarantineCount());
    }

    @Test
    void dropWithinThresholdIsAllowed() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));

        PriceDropDecision decision = protection.evaluate(DIAMOND, 1_000L, 800L);

        assertTrue(decision.allowed());
        assertEquals(0.2, decision.dropRatio(), 1e-9);
        assertEquals("drop-within-threshold", decision.reason());
        assertEquals(0, protection.quarantineCount());
    }

    @Test
    void increaseIsAlwaysAllowed() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.01));

        PriceDropDecision decision = protection.evaluate(DIAMOND, 1_000L, 2_000L);

        assertTrue(decision.allowed());
        assertEquals(0.0, decision.dropRatio(), 0.0);
        assertEquals("increase", decision.reason());
        assertEquals(0, protection.quarantineCount());
    }

    @Test
    void unchangedValueIsAllowed() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));

        PriceDropDecision decision = protection.evaluate(DIAMOND, 1_000L, 1_000L);

        assertTrue(decision.allowed());
        assertFalse(decision.changed());
        assertEquals("unchanged", decision.reason());
        assertEquals(0, protection.quarantineCount());
    }

    @Test
    void zeroPreviousValueIsAllowed() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));

        PriceDropDecision decision = protection.evaluate(DIAMOND, 0L, 0L);

        assertTrue(decision.allowed());
        assertEquals("unchanged", decision.reason());
        assertEquals(0, protection.quarantineCount());
    }

    @Test
    void itemExemptionAllowsLargeDropAndDoesNotAffectOtherItems() {
        LargeDropProtection protection = new LargeDropProtection(new LargeDropProtection.Settings(
                true, 0.5, Set.of(), Set.of(IRON)));

        PriceDropDecision exemptDecision = protection.evaluate(IRON, 1_000L, 1L);
        PriceDropDecision otherDecision = protection.evaluate(DIAMOND, 1_000L, 1L);

        assertTrue(exemptDecision.allowed());
        assertEquals("exempt-item", exemptDecision.reason());
        assertFalse(otherDecision.allowed());
        assertEquals("drop-exceeds-threshold", otherDecision.reason());
        assertEquals(1, protection.quarantineCount());
    }

    @Test
    void namespaceExemptionAllowsLargeDrop() {
        // 元素只使用 namespace 部分，path 是占位符；任何 minecraft 物品都应被豁免。
        LargeDropProtection protection = new LargeDropProtection(new LargeDropProtection.Settings(
                true, 0.5, Set.of(new ResourceLocation("minecraft", "all_items")), Set.of()));

        PriceDropDecision exemptDecision = protection.evaluate(DIAMOND, 1_000L, 1L);
        PriceDropDecision otherNamespace = protection.evaluate(COGWHEEL, 1_000L, 1L);

        assertTrue(exemptDecision.allowed());
        assertEquals("exempt-namespace", exemptDecision.reason());
        assertFalse(otherNamespace.allowed());
        assertEquals("drop-exceeds-threshold", otherNamespace.reason());
        assertEquals(1, protection.quarantineCount());
    }

    @Test
    void disabledProtectionAllowsEverything() {
        LargeDropProtection protection = new LargeDropProtection(new LargeDropProtection.Settings(
                false, 0.5, Set.of(new ResourceLocation("minecraft")), Set.of(DIAMOND)));

        PriceDropDecision decision = protection.evaluate(EMERALD, 1_000_000L, 1L);

        assertTrue(decision.allowed());
        assertEquals("protection-disabled", decision.reason());
        assertEquals(0, protection.quarantineCount());
    }

    @Test
    void approveClearsOnlyThatItemsQuarantine() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));
        PriceDropDecision diamondDecision = protection.evaluate(DIAMOND, 1_000L, 100L);
        PriceDropDecision ironDecision = protection.evaluate(IRON, 1_000L, 100L);
        assertEquals(2, protection.quarantineCount());

        List<PriceDropDecision> approved = protection.approve(DIAMOND);

        assertEquals(1, protection.quarantineCount());
        assertEquals(List.of(ironDecision), protection.quarantined());
        assertEquals(List.of(diamondDecision), approved, "approve 必须返回被移除的具体决策");
        assertTrue(protection.isApproved(DIAMOND));
        assertFalse(protection.isApproved(IRON));
        assertEquals(Set.of(DIAMOND), protection.approvedItems());
        assertEquals(1_000L, diamondDecision.previousValue(), "批准绝不能修改原判定中的数值");
        assertEquals(100L, diamondDecision.proposedValue(), "批准绝不能修改原判定中的数值");
    }

    @Test
    void clearQuarantineEmptiesTheListAndKeepsApprovalHistory() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));
        protection.evaluate(DIAMOND, 1_000L, 100L);
        protection.evaluate(IRON, 1_000L, 100L);
        protection.approve(DIAMOND);

        protection.clearQuarantine();

        assertEquals(0, protection.quarantineCount());
        assertTrue(protection.quarantined().isEmpty());
        assertTrue(protection.isApproved(DIAMOND), "审批历史与待处理隔离列表相互独立");
    }

    @Test
    void repeatedEvaluationsKeepOnlyTheLatestDecisionPerItem() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));

        PriceDropDecision first = protection.evaluate(DIAMOND, 1_000L, 100L);
        PriceDropDecision second = protection.evaluate(DIAMOND, 1_000L, 200L);

        assertFalse(first.allowed());
        assertFalse(second.allowed());
        assertEquals(1, protection.quarantineCount(), "重复判定同一物品不得增长隔离列表");
        assertEquals(List.of(second), protection.quarantined());
    }

    @Test
    void approveReturnsRemovedDecisionsAndDoesNotBypassFutureDrops() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));
        PriceDropDecision quarantined = protection.evaluate(DIAMOND, 1_000L, 100L);

        assertEquals(List.of(quarantined), protection.approve(DIAMOND));
        assertEquals(0, protection.quarantineCount());
        assertTrue(protection.isApproved(DIAMOND), "审批历史仍可用于诊断");

        PriceDropDecision future = protection.evaluate(DIAMOND, 1_000L, 50L);

        assertFalse(future.allowed(), "批准历史绝不能放行未来的新降价");
        assertEquals(1, protection.quarantineCount());
        assertEquals(List.of(future), protection.quarantined());
    }

    @Test
    void approveWithoutPendingDecisionReturnsEmptyList() {
        LargeDropProtection protection = new LargeDropProtection(settings(true, 0.5));

        assertTrue(protection.approve(IRON).isEmpty());
        assertEquals(0, protection.quarantineCount());
    }

    @Test
    void invalidRatiosAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new LargeDropProtection.Settings(true, 0.0, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LargeDropProtection.Settings(true, -0.1, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LargeDropProtection.Settings(true, 1.0001, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LargeDropProtection.Settings(true, Double.NaN, Set.of(), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new LargeDropProtection.Settings(
                        true, Double.POSITIVE_INFINITY, Set.of(), Set.of()));
    }

    @Test
    void defaultsMatchDocumentedValues() {
        LargeDropProtection.Settings defaults = LargeDropProtection.Settings.defaults();

        assertTrue(defaults.enabled());
        assertEquals(0.5, defaults.maxAllowedDropRatio(), 0.0);
        assertTrue(defaults.exemptNamespaces().isEmpty());
        assertTrue(defaults.exemptItems().isEmpty());
    }
}
