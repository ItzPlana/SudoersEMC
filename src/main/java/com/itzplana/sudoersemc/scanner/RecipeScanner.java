package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * recipe increments a counter instead of aborting the scan. Failure to enumerate the recipe id
 * collection itself is different: it aborts the scan with {@link IllegalStateException} because an
 * empty id list would otherwise be indistinguishable from a successful empty scan.</p>
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
        ScanSession session = beginScan(manager);
        while (!session.isDone()) {
            session.step(Integer.MAX_VALUE);
        }
        return session.result();
    }

    /**
     * 创建一个分片配方扫描会话。
     *
     * <p>会话开始时只枚举一次配方 id，不会描述任何配方；之后每个 {@link ScanSession#step(int)}
     * 最多描述 {@code maxItems} 条配方。会话必须在服务器线程推进。</p>
     *
     * <p><strong>失败语义：</strong>配方 id 枚举失败代表整个配方输入不可读取，必须让本次采集
     * 失败而不是返回空列表；否则 {@code ScanManager} 会以 0 配方完成扫描并写入空配方指纹。
     * 单条配方在 {@link #scanRecipe} 中失败仍然只增加 {@code failed} 计数，不影响其余配方。</p>
     *
     * @param manager the active recipe manager
     * @return 新的分片扫描会话
     * @throws IllegalStateException 无法枚举配方 id 时抛出
     */
    public ScanSession beginScan(RecipeManager manager) {
        Objects.requireNonNull(manager, "manager");
        // getRecipeIds()/byKey() is the only 1.20.1 API that pairs an id with its recipe without
        // touching private state.
        List<ResourceLocation> ids;
        try {
            ids = manager.getRecipeIds().toList();
        } catch (RuntimeException e) {
            LOGGER.error("Could not enumerate recipe ids", e);
            throw new IllegalStateException("扫描采集失败：无法枚举配方 id", e);
        }
        return new ScanSession(this, manager, ids);
    }

    /**
     * 处理单条配方并把结果写入会话计数器。
     */
    private void scanRecipe(RecipeManager manager, ResourceLocation id, ScanSession session) {
        Optional<? extends Recipe<?>> maybeRecipe;
        try {
            maybeRecipe = manager.byKey(id);
        } catch (RuntimeException e) {
            session.failed++;
            return;
        }
        if (maybeRecipe.isEmpty()) {
            session.invalid++;
            return;
        }
        Recipe<?> recipe = maybeRecipe.get();
        try {
            RecipeSnapshot snapshot = describe(recipe, id);
            if (snapshot == null) {
                session.unsupported++;
                return;
            }
            if (!snapshot.hasOutputs()) {
                session.invalid++;
                return;
            }
            session.snapshots.add(snapshot);
        } catch (RuntimeException e) {
            session.failed++;
            LOGGER.debug("Failed to describe recipe {}; skipping it", id, e);
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
     * 分片配方扫描会话。
     *
     * <p>配方 id 在会话开始时一次性枚举，单条配方的描述工作按 {@link #step(int)} 的预算推进。
     * 会话只能在服务器线程使用。</p>
     */
    public static final class ScanSession {

        private final RecipeScanner scanner;
        private final RecipeManager manager;
        private final List<ResourceLocation> ids;
        private final List<RecipeSnapshot> snapshots = new ArrayList<>();

        private int cursor;
        private int unsupported;
        private int invalid;
        private int failed;
        private boolean completionLogged;

        private ScanSession(RecipeScanner scanner, RecipeManager manager, List<ResourceLocation> ids) {
            this.scanner = Objects.requireNonNull(scanner, "scanner");
            this.manager = Objects.requireNonNull(manager, "manager");
            this.ids = List.copyOf(Objects.requireNonNull(ids, "ids"));
        }

        /**
         * @return 计划描述的配方数量
         */
        public int totalRecipes() {
            return ids.size();
        }

        /**
         * @return 已经处理的配方数量
         */
        public int processedRecipes() {
            return cursor;
        }

        /**
         * @return 是否已经处理完所有配方 id
         */
        public boolean isDone() {
            return cursor >= ids.size();
        }

        /**
         * 推进最多 {@code maxItems} 条配方。
         *
         * @param maxItems 本次允许处理的最大配方数，必须大于等于 1
         * @return 本批实际处理的配方数量
         */
        public int step(int maxItems) {
            if (maxItems < 1) {
                throw new IllegalArgumentException("maxItems 必须 >= 1，实际为 " + maxItems);
            }
            int processed = 0;
            while (processed < maxItems && cursor < ids.size()) {
                ResourceLocation id = ids.get(cursor++);
                scanner.scanRecipe(manager, id, this);
                processed++;
            }
            if (cursor >= ids.size()) {
                logCompletion();
            }
            return processed;
        }

        /**
         * @return 本会话的扫描结果；可在未完成时调用，返回当前已处理的条目
         */
        public ScanResult result() {
            return new ScanResult(List.copyOf(snapshots), unsupported, invalid, failed);
        }

        private void logCompletion() {
            if (completionLogged) {
                return;
            }
            completionLogged = true;
            if (unsupported > 0 || invalid > 0 || failed > 0) {
                LOGGER.debug("Recipe scan: {} usable, {} unsupported, {} invalid, {} failed",
                        snapshots.size(), unsupported, invalid, failed);
            }
        }
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
