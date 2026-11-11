package com.example.sudoersemc;

import com.example.sudoersemc.config.SudoersemcConfig;
import com.example.sudoersemc.diagnostic.SmokeTestHook;
import com.example.sudoersemc.integration.GeneratedEmcMapper;
import com.example.sudoersemc.integration.OptionalIntegrationManager;
import com.example.sudoersemc.storage.GeneratedEntryStore;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SudoersEMC entry point.
 *
 * <p>Phase 1 wiring: persistence, ProjectE integration detection, and the ProjectE
 * {@code @EMCMapper} that publishes owned values. Scanning and pricing engines are added in later
 * phases.</p>
 */
@Mod(Sudoersemc.MODID)
public final class Sudoersemc {

    public static final String MODID = "sudoersemc";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static volatile GeneratedEntryStore entryStore;

    public Sudoersemc() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();
        modEventBus.addListener(this::onCommonSetup);

        MinecraftForge.EVENT_BUS.addListener(this::onServerStarted);
        MinecraftForge.EVENT_BUS.addListener(this::onServerStopping);
        MinecraftForge.EVENT_BUS.addListener(SmokeTestHook::onServerTick);

        // Must be registered during mod construction, before configs are loaded.
        SudoersemcConfig.register();

        LOGGER.info("SudoersEMC is loading");
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            GeneratedEntryStore store = new GeneratedEntryStore();
            store.load();
            entryStore = store;

            // The sink must exist before the integration manager can create a provider.
            OptionalIntegrationManager.setSink(store);
            OptionalIntegrationManager.init();

            // GeneratedEmcMapper implements a ProjectE interface, so its class may only be
            // initialized when ProjectE is actually present. The guard is what keeps the mod
            // loadable on servers that do not have ProjectE installed.
            if (OptionalIntegrationManager.isProjectEAvailable()) {
                GeneratedEmcMapper.setSource(store);
                GeneratedEmcMapper.setManualValues(OptionalIntegrationManager.manualValues());
                LOGGER.info("SudoersEMC wired its generated-value mapper into ProjectE");
            }

            LOGGER.info("SudoersEMC common setup complete ({} generated entries loaded)", store.size());
        });
    }

    private void onServerStarted(ServerStartedEvent event) {
        GeneratedEntryStore store = entryStore;
        if (store != null) {
            LOGGER.info("SudoersEMC active: {} generated entries, ProjectE {}",
                    store.size(),
                    OptionalIntegrationManager.isProjectEAvailable() ? "detected" : "absent");
        }
        SmokeTestHook.onServerStarted(event);
    }

    private void onServerStopping(ServerStoppingEvent event) {
        GeneratedEntryStore store = entryStore;
        if (store != null) {
            store.saveIfDirty();
        }
    }

    /**
     * @return the live entry store, or {@code null} before common setup has completed
     */
    public static GeneratedEntryStore entryStore() {
        return entryStore;
    }
}
