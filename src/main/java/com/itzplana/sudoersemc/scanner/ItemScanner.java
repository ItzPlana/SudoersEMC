package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
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
 *
 * <p>The production pipeline uses {@link #beginSession()} to obtain an {@link ItemScanSession} that
 * advances across ticks, processing only a limited number of registry entries per tick and checking a
 * deadline; {@link #beginScan()} keeps the phase-12 {@link ScanSession} view, and {@link #scan()} is
 * kept for tests and one-shot calls.</p>
 *
 * <p>Each snapshot carries both tag paths and rarity for {@code TagPricing} anchors and fallback
 * pricing; a failure to read tags or rarity is treated as empty tags and {@link Rarity#COMMON}, and a
 * single item failure never terminates the whole scan.</p>
 */
public final class ItemScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/scanner/ItemScanner");

    private final EmcQuery emcQuery;
    private final Set<String> ignoredNamespaces;
    private final Set<ResourceLocation> ignoredItems;
    private final MetadataReader metadataReader;

    /**
     * @param emcQuery          backend query used to detect existing values
     * @param ignoredNamespaces namespaces that must not be priced at all
     * @param ignoredItems      exact item ids that must not be priced
     */
    public ItemScanner(EmcQuery emcQuery, Set<String> ignoredNamespaces, Set<ResourceLocation> ignoredItems) {
        this(emcQuery, ignoredNamespaces, ignoredItems, ItemScanner::readMetadata);
    }

    /**
     * Constructor injectable by tests/integration.
     *
     * @param metadataReader tag and rarity reader, must not be {@code null}
     */
    ItemScanner(EmcQuery emcQuery, Set<String> ignoredNamespaces, Set<ResourceLocation> ignoredItems,
                MetadataReader metadataReader) {
        this.emcQuery = Objects.requireNonNull(emcQuery, "emcQuery");
        this.ignoredNamespaces = Set.copyOf(ignoredNamespaces == null ? Set.of() : ignoredNamespaces);
        this.ignoredItems = Set.copyOf(ignoredItems == null ? Set.of() : ignoredItems);
        this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
    }

    /** How many registry entries are scanned between cancellation checks. */
    public static final int CANCELLATION_CHECK_INTERVAL = 256;

    /**
     * Creates a sharded scan session (phase-12 view).
     *
     * @return a new scan session
     */
    public ScanSession beginScan() {
        return new ScanSession(beginSession());
    }

    /**
     * Begins an item scan that can advance in shards (phase-11 production wiring).
     *
     * <p>At construction the session only fixes the registry total and iterator and performs no
     * per-item query; each {@link ItemScanSession#step(int)} checks at most {@code maxItems} registry
     * entries. The caller ({@code ScanManager}) can therefore spread a large modpack's item scan across
     * multiple server ticks.</p>
     *
     * @return a new item scan cursor
     */
    public ItemScanSession beginSession() {
        return new ItemScanSession(emcQuery, ignoredNamespaces, ignoredItems, metadataReader);
    }

    /**
     * Scans every registered item in one call. Kept for tests and callers that do not need
     * cross-tick chunking; {@code ScanCollectionPipeline} uses {@link #beginSession()} instead.
     *
     * @return one snapshot per non-air, non-ignored item, in registry order
     */
    public List<ItemSnapshot> scan() {
        ItemScanSession session = beginSession();
        while (!session.isDone()) {
            session.step(Integer.MAX_VALUE);
        }
        return session.snapshots();
    }

    /**
     * Scans registered items with a cooperative cancellation checkpoint.
     *
     * <p>Checks the cancellation signal every {@value #CANCELLATION_CHECK_INTERVAL} registry entries. On
     * cancellation returns the partial snapshots produced so far; the list is still immutable and in
     * registry order, so the caller can safely use it for a partial diagnostic report.</p>
     *
     * @param cancellation cancellation check; stop scanning when it returns {@code true}
     * @return one snapshot per processed non-air, non-ignored item, in registry order
     */
    public List<ItemSnapshot> scan(BooleanSupplier cancellation) {
        Batch batch = scanBatch(orderedItemIds(), cancellation);
        if (batch.skippedEmpty() > 0 || batch.skippedIgnored() > 0
                || batch.skippedUnregistered() > 0) {
            LOGGER.debug("Item scan skipped: {} ignored, {} empty, {} unregistered ({} collected)",
                    batch.skippedIgnored(), batch.skippedEmpty(), batch.skippedUnregistered(),
                    batch.snapshots().size());
        }
        return batch.snapshots();
    }

    /**
     * Returns registered, non-air item ids sorted in {@link ResourceLocation} natural order.
     *
     * <p>Paged scanning must use a stable order, otherwise the cross-tick cursor drifts when registry
     * order changes. The order is frozen once at scan start, and each subsequent batch only advances
     * along that list.</p>
     *
     * @return immutable, sorted item id list
     */
    public List<ResourceLocation> orderedItemIds() {
        List<ResourceLocation> ids = new ArrayList<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id != null) {
                ids.add(id);
            }
        }
        ids.sort(ResourceLocation::compareTo);
        return List.copyOf(ids);
    }

    /**
     * Scans one page of already-sorted item ids.
     *
     * <p>This method does not modify scanner state; the caller maintains the cursor. Checks the
     * cancellation signal every {@value #CANCELLATION_CHECK_INTERVAL} entries; on cancellation returns
     * the partial result already processed in this page.</p>
     *
     * @param orderedIds   item id list in stable order
     * @param cancellation cancellation check; stop this page when it returns {@code true}
     * @return this page's snapshots and skip counts
     */
    public Batch scanBatch(List<ResourceLocation> orderedIds, BooleanSupplier cancellation) {
        Objects.requireNonNull(orderedIds, "orderedIds");
        Objects.requireNonNull(cancellation, "cancellation");
        List<ItemSnapshot> snapshots = new ArrayList<>();
        int skippedEmpty = 0;
        int skippedIgnored = 0;
        int skippedUnregistered = 0;
        int processed = 0;

        for (ResourceLocation id : orderedIds) {
            if (processed % CANCELLATION_CHECK_INTERVAL == 0 && cancellation.getAsBoolean()) {
                break;
            }
            processed++;
            if (id == null) {
                skippedUnregistered++;
                continue;
            }
            if (isIgnored(ignoredNamespaces, ignoredItems, id)) {
                skippedIgnored++;
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(id);
            if (item == null || item == Items.AIR) {
                skippedUnregistered++;
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
                ItemMetadata metadata = metadataReader.read(stack);
                tagPaths = metadata.tagPaths();
                rarity = metadata.rarity();
            } catch (RuntimeException e) {
                // Tag/rarity lookup is best-effort: a single item must not abort the whole scan.
                LOGGER.warn("读取物品 {} 的标签或稀有度失败，按空标签与 COMMON 处理", id, e);
                tagPaths = List.of();
                rarity = Rarity.COMMON;
            }
            snapshots.add(ItemSnapshot.of(key, hasEmc, value, tagPaths, rarity));
        }

        return new Batch(List.copyOf(snapshots), processed, skippedEmpty, skippedIgnored,
                skippedUnregistered);
    }

    /**
     * Result of one page of item scanning.
     *
     * @param snapshots           snapshots produced this page, immutable
     * @param processed           number of ids actually checked this page (including skipped ones)
     * @param skippedEmpty        number of empty stacks
     * @param skippedIgnored      number hitting an ignore rule
     * @param skippedUnregistered number that could not resolve to a registered item
     */
    public record Batch(List<ItemSnapshot> snapshots, int processed, int skippedEmpty,
                        int skippedIgnored, int skippedUnregistered) {

        public Batch {
            snapshots = List.copyOf(snapshots);
            if (processed < 0 || skippedEmpty < 0 || skippedIgnored < 0
                    || skippedUnregistered < 0) {
                throw new IllegalArgumentException("Batch 计数不能为负数");
            }
        }
    }

    /**
     * Tag and rarity metadata for a single item.
     *
     * @param tagPaths tag paths; {@code null} normalised to an empty list
     * @param rarity   rarity; {@code null} normalised to {@link Rarity#COMMON}
     */
    record ItemMetadata(List<String> tagPaths, Rarity rarity) {

        ItemMetadata {
            tagPaths = tagPaths == null ? List.of() : List.copyOf(tagPaths);
            rarity = rarity == null ? Rarity.COMMON : rarity;
        }
    }

    /**
     * Tag and rarity reader. The production implementation reads {@link ItemStack}; tests can inject
     * fixed values or failures.
     */
    @FunctionalInterface
    interface MetadataReader {

        ItemMetadata read(ItemStack stack);
    }

    private static ItemMetadata readMetadata(ItemStack stack) {
        List<String> tagPaths = stack.getTags()
                .map(tag -> tag.location().toString())
                .distinct()
                .sorted()
                .toList();
        return new ItemMetadata(tagPaths, stack.getRarity());
    }

    private static boolean isIgnored(Set<String> ignoredNamespaces,
                                     Set<ResourceLocation> ignoredItems,
                                     ResourceLocation id) {
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
     * Item scan session holding a registry cursor.
     *
     * <p>{@link #processed()} counts registry entries already checked (including skipped entries); the
     * return value of {@link #step(int)} matches it and serves as the actual work amount for
     * {@code BatchProcessor}.</p>
     */
    public static final class ItemScanSession {

        private final EmcQuery emcQuery;
        private final Set<String> ignoredNamespaces;
        private final Set<ResourceLocation> ignoredItems;
        private final MetadataReader metadataReader;
        private final Iterator<Item> iterator;
        private final int total;
        private final List<ItemSnapshot> snapshots = new ArrayList<>();

        private int processed;
        private int skippedEmpty;
        private int skippedIgnored;
        private int skippedUnregistered;
        private boolean skipSummaryLogged;

        private ItemScanSession(EmcQuery emcQuery,
                                Set<String> ignoredNamespaces,
                                Set<ResourceLocation> ignoredItems,
                                MetadataReader metadataReader) {
            this.emcQuery = Objects.requireNonNull(emcQuery, "emcQuery");
            this.ignoredNamespaces = Set.copyOf(ignoredNamespaces == null ? Set.of() : ignoredNamespaces);
            this.ignoredItems = Set.copyOf(ignoredItems == null ? Set.of() : ignoredItems);
            this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
            this.iterator = BuiltInRegistries.ITEM.iterator();
            this.total = BuiltInRegistries.ITEM.size();
        }

        /**
         * @return total registry items (including air/ignored entries that will be skipped)
         */
        public int total() {
            return total;
        }

        /**
         * @return number of registry entries already checked
         */
        public int processed() {
            return processed;
        }

        /**
         * @return whether the whole registry has been traversed
         */
        public boolean isDone() {
            return !iterator.hasNext();
        }

        /**
         * Checks at most {@code maxItems} registry entries.
         *
         * @param maxItems maximum entries allowed this call, must be >= 1
         * @return entries actually checked this call; 0 when the registry is already exhausted
         */
        public int step(int maxItems) {
            return step(maxItems, Long.MAX_VALUE);
        }

        /**
         * Checks at most {@code maxItems} registry entries, checking the deadline between each entry.
         *
         * <p>A single item's EMC backend query cannot be preempted, so the deadline is best-effort: this
         * method returns immediately after the current entry finishes rather than interrupting an
         * in-flight query when checking the budget.</p>
         *
         * @param maxItems      maximum entries allowed this call, must be >= 1
         * @param deadlineNanos {@link System#nanoTime()} deadline; {@link Long#MAX_VALUE} means unlimited
         * @return entries actually checked this call; 0 when the registry is already exhausted
         */
        public int step(int maxItems, long deadlineNanos) {
            if (maxItems < 1) {
                throw new IllegalArgumentException("maxItems 必须 >= 1，实际为 " + maxItems);
            }
            int work = 0;
            while (work < maxItems && iterator.hasNext()) {
                if (deadlineReached(deadlineNanos)) {
                    break;
                }
                Item item = iterator.next();
                work++;
                processed++;
                collect(item);
            }
            if (!iterator.hasNext()) {
                logSkipSummary();
            }
            return work;
        }

        private static boolean deadlineReached(long deadlineNanos) {
            return deadlineNanos != Long.MAX_VALUE && System.nanoTime() >= deadlineNanos;
        }

        /**
         * @return immutable copy of item snapshots collected so far
         */
        public List<ItemSnapshot> snapshots() {
            return List.copyOf(snapshots);
        }

        private void collect(Item item) {
            if (item == Items.AIR) {
                return;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
            if (id == null) {
                skippedUnregistered++;
                return;
            }
            if (isIgnored(ignoredNamespaces, ignoredItems, id)) {
                skippedIgnored++;
                return;
            }
            ItemStack stack = defaultStack(item);
            if (stack.isEmpty()) {
                skippedEmpty++;
                return;
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
                ItemMetadata metadata = metadataReader.read(stack);
                tagPaths = metadata.tagPaths();
                rarity = metadata.rarity();
            } catch (RuntimeException e) {
                // Tag/rarity lookup is best-effort: a single item must not abort the whole scan.
                LOGGER.warn("读取物品 {} 的标签或稀有度失败，按空标签与 COMMON 处理", id, e);
                tagPaths = List.of();
                rarity = Rarity.COMMON;
            }
            snapshots.add(ItemSnapshot.of(key, hasEmc, value, tagPaths, rarity));
        }

        private void logSkipSummary() {
            if (skipSummaryLogged) {
                return;
            }
            skipSummaryLogged = true;
            if (skippedEmpty > 0 || skippedIgnored > 0 || skippedUnregistered > 0) {
                LOGGER.debug("Item scan skipped: {} ignored, {} empty, {} unregistered ({} collected)",
                        skippedIgnored, skippedEmpty, skippedUnregistered, snapshots.size());
            }
        }
    }

    /**
     * Phase-12 sharded item scan view, delegating to {@link ItemScanSession}.
     *
     * <p>Keeps the old contract of {@link #totalItems()} / {@link #processedItems()} / {@link #step(int)}
     * for {@code ScanPipeline} and existing tests; production wiring should prefer
     * {@link ItemScanner#beginSession()} for deadline support.</p>
     */
    public static final class ScanSession {

        private final ItemScanSession delegate;

        private ScanSession(ItemScanSession delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /**
         * @return total registry entries planned to check; 0 when unknown
         */
        public int totalItems() {
            return delegate.total();
        }

        /**
         * @return number of registry entries already visited
         */
        public int processedItems() {
            return delegate.processed();
        }

        /**
         * @return whether the registry has been traversed
         */
        public boolean isDone() {
            return delegate.isDone();
        }

        /**
         * Advances at most {@code maxItems} registry entries.
         *
         * @param maxItems maximum entries allowed this call, must be >= 1
         * @return entries actually visited this batch
         */
        public int step(int maxItems) {
            return delegate.step(maxItems);
        }

        /**
         * @return immutable copy of collected item snapshots, in registry order
         */
        public List<ItemSnapshot> snapshots() {
            return delegate.snapshots();
        }
    }

    /**
     * Minimal EMC query used by the scanner, kept separate from the pipeline so tests can supply a
     * map-backed implementation.
     */
    public interface EmcQuery {

        boolean hasValue(ItemStack stack);

        long getValue(ItemStack stack);

        /**
         * Queries the exact EMC value for full NBT, without falling back to the base item.
         *
         * <p>The default implementation falls back to {@link #getValue(ItemStack)} for backends with no
         * exact-NBT concept.</p>
         *
         * @param stack the stack to query
         * @return the exact NBT mapping value; {@code 0} when there is no dedicated mapping
         */
        default long getExactValue(ItemStack stack) {
            return getValue(stack);
        }
    }
}
