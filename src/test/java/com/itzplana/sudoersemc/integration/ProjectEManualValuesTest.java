package com.itzplana.sudoersemc.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ProjectEManualValues#snapshotPredicate()} 的单元测试。
 *
 * <p>扫描阶段只应刷新一次手工文件，并复用同一个不可变快照判定器；文件在下一次扫描前变化时，
 * 新建的判定器必须看到新内容。</p>
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
}
