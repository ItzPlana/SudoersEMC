package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.OwnedEntrySink;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Detects optional EMC backends without ever linking their classes.
 *
 * <p>Only this class decides whether {@link ProjectECompat} may be instantiated. Because that class
 * references {@code moze_intel.projecte} types, it is created strictly after
 * {@link ModList#isLoaded(String)} confirmed ProjectE is present, and only once an ownership sink
 * has been registered.</p>
 */
public final class OptionalIntegrationManager {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String PROJECTE_MODID = "projecte";

    /**
     * Shared manual-value view. Safe to load without ProjectE; reading is lazy and defensive.
     */
    private static final ProjectEManualValues MANUAL_VALUES = new ProjectEManualValues();

    private static volatile OwnedEntrySink sink;
    private static volatile EmcProvider provider;
    private static volatile boolean initialized;
    private static volatile boolean absentLogged;
    private static volatile boolean waitingForSinkLogged;

    private OptionalIntegrationManager() {
    }

    /**
     * Detects ProjectE. Call once from mod construction (or common setup); calling again is cheap
     * and idempotent. If ProjectE is absent the integration stays disabled for the whole session.
     */
    public static synchronized void init() {
        initialized = true;
        if (!isProjectEAvailable()) {
            provider = null;
            if (!absentLogged) {
                absentLogged = true;
                LOGGER.info("ProjectE not present, EMC generation disabled");
            }
            return;
        }
        if (provider != null) {
            return;
        }
        OwnedEntrySink currentSink = sink;
        if (currentSink == null) {
            if (!waitingForSinkLogged) {
                waitingForSinkLogged = true;
                LOGGER.info("ProjectE detected, but no ownership sink is registered yet; EMC generation stays disabled until setSink(...) is called");
            }
            return;
        }
        createProvider(currentSink);
    }

    /**
     * Registers the ownership store. When ProjectE is loaded this immediately enables the provider.
     */
    public static synchronized void setSink(@Nullable OwnedEntrySink ownedEntrySink) {
        if (ownedEntrySink == null) {
            LOGGER.warn("Ignoring null OwnedEntrySink; ProjectE integration state is unchanged");
            return;
        }
        sink = ownedEntrySink;
        if (provider != null) {
            return;
        }
        if (!isProjectEAvailable()) {
            if (!absentLogged) {
                absentLogged = true;
                LOGGER.info("ProjectE not present, EMC generation disabled");
            }
            return;
        }
        createProvider(ownedEntrySink);
    }

    private static void createProvider(OwnedEntrySink ownedEntrySink) {
        try {
            provider = new ProjectECompat(ownedEntrySink, MANUAL_VALUES);
            LOGGER.info("ProjectE detected, EMC generation enabled");
        } catch (Throwable t) {
            provider = null;
            LOGGER.warn("ProjectE is present but its EMC API could not be initialized; EMC generation disabled", t);
        }
    }

    /**
     * @return the active EMC backend, or empty when ProjectE is absent or no sink is registered
     */
    public static Optional<EmcProvider> provider() {
        if (!initialized) {
            init();
        }
        return Optional.ofNullable(provider);
    }

    public static boolean isProjectEAvailable() {
        try {
            return ModList.get().isLoaded(PROJECTE_MODID);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return the shared manual-value view to pass to
     *         {@link GeneratedEmcMapper#setManualValues(ProjectEManualValues)} when ProjectE is loaded
     */
    public static ProjectEManualValues manualValues() {
        return MANUAL_VALUES;
    }
}
