package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.solver.GoetyRitualEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads Goety ritual recipes via reflection.
 *
 * <p>Goety is an optional dependency, so this class must not link any Goety type at compile time; all
 * access points delegate to {@link GoetyRitualAccess}, using {@link ReflectiveGoetyRitualAccess} in
 * production and allowing a fake implementation in unit tests. This keeps a no-Goety environment
 * runnable and lets failure semantics be verified in a pure JVM.</p>
 *
 * <p><strong>Required fields:</strong> if any of {@code result}, {@code ritualType}, {@code ingredients}
 * or {@code activationItem} fails to read (accessor absent, call exception, or required value
 * {@code null}), the whole recipe returns {@code null} and is counted as {@code unsupported} by
 * {@link RecipeScanner}. Rather cover one fewer recipe than generate a wrong price "missing a cost
 * term".</p>
 *
 * <p>Logging is rate-limited per failure point: the same accessor warns at most once per JVM lifetime,
 * with subsequent repeats keeping only a debug; non-{@code goety:ritual} types are on the scan hot path
 * and are not logged at all.</p>
 *
 * <p>Both the type gate and field reads isolate {@link LinkageError} (e.g. {@link NoClassDefFoundError})
 * from third-party recipe class initialisation failures, ensuring a single corrupt recipe is only
 * skipped and never fails the whole scan.</p>
 */
public final class GoetyRitualReader {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/scanner/GoetyRitualReader");
    private static final OnceLogger LOG = new OnceLogger(LOGGER);

    private static final String GOETY_MODID = "goety";
    private static final ResourceLocation RITUAL_ID = new ResourceLocation(GOETY_MODID, "ritual");

    private final GoetyRitualAccess access;
    private final boolean available;

    private GoetyRitualReader(GoetyRitualAccess access) {
        this.access = Objects.requireNonNull(access, "access");
        this.available = access.available();
    }

    /**
     * @return a reader that reads no fields
     */
    public static GoetyRitualReader unavailable() {
        return new GoetyRitualReader(GoetyRitualAccess.unavailable());
    }

    /**
     * @return {@code true} when Goety is loaded and all four accessors resolved successfully
     */
    public static boolean available() {
        return ReflectiveGoetyRitualAccess.INSTANCE.available();
    }

    /**
     * @return a reader bound to the current Goety version
     */
    public static GoetyRitualReader create() {
        return new GoetyRitualReader(ReflectiveGoetyRitualAccess.INSTANCE);
    }

    /**
     * Package-level test hook: inject a fake accessor, touching no reflection at all.
     *
     * @param access the field-access implementation
     * @return a reader using the given access implementation
     */
    static GoetyRitualReader withAccess(GoetyRitualAccess access) {
        return new GoetyRitualReader(access);
    }

    /**
     * Package-level test hook: returns the rate-limited log helper used by the reader.
     *
     * @return the static rate-limited log helper
     */
    static OnceLogger testLogger() {
        return LOG;
    }

    /**
     * Describes one Goety ritual recipe.
     *
     * @param recipe any recipe; fields are read only when its type is {@code goety:ritual}
     * @param id     recipe id
     * @return recipe snapshot; {@code null} when not this type, the reader is unavailable, or a required field fails
     */
    @Nullable
    public RecipeSnapshot describe(Recipe<?> recipe, ResourceLocation id) {
        if (!available || recipe == null || id == null) {
            return null;
        }
        ResourceLocation typeId;
        try {
            typeId = recipe.getType() == null
                    ? null
                    : BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        } catch (RuntimeException | LinkageError failure) {
            return null;
        }
        if (!RITUAL_ID.equals(typeId)) {
            // Hot path: every non-ritual recipe reaches here; per-recipe logging is forbidden.
            return null;
        }
        return describeReadable(recipe, id);
    }

    /**
     * Package-level test hook: skips the type gate to directly verify field required-ness and conversion
     * logic.
     *
     * <p>A {@link RuntimeException} or {@link LinkageError} thrown by the accessor implementation itself
     * is also treated as a field-read failure, returning {@code null} for the whole recipe.</p>
     *
     * @param recipe the Goety ritual recipe object
     * @param id     recipe id
     * @return recipe snapshot; {@code null} when any required field fails
     */
    @Nullable
    RecipeSnapshot describeReadable(Object recipe, ResourceLocation id) {
        if (recipe == null || id == null) {
            return null;
        }

        GoetyRitualAccess.FieldRead<List<Ingredient>> ingredients =
                readField(() -> access.ingredients(recipe));
        if (!ingredients.ok()) {
            return skipRequired("ingredients", ingredients);
        }
        GoetyRitualAccess.FieldRead<Ingredient> activation =
                readField(() -> access.activationItem(recipe));
        if (!activation.ok()) {
            return skipRequired("activationItem", activation);
        }
        GoetyRitualAccess.FieldRead<String> ritualType =
                readField(() -> access.ritualType(recipe));
        if (!ritualType.ok() || ritualType.value() == null || ritualType.value().isBlank()) {
            return skipRequired("ritualType", ritualType);
        }
        GoetyRitualAccess.FieldRead<ItemStack> result =
                readField(() -> access.result(recipe));
        if (!result.ok()) {
            return skipRequired("result", result);
        }
        if (result.value() == null || result.value().isEmpty()) {
            LOG.debug("goety.result.empty",
                    "Goety 仪式 " + id + " 的结果为空栈；跳过该配方", null);
            return null;
        }

        Conversion ingredientsConversion = convert(ingredients.value());
        if (!ingredientsConversion.complete()) {
            return skipConversion("ingredients", id);
        }
        Conversion activationConversion = convertActivation(activation.value());
        if (!activationConversion.complete()) {
            return skipConversion("activationItem", id);
        }

        List<RecipeInput> inputs = new ArrayList<>(ingredientsConversion.inputs());
        inputs.addAll(activationConversion.inputs());
        if (inputs.isEmpty()) {
            LOG.debug("goety.inputs.empty",
                    "Goety 仪式 " + id + " 没有可用输入；跳过该配方", null);
            return null;
        }

        RecipeOutput output = RecipeScanner.convertStack(result.value());
        if (output == null) {
            LOG.debug("goety.result.unregistered",
                    "Goety 仪式 " + id + " 的结果物品未注册；跳过该配方", null);
            return null;
        }

        return new RecipeSnapshot(id, RecipeKind.GOETY_RITUAL, inputs, List.of(output),
                List.of(),
                // The dark altar and pedestals are catalysts, not part of the consumed inputs.
                true,
                Map.of(GoetyRitualEvaluator.ATTRIBUTE_RITUAL_TYPE, ritualType.value()));
    }

