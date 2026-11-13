package com.itzplana.sudoersemc.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.itzplana.sudoersemc.storage.JsonFileIO;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
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
 * <p>Forge 1.20.1 没有公开的“解析前”配置钩子。本类提供两条入口：</p>
 * <ul>
 *   <li>{@link #migrate(java.nio.file.Path)}：由 {@code Sudoersemc} 在
 *       {@code SudoersemcConfig.register()} 之前直接调用磁盘文件，此时 Forge 尚未按 spec
 *       重写 TOML，因此能保留用户注释与未知键；</li>
 *   <li>{@link #migrate(ModConfig)}：在 {@code Loading}/{@code Reloading} 事件内作为
 *       reload/兜底入口，已迁移文件会返回 {@code NO_OP}。</li>
 * </ul>
 *
 * <p>迁移只读写以下三个键：</p>
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
 * 迁移且绝不覆盖。拒绝（REFUSED_NEWER）与失败（FAILED_RETAINED）路径都会在返回前尽力
 * 创建时间戳 {@code .bak}，因为返回后 Forge 仍可能按 spec 执行 {@code correct()/save()}
 * 并丢弃未来版本的未知键与注释。迁移完成后同步 Forge 内存中的 live 值与
 * {@code SudoersemcConfig.SCHEMA_VERSION}，
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
     * @param backup      成功迁移、拒绝迁移或失败保留前尽力创建的时间戳备份路径；
     *                    无法备份时为 {@code null}
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
            return failed(target, ops, -1, "迁移异常，保留原文件");
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
            return failed(target, ops, -1, "无法读取配置文件");
        }
        if (original.trim().isEmpty()) {
            // 空文件通常意味着 Forge 尚未完成首次写入；不要把它当成合法 legacy 配置。
            LOGGER.warn("[config] {} 为空文件，保留原文件等待 Forge 重新生成", target);
            return failed(target, ops, -1, "空配置文件，保留原文件");
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

                VersionRead versionRead = readVersion(toml);
                if (versionRead.status() == VersionStatus.TOO_HIGH) {
                    LOGGER.error(
                            "[config] {} 的 schemaVersion 超出本构建可表示范围；拒绝迁移并保留原文件",
                            target);
                    return refused(target, ops, Integer.MAX_VALUE, Integer.MAX_VALUE,
                            "配置版本超出当前构建可表示范围");
                }
                if (versionRead.status() == VersionStatus.INVALID) {
                    LOGGER.warn(
                            "[config] {} 的 schemaVersion 非法（必须是正整数）；保留原文件等待人工修复",
                            target);
                    return failed(target, ops, -1, "schemaVersion 非法，保留原文件");
                }
                fromVersion = versionRead.status() == VersionStatus.MISSING
                        ? ConfigMigrations.LEGACY_SCHEMA_VERSION
                        : versionRead.version();
                if (fromVersion > ConfigMigrations.SCHEMA_VERSION) {
                    LOGGER.error(
                            "[config] {} 的 schemaVersion={} 高于本构建支持的 {}；拒绝迁移并保留原文件",
                            target, fromVersion, ConfigMigrations.SCHEMA_VERSION);
                    return refused(target, ops, fromVersion, fromVersion,
                            "配置版本高于当前构建支持的 " + ConfigMigrations.SCHEMA_VERSION);
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
                    return refused(target, ops, fromVersion, result.toVersion(),
                            String.join("；", result.notes()));
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
                return failed(target, ops, fromVersion, "备份失败，保留原文件");
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
            return failed(target, ops, -1, "迁移失败，保留原文件");
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

    private enum VersionStatus {
        MISSING,
        VALID,
        TOO_HIGH,
        INVALID
    }

    private record VersionRead(VersionStatus status, int version) {
    }

    /**
     * 严格读取 {@code schemaVersion}：
     *
     * <ul>
     *   <li>键不存在或值为 0 → legacy 1（0 是 spec 下界，按未知版本抬到当前版本）；</li>
     *   <li>正整数且不超过 {@link Integer#MAX_VALUE} → 有效版本；</li>
     *   <li>数值超过 int 上限 → {@link VersionStatus#TOO_HIGH}，入口返回 REFUSED_NEWER；</li>
     *   <li>负数、非数字字符串、布尔/数组/小数等非法类型 → {@link VersionStatus#INVALID}，
     *       入口返回 FAILED_RETAINED。</li>
     * </ul>
     *
     * <p>绝不能把超大 Long 静默截断成 int：那会让旧构建误以为未来配置是当前版本并覆盖它。</p>
     */
    private static VersionRead readVersion(CommentedConfig config) {
        if (config == null || !config.contains(SCHEMA_VERSION_KEY)) {
            return new VersionRead(VersionStatus.MISSING,
                    ConfigMigrations.LEGACY_SCHEMA_VERSION);
        }
        Object raw = config.get(SCHEMA_VERSION_KEY);
        if (raw == null) {
            return new VersionRead(VersionStatus.INVALID, -1);
        }
        try {
            BigDecimal decimal = toDecimal(raw);
            if (decimal == null) {
                return new VersionRead(VersionStatus.INVALID, -1);
            }
            BigInteger integer = decimal.toBigIntegerExact();
            if (integer.signum() == 0) {
                // spec 下界是 0；0 表示“未知/legacy”，按缺失键处理并抬到当前版本，
                // 同时避免 Forge 对 0 报警。
                return new VersionRead(VersionStatus.MISSING,
                        ConfigMigrations.LEGACY_SCHEMA_VERSION);
            }
            if (integer.signum() < 0) {
                return new VersionRead(VersionStatus.INVALID, -1);
            }
            if (integer.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
                return new VersionRead(VersionStatus.TOO_HIGH, Integer.MAX_VALUE);
            }
            return new VersionRead(VersionStatus.VALID, integer.intValue());
        } catch (ArithmeticException | NumberFormatException failure) {
            // 小数、NaN/Infinity 等无法精确转为整数的输入都属于非法版本。
            return new VersionRead(VersionStatus.INVALID, -1);
        }
    }

    /**
     * 只接受真正的数值类型。
     *
     * <p>TOML 中 {@code schemaVersion = "2"} 是字符串，不是数值；Forge 的 spec 字段为
     * {@code IntValue}，随后 {@code correct()} 会把它纠正为默认 {@code 1}。因此必须把
     * quoted numeric 视为非法并 fail-closed，绝不能解析为未来版本后返回 {@code NO_OP}。</p>
     */
    @Nullable
    private static BigDecimal toDecimal(Object raw) {
        if (!(raw instanceof Number number)) {
            return null;
        }
        String text = number.toString().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(text);
        } catch (NumberFormatException failure) {
            return null;
        }
    }

    private static MigrationOutcome refused(Path target, FileOps ops, int fromVersion,
                                            int toVersion, String note) {
        List<String> notes = new ArrayList<>();
        if (note != null && !note.isBlank()) {
            notes.add(note);
        }
        Path backup = backupBeforeRewrite(target, ops, notes);
        return new MigrationOutcome(Status.REFUSED_NEWER, fromVersion, toVersion, backup, notes);
    }

    private static MigrationOutcome failed(Path target, FileOps ops, int fromVersion, String note) {
        LOGGER.warn("[config] 配置迁移未完成：{}（{}）", target, note);
        List<String> notes = new ArrayList<>();
        if (note != null && !note.isBlank()) {
            notes.add(note);
        }
        Path backup = backupBeforeRewrite(target, ops, notes);
        return new MigrationOutcome(Status.FAILED_RETAINED, fromVersion, fromVersion, backup, notes);
    }

    /**
     * 在拒绝/失败返回前尽力备份原文件。
     *
     * <p>解析前迁移返回后，Forge 仍会按 spec 执行 {@code correct()/save()}，可能丢弃未来
     * 版本的未知键与注释。备份失败不能改变迁移状态，也不能覆盖原文件。</p>
     */
    @Nullable
    private static Path backupBeforeRewrite(Path target, FileOps ops, List<String> notes) {
        try {
            Path backup = ops.backup(target);
            if (backup == null) {
                notes.add("无法创建备份，原文件保持不变");
            } else {
                notes.add("已备份到 " + backup.getFileName());
                LOGGER.warn("[config] 配置迁移未完成前已备份原始配置：{}", backup);
            }
            return backup;
        } catch (Throwable failure) {
            LOGGER.warn("[config] 备份 {} 失败，继续保留原文件", target, failure);
            notes.add("备份失败，原文件保持不变");
            return null;
        }
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
