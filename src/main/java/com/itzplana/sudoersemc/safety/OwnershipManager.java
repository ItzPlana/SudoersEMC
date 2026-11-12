package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OwnedEntrySink;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import org.jetbrains.annotations.Nullable;

/**
 * 判定物品归属，并在发现外部改动时把所有权迁移出去。
 *
 * <p>本类只做判定与所有权标记迁移，不修改任何 EMC 数值。{@link #releaseOwnership(ItemKey)}
 * 写入的条目与原条目数值完全相同，只是把 {@code owned} 从 {@code true} 改为 {@code false}，
 * 从而告诉后续流程“这个值已经不再由 SudoersEMC 负责”。</p>
 *
 * <h2>失败行为</h2>
 * <p>归属存储（{@link OwnedEntrySink}）或当前值读取器（{@link EmcValueReader}）抛出运行时
 * 异常时，异常绝不会向外传播。此时无法确认归属，因此：</p>
 * <ul>
 *   <li>{@link #classify(ItemKey)} 在存储读取失败时返回 {@link OwnershipState#NOT_OWNED}，
 *       在读取器异常时返回 {@link OwnershipState#UNKNOWN}；</li>
 *   <li>{@link #detectExternalModifications()} 在存储或读取器异常时返回空列表；</li>
 *   <li>{@link #snapshotStates()} 在存储异常时返回空映射，读取器不可用或读取异常时把对应
 *       物品标记为 {@link OwnershipState#UNKNOWN}；</li>
 *   <li>{@link #releaseOwnership(ItemKey)} 返回 {@code false}；</li>
 *   <li>{@link #migrateOwnership()} 返回 {@code 0}。</li>
 * </ul>
 *
 * <p>归属绝不能仅凭当前数值推断：外部系统可能把值改成与记录相同的数字。本类始终以持久化
 * 记录中的 {@code owned} 标记为唯一依据。</p>
 */
public final class OwnershipManager {

    private final OwnedEntrySink sink;
    private final EmcValueReader reader;

    /**
     * @param sink   归属记录的读写入口；不能为 {@code null}
     * @param reader 读取物品当前 EMC 值的后端；不能为 {@code null}
     */
    public OwnershipManager(OwnedEntrySink sink, EmcValueReader reader) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /**
     * 只读的当前 EMC 值后端。
     *
     * <p>实现方在无法确定某个物品的值时应返回 {@code 0}。整体后端不可用（例如 ProjectE 未
     * 安装、映射尚未建立）时必须让 {@link #available()} 返回 {@code false}，否则调用方会把
     * 占位值误判成真实的外部改动。</p>
     */
    @FunctionalInterface
    public interface EmcValueReader {

        /**
         * @return 物品当前 EMC 值；未知时返回 {@code 0}
         */
        long currentValue(ItemKey item);

        /**
         * @return {@code true} 表示后端可以回答当前值查询；默认视为可用
         */
        default boolean available() {
            return true;
        }
    }

    /**
     * 判定单个物品的归属。
     *
     * @param item 待判定物品；{@code null} 视为没有记录
     * @return 归属状态；读取器不可用时返回 {@link OwnershipState#UNKNOWN}
     */
    public OwnershipState classify(ItemKey item) {
        if (item == null) {
            return OwnershipState.NOT_OWNED;
        }
        if (!isReaderAvailable()) {
            return OwnershipState.UNKNOWN;
        }
        Map<ItemKey, GeneratedEmcEntry> snapshot = safeSnapshot();
        if (snapshot == null) {
            return OwnershipState.NOT_OWNED;
        }
        return classifyEntry(snapshot.get(item), item);
    }

    /**
     * 找出所有“记录为 owned 且当前值与记录值不同”的物品。
     *
     * <p>结果按 {@link ItemKey} 的自然顺序升序排列。只比较 {@code owned=true} 的记录；
     * 读取器不可用或存储异常时返回空列表，绝不抛出异常。</p>
     */
    public List<ItemKey> detectExternalModifications() {
        return detectExternalModifications(null);
    }

    /**
     * 找出指定集合中“记录为 owned 且当前值与记录值不同”的物品。
     *
     * <p>调用方通常只把本轮后端实际报告过数值的物品传入，避免把“本轮未扫描到”误判为
     * 外部删除。{@code consideredItems == null} 表示不限制，保持旧行为。</p>
     *
     * @param consideredItems 需要比较的物品集合；{@code null} 表示全部已记录物品
     * @return 发生外部修改的物品，按自然顺序升序排列
     */
    public List<ItemKey> detectExternalModifications(@Nullable Set<ItemKey> consideredItems) {
        if (!isReaderAvailable()) {
            return List.of();
        }
        Map<ItemKey, GeneratedEmcEntry> snapshot = safeSnapshot();
        if (snapshot == null) {
            return List.of();
        }
        List<ItemKey> drifted = new ArrayList<>();
        for (Map.Entry<ItemKey, GeneratedEmcEntry> mapEntry : snapshot.entrySet()) {
            GeneratedEmcEntry entry = mapEntry.getValue();
            if (entry == null || !entry.owned()) {
                continue;
            }
            ItemKey item = entry.item() == null ? mapEntry.getKey() : entry.item();
            if (item == null) {
                continue;
            }
            if (consideredItems != null && !consideredItems.contains(item)) {
                continue;
            }
            Long current = readCurrentValue(item);
            if (current != null && current.longValue() != entry.value()) {
                drifted.add(item);
            }
        }
        drifted.sort(null);
        return List.copyOf(drifted);
    }

