package com.itzplana.sudoersemc.storage;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.GeneratedValueSource;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OwnedEntrySink;
import com.itzplana.sudoersemc.core.PriceSource;
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

    /** Current on-disk schema version. */
    public static final int SCHEMA_VERSION = 1;

    /** File name inside {@link SudoersemcPaths#dataDir()}. */
    public static final String FILE_NAME = "generated_entries.json";

    private final Path file;

    private final ConcurrentHashMap<ItemKey, GeneratedEmcEntry> entries = new ConcurrentHashMap<>();
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
     * <p>Missing file: empty state, no warning. Corrupt JSON: empty state with a warning; the
     * original bytes stay on disk and are backed up before the next save. Unknown
     * {@code schemaVersion}: nothing is loaded and {@link #save()} refuses to overwrite the file.
     * Malformed entries are skipped individually.</p>
     */
    public void load() {
        synchronized (loadSaveLock) {
            entries.clear();
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
                if (dto.schemaVersion != SCHEMA_VERSION) {
                    unsupportedSchemaVersion = true;
                    loadedSchemaVersion = dto.schemaVersion;
                    backupPending = false;
                    LOGGER.warn(
                            "{} uses unsupported schemaVersion {} (this build supports {}); "
                                    + "keeping the file untouched and refusing to save over it",
                            file, dto.schemaVersion, SCHEMA_VERSION);
                    return;
                }
                if (dto.entries == null) {
                    return;
                }

                int skipped = 0;
                for (EntryDto dtoEntry : dto.entries) {
                    GeneratedEmcEntry entry = toEntry(dtoEntry);
                    if (entry == null) {
                        skipped++;
                        continue;
                    }
                    entries.put(entry.item(), entry);
                }
                if (skipped > 0) {
                    LOGGER.warn("Skipped {} malformed entries while loading {}", skipped, file);
                }
            } catch (RuntimeException e) {
                entries.clear();
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
                    dto.entries.add(EntryDto.from(entry));
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
    public boolean isOwned(ItemKey key) {
        if (key == null) {
            return false;
        }
        GeneratedEmcEntry entry = entries.get(key);
        return entry != null && entry.owned();
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

    private static GeneratedEmcEntry toEntry(EntryDto dto) {
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
        try {
            return new GeneratedEmcEntry(
                    ItemKey.of(itemId, dto.variant), dto.value, source, recipeId, dto.owned, dto.timestamp);
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
     * On-disk document. Field order is deliberate: Gson writes declared fields in order, which
     * keeps diffs of this file stable.
     */
    static final class EntriesFileDto {
        int schemaVersion;
        List<EntryDto> entries = new ArrayList<>();
    }

    /** One entry in the on-disk document. */
    static final class EntryDto {
        String item;
        String variant;
        long value;
        String source;
        String recipe;
        boolean owned;
        long timestamp;

        static EntryDto from(GeneratedEmcEntry entry) {
            EntryDto dto = new EntryDto();
            dto.item = entry.item().itemId().toString();
            dto.variant = entry.item().variantHash();
            dto.value = entry.value();
            dto.source = entry.source().name();
            dto.recipe = entry.recipeId() == null ? null : entry.recipeId().toString();
            dto.owned = entry.owned();
            dto.timestamp = entry.timestamp();
            return dto;
        }
    }
}
