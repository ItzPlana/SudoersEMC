package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
import java.util.Iterator;
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
 *
 * <p>生产管线使用 {@link #beginScan()} 获取可跨 tick 推进的 {@link ScanSession}，每个 tick
 * 只处理有限数量的注册表条目；{@link #scan()} 保留给测试与一次性调用。</p>
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
     * 测试/集成可注入的构造器。
     *
     * @param metadataReader 标签与稀有度读取器，不能为 {@code null}
     */
    ItemScanner(EmcQuery emcQuery, Set<String> ignoredNamespaces, Set<ResourceLocation> ignoredItems,
                MetadataReader metadataReader) {
        this.emcQuery = Objects.requireNonNull(emcQuery, "emcQuery");
        this.ignoredNamespaces = Set.copyOf(ignoredNamespaces == null ? Set.of() : ignoredNamespaces);
        this.ignoredItems = Set.copyOf(ignoredItems == null ? Set.of() : ignoredItems);
        this.metadataReader = Objects.requireNonNull(metadataReader, "metadataReader");
    }

    /**
     * 创建一个分片扫描会话。会话持有注册表迭代器，必须在服务器线程推进。
     *
     * @return 新的扫描会话
     */
    public ScanSession beginScan() {
        return new ScanSession(this, BuiltInRegistries.ITEM.iterator(), BuiltInRegistries.ITEM.size());
    }

    /**
     * Scans every registered item in one call.
     *
     * @return one snapshot per non-air, non-ignored item, in registry order
     */
    public List<ItemSnapshot> scan() {
        ScanSession session = beginScan();
        while (!session.isDone()) {
            session.step(Integer.MAX_VALUE);
        }
        return session.snapshots();
    }

    private ItemSnapshot scanItem(Item item, ScanSession session) {
        if (item == Items.AIR) {
            return null;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            session.skippedUnregistered++;
            return null;
        }
        if (isIgnored(id)) {
            session.skippedIgnored++;
            return null;
        }
        ItemStack stack = defaultStack(item);
        if (stack.isEmpty()) {
            session.skippedEmpty++;
            return null;
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
        return ItemSnapshot.of(key, hasEmc, value, tagPaths, rarity);
    }

    /**
     * 单个物品的标签与稀有度元数据。
     *
     * @param tagPaths 标签路径；{@code null} 归一为空列表
     * @param rarity   稀有度；{@code null} 归一为 {@link Rarity#COMMON}
     */
    record ItemMetadata(List<String> tagPaths, Rarity rarity) {

        ItemMetadata {
            tagPaths = tagPaths == null ? List.of() : List.copyOf(tagPaths);
            rarity = rarity == null ? Rarity.COMMON : rarity;
        }
    }

    /**
     * 标签与稀有度读取器。生产实现读取 {@link ItemStack}；测试可注入固定值或故障。
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
     * 分片物品扫描会话。
     *
     * <p>每次 {@link #step(int)} 最多访问 {@code maxItems} 个注册表条目，并在会话结束时输出
     * 跳过统计。会话本身不做线程安全承诺，只能由创建它的服务器线程推进。</p>
     */
    public static final class ScanSession {

        private final ItemScanner scanner;
        private final Iterator<Item> iterator;
        private final int total;
        private final List<ItemSnapshot> snapshots = new ArrayList<>();

        private int processed;
        private int skippedEmpty;
        private int skippedIgnored;
        private int skippedUnregistered;
        private boolean done;
        private boolean completionLogged;

        private ScanSession(ItemScanner scanner, Iterator<Item> iterator, int total) {
            this.scanner = scanner;
            this.iterator = Objects.requireNonNull(iterator, "iterator");
            this.total = Math.max(0, total);
        }

        /**
         * @return 计划检查的注册表条目总数；未知时为 0
         */
        public int totalItems() {
            return total;
        }

        /**
         * @return 已经访问的注册表条目数量
         */
        public int processedItems() {
            return processed;
        }

        /**
         * @return 是否已经遍历完注册表
         */
        public boolean isDone() {
            return done;
        }

        /**
         * 推进最多 {@code maxItems} 个注册表条目。
         *
         * @param maxItems 本次允许访问的最大条目数，必须大于等于 1
         * @return 本批实际访问的条目数量
         */
        public int step(int maxItems) {
            if (maxItems < 1) {
                throw new IllegalArgumentException("maxItems 必须 >= 1，实际为 " + maxItems);
            }
            if (done) {
                return 0;
            }
            int visited = 0;
            while (visited < maxItems && iterator.hasNext()) {
                Item item = iterator.next();
                visited++;
                processed++;
                ItemSnapshot snapshot = scanner.scanItem(item, this);
                if (snapshot != null) {
                    snapshots.add(snapshot);
                }
            }
            if (!iterator.hasNext()) {
                done = true;
                logCompletion();
            }
            return visited;
        }

        /**
         * @return 已采集物品快照的不可变副本，保持注册表顺序
         */
        public List<ItemSnapshot> snapshots() {
            return List.copyOf(snapshots);
        }

        private void logCompletion() {
            if (completionLogged) {
                return;
            }
            completionLogged = true;
            if (skippedEmpty > 0 || skippedIgnored > 0 || skippedUnregistered > 0) {
                LOGGER.debug("Item scan skipped: {} ignored, {} empty, {} unregistered ({} collected)",
                        skippedIgnored, skippedEmpty, skippedUnregistered, snapshots.size());
            }
        }
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
