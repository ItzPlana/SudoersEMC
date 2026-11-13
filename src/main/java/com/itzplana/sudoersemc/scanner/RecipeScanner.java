package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts live Minecraft recipes into immutable {@link RecipeSnapshot}s.
 *
 * <p>Must run on the server thread. Every recipe is handled defensively: one broken or unsupported
 * recipe increments a counter instead of aborting the scan.</p>
 *
 * <p><strong>Contract:</strong> {@link RecipeSnapshot#inputs()} contains only items that are
 * actually consumed. A recipe's station, tool or altar is never listed, so evaluators can sum every
 * input without special casing.</p>
 */
public final class RecipeScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/scanner/RecipeScanner");

    /** Maximum failure details kept per scan result, avoiding excess memory when anomalous recipe counts run away. */
    static final int MAX_FAILED_DETAILS = 50;

    private final GoetyRitualReader goetyReader;
    private final boolean scanContainerRemainders;

    public RecipeScanner() {
        this(GoetyRitualReader.unavailable(), true);
    }

    /**
     * @param goetyReader optional Goety reflection bridge; pass {@link GoetyRitualReader#unavailable()}
     *                    when Goety is not installed
     */
    public RecipeScanner(GoetyRitualReader goetyReader) {
        this(goetyReader, true);
    }

    /**
     * @param goetyReader            optional Goety reflection bridge
     * @param scanContainerRemainders whether crafting remainders (buckets, bottles, ...) should be read
     */
    public RecipeScanner(GoetyRitualReader goetyReader, boolean scanContainerRemainders) {
        this.goetyReader = Objects.requireNonNull(goetyReader, "goetyReader");
        this.scanContainerRemainders = scanContainerRemainders;
    }

    /** How many recipes are scanned between cancellation checks. */
    public static final int CANCELLATION_CHECK_INTERVAL = 256;

    /**
     * @param manager the active recipe manager
     * @return every recipe this build can describe, plus counters for what it skipped
     */
    public ScanResult scan(RecipeManager manager) {
        return scan(manager, () -> false);
    }

    /**
     * Scans recipes with a cooperative cancellation checkpoint.
     *
     * <p>Checks the cancellation signal every {@value #CANCELLATION_CHECK_INTERVAL} recipes. On
     * cancellation returns the partial recipes already described and the corresponding counters; the
     * list is immutable and usable for a partial diagnostic report.</p>
     *
     * @param manager      the active recipe manager
     * @param cancellation cancellation check; stop scanning when it returns {@code true}
     * @return every processed recipe this build can describe, plus counters for what it skipped
     */
    public ScanResult scan(RecipeManager manager, BooleanSupplier cancellation) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(cancellation, "cancellation");
        List<ResourceLocation> ids;
        try {
            ids = orderedRecipeIds(manager);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not enumerate recipe ids", e);
            return new ScanResult(List.of(), 0, 0, 1,
                    List.of("recipe-manager: " + failureText(e)));
        }
        return scanBatch(manager, ids, cancellation);
    }

    /**
     * Returns recipe ids sorted in {@link ResourceLocation} natural order.
     *
     * <p>The collection session freezes the order once when the recipe phase begins, then advances by
     * page; this way a change in the recipe manager's iteration order cannot drift the cross-tick
     * cursor.</p>
     *
     * @param manager the current recipe manager
     * @return immutable, sorted recipe id list
     */
    public List<ResourceLocation> orderedRecipeIds(RecipeManager manager) {
        Objects.requireNonNull(manager, "manager");
        // Stream.toList() returns an immutable list; it must be copied before sorting. On a real server
        // sorting directly throws UnsupportedOperationException and degrades the whole recipe scan to 0 usable recipes.
        List<ResourceLocation> ids = new ArrayList<>(manager.getRecipeIds().toList());
        ids.sort(ResourceLocation::compareTo);
        return List.copyOf(ids);
    }

    /**
     * Scans one page of already-sorted recipe ids.
     *
     * <p>This method does not modify scanner state; the caller maintains the cursor and accumulated
     * counters. Checks the cancellation signal every {@value #CANCELLATION_CHECK_INTERVAL} recipes; on
     * cancellation returns the partial recipes already described in this page. Failure details keep at
     * most {@link #MAX_FAILED_DETAILS} per page; cross-page merging is the caller's responsibility.</p>
     *
     * @param manager      the current recipe manager
     * @param orderedIds   recipe id list in stable order
     * @param cancellation cancellation check; stop this page when it returns {@code true}
     * @return this page's usable recipes and skip counts
     */
    public ScanResult scanBatch(RecipeManager manager, List<ResourceLocation> orderedIds,
                                BooleanSupplier cancellation) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(orderedIds, "orderedIds");
        Objects.requireNonNull(cancellation, "cancellation");
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        List<String> failedDetails = new ArrayList<>();
        int unsupported = 0;
        int invalid = 0;
        int failed = 0;

        int processed = 0;
        for (ResourceLocation id : orderedIds) {
            if (processed % CANCELLATION_CHECK_INTERVAL == 0 && cancellation.getAsBoolean()) {
                break;
            }
            processed++;
            Optional<? extends Recipe<?>> maybeRecipe;
            try {
                maybeRecipe = manager.byKey(id);
            } catch (RuntimeException e) {
                failed++;
                addFailureDetail(failedDetails, id, e);
                continue;
            }
            if (maybeRecipe.isEmpty()) {
                invalid++;
                continue;
            }
            Recipe<?> recipe = maybeRecipe.get();
            try {
                RecipeSnapshot snapshot = describe(recipe, id);
                if (snapshot == null) {
                    unsupported++;
                    continue;
                }
                if (!snapshot.hasOutputs()) {
                    invalid++;
                    continue;
                }
                snapshots.add(snapshot);
            } catch (RuntimeException e) {
                failed++;
                addFailureDetail(failedDetails, id, e);
                LOGGER.debug("Failed to describe recipe {}; skipping it", id, e);
            }
        }

        if (unsupported > 0 || invalid > 0 || failed > 0) {
            LOGGER.debug("Recipe scan: {} usable, {} unsupported, {} invalid, {} failed",
                    snapshots.size(), unsupported, invalid, failed);
        }
        return new ScanResult(List.copyOf(snapshots), unsupported, invalid, failed,
                List.copyOf(failedDetails));
    }

    /**
     * Formats one failed recipe into a stable, readable single-line detail.
     *
     * <p>Recording stops once the detail count reaches {@link #MAX_FAILED_DETAILS}, but the caller still
     * accumulates the total failure count, so the report can show "recorded X / total Y".</p>
     */
    private static void addFailureDetail(List<String> details, ResourceLocation id,
                                         RuntimeException failure) {
        if (details.size() >= MAX_FAILED_DETAILS) {
            return;
        }
        String recipeId = id == null ? "unknown" : id.toString();
        details.add(recipeId + ": " + failureText(failure));
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
        String singleLine = message.replace('\r', ' ').replace('\n', ' ').trim();
        return type + ": " + singleLine;
    }

    /**
     * @return the snapshot, or {@code null} when this build cannot describe the recipe type
     */
    @Nullable
    RecipeSnapshot describe(Recipe<?> recipe, ResourceLocation id) {
        if (recipe instanceof AbstractCookingRecipe cooking) {
            return describeCooking(cooking, id);
        }
        if (recipe instanceof StonecutterRecipe stonecutter) {
            return describeSingleOutput(id, RecipeKind.STONECUTTER, stonecutter.getIngredients(),
                    stonecutter.getResultItem(null));
        }
        if (recipe instanceof CraftingRecipe crafting) {
            return describeCrafting(crafting, id);
        }
        RecipeSnapshot ritual = goetyReader.describe(recipe, id);
        if (ritual != null) {
            return ritual;
        }
        return null;
    }

    private RecipeSnapshot describeCooking(AbstractCookingRecipe recipe, ResourceLocation id) {
        RecipeKind kind;
        if (recipe instanceof SmeltingRecipe) {
            kind = RecipeKind.FURNACE;
        } else if (recipe instanceof BlastingRecipe) {
            kind = RecipeKind.BLAST_FURNACE;
        } else if (recipe instanceof SmokingRecipe) {
            kind = RecipeKind.SMOKER;
        } else {
            // Unknown cooking family: still a deterministic single-input conversion.
            kind = RecipeKind.FURNACE;
        }
        return describeSingleOutput(id, kind, recipe.getIngredients(), recipe.getResultItem(null));
    }

    private RecipeSnapshot describeSingleOutput(ResourceLocation id, RecipeKind kind,
                                                NonNullList<Ingredient> ingredients, ItemStack result) {
        List<RecipeInput> inputs = convertIngredients(ingredients);
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }
        List<RecipeOutput> outputs = convertStacks(List.of(result));
        if (outputs.isEmpty()) {
            return null;
        }
        return RecipeSnapshot.of(id, kind, inputs, outputs);
    }

    private RecipeSnapshot describeCrafting(CraftingRecipe recipe, ResourceLocation id) {
        if (recipe.isSpecial()) {
            // Special crafting recipes (dye blending, map cloning, ...) do not follow the generic
            // cost rule and are intentionally excluded from automatic pricing.
            return null;
        }
        NonNullList<Ingredient> ingredients = recipe.getIngredients();
        List<RecipeInput> inputs = convertIngredients(ingredients);
        if (inputs == null || inputs.isEmpty()) {
            return null;
        }

        RecipeKind kind;
        int width;
        int height;
        if (recipe instanceof ShapedRecipe shaped) {
            kind = RecipeKind.SHAPED;
            width = Math.max(1, shaped.getWidth());
            height = Math.max(1, shaped.getHeight());
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            kind = RecipeKind.SHAPELESS;
            width = Math.max(1, shapeless.getIngredients().size());
            height = 1;
        } else {
            // Unknown generic crafting recipe: preserve the previous shaped classification while
            // still accepting grids larger than 3x3.
            kind = RecipeKind.SHAPED;
            width = Math.max(1, ingredients.size());
            height = 1;
        }
        if (!fitsCraftingGrid(recipe, width, height)) {
            return null;
        }

        ItemStack result;
        try {
            result = recipe.getResultItem(null);
        } catch (RuntimeException e) {
            return null;
        }
        // Reuse the shared stack conversion path so output counts and NBT handling stay identical
        // to every other recipe family. A single result stack may still have count > 1; that count
        // is divided in the evaluator, never duplicated here.
        List<RecipeOutput> outputs = convertStacks(List.of(result));
        if (outputs.isEmpty()) {
            return null;
        }

        List<RecipeOutput> remainders = scanContainerRemainders
                ? containerRemainderOutputs(recipe, ingredients, width, height)
                : List.of();
        return new RecipeSnapshot(id, kind, inputs, outputs, remainders, false,
                java.util.Map.of(
                        "gridWidth", Integer.toString(width),
                        "gridHeight", Integer.toString(height)));
    }

    /**
     * Whether the recipe fits its actually declared crafting grid.
     *
     * <p>The old implementation unconditionally required 3x3, discarding mod-provided 4x1, 4x4 etc.
     * workbench recipes. Here the 3x3 compatibility path is kept first, then checked against the
     * recipe's own declared width×height; "larger than 3x3" is never treated as illegal outright.</p>
     */
    private static boolean fitsCraftingGrid(CraftingRecipe recipe, int width, int height) {
        try {
            if (recipe.canCraftInDimensions(3, 3)) {
                return true;
            }
            return width > 0 && height > 0 && recipe.canCraftInDimensions(width, height);
        } catch (RuntimeException failure) {
            LOGGER.debug("配方网格判断失败，按不可合成处理", failure);
            return false;
        }
    }

    /**
     * Builds a temporary crafting grid from representative items of the recipe inputs and reads the
     * remaining items.
     *
     * <p>A tag input may have multiple candidates; the scan phase has no player choice, so the first
     * candidate is used fixed. The result is only used for cost credit and is never published as an
     * output. When remainders cannot be computed, returns an empty list and the recipe still participates
     * in pricing.</p>
     */
    private static List<RecipeOutput> containerRemainderOutputs(CraftingRecipe recipe,
                                                               NonNullList<Ingredient> ingredients,
                                                               int width, int height) {
        try {
            int size = Math.max(1, ingredients.size());
            NonNullList<ItemStack> items = NonNullList.withSize(size, ItemStack.EMPTY);
            for (int index = 0; index < size; index++) {
                Ingredient ingredient = index < ingredients.size() ? ingredients.get(index) : null;
                if (ingredient == null || ingredient.isEmpty()) {
                    continue;
                }
                ItemStack[] stacks = ingredient.getItems();
                if (stacks.length > 0 && !stacks[0].isEmpty()) {
                    items.set(index, stacks[0].copyWithCount(1));
                }
            }
            TransientCraftingContainer container = new TransientCraftingContainer(
                    null, Math.max(1, width), Math.max(1, height), items);
            return convertStacks(recipe.getRemainingItems(container));
        } catch (RuntimeException failure) {
            LOGGER.debug("无法计算配方 {} 的容器返还物，按无余量处理", recipe.getId(), failure);
            return List.of();
        }
    }

    /**
     * Converts Minecraft ingredients. Tag ingredients become multi-choice options so the evaluator
     * can pick the cheapest member.
     */
    @Nullable
    private static List<RecipeInput> convertIngredients(NonNullList<Ingredient> ingredients) {
        List<RecipeInput> converted = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                continue;
            }
            ItemStack[] stacks;
            try {
                stacks = ingredient.getItems();
            } catch (RuntimeException e) {
                return null;
            }
            List<ItemKey> candidates = new ArrayList<>(stacks.length);
            for (ItemStack stack : stacks) {
                if (stack.isEmpty() || IngredientStacks.isEmptyTagPlaceholder(stack)) {
                    continue;
                }
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) {
                    candidates.add(ItemKey.of(id));
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            converted.add(RecipeInput.of(new IngredientOption(candidates)));
        }
        return converted;
    }

    /**
     * Converts an output stack into an immutable output, preserving its SNBT.
     *
     * <p>Package-visible so unit tests can directly verify SNBT preservation and empty-tag semantics.</p>
     */
    @Nullable
    static RecipeOutput convertStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        return new RecipeOutput(ItemKey.of(id), Math.max(1, stack.getCount()), serializeTag(stack));
    }

    /**
     * Serialises an output stack's NBT to SNBT.
     *
     * <p>Returns {@code null} when there is no tag; on serialisation failure logs a debug message and
     * returns {@code null}, never aborting the whole recipe scan because of a single corrupt tag.</p>
     */
    @Nullable
    private static String serializeTag(ItemStack stack) {
        try {
            CompoundTag tag = stack.getTag();
            if (tag == null || tag.isEmpty()) {
                return null;
            }
            String snbt = tag.toString();
            if (snbt == null) {
                return null;
            }
            // Some implementations may serialise an empty CompoundTag as "{}" or a whitespace-bearing equivalent;
            // when still "{}" after removing all whitespace, treat as no NBT to avoid downstream fake variants.
            String compact = snbt.replaceAll("\\s+", "");
            if (compact.isEmpty() || "{}".equals(compact)) {
                return null;
            }
            // Damageable items carry {Damage:0} by default; it only means "undamaged", not an economic variant.
            // Stay consistent with VariantNormalizer so downstream does not treat default damage as a real difference.
            return "{Damage:0}".equals(compact) ? null : snbt;
        } catch (RuntimeException failure) {
            LOGGER.debug("序列化配方输出 NBT 失败，按无 NBT 处理", failure);
            return null;
        }
    }

    private static List<RecipeOutput> convertStacks(@Nullable Iterable<ItemStack> stacks) {
        if (stacks == null) {
            return List.of();
        }
        List<RecipeOutput> converted = new ArrayList<>();
        for (ItemStack stack : stacks) {
            RecipeOutput output = convertStack(stack);
            if (output != null) {
                converted.add(output);
            }
        }
        return converted;
    }

    /**
     * Outcome of one recipe scan.
     *
     * @param recipes             every usable snapshot
     * @param unsupported         recipes whose type this build cannot evaluate
     * @param invalid             recipes missing an id, inputs or outputs
     * @param failed              recipes that threw while being described
     * @param failedRecipeDetails readable details of failed recipes; at most {@link RecipeScanner#MAX_FAILED_DETAILS} kept
     */
    public record ScanResult(List<RecipeSnapshot> recipes, int unsupported, int invalid, int failed,
                             List<String> failedRecipeDetails) {

        public ScanResult {
            recipes = List.copyOf(recipes);
            failedRecipeDetails = failedRecipeDetails == null
                    ? List.of()
                    : List.copyOf(failedRecipeDetails);
        }

        /**
         * Compatibility constructor for older callers: carries no failure details.
         */
        public ScanResult(List<RecipeSnapshot> recipes, int unsupported, int invalid, int failed) {
            this(recipes, unsupported, invalid, failed, List.of());
        }

        public int total() {
            return recipes.size() + unsupported + invalid + failed;
        }
    }

    /** Convenience accessor used by the pipeline. */
    public static Optional<RecipeScanner> withGoety() {
        return withGoety(true);
    }

    /**
     * @param scanContainerRemainders whether to read crafting remaining items
     * @return a scanner wired to Goety; empty when Goety is unavailable
     */
    public static Optional<RecipeScanner> withGoety(boolean scanContainerRemainders) {
        return GoetyRitualReader.available()
                ? Optional.of(new RecipeScanner(GoetyRitualReader.create(), scanContainerRemainders))
                : Optional.empty();
    }
}
