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
 * Resolution-failure semantics tests for {@link ReflectiveGoetyRitualAccess}.
 *
 * <p>Via the package-level {@link ReflectiveGoetyRitualAccess.ClassResolver} seam and the static resolution entry point,
 * covers missing class, class linkage failure, missing method and return-type drift in a pure JVM without Goety.</p>
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
     * Recipe class with all four accessors missing.
     */
    public static final class NoAccessors {
    }

    /**
     * Recipe class whose {@code getIngredients} return type has drifted.
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
     * Recipe class matching the Goety 2.5.60.6 signatures.
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
