package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumerates registered items and detects which ones already have EMC.
 *
 * <p>Runs on the server thread because it touches the item registry. The output is a list of
 * immutable {@link ItemSnapshot}s that the rest of the pipeline can process off-thread.</p>
 */
public final class ItemScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/scanner/ItemScanner");

    private final EmcQuery emcQuery;
    private final Set<String> ignoredNamespaces;
    private final Set<ResourceLocation> ignoredItems;

    /**
     * @param emcQuery          backend query used to detect existing values
     * @param ignoredNamespaces namespaces that must not be priced at all
     * @param ignoredItems      exact item ids that must not be priced
     */
    public ItemScanner(EmcQuery emcQuery, Set<String> ignoredNamespaces, Set<ResourceLocation> ignoredItems) {
        this.emcQuery = Objects.requireNonNull(emcQuery, "emcQuery");
        this.ignoredNamespaces = Set.copyOf(ignoredNamespaces == null ? Set.of() : ignoredNamespaces);
        this.ignoredItems = Set.copyOf(ignoredItems == null ? Set.of() : ignoredItems);
    }

    /**
     * Scans every registered item.
     *
     * @return one snapshot per non-air, non-ignored item, in registry order
     */
    public List<ItemSnapshot> scan() {
        List<ItemSnapshot> snapshots = new ArrayList<>();
        int skippedEmpty = 0;
        int skippedIgnored = 0;
        int skippedUnregistered = 0;

        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                skippedUnregistered++;
                continue;
            }
            if (isIgnored(id)) {
                skippedIgnored++;
                continue;
            }
            ItemStack stack = defaultStack(item);
            if (stack.isEmpty()) {
                skippedEmpty++;
                continue;
            }
            ItemKey key = ItemKey.of(id);
            boolean hasEmc;
            long value;
            try {
                hasEmc = emcQuery.hasValue(stack);
                value = hasEmc ? Math.max(0L, emcQuery.getValue(stack)) : 0L;
            } catch (RuntimeException e) {
                // A single broken backend query must not abort the whole scan.
                LOGGER.warn("EMC query failed for {}; treating it as unvalued", id, e);
                hasEmc = false;
                value = 0L;
            }
            List<String> tagPaths;
            Rarity rarity;
            try {
                tagPaths = stack.getTags()
                        .map(tag -> tag.location().toString())
                        .distinct()
                        .sorted()
                        .toList();
                rarity = stack.getRarity();
            } catch (RuntimeException e) {
                // Tag/rarity lookup is best-effort: a single item must not abort the whole scan.
                LOGGER.warn("读取物品 {} 的标签或稀有度失败，按空标签与 COMMON 处理", id, e);
                tagPaths = List.of();
                rarity = Rarity.COMMON;
            }
            snapshots.add(ItemSnapshot.of(key, hasEmc, value, tagPaths, rarity));
        }

        if (skippedEmpty > 0 || skippedIgnored > 0 || skippedUnregistered > 0) {
            LOGGER.debug("Item scan skipped: {} ignored, {} empty, {} unregistered ({} collected)",
                    skippedIgnored, skippedEmpty, skippedUnregistered, snapshots.size());
        }
        return List.copyOf(snapshots);
    }

    private boolean isIgnored(ResourceLocation id) {
        return ignoredItems.contains(id) || ignoredNamespaces.contains(id.getNamespace());
    }

    /**
     * Builds the canonical "no NBT" stack for an item. Damageable items are left at their default
     * state so the value applies to the plain item rather than a specific damage value.
     */
    private static ItemStack defaultStack(Item item) {
        return new ItemStack(item);
    }

    /**
     * Minimal EMC query used by the scanner, kept separate from the pipeline so tests can supply a
     * map-backed implementation.
     */
    public interface EmcQuery {

        boolean hasValue(ItemStack stack);

        long getValue(ItemStack stack);
    }
}
