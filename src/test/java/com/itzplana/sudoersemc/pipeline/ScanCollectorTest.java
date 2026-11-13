package com.itzplana.sudoersemc.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.scanner.IngredientOption;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeKind;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeScanner;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;

/**
 * Paging, stage advance, cancellation and NBT variant aggregation verification for {@link ScanCollector}.
 */
class ScanCollectorTest {

    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");
    private static final ResourceLocation OAK_LOG = new ResourceLocation("minecraft", "oak_log");
    private static final ResourceLocation DIRT_RECIPE = new ResourceLocation("sudoersemc", "dirt_recipe");
    private static final ResourceLocation OAK_LOG_RECIPE =
            new ResourceLocation("sudoersemc", "oak_log_recipe");
    private static final ResourceLocation STONE_NBT_RECIPE =
            new ResourceLocation("sudoersemc", "stone_nbt_recipe");

    @Test
    void budgetOneAdvancesOneItemPerStep() {
        ScanCollector session = session(List.of(STONE, DIRT, OAK_LOG), List.of(),
                Map.of(), false, 1, new AtomicBoolean(false));

        assertEquals("ITEMS", session.stageName());
        assertEquals(3, session.itemCount());

        session.step(1);
        assertEquals(1, session.processedItems());

        session.step(1);
        assertEquals(2, session.processedItems());

        session.step(1);
        assertEquals(3, session.processedItems());
        assertEquals("RECIPES", session.stageName());
    }

    @Test
    void emptyRegistryCompletesWithEmptySnapshot() {
        ScanCollector session = session(List.of(), List.of(), Map.of(), false, 1,
                new AtomicBoolean(false));

        assertFalse(session.isDone());
        PipelineSnapshot snapshot = session.finish();

        assertTrue(session.isDone());
        assertTrue(snapshot.isEmpty());
        assertEquals(0, snapshot.itemCount());
        assertEquals(0, snapshot.recipeCount());
    }

    @Test
    void stagesAdvanceItemsThenRecipesThenDone() {
        ScanCollector session = session(List.of(STONE), List.of(DIRT_RECIPE, OAK_LOG_RECIPE),
                Map.of(), false, 1, new AtomicBoolean(false));

        session.step(1);
        assertEquals("RECIPES", session.stageName());

        session.step(1);
        assertEquals(1, session.processedRecipes());
        assertEquals("RECIPES", session.stageName());

        session.step(1);
        assertEquals(2, session.processedRecipes());
        assertEquals("EXISTING", session.stageName());

        PipelineSnapshot snapshot = session.finish();
        assertEquals("DONE", session.stageName());
        assertEquals(2, snapshot.recipeCount());
        assertEquals(1, snapshot.itemCount());
    }

    @Test
    void totalsAndExistingValuesAreAggregated() {
        ScanCollector session = session(List.of(STONE, DIRT), List.of(),
                Map.of(STONE, 42L, DIRT, 7L), false, 1, new AtomicBoolean(false));

        PipelineSnapshot snapshot = session.finish();

        assertEquals(2, snapshot.itemCount());
        assertEquals(2, snapshot.existingValues().size());
        assertEquals(42L, snapshot.existingValues().get(ItemKey.of(STONE)));
        assertEquals(7L, snapshot.existingValues().get(ItemKey.of(DIRT)));
    }

    @Test
    void cancellationReturnsPartialSnapshotAtBatchBoundary() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ScanCollector session = session(List.of(STONE, DIRT, OAK_LOG), List.of(),
                Map.of(), false, 1, cancelled);

        session.step(1);
        assertEquals(1, session.processedItems());

        cancelled.set(true);
        session.step(1);

