package com.example.sudoersemc.integration;

import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.core.PriceSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Internal abstraction over the EMC backend. This is deliberately not a mirror of ProjectE's API:
 * implementations translate these operations to whatever the concrete backend supports.
 *
 * <p>Implementations must be safe to call when the backing mod is absent: {@link #isAvailable()}
 * returns {@code false} and the remaining methods behave as a no-op / empty query.</p>
 */
public interface EmcProvider {

    /**
     * @return {@code true} if the backing EMC system is present and initialized
     */
    boolean isAvailable();

    /**
     * @return {@code true} if the stack already has an EMC value from any source
     */
    boolean hasValue(ItemStack stack);

    /**
     * @return the current EMC value, or {@code 0} when the stack has none
     */
    long getValue(ItemStack stack);

    /**
     * Records a generated value as owned by SudoersEMC.
     *
     * <p>This only stages the value in the ownership store. The actual ProjectE write happens
     * during the next EMC remap, so callers must invoke {@link #requestRemap(MinecraftServer)} or
     * ask an administrator to run {@code /reload}.</p>
     *
     * @return {@code true} if the value was accepted, {@code false} if it was rejected because the
     *         item has a manual value or the backend is unavailable
     */
    boolean stageGeneratedValue(ItemKey key, long value, PriceSource source, @Nullable ResourceLocation recipeId);

    /**
     * Requests a backend EMC recalculation. Must be called on the server thread.
     */
    void requestRemap(MinecraftServer server);
}
