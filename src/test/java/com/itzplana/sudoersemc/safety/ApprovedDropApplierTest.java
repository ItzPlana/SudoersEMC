package com.itzplana.sudoersemc.safety;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore.QuarantinedDrop;
import java.nio.file.Path;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ApprovedDropApplier} 的安全规则测试。
 *
 * <p>所有场景都是纯逻辑的：只使用临时目录中的真实 {@link GeneratedEntryStore}，不启动
 * Minecraft 服务器。拒绝路径必须保证存储完全不变。</p>
 */
class ApprovedDropApplierTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final long PREVIOUS_VALUE = 100L;
    private static final long TIMESTAMP = 1_730_000_000_000L;

    private static final Predicate<ItemKey> NEVER_MANUAL = key -> false;
    private static final Predicate<ItemKey> NEVER_EXCLUDED = key -> false;

    @TempDir
    Path tempDir;

    private GeneratedEntryStore store() {
        return new GeneratedEntryStore(tempDir.resolve(GeneratedEntryStore.FILE_NAME));
    }

    private static QuarantinedDrop drop(long proposedValue) {
        return drop(proposedValue, false);
    }

    private static QuarantinedDrop drop(long proposedValue, boolean approved) {
        return new QuarantinedDrop(
                ItemKey.of(STONE),
                PREVIOUS_VALUE,
                proposedValue,
                0.9D,
                "drop-exceeds-threshold",
                TIMESTAMP,
                approved);
    }

    @Test
    void appliedStagesApprovedEntryWithExpectedFields() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L), store, true, 1L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.APPLIED, result.status());
        GeneratedEmcEntry applied = result.applied();
        assertNotNull(applied);
        assertEquals(ItemKey.of(STONE), applied.item());
        assertEquals(10L, applied.value());
        assertEquals(PriceSource.APPROVED, applied.source());
        assertNull(applied.recipeId());
        assertTrue(applied.owned());
        assertTrue(applied.alternativeCosts().isEmpty());
        assertTrue(applied.timestamp() >= TIMESTAMP);

        GeneratedEmcEntry stored = store.snapshotAll().get(ItemKey.of(STONE));
        assertSame(applied, stored, "应用器返回的条目必须就是写入存储的条目");
        assertTrue(store.isOwned(ItemKey.of(STONE)));
    }

    @Test
    void alreadyApprovedRecordIsNotAppliedAgain() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L, true), store, true, 1L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.ALREADY_APPLIED, result.status());
        assertNull(result.applied());
        assertTrue(store.snapshotAll().isEmpty());
        assertFalse(store.isDirty());
    }

    @Test
    void disabledConfigRefusesApplication() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L), store, false, 1L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.REFUSED_DISABLED, result.status());
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void nonPositiveProposedValueIsInvalid() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(0L), store, true, 0L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.REFUSED_INVALID, result.status());
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void valueBelowMinimumIsRefused() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(9L), store, true, 10L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.REFUSED_BELOW_MIN, result.status());
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void manualValueIsRefused() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L), store, true, 1L, key -> true, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.REFUSED_MANUAL, result.status());
        assertTrue(result.message().contains("手动值"));
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void manualCheckRunsBeforeExclusionAndThrowingDetectorFailsClosed() {
        GeneratedEntryStore store = store();
        Predicate<ItemKey> throwing = key -> {
            throw new IllegalStateException("boom");
        };

        ApprovedDropApplier.Result manualWins = ApprovedDropApplier.apply(
                drop(10L), store, true, 1L, throwing, key -> true);
        ApprovedDropApplier.Result exclusionAlsoThrows = ApprovedDropApplier.apply(
                drop(10L), store, true, 1L, NEVER_MANUAL, throwing);

        assertEquals(ApprovedDropApplier.Status.REFUSED_MANUAL, manualWins.status());
        assertEquals(ApprovedDropApplier.Status.REFUSED_EXCLUDED, exclusionAlsoThrows.status());
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void nullManualDetectorFailsClosed() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L), store, true, 1L, null, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.REFUSED_MANUAL, result.status());
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void excludedItemIsRefusedWithActionableMessage() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L), store, true, 1L, NEVER_MANUAL, key -> true);

        assertEquals(ApprovedDropApplier.Status.REFUSED_EXCLUDED, result.status());
        assertTrue(result.message().contains("下次扫描清理"));
        assertTrue(result.message().contains("排除配置"));
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void nullStoreIsStorageUnavailable() {
        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L), null, true, 1L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.STORAGE_UNAVAILABLE, result.status());
        assertNull(result.applied());
    }

    @Test
    void nullDropIsInvalid() {
        GeneratedEntryStore store = store();

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                null, store, true, 1L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.REFUSED_INVALID, result.status());
        assertTrue(store.snapshotAll().isEmpty());
    }

    @Test
    void stageRejectionIsReportedAsRefusal() {
        GeneratedEntryStore store = store();
        ItemKey key = ItemKey.of(STONE);
        // A non-owned baseline entry must never be overwritten by approval.
        assertTrue(store.stage(new GeneratedEmcEntry(
                key, 50L, PriceSource.EXTERNAL_BASELINE, null, false, 1L)));

        ApprovedDropApplier.Result result = ApprovedDropApplier.apply(
                drop(10L), store, true, 1L, NEVER_MANUAL, NEVER_EXCLUDED);

        assertEquals(ApprovedDropApplier.Status.REFUSED_INVALID, result.status());
        assertEquals(50L, store.snapshotAll().get(key).value());
        assertEquals(PriceSource.EXTERNAL_BASELINE, store.snapshotAll().get(key).source());
    }
}
