package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        Objects.requireNonNull(manager, "manager");
        List<RecipeSnapshot> snapshots = new ArrayList<>();
        int unsupported = 0;
        int invalid = 0;
        int failed = 0;

        // getRecipeIds()/byKey() is the only 1.20.1 API that pairs an id with its recipe without
        // touching private state.
        List<ResourceLocation> ids;
        try {
            ids = manager.getRecipeIds().toList();
        } catch (RuntimeException e) {
            LOGGER.warn("Could not enumerate recipe ids", e);
            return new ScanResult(List.of(), 0, 0, 0);
        }

        for (ResourceLocation id : ids) {
            Optional<? extends Recipe<?>> maybeRecipe;
            try {
                maybeRecipe = manager.byKey(id);
            } catch (RuntimeException e) {
                failed++;
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
                LOGGER.debug("Failed to describe recipe {}; skipping it", id, e);
            }
        }

        if (unsupported > 0 || invalid > 0 || failed > 0) {
            LOGGER.debug("Recipe scan: {} usable, {} unsupported, {} invalid, {} failed",
                    snapshots.size(), unsupported, invalid, failed);
        }
        return new ScanResult(List.copyOf(snapshots), unsupported, invalid, failed);
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
