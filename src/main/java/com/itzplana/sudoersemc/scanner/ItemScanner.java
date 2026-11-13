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
     * 开始一次可分片推进的物品扫描。
     *
     * <p>会话在构造时只固定注册表总量与迭代器，不执行逐物品查询；每次
     * {@link ItemScanSession#step(int)} 最多检查 {@code maxItems} 个注册表条目。调用方
     * （{@code ScanManager}）因此可以把大型整合包的物品扫描分散到多个服务器 tick。</p>
     *
     * @return 新的物品扫描游标
     */
    public ItemScanSession beginSession() {
        return new ItemScanSession(emcQuery, ignoredNamespaces, ignoredItems);
    }

    /**
     * 持有注册表游标的物品扫描会话。
     *
     * <p>{@link #processed()} 统计的是已经检查过的注册表条目数（包括被跳过条目），
     * {@link #step(int)} 的返回值与之一致，作为 {@code BatchProcessor} 的实际工作量。</p>
     */
    public static final class ItemScanSession {

        private final EmcQuery emcQuery;
        private final Set<String> ignoredNamespaces;
        private final Set<ResourceLocation> ignoredItems;
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
                                Set<ResourceLocation> ignoredItems) {
            this.emcQuery = Objects.requireNonNull(emcQuery, "emcQuery");
            this.ignoredNamespaces = Set.copyOf(ignoredNamespaces == null ? Set.of() : ignoredNamespaces);
            this.ignoredItems = Set.copyOf(ignoredItems == null ? Set.of() : ignoredItems);
            this.iterator = BuiltInRegistries.ITEM.iterator();
            this.total = BuiltInRegistries.ITEM.size();
        }

        /**
         * @return 注册表物品总量（包含会被跳过的空气/忽略项）
         */
        public int total() {
            return total;
        }

        /**
         * @return 已经检查过的注册表条目数
         */
        public int processed() {
            return processed;
        }

        /**
         * @return 是否已经遍历完整个注册表
         */
        public boolean isDone() {
            return !iterator.hasNext();
        }

        /**
         * 检查最多 {@code maxItems} 个注册表条目。
         *
         * @param maxItems 本次允许检查的最大条目数，必须大于等于 1
         * @return 本次实际检查的条目数；注册表已经遍历完时返回 0
         */
        public int step(int maxItems) {
            return step(maxItems, Long.MAX_VALUE);
        }

        /**
         * 检查最多 {@code maxItems} 个注册表条目，并在每个条目之间检查截止时间。
         *
         * <p>单个物品的 EMC 后端查询无法被抢占，因此截止时间是最佳努力：本方法会在当前
         * 条目处理完后立即返回，而不会在校验预算时中断正在执行的查询。</p>
         *
         * @param maxItems      本次允许检查的最大条目数，必须大于等于 1
         * @param deadlineNanos {@link System#nanoTime()} 截止时间；{@link Long#MAX_VALUE} 表示不限
         * @return 本次实际检查的条目数；注册表已经遍历完时返回 0
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
         * @return 到目前为止收集到的物品快照的不可变副本
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
            if (isIgnored(id)) {
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
            snapshots.add(new ItemSnapshot(key, id, id.getNamespace(), id.getPath(), hasEmc, value));
        }

        private boolean isIgnored(ResourceLocation id) {
            return ignoredItems.contains(id) || ignoredNamespaces.contains(id.getNamespace());
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

        /**
         * Builds the canonical "no NBT" stack for an item. Damageable items are left at their default
         * state so the value applies to the plain item rather than a specific damage value.
         */
        private static ItemStack defaultStack(Item item) {
            return new ItemStack(item);
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
