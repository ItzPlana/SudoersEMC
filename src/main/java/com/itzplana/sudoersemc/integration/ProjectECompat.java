package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OwnedEntrySink;
import com.itzplana.sudoersemc.core.PriceSource;
import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import moze_intel.projecte.api.proxy.IEMCProxy;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * {@link EmcProvider} backed by ProjectE's public API.
 *
 * <p>This is the only class (together with {@link GeneratedEmcMapper}) allowed to reference
 * {@code moze_intel.projecte} types. Everything else must go through {@link EmcProvider} so the mod
 * still loads when ProjectE is absent.</p>
 *
 * <p>ProjectE's {@link IEMCProxy#INSTANCE} is initialized through a {@code ServiceLoader} and throws
 * when ProjectE is missing or failed to start. Every call therefore goes through a guarded path that
 * degrades the whole integration to "unavailable" after the first failure.</p>
 */
public final class ProjectECompat implements EmcProvider {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROJECTE_MODID = "projecte";

    private final OwnedEntrySink sink;
    private final ProjectEManualValues manualValues;
    private final AtomicBoolean proxyFailureLogged = new AtomicBoolean();

    private volatile boolean proxyBroken;

    public ProjectECompat(OwnedEntrySink sink, ProjectEManualValues manualValues) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.manualValues = Objects.requireNonNull(manualValues, "manualValues");
    }

    @Override
    public boolean isAvailable() {
        if (proxyBroken) {
            return false;
        }
        try {
            return ModList.get().isLoaded(PROJECTE_MODID);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean hasValue(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isAvailable()) {
            return false;
        }
        try {
            return IEMCProxy.INSTANCE.hasValue(stack);
        } catch (Throwable t) {
            degrade("IEMCProxy.hasValue(ItemStack)", t);
            return false;
        }
    }

    @Override
    public long getValue(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !isAvailable()) {
            return 0L;
        }
        try {
            return IEMCProxy.INSTANCE.getValue(stack);
        } catch (Throwable t) {
            degrade("IEMCProxy.getValue(ItemStack)", t);
            return 0L;
        }
    }

    @Override
    public boolean stageGeneratedValue(ItemKey key, long value, PriceSource source,
                                       @Nullable ResourceLocation recipeId) {
        if (key == null || source == null) {
            return false;
        }
        if (!isAvailable()) {
            return false;
        }
        if (value <= 0L) {
            return false;
        }
        // Protection over automation: never stage a value for something a human pinned in
        // config/ProjectE/custom_emc.json.
        if (manualValues.isManuallySet(key)) {
            LOGGER.debug("Refusing to stage generated EMC for {}: a manual ProjectE value exists", key);
            return false;
        }

        GeneratedEmcEntry entry = new GeneratedEmcEntry(
                key,
                value,
                source,
                recipeId,
                true,
                System.currentTimeMillis()
        );
        boolean accepted = sink.stage(entry);
        if (!accepted) {
            LOGGER.debug("Owned entry sink rejected generated EMC value for {}", key);
        }
        return accepted;
    }

    @Override
    public void requestRemap(@Nullable MinecraftServer server) {
        requestRemapAsync(server);
    }

    @Override
    @Nullable
    public CompletableFuture<?> requestRemapAsync(@Nullable MinecraftServer server) {
        if (server == null) {
            LOGGER.warn("Cannot request a ProjectE EMC remap: no server instance is available");
            return null;
        }
        if (!server.isSameThread()) {
            LOGGER.warn("Refusing to request a ProjectE EMC remap off the server thread; call requestRemap on the server thread");
            return null;
        }

        // Pick up administrator edits before the mapping pass reads them.
        manualValues.refreshIfChanged();

        try {
            CompletableFuture<Void> completion =
                    server.reloadResources(server.getPackRepository().getSelectedIds());
            LOGGER.info("Requested a ProjectE EMC remap through a server resource reload");
            return completion;
        } catch (Throwable t) {
            LOGGER.warn("Failed to request a ProjectE EMC remap", t);
            return CompletableFuture.failedFuture(t);
        }
    }

    private void degrade(String operation, Throwable cause) {
        proxyBroken = true;
        if (proxyFailureLogged.compareAndSet(false, true)) {
            LOGGER.warn("ProjectE EMC proxy failed during {}; disabling the ProjectE integration for this session",
                    operation, cause);
        }
    }
}
