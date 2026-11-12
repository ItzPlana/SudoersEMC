package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.MaterialCategory;
import com.itzplana.sudoersemc.pricing.PricingSettings;
import com.itzplana.sudoersemc.pricing.RarityPricing;
import com.itzplana.sudoersemc.pricing.TagPricing;
import com.itzplana.sudoersemc.pricing.TierPricing;
import com.itzplana.sudoersemc.resolver.NbtMaterialResolver;
import com.itzplana.sudoersemc.resolver.ResolverRegistry;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeKind;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.scanner.IngredientOption;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.OverflowResetLog;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 25 resolver 接线测试：默认空表行为不变；resolver 值只能作为 EXTERNAL_BASELINE 候选，
 * 非正值与异常均不得制造免费物品。
 */
class Phase25ResolverIntegrationTest {

    private static final ResourceLocation STONE_ID = new ResourceLocation("minecraft", "stone");
    private static final ItemKey STONE = ItemKey.of(STONE_ID);
    private static final ItemKey IRON = ItemKey.of(new ResourceLocation("minecraft", "iron_ingot"));
    private static final ResourceLocation RECIPE_ID =
            new ResourceLocation("sudoersemc", "stone_with_nbt");

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @TempDir
    Path tempDir;

    @Test
    void emptyRegistryKeepsVariantSnapshotsUnchanged() {
        RecipeSnapshot recipe = RecipeSnapshot.of(
                RECIPE_ID,
                RecipeKind.SHAPED,
                List.of(RecipeInput.of(IngredientOption.of(STONE))),
                List.of(RecipeOutput.of(STONE, 1, "{Custom:1}")));

        List<ItemSnapshot> variants = ScanPipeline.collectVariantSnapshots(
                List.of(ItemSnapshot.of(STONE, false, 0L)), List.of(recipe), true, 10);

        assertEquals(1, variants.size());
        assertTrue(variants.get(0).key().hasVariant());
        assertFalse(variants.get(0).hasExistingEmc());
        assertEquals(0L, variants.get(0).existingValue());
    }

    @Test
    void resolverValueBecomesExternalBaseline() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("resolver-hit.json"));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(new StubResolver(true, OptionalLong.of(100L), null));
        ScanPipeline pipeline = pipeline(store, disabledFallback());
        pipeline.setResolverRegistry(registry);

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE, List.of()));

        assertEquals(1, result.applied());
        assertEquals(PriceSource.EXTERNAL_BASELINE, store.snapshotAll().get(STONE).source());
        assertEquals(100L, store.snapshotAll().get(STONE).value());
        assertEquals(1L, result.extraCounter("resolverApplied", -1L));
        assertEquals(1L, result.extraCounter("resolverRegistered", -1L));
    }

    @Test
    void resolverThrowsAndNextResolverWins() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("resolver-throw.json"));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(new StubResolver(true, OptionalLong.of(1L),
                new IllegalStateException("stub failure")));
        registry.register(new StubResolver(true, OptionalLong.of(200L), null));
        ScanPipeline pipeline = pipeline(store, disabledFallback());
        pipeline.setResolverRegistry(registry);

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE, List.of()));

        assertEquals(1, result.applied());
        assertEquals(200L, store.snapshotAll().get(STONE).value());
        assertEquals(PriceSource.EXTERNAL_BASELINE, store.snapshotAll().get(STONE).source());
    }

    @Test
    void resolverNonPositiveIsIgnored() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("resolver-zero.json"));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(new StubResolver(true, OptionalLong.of(-1L), null));
        ScanPipeline pipeline = pipeline(store, tagFallback());
        pipeline.setResolverRegistry(registry);

        PipelineResult result = pipeline.solveAndApply(
                snapshot(IRON, List.of("forge:ingots/iron")));

        assertEquals(1, result.applied());
        assertEquals(256L, store.snapshotAll().get(IRON).value());
        assertEquals(PriceSource.TAG, store.snapshotAll().get(IRON).source());
        assertEquals(0L, result.extraCounter("resolverApplied", -1L));
    }

    @Test
    void resolverValueAboveOverflowThresholdIsDiscarded() {
        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("resolver-overflow.json"));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(new StubResolver(true, OptionalLong.of(5000L), null));
        ScanPipeline pipeline = new ScanPipeline(store, null, null, null, null,
                overflowPipeline(1000L), disabledFallback(), SolveSettings.defaults());
        pipeline.setResolverRegistry(registry);

        PipelineResult result = pipeline.solveAndApply(snapshot(STONE, List.of()));

        assertEquals(0, result.applied());
        assertEquals(0, store.size());
    }

    @Test
    void resolverRegistrationOrderWins() {
        StubResolver first = new StubResolver(true, OptionalLong.of(10L), null);
        StubResolver second = new StubResolver(true, OptionalLong.of(20L), null);
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(first);
        registry.register(second);

        assertEquals(OptionalLong.of(10L), registry.resolve(new ItemStack(net.minecraft.world.item.Items.STONE)));
        assertEquals(0, second.resolveCalls);
    }

    @Test
    void resolverFingerprintChangesOnRegistration() {
        ResolverRegistry registry = ResolverRegistry.defaults();
        List<String> empty = registry.fingerprintEntries();
        assertTrue(empty.isEmpty());

        StubResolver resolver = new StubResolver(true, OptionalLong.of(1L), null);
        assertTrue(registry.registerIfAbsent(resolver));
        assertFalse(registry.registerIfAbsent(resolver));
        List<String> registered = registry.fingerprintEntries();

        assertNotEquals(empty, registered);
        assertTrue(registered.get(0).startsWith("resolver[0]:"));
    }

    private ScanPipeline pipeline(GeneratedEntryStore store, FallbackPricing pricing) {
        return new ScanPipeline(store, null, null, null, null, null, pricing,
                SolveSettings.defaults());
    }

    private static FallbackPricing disabledFallback() {
        return FallbackPricing.withDefaults(
                new PricingSettings(false, false, false, false, 0L));
    }

    private static FallbackPricing tagFallback() {
        return new FallbackPricing(
                new TagPricing(Map.of(MaterialCategory.INGOT, 256L), Map.of()),
                RarityPricing.defaults(),
                TierPricing.defaults(),
                new PricingSettings(true, false, false, false, 0L));
    }

    private OverflowPipeline overflowPipeline(long threshold) {
        return new OverflowPipeline(
                new OverflowProtection(new OverflowProtection.Settings(
                        true, threshold, 100L, 900L, 10, 5,
                        OverflowScope.OWNED_ONLY, false, 42L)),
                new OverflowResetLog(tempDir.resolve("overflow-log-" + threshold + ".json")));
    }

    private static PipelineSnapshot snapshot(ItemKey key, List<String> tags) {
        return new PipelineSnapshot(
                List.of(ItemSnapshot.of(key, false, 0L, tags, Rarity.COMMON)),
                List.of(),
                Map.of(),
                0, 0, 0);
    }

    private static final class StubResolver implements NbtMaterialResolver {

        private final boolean supports;
        private final OptionalLong value;
        private final RuntimeException failure;
        private int resolveCalls;

        private StubResolver(boolean supports, OptionalLong value, RuntimeException failure) {
            this.supports = supports;
            this.value = value;
            this.failure = failure;
        }

        @Override
        public boolean supports(ItemStack stack) {
            return supports;
        }

        @Override
        public OptionalLong resolve(ItemStack stack) {
            resolveCalls++;
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
