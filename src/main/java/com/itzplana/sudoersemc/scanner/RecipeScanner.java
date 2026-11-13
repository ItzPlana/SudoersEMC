package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.resolver.VariantNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
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
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        List<String> failedDetails = new ArrayList<>();
        int unsupported = 0;
        int invalid = 0;
        int failed = 0;

        // getRecipeIds()/byKey() is the only 1.20.1 API that pairs an id with its recipe without
        // touching private state.
        List<ResourceLocation> ids;
        try {
            ids = manager.getRecipeIds().toList();
        } catch (RuntimeException e) {
            // phase-22 诊断语义：配方 id 枚举失败时必须返回带独立标志的结果，
            // 不能把“0 条配方”当成真实统计；调用方应先检查 enumerationFailed()。
            LOGGER.warn("Could not enumerate recipe ids", e);
            return new ScanResult(List.of(), 0, 0, 0, true,
                    List.of("recipe-manager: " + failureText(e)));
        }

        int processed = 0;
        for (ResourceLocation id : ids) {
            if (processed % CANCELLATION_CHECK_INTERVAL == 0 && cancellation.getAsBoolean()) {
                break;
            }
            processed++;
            Optional<? extends Recipe<?>> maybeRecipe;
            try {
                maybeRecipe = manager.byKey(id);
            } catch (RuntimeException | LinkageError e) {
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
            } catch (RuntimeException | LinkageError e) {
                // 可选模组的配方类可能在加载/链接阶段失败（NoClassDefFoundError 等）；
                // 只跳过该条配方，不能让整轮扫描失去全部数据。
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
     * 创建 phase-12 的分片配方扫描视图。
     *
     * <p>保留 {@link ScanSession#totalRecipes()} / {@link ScanSession#processedRecipes()} /
     * {@link ScanSession#step(int)} 的旧契约，内部委托给 {@link RecipeScanSession}；
     * 生产接线应优先使用 {@link #beginSession(RecipeManager)} 以获得截止时间与增量回调支持。</p>
     *
     * @param manager the active recipe manager
     * @return 新的分片扫描会话
     * @throws IllegalStateException 无法枚举配方 id 时抛出
     */
    public ScanSession beginScan(RecipeManager manager) {
        return new ScanSession(beginSession(manager));
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
     * @throws IllegalStateException 无法枚举配方 id 时抛出
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
     * <p><strong>失败语义：</strong>配方 id 枚举失败代表整个配方输入不可读取，必须让本次采集
     * 失败而不是返回空列表；否则 {@code ScanManager} 会以 0 配方完成扫描并写入空配方指纹。
     * 单条配方在 {@link RecipeScanSession#scanOne(ResourceLocation)} 中失败仍然只增加
     * {@code failed} 计数，不影响其余配方。</p>
     *
     * @param manager    当前配方管理器
     * @param onSnapshot 每条可用配方的回调，允许为 {@code null}
     * @return 新的配方扫描游标
     * @throws IllegalStateException 无法枚举配方 id 时抛出
     */
    public RecipeScanSession beginSession(RecipeManager manager,
                                          Consumer<RecipeSnapshot> onSnapshot) {
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
        return new RecipeScanSession(this, manager, ids, onSnapshot);
    }

    /**
     * 持有配方 id 游标的扫描会话。
     *
     * <p>配方 id 在会话开始时一次性枚举，单条配方的描述工作按 {@link #step(int)} 的预算推进，
     * 并在每条配方之间检查截止时间。会话只能在服务器线程使用。</p>
     */
    public static final class RecipeScanSession {

        private final RecipeScanner scanner;
        private final RecipeManager manager;
        private final List<ResourceLocation> ids;
        private final Consumer<RecipeSnapshot> onSnapshot;
        private final List<RecipeSnapshot> snapshots = new ArrayList<>();
        private final List<String> failedDetails = new ArrayList<>();

        private int index;
        private int unsupported;
        private int invalid;
        private int failed;
        private boolean summaryLogged;

        private RecipeScanSession(RecipeScanner scanner,
                                  RecipeManager manager,
                                  List<ResourceLocation> ids,
                                  Consumer<RecipeSnapshot> onSnapshot) {
            this.scanner = Objects.requireNonNull(scanner, "scanner");
            this.manager = Objects.requireNonNull(manager, "manager");
            this.ids = List.copyOf(Objects.requireNonNull(ids, "ids"));
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
            return new ScanResult(List.copyOf(snapshots), unsupported, invalid, failed, false,
                    List.copyOf(failedDetails));
        }

        /**
         * @return 到目前为止采集到的配方快照的不可变副本
         */
        public List<RecipeSnapshot> snapshots() {
            return List.copyOf(snapshots);
        }

        private void scanOne(ResourceLocation id) {
            Optional<? extends Recipe<?>> maybeRecipe;
            try {
                maybeRecipe = manager.byKey(id);
            } catch (RuntimeException | LinkageError e) {
                failed++;
                addFailureDetail(failedDetails, id, e);
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
                snapshots.add(snapshot);
            } catch (RuntimeException | LinkageError e) {
                // 可选模组的配方类可能在加载/链接阶段失败（NoClassDefFoundError 等）；
                // 只跳过该条配方，不能让整轮扫描失去全部数据。
                failed++;
                addFailureDetail(failedDetails, id, e);
                LOGGER.debug("Failed to describe recipe {}; skipping it", id, e);
                return;
            }
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
     * 把一条失败配方整理成稳定、可读的单行明细。
     *
     * <p>明细数量达到 {@link #MAX_FAILED_DETAILS} 后停止记录，但调用方仍会继续累加失败
     * 总数，因此报告可以显示“已记录 X / 共 Y 条”。</p>
     */
    private static void addFailureDetail(List<String> details, ResourceLocation id,
                                         Throwable failure) {
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
     * <p>标签输入可能有多个候选；求解器按“最便宜候选”计算成本，而返还物只能按一个具体候选读取。
     * 为了保持成本与抵扣口径一致，只要任一输入槽存在多个候选就放弃本配方的返还物抵扣。
     * 计算结果只用于成本抵扣，不会发布为产物。无法计算余量时返回空列表，配方仍然参与定价。</p>
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
                if (stacks.length != 1 || stacks[0].isEmpty()
                        || IngredientStacks.isEmptyTagPlaceholder(stacks[0])) {
                    // 多候选标签的首个候选可能不同于求解器选中的最便宜输入，
                    // 返还物无法与成本口径对齐，直接放弃本配方的返还物抵扣。
                    return List.of();
                }
                items.set(index, stacks[0].copyWithCount(1));
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
     * 把输出堆叠转换为不可变产物，并保留其 SNBT。
     *
     * <p>输出身份与变体扫描共用 {@link VariantNormalizer#keyFor(ResourceLocation, CompoundTag)}：
     * 只有经济相关字段会进入 {@link ItemKey#variantHash()}，展示字段、可损坏物品的
     * {@code Damage} 等不会产生额外变体。原始 SNBT 单独保存在 {@link RecipeOutput#outputNbt()}，
     * 供后续注册 ProjectE 的精确 NBT key。</p>
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
        CompoundTag tag = effectiveVariantTag(stack);
        ItemKey key = tag == null ? ItemKey.of(id) : VariantNormalizer.keyFor(id, tag);
        return new RecipeOutput(key, Math.max(1, stack.getCount()), serializeTag(tag));
    }

    /**
     * 读取参与变体身份计算的标签，并把“仅数值为 0 的 {@code Damage}”归一为无 NBT。
     *
     * <p>可损坏物品默认携带 {@code Damage:0}，它只表示“未受损”，不是经济变体；
     * 若保留该标签，非可损坏物品还会产生无意义的 {@code {Damage:0}} 伪变体。数值判定统一
     * 委托给 {@link VariantNormalizer#isDefaultDamageOnly(CompoundTag)}，避免本层用
     * {@code getInt()} 把 {@code 4294967296L}、{@code 0.5d} 等非零值截断为 0。</p>
     */
    @Nullable
    private static CompoundTag effectiveVariantTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        if (VariantNormalizer.isDefaultDamageOnly(tag)) {
            return null;
        }
        return tag;
    }

    /**
     * 把输出堆叠的 NBT 序列化为 SNBT。
     *
     * <p>没有标签、空标签或仅默认伤害（数值为 0 的 {@code Damage}）时返回 {@code null}；
     * 序列化失败时记录调试日志并返回 {@code null}，绝不因为单个损坏的标签中断整轮配方扫描。</p>
     */
    @Nullable
    private static String serializeTag(@Nullable CompoundTag tag) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        try {
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
            // 默认伤害的数值判定统一由 VariantNormalizer 提供，覆盖 0b/0L/0.0f 等标签类型；
            // 避免本层再用精确字符串判定造成两套语义。
            return VariantNormalizer.isDefaultDamageOnly(tag) ? null : snbt;
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
     * phase-12 的分片配方扫描视图，委托给 {@link RecipeScanSession}。
     *
     * <p>保留 {@link #totalRecipes()} / {@link #processedRecipes()} / {@link #step(int)} 的旧契约，
     * 供 {@code ScanPipeline} 与既有测试使用。</p>
     */
    public static final class ScanSession {

        private final RecipeScanSession delegate;

        private ScanSession(RecipeScanSession delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /**
         * @return 计划描述的配方数量
         */
        public int totalRecipes() {
            return delegate.total();
        }

        /**
         * @return 已经处理的配方数量
         */
        public int processedRecipes() {
            return delegate.processed();
        }

        /**
         * @return 是否已经处理完所有配方 id
         */
        public boolean isDone() {
            return delegate.isDone();
        }

        /**
         * 推进最多 {@code maxItems} 条配方。
         *
         * @param maxItems 本次允许处理的最大配方数，必须大于等于 1
         * @return 本批实际处理的配方数量
         */
        public int step(int maxItems) {
            return delegate.step(maxItems);
        }

        /**
         * @return 本会话的扫描结果；可在未完成时调用，返回当前已处理的条目
         */
        public ScanResult result() {
            return delegate.result();
        }
    }

    /**
     * Outcome of one recipe scan.
     *
     * @param recipes             every usable snapshot
     * @param unsupported         recipes whose type this build cannot evaluate
     * @param invalid             recipes missing an id, inputs or outputs
     * @param failed              recipes that threw while being described
     * @param enumerationFailed   是否在枚举配方 id 时整体失败；为 {@code true} 时总数未知
     * @param failedRecipeDetails 失败配方的可读明细；最多保留 {@link RecipeScanner#MAX_FAILED_DETAILS} 条
     */
    public record ScanResult(List<RecipeSnapshot> recipes, int unsupported, int invalid, int failed,
                             boolean enumerationFailed, List<String> failedRecipeDetails) {

        public ScanResult {
            recipes = List.copyOf(recipes);
            failedRecipeDetails = failedRecipeDetails == null
                    ? List.of()
                    : List.copyOf(failedRecipeDetails);
        }

        /**
         * 兼容 Phase 22 早期调用点的构造器：不携带枚举失败标志。
         */
        public ScanResult(List<RecipeSnapshot> recipes, int unsupported, int invalid, int failed,
                          List<String> failedRecipeDetails) {
            this(recipes, unsupported, invalid, failed, false, failedRecipeDetails);
        }

        /**
         * 兼容旧调用点的构造器：不携带枚举失败标志与失败明细。
         */
        public ScanResult(List<RecipeSnapshot> recipes, int unsupported, int invalid, int failed) {
            this(recipes, unsupported, invalid, failed, false, List.of());
        }

        /**
         * @return 已分类的配方总数；配方枚举失败时返回 0，调用方必须优先检查
         *         {@link #enumerationFailed()} 而不能把 0 当作“世界中没有配方”
         */
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
