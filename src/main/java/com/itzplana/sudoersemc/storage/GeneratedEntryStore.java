package com.itzplana.sudoersemc.storage;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.GeneratedValueSource;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OwnedEntrySink;
import com.itzplana.sudoersemc.core.PriceSource;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.itzplana.sudoersemc.core.VariantRegistration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent record of every EMC value SudoersEMC generated or adopted.
 *
 * <p>Backed by {@code config/sudoersemc/generated_entries.json}. The file lists <em>all</em> entries,
 * including ones SudoersEMC does not own (for example values adopted from ProjectE or written by
 * a human). Only entries marked {@code owned=true} may later be updated or removed by this mod;
 * {@link #stage(GeneratedEmcEntry)} therefore rejects attempts to overwrite a non-owned entry.</p>
 *
 * <h2>Threading model</h2>
 * <p>The in-memory map is a {@link ConcurrentHashMap}: {@link #stage(GeneratedEmcEntry)},
 * {@link #removeOwned(ItemKey)}, {@link #isOwned(ItemKey)} and the snapshot methods may be called
 * from any thread, typically the server thread. {@link #save()} and {@link #load()} serialize on a
 * private lock; only one writer is ever active. Snapshots return immutable copies and are safe to
 * iterate while another thread stages entries. A stage that races with {@link #save()} is either
 * included in that save or leaves the store dirty for the next one, never lost.</p>
 *
 * <h2>Failure behaviour</h2>
 * <p>{@link #load()}, {@link #save()}, {@link #stage(GeneratedEmcEntry)} and
 * {@link #removeOwned(ItemKey)} never throw. Corrupt entries are skipped individually. A file
 * written by a newer schema version is preserved untouched: it is neither loaded nor overwritten.
 * Before the first rewrite of a file that was read from disk, a timestamped {@code .bak} copy is
 * made. A failed write keeps the store dirty and leaves the one-time backup flag set, so a later
 * {@link #saveIfDirty()} retries; an existing file is never overwritten without a successful
 * backup.</p>
 */
public final class GeneratedEntryStore implements OwnedEntrySink, GeneratedValueSource {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/storage/GeneratedEntryStore");

    /**
     * 当前磁盘 schema 版本。
     *
     * <p>版本 1 不包含备选成本；版本 2 为每个条目新增 {@code alternatives} 数组。读取时兼容
     * 版本 1，但只会写出当前版本；更高版本仍然拒绝覆盖。</p>
     */
    public static final int SCHEMA_VERSION = 2;

    /** File name inside {@link SudoersemcPaths#dataDir()}. */
    public static final String FILE_NAME = "generated_entries.json";

    private final Path file;

    private final ConcurrentHashMap<ItemKey, GeneratedEmcEntry> entries = new ConcurrentHashMap<>();
    /**
     * 变体身份到观测到的原始 SNBT 列表。
     *
     * <p>{@link ItemKey#variantHash()} 是规范化后的经济身份，ProjectE 注册需要完整 NBT。
     * 该映射只对变体条目有内容，并与 {@link #entries} 同步落盘。</p>
     */
    private final ConcurrentHashMap<ItemKey, List<String>> variantNbts = new ConcurrentHashMap<>();
    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final Object loadSaveLock = new Object();

    /** Set when the file was written by an unsupported (newer) schema version; blocks save(). */
    private volatile boolean unsupportedSchemaVersion;
    private volatile int loadedSchemaVersion = SCHEMA_VERSION;
    /** True until the first save of a file that already existed on disk. */
    private volatile boolean backupPending;

    /**
     * Production constructor; resolves {@code config/sudoersemc/generated_entries.json}.
     *
     * <p>Construction does not touch the disk. Call {@link #load()} once during startup.</p>
     */
    public GeneratedEntryStore() {
        this(SudoersemcPaths.file(FILE_NAME));
    }

    /**
     * Test/embedded constructor.
     *
     * @param file backing JSON file; the parent directory is created on save
     */
    public GeneratedEntryStore(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * Loads the backing file into memory.
     *
     * <p>文件缺失：空状态且无警告。JSON 损坏：空状态并记录警告，原始字节保留到下次覆盖前
     * 备份。{@code schemaVersion} 为 1 时按无备选成本加载，为 2 时读取备选成本；更高或非法
     * 的版本不加载任何条目，且 {@link #save()} 拒绝覆盖原文件。单条格式错误的记录会被跳过。</p>
     */
    public void load() {
        synchronized (loadSaveLock) {
            entries.clear();
            variantNbts.clear();
            dirty.set(false);
            unsupportedSchemaVersion = false;
            loadedSchemaVersion = SCHEMA_VERSION;
            backupPending = false;

            try {
                boolean existed = Files.isRegularFile(file);
                EntriesFileDto dto = JsonFileIO.read(file, EntriesFileDto.class);
                if (!existed) {
                    return;
                }
                // From here on the file exists: any rewrite must be preceded by a backup.
                backupPending = true;

                if (dto == null) {
                    LOGGER.warn("Could not parse {}; starting with an empty generated-entry set", file);
                    return;
                }
                // 版本 1 是旧格式（没有备选成本），版本 2 是当前格式。两者都可读，
                // 但只会写出当前版本；更高或非法的版本保持原文件不动。
                if (dto.schemaVersion < 1 || dto.schemaVersion > SCHEMA_VERSION) {
                    unsupportedSchemaVersion = true;
                    loadedSchemaVersion = dto.schemaVersion;
                    backupPending = false;
                    LOGGER.warn(
                            "{} uses unsupported schemaVersion {} (this build supports 1..{}); "
                                    + "keeping the file untouched and refusing to save over it",
                            file, dto.schemaVersion, SCHEMA_VERSION);
                    return;
                }
                boolean includeAlternatives = dto.schemaVersion >= 2;
                if (dto.entries == null) {
                    return;
                }

                int skipped = 0;
                for (EntryDto dtoEntry : dto.entries) {
                    GeneratedEmcEntry entry = toEntry(dtoEntry, includeAlternatives);
                    if (entry == null) {
                        skipped++;
                        continue;
                    }
                    entries.put(entry.item(), entry);
                    List<String> observedNbts = VariantRegistration.sanitizeObserved(dtoEntry.variantNbts);
                    if (entry.item().hasVariant() && !observedNbts.isEmpty()) {
                        variantNbts.put(entry.item(), observedNbts);
                    }
                }
                if (skipped > 0) {
                    LOGGER.warn("Skipped {} malformed entries while loading {}", skipped, file);
                }
            } catch (RuntimeException e) {
                entries.clear();
                variantNbts.clear();
                LOGGER.warn("Failed to load generated entries from {}; starting empty", file, e);
            }
        }
    }

    /**
     * Writes the current state if at least one entry changed since the last save.
     *
     * @return 没有待写内容或写入成功时返回 {@code true}；写入失败返回 {@code false}
     */
    public boolean saveIfDirty() {
        if (!dirty.get()) {
            return true;
        }
        return save();
    }

    /**
     * Writes all entries, ordered by {@link ItemKey#compareTo(ItemKey)}, using an atomic replace.
     *
     * <p>No-op when the loaded file had an unsupported schema version, because overwriting it
     * could destroy data produced by a newer build.</p>
     *
     * @return 写入成功返回 {@code true}；schema 不受支持或写入失败返回 {@code false}
     */
    public boolean save() {
        synchronized (loadSaveLock) {
            if (unsupportedSchemaVersion) {
                LOGGER.warn(
                        "Not saving {} because it was written with unsupported schemaVersion {}",
                        file, loadedSchemaVersion);
                return false;
            }
            // Clear first: a stage that races with this save sets the flag again and is picked up
            // by the next save instead of being silently dropped.
            dirty.set(false);
            try {
                List<GeneratedEmcEntry> ordered = new ArrayList<>(entries.values());
                ordered.sort((left, right) -> left.item().compareTo(right.item()));

                EntriesFileDto dto = new EntriesFileDto();
                dto.schemaVersion = SCHEMA_VERSION;
                dto.entries = new ArrayList<>(ordered.size());
                for (GeneratedEmcEntry entry : ordered) {
                    dto.entries.add(EntryDto.from(entry, variantNbts.get(entry.item())));
                }

                if (backupPending && !JsonFileIO.backupIfExists(file)) {
                    dirty.set(true);
                    LOGGER.warn(
                            "Failed to save generated entries to {}: could not create a backup; "
                                    + "keeping the store dirty for a later retry",
                            file);
                    return false;
                }
                if (!JsonFileIO.writeAtomically(file, dto)) {
                    dirty.set(true);
                    return false;
                }
                backupPending = false;
                return true;
            } catch (RuntimeException e) {
                dirty.set(true);
                LOGGER.warn("Failed to save generated entries to {}", file, e);
                return false;
            }
        }
    }

    @Override
    public boolean stage(GeneratedEmcEntry entry) {
        return stage(entry, List.of());
    }

    /**
     * 暂存一个生成值，并记录该变体观测到的原始 SNBT。
     *
     * <p>原始 SNBT 只在 {@link ItemKey#hasVariant()} 时保存；它用于 ProjectE 的精确 NBT
     * 注册，避免规范化后的经济身份匹配不到带展示字段的真实堆叠。传入空列表时保留已有
     * 原始值，防止一次缺少观测数据的刷新擦掉可用的注册信息。</p>
     *
     * @param entry      生成值条目
     * @param rawVariantNbts 观测到的原始 SNBT，允许为 {@code null}
     * @return {@code true} 表示条目被接受
     */
    public boolean stage(GeneratedEmcEntry entry, List<String> rawVariantNbts) {
        if (entry == null || entry.item() == null || entry.value() < 0) {
            return false;
        }
        try {
            GeneratedEmcEntry existing = entries.get(entry.item());
            if (existing != null && !existing.owned()) {
                LOGGER.debug(
                        "Rejected staged value for {} because the existing entry is not owned by SudoersEMC",
                        entry.item());
                return false;
            }
            entries.put(entry.item(), entry);
            if (entry.item().hasVariant()) {
                List<String> sanitized = VariantRegistration.sanitizeObserved(rawVariantNbts);
                if (!sanitized.isEmpty()) {
                    variantNbts.put(entry.item(), sanitized);
                }
            } else {
                variantNbts.remove(entry.item());
            }
            dirty.set(true);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to stage generated EMC entry for {}", entry.item(), e);
            return false;
        }
    }

    /**
     * Removes an entry, but only if SudoersEMC owns it.
     *
     * @param key item to remove
     * @return {@code true} if an owned entry was removed
     */
    public boolean removeOwned(ItemKey key) {
        if (key == null) {
            return false;
        }
        try {
            GeneratedEmcEntry existing = entries.get(key);
            if (existing == null || !existing.owned()) {
                return false;
            }
            entries.remove(key);
            variantNbts.remove(key);
            dirty.set(true);
            return true;
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to remove generated EMC entry for {}", key, e);
            return false;
        }
    }

    @Override
    public Map<ItemKey, GeneratedEmcEntry> snapshotAll() {
        try {
            return Map.copyOf(entries);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to snapshot generated EMC entries", e);
            return Map.of();
        }
    }

    @Override
    public Map<ItemKey, Long> snapshotOwnedValues() {
        try {
            Map<ItemKey, Long> owned = new ConcurrentHashMap<>();
            for (Map.Entry<ItemKey, GeneratedEmcEntry> entry : entries.entrySet()) {
                GeneratedEmcEntry value = entry.getValue();
                if (value.owned()) {
                    owned.put(entry.getKey(), value.value());
                }
            }
            return Map.copyOf(owned);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to snapshot owned EMC values", e);
            return Map.of();
        }
    }

    @Override
    public Map<ItemKey, List<String>> snapshotOwnedVariantNbts() {
        try {
            Map<ItemKey, List<String>> owned = new ConcurrentHashMap<>();
            for (Map.Entry<ItemKey, GeneratedEmcEntry> entry : entries.entrySet()) {
                if (!entry.getValue().owned()) {
                    continue;
                }
                List<String> observed = variantNbts.get(entry.getKey());
                if (observed != null && !observed.isEmpty()) {
                    owned.put(entry.getKey(), observed);
                }
            }
            return Map.copyOf(owned);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to snapshot owned variant NBT registrations", e);
            return Map.of();
        }
    }

    @Override
    public boolean isOwned(ItemKey key) {
        if (key == null) {
            return false;
        }
        GeneratedEmcEntry entry = entries.get(key);
        return entry != null && entry.owned();
    }

    /**
     * Looks up a single entry without copying the whole map.
     *
     * <p>Used by the {@code /sudoemc explain} command so a large generated-entry table is not
     * snapshotted merely to read one item.</p>
     *
     * @param key item identity; {@code null} returns {@code null}
     * @return the current entry, or {@code null} when absent
     */
    public GeneratedEmcEntry get(ItemKey key) {
        return key == null ? null : entries.get(key);
    }

    /**
     * @return number of entries currently held in memory
     */
    public int size() {
        return entries.size();
    }

    /**
     * @return {@code true} if there are staged changes that have not been written yet
     */
    public boolean isDirty() {
        return dirty.get();
    }

    /**
     * @return the backing file, mainly for diagnostics and tests
     */
    public Path file() {
        return file;
    }

    private static GeneratedEmcEntry toEntry(EntryDto dto, boolean includeAlternatives) {
        if (dto == null || dto.item == null || dto.item.isBlank()) {
            return null;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(dto.item);
        if (itemId == null) {
            return null;
        }
        PriceSource source = parseSource(dto.source);
        if (source == null) {
            return null;
        }
        if (dto.value < 0) {
            return null;
        }
        ResourceLocation recipeId = null;
        if (dto.recipe != null && !dto.recipe.isBlank()) {
            // An unparseable recipe id does not make the value itself invalid; drop the reference.
            recipeId = ResourceLocation.tryParse(dto.recipe);
        }
        List<Long> alternativeCosts = includeAlternatives
                ? parseAlternativeCosts(dto.alternatives)
                : List.of();
        try {
            return new GeneratedEmcEntry(ItemKey.of(itemId, dto.variant), dto.value, source,
                    recipeId, dto.owned, dto.timestamp, alternativeCosts);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 解析磁盘上的备选成本数组。
     *
     * <p>字段缺失或为 {@code null} 时返回空列表；单个元素的 {@code null}、非数字或负值会被
     * 跳过，不影响同一物品的其它字段。</p>
     *
     * @param raw 原始 JSON 元素列表，可为 {@code null}
     * @return 不可变的合法备选成本列表
     */
    private static List<Long> parseAlternativeCosts(List<JsonElement> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> costs = new ArrayList<>(raw.size());
        for (JsonElement element : raw) {
            Long cost = parseAlternativeCost(element);
            if (cost != null) {
                costs.add(cost);
            }
        }
        return List.copyOf(costs);
    }

    /**
     * 把单个 JSON 元素解析为非负 long；无法解析时返回 {@code null}。
     */
    private static Long parseAlternativeCost(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        try {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            long value;
            if (primitive.isNumber()) {
                value = primitive.getAsLong();
            } else if (primitive.isString()) {
                value = Long.parseLong(primitive.getAsString().trim());
            } else {
                return null;
            }
            return value >= 0L ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static PriceSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return PriceSource.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 磁盘文档。字段顺序固定，保证 Gson 每次写出的字节顺序一致。
     *
     * <p>{@code schemaVersion=2} 时每个条目额外写出 {@code alternatives} 数组；版本 1 的旧
     * 文件没有该字段，读取时按空列表处理。</p>
     */
    static final class EntriesFileDto {
        int schemaVersion;
        List<EntryDto> entries = new ArrayList<>();
    }

    /** 磁盘文档中的单条记录。 */
    static final class EntryDto {
        String item;
        String variant;
        /**
         * 观测到的原始 SNBT，仅变体条目使用。
         *
         * <p>旧版本文件没有该字段，加载时为 {@code null}；此时回退到规范 variant 注册。</p>
         */
        List<String> variantNbts;
        long value;
        String source;
        String recipe;
        boolean owned;
        long timestamp;
        List<JsonElement> alternatives = new ArrayList<>();

        static EntryDto from(GeneratedEmcEntry entry, @Nullable List<String> observedVariantNbts) {
            EntryDto dto = new EntryDto();
            dto.item = entry.item().itemId().toString();
            dto.variant = entry.item().variantHash();
            List<String> sanitized = VariantRegistration.sanitizeObserved(observedVariantNbts);
            dto.variantNbts = sanitized.isEmpty() ? null : sanitized;
            dto.value = entry.value();
            dto.source = entry.source().name();
            dto.recipe = entry.recipeId() == null ? null : entry.recipeId().toString();
            dto.owned = entry.owned();
            dto.timestamp = entry.timestamp();
            dto.alternatives = new ArrayList<>(entry.alternativeCosts().size());
            for (Long cost : entry.alternativeCosts()) {
                dto.alternatives.add(new JsonPrimitive(cost));
            }
            return dto;
        }
    }
}
