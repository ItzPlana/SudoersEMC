package com.itzplana.sudoersemc.storage;

import com.itzplana.sudoersemc.core.ItemKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 被大幅降价保护隔离的待审记录的持久化存储。
 *
 * <p>对应文件 {@code config/sudoersemc/quarantined_drops.json}。每次隔离都会把旧值、拟写入值、
 * 降幅、判定原因与时间戳写入这里，管理员审批后把 {@code approved} 置为 {@code true}；
 * {@code safety.approveAppliesImmediately=true} 且降价值成功落盘时，{@code applied} 也会置为
 * {@code true}。该文件只记录“发生过什么”，不会直接修改 {@link GeneratedEntryStore} 中的数值。</p>
 *
 * <h2>保留策略</h2>
 * <p>同一物品只保留一条记录：再次记录时用新条目替换旧条目。总量上限为
 * {@link #DEFAULT_MAX_ENTRIES}，超出时按时间戳从最旧开始淘汰，避免长期运行后文件无限增长。
 * 加载后若超过上限，会先按时间戳排序再一次性裁剪，避免手工放大的异常文件造成平方级开销。</p>
 *
 * <h2>并发与失败行为</h2>
 * <p>所有公开方法都在私有锁内操作内存表，快照返回不可变副本。{@code load/save/record/approve/
 * clear} 均不抛出异常：文件缺失按空列表处理，JSON 损坏时清空内存并保留原文件，更高的
 * {@code schemaVersion} 既不加载也不覆盖。</p>
 */
public final class QuarantinedDropsStore {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("sudoersemc/storage/QuarantinedDropsStore");

    /**
     * 当前磁盘格式版本。版本 2 新增 {@code applied} 字段；版本 1/0 仍可读取，缺失字段按
     * {@code false} 处理。更高版本保持原文件不动并拒绝覆盖。
     */
    public static final int SCHEMA_VERSION = 2;

    /** 相对于 {@link SudoersemcPaths#dataDir()} 的文件名。 */
    public static final String FILE_NAME = "quarantined_drops.json";

    /** 最多保留的隔离条目数量。 */
    public static final int DEFAULT_MAX_ENTRIES = 5000;

    private final Path file;

    /** 用插入顺序保存，便于在没有时间戳差异时给出稳定的淘汰顺序。 */
    private final Map<ItemKey, QuarantinedDrop> entries = new LinkedHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object lock = new Object();

    /** 文件由不支持的（更高）版本写入时为 true，用于阻止覆盖。 */
    private volatile boolean unsupportedSchemaVersion;
    private volatile int loadedSchemaVersion = SCHEMA_VERSION;
    /** 首次重写从磁盘读到的文件之前需要先备份。 */
    private volatile boolean backupPending;

    /**
     * 一条被隔离的降价记录。
     *
     * @param key           物品身份
     * @param previousValue 记录中的旧值，必须 {@code >= 0}
     * @param proposedValue 拟写入的新值，必须 {@code >= 0}
     * @param dropRatio     相对降幅，必须为有限且 {@code >= 0} 的数值
     * @param reason        机器可读的判定原因，不能为 {@code null}
     * @param timestamp     隔离发生的 epoch 毫秒，必须 {@code >= 0}
     * @param approved      管理员是否已批准应用该降价
     * @param applied       降价值是否真的已经写入 {@link GeneratedEntryStore}
     */
    public record QuarantinedDrop(
            ItemKey key,
            long previousValue,
            long proposedValue,
            double dropRatio,
            String reason,
            long timestamp,
            boolean approved,
            boolean applied
    ) {

        public QuarantinedDrop {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(reason, "reason");
            if (previousValue < 0L) {
                throw new IllegalArgumentException("previousValue 必须 >= 0，实际为 " + previousValue);
            }
            if (proposedValue < 0L) {
                throw new IllegalArgumentException("proposedValue 必须 >= 0，实际为 " + proposedValue);
            }
            if (!Double.isFinite(dropRatio) || dropRatio < 0.0) {
                throw new IllegalArgumentException("dropRatio 必须为有限且 >= 0 的数值，实际为 " + dropRatio);
            }
            if (timestamp < 0L) {
                throw new IllegalArgumentException("timestamp 必须 >= 0，实际为 " + timestamp);
            }
        }

        /**
         * 兼容 Phase 21/26 早期调用点的构造器：未写入任何数值，因此 {@code applied=false}。
         */
        public QuarantinedDrop(ItemKey key, long previousValue, long proposedValue,
                               double dropRatio, String reason, long timestamp, boolean approved) {
            this(key, previousValue, proposedValue, dropRatio, reason, timestamp, approved, false);
        }

        /**
         * @param newApproved 新的审批状态
         * @return 除审批状态外其余字段完全相同的副本
         */
        public QuarantinedDrop withApproved(boolean newApproved) {
            return new QuarantinedDrop(
                    key, previousValue, proposedValue, dropRatio, reason, timestamp,
                    newApproved, applied);
        }

        /**
         * @param newApplied 是否已实际写入生成条目
         * @return 除 {@code applied} 外其余字段相同的副本；置为 true 时同时视为已批准
         */
        public QuarantinedDrop withApplied(boolean newApplied) {
            return new QuarantinedDrop(
                    key, previousValue, proposedValue, dropRatio, reason, timestamp,
                    newApplied || approved, newApplied);
        }
    }

    /**
     * 生产环境构造函数，使用 {@code config/sudoersemc/quarantined_drops.json}。
     *
     * <p>构造过程不访问磁盘；需要显式调用一次 {@link #load()}。</p>
     */
    public QuarantinedDropsStore() {
        this(SudoersemcPaths.file(FILE_NAME));
    }

    /**
     * 测试/嵌入式构造函数。
     *
     * @param file 隔离记录文件路径；父目录在保存时自动创建
     */
    public QuarantinedDropsStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * 从磁盘加载隔离记录。
     *
     * <p>文件缺失：空列表且无警告。JSON 损坏：清空内存并记录警告，原文件保留到下次覆盖前
     * 备份。更高版本：不加载任何条目，并且 {@link #save()} 拒绝覆盖。单条非法记录会被跳过；
     * 加载完成后同样执行数量上限裁剪。</p>
     */
    public void load() {
        synchronized (lock) {
            entries.clear();
            dirty.set(false);
            unsupportedSchemaVersion = false;
            loadedSchemaVersion = SCHEMA_VERSION;
            backupPending = false;

            try {
                boolean existed = Files.isRegularFile(file);
                QuarantinedDropsFileDto dto =
                        JsonFileIO.read(file, QuarantinedDropsFileDto.class);
                if (!existed) {
                    return;
                }
                // 文件已存在：首次重写前必须备份。
                backupPending = true;

                if (dto == null) {
                    LOGGER.warn("无法解析 {}，按空隔离列表处理", file);
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
                if (dto.entries == null) {
                    return;
                }

                int skipped = 0;
                for (DropDto dtoEntry : dto.entries) {
                    QuarantinedDrop drop = toDrop(dtoEntry);
                    if (drop == null) {
                        skipped++;
                        continue;
                    }
                    entries.put(drop.key(), drop);
                }
                if (skipped > 0) {
                    LOGGER.warn("加载 {} 时跳过了 {} 条格式错误的隔离记录", file, skipped);
                }
                trimToCapacity();
            } catch (RuntimeException e) {
                entries.clear();
                LOGGER.warn("加载隔离记录 {} 失败，按空列表处理", file, e);
            }
        }
    }

    /**
     * 若存在尚未写盘的变更则保存。
     *
     * @return 没有待保存变更或保存成功时为 {@code true}；保存失败（包括不支持的 schema 版本）
     *         时为 {@code false}
     */
    public boolean saveIfDirty() {
        if (dirty.get()) {
            return save();
        }
        return true;
    }

    /**
     * 按 {@link ItemKey#compareTo(ItemKey)} 升序写出全部隔离记录。
     *
     * <p>不支持的更高版本会阻止写入。首次重写已存在的文件前会生成带时间戳的 {@code .bak}。
     * 写入或备份失败时保留脏标记，调用方可在下一次 {@link #saveIfDirty()} 时重试。</p>
     *
     * @return 写入成功或无需写入时为 {@code true}；版本不受支持、备份或写入失败时为
     *         {@code false}
     */
    public boolean save() {
        synchronized (lock) {
            if (unsupportedSchemaVersion) {
                LOGGER.warn(
                        "不保存 {}：该文件由不受支持的 schemaVersion {} 写入",
                        file, loadedSchemaVersion);
                return false;
            }
            // 先清除脏标记：并发记录会重新置位，留给下一次保存。
            dirty.set(false);
            try {
                List<QuarantinedDrop> ordered = sortedByKey();

                QuarantinedDropsFileDto dto = new QuarantinedDropsFileDto();
                dto.schemaVersion = SCHEMA_VERSION;
                dto.entries = new ArrayList<>(ordered.size());
                for (QuarantinedDrop drop : ordered) {
                    dto.entries.add(DropDto.from(drop));
                }

                if (backupPending && !JsonFileIO.backupIfExists(file)) {
                    dirty.set(true);
                    LOGGER.warn("保存隔离记录到 {} 失败：无法创建备份，保留待保存状态等待重试", file);
                    return false;
                }
                if (!JsonFileIO.writeAtomically(file, dto)) {
                    dirty.set(true);
                    LOGGER.warn("保存隔离记录到 {} 失败：原子写入未完成，保留待保存状态等待重试", file);
                    return false;
                }
                backupPending = false;
                return true;
            } catch (RuntimeException e) {
                dirty.set(true);
                LOGGER.warn("保存隔离记录到 {} 失败", file, e);
                return false;
            }
        }
    }

    /**
     * 记录一条隔离降价。
     *
     * <p>同一物品已有记录时直接替换，保证一个物品最多一条待审记录。若新旧记录表示同一次
     * 经济判定（旧值、拟写入值、降幅与原因完全相同），则保留已有审批状态，避免下一次扫描
     * 静默清除管理员审批；拟写入值等发生变化时视为新的隔离事件，审批状态重置为未批准。
     * 写入后若超过上限，会按时间戳淘汰最旧的条目。{@code null} 会被忽略。</p>
     *
     * @param drop 隔离记录；构造时已经完成数值校验
     */
    public void record(QuarantinedDrop drop) {
        if (drop == null) {
            return;
        }
        synchronized (lock) {
            try {
                QuarantinedDrop existing = entries.get(drop.key());
                if (existing != null && existing.approved() && sameDecision(existing, drop)) {
                    drop = drop.withApproved(true);
                }
                entries.put(drop.key(), drop);
                trimToCapacity();
                dirty.set(true);
            } catch (RuntimeException e) {
                LOGGER.warn("记录隔离降价失败：{}", drop.key(), e);
            }
        }
    }

    /** 判断两条记录是否描述同一次经济判定；时间戳与审批状态不参与比较。 */
    private static boolean sameDecision(QuarantinedDrop left, QuarantinedDrop right) {
        return left.previousValue() == right.previousValue()
                && left.proposedValue() == right.proposedValue()
                && Double.compare(left.dropRatio(), right.dropRatio()) == 0
                && left.reason().equals(right.reason());
    }

    /**
     * 批准某个物品当前的隔离记录。
     *
     * @param key 物品身份，可为 {@code null}
     * @return 记录存在时返回 {@code true}（已是批准状态也返回 {@code true}）；不存在时返回
     *         {@code false}
     */
    public boolean approve(ItemKey key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            QuarantinedDrop existing = entries.get(key);
            if (existing == null) {
                return false;
            }
            if (existing.approved()) {
                return true;
            }
            entries.put(key, existing.withApproved(true));
            dirty.set(true);
            return true;
        }
    }

    /**
     * 在锁内读取某个物品当前的隔离记录。
     *
     * @param key 物品身份，可为 {@code null}
     * @return 记录存在时的不可变快照；不存在、key 为 {@code null} 或读取失败时为空
     */
    public Optional<QuarantinedDrop> find(ItemKey key) {
        if (key == null) {
            return Optional.empty();
        }
        synchronized (lock) {
            try {
                return Optional.ofNullable(entries.get(key));
            } catch (RuntimeException e) {
                LOGGER.warn("读取隔离降价失败：{}", key, e);
                return Optional.empty();
            }
        }
    }

    /**
     * 把某条隔离记录标记为“已批准但未应用”。与 {@link #approve(ItemKey)} 语义相同，命名更明确，
     * 供审批后不立即应用的审计模式使用。
     *
     * @param key 物品身份，可为 {@code null}
     * @return 记录存在时返回 {@code true}（已是批准状态也返回 {@code true}）
     */
    public boolean markApproved(ItemKey key) {
        return approve(key);
    }

    /**
     * 把某条隔离记录标记为“已批准且已应用”。只有生成条目确认落盘后才应调用；
     * 该标记跨重启保留，避免历史记录或审计模式记录被误判为“已应用”。
     *
     * @param key 物品身份，可为 {@code null}
     * @return 记录存在时返回 {@code true}；不存在时返回 {@code false}
     */
    public boolean markApplied(ItemKey key) {
        if (key == null) {
            return false;
        }
        synchronized (lock) {
            QuarantinedDrop existing = entries.get(key);
            if (existing == null) {
                return false;
            }
            if (existing.applied() && existing.approved()) {
                return true;
            }
            entries.put(key, existing.withApplied(true));
            dirty.set(true);
            return true;
        }
    }

    /**
     * @return 按 {@link ItemKey#compareTo(ItemKey)} 升序排列的不可变快照；内部错误时返回空列表
     */
    public List<QuarantinedDrop> entries() {
        synchronized (lock) {
            try {
                return List.copyOf(sortedByKey());
            } catch (RuntimeException e) {
                LOGGER.warn("快照隔离记录失败", e);
                return List.of();
            }
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }

    /**
     * 清空内存中的隔离记录并标记为待保存。
     *
     * <p>即使列表本来就是空的也会置脏，因为“清空”本身是一次显式变更请求。</p>
     */
    public void clear() {
        synchronized (lock) {
            try {
                entries.clear();
                dirty.set(true);
            } catch (RuntimeException e) {
                LOGGER.warn("清空隔离记录失败", e);
            }
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

    /** 调用方必须持有 {@link #lock}。 */
    private List<QuarantinedDrop> sortedByKey() {
        List<QuarantinedDrop> ordered = new ArrayList<>(entries.values());
        ordered.sort((left, right) -> left.key().compareTo(right.key()));
        return ordered;
    }

    /** 调用方必须持有 {@link #lock}。按时间戳从最旧开始淘汰，直到不超过上限。 */
    private void trimToCapacity() {
        int excess = entries.size() - DEFAULT_MAX_ENTRIES;
        if (excess <= 0) {
            return;
        }
        if (excess == 1) {
            // 记录路径每次最多超出一条，单次扫描比排序更便宜。
            removeOldestEntry();
            return;
        }
        // 批量裁剪（例如加载手工放大的文件）只排序一次，避免最坏 O(n²)。
        List<QuarantinedDrop> byTimestamp = new ArrayList<>(entries.values());
        byTimestamp.sort((left, right) -> Long.compare(left.timestamp(), right.timestamp()));
        for (int i = 0; i < excess; i++) {
            entries.remove(byTimestamp.get(i).key());
        }
    }

    /** 调用方必须持有 {@link #lock}。 */
    private void removeOldestEntry() {
        ItemKey oldestKey = null;
        long oldestTimestamp = Long.MAX_VALUE;
        for (QuarantinedDrop candidate : entries.values()) {
            if (oldestKey == null || candidate.timestamp() < oldestTimestamp) {
                oldestKey = candidate.key();
                oldestTimestamp = candidate.timestamp();
            }
        }
        if (oldestKey != null) {
            entries.remove(oldestKey);
        }
    }

    /** 把一条磁盘记录转换为内存记录；任何字段非法时返回 {@code null}。 */
    private static QuarantinedDrop toDrop(DropDto dto) {
        if (dto == null || dto.item == null || dto.item.isBlank()) {
            return null;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(dto.item);
        if (itemId == null) {
            return null;
        }
        try {
            return new QuarantinedDrop(
                    ItemKey.of(itemId, dto.variant),
                    dto.previousValue,
                    dto.proposedValue,
                    dto.dropRatio,
                    dto.reason,
                    dto.timestamp,
                    dto.approved,
                    dto.applied);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 磁盘文档。字段顺序固定，保证隔离记录每次写出的字节顺序一致。
     */
    static final class QuarantinedDropsFileDto {
        int schemaVersion;
        List<DropDto> entries = new ArrayList<>();
    }

    /** 隔离列表中的单条记录。 */
    static final class DropDto {
        String item;
        String variant;
        long previousValue;
        long proposedValue;
        double dropRatio;
        String reason;
        long timestamp;
        boolean approved;
        boolean applied;

        static DropDto from(QuarantinedDrop drop) {
            DropDto dto = new DropDto();
            dto.item = drop.key().itemId().toString();
            dto.variant = drop.key().variantHash();
            dto.previousValue = drop.previousValue();
            dto.proposedValue = drop.proposedValue();
            dto.dropRatio = drop.dropRatio();
            dto.reason = drop.reason();
            dto.timestamp = drop.timestamp();
            dto.approved = drop.approved();
            dto.applied = drop.applied();
            return dto;
        }
    }
}
