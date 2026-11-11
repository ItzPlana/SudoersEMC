package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigValidationResultTest {

    @Test
    void okFactoriesProduceValidResults() {
        ConfigValidationResult empty = ConfigValidationResult.ok();

        assertTrue(empty.valid());
        assertEquals(List.of(), empty.errors());
        assertEquals(List.of(), empty.warnings());
        assertFalse(empty.hasWarnings());
        assertEquals("配置校验通过", empty.format());

        ConfigValidationResult withWarnings = ConfigValidationResult.ok(List.of("w1", "w2"));

        assertTrue(withWarnings.valid());
        assertTrue(withWarnings.hasWarnings());
        assertTrue(withWarnings.format().startsWith("配置校验通过（2 条警告）"));
        assertTrue(withWarnings.format().contains("  [警告] w1"));
        assertTrue(withWarnings.format().contains("  [警告] w2"));
    }

    @Test
    void failedFactoryProducesInvalidResult() {
        ConfigValidationResult result = ConfigValidationResult.failed(List.of("e1"), List.of("w1"));

        assertFalse(result.valid());
        assertEquals(List.of("e1"), result.errors());
        assertEquals(List.of("w1"), result.warnings());

        String formatted = result.format();
        assertTrue(formatted.startsWith("配置校验失败（1 个错误，1 条警告）"));
        assertTrue(formatted.contains("  [错误] e1"));
        assertTrue(formatted.contains("  [警告] w1"));
        assertTrue(formatted.indexOf("[错误]") < formatted.indexOf("[警告]"));
    }

    @Test
    void listsAreImmutableCopies() {
        List<String> errors = new ArrayList<>(List.of("e1"));
        List<String> warnings = new ArrayList<>(List.of("w1"));

        ConfigValidationResult result = ConfigValidationResult.failed(errors, warnings);
        errors.add("e2");
        warnings.add("w2");

        assertEquals(List.of("e1"), result.errors());
        assertEquals(List.of("w1"), result.warnings());
        assertThrows(UnsupportedOperationException.class, () -> result.errors().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> result.warnings().add("x"));
    }

    @Test
    void nullListsBecomeEmpty() {
        ConfigValidationResult result = new ConfigValidationResult(true, null, null);

        assertEquals(List.of(), result.errors());
        assertEquals(List.of(), result.warnings());
        assertFalse(result.hasWarnings());
    }

    @Test
    void validResultWithErrorsIsRejected() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ConfigValidationResult(true, List.of("e1"), List.of()));
    }
}
