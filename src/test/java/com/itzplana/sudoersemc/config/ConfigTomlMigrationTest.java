package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ConfigTomlMigration} 的纯文件测试。全部使用 JUnit 临时目录与 NightConfig，
 * 不启动 Forge、不依赖真实配置路径。
 */
class ConfigTomlMigrationTest {

    @TempDir
    Path tempDir;

    @Test
    void schemaVersionSpecDefaultsToLegacyOne() {
        // 默认值必须是 legacy 版本，否则 Forge 会为缺失键写入 2，旧配置将永远不会被迁移。
        assertEquals(ConfigMigrations.LEGACY_SCHEMA_VERSION,
                SudoersemcConfig.SCHEMA_VERSION.getDefault());
    }

    @Test
    void legacyTomlMigratesAndCreatesBackup() throws IOException {
        Path file = tempDir.resolve("sudoersemc-common.toml");
        String original = """
                # top comment
                [overflow]
                threshold = 123
                # custom comment
                [unknown]
                futureKey = "keep"
                """;
        Files.writeString(file, original, StandardCharsets.UTF_8);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.MIGRATED, outcome.status());
        assertEquals(1, outcome.fromVersion());
        assertEquals(ConfigMigrations.SCHEMA_VERSION, outcome.toVersion());
        assertNotNull(outcome.backup());
        assertEquals(original, Files.readString(outcome.backup(), StandardCharsets.UTF_8));

