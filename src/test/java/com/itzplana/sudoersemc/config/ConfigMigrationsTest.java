package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigMigrationsTest {

    @Test
    void schemaVersionConstantsAreStable() {
        assertEquals(2, ConfigMigrations.SCHEMA_VERSION);
        assertEquals(1, ConfigMigrations.LEGACY_SCHEMA_VERSION);
    }

    @Test
    void nullInputMapIsTolerated() {
        ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(0, null);

        assertTrue(result.values().isEmpty());
        assertEquals(ConfigMigrations.SCHEMA_VERSION, result.toVersion());
        assertEquals(List.of("未知配置版本，按当前版本处理"), result.notes());
    }

    @Test
    void unknownVersionsAreTreatedAsCurrent() {
        for (int version : new int[] {0, -1}) {
            ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(
                    version, Map.of("existing", "value"));

            assertEquals(Map.of("existing", "value"), result.values());
            assertEquals(ConfigMigrations.SCHEMA_VERSION, result.toVersion());
            assertEquals(List.of("未知配置版本，按当前版本处理"), result.notes());
        }
    }

    @Test
    void versionOneAddsBothMissingDefaults() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("existing", 42);

        ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(1, raw);

        assertEquals(ConfigMigrations.SCHEMA_VERSION, result.toVersion());
        assertEquals(42, result.values().get("existing"));
        assertEquals(999_999L, result.values().get("threshold"));
        assertEquals("owned_only", result.values().get("overwriteScope"));
        assertEquals(List.of(
                "v1→v2：补充 overflow.threshold 默认值 999999",
                "v1→v2：补充 overwriteScope 默认值 owned_only"), result.notes());
    }

    @Test
    void versionOneKeepsExistingThreshold() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("threshold", 123L);

        ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(1, raw);

        assertEquals(123L, result.values().get("threshold"));
        assertEquals("owned_only", result.values().get("overwriteScope"));
        assertEquals(List.of("v1→v2：补充 overwriteScope 默认值 owned_only"), result.notes());
    }

    @Test
    void currentVersionIsNoOp() {
        Map<String, Object> raw = Map.of("a", 1, "b", "two");

        ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(
                ConfigMigrations.SCHEMA_VERSION, raw);

        assertEquals(raw, result.values());
        assertEquals(ConfigMigrations.SCHEMA_VERSION, result.toVersion());
        assertEquals(List.of(), result.notes());
    }

    @Test
    void newerVersionIsPreservedUntouched() {
        Map<String, Object> raw = Map.of("threshold", 5L, "futureKey", "futureValue");

        ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(3, raw);

        assertEquals(raw, result.values());
        assertEquals(3, result.toVersion());
        assertEquals(
                List.of("配置版本 3 高于当前构建支持的 2，保持原样不做修改"),
                result.notes());
    }

    @Test
    void inputMapIsNeverMutated() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("existing", "value");
        Map<String, Object> snapshot = new LinkedHashMap<>(raw);

        ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(
                ConfigMigrations.LEGACY_SCHEMA_VERSION, raw);

        assertEquals(snapshot, raw);
        assertFalse(raw.containsKey("threshold"));
        assertFalse(raw.containsKey("overwriteScope"));
        assertNotSame(raw, result.values());
    }

    @Test
    void returnedValuesAreImmutable() {
        ConfigMigrations.MigrationResult result = ConfigMigrations.migrate(
                ConfigMigrations.SCHEMA_VERSION, Map.of("a", 1));

        org.junit.jupiter.api.Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> result.values().put("b", 2));
    }
}
