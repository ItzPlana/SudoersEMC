package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFileMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void legacyFileGetsSchemaVersionAndDefaults() throws IOException {
        Path file = write("""
                [general]
                enabled = true

                [overflow]
                resetMin = 100
                resetMax = 999000
                bucketCount = 100
                weightCap = 20
                randomSeed = 0
                """);

        assertTrue(ConfigFileMigration.migrate(file));

        String migrated = Files.readString(file);
        assertTrue(migrated.contains("schemaVersion = 2"));
        assertTrue(migrated.contains("threshold = 999999"));
        assertTrue(migrated.contains("overwriteScope = \"owned_only\""));
        assertTrue(migrated.contains("enabled = true"), "unknown/unrelated keys must be preserved");
    }

    @Test
    void existingOverflowValuesArePreserved() throws IOException {
        Path file = write("""
                schemaVersion = 1

                [overflow]
                threshold = 123456
                overwriteScope = "entire_custom_emc"
                """);

        assertTrue(ConfigFileMigration.migrate(file));

        String migrated = Files.readString(file);
        assertTrue(migrated.contains("schemaVersion = 2"));
        assertTrue(migrated.contains("threshold = 123456"));
        assertTrue(migrated.contains("overwriteScope = \"entire_custom_emc\""));
        assertFalse(migrated.contains("threshold = 999999"));
    }

    @Test
    void currentVersionIsLeftByteForByteUntouched() throws IOException {
        String original = """
                schemaVersion = 2

                [overflow]
                threshold = 123456
                overwriteScope = "owned_only"
                """;
        Path file = write(original);

        assertFalse(ConfigFileMigration.migrate(file));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void newerVersionIsRefused() throws IOException {
        String original = """
                schemaVersion = 3

                [overflow]
                threshold = 1
                """;
        Path file = write(original);

        assertFalse(ConfigFileMigration.migrate(file));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void missingFileIsNoOp() throws IOException {
        assertFalse(ConfigFileMigration.migrate(tempDir.resolve("absent.toml")));
    }

    @Test
    void malformedFileIsLeftUntouched() throws IOException {
        String original = "schemaVersion = 1\n[overflow\nthreshold = 1\n";
        Path file = write(original);

        assertFalse(ConfigFileMigration.migrate(file));
        assertEquals(original, Files.readString(file));
    }

    @Test
    void invalidSchemaVersionIsLeftUntouched() throws IOException {
        String original = """
                schemaVersion = "not-a-number"

                [overflow]
                threshold = 1
                """;
        Path file = write(original);

        assertFalse(ConfigFileMigration.migrate(file));
        assertEquals(original, Files.readString(file));
    }

    private Path write(String content) throws IOException {
        Path file = tempDir.resolve("sudoersemc-common.toml");
        Files.writeString(file, content);
        return file;
    }
}
