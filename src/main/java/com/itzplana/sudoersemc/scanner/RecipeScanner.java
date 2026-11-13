package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.resolver.VariantNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        RecipeScanSession session = beginSession(manager);
        while (!session.isDone()) {
            session.step(Integer.MAX_VALUE);
        }
        return session.result();
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
            return new ScanResult(List.copyOf(snapshots), unsupported, invalid, failed);
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
