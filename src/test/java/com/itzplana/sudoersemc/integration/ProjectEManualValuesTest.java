package com.itzplana.sudoersemc.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import java.io.IOException;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ProjectEManualValues#snapshotPredicate()} 与手工值文件内容摘要的单元测试。
 *
 * <p>扫描阶段只应刷新一次手工文件，并复用同一个不可变快照判定器；文件在下一次扫描前变化时，
 * 新建的判定器必须看到新内容。基线指纹必须反映文件内容本身，而不是仅反映手工值条目数量或
 * item id 集合；同一 item id 的数值变化必须使摘要失效。</p>
 */
class ProjectEManualValuesTest {

    private static final ItemKey DIAMOND =
            ItemKey.of(new ResourceLocation("minecraft", "diamond"));
    private static final ItemKey EMERALD =
            ItemKey.of(new ResourceLocation("minecraft", "emerald"));
    @TempDir
    Path tempDir;

    @Test
    void snapshotPredicateMatchesExplicitManualEntries() throws Exception {
        Path file = tempDir.resolve("custom_emc.json");
        Files.writeString(file, "{\"entries\":[{\"item\":\"minecraft:diamond\",\"emc\":1234}]}");
        ProjectEManualValues values = new ProjectEManualValues(file);

        Predicate<ItemKey> predicate = values.snapshotPredicate();

        assertTrue(predicate.test(DIAMOND));
        assertFalse(predicate.test(EMERALD));
    }

    @Test
    void snapshotPredicateSeesFileChangesOnNextScan() throws Exception {
        Path file = tempDir.resolve("custom_emc.json");
        Files.writeString(file, "{\"entries\":[]}");
        ProjectEManualValues values = new ProjectEManualValues(file);
        assertFalse(values.snapshotPredicate().test(DIAMOND));

        Files.writeString(file, "{\"entries\":[{\"item\":\"minecraft:diamond\",\"emc\":42}]}");

        assertTrue(values.snapshotPredicate().test(DIAMOND),
                "下一次扫描必须重新读取发生变化的手工文件");
    }

    @Test
    void contentFingerprintChangesWhenManualValueChangesForSameItemId() throws IOException {
        Path file = tempDir.resolve("custom_emc.json");
        Files.writeString(file, """
                {"entries":[{"item":"minecraft:stone","emc":1}]}
                """);

        ProjectEManualValues values = new ProjectEManualValues(file);
        String before = values.contentFingerprint();

        // 同一 item id、不同数值；同时改变字节长度，确保 mtime + size 缓存必然失效。
        Files.writeString(file, """
                {"entries":[{"item":"minecraft:stone","emc":4096}]}
                """);
        String after = values.contentFingerprint();

        assertTrue(before.startsWith("sha256:"), "手工值文件存在时应返回内容摘要");
        assertNotEquals(before, after, "同一 item id 的手工值变化必须改变基线摘要");
        assertEquals(
                Map.of(ItemKey.of(new ResourceLocation("minecraft", "stone")), 4096L),
                values.manualValues());
    }

    @Test
    void contentFingerprintUsesStableFallbackWhenFileIsMissing() {
        ProjectEManualValues values = new ProjectEManualValues(tempDir.resolve("absent.json"));

        assertEquals("missing", values.contentFingerprint());
        assertEquals("missing", values.contentFingerprint(),
                "缺失状态必须稳定，不能每次调用都变成不同摘要");
    }
}
