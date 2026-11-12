package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
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
     * 查询完整 NBT 对应的精确 EMC 值，不回退到基础物品。
     *
     * <p>ProjectE 的 {@link #getValue(ItemStack)} 对带 NBT 的堆叠会先查精确映射，
     * 未命中时回退基础物品值并叠加 NBT 处理器。变体扫描需要区分“该 NBT 自身已有值”
     * 与“仅继承基础物品值”，因此提供该精确查询。默认实现退回 {@link #getValue(ItemStack)}，
     * 供没有精确 NBT 概念的后端使用。</p>
     *
     * @param stack 待查询堆叠
     * @return 精确 NBT 映射值；没有 NBT 专属映射时返回 {@code 0}
     */
    default long getExactValue(ItemStack stack) {
        return getValue(stack);
    }

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
