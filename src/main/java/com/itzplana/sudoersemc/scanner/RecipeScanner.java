package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
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

    private final GoetyRitualReader goetyReader;

    public RecipeScanner() {
        this(GoetyRitualReader.unavailable());
    }

    /**
     * @param goetyReader optional Goety reflection bridge; pass {@link GoetyRitualReader#unavailable()}
     *                    when Goety is not installed
     */
    public RecipeScanner(GoetyRitualReader goetyReader) {
        this.goetyReader = Objects.requireNonNull(goetyReader, "goetyReader");
    }

    /**
     * @param manager the active recipe manager
     * @return every recipe this build can describe, plus counters for what it skipped
     */
    public ScanResult scan(RecipeManager manager) {
        RecipeScanSession session = beginSession(manager);
        while (!session.isDone()) {
            session.step(Integer.MAX_VALUE);
        }
        return session.result();
    }

    /**
     * 开始一次可分片推进的配方扫描。
     *
     * <p>会话在构造时只枚举配方 id，不读取或转换任何配方；每次
     * {@link RecipeScanSession#step(int)} 最多描述 {@code maxItems} 条配方。
     * 这样 {@code ScanManager} 可以把配方扫描分散到多个服务器 tick，同时保持与
     * {@link #scan(RecipeManager)} 完全相同的排除/失败计数语义。</p>
     *
     * @param manager 当前配方管理器
     * @return 新的配方扫描游标
     */
    public RecipeScanSession beginSession(RecipeManager manager) {
        return beginSession(manager, null);
    }

    /**
     * 开始一次可分片推进的配方扫描，并在每条配方成功描述后回调。
     *
     * <p>回调在生产接线中用于增量累积配方内容指纹：配方快照一产生就转换为稳定字符串，
     * 不再等到采集结束再全量遍历。回调在服务器线程、配方扫描批次内执行，必须保持轻量；
     * 抛出的运行时异常会让本次扫描失败。</p>
     *
     * @param manager    当前配方管理器
     * @param onSnapshot 每条可用配方的回调，允许为 {@code null}
     * @return 新的配方扫描游标
     */
    public RecipeScanSession beginSession(RecipeManager manager,
                                          Consumer<RecipeSnapshot> onSnapshot) {
        Objects.requireNonNull(manager, "manager");
        List<ResourceLocation> ids;
        try {
            // getRecipeIds()/byKey() is the only 1.20.1 API that pairs an id with its recipe without
            // touching private state.
            ids = manager.getRecipeIds().toList();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not enumerate recipe ids", e);
            ids = List.of();
        }
        return new RecipeScanSession(this, manager, ids, onSnapshot);
    }

    /**
     * 持有配方 id 游标的扫描会话。
     */
    public static final class RecipeScanSession {

        private final RecipeScanner scanner;
        private final RecipeManager manager;
        private final List<ResourceLocation> ids;
        private final Consumer<RecipeSnapshot> onSnapshot;
        private final List<RecipeSnapshot> snapshots = new ArrayList<>();

        private int index;
        private int unsupported;
        private int invalid;
        private int failed;
        private boolean summaryLogged;

        private RecipeScanSession(RecipeScanner scanner,
                                  RecipeManager manager,
                                  List<ResourceLocation> ids,
                                  Consumer<RecipeSnapshot> onSnapshot) {
            this.scanner = scanner;
            this.manager = manager;
            this.ids = List.copyOf(ids);
            this.onSnapshot = onSnapshot;
        }

        /**
         * @return 计划处理的配方总数
         */
        public int total() {
            return ids.size();
        }

        /**
         * @return 已经检查过的配方数
         */
        public int processed() {
            return index;
        }

        /**
         * @return 是否已经处理完全部配方 id
         */
        public boolean isDone() {
            return index >= ids.size();
        }

        /**
         * 检查最多 {@code maxItems} 条配方。
         *
         * @param maxItems 本次允许处理的最大配方数，必须大于等于 1
         * @return 本次实际检查的配方数；全部处理完时返回 0
         */
        public int step(int maxItems) {
            return step(maxItems, Long.MAX_VALUE);
        }

        /**
         * 检查最多 {@code maxItems} 条配方，并在每条配方之间检查截止时间。
         *
         * <p>单条配方的描述无法被抢占，因此截止时间是最佳努力：本方法会在当前配方处理完后
         * 立即返回，而不会中断正在进行的转换。</p>
         *
         * @param maxItems      本次允许处理的最大配方数，必须大于等于 1
         * @param deadlineNanos {@link System#nanoTime()} 截止时间；{@link Long#MAX_VALUE} 表示不限
         * @return 本次实际检查的配方数；全部处理完时返回 0
         */
        public int step(int maxItems, long deadlineNanos) {
            if (maxItems < 1) {
                throw new IllegalArgumentException("maxItems 必须 >= 1，实际为 " + maxItems);
            }
            int work = 0;
            while (work < maxItems && index < ids.size()) {
                if (deadlineReached(deadlineNanos)) {
                    break;
                }
                ResourceLocation id = ids.get(index++);
                work++;
                scanOne(id);
            }
            if (isDone()) {
                logSummary();
            }
            return work;
        }

        private static boolean deadlineReached(long deadlineNanos) {
            return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
        }

        /**
         * @return 到目前为止的结果；未完成时包含已扫描到的配方与实时计数
         */
        public ScanResult result() {
            if (isDone()) {
                logSummary();
            }
            return new ScanResult(List.copyOf(snapshots), unsupported, invalid, failed);
        }

        private void scanOne(ResourceLocation id) {
            Optional<? extends Recipe<?>> maybeRecipe;
            try {
                maybeRecipe = manager.byKey(id);
            } catch (RuntimeException e) {
                failed++;
                return;
            }
            if (maybeRecipe.isEmpty()) {
                invalid++;
                return;
            }
            Recipe<?> recipe = maybeRecipe.get();
            RecipeSnapshot snapshot;
            try {
                snapshot = scanner.describe(recipe, id);
                if (snapshot == null) {
                    unsupported++;
                    return;
                }
                if (!snapshot.hasOutputs()) {
                    invalid++;
                    return;
                }
            } catch (RuntimeException e) {
                failed++;
                LOGGER.debug("Failed to describe recipe {}; skipping it", id, e);
                return;
            }
            snapshots.add(snapshot);
            if (onSnapshot != null) {
                // 指纹回调失败必须让本次扫描失败，不能在结果集中留下未进入指纹的配方。
                onSnapshot.accept(snapshot);
            }
        }

        private void logSummary() {
            if (summaryLogged) {
                return;
            }
            summaryLogged = true;
            if (unsupported > 0 || invalid > 0 || failed > 0) {
                LOGGER.debug("Recipe scan: {} usable, {} unsupported, {} invalid, {} failed",
                        snapshots.size(), unsupported, invalid, failed);
            }
        }
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
        ConvertedIngredients converted = convertIngredients(ingredients, false);
        if (converted == null || converted.inputs().isEmpty()) {
            return null;
        }
        RecipeOutput output = convertStack(result);
        if (output == null) {
            return null;
        }
        return RecipeSnapshot.of(id, kind, converted.inputs(), List.of(output));
    }

    private RecipeSnapshot describeCrafting(CraftingRecipe recipe, ResourceLocation id) {
        if (recipe.isSpecial()) {
            // Special crafting recipes (dye blending, map cloning, ...) do not follow the generic
            // cost rule and are intentionally excluded from automatic pricing.
            return null;
        }
        if (!recipe.canCraftInDimensions(3, 3)) {
            return null;
        }
        RecipeKind kind = recipe instanceof ShapelessRecipe ? RecipeKind.SHAPELESS : RecipeKind.SHAPED;
        // Vanilla Recipe#getRemainingItems dereferences its container argument, and ShapedRecipe /
        // ShapelessRecipe do not override it. Deriving remainders from the ingredient choices keeps
        // the scan container-free; any unreadable slot makes the whole recipe unsupported.
        ConvertedIngredients converted = convertIngredients(recipe.getIngredients(), true);
        if (converted == null || converted.inputs().isEmpty()) {
            return null;
        }
        ItemStack result;
        try {
            result = recipe.getResultItem(null);
        } catch (RuntimeException e) {
            return null;
        }
        RecipeOutput output = convertStack(result);
        if (output == null) {
            return null;
        }
        return new RecipeSnapshot(id, kind, converted.inputs(), List.of(output),
                converted.remainders(), false, java.util.Map.of());
    }

    /**
     * Converts Minecraft ingredients. Tag ingredients become multi-choice options so the evaluator
     * can pick the cheapest member.
     *
     * <p>Fail-closed: if a non-empty slot cannot be read (an exception, an unbound tag, or no usable
     * candidates) the whole recipe is reported as unsupported instead of being priced with missing
     * inputs. Container remainders are only credited when every candidate for a slot returns the
     * same remainder item, because the scan cannot know which candidate the player will use.</p>
     *
     * @param collectRemainders whether to derive container remainders for this recipe family
     * @return the conversion, or {@code null} when the recipe cannot be described safely
     */
    @Nullable
    private static ConvertedIngredients convertIngredients(NonNullList<Ingredient> ingredients,
                                                            boolean collectRemainders) {
        List<RecipeInput> inputs = new ArrayList<>();
        List<RecipeOutput> remainders = new ArrayList<>();
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
            ItemKey commonRemainder = null;
            boolean remainderAmbiguous = false;
            for (ItemStack stack : stacks) {
                if (stack.isEmpty() || IngredientStacks.isEmptyTagPlaceholder(stack)) {
                    continue;
                }
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemId != null) {
                    candidates.add(ItemKey.of(itemId));
                }
                if (collectRemainders) {
                    if (stack.hasCraftingRemainingItem()) {
                        ItemStack remainder = stack.getCraftingRemainingItem();
                        ResourceLocation remainderId = remainder.isEmpty()
                                ? null
                                : BuiltInRegistries.ITEM.getKey(remainder.getItem());
                        if (remainderId == null) {
                            remainderAmbiguous = true;
                        } else {
                            ItemKey remainderKey = ItemKey.of(remainderId);
                            if (commonRemainder == null) {
                                commonRemainder = remainderKey;
                            } else if (!commonRemainder.equals(remainderKey)) {
                                remainderAmbiguous = true;
                            }
                        }
                    } else {
                        // Another candidate would not return a container, so crediting one would
                        // understate the recipe cost.
                        remainderAmbiguous = true;
                    }
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            inputs.add(RecipeInput.of(new IngredientOption(candidates)));
            if (collectRemainders && !remainderAmbiguous && commonRemainder != null) {
                remainders.add(RecipeOutput.of(commonRemainder));
            }
        }
        return new ConvertedIngredients(inputs, remainders);
    }

    /**
     * Result of a fail-closed ingredient conversion. Callers must reject an empty {@code inputs}
     * list as unsupported.
     *
     * @param inputs     consumed slots
     * @param remainders container returns that are unambiguous across every candidate
     */
    private record ConvertedIngredients(List<RecipeInput> inputs, List<RecipeOutput> remainders) {
    }

    @Nullable
    private static RecipeOutput convertStack(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        return new RecipeOutput(ItemKey.of(id), Math.max(1, stack.getCount()));
    }

    /**
     * Outcome of one recipe scan.
     *
     * @param recipes     every usable snapshot
     * @param unsupported recipes whose type this build cannot evaluate
     * @param invalid     recipes missing an id, inputs or outputs
     * @param failed      recipes that threw while being described
     */
    public record ScanResult(List<RecipeSnapshot> recipes, int unsupported, int invalid, int failed) {

        public ScanResult {
            recipes = List.copyOf(recipes);
        }

        public int total() {
            return recipes.size() + unsupported + invalid + failed;
        }
    }

    /** Convenience accessor used by the pipeline. */
    public static Optional<RecipeScanner> withGoety() {
        return GoetyRitualReader.available() ? Optional.of(new RecipeScanner(GoetyRitualReader.create())) : Optional.empty();
    }
}
