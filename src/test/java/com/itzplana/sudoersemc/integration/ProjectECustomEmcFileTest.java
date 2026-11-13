package com.itzplana.sudoersemc.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import java.util.stream.Stream;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ProjectECustomEmcFile} 的单元测试。
 *
 * <p>所有用例都使用 {@link TempDir} 中的文件，不接触真实配置目录。测试覆盖阈值边界、未知字段
 * 保留、备份、非自有条目策略、幂等性以及缺失/损坏文件的安全行为。</p>
 */
class ProjectECustomEmcFileTest {

    private static final long THRESHOLD = 1_000L;
    private static final long RESET_MIN = 100L;
    private static final long RESET_MAX = 900L;

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");

    @TempDir
    Path tempDir;

    @Test
    void onlyValuesAboveThresholdAreRewritten() throws IOException {
        Path file = write("custom_emc.json", """
                {
                  "entries": [
                    { "item": "minecraft:stone", "emc": 1001 },
                    { "item": "minecraft:dirt", "emc": 1000 },
                    { "item": "minecraft:apple", "emc": 5 }
                  ]
                }
                """);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> true);

        assertEquals(1, result.rewritten());
        assertEquals(0, result.skipped());
        assertEquals(0, result.notOwned());
        assertTrue(result.wroteFile());
        assertNotNull(result.backup());

