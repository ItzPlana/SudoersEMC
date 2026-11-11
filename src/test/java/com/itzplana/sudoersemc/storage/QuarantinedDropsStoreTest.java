package com.itzplana.sudoersemc.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link QuarantinedDropsStore} 的单元测试。
 *
 * <p>覆盖写入/读取、同物品替换、审批、容量上限、损坏文件容错与非法记录校验。</p>
 */
class QuarantinedDropsStoreTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");

    @TempDir
    Path tempDir;

    private static QuarantinedDropsStore.QuarantinedDrop drop(
            ResourceLocation itemId, long previousValue, long proposedValue, long timestamp) {
        double ratio = previousValue <= 0L
                ? 0.0
                : Math.max(0.0, (previousValue - proposedValue) / (double) previousValue);
        return new QuarantinedDropsStore.QuarantinedDrop(
                ItemKey.of(itemId),
                previousValue,
                proposedValue,
                ratio,
                "drop-exceeds-threshold",
                timestamp,
                false);
    }

    @Test
    void recordAndRoundTrip() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        QuarantinedDropsStore.QuarantinedDrop drop = new QuarantinedDropsStore.QuarantinedDrop(
                ItemKey.of(STONE),
                100_000L,
                100L,
                0.999,
                "drop-exceeds-threshold",
                1_730_000_000_000L,
                false);

        store.record(drop);
        assertTrue(store.isDirty());
        store.save();
        assertFalse(store.isDirty());

        QuarantinedDropsStore reloaded = new QuarantinedDropsStore(file);
        reloaded.load();

        assertEquals(1, reloaded.size());
        assertEquals(drop, reloaded.entries().get(0));
        assertFalse(reloaded.isDirty());
    }

    @Test
    void recordingSameItemTwiceKeepsOneEntry() {
        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));

        store.record(drop(STONE, 100_000L, 100L, 1L));
        store.record(drop(STONE, 100_000L, 50L, 2L));

        assertEquals(1, store.size());
        List<QuarantinedDropsStore.QuarantinedDrop> entries = store.entries();
        assertEquals(1, entries.size());
        assertEquals(50L, entries.get(0).proposedValue());
        assertEquals(2L, entries.get(0).timestamp());
    }

    @Test
    void approveFlipsFlagAndRejectsUnknown() {
        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        store.record(drop(STONE, 100_000L, 100L, 1L));

        assertFalse(store.approve(ItemKey.of(DIRT)));
        assertFalse(store.approve(null));
        assertTrue(store.approve(ItemKey.of(STONE)));
        assertTrue(store.entries().get(0).approved());
        // 已批准的条目再次审批仍然认为是“存在”，返回 true。
        assertTrue(store.approve(ItemKey.of(STONE)));
    }

    @Test
    void retentionCapDropsOldestEntries() {
        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));

        for (int i = 0; i <= QuarantinedDropsStore.DEFAULT_MAX_ENTRIES; i++) {
            ResourceLocation itemId = new ResourceLocation("minecraft", "item_" + i);
            store.record(drop(itemId, 1000L, 10L, 1_730_000_000_000L + i));
        }

        assertEquals(QuarantinedDropsStore.DEFAULT_MAX_ENTRIES, store.size());
        List<QuarantinedDropsStore.QuarantinedDrop> entries = store.entries();
        assertEquals(QuarantinedDropsStore.DEFAULT_MAX_ENTRIES, entries.size());

        ItemKey oldest = ItemKey.of(new ResourceLocation("minecraft", "item_0"));
        ItemKey newest = ItemKey.of(new ResourceLocation("minecraft", "item_"
                + QuarantinedDropsStore.DEFAULT_MAX_ENTRIES));
        assertTrue(entries.stream().noneMatch(entry -> entry.key().equals(oldest)),
                "时间戳最旧的条目应当被淘汰");
        assertTrue(entries.stream().anyMatch(entry -> entry.key().equals(newest)),
                "最新写入的条目必须保留");
    }

    @Test
    void corruptFileIsTolerated() throws IOException {
        Path file = tempDir.resolve(QuarantinedDropsStore.FILE_NAME);
        Files.writeString(file, "{ 这不是合法 JSON ][", StandardCharsets.UTF_8);

        QuarantinedDropsStore store = new QuarantinedDropsStore(file);
        assertDoesNotThrow(store::load);

        assertEquals(0, store.size());
        assertFalse(store.isDirty());
        assertTrue(store.entries().isEmpty());
    }

    @Test
    void invalidDropsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), -1L, 10L, 0.5, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, -1L, 0.5, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, 1L, Double.NaN, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, 1L, Double.POSITIVE_INFINITY, "reason", 1L, false));
        assertThrows(IllegalArgumentException.class,
                () -> new QuarantinedDropsStore.QuarantinedDrop(
                        ItemKey.of(STONE), 10L, 1L, 0.5, "reason", -1L, false));

        QuarantinedDropsStore store =
                new QuarantinedDropsStore(tempDir.resolve(QuarantinedDropsStore.FILE_NAME));
        assertDoesNotThrow(() -> store.record(null));
        assertEquals(0, store.size());
        assertFalse(store.isDirty());
    }
}
