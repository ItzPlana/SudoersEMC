package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.command.CommandResult;
import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OwnedEntrySink;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.pipeline.ScanPipeline;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.PriceDropDecision;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code approveQuarantined} 的端到端测试：读取持久化记录 → approve → stage(APPROVED) → flush。
 *
 * <p>这些用例直接调用生产入口使用的同一个可注入实现，覆盖磁盘回读、内存隔离清理、stage 失败
 * 时保留 approved 以便幂等重试，以及非自有条目阻断时不产生部分提交。</p>
 */
class SudoersemcApprovalTest {

    private static final ResourceLocation ITEM_ID =
            new ResourceLocation("sudoersemc_test", "quarantined_item");
    private static final ItemKey KEY = ItemKey.of(ITEM_ID);

    @TempDir
    Path tempDir;

    @Test
    void approveQuarantinedAppliesPersistedDropAndFlushes() {
        Path entryFile = tempDir.resolve("generated_entries.json");
        Path dropsFile = tempDir.resolve("quarantined_drops.json");
        GeneratedEntryStore store = new GeneratedEntryStore(entryFile);
        QuarantinedDropsStore drops = new QuarantinedDropsStore(dropsFile);
        LargeDropProtection protection = new LargeDropProtection(
                new LargeDropProtection.Settings(true, 0.5D, Set.of(), Set.of()), drops);
        ScanPipeline pipeline = new ScanPipeline(
                store, null, null, protection, null, null, null, null);

        PriceDropDecision decision = protection.evaluate(KEY, 1_000L, 100L);
        assertFalse(decision.allowed(), "90% 降幅必须进入隔离");
        assertEquals(1, protection.quarantineCount());
        assertEquals(1, drops.size());
        assertFalse(drops.entries().get(0).approved());

        AtomicInteger flushCount = new AtomicInteger();
        CommandResult result = Sudoersemc.approveQuarantined(
                ITEM_ID.toString(),
                store,
                drops,
                pipeline,
                () -> {
                    flushCount.incrementAndGet();
                    store.saveIfDirty();
                    drops.saveIfDirty();
                });

        assertTrue(result.success(), result.joinLines());
        assertEquals(1, flushCount.get(), "成功应用后必须执行一次落盘/重映射刷新");
        assertEquals(0, protection.quarantineCount(), "内存隔离列表应被清理");
        assertTrue(protection.isApproved(KEY), "审批历史应记录该物品");
        assertTrue(drops.entries().get(0).approved(), "持久化记录的 approved 必须置位");
        assertTrue(result.joinLines().contains("已从内存隔离列表移除 1 条记录"));

        GeneratedEmcEntry applied = store.get(KEY);
        assertNotNull(applied, "stage 后应存在生成值条目");
        assertEquals(100L, applied.value());
        assertEquals(PriceSource.APPROVED, applied.source());
        assertTrue(applied.owned());

        GeneratedEntryStore reloadedEntries = new GeneratedEntryStore(entryFile);
        reloadedEntries.load();
        GeneratedEmcEntry reloaded = reloadedEntries.get(KEY);
        assertNotNull(reloaded, "flush 后生成值必须可从磁盘回读");
        assertEquals(100L, reloaded.value());
        assertEquals(PriceSource.APPROVED, reloaded.source());

        QuarantinedDropsStore reloadedDrops = new QuarantinedDropsStore(dropsFile);
        reloadedDrops.load();
        assertTrue(reloadedDrops.entries().get(0).approved(),
                "flush 后 approved 状态必须可从磁盘回读");
    }

    @Test
    void stageFailureKeepsApprovedRecordAndRetrySucceeds() {
        Path dropsFile = tempDir.resolve("quarantined_stage_failure.json");
        QuarantinedDropsStore drops = new QuarantinedDropsStore(dropsFile);
        drops.record(new QuarantinedDropsStore.QuarantinedDrop(
                KEY, 1_000L, 100L, 0.9D, "drop-exceeds-threshold", 1L, false));

        OwnedEntrySink failingSink = new OwnedEntrySink() {
            @Override
            public boolean stage(GeneratedEmcEntry entry) {
                return false;
            }

            @Override
            public Map<ItemKey, GeneratedEmcEntry> snapshotAll() {
                return Map.of();
            }

            @Override
            public boolean isOwned(ItemKey key) {
                return false;
            }
        };

        AtomicInteger failedFlushes = new AtomicInteger();
        CommandResult failed = Sudoersemc.approveQuarantined(
                ITEM_ID.toString(), failingSink, drops, null, failedFlushes::incrementAndGet);

        assertFalse(failed.success(), "stage 失败时命令必须返回失败");
        assertEquals(0, failedFlushes.get(), "stage 失败时不得执行落盘/重映射");
        assertTrue(drops.entries().get(0).approved(),
                "approved 在 stage 前写入；失败时必须保留以便幂等重试");

        QuarantinedDropsStore reloadedDrops = new QuarantinedDropsStore(dropsFile);
        reloadedDrops.load();
        assertTrue(reloadedDrops.entries().get(0).approved(),
                "stage 失败后 approved 必须已落盘，重启后仍可重试");

        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_retry.json"));
        AtomicInteger retryFlushes = new AtomicInteger();
        CommandResult retried = Sudoersemc.approveQuarantined(
                ITEM_ID.toString(),
                store,
                reloadedDrops,
                null,
                () -> {
                    retryFlushes.incrementAndGet();
                    store.saveIfDirty();
                    reloadedDrops.saveIfDirty();
                });

        assertTrue(retried.success(), retried.joinLines());
        assertEquals(1, retryFlushes.get());
        GeneratedEmcEntry applied = store.get(KEY);
        assertNotNull(applied);
        assertEquals(100L, applied.value());
        assertEquals(PriceSource.APPROVED, applied.source());
        assertTrue(applied.owned());
    }

    @Test
    void nonOwnedExistingEntryBlocksApprovalBeforeFlagIsWritten() {
        Path dropsFile = tempDir.resolve("quarantined_non_owned.json");
        QuarantinedDropsStore drops = new QuarantinedDropsStore(dropsFile);
        drops.record(new QuarantinedDropsStore.QuarantinedDrop(
                KEY, 1_000L, 100L, 0.9D, "drop-exceeds-threshold", 1L, false));

        GeneratedEntryStore store = new GeneratedEntryStore(tempDir.resolve("generated_non_owned.json"));
        assertTrue(store.stage(new GeneratedEmcEntry(
                KEY, 500L, PriceSource.EXISTING, null, false, 0L)));

        AtomicInteger flushes = new AtomicInteger();
        CommandResult blocked = Sudoersemc.approveQuarantined(
                ITEM_ID.toString(), store, drops, null, flushes::incrementAndGet);

        assertFalse(blocked.success());
        assertEquals(0, flushes.get());
        assertFalse(drops.entries().get(0).approved(),
                "非自有条目阻断时不得提前写入 approved");
        assertEquals(500L, store.get(KEY).value(), "阻断时不得覆盖现有值");
        assertFalse(store.get(KEY).owned());
    }
}