        assertTrue(session.isDone());
        PipelineSnapshot snapshot = session.finish();
        assertEquals(1, snapshot.itemCount(), "取消后只能保留已经采集的物品");
        assertEquals(1, session.processedItems());
    }

    @Test
    void variantsAreObservedAndBuiltInSeparateBatches() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ScanCollector session = session(List.of(STONE), List.of(STONE_NBT_RECIPE),
                Map.of(), true, 1, cancelled);

        session.step(1);
        assertEquals("RECIPES", session.stageName());

        session.step(1);
        assertEquals("VARIANTS_OBSERVE", session.stageName());

        session.step(1);
        assertEquals("VARIANTS_BUILD", session.stageName());

        session.step(1);
        assertEquals("EXISTING", session.stageName());

        PipelineSnapshot snapshot = session.finish();

        assertEquals(2, snapshot.itemCount(), "基础物品 + 一个 NBT 变体");
        long variantCount = snapshot.items().stream()
                .filter(item -> item.key().hasVariant())
                .count();
        assertEquals(1L, variantCount);
    }

    @Test
    void existingStageChecksCancellationAtEveryBatchBoundary() {
        AtomicInteger checks = new AtomicInteger();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ScanCollector session = ScanCollector.forTesting(
                List.of(STONE, DIRT, OAK_LOG),
                ids -> new ItemScanner.Batch(
                        ids.stream()
                                .map(id -> ItemSnapshot.of(ItemKey.of(id), false, 0L))
                                .toList(),
                        0, 0, 0, 0),
                List.of(),
                null,
                false,
                1,
                () -> {
                    checks.incrementAndGet();
                    return cancelled.get();
                },
                null);

        session.step(1);
        session.step(1);
        session.step(1);
        session.step(1);
        assertEquals("EXISTING", session.stageName());
        checks.set(0);

        session.step(1);

        assertEquals(2, checks.get(),
                "EXISTING 阶段每个批次边界都必须检查取消，而不是仅在 cursor%256==0 时检查");
    }

    @Test
    void cancelledPartialSnapshotKeepsRecipeFailureCounters() {
        AtomicBoolean cancelled = new AtomicBoolean(false);
        ScanCollector session = ScanCollector.forTesting(
                List.of(STONE),
                ids -> new ItemScanner.Batch(List.of(), 0, 0, 0, 0),
                List.of(DIRT_RECIPE),
                ids -> new RecipeScanner.ScanResult(
                        List.of(recipesFor(ids).get(0)), 2, 3, 4,
                        List.of("bad_recipe: boom")),
                false,
                1,
                cancelled::get,
                null);

        session.step(1);
        session.step(1);
        cancelled.set(true);
        PipelineSnapshot snapshot = session.finish();

        assertEquals(2, snapshot.unsupportedRecipes());
        assertEquals(3, snapshot.invalidRecipes());
        assertEquals(4, snapshot.failedRecipes());
        assertEquals(List.of("bad_recipe: boom"), snapshot.failedRecipeDetails(),
                "取消后的部分快照必须保留已扫描到的失败明细");
    }

    /**
     * Builds a collection session with a real item scanner and a fake recipe page.
     */
    private static ScanCollector session(List<ResourceLocation> itemIds,
                                         List<ResourceLocation> recipeIds,
                                         Map<ResourceLocation, Long> existing,
                                         boolean variantsEnabled,
                                         int maxVariants,
                                         AtomicBoolean cancelled) {
        ItemScanner scanner = new ItemScanner(new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(ItemStack stack) {
                return existing.containsKey(BuiltInRegistries.ITEM.getKey(stack.getItem()));
            }

            @Override
            public long getValue(ItemStack stack) {
                return existing.getOrDefault(
                        BuiltInRegistries.ITEM.getKey(stack.getItem()), 0L);
            }
        }, Set.of(), Set.of());

        ScanCollector.RecipePage recipePage = ids -> new RecipeScanner.ScanResult(
                recipesFor(ids), 0, 0, 0, List.of());

        return ScanCollector.forTesting(
                itemIds,
                ids -> scanner.scanBatch(ids, cancelled::get),
                recipeIds,
                recipePage,
                variantsEnabled,
                maxVariants,
                cancelled::get,
                null);
    }

    private static List<RecipeSnapshot> recipesFor(List<ResourceLocation> ids) {
        List<RecipeSnapshot> recipes = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            if (DIRT_RECIPE.equals(id)) {
                recipes.add(RecipeSnapshot.of(id, RecipeKind.SHAPED,
                        List.of(RecipeInput.of(IngredientOption.of(ItemKey.of(STONE)))),
                        List.of(RecipeOutput.of(ItemKey.of(DIRT)))));
            } else if (OAK_LOG_RECIPE.equals(id)) {
                recipes.add(RecipeSnapshot.of(id, RecipeKind.SHAPELESS,
                        List.of(RecipeInput.of(IngredientOption.of(ItemKey.of(DIRT)))),
                        List.of(RecipeOutput.of(ItemKey.of(OAK_LOG)))));
            } else if (STONE_NBT_RECIPE.equals(id)) {
                recipes.add(RecipeSnapshot.of(id, RecipeKind.SHAPED,
                        List.of(RecipeInput.of(IngredientOption.of(ItemKey.of(STONE)))),
                        List.of(RecipeOutput.of(ItemKey.of(STONE), 1, "{Damage:1}"))));
            } else {
                recipes.add(RecipeSnapshot.of(id, RecipeKind.SHAPED,
                        List.of(RecipeInput.of(IngredientOption.of(ItemKey.of(STONE)))),
                        List.of(RecipeOutput.of(ItemKey.of(STONE), 1, "{Damage:1}"))));
            }
        }
        return recipes;
    }
}