        try (CommentedFileConfig toml = load(file)) {
            assertEquals(2, ((Number) toml.get("schemaVersion")).intValue());
            assertEquals(123L, ((Number) toml.get("overflow.threshold")).longValue());
            assertEquals("owned_only", toml.get("overflow.overwriteScope"));
            assertEquals("keep", toml.get("unknown.futureKey"));
        }
        String migrated = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(migrated.contains("# top comment"), "顶部注释必须保留");
        assertTrue(migrated.contains("# custom comment"), "条目注释必须保留");
        assertEquals(1, countBackups(file));
        assertFalse(Files.exists(temp(file)), "临时文件必须被清理");
    }

    @Test
    void currentSchemaVersionIsNoOp() throws IOException {
        Path file = tempDir.resolve("current.toml");
        Files.writeString(file, """
                schemaVersion = 2

                [overflow]
                threshold = 999
                """, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        long modified = Files.getLastModifiedTime(file).toMillis();

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.NO_OP, outcome.status());
        assertEquals(2, outcome.fromVersion());
        assertNull(outcome.backup());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(modified, Files.getLastModifiedTime(file).toMillis());
        assertEquals(0, countBackups(file));
        assertFalse(Files.exists(temp(file)));
    }

    @Test
    void newerSchemaVersionIsRefused() throws IOException {
        Path file = tempDir.resolve("future.toml");
        Files.writeString(file, """
                schemaVersion = 3
                futureKey = "x"

                [overflow]
                threshold = 5
                """, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.REFUSED_NEWER, outcome.status());
        assertEquals(3, outcome.fromVersion());
        assertEquals(3, outcome.toVersion());
        assertNull(outcome.backup());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(0, countBackups(file));
        assertFalse(Files.exists(temp(file)));
    }

    @Test
    void oversizedSchemaVersionIsRefusedWithoutWrite() throws IOException {
        Path file = tempDir.resolve("oversized-version.toml");
        Files.writeString(file, """
                schemaVersion = 9999999999
                futureKey = "keep"

                [overflow]
                threshold = 5
                """, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.REFUSED_NEWER, outcome.status());
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(0, countBackups(file));
        assertFalse(Files.exists(temp(file)));
    }

    @Test
    void nonNumericSchemaVersionIsRetained() throws IOException {
        Path file = tempDir.resolve("non-numeric-version.toml");
        String text = """
                schemaVersion = "not-a-number"

                [overflow]
                threshold = 5
                """;
        Files.writeString(file, text, StandardCharsets.UTF_8);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.FAILED_RETAINED, outcome.status());
        assertEquals(text, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(0, countBackups(file));
        assertFalse(Files.exists(temp(file)));
    }

    @Test
    void nonPositiveSchemaVersionIsRetained() throws IOException {
        Path negative = tempDir.resolve("negative-version.toml");
        String negativeText = """
                schemaVersion = -1

                [overflow]
                threshold = 5
                """;
        Files.writeString(negative, negativeText, StandardCharsets.UTF_8);
        ConfigTomlMigration.MigrationOutcome negativeOutcome =
                ConfigTomlMigration.migrate(negative);
        assertEquals(ConfigTomlMigration.Status.FAILED_RETAINED, negativeOutcome.status());
        assertEquals(negativeText, Files.readString(negative, StandardCharsets.UTF_8));

        Path zero = tempDir.resolve("zero-version.toml");
        String zeroText = "schemaVersion = 0\n";
        Files.writeString(zero, zeroText, StandardCharsets.UTF_8);
        ConfigTomlMigration.MigrationOutcome zeroOutcome = ConfigTomlMigration.migrate(zero);
        assertEquals(ConfigTomlMigration.Status.FAILED_RETAINED, zeroOutcome.status());
        assertEquals(zeroText, Files.readString(zero, StandardCharsets.UTF_8));
        assertEquals(0, countBackups(negative));
        assertEquals(0, countBackups(zero));
    }

    @Test
    void malformedTomlIsRetained() throws IOException {
        Path broken = tempDir.resolve("broken.toml");
        String text = "not = [valid";
        Files.writeString(broken, text, StandardCharsets.UTF_8);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(broken);

        assertEquals(ConfigTomlMigration.Status.FAILED_RETAINED, outcome.status());
        assertEquals(text, Files.readString(broken, StandardCharsets.UTF_8));
        assertEquals(0, countBackups(broken));
        assertFalse(Files.exists(temp(broken)));

        Path empty = tempDir.resolve("empty.toml");
        Files.writeString(empty, "", StandardCharsets.UTF_8);
        ConfigTomlMigration.MigrationOutcome emptyOutcome = ConfigTomlMigration.migrate(empty);
        assertEquals(ConfigTomlMigration.Status.FAILED_RETAINED, emptyOutcome.status());
        assertEquals("", Files.readString(empty, StandardCharsets.UTF_8));
        assertFalse(Files.exists(temp(empty)));
    }

    @Test
    void backupFailureAbortsReplace() throws IOException {
        Path file = tempDir.resolve("backup-failure.toml");
        String original = """
                [overflow]
                threshold = 7
                """;
        Files.writeString(file, original, StandardCharsets.UTF_8);
        ConfigTomlMigration.FileOps ops = delegate(null);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file, ops);

        assertEquals(ConfigTomlMigration.Status.FAILED_RETAINED, outcome.status());
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
        assertFalse(Files.exists(temp(file)));
    }

    @Test
    void atomicMoveFallbackStillWritesValidFile() throws IOException {
        Path file = tempDir.resolve("atomic-fallback.toml");
        Files.writeString(file, """
                [overflow]
                threshold = 42
                """, StandardCharsets.UTF_8);
        ConfigTomlMigration.FileOps ops = delegateWithAtomicFailure();

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file, ops);

        assertEquals(ConfigTomlMigration.Status.MIGRATED, outcome.status());
        try (CommentedFileConfig toml = load(file)) {
            assertEquals(2, ((Number) toml.get("schemaVersion")).intValue());
            assertEquals(42L, ((Number) toml.get("overflow.threshold")).longValue());
        }
        assertFalse(Files.exists(temp(file)));
    }

    @Test
    void migrationIsIdempotentAcrossReloads() throws IOException {
        Path file = tempDir.resolve("idempotent.toml");
        Files.writeString(file, """
                [overflow]
                threshold = 10
                """, StandardCharsets.UTF_8);

        ConfigTomlMigration.MigrationOutcome first = ConfigTomlMigration.migrate(file);
        byte[] afterFirst = Files.readAllBytes(file);
        ConfigTomlMigration.MigrationOutcome second = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.MIGRATED, first.status());
        assertEquals(ConfigTomlMigration.Status.NO_OP, second.status());
        assertEquals(1, countBackups(file));
        assertArrayEquals(afterFirst, Files.readAllBytes(file));
        assertFalse(Files.exists(temp(file)));
    }

    @Test
    void existingOverflowValuesAreNotOverwritten() throws IOException {
        Path file = tempDir.resolve("existing-values.toml");
        Files.writeString(file, """
                [overflow]
                # custom
                threshold = 5000
                overwriteScope = "entire_custom_emc"
                """, StandardCharsets.UTF_8);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.MIGRATED, outcome.status());
        try (CommentedFileConfig toml = load(file)) {
            assertEquals(5000L, ((Number) toml.get("overflow.threshold")).longValue());
            assertEquals("entire_custom_emc", toml.get("overflow.overwriteScope"));
            assertEquals(2, ((Number) toml.get("schemaVersion")).intValue());
        }
        assertTrue(Files.readString(file, StandardCharsets.UTF_8).contains("# custom"));
    }

    @Test
    void keyMappingUsesOverflowPathsOnly() throws IOException {
        Path file = tempDir.resolve("mapping.toml");
        Files.writeString(file, """
                [overflow]
                threshold = 321
                overwriteScope = "owned_only"
                """, StandardCharsets.UTF_8);

        ConfigTomlMigration.MigrationOutcome outcome = ConfigTomlMigration.migrate(file);

        assertEquals(ConfigTomlMigration.Status.MIGRATED, outcome.status());
        try (CommentedFileConfig toml = load(file)) {
            assertEquals(321L, ((Number) toml.get("overflow.threshold")).longValue());
            assertEquals("owned_only", toml.get("overflow.overwriteScope"));
            assertNull(toml.get("threshold"), "不得写出根级 threshold");
            assertNull(toml.get("overwriteScope"), "不得写出根级 overwriteScope");
        }
    }

    private static CommentedFileConfig load(Path file) {
        CommentedFileConfig toml = CommentedFileConfig.builder(file)
                .preserveInsertionOrder()
                .build();
        toml.load();
        return toml;
    }

    private static Path temp(Path target) {
        return target.resolveSibling(target.getFileName() + ".migration.tmp");
    }

    private static long countBackups(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        String prefix = file.getFileName() + ".";
        try (Stream<Path> files = Files.list(parent)) {
            return files.filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(prefix) && name.endsWith(".bak");
            }).count();
        }
    }

    private static ConfigTomlMigration.FileOps delegate(Path forcedBackup) {
        return new ConfigTomlMigration.FileOps() {
            @Override
            public boolean isRegularFile(Path path) {
                return Files.isRegularFile(path);
            }

            @Override
            public Path backup(Path target) {
                return forcedBackup;
            }

            @Override
            public void copy(Path source, Path target) throws IOException {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void moveAtomic(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void moveReplace(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void deleteIfExists(Path path) throws IOException {
                Files.deleteIfExists(path);
            }
        };
    }

    private static ConfigTomlMigration.FileOps delegateWithAtomicFailure() {
        return new ConfigTomlMigration.FileOps() {
            @Override
            public boolean isRegularFile(Path path) {
                return Files.isRegularFile(path);
            }

            @Override
            public Path backup(Path target) throws IOException {
                Path backup = target.resolveSibling(
                        target.getFileName() + "." + System.currentTimeMillis() + ".bak");
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
                return backup;
            }

            @Override
            public void copy(Path source, Path target) throws IOException {
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void moveAtomic(Path source, Path target) throws IOException {
                throw new AtomicMoveNotSupportedException(source.toString(), target.toString(),
                        "test");
            }

            @Override
            public void moveReplace(Path source, Path target) throws IOException {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }

            @Override
            public void deleteIfExists(Path path) throws IOException {
                Files.deleteIfExists(path);
            }
        };
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}
