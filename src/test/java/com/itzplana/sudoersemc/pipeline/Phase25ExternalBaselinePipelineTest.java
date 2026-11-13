package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.integration.ExternalBaselineSource;
import com.itzplana.sudoersemc.integration.ProjectEIntegrationBaselineSource;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.MaterialCategory;
import com.itzplana.sudoersemc.pricing.PricingSettings;
import com.itzplana.sudoersemc.pricing.RarityPricing;
import com.itzplana.sudoersemc.pricing.TagPricing;
import com.itzplana.sudoersemc.pricing.TierPricing;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Rarity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 25 external baseline safety-layer tests: baseline must go through the candidate path and pass exclusion, minimum
 * EMC and large-drop protection; when unavailable by default, behaviour matches Phase 24.
 */
class Phase25ExternalBaselinePipelineTest {

    private static final ResourceLocation STONE_ID = new ResourceLocation("minecraft", "stone");
    private static final ItemKey STONE = ItemKey.of(STONE_ID);
    private static final ResourceLocation INGOT_ID = new ResourceLocation("sudoersemc", "test_ingot");
    private static final ItemKey INGOT = ItemKey.of(INGOT_ID);
    private static final ItemKey SPAWN_EGG =
            ItemKey.of(new ResourceLocation("minecraft", "pig_spawn_egg"));

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @TempDir
    Path tempDir;

