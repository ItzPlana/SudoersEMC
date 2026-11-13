package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.GeneratedValueSource;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.VariantRegistration;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.mojang.logging.LogUtils;
import java.util.List;
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
import net.minecraft.resources.ResourceLocation;
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
            Map<ItemKey, List<String>> variantNbts = currentSource.snapshotOwnedVariantNbts();

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

                if (!key.hasVariant()) {
                    if (registerValue(mapper, key.itemId(), null, value)) {
                        applied++;
                    } else {
                        invalidSkips++;
                    }
                    continue;
                }

                List<String> registrations = VariantRegistration.registrationNbts(
                        key, variantNbts == null ? List.of() : variantNbts.get(key));
                if (registrations.isEmpty()) {
                    invalidSkips++;
                    continue;
                }
                for (String snbt : registrations) {
                    // 手动配置的原始 NBT 变体也必须保持保护；规范 key 的检查在上方已完成。
                    if (currentManual != null
                            && currentManual.isManuallySetInSnapshot(ItemKey.of(key.itemId(), snbt))) {
                        manualSkips++;
                        continue;
                    }
                    if (registerValue(mapper, key.itemId(), snbt, value)) {
                        applied++;
                    } else {
                        invalidSkips++;
                    }
                }
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

    /**
     * 把一个 NBT key 注册到 ProjectE。
     *
     * <p>变体条目会同时注册规范 SNBT 与观测到的原始 SNBT：规范 key 用于经济身份与去重，
     * 原始 key 才能匹配带上展示字段的真实堆叠。单个坏 SNBT 只跳过该 key，不中断整轮映射。</p>
     *
     * @return {@code true} 表示注册成功
     */
    private static boolean registerValue(IMappingCollector<NormalizedSimpleStack, Long> mapper,
                                         ResourceLocation itemId,
                                         @Nullable String snbt, long value) {
        try {
            NormalizedSimpleStack nss = toSimpleStack(itemId, snbt);
            if (nss == null) {
                return false;
            }
            mapper.setValueBefore(nss, value);
            return true;
        } catch (Throwable t) {
            if (VARIANT_WARNING_LOGGED.compareAndSet(false, true)) {
                LOGGER.warn("Skipping generated EMC value for {} with NBT {}: the variant data is not valid SNBT",
                        itemId, snbt, t);
            }
            return false;
        }
    }

    /**
     * 将物品键转换为 ProjectE 的规范化堆叠表示。
     *
     * <p>变体哈希必须能还原真实堆叠的完整 NBT：ProjectE 的 {@code ItemInfo.equals}
     * 比较完整 NBT，{@code EMCMappingHandler.getStoredEmcValue} 是精确 map 查找，容器
     * 内容物的嵌套 {@code StoredEMC} 必须包含在变体键中，否则生成的映射永远不会命中。</p>
     *
     * <p>包级可见性仅用于端到端回归测试；生产调用方只应通过 {@code @EMCMapper} 入口。</p>
     */
    static @Nullable NormalizedSimpleStack toSimpleStack(ItemKey key) throws Exception {
        CompoundTag nbt = null;
        if (key.hasVariant()) {
            nbt = TagParser.parseTag(key.variantHash());
        }
        return NSSItem.createItem(key.itemId(), nbt);
    }

    private static @Nullable NormalizedSimpleStack toSimpleStack(
            ResourceLocation itemId, @Nullable String snbt) throws Exception {
        CompoundTag nbt = null;
        if (snbt != null) {
            nbt = TagParser.parseTag(snbt);
        }
        return NSSItem.createItem(itemId, nbt);
    }
}
