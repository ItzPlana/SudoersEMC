package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.resolver.VariantScanner;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeScanner;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real sharded implementation of {@link ScanPipeline.CollectSession}.
 *
 * <p>Collection advances strictly in the order "item batches → recipe batches → NBT variant observation
 * batches → variant generation batches → existing EMC read batches". Item and recipe ids are frozen and
 * sorted at the start phase, and afterwards only advance by cursor, so cross-tick paging does not drift
 * when the registry or recipe manager iteration order changes.</p>
 *
 * <p><strong>Threading:</strong> all methods of this class must be called on the server thread. The
 * generated {@link ItemSnapshot} / {@link RecipeSnapshot} are themselves immutable; the
 * {@link PipelineSnapshot} returned by {@link #finish()} also defensively copies all collections, so it
 * can be safely handed to a worker thread.</p>
 */
final class ScanCollector implements ScanPipeline.CollectSession {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanCollector");

    /** Failure-detail cap kept consistent with {@link RecipeScanner}. */
    private static final int MAX_FAILED_DETAILS = 50;

    /** Item paged-scan function; the production implementation delegates to {@link ItemScanner#scanBatch}. */
    @FunctionalInterface
    interface ItemPage {

        ItemScanner.Batch scan(List<ResourceLocation> ids);
    }

    /** Recipe paged-scan function; the production implementation delegates to {@link RecipeScanner#scanBatch}. */
    @FunctionalInterface
    interface RecipePage {

        RecipeScanner.ScanResult scan(List<ResourceLocation> ids);
    }

    private enum Stage {
        ITEMS,
        RECIPES,
        VARIANTS_OBSERVE,
        VARIANTS_BUILD,
        EXISTING,
        DONE
    }

    private final List<ResourceLocation> itemIds;
    private final ItemPage itemPage;
    private final List<ResourceLocation> recipeIds;
    @Nullable
    private final RecipePage recipePage;
    @Nullable
    private final String recipeEnumerationFailure;
    private final boolean variantsEnabled;
    private final int maxVariants;
    private final BooleanSupplier cancellation;
    @Nullable
    private final Consumer<List<ItemSnapshot>> prepareInputs;

    private final List<ItemSnapshot> items = new ArrayList<>();
    private final List<RecipeSnapshot> recipes = new ArrayList<>();
    private final List<String> failedRecipeDetails = new ArrayList<>();
    private final Map<ResourceLocation, List<String>> observedByItem = new TreeMap<>();
    private final List<ItemSnapshot> variants = new ArrayList<>();
    private final Set<ItemKey> seenVariants = new HashSet<>();
    private final Map<ItemKey, Long> existingValues = new TreeMap<>();
    private final VariantScanner variantScanner = new VariantScanner();

    @Nullable
    private Map<ResourceLocation, ItemSnapshot> baseByItem;
    private List<Map.Entry<ResourceLocation, List<String>>> observedEntries = List.of();
    private List<ItemSnapshot> allItems = List.of();

    private Stage stage = Stage.ITEMS;
    private int itemCursor;
    private int recipeCursor;
    private int observeCursor;
    private int buildCursor;
    private int existingCursor;
    private int processedVariantEntries;
    private int unsupportedRecipes;
    private int invalidRecipes;
    private int failedRecipes;
    private int recipeEnumerationFailures;

    @Nullable
    private PipelineSnapshot snapshot;
    private boolean done;

    private ScanCollector(List<ResourceLocation> itemIds,
                          ItemPage itemPage,
                          List<ResourceLocation> recipeIds,
                          @Nullable RecipePage recipePage,
                          @Nullable String recipeEnumerationFailure,
                          boolean variantsEnabled,
                          int maxVariants,
                          BooleanSupplier cancellation,
                          @Nullable Consumer<List<ItemSnapshot>> prepareInputs) {
        this.itemIds = List.copyOf(Objects.requireNonNull(itemIds, "itemIds"));
        this.itemPage = Objects.requireNonNull(itemPage, "itemPage");
        this.recipeIds = List.copyOf(Objects.requireNonNull(recipeIds, "recipeIds"));
        this.recipePage = recipePage;
        this.recipeEnumerationFailure = recipeEnumerationFailure;
        this.variantsEnabled = variantsEnabled;
        this.maxVariants = Math.max(1, Math.min(maxVariants, VariantScanner.HARD_VARIANT_CEILING));
        this.cancellation = Objects.requireNonNull(cancellation, "cancellation");
        this.prepareInputs = prepareInputs;
    }

    /**
     * Constructs a production collection session: freezes item/recipe order on the server thread and
     * captures pipeline configuration.
     */
    static ScanCollector begin(ScanPipeline pipeline, MinecraftServer server) {
        Objects.requireNonNull(pipeline, "pipeline");
        Objects.requireNonNull(server, "server");

        ItemScanner itemScanner = pipeline.newItemScanner();
        List<ResourceLocation> itemIds = itemScanner.orderedItemIds();
        RecipeScanner recipeScanner = pipeline.newRecipeScanner();
        List<ResourceLocation> recipeIds = List.of();
        String recipeFailure = null;
        try {
            recipeIds = recipeScanner.orderedRecipeIds(server.getRecipeManager());
        } catch (RuntimeException failure) {
            recipeFailure = "recipe-manager: " + failureText(failure);
            LOGGER.warn("SudoersEMC 无法枚举配方 id，本轮按空配方继续", failure);
        }
        BooleanSupplier cancellation = pipeline.cancellationPredicate();
        List<ResourceLocation> finalRecipeIds = recipeIds;
        return new ScanCollector(
                itemIds,
                ids -> itemScanner.scanBatch(ids, cancellation),
                recipeIds,
                ids -> recipeScanner.scanBatch(server.getRecipeManager(), ids, cancellation),
                recipeFailure,
                pipeline.nbtVariantsEnabled(),
                pipeline.maxNbtVariantsPerItem(),
                cancellation,
                pipeline::prepareSolveInputs);
    }

    /**
     * @return an already-completed empty session, for scenarios such as the server not being ready
     */
    static ScanCollector completed(PipelineSnapshot snapshot) {
        ScanCollector collector = new ScanCollector(
                List.of(), ids -> new ItemScanner.Batch(List.of(), 0, 0, 0, 0),
                List.of(), null, null, false, 1, () -> false, null);
        collector.snapshot = Objects.requireNonNull(snapshot, "snapshot");
        collector.stage = Stage.DONE;
        collector.done = true;
        return collector;
    }

    /**
     * Test/embedded constructor: injects paging functions directly without accessing the server.
     */
    static ScanCollector forTesting(List<ResourceLocation> itemIds,
                                    ItemPage itemPage,
                                    List<ResourceLocation> recipeIds,
                                    @Nullable RecipePage recipePage,
                                    boolean variantsEnabled,
                                    int maxVariants,
                                    BooleanSupplier cancellation,
                                    @Nullable Consumer<List<ItemSnapshot>> prepareInputs) {
        return new ScanCollector(itemIds, itemPage, recipeIds, recipePage, null,
                variantsEnabled, maxVariants, cancellation, prepareInputs);
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public int itemCount() {
        return itemIds.size();
    }

    @Override
    public int recipeCount() {
        return recipeIds.size();
    }

    @Override
    public int processedItems() {
        return itemCursor;
    }

    @Override
    public int processedRecipes() {
        return recipeCursor;
    }

    /**
     * @return the current stage name, for tests and progress logs
     */
    String stageName() {
        return stage.name();
    }

    @Override
    public void step(int budget) {
        if (done) {
            return;
        }
        int effectiveBudget = Math.max(1, budget);
        if (cancellation.getAsBoolean()) {
            finishPartial();
            return;
        }

        switch (stage) {
            case ITEMS -> stepItems(effectiveBudget);
            case RECIPES -> stepRecipes(effectiveBudget);
            case VARIANTS_OBSERVE -> stepObserveVariants(effectiveBudget);
            case VARIANTS_BUILD -> stepBuildVariants(effectiveBudget);
            case EXISTING -> stepExistingValues(effectiveBudget);
            case DONE -> {
                // Already returned above.
            }
        }
    }

    @Override
    public PipelineSnapshot finish() {
        while (!done) {
            step(Integer.MAX_VALUE);
        }
        return snapshot == null ? PipelineSnapshot.empty() : snapshot;
    }

    private void stepItems(int budget) {
        int remaining = itemIds.size() - itemCursor;
        if (remaining <= 0) {
            stage = Stage.RECIPES;
            return;
        }
        int take = Math.min(budget, remaining);
        ItemScanner.Batch batch = itemPage.scan(itemIds.subList(itemCursor, itemCursor + take));
        items.addAll(batch.snapshots());
        itemCursor += take;
        if (cancellation.getAsBoolean()) {
            finishPartial();
            return;
        }
        if (itemCursor >= itemIds.size()) {
            stage = Stage.RECIPES;
        }
    }

    private void stepRecipes(int budget) {
        if (recipeEnumerationFailure != null && recipeCursor == 0) {
            recipeEnumerationFailures = 1;
            addFailureDetail(recipeEnumerationFailure);
            recipeCursor = recipeIds.size();
            stage = nextAfterRecipes();
            return;
        }
        int remaining = recipeIds.size() - recipeCursor;
        if (remaining <= 0) {
            stage = nextAfterRecipes();
            return;
        }
        int take = Math.min(budget, remaining);
        if (recipePage == null) {
            recipeCursor += take;
        } else {
            RecipeScanner.ScanResult result =
                    recipePage.scan(recipeIds.subList(recipeCursor, recipeCursor + take));
            recipes.addAll(result.recipes());
            unsupportedRecipes += result.unsupported();
            invalidRecipes += result.invalid();
            failedRecipes += result.failed();
            for (String detail : result.failedRecipeDetails()) {
                addFailureDetail(detail);
            }
            recipeCursor += take;
        }
        if (cancellation.getAsBoolean()) {
            finishPartial();
            return;
        }
        if (recipeCursor >= recipeIds.size()) {
            stage = nextAfterRecipes();
        }
    }

    private Stage nextAfterRecipes() {
        return variantsEnabled && !recipes.isEmpty() ? Stage.VARIANTS_OBSERVE : Stage.EXISTING;
    }

    private void stepObserveVariants(int budget) {
        if (!variantsEnabled || recipes.isEmpty()) {
            stage = Stage.EXISTING;
            return;
        }
        int remaining = recipes.size() - observeCursor;
        if (remaining <= 0) {
            initializeObservedEntries();
            stage = Stage.VARIANTS_BUILD;
            return;
        }
        int take = Math.min(budget, remaining);
        for (int index = observeCursor; index < observeCursor + take; index++) {
            RecipeSnapshot recipe = recipes.get(index);
            for (RecipeOutput output : recipe.outputs()) {
                String outputNbt = output.outputNbt();
                if (outputNbt == null || outputNbt.isBlank()) {
                    continue;
                }
                observedByItem
                        .computeIfAbsent(output.item().itemId(), id -> new ArrayList<>())
                        .add(outputNbt);
            }
        }
        observeCursor += take;
        if (cancellation.getAsBoolean()) {
            finishPartial();
            return;
        }
        if (observeCursor >= recipes.size()) {
            initializeObservedEntries();
            stage = Stage.VARIANTS_BUILD;
        }
    }

    private void initializeObservedEntries() {
        if (!observedEntries.isEmpty() || observedByItem.isEmpty()) {
            return;
        }
        observedEntries = List.copyOf(observedByItem.entrySet());
        baseByItem = new LinkedHashMap<>();
        for (ItemSnapshot item : items) {
            if (!item.key().hasVariant()) {
                baseByItem.putIfAbsent(item.registryId(), item);
            }
        }
    }

    private void stepBuildVariants(int budget) {
        initializeObservedEntries();
        if (observedEntries.isEmpty()) {
            finishVariants();
            stage = Stage.EXISTING;
            return;
        }
        int remaining = observedEntries.size() - buildCursor;
        if (remaining <= 0) {
            finishVariants();
            stage = Stage.EXISTING;
            return;
        }
        int take = Math.min(budget, remaining);
        for (int index = buildCursor; index < buildCursor + take; index++) {
            processedVariantEntries = index + 1;
            Map.Entry<ResourceLocation, List<String>> entry = observedEntries.get(index);
            ItemSnapshot base = baseByItem == null ? null : baseByItem.get(entry.getKey());
            if (base == null) {
                // Do not generate variants when the base item was not scanned (ignored or unregistered), to avoid bypassing exclusion rules.
                continue;
            }
            List<ItemKey> variantKeys = ScanPipeline.variantKeysFor(
                    variantScanner, entry.getKey(), entry.getValue(), maxVariants,
                    processedVariantEntries);
            for (ItemKey variantKey : variantKeys) {
                if (!variantKey.hasVariant() || !seenVariants.add(variantKey)) {
                    continue;
                }
                variants.add(ItemSnapshot.of(
                        variantKey, false, 0L, base.tagPaths(), base.rarity()));
            }
        }
        buildCursor += take;
        if (cancellation.getAsBoolean()) {
            finishPartial();
            return;
        }
        if (buildCursor >= observedEntries.size()) {
            finishVariants();
            stage = Stage.EXISTING;
        }
    }

    private void finishVariants() {
        if (variants.isEmpty()) {
            return;
        }
        variants.sort(java.util.Comparator.comparing(ItemSnapshot::key));
    }

    private void stepExistingValues(int budget) {
        if (allItems.isEmpty()) {
            allItems = combineItemsAndVariants();
        }
        int remaining = allItems.size() - existingCursor;
        if (remaining <= 0) {
            finishInternal();
            return;
        }
        int take = Math.min(budget, remaining);
        for (int index = existingCursor; index < existingCursor + take; index++) {
            ItemSnapshot item = allItems.get(index);
            if (item.hasExistingEmc()) {
                existingValues.put(item.key(), item.existingValue());
            }
        }
        existingCursor += take;
        // Check cancellation at the end of every batch. Previously cursor % 256 was used as the gate, but batches are
        // usually not multiples of 256, so that checkpoint almost never fired; the batch boundary itself is a low-cost cancellation point.
        if (cancellation.getAsBoolean()) {
            finishPartial();
            return;
        }
        if (existingCursor >= allItems.size()) {
            finishInternal();
        }
    }

    private List<ItemSnapshot> combineItemsAndVariants() {
        if (variants.isEmpty()) {
            return List.copyOf(items);
        }
        List<ItemSnapshot> combined = new ArrayList<>(items.size() + variants.size());
        combined.addAll(items);
        combined.addAll(variants);
        return List.copyOf(combined);
    }

    private void finishInternal() {
        if (done) {
            return;
        }
        allItems = allItems.isEmpty() ? combineItemsAndVariants() : allItems;
        if (prepareInputs != null) {
            prepareInputs.accept(allItems);
        }
        snapshot = new PipelineSnapshot(
                allItems,
                recipes,
                existingValues,
                unsupportedRecipes,
                invalidRecipes,
                failedRecipes + recipeEnumerationFailures,
                failedRecipeDetails);
        stage = Stage.DONE;
        done = true;
    }

    private void finishPartial() {
        if (done) {
            return;
        }
        List<ItemSnapshot> partialItems = allItems.isEmpty() ? combineItemsAndVariants() : allItems;
        Map<ItemKey, Long> partialExisting = new TreeMap<>();
        for (ItemSnapshot item : partialItems) {
            if (item.hasExistingEmc()) {
                partialExisting.put(item.key(), item.existingValue());
            }
        }
        // The partial snapshot must still keep the recipe failure statistics and details already scanned; zeroing them would under-report the post-cancel diagnostic report.
        snapshot = new PipelineSnapshot(partialItems, recipes, partialExisting,
                unsupportedRecipes, invalidRecipes, failedRecipes + recipeEnumerationFailures,
                failedRecipeDetails);
        stage = Stage.DONE;
        done = true;
    }

    private void addFailureDetail(String detail) {
        if (failedRecipeDetails.size() >= MAX_FAILED_DETAILS || detail == null) {
            return;
        }
        failedRecipeDetails.add(detail);
    }

    private static String failureText(Throwable failure) {
        if (failure == null) {
            return "未知错误";
        }
        String message = failure.getMessage();
        String type = failure.getClass().getSimpleName();
        if (message == null || message.isBlank()) {
            return type;
        }
        return type + ": " + message.replace('\r', ' ').replace('\n', ' ').trim();
    }
}
