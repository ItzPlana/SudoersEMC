package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.junit.jupiter.api.Test;

/**
 * {@link ReflectiveGoetyRitualAccess} 的解析失败语义测试。
 *
 * <p>通过包级 {@link ReflectiveGoetyRitualAccess.ClassResolver} 接缝与静态解析入口，
 * 在没有 Goety 的纯 JVM 中覆盖类缺失、类链接失败、方法缺失与返回类型漂移。</p>
 */
class ReflectiveGoetyRitualAccessTest {

    @Test
    void missingClassMakesAccessUnavailable() {
        assertFalse(ReflectiveGoetyRitualAccess.resolveAccessors(name -> {
            throw new ClassNotFoundException(name);
        }).available());
    }

    @Test
    void linkageErrorDuringClassLoadMakesAccessUnavailable() {
        assertFalse(ReflectiveGoetyRitualAccess.resolveAccessors(name -> {
            throw new NoClassDefFoundError(name);
        }).available());
    }

    @Test
    void missingAccessorMakesAccessUnavailable() {
        assertFalse(ReflectiveGoetyRitualAccess.resolveAccessors(NoAccessors.class).available());
    }

    @Test
    void returnTypeDriftMakesAccessUnavailable() {
        assertFalse(ReflectiveGoetyRitualAccess.resolveAccessors(ReturnTypeDrift.class).available());
    }

    @Test
    void allFourAccessorsResolve() {
        assertTrue(ReflectiveGoetyRitualAccess.resolveAccessors(ValidRitual.class).available());
    }

    /**
     * 四个访问器全部缺失的配方类。
     */
    public static final class NoAccessors {
    }

    /**
     * {@code getIngredients} 返回类型漂移的配方类。
     */
    public static final class ReturnTypeDrift {

        public String getIngredients() {
            return "";
        }

        public Ingredient getActivationItem() {
            return Ingredient.EMPTY;
        }

        public ResourceLocation getRitualType() {
            return new ResourceLocation("goety", "craft");
        }

        public ItemStack getResultItem(RegistryAccess access) {
            return ItemStack.EMPTY;
        }
    }

    /**
     * 与 Goety 2.5.60.6 签名一致的配方类。
     */
    public static final class ValidRitual {

        public List<Ingredient> getIngredients() {
            return List.of();
        }

        public Ingredient getActivationItem() {
            return Ingredient.EMPTY;
        }

        public ResourceLocation getRitualType() {
            return new ResourceLocation("goety", "craft");
        }

        public ItemStack getResultItem(RegistryAccess access) {
            return ItemStack.EMPTY;
        }
    }
}
