package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.solver.GoetyRitualEvaluator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过反射读取 Goety 仪式配方。
 *
 * <p>Goety 是可选依赖，本类不能在编译期链接任何 Goety 类型；所有访问点都委托给
 * {@link GoetyRitualAccess}，生产环境使用 {@link ReflectiveGoetyRitualAccess}，单元测试
 * 可以注入假实现。这样既保持无 Goety 环境可运行，也能在纯 JVM 中验证失败语义。</p>
 *
 * <p><strong>必需字段：</strong>{@code result}、{@code ritualType}、{@code ingredients} 与
 * {@code activationItem} 四者任一读取失败（访问器缺失、调用异常或必需值为 {@code null}）
 * 时，整条配方返回 {@code null}，由 {@link RecipeScanner} 计入 {@code unsupported}。
 * 这样宁可少覆盖一条配方，也不生成「缺少成本项」的错误定价。</p>
 *
 * <p>日志按失败点限流：同一个访问器在 JVM 生命周期内最多 warn 一次，后续重复出现只保留
 * 一条 debug；非 {@code goety:ritual} 类型是扫描热路径，完全不记录。</p>
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
     * @return 不读取任何字段的读取器
     */
    public static GoetyRitualReader unavailable() {
        return new GoetyRitualReader(GoetyRitualAccess.unavailable());
    }

    /**
     * @return Goety 已加载且四个访问器全部解析成功时为 {@code true}
     */
    public static boolean available() {
        return ReflectiveGoetyRitualAccess.INSTANCE.available();
    }

    /**
     * @return 绑定当前 Goety 版本的读取器
     */
    public static GoetyRitualReader create() {
        return new GoetyRitualReader(ReflectiveGoetyRitualAccess.INSTANCE);
    }

    /**
     * 包级测试钩子：注入假访问器，完全不触碰反射。
     *
     * @param access 字段访问实现
     * @return 使用指定访问实现的读取器
     */
    static GoetyRitualReader withAccess(GoetyRitualAccess access) {
        return new GoetyRitualReader(access);
    }

    /**
     * 包级测试钩子：返回读取器使用的限流日志助手。
     *
     * @return 静态限流日志助手
     */
    static OnceLogger testLogger() {
        return LOG;
    }

    /**
     * 描述一条 Goety 仪式配方。
     *
     * @param recipe 任意配方；只有类型为 {@code goety:ritual} 时才会读取字段
     * @param id     配方 id
     * @return 配方快照；非本类型、读取器不可用或必需字段失败时返回 {@code null}
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
        } catch (RuntimeException failure) {
            return null;
        }
        if (!RITUAL_ID.equals(typeId)) {
            // 热路径：每个非仪式配方都会走到这里，禁止逐条记录日志。
            return null;
        }
        return describeReadable(recipe, id);
    }

    /**
     * 包级测试钩子：跳过类型闸门，直接验证字段必需性与转换逻辑。
     *
     * @param recipe Goety 仪式配方对象
     * @param id     配方 id
     * @return 配方快照；任一必需字段失败时返回 {@code null}
     */
    @Nullable
    RecipeSnapshot describeReadable(Object recipe, ResourceLocation id) {
        if (recipe == null || id == null) {
            return null;
        }

        GoetyRitualAccess.FieldRead<List<Ingredient>> ingredients = access.ingredients(recipe);
        if (!ingredients.ok()) {
            return skipRequired("ingredients", ingredients);
        }
        GoetyRitualAccess.FieldRead<Ingredient> activation = access.activationItem(recipe);
        if (!activation.ok()) {
            return skipRequired("activationItem", activation);
        }
        GoetyRitualAccess.FieldRead<String> ritualType = access.ritualType(recipe);
        if (!ritualType.ok() || ritualType.value() == null || ritualType.value().isBlank()) {
            return skipRequired("ritualType", ritualType);
        }
        GoetyRitualAccess.FieldRead<ItemStack> result = access.result(recipe);
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
                // 暗黑祭坛与基座是催化剂，不属于被消耗的 inputs。
                true,
                Map.of(GoetyRitualEvaluator.ATTRIBUTE_RITUAL_TYPE, ritualType.value()));
    }

    /**
     * 必需字段读取失败时统一跳过整条配方，并对同一失败点限流记录。
     */
    @Nullable
    private static RecipeSnapshot skipRequired(String field,
                                               GoetyRitualAccess.FieldRead<?> read) {
        String message = "Goety 仪式字段 " + field + " 读取失败；跳过该配方";
        Throwable cause = read.failure();
        if (!LOG.warn("goety.read." + field, message, cause)) {
            // 同一个失败点已经 warn 过一次，后续重复只保留一条 debug。
            LOG.debug("goety.read." + field + ".repeat", message + "（重复出现）", cause);
        }
        return null;
    }

    /**
     * 材料转换不完整时跳过整条配方。哪怕只剩一个槽位读取失败，也不能用剩余材料定价。
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
     * 把材料列表转换为配方输入。
     *
     * <p>空槽位或 {@link Ingredient#EMPTY} 表示零成本，直接忽略；非空材料读取失败、
     * 返回 0 个候选或所有候选都未注册时返回不完整结果，由调用方跳过整条配方。</p>
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
     * 转换单个激活物品。
     *
     * <p>{@link Ingredient#EMPTY} 是合法零成本；{@code null} 或转换不完整则失败。</p>
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
     * 材料转换结果。
     *
     * @param inputs   已解析的输入槽位
     * @param complete 是否完整；{@code false} 时调用方必须跳过整条配方
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
