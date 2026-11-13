package com.itzplana.sudoersemc.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link CommandResult} 的不可变性与工厂方法语义测试。
 */
class CommandResultTest {

    @Test
    void okVarargsCreatesSuccessResult() {
        CommandResult result = CommandResult.ok("第一行", "第二行");

        assertTrue(result.success());
        assertEquals(List.of("第一行", "第二行"), result.lines());
    }

    @Test
    void okListCreatesSuccessResultAndCopiesInput() {
        List<String> source = new ArrayList<>(List.of("状态正常"));
        CommandResult result = CommandResult.ok(source);

        source.add("调用方后续修改");

        assertTrue(result.success());
        assertEquals(List.of("状态正常"), result.lines());
    }

    @Test
    void failureCreatesFailureResultWithSingleLine() {
        CommandResult result = CommandResult.failure("扫描服务不可用");

        assertFalse(result.success());
        assertEquals(List.of("扫描服务不可用"), result.lines());
        assertEquals("扫描服务不可用", result.joinLines());
    }

    @Test
    void linesAreImmutable() {
        CommandResult result = CommandResult.ok("固定内容");

        assertThrows(UnsupportedOperationException.class, () -> result.lines().add("追加内容"));
    }

    @Test
    void joinLinesUsesNewlineSeparator() {
        CommandResult result = CommandResult.ok("物品: minecraft:stone", "EMC : 1", "来源: RECIPE");

        assertEquals("物品: minecraft:stone\nEMC : 1\n来源: RECIPE", result.joinLines());
    }

    @Test
    void nullLinesBecomeEmptyList() {
        CommandResult constructorNull = new CommandResult(true, null);
        CommandResult listNull = CommandResult.ok((List<String>) null);
        CommandResult arrayNull = CommandResult.ok((String[]) null);

        assertTrue(constructorNull.lines().isEmpty());
        assertTrue(listNull.lines().isEmpty());
        assertTrue(arrayNull.lines().isEmpty());
        assertEquals("", constructorNull.joinLines());
    }

    @Test
    void nullElementsAreToleratedAndDropped() {
        List<String> source = new ArrayList<>();
        source.add("有效行");
        source.add(null);

        CommandResult result = CommandResult.ok(source);

        assertEquals(List.of("有效行"), result.lines());
    }

    @Test
    void failureWithNullMessageHasNoLines() {
        CommandResult result = CommandResult.failure(null);

        assertFalse(result.success());
        assertTrue(result.lines().isEmpty());
        assertEquals("", result.joinLines());
    }
}
