package com.itzplana.sudoersemc.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Defensive helpers for the small JSON files SudoersEMC owns.
 *
 * <p>Every operation is fail-soft: a missing, corrupt or unwritable file is logged and reported
 * through a return value, never through an exception. This is intentional because these files are
 * written during world saves and server shutdown, where throwing would either abort the save or
 * crash the server.</p>
 *
 * <p>Serialization uses pretty printing, {@code serializeNulls()} and the field declaration order
 * of explicitly defined DTO classes, which keeps the on-disk format stable and diff friendly.</p>
 */
public final class JsonFileIO {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/storage");

    private static final Gson GSON = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .create();

    private static final String TEMP_SUFFIX = ".tmp";
    private static final String BACKUP_SUFFIX = ".bak";

    private JsonFileIO() {
    }

    /**
     * Reads a JSON document.
     *
     * @param file file to read
     * @param type target type
     * @param <T>  target type
     * @return the parsed value, or {@code null} if the file does not exist, is empty, is
     *         malformed, or cannot be read; the reason is logged as a warning
     */
    public static <T> T read(Path file, Class<T> type) {
        if (file == null || type == null) {
            return null;
        }
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                return GSON.fromJson(reader, type);
            }
        } catch (JsonParseException e) {
            LOGGER.warn("Malformed JSON in {}: {}", file, e.getMessage());
            return null;
        } catch (IOException e) {
            LOGGER.warn("Could not read JSON file {}: {}", file, e.getMessage());
            return null;
        } catch (RuntimeException e) {
            LOGGER.warn("Unexpected error reading JSON file {}", file, e);
            return null;
        }
    }

    /**
     * Writes a JSON document to a temporary sibling file and moves it over the target.
     *
     * <p>This guarantees that a crash mid-write never leaves a truncated document behind. If the
     * file system does not support atomic moves the write still succeeds, but is no longer
     * crash-atomic.</p>
     *
     * @param file destination
     * @param data object to serialize; must not be {@code null}
     * @return {@code true} when the document was written and moved into place; {@code false} when
     *         the arguments are invalid or any write/serialization step failed
     */
    public static boolean writeAtomically(Path file, Object data) {
        if (file == null || data == null) {
            LOGGER.warn("Refusing to write JSON: file={} data={}", file, data);
            return false;
        }
        Path fileName = file.getFileName();
        if (fileName == null) {
            LOGGER.warn("Refusing to write JSON to a path without a file name: {}", file);
            return false;
        }

        Path temp = file.resolveSibling(fileName + TEMP_SUFFIX);
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (Writer writer = Files.newBufferedWriter(
                    temp,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                GSON.toJson(data, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                LOGGER.debug("Atomic move not supported for {}; falling back to a plain replace", file);
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Failed to write JSON file {}", file, e);
            deleteQuietly(temp);
            return false;
        }
    }

    /**
     * Copies an existing file to {@code <name>.<epochMillis>.bak}.
     *
     * <p>Called before the first destructive rewrite of a file that was loaded from disk, so a
     * bug or a partially generated dataset never permanently destroys the previous content. No-op
     * if the source does not exist. Failures are logged and reported through the return value.</p>
     *
     * @param file file to back up
     * @return {@code true} if the file did not exist or was copied successfully; {@code false}
     *         when an existing file could not be copied
     */
    public static boolean backupIfExists(Path file) {
        if (file == null) {
            return false;
        }
        try {
            if (!Files.isRegularFile(file)) {
                return true;
            }
            Path fileName = file.getFileName();
            if (fileName == null) {
                return true;
            }
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path backup = file.resolveSibling(fileName + "." + System.currentTimeMillis() + BACKUP_SUFFIX);
            Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            LOGGER.debug("Backed up {} to {}", file, backup);
            return true;
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not back up {} before rewriting it", file, e);
            return false;
        }
    }

    private static void deleteQuietly(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            Files.deleteIfExists(path);
        } catch (IOException | RuntimeException ignored) {
            // Best effort only; the stale .tmp file is harmless and gets overwritten next time.
        }
    }
}
