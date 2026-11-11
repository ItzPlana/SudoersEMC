package com.example.sudoersemc.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves the directory SudoersEMC uses for its own persisted data.
 *
 * <p>All files live under {@code config/sudoersemc/} inside the Forge instance. Keeping them
 * separate from ProjectE's own {@code config/ProjectE/} files avoids ever confusing generated
 * values with manually configured ones.</p>
 */
public final class SudoersemcPaths {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/storage");

    /** Name of the sub-directory below the Forge config directory. */
    public static final String DIRECTORY_NAME = "sudoersemc";

    /**
     * Test-only override. {@code null} restores the normal Forge resolution.
     *
     * <p>Package-private on purpose: production code must never redirect the data directory.
     * Unit tests that cannot bootstrap Forge may set this before constructing stores.</p>
     */
    private static volatile Path dataDirOverride;

    private SudoersemcPaths() {
    }

    /**
     * @return the directory holding SudoersEMC's generated data files
     */
    public static Path dataDir() {
        Path override = dataDirOverride;
        if (override != null) {
            return override;
        }
        return FMLPaths.CONFIGDIR.get().resolve(DIRECTORY_NAME);
    }

    /**
     * @param name file name relative to {@link #dataDir()}
     * @return the resolved path (the file is not created)
     */
    public static Path file(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        return dataDir().resolve(name);
    }

    /**
     * Creates {@link #dataDir()} if necessary. Logs and swallows I/O errors so startup never
     * fails only because the directory could not be created; the individual save operations
     * report their own failures.
     */
    public static void ensureDirectories() {
        try {
            Files.createDirectories(dataDir());
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("Could not create SudoersEMC data directory {}", dataDir(), e);
        }
    }

    /**
     * Test-only override of the data directory.
     *
     * <p>Never call this from production code. Passing {@code null} restores the Forge
     * config-directory based resolution.</p>
     *
     * @param directory temporary directory used by a test, or {@code null} to reset
     */
    static void setDataDirForTesting(Path directory) {
        dataDirOverride = directory;
    }
}
