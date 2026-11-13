package com.itzplana.sudoersemc.scanner;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.jetbrains.annotations.Nullable;

/**
 * Field-access seam for Goety ritual recipes.
 *
 * <p>The production implementation {@link ReflectiveGoetyRitualAccess} reads the optional dependency
 * Goety's recipe objects via reflection; unit tests can inject a fake implementation to verify
 * required-field failure semantics in a JVM with no Goety at all.</p>
 *
 * <p>Each accessor returns a three-state {@link FieldRead} result to distinguish "accessor absent" from
 * "call failed". Both are treated fail-closed economically, but logging and troubleshooting differ.</p>
 */
interface GoetyRitualAccess {

    /**
     * @return whether all four accessors resolved successfully
     */
    boolean available();

    /**
     * Reads the ritual ingredient list.
     *
     * @param recipe the Goety ritual recipe object
     * @return three-state read result; the value may be {@code null}, the caller decides whether that is a failure
     */
    FieldRead<List<Ingredient>> ingredients(Object recipe);

    /**
     * Reads the activation item.
     *
     * @param recipe the Goety ritual recipe object
     * @return three-state read result; returns {@link Ingredient#EMPTY} for a legitimate zero cost
     */
    FieldRead<Ingredient> activationItem(Object recipe);

    /**
     * Reads the ritual type identifier, e.g. {@code goety:craft}.
     *
     * @param recipe the Goety ritual recipe object
     * @return three-state read result; the caller skips the whole recipe when missing or unconvertible
     */
    FieldRead<String> ritualType(Object recipe);

    /**
     * Reads the ritual result.
     *
     * @param recipe the Goety ritual recipe object
     * @return three-state read result; an empty stack is judged a data anomaly by the caller
     */
    FieldRead<ItemStack> result(Object recipe);

    /**
     * Result of a single field read.
     *
     * @param status  read status
     * @param value   the read value, meaningful only for {@link Status#OK}
     * @param failure the call-failure cause, non-null only for {@link Status#FAILED}
     * @param <T>     the field type
     */
    record FieldRead<T>(Status status, @Nullable T value, @Nullable Throwable failure) {

        /**
         * Read status.
         */
        enum Status {
            /** Accessor exists and the call succeeded; the value itself may be {@code null}. */
            OK,
            /** Accessor was absent at resolution time, usually meaning version or signature drift. */
            ABSENT,
            /** Accessor exists, but the call, type conversion or access check failed. */
            FAILED
        }

        /**
         * @param value the read value, may be {@code null}
         * @return a success result
         */
        static <T> FieldRead<T> ok(@Nullable T value) {
            return new FieldRead<>(Status.OK, value, null);
        }

        /**
         * @return an accessor-absent result
         */
        static <T> FieldRead<T> absent() {
            return new FieldRead<>(Status.ABSENT, null, null);
        }

        /**
         * @param cause the failure cause
         * @return a call-failure result
         */
        static <T> FieldRead<T> failed(Throwable cause) {
            return new FieldRead<>(Status.FAILED, null, cause);
        }

        /**
         * @return whether the read succeeded
         */
        boolean ok() {
            return status == Status.OK;
        }
    }

    /**
     * @return a disabled implementation that reads no fields
     */
    static GoetyRitualAccess unavailable() {
        return new GoetyRitualAccess() {

            @Override
            public boolean available() {
                return false;
            }

            @Override
            public FieldRead<List<Ingredient>> ingredients(Object recipe) {
                return FieldRead.absent();
            }

            @Override
            public FieldRead<Ingredient> activationItem(Object recipe) {
                return FieldRead.absent();
            }

            @Override
            public FieldRead<String> ritualType(Object recipe) {
                return FieldRead.absent();
            }

            @Override
            public FieldRead<ItemStack> result(Object recipe) {
                return FieldRead.absent();
            }
        };
    }
}