    /**
     * Exceptions from the accessor implementation itself are also treated as field-read failures.
     *
     * <p>The production implementation already catches {@link Throwable} from reflective calls; this adds
     * another layer of defence so that replacing {@link GoetyRitualAccess} in future cannot let a single
     * field exception pierce the whole scan.</p>
     */
    private static <T> GoetyRitualAccess.FieldRead<T> readField(
            Supplier<GoetyRitualAccess.FieldRead<T>> reader) {
        try {
            return reader.get();
        } catch (RuntimeException | LinkageError failure) {
            return GoetyRitualAccess.FieldRead.failed(failure);
        }
    }

    /**
     * On a required-field read failure, skips the whole recipe uniformly and rate-limits logging per
     * failure point.
     */
    @Nullable
    private static RecipeSnapshot skipRequired(String field,
                                               GoetyRitualAccess.FieldRead<?> read) {
        String message = "Goety 仪式字段 " + field + " 读取失败；跳过该配方";
        Throwable cause = read.failure();
        if (!LOG.warn("goety.read." + field, message, cause)) {
            // This failure point already warned once; subsequent repeats keep only a debug.
            LOG.debug("goety.read." + field + ".repeat", message + "（重复出现）", cause);
        }
        return null;
    }

    /**
     * Skips the whole recipe when ingredient conversion is incomplete. Even if only one slot failed to
     * read, the remaining ingredients must not be used to price.
     */
    @Nullable
    private static RecipeSnapshot skipConversion(String field, ResourceLocation id) {
        String message = "Goety 仪式 " + id + " 的 " + field + " 无法完整转换；跳过该配方";
        if (!LOG.warn("goety.convert." + field, message, null)) {
            LOG.debug("goety.convert." + field + ".repeat", message + "（重复出现）", null);
        }
        return null;
    }

    /**
     * Converts an ingredient list into recipe inputs.
     *
     * <p>An empty slot or {@link Ingredient#EMPTY} means zero cost and is ignored; a non-empty ingredient
     * that fails to read, returns 0 candidates, or whose candidates are all unregistered yields an
     * incomplete result, and the caller skips the whole recipe.</p>
     */
    private static Conversion convert(@Nullable List<Ingredient> ingredients) {
        if (ingredients == null) {
            return Conversion.failed();
        }
        List<RecipeInput> converted = new ArrayList<>();
        for (Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.isEmpty()) {
                LOG.debug("goety.ingredient.blank", "Goety 仪式包含空材料槽；已忽略", null);
                continue;
            }
            ItemStack[] stacks;
            try {
                stacks = ingredient.getItems();
            } catch (RuntimeException failure) {
                LOG.debug("goety.ingredient.failed",
                        "Goety 仪式材料转换失败；整条配方将跳过", failure);
                return Conversion.failed();
            }
            if (stacks == null || stacks.length == 0) {
                LOG.debug("goety.ingredient.empty",
                        "Goety 仪式材料没有任何候选；整条配方将跳过", null);
                return Conversion.failed();
            }
            List<ItemKey> candidates = new ArrayList<>(stacks.length);
            for (ItemStack stack : stacks) {
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (itemId == null) {
                    LOG.debug("goety.ingredient.candidate",
                            "Goety 仪式材料候选物品未注册；已丢弃该候选", null);
                    continue;
                }
                candidates.add(ItemKey.of(itemId));
            }
            if (candidates.isEmpty()) {
                LOG.debug("goety.ingredient.empty",
                        "Goety 仪式材料没有已注册候选；整条配方将跳过", null);
                return Conversion.failed();
            }
            converted.add(RecipeInput.of(new IngredientOption(candidates)));
        }
        return Conversion.ok(converted);
    }

    /**
     * Converts a single activation item.
     *
     * <p>{@link Ingredient#EMPTY} is a legitimate zero cost; {@code null} or incomplete conversion fails.</p>
     */
    private static Conversion convertActivation(@Nullable Ingredient activation) {
        if (activation == null) {
            return Conversion.failed();
        }
        if (activation.isEmpty()) {
            LOG.debug("goety.activation.empty",
                    "Goety 仪式没有消耗型激活物品；按合法零成本处理", null);
            return Conversion.ok(List.of());
        }
        return convert(List.of(activation));
    }

    /**
     * Ingredient conversion result.
     *
     * @param inputs   resolved input slots
     * @param complete whether complete; when {@code false} the caller must skip the whole recipe
     */
    private record Conversion(List<RecipeInput> inputs, boolean complete) {

        static Conversion ok(List<RecipeInput> inputs) {
            return new Conversion(List.copyOf(inputs), true);
        }

        static Conversion failed() {
            return new Conversion(List.of(), false);
        }
    }
}
