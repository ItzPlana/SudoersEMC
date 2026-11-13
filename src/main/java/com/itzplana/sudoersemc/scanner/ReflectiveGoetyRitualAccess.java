package com.itzplana.sudoersemc.scanner;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通过反射读取 Goety 仪式配方的生产实现。
 *
 * <p>Goety 是可选依赖，因此本类不能在编译期引用任何 Goety 类型。四个访问器在首次使用时
 * 按名称解析并缓存；只有全部访问器都解析成功时 {@link #available()} 才返回 {@code true}，
 * 任何一项失败都会让读取器整体降级为不可用，从而避免「少算成本仍定价」。</p>
 *
 * <p>Goety 未安装属于正常路径，只记录一次 debug；Goety 已加载但类或方法缺失则说明集成
 * 损坏，记录一次 warn。</p>
 */
final class ReflectiveGoetyRitualAccess implements GoetyRitualAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/scanner/GoetyRitualAccess");
    private static final OnceLogger LOG = new OnceLogger(LOGGER);

    private static final String GOETY_MODID = "goety";
    private static final String RITUAL_RECIPE_CLASS =
            "com.Polarice3.Goety.common.crafting.RitualRecipe";
    private static final ClassResolver DEFAULT_CLASS_RESOLVER = Class::forName;

    /**
     * 类查找接缝；生产环境使用 {@link Class#forName(String)}，测试可注入自定义实现。
     */
    @FunctionalInterface
    interface ClassResolver {

        Class<?> resolve(String className) throws ClassNotFoundException;
    }

    /**
     * 生产环境共享实例；解析结果在 JVM 生命周期内缓存。
     */
    static final ReflectiveGoetyRitualAccess INSTANCE = new ReflectiveGoetyRitualAccess();

    private volatile Resolved resolved;

    private ReflectiveGoetyRitualAccess() {
    }

    @Override
    public boolean available() {
        return resolved().available();
    }

    @Override
    public FieldRead<List<Ingredient>> ingredients(Object recipe) {
        Method method = resolved().ingredients();
        if (method == null) {
            return FieldRead.absent();
        }
        Object value;
        try {
            value = method.invoke(recipe);
        } catch (Throwable failure) {
            return FieldRead.failed(unwrap(failure));
        }
        if (value == null) {
            return FieldRead.ok(null);
        }
        if (!(value instanceof List<?> list)) {
            return FieldRead.failed(new ClassCastException(
                    "getIngredients returned " + value.getClass().getName()));
        }
        List<Ingredient> typed = new ArrayList<>(list.size());
        for (Object element : list) {
            if (element != null && !(element instanceof Ingredient)) {
                return FieldRead.failed(new ClassCastException(
                        "getIngredients contained " + element.getClass().getName()));
            }
            typed.add((Ingredient) element);
        }
        return FieldRead.ok(typed);
    }

    @Override
    public FieldRead<Ingredient> activationItem(Object recipe) {
        Method method = resolved().activationItem();
        if (method == null) {
            return FieldRead.absent();
        }
        Object value;
        try {
            value = method.invoke(recipe);
        } catch (Throwable failure) {
            return FieldRead.failed(unwrap(failure));
        }
        if (value == null) {
            return FieldRead.ok(null);
        }
        if (value instanceof Ingredient ingredient) {
            return FieldRead.ok(ingredient);
        }
        // 防御性适配：若未来版本把激活物品改为直接返回 ItemStack，仍能给出正确成本。
        if (value instanceof ItemStack stack) {
            return FieldRead.ok(stack.isEmpty() ? Ingredient.EMPTY : Ingredient.of(stack));
        }
        return FieldRead.failed(new ClassCastException(
                "getActivationItem returned " + value.getClass().getName()));
    }

    @Override
    public FieldRead<String> ritualType(Object recipe) {
        Method method = resolved().ritualType();
        if (method == null) {
            return FieldRead.absent();
        }
        Object value;
        try {
            value = method.invoke(recipe);
        } catch (Throwable failure) {
            return FieldRead.failed(unwrap(failure));
        }
        if (value == null) {
            return FieldRead.ok(null);
        }
        if (value instanceof ResourceLocation || value instanceof CharSequence || value instanceof Enum<?>) {
            return FieldRead.ok(value.toString());
        }
        return FieldRead.failed(new ClassCastException(
                "getRitualType returned " + value.getClass().getName()));
    }

    @Override
    public FieldRead<ItemStack> result(Object recipe) {
        Method method = resolved().result();
        if (method == null) {
            return FieldRead.absent();
        }
        Object value;
        try {
            // 1.20.1 中 getResultItem(RegistryAccess) 只返回预构造的堆叠，传 null 是安全的。
            value = method.invoke(recipe, (Object) null);
        } catch (Throwable failure) {
            return FieldRead.failed(unwrap(failure));
        }
        if (value == null) {
            return FieldRead.ok(null);
        }
        if (value instanceof ItemStack stack) {
            return FieldRead.ok(stack);
        }
        return FieldRead.failed(new ClassCastException(
                "getResultItem returned " + value.getClass().getName()));
    }

    private Resolved resolved() {
        Resolved current = resolved;
        if (current == null) {
            synchronized (this) {
                current = resolved;
                if (current == null) {
                    current = resolveAccessors();
                    resolved = current;
                }
            }
        }
        return current;
    }

    /**
     * 解析 Goety 类与四个访问器。只有全部成功时才标记为可用。
     */
    private Resolved resolveAccessors() {
        boolean loaded;
        try {
            ModList modList = ModList.get();
            loaded = modList != null && modList.isLoaded(GOETY_MODID);
        } catch (Throwable failure) {
            LOG.debug("goety.absent",
                    "Forge 模块列表不可用，按未安装 Goety 处理；仪式配方将计为不支持", failure);
            return Resolved.unavailable();
        }
        if (!loaded) {
            LOG.debug("goety.absent",
                    "未检测到 Goety；仪式配方将计为不支持", null);
            return Resolved.unavailable();
        }
        return resolveAccessors(DEFAULT_CLASS_RESOLVER);
    }

    /**
     * 包级测试钩子：跳过 Forge 模块探测，用指定类查找器解析 Goety 配方类与访问器。
     *
     * <p>生产环境由 {@link #resolveAccessors()} 先确认 Goety 已加载；该入口用于覆盖
     * 类缺失、类初始化失败与方法签名漂移等边界。</p>
     */
    static Resolved resolveAccessors(ClassResolver classResolver) {
        Class<?> ritualClass;
        try {
            ritualClass = classResolver.resolve(RITUAL_RECIPE_CLASS);
        } catch (ClassNotFoundException | RuntimeException | LinkageError failure) {
            LOG.warn("goety.class",
                    "Goety 已加载但缺少 " + RITUAL_RECIPE_CLASS + "；所有仪式配方将被跳过", failure);
            return Resolved.unavailable();
        }
        return resolveAccessors(ritualClass);
    }

    /**
     * 解析指定配方类的四个访问器；任一解析失败都整体降级为不可用。
     *
     * <p>{@link RuntimeException} 与 {@link LinkageError}（例如方法签名引用的可选类型缺失）
     * 都只让 Goety 集成降级，不能穿透到整轮扫描。</p>
     */
    static Resolved resolveAccessors(Class<?> ritualClass) {
        Objects.requireNonNull(ritualClass, "ritualClass");
        Method ingredients;
        Method activation;
        Method ritualType;
        Method result;
        try {
            ingredients = findAccessor(ritualClass, "getIngredients", "ingredients",
                    new Class<?>[0]);
            activation = findAccessor(ritualClass, "getActivationItem", "activationItem",
                    new Class<?>[0]);
            ritualType = findAccessor(ritualClass, "getRitualType", "ritualType",
                    new Class<?>[0]);
            result = findAccessor(ritualClass, "getResultItem", "result",
                    new Class<?>[]{RegistryAccess.class});
        } catch (RuntimeException | LinkageError failure) {
            LOG.warn("goety.access.resolve",
                    "Goety 仪式访问器解析失败；所有仪式配方将被跳过", failure);
            return Resolved.unavailable();
        }
        if (ingredients == null || activation == null || ritualType == null || result == null) {
            return Resolved.unavailable();
        }
        LOG.debug("goety.enabled", "Goety 仪式读取器已启用（4/4 访问器）", null);
        return new Resolved(true, ingredients, activation, ritualType, result);
    }

    /**
     * 解析单个访问器。
     *
     * <p>先尝试 {@link Class#getMethod(String, Class[])}（public 方法），失败后沿类层次查找
     * 声明方法并尝试 {@code setAccessible(true)}。返回值类型不符合预期时按不可用处理。</p>
     */
    @Nullable
    private static Method findAccessor(Class<?> owner, String name, String field,
                                       Class<?>[] parameterTypes) {
        Method method;
        try {
            method = owner.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException notPublic) {
            try {
                method = findDeclaredMethod(owner, name, parameterTypes);
            } catch (RuntimeException failure) {
                LOG.warn("goety.access." + field,
                        "Goety 仪式访问器 " + name + " 无法访问；相关配方将被跳过", failure);
                return null;
            }
        } catch (RuntimeException failure) {
            LOG.warn("goety.access." + field,
                    "Goety 仪式访问器 " + name + " 解析失败；相关配方将被跳过", failure);
            return null;
        }

        if (method == null) {
            LOG.warn("goety.method." + field,
                    "Goety 仪式访问器 " + name + " 缺失（签名可能已变化）；相关配方将被跳过", null);
            return null;
        }
        try {
            method.setAccessible(true);
        } catch (RuntimeException failure) {
            LOG.warn("goety.access." + field,
                    "Goety 仪式访问器 " + name + " 无法设置为可访问；相关配方将被跳过", failure);
            return null;
        }
        if (!compatibleReturnType(field, method.getReturnType())) {
            LOG.warn("goety.access." + field,
                    "Goety 仪式访问器 " + name + " 返回类型不兼容："
                            + method.getReturnType().getName() + "；相关配方将被跳过", null);
            return null;
        }
        return method;
    }

    @Nullable
    private static Method findDeclaredMethod(Class<?> owner, String name, Class<?>[] parameterTypes) {
        Class<?> current = owner;
        while (current != null) {
            try {
                return current.getDeclaredMethod(name, parameterTypes);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    /**
     * 访问器返回值类型必须与业务层期望一致，否则视为签名漂移。
     */
    private static boolean compatibleReturnType(String field, Class<?> returnType) {
        return switch (field) {
            case "ingredients" -> List.class.isAssignableFrom(returnType);
            case "activationItem" -> Ingredient.class.isAssignableFrom(returnType)
                    || ItemStack.class.isAssignableFrom(returnType);
            case "ritualType" -> ResourceLocation.class.isAssignableFrom(returnType)
                    || CharSequence.class.isAssignableFrom(returnType)
                    || Enum.class.isAssignableFrom(returnType)
                    || returnType == Object.class;
            case "result" -> ItemStack.class.isAssignableFrom(returnType);
            default -> false;
        };
    }

    /**
     * 去掉反射包装，保留真正的业务异常用于日志。
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException wrapper && wrapper.getCause() != null) {
            current = wrapper.getCause();
        }
        return current;
    }

    /**
     * 反射解析结果；不可用时四个方法均为 {@code null}。
     */
    record Resolved(boolean available,
                    @Nullable Method ingredients,
                    @Nullable Method activationItem,
                    @Nullable Method ritualType,
                    @Nullable Method result) {

        static Resolved unavailable() {
            return new Resolved(false, null, null, null, null);
        }
    }
}
