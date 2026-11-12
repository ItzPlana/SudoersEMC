package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.concurrent.CompletableFuture;
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

    /**
     * 请求一次后端 EMC 重映射，并返回“写入已生效”的完成信号。
     *
     * <p>默认实现退化为同步请求且无法报告完成（返回 {@code null}），调用方应把
     * {@code null} 视为“无法确认”，不要据此放宽外部修改检测。实现方若能提供资源重载的
     * {@link CompletableFuture}，应覆盖本方法，让调用方在重映射完成前跳过外部修改释放，
     * 避免把 SudoersEMC 自己刚写入、但后端尚未重映射的值误判为外部修改。</p>
     *
     * <p>Must be called on the server thread.</p>
     *
     * @param server 当前服务器，允许为 {@code null}
     * @return 重映射完成信号；无法提供完成信号时返回 {@code null}
     */
    @Nullable
    default CompletableFuture<?> requestRemapAsync(@Nullable MinecraftServer server) {
        requestRemap(server);
        return null;
    }
}
