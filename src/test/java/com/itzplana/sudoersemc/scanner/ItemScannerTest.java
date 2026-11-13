package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ItemScannerTest {

    private static final ResourceLocation AIR = new ResourceLocation("minecraft", "air");
    private static final ResourceLocation DIRT = new ResourceLocation("minecraft", "dirt");

    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.ensure();
    }

    @Test
    void scanSkipsAirAndIgnoredEntries() {
        ItemScanner scanner = new ItemScanner(noEmc(), Set.of(), Set.of(DIRT));

        List<ItemSnapshot> snapshots = scanner.scan();

        assertFalse(snapshots.isEmpty());
        assertTrue(snapshots.stream().noneMatch(snapshot -> snapshot.registryId().equals(AIR)),
                "air must never be priced");
        assertTrue(snapshots.stream().noneMatch(snapshot -> snapshot.registryId().equals(DIRT)),
                "exact ignore entries must be honoured");
        assertTrue(snapshots.stream().anyMatch(snapshot -> snapshot.registryId().equals(
                new ResourceLocation("minecraft", "stone"))));
    }

    @Test
    void ignoredNamespaceSkipsEverything() {
        Set<String> namespaces = BuiltInRegistries.ITEM.keySet().stream()
                .map(ResourceLocation::getNamespace)
                .collect(Collectors.toSet());
        ItemScanner scanner = new ItemScanner(noEmc(), namespaces, Set.of());

        assertTrue(scanner.scan().isEmpty());
    }

    @Test
    void existingEmcValuesAreCaptured() {
        ItemScanner.EmcQuery dirtOnly = new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(ItemStack stack) {
                return stack.is(Items.DIRT);
            }

            @Override
            public long getValue(ItemStack stack) {
                return stack.is(Items.DIRT) ? 42L : 0L;
            }
        };

        ItemSnapshot dirt = find(new ItemScanner(dirtOnly, Set.of(), Set.of()).scan(), DIRT);

        assertTrue(dirt.hasExistingEmc());
        assertEquals(42L, dirt.existingValue());
        assertEquals("minecraft", dirt.namespace());
        assertEquals("dirt", dirt.path());
    }

    @Test
    void brokenQueryDoesNotAbortTheScan() {
        ItemScanner.EmcQuery broken = new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(ItemStack stack) {
                if (stack.is(Items.DIRT)) {
                    throw new IllegalStateException("backend failure");
                }
                return false;
            }

            @Override
            public long getValue(ItemStack stack) {
                return 0L;
            }
        };

        List<ItemSnapshot> snapshots = new ItemScanner(broken, Set.of(), Set.of()).scan();
        ItemSnapshot dirt = find(snapshots, DIRT);

        assertFalse(snapshots.isEmpty(), "one broken query must not abort the item scan");
        assertFalse(dirt.hasExistingEmc());
        assertEquals(0L, dirt.existingValue());
    }

    @Test
    void sessionHonoursStepBudgetAndProducesFullScan() {
        ItemScanner scanner = new ItemScanner(noEmc(), Set.of(), Set.of());
        ItemScanner.ItemScanSession session = scanner.beginSession();

        assertTrue(session.total() > 10, "vanilla registry should contain more than 10 items");
        assertEquals(0, session.processed());
        assertFalse(session.isDone());

        int first = session.step(3);
        assertEquals(3, first, "one step must not inspect more than the requested budget");
        assertEquals(3, session.processed());
        assertFalse(session.isDone());

        int guard = 0;
        while (!session.isDone() && guard++ < 10_000) {
            int actual = session.step(7);
            assertTrue(actual > 0 && actual <= 7, "step must stay inside the requested budget");
        }
        assertTrue(session.isDone());
        assertEquals(session.total(), session.processed());

        List<ResourceLocation> sessionIds = session.snapshots().stream()
                .map(ItemSnapshot::registryId)
                .toList();
        List<ResourceLocation> fullScanIds = scanner.scan().stream()
                .map(ItemSnapshot::registryId)
                .toList();
        assertEquals(fullScanIds, sessionIds,
                "分片会话必须产生与一次性 scan 相同顺序的物品快照");
    }

    @Test
    void sessionStepRejectsNonPositiveBudget() {
        ItemScanner scanner = new ItemScanner(noEmc(), Set.of(), Set.of());

        assertThrows(IllegalArgumentException.class, () -> scanner.beginSession().step(0));
    }

    private static ItemSnapshot find(List<ItemSnapshot> snapshots, ResourceLocation id) {
        return snapshots.stream()
                .filter(snapshot -> snapshot.registryId().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing item snapshot: " + id));
    }

    private static ItemScanner.EmcQuery noEmc() {
        return new ItemScanner.EmcQuery() {
            @Override
            public boolean hasValue(ItemStack stack) {
                return false;
            }

            @Override
            public long getValue(ItemStack stack) {
                return 0L;
            }
        };
    }
}
