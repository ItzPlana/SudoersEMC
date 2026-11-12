package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.ArrayList;
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

    /** 每扫描多少个注册项检查一次取消信号。 */
    public static final int CANCELLATION_CHECK_INTERVAL = 256;

    /**
     * Scans every registered item.
     *
     * @return one snapshot per non-air, non-ignored item, in registry order
     */
    public List<ItemSnapshot> scan() {
        return scan(() -> false);
    }

    /**
     * Scans registered items with a cooperative cancellation checkpoint.
     *
     * <p>每 {@value #CANCELLATION_CHECK_INTERVAL} 个注册项检查一次取消信号。取消时返回已经产生的
     * 部分快照；列表本身仍然不可变且顺序与注册表一致，调用方可以安全地用于部分诊断报告。</p>
     *
     * @param cancellation 取消检查；返回 {@code true} 时停止继续扫描
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
     * 返回按 {@link ResourceLocation} 自然顺序排序的已注册、非空气物品 id。
     *
     * <p>分页扫描必须使用稳定顺序，否则跨 tick 的游标会在注册表顺序变化时漂移。这里在
     * 开始扫描时固化一次顺序，后续每批只按该列表前进。</p>
     *
     * @return 不可变、排序后的物品 id 列表
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
     * 扫描一页已经排好序的物品 id。
     *
     * <p>本方法不修改扫描器状态，调用方负责维护游标。每 {@value #CANCELLATION_CHECK_INTERVAL}
     * 个条目检查一次取消信号；取消时返回本页已经处理的部分结果。</p>
     *
     * @param orderedIds   稳定顺序的物品 id 列表
     * @param cancellation 取消检查；返回 {@code true} 时停止本页
     * @return 本页快照与跳过计数
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
            if (isIgnored(id)) {
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

        return new Batch(List.copyOf(snapshots), processed, skippedEmpty, skippedIgnored,
                skippedUnregistered);
    }

    /**
     * 一页物品扫描结果。
     *
     * @param snapshots           本页生成的快照，不可变
     * @param processed           本页实际检查的 id 数量（包含被跳过项）
     * @param skippedEmpty        空堆叠数量
     * @param skippedIgnored      命中忽略规则的数量
     * @param skippedUnregistered 无法解析到注册物品的数量
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
