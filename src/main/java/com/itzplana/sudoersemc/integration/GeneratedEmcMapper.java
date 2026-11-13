package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.GeneratedValueSource;
import com.itzplana.sudoersemc.core.ItemKey;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import moze_intel.projecte.api.mapper.EMCMapper;
import moze_intel.projecte.api.mapper.IEMCMapper;
import moze_intel.projecte.api.mapper.collector.IMappingCollector;
import moze_intel.projecte.api.nss.NSSItem;
import moze_intel.projecte.api.nss.NormalizedSimpleStack;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.ReloadableServerResources;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * ProjectE {@code @EMCMapper} that injects the values SudoersEMC generated.
 *
 * <p><strong>Loading contract:</strong> ProjectE's annotation scanner instantiates this class via
 * {@link #INSTANCE} while it builds its EMC graph. It must never be referenced directly from mod
 * code unless ProjectE is known to be loaded, because it implements a ProjectE interface and cannot
 * be class-loaded while ProjectE is absent. All other layers use {@link EmcProvider} instead.</p>
 *
 * <p>Priority {@code -1000} runs after ProjectE's built-in mappers (higher priority runs first), so
 * SudoersEMC's values win over automatic ProjectE mappings while manual values are still skipped by
 * the guard below.</p>
 */
@EMCMapper(priority = -1000)
public final class GeneratedEmcMapper implements IEMCMapper<NormalizedSimpleStack, Long> {

    /**
     * Discovered reflectively by ProjectE. Do not rename or make non-static.
     */
    @EMCMapper.Instance
    public static final GeneratedEmcMapper INSTANCE = new GeneratedEmcMapper();

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final AtomicBoolean VARIANT_WARNING_LOGGED = new AtomicBoolean();

    private static volatile GeneratedValueSource source;
    private static volatile ProjectEManualValues manualValues;

    private GeneratedEmcMapper() {
    }

    /**
     * Wires the ownership source. Must only be called when ProjectE is loaded.
     */
    public static void setSource(@Nullable GeneratedValueSource source) {
        GeneratedEmcMapper.source = source;
    }

    /**
     * Wires the manual-value guard. Must only be called when ProjectE is loaded.
     */
    public static void setManualValues(@Nullable ProjectEManualValues manualValues) {
        GeneratedEmcMapper.manualValues = manualValues;
    }

    @Override
    public String getName() {
        return "SudoersEmcGenerated";
    }

    @Override
    public String getDescription() {
        return "Applies EMC values generated and owned by SudoersEMC.";
    }

    @Override
    public boolean isAvailable() {
        return source != null && ModList.get().isLoaded("projecte");
    }

    @Override
    public void addMappings(IMappingCollector<NormalizedSimpleStack, Long> mapper,
                            CommentedFileConfig config,
                            ReloadableServerResources serverResources,
                            RegistryAccess registryAccess,
                            ResourceManager resourceManager) {
        try {
            if (mapper == null) {
                return;
            }
            GeneratedValueSource currentSource = source;
            if (currentSource == null) {
                return;
            }

            ProjectEManualValues currentManual = manualValues;
            if (currentManual != null) {
                currentManual.refreshIfChanged();
            }

            Map<ItemKey, Long> values = currentSource.snapshotOwnedValues();
            if (values == null || values.isEmpty()) {
                return;
            }

            int applied = 0;
            int manualSkips = 0;
            int invalidSkips = 0;

            for (Map.Entry<ItemKey, Long> entry : values.entrySet()) {
                ItemKey key = entry.getKey();
                Long value = entry.getValue();
                if (key == null || value == null || value <= 0L) {
                    invalidSkips++;
                    continue;
                }
                if (currentManual != null && currentManual.isManuallySetInSnapshot(key)) {
                    manualSkips++;
                    continue;
                }

                NormalizedSimpleStack nss;
                try {
                    nss = toSimpleStack(key);
                } catch (Throwable t) {
                    invalidSkips++;
                    if (VARIANT_WARNING_LOGGED.compareAndSet(false, true)) {
                        LOGGER.warn("Skipping generated EMC value for {}: the variant data is not valid SNBT", key, t);
                    }
                    continue;
                }
                if (nss == null) {
                    invalidSkips++;
                    continue;
                }

                mapper.setValueBefore(nss, value);
                applied++;
            }

            if (applied > 0 || manualSkips > 0 || invalidSkips > 0) {
                LOGGER.debug("SudoersEMC generated EMC mapping pass: {} applied, {} manual skips, {} invalid skips",
                        applied, manualSkips, invalidSkips);
            }
        } catch (Throwable t) {
            // Never break ProjectE's mapping pass, even if our data or another mod's item is broken.
            LOGGER.error("SudoersEMC failed to apply generated EMC values during ProjectE mapping; continuing without them", t);
        }
    }

    private static @Nullable NormalizedSimpleStack toSimpleStack(ItemKey key) throws Exception {
        CompoundTag nbt = null;
        if (key.hasVariant()) {
            nbt = TagParser.parseTag(key.variantHash());
        }
        return NSSItem.createItem(key.itemId(), nbt);
    }
}
