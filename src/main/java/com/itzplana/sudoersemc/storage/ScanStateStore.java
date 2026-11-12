package com.itzplana.sudoersemc.storage;

import com.itzplana.sudoersemc.task.ScanFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 增量扫描状态的持久化存储。
 *
 * <p>对应文件 {@code config/sudoersemc/scan_state.json}，保存上一次扫描使用的输入指纹、扫描时刻、
 * 耗时以及已解析/未解析物品数量。启动时把当前指纹与 {@link #matches(ScanFingerprint)} 比较，
 * 结果一致即可跳过重复的全量扫描。</p>
 *
 * <h2>并发约定</h2>
 * <p>所有可变字段都由私有锁保护；{@link #update(ScanFingerprint, long, int, int)}、
 * {@link #load()} 与 {@link #save()} 可以在扫描线程和保存线程上并发调用，读取方看到的永远是一组
 * 完整的状态值，而不是更新到一半的字段组合。</p>
 *
 * <h2>失败行为</h2>
 * <p>{@link #load()}、{@link #save()} 与 {@link #update(ScanFingerprint, long, int, int)} 都不会
 * 向调用方抛出异常。文件缺失按空状态处理；文件损坏时丢弃内存状态并记录警告；文件由更高的
 * {@code schemaVersion} 写入时既不加载也不覆盖，避免破坏新版本数据。</p>
 */
public final class ScanStateStore {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/storage/ScanStateStore");

    /** 当前磁盘格式版本。 */
    public static final int SCHEMA_VERSION = 1;

    /** 相对于 {@link SudoersemcPaths#dataDir()} 的文件名。 */
    public static final String FILE_NAME = "scan_state.json";

    private final Path file;

    private final Object lock = new Object();
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    /** 以下字段全部由 {@link #lock} 保护。 */
    private ScanFingerprint storedFingerprint;
    private long lastScanEpochMillis;
    private long lastScanDurationMillis;
    private int lastSolvedCount;
    private int lastUnresolvedCount;

    /** 文件由不支持的（更高）版本写入时为 true，用于阻止覆盖。 */
    private volatile boolean unsupportedSchemaVersion;
    private volatile int loadedSchemaVersion = SCHEMA_VERSION;
    /** 首次重写从磁盘读到的文件之前需要先备份。 */
    private volatile boolean backupPending;

    /**
     * 生产环境构造函数，使用 {@code config/sudoersemc/scan_state.json}。
     *
     * <p>构造过程不访问磁盘；启动阶段需要显式调用一次 {@link #load()}。</p>
     */
    public ScanStateStore() {
        this(SudoersemcPaths.file(FILE_NAME));
    }

    /**
     * 测试/嵌入式构造函数。
     *
     * @param file 状态文件路径；父目录在保存时自动创建
     */
    public ScanStateStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * 从磁盘加载扫描状态。
     *
     * <p>文件缺失：保持空状态且不产生警告。JSON 损坏：清空内存状态并记录一条警告，原始字节
     * 保留在磁盘上，下次保存前会先备份。未知的更高 {@code schemaVersion}：不加载任何字段，
     * 并且 {@link #save()} 拒绝覆盖该文件。负数的计数与耗时会被归零，避免把损坏数据带入报告。</p>
     */
    public void load() {
        synchronized (lock) {
            clearStateLocked();
            dirty.set(false);
            unsupportedSchemaVersion = false;
            loadedSchemaVersion = SCHEMA_VERSION;
            backupPending = false;

            try {
                boolean existed = Files.isRegularFile(file);
                ScanStateFileDto dto = JsonFileIO.read(file, ScanStateFileDto.class);
                if (!existed) {
                    return;
                }
                // 文件确实存在：之后的任何重写都要先留下备份。
                backupPending = true;

                if (dto == null) {
                    LOGGER.warn("无法解析 {}，按空扫描状态处理", file);
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
                if (dto.fingerprint != null) {
                    storedFingerprint = new ScanFingerprint(
                            dto.fingerprint.item,
                            dto.fingerprint.recipe,
                            dto.fingerprint.tag,
                            dto.fingerprint.config,
                            dto.fingerprint.baseline);
                }
                lastScanEpochMillis = Math.max(0L, dto.lastScanEpochMillis);
                lastScanDurationMillis = Math.max(0L, dto.lastScanDurationMillis);
                lastSolvedCount = Math.max(0, dto.lastSolvedCount);
                lastUnresolvedCount = Math.max(0, dto.lastUnresolvedCount);
            } catch (RuntimeException e) {
                clearStateLocked();
                LOGGER.warn("加载扫描状态 {} 失败，按空状态处理", file, e);
            }
        }
    }

    /**
     * 若存在尚未写盘的更新则保存。
     *
     * <p>与 {@link #save()} 的区别只是多一次脏标记判断，语义完全一致。</p>
     */
    public void saveIfDirty() {
        if (dirty.get()) {
            save();
        }
    }

    /**
     * 以原子替换方式写入当前扫描状态。
     *
     * <p>加载时发现的不支持版本会阻止本次写入，文件内容保持原样。写入前若文件是本次进程首次
     * 重写的对象，会先生成一份带时间戳的 {@code .bak} 备份。写入或备份失败时保留脏标记与
     * 首次备份标记，下一次 {@link #saveIfDirty()} 会重试。</p>
     */
    public void save() {
        synchronized (lock) {
            if (unsupportedSchemaVersion) {
                LOGGER.warn(
                        "不保存 {}：该文件由不受支持的 schemaVersion {} 写入",
                        file, loadedSchemaVersion);
                return;
            }
            // 先清除脏标记：与保存并发的更新会重新置位，从而被下一次保存捕获。
            dirty.set(false);
            try {
                ScanStateFileDto dto = new ScanStateFileDto();
                dto.schemaVersion = SCHEMA_VERSION;
                if (storedFingerprint != null) {
                    FingerprintDto fingerprint = new FingerprintDto();
                    fingerprint.item = storedFingerprint.itemFingerprint();
                    fingerprint.recipe = storedFingerprint.recipeFingerprint();
                    fingerprint.tag = storedFingerprint.tagFingerprint();
                    fingerprint.config = storedFingerprint.configFingerprint();
                    fingerprint.baseline = storedFingerprint.baselineFingerprint();
                    dto.fingerprint = fingerprint;
                }
                dto.lastScanEpochMillis = lastScanEpochMillis;
                dto.lastScanDurationMillis = lastScanDurationMillis;
                dto.lastSolvedCount = lastSolvedCount;
                dto.lastUnresolvedCount = lastUnresolvedCount;

                if (backupPending && !JsonFileIO.backupIfExists(file)) {
                    dirty.set(true);
                    LOGGER.warn("保存扫描状态到 {} 失败：无法创建备份，保留待保存状态等待重试", file);
                    return;
                }
                if (!JsonFileIO.writeAtomically(file, dto)) {
                    dirty.set(true);
                    return;
                }
                backupPending = false;
            } catch (RuntimeException e) {
                dirty.set(true);
                LOGGER.warn("保存扫描状态到 {} 失败", file, e);
            }
        }
    }

    /**
     * 记录一次扫描的结果并标记为待保存。
     *
     * <p>扫描时刻取当前系统时间；耗时与两个计数中的负数会被归零。{@code fingerprint} 为
     * {@code null} 时忽略本次调用，因为缺少指纹的状态无法用于增量判断。</p>
     *
     * @param fingerprint     本次扫描使用的输入指纹
     * @param durationMillis  本次扫描耗时毫秒数
     * @param solvedCount     本次已解析物品数量
     * @param unresolvedCount 本次仍未解析的物品数量
     */
    public void update(ScanFingerprint fingerprint, long durationMillis, int solvedCount, int unresolvedCount) {
        if (fingerprint == null) {
            return;
        }
        try {
            synchronized (lock) {
                storedFingerprint = fingerprint;
                lastScanEpochMillis = System.currentTimeMillis();
                lastScanDurationMillis = Math.max(0L, durationMillis);
                lastSolvedCount = Math.max(0, solvedCount);
                lastUnresolvedCount = Math.max(0, unresolvedCount);
                dirty.set(true);
            }
        } catch (RuntimeException e) {
            LOGGER.warn("更新扫描状态失败", e);
        }
    }

    /**
     * @return 已保存的指纹；从未保存过时返回五个通道均为 {@link ScanFingerprint#EMPTY} 的指纹
     */
    public ScanFingerprint fingerprint() {
        synchronized (lock) {
            if (storedFingerprint != null) {
                return storedFingerprint;
            }
        }
        return ScanFingerprint.of(null, null, null, null, null);
    }

    /**
     * 判断候选指纹是否与已保存指纹描述同一组扫描输入。
     *
     * @param candidate 待比较指纹，可为 {@code null}
     * @return 已保存指纹存在且五个通道全部相等时返回 {@code true}；没有保存过指纹时返回
     *         {@code false}
     */
    public boolean matches(ScanFingerprint candidate) {
        if (candidate == null) {
            return false;
        }
        synchronized (lock) {
            return storedFingerprint != null && storedFingerprint.equalsFingerprint(candidate);
        }
    }

    /**
     * 比较候选指纹与已保存指纹，并返回发生变化的通道名称。
     *
     * <p>该 API 用于启动时的可观测诊断：调用方可以据此记录“为什么没有跳过扫描”。
     * 没有保存过指纹时 {@code stored=false}，且 {@code changedChannels} 为五个通道名称。</p>
     *
     * @param candidate 候选指纹，可为 {@code null}
     * @return 不可变比较结果
     */
    public FingerprintComparison compare(ScanFingerprint candidate) {
        synchronized (lock) {
            if (storedFingerprint == null) {
                return new FingerprintComparison(false, false,
                        List.of("item", "recipe", "tag", "config", "baseline"));
            }
            if (candidate == null) {
                return new FingerprintComparison(true, false,
                        List.of("item", "recipe", "tag", "config", "baseline"));
            }
            List<String> changed = storedFingerprint.changedChannels(candidate);
            return new FingerprintComparison(true, changed.isEmpty(), changed);
        }
    }

    /**
     * 指纹比较结果。
     *
     * @param stored         是否已经保存过指纹
     * @param matches        五个通道是否全部一致
     * @param changedChannels 发生变化的通道名称；完全一致时为空
     */
    public record FingerprintComparison(boolean stored, boolean matches, List<String> changedChannels) {

        public FingerprintComparison {
            changedChannels = List.copyOf(changedChannels);
        }
    }

    /** @return 上一次扫描完成时的 epoch 毫秒；从未记录时为 0 */
    public long lastScanEpochMillis() {
        synchronized (lock) {
            return lastScanEpochMillis;
        }
    }

    /** @return 上一次扫描耗时毫秒数；从未记录时为 0 */
    public long lastScanDurationMillis() {
        synchronized (lock) {
            return lastScanDurationMillis;
        }
    }

    /** @return 上一次扫描已解析的物品数量；从未记录时为 0 */
    public int lastSolvedCount() {
        synchronized (lock) {
            return lastSolvedCount;
        }
    }

    /** @return 上一次扫描仍未解析的物品数量；从未记录时为 0 */
    public int lastUnresolvedCount() {
        synchronized (lock) {
            return lastUnresolvedCount;
        }
    }

    /** @return 是否存在尚未写盘的更新 */
    public boolean isDirty() {
        return dirty.get();
    }

    /** @return 该存储使用的文件，主要用于诊断与测试 */
    public Path file() {
        return file;
    }

    /** 把内存状态恢复为“没有保存过任何扫描”的形态；不改变备份/版本标记。 */
    private void clearStateLocked() {
        storedFingerprint = null;
        lastScanEpochMillis = 0L;
        lastScanDurationMillis = 0L;
        lastSolvedCount = 0;
        lastUnresolvedCount = 0;
    }

    /**
     * 磁盘文档。字段声明顺序即 Gson 的写出顺序，保持稳定以便 diff。
     */
    static final class ScanStateFileDto {
        int schemaVersion;
        FingerprintDto fingerprint;
        long lastScanEpochMillis;
        long lastScanDurationMillis;
        int lastSolvedCount;
        int lastUnresolvedCount;
    }

    /** 五个输入通道的磁盘表示。 */
    static final class FingerprintDto {
        String item;
        String recipe;
        String tag;
        String config;
        String baseline;
    }
}