    @Test
    void baselineBeatsTagAndRarity() {
        FallbackPricing pricing = pricing();

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                INGOT, List.of("forge:ingots/iron"), Rarity.COMMON, 0L, 500L)).orElseThrow();

        assertEquals(500L, value.value());
        assertEquals(PriceSource.EXTERNAL_BASELINE, value.source());
    }

    @Test
    void baselineDisabledFallsBackToTag() {
        FallbackPricing pricing = pricing();

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                INGOT, List.of("forge:ingots/iron"), Rarity.COMMON, 0L, 0L)).orElseThrow();

        assertEquals(256L, value.value());
        assertEquals(PriceSource.TAG, value.source());
    }

    @Test
    void existingValueBeatsBaseline() {
        FallbackPricing pricing = pricing();

        FallbackPricing.PricedValue value = pricing.price(new FallbackPricing.PricingRequest(
                INGOT, List.of(), Rarity.COMMON, 777L, 500L)).orElseThrow();

        assertEquals(777L, value.value());
        assertEquals(PriceSource.EXISTING, value.source());
    }

    @Test
    void excludedItemWithBaselineIsNotPriced() {
        FallbackPricing pricing = pricing();

        assertTrue(pricing.price(new FallbackPricing.PricingRequest(
                SPAWN_EGG, List.of(), Rarity.COMMON, 0L, 500L)).isEmpty(),
                "外部基线不得绕过排除规则");
    }

    @Test
    void baselineBelowMinEmcIsDiscarded() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("min-emc.json"));
        FakeBaseline baseline = FakeBaseline.of(Map.of(STONE, 1L));
        ScanPipeline pipeline = pipeline(store, null, disabledFallback(),
                new SolveSettings(1, false, 10L, 8), baseline);

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE));

        assertEquals(0, result.applied());
        assertEquals(0, store.size());
    }

    @Test
    void baselineLargeDropIsQuarantined() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("drop.json"));
        store.stage(new GeneratedEmcEntry(STONE, 1000L, PriceSource.RECIPE, null, true, 0L));
        QuarantinedDropsStore quarantine = new QuarantinedDropsStore(
                tempDir.resolve("quarantined.json"));
        quarantine.load();
        LargeDropProtection protection = new LargeDropProtection(
                new LargeDropProtection.Settings(true, 0.5D, Set.of(), Set.of()));
        FakeBaseline baseline = FakeBaseline.of(Map.of(STONE, 100L));
        ScanPipeline pipeline = pipeline(store, protection, disabledFallback(),
                SolveSettings.defaults(), baseline);
        pipeline.setQuarantinedDropsStore(quarantine);

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE));

        assertEquals(1, result.quarantined());
        assertEquals(0, result.applied());
        assertEquals(1000L, store.snapshotAll().get(STONE).value());
        assertEquals(1, quarantine.size());
    }

    @Test
    void baselineFingerprintChanges() {
        ScanFingerprint first = ScanPipeline.fingerprintFor(List.of(), List.of(), List.of(),
                ProjectEIntegrationBaselineSource.fingerprintFor(Map.of(STONE, 100L)));
        ScanFingerprint second = ScanPipeline.fingerprintFor(List.of(), List.of(), List.of(),
                ProjectEIntegrationBaselineSource.fingerprintFor(Map.of(STONE, 200L)));

        assertNotEquals(first.baselineFingerprint(), second.baselineFingerprint());
        assertTrue(first.changedChannels(second).contains("baseline"));
    }

    @Test
    void baselineAboveOverflowThresholdIsDiscarded() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("overflow-high.json"));
        FakeBaseline baseline = FakeBaseline.of(Map.of(STONE, 5000L));
        ScanPipeline pipeline = new ScanPipeline(store, null, null, null, null,
                overflowPipeline(1000L), disabledFallback(), SolveSettings.defaults());
        pipeline.setExternalBaselineSource(baseline);

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE));

        assertEquals(0, result.applied());
        assertEquals(0, store.size());
    }

    @Test
    void baselineBelowOverflowThresholdIsAccepted() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("overflow-low.json"));
        FakeBaseline baseline = FakeBaseline.of(Map.of(STONE, 5000L));
        ScanPipeline pipeline = new ScanPipeline(store, null, null, null, null,
                overflowPipeline(10000L), disabledFallback(), SolveSettings.defaults());
        pipeline.setExternalBaselineSource(baseline);

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE));

        assertEquals(1, result.applied());
        assertEquals(5000L, store.snapshotAll().get(STONE).value());
        assertEquals(PriceSource.EXTERNAL_BASELINE, store.snapshotAll().get(STONE).source());
    }

    @Test
    void manualDetectorSupplierFailureSkipsFallbackRound() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("manual-broken.json"));
        ScanPipeline pipeline = pipeline(store, null, genericFallback(50L),
                SolveSettings.defaults(), ExternalBaselineSource.unavailable());
        pipeline.setManualValueDetectorSupplier(() -> {
            throw new IllegalStateException("simulated manual detector failure");
        });

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE));

        assertEquals(0, result.applied(), "判定器不可用时不得产生任何兜底候选");
        assertEquals(0, store.size());
    }

    @Test
    void manualDetectorPredicateFailureProtectsItem() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("manual-throw.json"));
        ScanPipeline pipeline = pipeline(store, null, genericFallback(50L),
                SolveSettings.defaults(), ExternalBaselineSource.unavailable());
        pipeline.setManualValueDetectorSupplier(() -> item -> {
            throw new IllegalStateException("simulated per-item failure");
        });

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE));

        assertEquals(0, result.applied(), "单物品判定异常必须 fail-closed 跳过该物品");
        assertEquals(0, store.size());
    }

    @Test
    void unavailableBaselineKeepsDefaultBehaviour() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("unavailable.json"));
        ScanPipeline pipeline = pipeline(store, null, disabledFallback(),
                SolveSettings.defaults(), ExternalBaselineSource.unavailable());

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE));

        assertEquals(0, result.applied());
        assertEquals(0, store.size());
        assertEquals(0L, result.extraCounter("externalBaselineAvailable", -1L));
        assertEquals(0L, result.extraCounter("externalBaselineEntries", -1L));
    }

    private static FallbackPricing pricing() {
        return new FallbackPricing(
                new TagPricing(Map.of(MaterialCategory.INGOT, 256L), Map.of()),
                RarityPricing.defaults(),
                TierPricing.defaults(),
                PricingSettings.defaults());
    }
    private static FallbackPricing disabledFallback() {
        return FallbackPricing.withDefaults(
                new PricingSettings(false, false, false, false, 0L));
    }

    private static FallbackPricing genericFallback(long value) {
        return FallbackPricing.withDefaults(
                new PricingSettings(false, false, false, true, value));
    }

    private OverflowPipeline overflowPipeline(long threshold) {
        return new OverflowPipeline(
                new OverflowProtection(new OverflowProtection.Settings(
                        true, threshold, 100L, 900L, 10, 5,
                        OverflowScope.OWNED_ONLY, false, 42L)),
                new OverflowResetLog(tempDir.resolve("overflow-log-" + threshold + ".json")));
    }

    private static ScanPipeline pipeline(GeneratedEntryStore store,
                                         LargeDropProtection dropProtection,
                                         FallbackPricing pricing,
                                         SolveSettings settings,
                                         ExternalBaselineSource baseline) {
        ScanPipeline pipeline = new ScanPipeline(
                store, null, null, dropProtection, null, null, pricing, settings);
        pipeline.setExternalBaselineSource(baseline);
        return pipeline;
    }

    private static PipelineSnapshot snapshot(ItemKey key) {
        return new PipelineSnapshot(
                List.of(ItemSnapshot.of(key, false, 0L)),
                List.of(),
                Map.of(),
                0, 0, 0);
    }

    private static final class FakeBaseline implements ExternalBaselineSource {

        private final Map<ItemKey, Long> values;
        private int prepareCalls;

        private FakeBaseline(Map<ItemKey, Long> values) {
            this.values = new LinkedHashMap<>(values);
        }

        private static FakeBaseline of(Map<ItemKey, Long> values) {
            return new FakeBaseline(values);
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public boolean isAvailable() {
            return !values.isEmpty();
        }

        @Override
        public void prepare(net.minecraft.server.MinecraftServer server) {
            prepareCalls++;
        }

        @Override
        public OptionalLong lookup(ItemKey item) {
            Long value = values.get(item);
            return value == null ? OptionalLong.empty() : OptionalLong.of(value);
        }

        @Override
        public List<String> fingerprintEntries() {
            return values.isEmpty()
                    ? List.of("external-baseline=unavailable")
                    : ProjectEIntegrationBaselineSource.fingerprintFor(values);
        }

        @Override
        public int entryCount() {
            return values.size();
        }
    }
}
