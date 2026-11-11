package com.example.sudoersemc.diagnostic;

import com.example.sudoersemc.Sudoersemc;
import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.core.PriceSource;
import com.example.sudoersemc.integration.EmcProvider;
import com.example.sudoersemc.integration.OptionalIntegrationManager;
import com.mojang.logging.LogUtils;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import org.slf4j.Logger;

/**
 * Temporary Phase 1 end-to-end probe.
 *
 * <p>Disabled by default. Run the dedicated server with {@code -Dsudoersemc.smokeTest=true} to
 * stage one generated value, force a ProjectE remap, and log whether the value became queryable
 * through {@code IEMCProxy}. This exists so the ProjectE write path can be verified without
 * shipping test-only commands in production code.</p>
 */
public final class SmokeTestHook {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MARKER = "sudoersemc.smokeTest";
    private static final String QUERY_MARKER = "sudoersemc.smokeQuery";
    private static final ResourceLocation TEST_ITEM = new ResourceLocation("minecraft", "bedrock");
    private static final long TEST_VALUE = 12_345L;
    private static final int CHECK_DELAY_TICKS = 60;

    private static boolean enabled;
    private static boolean staged;
    private static int ticks;
    private static boolean logged;

    public static void onServerStarted(ServerStartedEvent event) {
        boolean writeMode = System.getProperty(MARKER, "false").equalsIgnoreCase("true");
        boolean queryMode = System.getProperty(QUERY_MARKER, "false").equalsIgnoreCase("true");
        if (!writeMode && !queryMode) {
            return;
        }
        enabled = true;
        Optional<EmcProvider> provider = OptionalIntegrationManager.provider();
        if (provider.isEmpty()) {
            LOGGER.warn("[smoke] ProjectE provider is unavailable; skipping the EMC probe");
            return;
        }
        EmcProvider emc = provider.get();
        ItemStack probeStack = new ItemStack(Items.BEDROCK);
        long before = emc.getValue(probeStack);
        LOGGER.info("[smoke] before: minecraft:bedrock hasValue={} emc={}",
                emc.hasValue(probeStack), before);
        if (queryMode) {
            LOGGER.info("[smoke] query-only mode: expected {} => {}",
                    TEST_VALUE,
                    before == TEST_VALUE ? "PERSISTED VALUE APPLIED" : "PERSISTED VALUE MISSING");
            logged = true;
            return;
        }

        boolean accepted = emc.stageGeneratedValue(
                ItemKey.of(TEST_ITEM), TEST_VALUE, PriceSource.FALLBACK, null);
        LOGGER.info("[smoke] stageGeneratedValue accepted={}", accepted);
        staged = accepted;
    }

    private static void runRemap(MinecraftServer server) {
        Optional<EmcProvider> provider = OptionalIntegrationManager.provider();
        provider.ifPresent(emc -> {
            LOGGER.info("[smoke] requesting ProjectE remap");
            emc.requestRemap(server);
        });
    }

    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (!enabled || !staged || logged) {
            return;
        }
        ticks++;
        if (ticks == 20) {
            runRemap(event.getServer());
        }
        if (ticks >= CHECK_DELAY_TICKS) {
            logged = true;
            Optional<EmcProvider> provider = OptionalIntegrationManager.provider();
            if (provider.isEmpty()) {
                LOGGER.warn("[smoke] provider disappeared before the probe could be checked");
                return;
            }
            ItemStack probeStack = new ItemStack(Items.BEDROCK);
            EmcProvider emc = provider.get();
            long after = emc.getValue(probeStack);
            boolean has = emc.hasValue(probeStack);
            LOGGER.info("[smoke] after: minecraft:bedrock hasValue={} emc={} (expected {}) => {}",
                    has, after, TEST_VALUE,
                    after == TEST_VALUE ? "WRITE VERIFIED" : "WRITE FAILED");
            GeneratedEntryStoreAccess.save();
            LOGGER.info("[smoke] probe finished");
        }
    }

    /** Small indirection so the probe can persist its staged entry. */
    private static final class GeneratedEntryStoreAccess {

        private GeneratedEntryStoreAccess() {
        }

        static void save() {
            var store = Sudoersemc.entryStore();
            if (store != null) {
                store.saveIfDirty();
            }
        }
    }
}