    /**
     * 释放单个物品的所有权，但保持数值不变。
     *
     * <p>该方法迁移的是“所有权”而不是“值”：写入的新条目沿用原条目的数值、来源、配方
     * 引用与时间戳，仅把 {@code owned} 置为 {@code false}。物品没有记录、记录中
     * {@code owned=false} 或存储拒绝写入时返回 {@code false}。</p>
     *
     * @param item 要释放所有权的物品；{@code null} 直接返回 {@code false}
     */
    public boolean releaseOwnership(ItemKey item) {
        if (item == null) {
            return false;
        }
        Map<ItemKey, GeneratedEmcEntry> snapshot = safeSnapshot();
        if (snapshot == null) {
            return false;
        }
        GeneratedEmcEntry entry = snapshot.get(item);
        if (entry == null || !entry.owned()) {
            return false;
        }
        try {
            return sink.stage(entry.withOwned(false));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * 把所有被外部改动过的条目迁移出所有权。
     *
     * <p>读取器不可用时是安全的空操作：返回 {@code 0} 且不修改任何记录。每个条目独立处理，
     * 单个写入失败只影响该条目，异常不会向外传播。</p>
     *
     * @return 成功释放所有权的条目数量
     */
    public int migrateOwnership() {
        return migrateOwnership(null);
    }

    /**
     * 把指定集合中被外部改动过的条目迁移出所有权。
     *
     * @param consideredItems 需要检查的物品集合；{@code null} 表示全部已记录物品
     * @return 成功释放所有权的条目数量
     */
    public int migrateOwnership(@Nullable Set<ItemKey> consideredItems) {
        if (!isReaderAvailable()) {
            return 0;
        }
        int released = 0;
        for (ItemKey item : detectExternalModifications(consideredItems)) {
            if (releaseOwnership(item)) {
                released++;
            }
        }
        return released;
    }

    /**
     * 为诊断输出每个已记录物品的归属状态。
     *
     * <p>返回的映射按 {@link ItemKey} 自然顺序排列且不可修改。读取器不可用时所有已记录物品
     * 都是 {@link OwnershipState#UNKNOWN}；存储异常时返回空映射。</p>
     */
    public Map<ItemKey, OwnershipState> snapshotStates() {
        Map<ItemKey, GeneratedEmcEntry> snapshot = safeSnapshot();
        if (snapshot == null) {
            return Map.of();
        }
        Map<ItemKey, OwnershipState> states = new TreeMap<>();
        for (Map.Entry<ItemKey, GeneratedEmcEntry> mapEntry : snapshot.entrySet()) {
            ItemKey item = mapEntry.getKey() == null ? itemOf(mapEntry.getValue()) : mapEntry.getKey();
            if (item == null) {
                continue;
            }
            states.put(item, classifyEntry(mapEntry.getValue(), item));
        }
        return Collections.unmodifiableMap(states);
    }

    private OwnershipState classifyEntry(GeneratedEmcEntry entry, ItemKey item) {
        if (!isReaderAvailable()) {
            return OwnershipState.UNKNOWN;
        }
        if (entry == null || !entry.owned()) {
            return OwnershipState.NOT_OWNED;
        }
        Long current = readCurrentValue(item);
        if (current == null) {
            return OwnershipState.UNKNOWN;
        }
        return current.longValue() == entry.value()
                ? OwnershipState.OWNED_BY_US
                : OwnershipState.EXTERNALLY_MODIFIED;
    }

    /**
     * @return 存储快照；存储抛出异常或返回 {@code null} 时返回 {@code null}
     *         （用 {@code null} 区分“读取失败”与“确实为空”）
     */
    private Map<ItemKey, GeneratedEmcEntry> safeSnapshot() {
        try {
            Map<ItemKey, GeneratedEmcEntry> snapshot = sink.snapshotAll();
            return snapshot == null ? Map.of() : snapshot;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Long readCurrentValue(ItemKey item) {
        try {
            return reader.currentValue(item);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean isReaderAvailable() {
        try {
            return reader.available();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static ItemKey itemOf(GeneratedEmcEntry entry) {
        return entry == null ? null : entry.item();
    }
}
