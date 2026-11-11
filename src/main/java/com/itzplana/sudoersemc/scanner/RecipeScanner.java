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
    private RecipeSnapshot describe(Recipe<?> recipe, ResourceLocation id) {
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
        RecipeOutput output = convertStack(result);
        if (output == null) {
            return null;
        }
        return RecipeSnapshot.of(id, kind, inputs, List.of(output));
    }

    private RecipeSnapshot describeCrafting(CraftingRecipe recipe, ResourceLocation id) {
        RecipeKind kind = recipe.isSpecial() ? RecipeKind.UNSUPPORTED : RecipeKind.SHAPED;
        if (kind == RecipeKind.UNSUPPORTED) {
            // Special crafting recipes (dye blending, map cloning, ...) do not follow the generic
            // cost rule and are intentionally excluded from automatic pricing.
            return null;
        }
        if (!recipe.canCraftInDimensions(3, 3)) {
            return null;
        }
        List<RecipeInput> inputs = convertIngredients(recipe.getIngredients());
        if (inputs.isEmpty()) {
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
        // 容器余量（桶、空瓶等）需要真实的 CraftingContainer 才能计算。扫描阶段没有可用容器，
        // 因此这里显式降级为「无余量」：配方仍然参与定价，只是不抵扣返还物。
        // 绝不因为拿不到余量就把整条配方标记为失败——那会丢失大量可定价配方。
        return new RecipeSnapshot(id, kind, inputs, List.of(output), List.of(),
                false, java.util.Map.of());
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

    private static List<RecipeOutput> convertStacks(@Nullable NonNullList<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) {
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
