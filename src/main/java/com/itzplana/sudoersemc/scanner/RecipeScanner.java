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

    /** 单个扫描结果最多保留的失败明细条数，避免异常配方数量失控时占用过多内存。 */
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

    /** 每扫描多少条配方检查一次取消信号。 */
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
     * <p>每 {@value #CANCELLATION_CHECK_INTERVAL} 条配方检查一次取消信号。取消时返回已经描述的
     * 部分配方与对应计数器；列表不可变，可用于部分诊断报告。</p>
     *
     * @param manager      the active recipe manager
     * @param cancellation 取消检查；返回 {@code true} 时停止继续扫描
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
     * 返回按 {@link ResourceLocation} 自然顺序排序的配方 id。
     *
     * <p>采集会话在开始配方阶段时固化一次顺序，随后按页推进；这样配方管理器的迭代顺序
     * 变化不会导致跨 tick 游标漂移。</p>
     *
     * @param manager 当前配方管理器
     * @return 不可变、排序后的配方 id 列表
     */
    public List<ResourceLocation> orderedRecipeIds(RecipeManager manager) {
        Objects.requireNonNull(manager, "manager");
        List<ResourceLocation> ids = manager.getRecipeIds().toList();
        ids.sort(ResourceLocation::compareTo);
        return List.copyOf(ids);
    }

    /**
     * 扫描一页已经排好序的配方 id。
     *
     * <p>本方法不修改扫描器状态，调用方负责维护游标与累计计数器。每
     * {@value #CANCELLATION_CHECK_INTERVAL} 条配方检查一次取消信号；取消时返回本页已经描述的
     * 部分配方。失败明细每页最多保留 {@link #MAX_FAILED_DETAILS} 条，跨页合并由调用方负责。</p>
     *
     * @param manager      当前配方管理器
     * @param orderedIds   稳定顺序的配方 id 列表
     * @param cancellation 取消检查；返回 {@code true} 时停止本页
     * @return 本页可用配方与跳过计数
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
     * 把一条失败配方整理成稳定、可读的单行明细。
     *
     * <p>明细数量达到 {@link #MAX_FAILED_DETAILS} 后停止记录，但调用方仍会继续累加失败
     * 总数，因此报告可以显示“已记录 X / 共 Y 条”。</p>
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
        if (inputs.isEmpty()) {
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
        if (inputs.isEmpty()) {
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
     * 判断配方能否放入其实际声明的合成网格。
     *
     * <p>旧实现无条件要求 3x3，会丢弃模组提供的 4x1、4x4 等工作台配方。这里先保留 3x3
     * 兼容路径，再按配方自己声明的 width×height 检查，绝不把“大于 3x3”直接当成非法。</p>
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
     * 使用配方输入的代表性物品构造临时合成网格，并读取 remaining items。
     *
     * <p>标签输入可能有多个候选；扫描阶段没有玩家选择，因此固定使用第一个候选。计算结果只用于
     * 成本抵扣，不会发布为产物。无法计算余量时返回空列表，配方仍然参与定价。</p>
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
                continue;
            }
            List<ItemKey> candidates = new ArrayList<>(stacks.length);
            for (ItemStack stack : stacks) {
                if (stack.isEmpty()) {
                    continue;
                }
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (id != null) {
                    candidates.add(ItemKey.of(id));
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }
            converted.add(RecipeInput.of(new IngredientOption(candidates)));
        }
        return converted;
    }

    /**
     * 把输出堆叠转换为不可变产物，并保留其 SNBT。
     *
     * <p>该方法为包级可见，便于单元测试直接验证 SNBT 保留与空标签语义。</p>
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
     * 把输出堆叠的 NBT 序列化为 SNBT。
     *
     * <p>没有标签时返回 {@code null}；序列化失败时记录调试日志并返回 {@code null}，
     * 绝不因为单个损坏的标签中断整轮配方扫描。</p>
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
            // 某些实现可能把空 CompoundTag 序列化成 "{}" 或带空白的等价形式；
            // 去除全部空白后仍为 "{}" 时按无 NBT 处理，避免下游生成伪变体。
            String compact = snbt.replaceAll("\\s+", "");
            if (compact.isEmpty() || "{}".equals(compact)) {
                return null;
            }
            // 可损坏物品默认携带 {Damage:0}，它只表示「未受损」，不是经济变体。
            // 与 VariantNormalizer 保持一致，避免下游把默认伤害当成真实差异。
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
     * @param failedRecipeDetails 失败配方的可读明细；最多保留 {@link RecipeScanner#MAX_FAILED_DETAILS} 条
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
         * 兼容旧调用点的构造器：不携带失败明细。
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
     * @param scanContainerRemainders 是否读取 crafting remaining items
     * @return 已接入 Goety 的扫描器；Goety 不可用时为空
     */
    public static Optional<RecipeScanner> withGoety(boolean scanContainerRemainders) {
        return GoetyRitualReader.available()
                ? Optional.of(new RecipeScanner(GoetyRitualReader.create(), scanContainerRemainders))
                : Optional.empty();
    }
}
