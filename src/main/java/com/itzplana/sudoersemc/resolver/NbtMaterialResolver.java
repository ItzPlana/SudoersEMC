package com.itzplana.sudoersemc.resolver;

import java.util.OptionalLong;
import net.minecraft.world.item.ItemStack;

/**
 * Price-resolution extension point for item stacks with NBT variants.
 *
 * <p>Mod-specific integrations can implement this interface and register via
 * {@link ResolverRegistry#register(NbtMaterialResolver)} to price a variant without modifying the core
 * pricing engine. A resolver must judge {@link #supports(ItemStack)} itself and return a non-empty
 * result only for stacks it recognises and can price.</p>
 *
 * <p>Contract: the EMC returned by {@link #resolve(ItemStack)} must be greater than 0; when it cannot
 * price, return {@link OptionalLong#empty()}, never 0 or a negative number. Implementations must not
 * modify the supplied stack.</p>
 *
 * <p>Threading: implementations typically access {@link ItemStack} and the item registry, so they should
 * only be called on the server thread; if a caller runs on another thread it must supply an immutable
 * snapshot that will not be concurrently modified.</p>
 */
public interface NbtMaterialResolver {

    /**
     * Whether this resolver recognises the stack.
     *
     * <p>Keep this lightweight: only check tag structure or mod markers, do not run a full recipe
     * calculation. An implementation may return {@code true} here and still have
     * {@link #resolve(ItemStack)} return empty.</p>
     *
     * @param stack the stack to check, may be {@code null}
     * @return whether the resolver is willing to try pricing this stack
     */
    boolean supports(ItemStack stack);

    /**
     * Computes the stack's EMC.
     *
     * <p>The registry calls this only when {@link #supports(ItemStack)} returned {@code true}. The return
     * value must be greater than 0; when the price cannot be determined, return empty.</p>
     *
     * @param stack the stack to price, may be {@code null}
     * @return EMC greater than 0, or empty
     */
    OptionalLong resolve(ItemStack stack);
}