        JsonArray entries = readObject(file).getAsJsonArray("entries");
        long stone = entries.get(0).getAsJsonObject().get("emc").getAsLong();
        assertTrue(stone >= RESET_MIN && stone <= RESET_MAX,
                "stone 必须回落到 [" + RESET_MIN + ", " + RESET_MAX + "]，实际为 " + stone);
        assertEquals(1000L, entries.get(1).getAsJsonObject().get("emc").getAsLong(),
                "等于阈值的条目不得改写");
        assertEquals(5L, entries.get(2).getAsJsonObject().get("emc").getAsLong());
    }

    @Test
    void unknownFieldsAndKeyOrderArePreserved() throws IOException {
        Path file = write("custom_emc.json", """
                {
                  "entries": [
                    { "item": "minecraft:stone", "emc": 5000, "note": "keep", "priority": 7 }
                  ],
                  "comment": "keep me",
                  "nested": { "flag": true, "name": "projecte" }
                }
                """);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> true);
        assertTrue(result.wroteFile());

        String rewritten = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(rewritten).getAsJsonObject();
        assertEquals("keep me", root.get("comment").getAsString());
        assertTrue(root.getAsJsonObject("nested").get("flag").getAsBoolean());

        JsonObject entry = root.getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("minecraft:stone", entry.get("item").getAsString());
        assertEquals("keep", entry.get("note").getAsString());
        assertEquals(7, entry.get("priority").getAsInt());
        assertTrue(rewritten.indexOf("\"entries\"") < rewritten.indexOf("\"comment\""),
                "顶层键顺序必须保持不变");
    }

    @Test
    void backupIsCreatedBeforeRewriteAndNotWhenNothingChanges() throws IOException {
        Path file = write("custom_emc.json", """
                { "entries": [ { "item": "minecraft:stone", "emc": 5000 } ] }
                """);
        String original = Files.readString(file, StandardCharsets.UTF_8);

        ProjectECustomEmcFile.RewriteResult first = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> true);

        assertNotNull(first.backup());
        assertTrue(Files.isRegularFile(first.backup()));
        assertEquals(original, Files.readString(first.backup(), StandardCharsets.UTF_8),
                "备份必须保存改写前的内容");
        assertEquals(1L, countBackups(file));

        Path stable = write("stable.json", """
                { "entries": [ { "item": "minecraft:stone", "emc": 1 } ] }
                """);
        ProjectECustomEmcFile.RewriteResult second = new ProjectECustomEmcFile(stable)
                .rewriteAboveThreshold(protection(), key -> true);

        assertFalse(second.wroteFile());
        assertNull(second.backup());
        assertEquals(0L, countBackups(stable), "没有改动时不得生成备份");
    }

    @Test
    void nonOwnedEntriesAreAlwaysPreserved() throws IOException {
        Predicate<ItemKey> owned = key -> key.itemId().equals(STONE);
        Path file = write("non_owned.json", """
                {
                  "entries": [
                    { "item": "minecraft:stone", "emc": 5000 },
                    { "item": "minecraft:dirt", "emc": 4000 }
                  ]
                }
                """);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), owned);

        assertEquals(1, result.rewritten());
        assertEquals(1, result.notOwned());
        assertTrue(result.wroteFile());
        JsonArray entries = readObject(file).getAsJsonArray("entries");
        assertTrue(entries.get(0).getAsJsonObject().get("emc").getAsLong() <= RESET_MAX);
        assertEquals(4000L, entries.get(1).getAsJsonObject().get("emc").getAsLong(),
                "管理员手动条目必须保持原值");
    }

    @Test
    void onlyNonOwnedOverflowEntriesDoNotTouchDisk() throws IOException {
        Path file = write("only_non_owned.json", """
                { "entries": [ { "item": "minecraft:dirt", "emc": 4000 } ] }
                """);
        String original = Files.readString(file, StandardCharsets.UTF_8);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> false);

        assertEquals(0, result.rewritten());
        assertEquals(1, result.notOwned());
        assertFalse(result.wroteFile());
        assertNull(result.backup());
        assertEquals(0L, countBackups(file));
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void ownershipPredicateFailureIsFailClosed() throws IOException {
        Path file = write("predicate_failure.json", """
                { "entries": [ { "item": "minecraft:stone", "emc": 5000 } ] }
                """);
        String original = Files.readString(file, StandardCharsets.UTF_8);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> {
                    throw new IllegalStateException("ownership unavailable");
                });

        assertEquals(0, result.rewritten());
        assertEquals(1, result.notOwned());
        assertFalse(result.wroteFile());
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void secondRewriteIsIdempotent() throws IOException {
        Path file = write("custom_emc.json", """
                { "entries": [ { "item": "minecraft:stone", "emc": 5000 } ] }
                """);
        ProjectECustomEmcFile custom = new ProjectECustomEmcFile(file);
        assertTrue(custom.rewriteAboveThreshold(protection(), key -> true).wroteFile());
        String afterFirst = Files.readString(file, StandardCharsets.UTF_8);

        ProjectECustomEmcFile.RewriteResult second =
                custom.rewriteAboveThreshold(protection(), key -> true);

        assertEquals(0, second.rewritten());
        assertFalse(second.wroteFile());
        assertNull(second.backup());
        assertEquals(afterFirst, Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void corruptJsonIsNeverOverwritten() throws IOException {
        Path file = write("custom_emc.json", "{ this is not valid json ]");
        String original = Files.readString(file, StandardCharsets.UTF_8);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> true);

        assertEquals(0, result.rewritten());
        assertEquals(0, result.skipped());
        assertEquals(0, result.notOwned());
        assertFalse(result.wroteFile());
        assertNull(result.backup());
        assertEquals(original, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(0L, countBackups(file));
    }

    @Test
    void missingFileIsNotCreated() {
        Path file = tempDir.resolve("missing.json");

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> true);

        assertEquals(0, result.rewritten());
        assertFalse(result.wroteFile());
        assertNull(result.backup());
        assertFalse(Files.exists(file), "文件缺失时不得创建新文件");
    }

    @Test
    void numericAndStringEmcAreParsedLikeProjectEManualValues() throws IOException {
        Path file = write("custom_emc.json", """
                {
                  "entries": [
                    { "item": "minecraft:stone", "emc": 5000.5 },
                    { "item": "minecraft:dirt", "emc": "6000" },
                    { "item": "minecraft:apple", "emc": 7000 }
                  ]
                }
                """);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> true);

        assertEquals(3, result.rewritten());
        assertEquals(0, result.skipped());
        JsonArray entries = readObject(file).getAsJsonArray("entries");
        assertTrue(entries.get(0).getAsJsonObject().get("emc").getAsLong() <= RESET_MAX,
                "JSON 小数按 ProjectE 的 long 语义截断后参与溢出判定");
        assertTrue(entries.get(1).getAsJsonObject().get("emc").getAsLong() <= RESET_MAX,
                "数字字符串 EMC 与 ProjectEManualValues 口径一致，应参与溢出判定");
        assertTrue(entries.get(2).getAsJsonObject().get("emc").getAsLong() <= RESET_MAX);
    }

    @Test
    void tagEntriesArePreservedAndSkipped() throws IOException {
        Path file = write("custom_emc.json", """
                {
                  "entries": [
                    { "item": "#forge:ingots/iron", "emc": 5000 },
                    { "item": "minecraft:stone", "emc": 5000 }
                  ]
                }
                """);

        ProjectECustomEmcFile.RewriteResult result = new ProjectECustomEmcFile(file)
                .rewriteAboveThreshold(protection(), key -> true);

        assertEquals(1, result.rewritten());
        assertEquals(1, result.skipped());
        JsonArray entries = readObject(file).getAsJsonArray("entries");
        assertEquals(5000L, entries.get(0).getAsJsonObject().get("emc").getAsLong(),
                "标签条目无法映射到 ItemKey，必须保持原值");
    }

    private Path write(String name, String json) throws IOException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, json, StandardCharsets.UTF_8);
        return file;
    }

    private static JsonObject readObject(Path file) throws IOException {
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
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

    private static OverflowProtection protection() {
        return new OverflowProtection(new OverflowProtection.Settings(
                true,
                THRESHOLD,
                RESET_MIN,
                RESET_MAX,
                10,
                5,
                OverflowScope.ENTIRE_CUSTOM_EMC,
                true,
                42L));
    }
}
