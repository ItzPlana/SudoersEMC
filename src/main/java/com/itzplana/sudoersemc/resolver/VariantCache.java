package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * 有界的 LRU 变体价格缓存。
 *
 * <p>缓存最多保存 {@code maxEntries} 个 {@link ItemKey}。容量已满时，最久未使用的条目在
 * 容量满时被淘汰。所有访问可变状态的方法都在同一把锁上同步，可从多个线程安全访问；命中与
 * 未命中计数只统计 {@link #get(ItemKey)} 调用，{@link #clear()} 会同时清空计数。</p>
 *
 * <p>容量按条目数计算，不限制单个 {@code variantHash} 的字节长度；调用方仍需为变体哈希
 * 设置长度上限，避免超大容器 NBT 占用过多内存。</p>
 */
public final class VariantCache {

    /**
     * 未显式指定容量时使用的默认条目上限。
     */
    public static final int DEFAULT_MAX_ENTRIES = 4096;

    private final int maxEntries;
    private final LinkedHashMap<ItemKey, Long> entries;
    private long hits;
    private long misses;

    /**
     * @param maxEntries 最多缓存的条目数，必须大于等于 1
     * @throws IllegalArgumentException 当 {@code maxEntries < 1}
     */
    public VariantCache(int maxEntries) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        this.entries = new LinkedHashMap<>(16, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ItemKey, Long> eldest) {
                return size() > VariantCache.this.maxEntries;
            }
        };
    }

    /**
     * 查询缓存并更新命中/未命中计数。
     *
     * @param key 物品键
     * @return 缓存的 EMC；不存在时返回空
     */
    public synchronized OptionalLong get(ItemKey key) {
        Objects.requireNonNull(key, "key");
        Long value = entries.get(key);
        if (value == null) {
            misses++;
            return OptionalLong.empty();
        }
        hits++;
        return OptionalLong.of(value);
    }

    /**
     * 写入或更新一个条目。
     *
     * @param key   物品键
     * @param value 非负 EMC
     */
    public synchronized void put(ItemKey key, long value) {
        Objects.requireNonNull(key, "key");
        entries.put(key, value);
    }

    /**
     * @return 当前缓存条目数，永不超过 {@link #maxEntries()}
     */
    public synchronized int size() {
        return entries.size();
    }

    /**
     * 清空缓存并把命中/未命中计数归零。
     */
    public synchronized void clear() {
        entries.clear();
        hits = 0L;
        misses = 0L;
    }

    /**
     * @return 自上次清空以来成功命中的次数
     */
    public synchronized long hitCount() {
        return hits;
    }

    /**
     * @return 自上次清空以来未命中的次数
     */
    public synchronized long missCount() {
        return misses;
    }

    /**
     * @return 构造时指定的容量上限
     */
    public int maxEntries() {
        return maxEntries;
    }
}
