package com.itzplana.sudoersemc.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置 schema 迁移。
 *
 * <p>当前构建支持 schema 版本 {@link #SCHEMA_VERSION}，旧版本为
 * {@link #LEGACY_SCHEMA_VERSION}。迁移只负责把原始键值映射补齐到当前版本，
 * 不读取配置文件、也不负责写盘。调用方在拿到 {@link MigrationResult} 后，
 * 应以「临时文件 + 原子替换」的方式持久化结果，避免写到一半留下损坏文件。</p>
 *
 * <p>迁移永远不会修改传入的映射：即使无需变更，也会返回一个独立副本。</p>
 */
public final class ConfigMigrations {

    /** 当前构建支持的配置 schema 版本。 */
    public static final int SCHEMA_VERSION = 2;

    /** 引入 schema 版本字段之前的旧版本号。 */
    public static final int LEGACY_SCHEMA_VERSION = 1;

    private static final String KEY_THRESHOLD = "threshold";
    private static final String KEY_OVERWRITE_SCOPE = "overwriteScope";
    private static final long DEFAULT_OVERFLOW_THRESHOLD = 999_999L;
    private static final String DEFAULT_OVERWRITE_SCOPE = "owned_only";

    private ConfigMigrations() {
    }

    /**
     * 迁移结果。
     *
     * <p>{@code values} 与 {@code notes} 在构造时被复制为不可变视图，调用方修改原集合
     * 不会影响本对象。</p>
     *
     * @param values    迁移后的键值映射
     * @param toVersion 迁移后的 schema 版本
     * @param notes     面向日志的中文迁移说明，按发生顺序排列
     */
    public record MigrationResult(Map<String, Object> values, int toVersion, List<String> notes) {

        public MigrationResult {
            Map<String, Object> copiedValues = new LinkedHashMap<>();
            if (values != null) {
                copiedValues.putAll(values);
            }
            values = Collections.unmodifiableMap(copiedValues);
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    /**
     * 把指定版本的原始配置迁移到当前 schema。
     *
     * <p>版本处理规则：</p>
     * <ul>
     *   <li>高于当前构建支持的版本：保持原样并记录提示，防止旧构建覆盖新配置；</li>
     *   <li>版本号小于等于 0（未知）：保持原样，按当前版本处理并记录提示；</li>
     *   <li>v1 → v2：缺少 {@code threshold} 时补充 {@code 999999}，缺少
     *       {@code overwriteScope} 时补充 {@code owned_only}，已有值不覆盖；</li>
     *   <li>当前版本：不做修改，也不产生提示。</li>
     * </ul>
     *
     * @param fromVersion 原始配置声明的 schema 版本
     * @param rawValues   原始键值映射，允许为 {@code null}（按空映射处理）
     * @return 迁移结果；{@code values} 始终是新的独立映射
     */
    public static MigrationResult migrate(int fromVersion, Map<String, Object> rawValues) {
        Map<String, Object> source = rawValues == null ? Map.of() : rawValues;

        if (fromVersion > SCHEMA_VERSION) {
            return new MigrationResult(
                    source,
                    fromVersion,
                    List.of("配置版本 " + fromVersion + " 高于当前构建支持的 "
                            + SCHEMA_VERSION + "，保持原样不做修改"));
        }

        if (fromVersion <= 0) {
            return new MigrationResult(
                    source,
                    SCHEMA_VERSION,
                    List.of("未知配置版本，按当前版本处理"));
        }

        if (fromVersion == LEGACY_SCHEMA_VERSION) {
            Map<String, Object> migrated = new LinkedHashMap<>(source);
            List<String> notes = new ArrayList<>(2);
            if (!migrated.containsKey(KEY_THRESHOLD)) {
                migrated.put(KEY_THRESHOLD, DEFAULT_OVERFLOW_THRESHOLD);
                notes.add("v1→v2：补充 overflow.threshold 默认值 999999");
            }
            if (!migrated.containsKey(KEY_OVERWRITE_SCOPE)) {
                migrated.put(KEY_OVERWRITE_SCOPE, DEFAULT_OVERWRITE_SCOPE);
                notes.add("v1→v2：补充 overwriteScope 默认值 owned_only");
            }
            return new MigrationResult(migrated, SCHEMA_VERSION, notes);
        }

        return new MigrationResult(source, SCHEMA_VERSION, List.of());
    }
}
