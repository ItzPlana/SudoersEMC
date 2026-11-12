package com.itzplana.sudoersemc.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
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
    void tagAndRarityMetadataAreCaptured() {
        ItemScanner scanner = new ItemScanner(noEmc(), Set.of(), Set.of(),
                stack -> new ItemScanner.ItemMetadata(
                        List.of("forge:storage_blocks/iron", "forge:ingots/iron"), Rarity.RARE));

        ItemSnapshot dirt = find(scanner.scan(), DIRT);

        assertEquals(List.of("forge:storage_blocks/iron", "forge:ingots/iron"), dirt.tagPaths());
        assertEquals(Rarity.RARE, dirt.rarity());
    }

    @Test
    void metadataFailureFallsBackToEmptyTagsAndCommonRarity() {
        ItemScanner scanner = new ItemScanner(noEmc(), Set.of(), Set.of(), stack -> {
            throw new IllegalStateException("tag lookup failed");
        });

        ItemSnapshot dirt = find(scanner.scan(), DIRT);

        assertTrue(dirt.tagPaths().isEmpty(), "采集失败时必须退化为空标签，不能中断整次扫描");
        assertEquals(Rarity.COMMON, dirt.rarity(), "采集失败时稀有度必须退化为 COMMON");
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
