package com.itzplana.sudoersemc.config;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 在 Forge 读取 TOML 之前应用 {@link ConfigMigrations} 的 schema 迁移。
 *
 * <p>Forge 1.20.1 没有公开的“解析前”配置钩子，因此本类必须由 {@link SudoersemcConfig#register()}
 * 在 {@code ModLoadingContext.registerConfig(...)} 之前调用。迁移只处理以下键：</p>
 *
 * <ul>
 *   <li>根级 {@code schemaVersion}；</li>
 *   <li>{@code overflow.threshold}；</li>
 *   <li>{@code overflow.overwriteScope}。</li>
 * </ul>
 *
 * <p>{@link ConfigMigrations} 使用扁平逻辑键，本类负责把它们映射到正确的 TOML 路径。迁移会
 * 保留未知键与注释，先写同目录临时文件，再尽力备份并原子替换；任何失败都保留原文件。</p>
 */
public final class ConfigFileMigration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigFileMigration.class);

    private static final String SCHEMA_VERSION_KEY = "schemaVersion";
    private static final String THRESHOLD_PATH = "overflow.threshold";
    private static final String OVERWRITE_SCOPE_PATH = "overflow.overwriteScope";
    private static final String SCHEMA_VERSION_COMMENT =
            "配置 schema 版本，请勿手动修改。当前构建支持 "
                    + ConfigMigrations.SCHEMA_VERSION + "。";

    private static final Map<String, String> LOGICAL_TO_TOML = Map.of(
            "threshold", THRESHOLD_PATH,
            "overwriteScope", OVERWRITE_SCOPE_PATH);

    private ConfigFileMigration() {
    }

    /**
     * 迁移指定配置文件。文件不存在、没有可迁移内容或版本高于当前构建时不做任何修改。
     *
     * @param configFile Forge 将读取的 TOML 文件路径
     * @return {@code true} 表示文件已被迁移并替换；{@code false} 表示未写盘
     */
    public static boolean migrate(Path configFile) {
        if (configFile == null) {
            return false;
        }
        Path fileName = configFile.getFileName();
        if (fileName == null || !Files.isRegularFile(configFile)) {
            return false;
        }

        Path temp = configFile.resolveSibling(fileName + ".migration.tmp");
        boolean replaced = false;
        int fromVersion = -1;
        try {
            Files.copy(configFile, temp, StandardCopyOption.REPLACE_EXISTING);

            ConfigMigrations.MigrationResult result;
            CommentedFileConfig toml = CommentedFileConfig
                    .builder(temp, TomlFormat.instance())
                    .preserveInsertionOrder()
                    .build();
            try {
                toml.load();

                Integer version = readVersion(toml);
                if (version == null) {
                    LOGGER.warn("[config] {} 的 schemaVersion 非法，保留原文件等待人工修复", configFile);
                    return false;
                }
                fromVersion = version;
                if (fromVersion > ConfigMigrations.SCHEMA_VERSION) {
                    LOGGER.warn(
                            "[config] {} 的 schemaVersion={} 高于本构建支持的 {}，保持原文件不变",
                            configFile, fromVersion, ConfigMigrations.SCHEMA_VERSION);
                    return false;
                }

                Map<String, Object> logical = new LinkedHashMap<>();
                Object threshold = toml.get(THRESHOLD_PATH);
                if (threshold != null) {
                    logical.put("threshold", threshold);
                }
                Object overwriteScope = toml.get(OVERWRITE_SCOPE_PATH);
                if (overwriteScope != null) {
                    logical.put("overwriteScope", overwriteScope);
                }

                result = ConfigMigrations.migrate(fromVersion, logical);
                if (result.toVersion() > ConfigMigrations.SCHEMA_VERSION) {
                    LOGGER.warn(
                            "[config] {} 的迁移目标版本 {} 高于本构建支持的 {}，保持原文件不变",
                            configFile, result.toVersion(), ConfigMigrations.SCHEMA_VERSION);
                    return false;
                }
                if (result.toVersion() == fromVersion && result.notes().isEmpty()) {
                    return false;
                }

                for (Map.Entry<String, String> mapping : LOGICAL_TO_TOML.entrySet()) {
                    Object value = result.values().get(mapping.getKey());
                    if (value != null) {
                        toml.set(mapping.getValue(), value);
                    }
                }
                toml.set(SCHEMA_VERSION_KEY, result.toVersion());
                toml.setComment(SCHEMA_VERSION_KEY, SCHEMA_VERSION_COMMENT);
                toml.save();
            } finally {
                toml.close();
            }

            Path backup = createBackup(configFile);
            replace(temp, configFile);
            replaced = true;
            LOGGER.info(
                    "[config] 已迁移 {}：v{} -> v{}；{}{}",
                    configFile,
                    fromVersion,
                    result.toVersion(),
                    String.join("；", result.notes()),
                    backup == null ? "" : "；备份=" + backup.getFileName());
            return true;
        } catch (Exception failure) {
            LOGGER.warn("[config] 迁移 {} 失败，保留原文件", configFile, failure);
            return false;
        } finally {
            if (!replaced) {
                deleteQuietly(temp);
            }
        }
    }

    /**
     * 严格读取 {@code schemaVersion}：缺失视为 legacy 1；非整数或超出 int 范围视为非法。
     * 负数与 0 会原样交给 {@link ConfigMigrations}，由后者按“未知版本”处理。
     *
     * @return 版本号；非法时返回 {@code null}
     */
    private static Integer readVersion(CommentedFileConfig toml) {
        if (!toml.contains(SCHEMA_VERSION_KEY)) {
            return ConfigMigrations.LEGACY_SCHEMA_VERSION;
        }
        Object raw = toml.get(SCHEMA_VERSION_KEY);
        if (!(raw instanceof Number number)) {
            return null;
        }
        double value = number.doubleValue();
        if (!Double.isFinite(value)
                || value != Math.rint(value)
                || value < Integer.MIN_VALUE
                || value > Integer.MAX_VALUE) {
            return null;
        }
        return (int) value;
    }

    /**
     * 尽力创建时间戳备份；备份失败不会阻止迁移，只会让回滚需要人工处理。
     */
    private static Path createBackup(Path target) {
        Path backup = target.resolveSibling(
                target.getFileName() + ".bak." + System.currentTimeMillis());
        try {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            return backup;
        } catch (IOException failure) {
            LOGGER.warn("[config] 无法备份 {}，迁移将继续但不会保留旧文件副本", target, failure);
            return null;
        }
    }

    private static void replace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException | UnsupportedOperationException unsupported) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // 临时文件残留无害，下一次迁移会覆盖。
        }
    }
}
