package com.itzplana.sudoersemc.storage;

import com.itzplana.sudoersemc.core.ItemKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 已见物品目录的持久化存储。
 *
 * <p>对应文件 {@code config/sudoersemc/known_items.json}，记录每个物品第一次和最近一次被扫描
 * 到的时间。目录只描述“见过什么”，不保存任何 EMC 数值，因此可以安全地随每次扫描增长。</p>
 *
 * <h2>并发约定</h2>
 * <p>内存表是 {@link ConcurrentHashMap}：{@link #recordSeen(ItemKey)}、{@link #contains(ItemKey)}
 * 与快照方法可由任意线程调用。{@link #load()} 与 {@link #save()} 通过私有锁串行化，快照返回
 * 不可变副本，可以在遍历时继续记录新物品。</p>
 *
 * <h2>容量</h2>
 * <p>目录按设计不设硬上限：它必须记住所有见过的 {@link ItemKey}（包括 NBT/经济变体），才能
 * 支撑增量扫描。文件大小与关服写盘时间随唯一物品数增长，单次保存还需要一次全量排序；大型整合包
 * 通常为数千到数万条，属于可接受范围。若文件因异常数据膨胀，管理员可在关服后删除
 * {@code known_items.json}，让目录在下次扫描时重建。</p>
 *
 * <h2>失败行为</h2>
 * <p>所有公开方法都不抛出异常。文件缺失按空目录处理；JSON 损坏时清空内存目录并保留原始文件，
 * 下次保存前自动备份；更高的 {@code schemaVersion} 既不加载也不覆盖。单条格式错误的记录会被
 * 跳过，不影响其余记录。写入或备份失败时保留脏标记，下一次 {@link #saveIfDirty()} 会重试。</p>
 */
public final class KnownItemsStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/storage/KnownItemsStore");

    /** 当前磁盘格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 相对于 {@link SudoersemcPaths#dataDir()} 的文件名。 */
    public static final String FILE_NAME = "known_items.json";

    private final Path file;

    private final ConcurrentHashMap<ItemKey, KnownItem> items = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object loadSaveLock = new Object();

    /** 文件由不支持的（更高）版本写入时为 true，用于阻止覆盖。 */
    private volatile boolean unsupportedSchemaVersion;
    private volatile int loadedSchemaVersion = SCHEMA_VERSION;
    /** 首次重写从磁盘读到的文件之前需要先备份。 */
    private volatile boolean backupPending;

    /**
     * 已见物品条目。
     *
     * @param key       物品身份（含经济变体）
     * @param firstSeen 第一次被观察到的 epoch 毫秒，必须 {@code >= 0}
     * @param lastSeen  最近一次被观察到的 epoch 毫秒，不得早于 {@code firstSeen}
     */
    public record KnownItem(ItemKey key, long firstSeen, long lastSeen) {

        public KnownItem {
            Objects.requireNonNull(key, "key");
            if (firstSeen < 0L) {
                throw new IllegalArgumentException("firstSeen 必须 >= 0，实际为 " + firstSeen);
            }
            if (lastSeen < 0L) {
                throw new IllegalArgumentException("lastSeen 必须 >= 0，实际为 " + lastSeen);
            }
            if (lastSeen < firstSeen) {
                throw new IllegalArgumentException(
                        "lastSeen 不能早于 firstSeen：" + lastSeen + " < " + firstSeen);
            }
        }
    }

    /**
     * 生产环境构造函数，使用 {@code config/sudoersemc/known_items.json}。
     *
     * <p>构造过程不访问磁盘；需要显式调用一次 {@link #load()}。</p>
     */
    public KnownItemsStore() {
        this(SudoersemcPaths.file(FILE_NAME));
    }

    /**
     * 测试/嵌入式构造函数。
     *
     * @param file 目录文件路径；父目录在保存时自动创建
     */
    public KnownItemsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * 从磁盘加载目录。
     *
     * <p>文件缺失：空目录且无警告。JSON 损坏：清空并记录警告，原始文件保留到下次覆盖前备份。
     * 更高版本：不加载任何记录，并且 {@link #save()} 拒绝覆盖。无法解析的物品 id、负数时间戳
     * 会按单条记录跳过；{@code lastSeen} 早于 {@code firstSeen} 时会被提升到 {@code firstSeen}，
     * 以修复时钟回拨造成的轻微倒序。</p>
     */
    public void load() {
        synchronized (loadSaveLock) {
            items.clear();
            dirty.set(false);
            unsupportedSchemaVersion = false;
            loadedSchemaVersion = SCHEMA_VERSION;
            backupPending = false;

            try {
                boolean existed = Files.isRegularFile(file);
                KnownItemsFileDto dto = JsonFileIO.read(file, KnownItemsFileDto.class);
                if (!existed) {
                    return;
                }
                // 文件已存在：首次重写前必须备份。
                backupPending = true;

                if (dto == null) {
                    LOGGER.warn("无法解析 {}，按空物品目录处理", file);
                    return;
                }
                if (dto.schemaVersion > SCHEMA_VERSION) {
                    unsupportedSchemaVersion = true;
                    loadedSchemaVersion = dto.schemaVersion;
                    backupPending = false;
                    LOGGER.warn(
                            "{} 使用不受支持的 schemaVersion {}（当前支持 {}）；保留文件原样并拒绝覆盖",
                            file, dto.schemaVersion, SCHEMA_VERSION);
                    return;
                }
                if (dto.items == null) {
                    return;
                }

                int skipped = 0;
                for (ItemDto dtoItem : dto.items) {
                    KnownItem knownItem = toKnownItem(dtoItem);
                    if (knownItem == null) {
                        skipped++;
                        continue;
                    }
                    items.put(knownItem.key(), knownItem);
                }
                if (skipped > 0) {
                    LOGGER.warn("加载 {} 时跳过了 {} 条格式错误的物品记录", file, skipped);
                }
            } catch (RuntimeException e) {
                items.clear();
                LOGGER.warn("加载物品目录 {} 失败，按空目录处理", file, e);
            }
        }
    }

    /** 若存在尚未写盘的变更则保存。 */
    public void saveIfDirty() {
        if (dirty.get()) {
            save();
        }
    }

    /**
     * 按 {@link ItemKey#compareTo(ItemKey)} 升序写出全部记录。
     *
     * <p>不支持的更高版本会阻止写入。首次重写已存在的文件前会生成带时间戳的 {@code .bak}。
     * 写入或备份失败时保留脏标记，调用方可在下一次 {@link #saveIfDirty()} 时重试。</p>
     */
    public void save() {
        synchronized (loadSaveLock) {
            if (unsupportedSchemaVersion) {
                LOGGER.warn(
                        "不保存 {}：该文件由不受支持的 schemaVersion {} 写入",
                        file, loadedSchemaVersion);
                return;
            }
            // 先清除脏标记：并发记录的新物品会重新置位，留给下一次保存。
            dirty.set(false);
            try {
                List<KnownItem> ordered = new ArrayList<>(items.values());
                ordered.sort(Comparator.comparing(KnownItem::key));

                KnownItemsFileDto dto = new KnownItemsFileDto();
                dto.schemaVersion = SCHEMA_VERSION;
                dto.items = new ArrayList<>(ordered.size());
                for (KnownItem item : ordered) {
                    dto.items.add(ItemDto.from(item));
                }

                if (backupPending && !JsonFileIO.backupIfExists(file)) {
                    dirty.set(true);
                    LOGGER.warn("保存物品目录到 {} 失败：无法创建备份，保留待保存状态等待重试", file);
                    return;
                }
                if (!JsonFileIO.writeAtomically(file, dto)) {
                    dirty.set(true);
                    return;
                }
                backupPending = false;
            } catch (RuntimeException e) {
                dirty.set(true);
                LOGGER.warn("保存物品目录到 {} 失败", file, e);
            }
        }
    }

    /**
     * 记录一次“看见某个物品”。
     *
     * <p>首次出现时同时设置 {@code firstSeen} 与 {@code lastSeen}；之后只把 {@code lastSeen}
     * 向前推进。时间戳不晚于已有 {@code lastSeen} 时视为没有变化，不会置脏，避免同一毫秒内的
     * 重复扫描产生无意义的写盘。{@code null} 会被忽略。</p>
     *
     * @param key 被观察到的物品身份
     */
    public void recordSeen(ItemKey key) {
        if (key == null) {
            return;
        }
        try {
            long now = System.currentTimeMillis();
            AtomicBoolean changed = new AtomicBoolean(false);
            items.compute(key, (ignored, existing) -> {
                if (existing == null) {
                    changed.set(true);
                    return new KnownItem(key, now, now);
                }
                long newLastSeen = Math.max(now, existing.lastSeen());
                if (newLastSeen == existing.lastSeen()) {
                    return existing;
                }
                changed.set(true);
                return new KnownItem(key, existing.firstSeen(), newLastSeen);
            });
            if (changed.get()) {
                dirty.set(true);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("记录物品出现时间失败：{}", key, e);
        }
    }

    /**
     * @param key 物品身份，可为 {@code null}
     * @return 目录中是否已有该物品
     */
    public boolean contains(ItemKey key) {
        return key != null && items.containsKey(key);
    }

    public int size() {
        return items.size();
    }

    /**
     * 返回按 {@link ItemKey#compareTo(ItemKey)} 升序排列的不可变快照。
     *
     * @return 目录副本；内部错误时返回空列表而不是抛出异常
     */
    public List<KnownItem> snapshot() {
        try {
            List<KnownItem> result = new ArrayList<>(items.values());
            result.sort(Comparator.comparing(KnownItem::key));
            return List.copyOf(result);
        } catch (RuntimeException e) {
            LOGGER.warn("快照物品目录失败", e);
            return List.of();
        }
    }

    /**
     * 清空内存目录并标记为待保存。
     *
     * <p>不会立即写盘；调用方需要在合适的时机调用 {@link #save()} 或 {@link #saveIfDirty()}。
     * 即使目录本来就是空的也会置脏，因为“清空”本身是一次显式变更请求。</p>
     */
    public void clear() {
        try {
            items.clear();
            dirty.set(true);
        } catch (RuntimeException e) {
            LOGGER.warn("清空物品目录失败", e);
        }
    }

    /** @return 是否存在尚未写盘的变更 */
    public boolean isDirty() {
        return dirty.get();
    }

    /** @return 该存储使用的文件，主要用于诊断与测试 */
    public Path file() {
        return file;
    }

    /** 把一条磁盘记录转换为内存记录；任何字段非法时返回 {@code null}。 */
    private static KnownItem toKnownItem(ItemDto dto) {
        if (dto == null || dto.item == null || dto.item.isBlank()) {
            return null;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(dto.item);
        if (itemId == null) {
            return null;
        }
        if (dto.firstSeen < 0L || dto.lastSeen < 0L) {
            return null;
        }
        long lastSeen = Math.max(dto.firstSeen, dto.lastSeen);
        try {
            return new KnownItem(ItemKey.of(itemId, dto.variant), dto.firstSeen, lastSeen);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 磁盘文档。字段顺序固定，保证同一份目录每次写出的字节顺序一致。
     */
    static final class KnownItemsFileDto {
        int schemaVersion;
        List<ItemDto> items = new ArrayList<>();
    }

    /** 目录中的单条记录。 */
    static final class ItemDto {
        String item;
        String variant;
        long firstSeen;
        long lastSeen;

        static ItemDto from(KnownItem knownItem) {
            ItemDto dto = new ItemDto();
            dto.item = knownItem.key().itemId().toString();
            dto.variant = knownItem.key().variantHash();
            dto.firstSeen = knownItem.firstSeen();
            dto.lastSeen = knownItem.lastSeen();
            return dto;
        }
    }
}
