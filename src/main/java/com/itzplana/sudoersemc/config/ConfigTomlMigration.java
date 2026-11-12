package com.itzplana.sudoersemc.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.itzplana.sudoersemc.storage.JsonFileIO;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraftforge.fml.config.ModConfig;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把 {@link ConfigMigrations} 的裸键迁移规则接入 Forge 的 TOML 配置文件。
 *
 * <p>Forge 1.20.1 没有公开的“解析前”配置钩子，因此本类在 {@code ModConfigEvent.Loading}
 * 与 {@code ModConfigEvent.Reloading} 事件内、运行时组件重建之前执行。迁移只读写以下三个键：</p>
 *
 * <ul>
 *   <li>{@code schemaVersion}（根级）；</li>
 *   <li>{@code overflow.threshold}；</li>
 *   <li>{@code overflow.overwriteScope}。</li>
 * </ul>
 *
 * <p>逻辑键与 TOML 路径的映射固定在 {@link #LOGICAL_TO_TOML} 中。{@link ConfigMigrations#migrate}
 * 的既有 API 与裸键语义完全不变，本类只负责把结果写到正确的 TOML 路径。</p>
 *
 * <p><strong>写盘策略：</strong>先复制原文件到同目录临时文件，用 NightConfig 就地修改已知键，
 * 保存成功后再做时间戳备份并原子替换。任何失败都保留原文件并清理临时文件；高版本配置拒绝
 * 迁移且绝不覆盖。迁移完成后同步 Forge 内存中的 live 值与 {@code SudoersemcConfig.SCHEMA_VERSION}，
 * 因此同一文件重复触发事件时第二次会得到 {@link Status#NO_OP}。</p>
 */
public final class ConfigTomlMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/config/ConfigTomlMigration");

    /** 迁移结果状态。 */
    public enum Status {
        /** 当前版本已经是本构建支持的版本，未写盘。 */
        NO_OP,
        /** 已成功迁移并写盘。 */
        MIGRATED,
        /** 文件版本高于当前构建，拒绝迁移并保留原文件。 */
        REFUSED_NEWER,
        /** 发生任何失败，原文件保持不变。 */
        FAILED_RETAINED,
        /** 没有可迁移的配置（非本模组配置、文件不存在或重入）。 */
        SKIPPED
    }

    /**
     * 一次迁移的结果。
     *
     * @param status      迁移状态
     * @param fromVersion 迁移前版本；无法读取时为 {@code -1}
     * @param toVersion   迁移后版本；失败时通常等于 {@code fromVersion}
     * @param backup      成功迁移时创建的时间戳备份路径，否则为 {@code null}
     * @param notes       面向日志的中文说明
     */
    public record MigrationOutcome(
            Status status,
            int fromVersion,
            int toVersion,
            @Nullable Path backup,
            List<String> notes) {

        public MigrationOutcome {
            Objects.requireNonNull(status, "status");
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * 逻辑键 → TOML 路径。只包含 Phase 25 需要迁移的两个键，绝不整体重写配置文件。
     */
    private static final Map<String, String> LOGICAL_TO_TOML = Map.of(
            "threshold", "overflow.threshold",
            "overwriteScope", "overflow.overwriteScope");

    private static final String SCHEMA_VERSION_KEY = "schemaVersion";

    private static final String SCHEMA_VERSION_COMMENT =
            "配置 schema 版本，请勿手动修改。当前构建支持 "
                    + ConfigMigrations.SCHEMA_VERSION + "。";

    /** 防止迁移写盘触发的二次 Reloading 事件重入。 */
    private static final AtomicBoolean MIGRATION_IN_PROGRESS = new AtomicBoolean(false);

    private ConfigTomlMigration() {
    }

    /**
     * 迁移 Forge 已加载的 COMMON 配置。只处理 {@link SudoersemcConfig#SPEC}。
     *
     * @param config Forge 配置对象，允许为 {@code null}
     * @return 迁移结果；任何异常都不会向调用方传播
     */
    public static MigrationOutcome migrate(@Nullable ModConfig config) {
        if (config == null || config.getSpec() != SudoersemcConfig.SPEC) {
            return new MigrationOutcome(Status.SKIPPED, -1, -1, null, List.of());
        }
        Path target = config.getFullPath();
        CommentedConfig live = config.getConfigData();
        if (target == null || live == null) {
            return new MigrationOutcome(Status.SKIPPED, -1, -1, null,
                    List.of("配置路径或内存数据不可用"));
        }
        return migrateInternal(target, DEFAULT_OPS, live);
    }

    /**
     * 迁移一个独立的 TOML 文件，主要用于单元测试与嵌入式场景。
     *
     * @param target TOML 文件路径，允许为 {@code null}
     * @return 迁移结果；任何异常都不会向调用方传播
     */
    public static MigrationOutcome migrate(@Nullable Path target) {
        return migrateInternal(target, DEFAULT_OPS, null);
    }

    /**
     * 使用可注入的文件操作迁移一个 TOML 文件。包内可见，供失败路径测试使用。
     */
    static MigrationOutcome migrate(@Nullable Path target, FileOps ops) {
        return migrateInternal(target, ops == null ? DEFAULT_OPS : ops, null);
    }

    private static MigrationOutcome migrateInternal(@Nullable Path target,
                                                    FileOps ops,
                                                    @Nullable CommentedConfig live) {
        if (target == null || ops == null) {
            return new MigrationOutcome(Status.SKIPPED, -1, -1, null, List.of());
        }
        if (!MIGRATION_IN_PROGRESS.compareAndSet(false, true)) {
            LOGGER.debug("[config] 已有一轮 TOML 迁移在进行，跳过 {}", target);
            return new MigrationOutcome(Status.SKIPPED, -1, -1, null,
                    List.of("已有迁移正在进行"));
        }
        try {
            return doMigrate(target, ops, live);
        } catch (Throwable failure) {
            LOGGER.warn("[config] 迁移 {} 时发生未预期异常，保留原文件", target, failure);
            return new MigrationOutcome(Status.FAILED_RETAINED, -1, -1, null,
                    List.of("迁移异常，保留原文件"));
        } finally {
            MIGRATION_IN_PROGRESS.set(false);
        }
    }

    private static MigrationOutcome doMigrate(Path target, FileOps ops,
                                              @Nullable CommentedConfig live) {
        if (!ops.isRegularFile(target)) {
            // 文件不存在时完全交给 Forge 生成，避免抢在 Forge 前面创建半成品。
            return new MigrationOutcome(Status.SKIPPED, -1, -1, null, List.of());
        }

        String original;
        try {
            original = Files.readString(target, StandardCharsets.UTF_8);
        } catch (IOException failure) {
            LOGGER.warn("[config] 无法读取 {}，保留原文件", target, failure);
            return failed(target, -1, "无法读取配置文件");
        }
        if (original.trim().isEmpty()) {
            // 空文件通常意味着 Forge 尚未完成首次写入；不要把它当成合法 legacy 配置。
            LOGGER.warn("[config] {} 为空文件，保留原文件等待 Forge 重新生成", target);
            return new MigrationOutcome(Status.FAILED_RETAINED, -1, -1, null,
                    List.of("空配置文件，保留原文件"));
        }

        Path fileName = target.getFileName();
        if (fileName == null) {
            return new MigrationOutcome(Status.SKIPPED, -1, -1, null,
                    List.of("配置路径没有文件名"));
        }
        Path temp = target.resolveSibling(fileName + ".migration.tmp");
        boolean replaced = false;
        int fromVersion = -1;
        ConfigMigrations.MigrationResult result = null;
        try {
            ops.copy(target, temp);

            CommentedFileConfig toml = CommentedFileConfig.builder(temp, TomlFormat.instance())
                    .preserveInsertionOrder()
                    .build();
            try {
                toml.load();

                fromVersion = readInt(toml, SCHEMA_VERSION_KEY,
                        ConfigMigrations.LEGACY_SCHEMA_VERSION);
                if (fromVersion > ConfigMigrations.SCHEMA_VERSION) {
                    LOGGER.error(
                            "[config] {} 的 schemaVersion={} 高于本构建支持的 {}；拒绝迁移并保留原文件",
                            target, fromVersion, ConfigMigrations.SCHEMA_VERSION);
                    return new MigrationOutcome(Status.REFUSED_NEWER, fromVersion, fromVersion,
                            null, List.of("配置版本高于当前构建支持的 "
                                    + ConfigMigrations.SCHEMA_VERSION));
                }
                if (fromVersion == ConfigMigrations.SCHEMA_VERSION) {
                    return new MigrationOutcome(Status.NO_OP, fromVersion, fromVersion,
                            null, List.of());
                }

                Map<String, Object> logical = new LinkedHashMap<>();
                for (Map.Entry<String, String> entry : LOGICAL_TO_TOML.entrySet()) {
                    Object value = toml.get(entry.getValue());
                    if (value != null) {
                        logical.put(entry.getKey(), value);
                    }
                }

                result = ConfigMigrations.migrate(fromVersion, logical);
                if (result.toVersion() > ConfigMigrations.SCHEMA_VERSION) {
                    return new MigrationOutcome(Status.REFUSED_NEWER, fromVersion,
                            result.toVersion(), null, result.notes());
                }

                for (Map.Entry<String, String> entry : LOGICAL_TO_TOML.entrySet()) {
                    Object value = result.values().get(entry.getKey());
                    if (value != null) {
                        toml.set(entry.getValue(), value);
                    }
                }
                toml.set(SCHEMA_VERSION_KEY, ConfigMigrations.SCHEMA_VERSION);
                toml.setComment(SCHEMA_VERSION_KEY, SCHEMA_VERSION_COMMENT);
                toml.save();
            } finally {
                toml.close();
            }

            Path backup = ops.backup(target);
            if (backup == null) {
                LOGGER.warn("[config] 无法备份 {}，中止迁移并保留原文件", target);
                return failed(target, fromVersion, "备份失败，保留原文件");
            }

            try {
                ops.moveAtomic(temp, target);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException unsupported) {
                LOGGER.debug("[config] 文件系统不支持原子移动，降级为普通替换：{}", target);
                ops.moveReplace(temp, target);
            }
            replaced = true;

            // 磁盘已经是新版本；尽力同步 Forge 内存，失败只影响下一次 save() 的基线。
            syncLiveValues(live, result.values());
            List<String> notes = new ArrayList<>(result.notes());
            notes.add("已备份到 " + backup.getFileName());
            return new MigrationOutcome(Status.MIGRATED, fromVersion,
                    ConfigMigrations.SCHEMA_VERSION, backup, notes);
        } catch (Throwable failure) {
            LOGGER.warn("[config] 迁移 {} 失败，保留原文件", target, failure);
            return failed(target, -1, "迁移失败，保留原文件");
        } finally {
            if (!replaced) {
                deleteQuietly(ops, temp);
            }
        }
    }

    /**
     * 磁盘写成功后同步 live 与 Forge 规格值。迁移只补默认值，因此这里的值与迁移规则一致。
     */
    private static void syncLiveValues(@Nullable CommentedConfig live,
                                       Map<String, Object> logicalValues) {
        if (live != null) {
            try {
                for (Map.Entry<String, String> entry : LOGICAL_TO_TOML.entrySet()) {
                    Object value = logicalValues.get(entry.getKey());
                    if (value != null) {
                        live.set(entry.getValue(), value);
                    }
                }
                live.set(SCHEMA_VERSION_KEY, ConfigMigrations.SCHEMA_VERSION);
            } catch (RuntimeException failure) {
                LOGGER.warn("[config] 迁移已写盘，但同步 Forge 内存配置失败；下一次重载将按文件版本处理",
                        failure);
            }
        }
        try {
            if (SudoersemcConfig.SPEC.isLoaded()) {
                SudoersemcConfig.SCHEMA_VERSION.set(ConfigMigrations.SCHEMA_VERSION);
            }
        } catch (Throwable failure) {
            LOGGER.warn("[config] 迁移已写盘，但同步 Forge schemaVersion 规格值失败", failure);
        }
    }

    static int readInt(CommentedConfig config, String key, int defaultValue) {
        if (config == null || key == null) {
            return defaultValue;
        }
        Object raw = config.get(key);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw instanceof String text) {
            try {
                return Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static MigrationOutcome failed(Path target, int fromVersion, String note) {
        LOGGER.warn("[config] 配置迁移未完成：{}（{}）", target, note);
        return new MigrationOutcome(Status.FAILED_RETAINED, fromVersion, fromVersion,
                null, List.of(note));
    }

    private static void deleteQuietly(FileOps ops, Path path) {
        try {
            ops.deleteIfExists(path);
        } catch (Throwable ignored) {
            // 临时文件残留无害，下一次迁移会覆盖。
        }
    }

    /**
     * 可注入的文件操作，便于稳定测试备份失败与原子移动降级路径。
     */
    interface FileOps {

        boolean isRegularFile(Path path);

        @Nullable
        Path backup(Path target) throws IOException;

        void copy(Path source, Path target) throws IOException;

        void moveAtomic(Path source, Path target) throws IOException;

        void moveReplace(Path source, Path target) throws IOException;

        void deleteIfExists(Path path) throws IOException;
    }

    static final FileOps DEFAULT_OPS = new FileOps() {

        @Override
        public boolean isRegularFile(Path path) {
            return Files.isRegularFile(path);
        }

        @Override
        public Path backup(Path target) {
            return JsonFileIO.backupIfExistsAndReturn(target);
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
