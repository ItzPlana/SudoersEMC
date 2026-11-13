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
 * Production implementation that reads Goety ritual recipes via reflection.
 *
 * <p>Goety is an optional dependency, so this class must not reference any Goety type at compile time.
 * The four accessors are resolved by name on first use and cached; {@link #available()} returns
 * {@code true} only when all accessors resolved successfully — any single failure degrades the whole
 * reader to unavailable, avoiding "pricing while under-counting cost".</p>
 *
 * <p>Goety not installed is a normal path and logs a single debug; Goety loaded but a class or method
 * missing means a broken integration and logs a single warn.</p>
 */
final class ReflectiveGoetyRitualAccess implements GoetyRitualAccess {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/scanner/GoetyRitualAccess");
    private static final OnceLogger LOG = new OnceLogger(LOGGER);

    private static final String GOETY_MODID = "goety";
    private static final String RITUAL_RECIPE_CLASS =
            "com.Polarice3.Goety.common.crafting.RitualRecipe";
    private static final ClassResolver DEFAULT_CLASS_RESOLVER = Class::forName;

    /**
     * Class-lookup seam; production uses {@link Class#forName(String)}, tests can inject a custom
     * implementation.
     */
    @FunctionalInterface
    interface ClassResolver {

        Class<?> resolve(String className) throws ClassNotFoundException;
    }

    /**
     * Shared production instance; resolution results are cached for the JVM lifetime.
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
        // Defensive adaptation: if a future version changes the activation item to return ItemStack directly, the correct cost is still produced.
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
            // In 1.20.1 getResultItem(RegistryAccess) only returns a pre-built stack, so passing null is safe.
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
     * Resolves the Goety class and the four accessors. Marked available only when all succeed.
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
     * Package-level test hook: skips Forge mod probing and resolves the Goety recipe class and accessors
     * with the given class resolver.
     *
     * <p>In production {@link #resolveAccessors()} first confirms Goety is loaded; this entry point covers
     * boundaries such as missing class, class-initialisation failure and method-signature drift.</p>
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
     * Resolves the four accessors of the given recipe class; any resolution failure degrades the whole to
     * unavailable.
     *
     * <p>{@link RuntimeException} and {@link LinkageError} (e.g. an optional type referenced by a method
     * signature is missing) only degrade the Goety integration and must not pierce the whole scan.</p>
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
     * Resolves a single accessor.
     *
     * <p>First tries {@link Class#getMethod(String, Class[])} (public method); on failure walks the class
     * hierarchy for a declared method and tries {@code setAccessible(true)}. A return type that does not
     * match expectations is treated as unavailable.</p>
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
     * The accessor return type must match what the business layer expects, otherwise it is treated as
     * signature drift.
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
     * Strips reflection wrappers, keeping the real business exception for logging.
     */
    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException wrapper && wrapper.getCause() != null) {
            current = wrapper.getCause();
        }
        return current;
    }

    /**
     * Reflection resolution result; when unavailable all four methods are {@code null}.
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
