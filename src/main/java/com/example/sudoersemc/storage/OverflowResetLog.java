package com.example.sudoersemc.storage;

import com.example.sudoersemc.core.ItemKey;
import com.example.sudoersemc.core.OverflowScope;
import com.example.sudoersemc.safety.OverflowReset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Append-only audit log of overflow resets, persisted to
 * {@code config/sudoersemc/overflow_resets.json}.
 *
 * <p>The log is kept in memory and rewritten atomically on {@link #save()}. History is capped:
 * once more than {@code maxEntries} records exist the oldest are dropped, so a pathological server
 * can never grow the file without bound.</p>
 *
 * <h2>Threading model</h2>
 * <p>{@link #record(OverflowReset)}, {@link #entries()} and {@link #save()} synchronize on a
 * private lock. {@code save()} snapshots the records under the lock and performs I/O outside it,
 * so recording a reset never waits on disk. A reset recorded during a save is written by the next
 * save, which is acceptable for an audit trail that is flushed on server stop.</p>
 *
 * <h2>Failure behaviour</h2>
 * <p>The constructor and all public methods swallow I/O and parsing problems. A corrupt file
 * yields an empty log after a single warning; a file from a newer schema version is preserved and
 * never overwritten.</p>
 */
public final class OverflowResetLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/storage/OverflowResetLog");

    /** Current on-disk schema version. */
    public static final int SCHEMA_VERSION = 1;

    /** Default maximum number of retained reset records. */
    public static final int DEFAULT_MAX_ENTRIES = 5000;

    /** File name inside {@link SudoersemcPaths#dataDir()}. */
    public static final String FILE_NAME = "overflow_resets.json";

    private final Path file;
    private final int maxEntries;
    private final Object lock = new Object();
    private final ArrayDeque<OverflowReset> resets = new ArrayDeque<>();

    private volatile boolean unsupportedSchemaVersion;
    private volatile int loadedSchemaVersion = SCHEMA_VERSION;

    /**
     * Production constructor; loads {@code config/sudoersemc/overflow_resets.json} immediately.
     */
    public OverflowResetLog() {
        this(SudoersemcPaths.file(FILE_NAME));
    }

    /**
     * Loads the given file immediately; a missing file starts an empty log.
     *
     * @param file backing JSON file
     */
    public OverflowResetLog(Path file) {
        this(file, DEFAULT_MAX_ENTRIES);
    }

    /**
     * @param file       backing JSON file
     * @param maxEntries maximum retained records; must be at least 1
     */
    public OverflowResetLog(Path file, int maxEntries) {
        this.file = Objects.requireNonNull(file, "file");
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be >= 1, got " + maxEntries);
        }
        this.maxEntries = maxEntries;
        load();
    }

    private void load() {
        try {
            boolean existed = Files.isRegularFile(file);
            ResetsFileDto dto = JsonFileIO.read(file, ResetsFileDto.class);
            if (!existed || dto == null) {
                // Missing file is normal. A corrupt file was already logged by JsonFileIO; start
                // empty and let the next save replace it.
                return;
            }
            if (dto.schemaVersion != SCHEMA_VERSION) {
                unsupportedSchemaVersion = true;
                loadedSchemaVersion = dto.schemaVersion;
                LOGGER.warn(
                        "{} uses unsupported schemaVersion {} (this build supports {}); "
                                + "keeping the file untouched and refusing to save over it",
                        file, dto.schemaVersion, SCHEMA_VERSION);
                return;
            }
            if (dto.resets == null) {
                return;
            }
            int skipped = 0;
            synchronized (lock) {
                for (ResetDto dtoReset : dto.resets) {
                    OverflowReset reset = toReset(dtoReset);
                    if (reset == null) {
                        skipped++;
                        continue;
                    }
                    resets.addLast(reset);
                    trimToLimit();
                }
            }
            if (skipped > 0) {
                LOGGER.warn("Skipped {} malformed overflow reset records while loading {}", skipped, file);
            }
        } catch (RuntimeException e) {
            synchronized (lock) {
                resets.clear();
            }
            LOGGER.warn("Failed to load overflow reset log from {}; starting empty", file, e);
        }
    }

    /**
     * Appends a reset record to the in-memory history.
     *
     * <p>Does not touch the disk; call {@link #save()} when the surrounding operation completes.
     * A {@code null} reset and any unexpected runtime failure are ignored.</p>
     *
     * @param reset reset to append
     */
    public void record(OverflowReset reset) {
        if (reset == null) {
            return;
        }
        try {
            synchronized (lock) {
                resets.addLast(reset);
                trimToLimit();
            }
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to record overflow reset for {}", reset.item(), e);
        }
    }

    /**
     * @return an immutable snapshot of the retained records, oldest first
     */
    public List<OverflowReset> entries() {
        synchronized (lock) {
            return List.copyOf(resets);
        }
    }

    /**
     * @return the configured maximum number of retained records
     */
    public int maxEntries() {
        return maxEntries;
    }

    /**
     * @return the backing file, mainly for diagnostics and tests
     */
    public Path file() {
        return file;
    }

    /**
     * Writes the retained records with an atomic replace.
     *
     * <p>No-op when the loaded file had an unsupported schema version.</p>
     */
    public void save() {
        if (unsupportedSchemaVersion) {
            LOGGER.warn(
                    "Not saving {} because it was written with unsupported schemaVersion {}",
                    file, loadedSchemaVersion);
            return;
        }
        List<OverflowReset> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(resets);
        }
        try {
            ResetsFileDto dto = new ResetsFileDto();
            dto.schemaVersion = SCHEMA_VERSION;
            dto.resets = new ArrayList<>(snapshot.size());
            for (OverflowReset reset : snapshot) {
                dto.resets.add(ResetDto.from(reset));
            }
            JsonFileIO.writeAtomically(file, dto);
        } catch (RuntimeException e) {
            LOGGER.warn("Failed to save overflow reset log to {}", file, e);
        }
    }

    private void trimToLimit() {
        while (resets.size() > maxEntries) {
            resets.removeFirst();
        }
    }

    private static OverflowReset toReset(ResetDto dto) {
        if (dto == null || dto.item == null || dto.item.isBlank() || dto.scope == null) {
            return null;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(dto.item);
        if (itemId == null) {
            return null;
        }
        OverflowScope scope;
        try {
            scope = OverflowScope.valueOf(dto.scope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        try {
            return new OverflowReset(
                    ItemKey.of(itemId, dto.variant),
                    dto.originalValue,
                    dto.resetValue,
                    dto.threshold,
                    scope,
                    dto.timestamp);
        } catch (RuntimeException e) {
            // Invalid numeric relationship (e.g. resetValue > originalValue): skip the record.
            return null;
        }
    }

    /**
     * On-disk document. Field order is deliberate: Gson writes declared fields in order, which
     * keeps diffs of this file stable.
     */
    static final class ResetsFileDto {
        int schemaVersion;
        List<ResetDto> resets = new ArrayList<>();
    }

    /** One entry in the on-disk reset array. */
    static final class ResetDto {
        String item;
        String variant;
        long originalValue;
        long resetValue;
        long threshold;
        String scope;
        long timestamp;

        static ResetDto from(OverflowReset reset) {
            ResetDto dto = new ResetDto();
            dto.item = reset.item().itemId().toString();
            dto.variant = reset.item().variantHash();
            dto.originalValue = reset.originalValue();
            dto.resetValue = reset.resetValue();
            dto.threshold = reset.threshold();
            dto.scope = reset.scope().name();
            dto.timestamp = reset.timestamp();
            return dto;
        }
    }
}
