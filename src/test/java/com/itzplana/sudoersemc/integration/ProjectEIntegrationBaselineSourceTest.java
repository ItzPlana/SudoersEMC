package com.itzplana.sudoersemc.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * ProjectE-Integration 基线解析测试。纯 JSON 字符串输入，不启动 Forge，也不依赖 PEI。
 */
class ProjectEIntegrationBaselineSourceTest {

    private static ItemKey item(String id) {
        return ItemKey.of(new ResourceLocation(id));
    }

    @Test
    void peiBaselineJsonIsParsed() {
        String json = """
                {
                  "values": {
                    "before": {
                      "minecraft:iron_ingot": 256,
                      "mod:gem": 128,
                      "#forge:ingots": 999,
                      "minecraft:zero": 0,
                      "minecraft:negative": -5,
                      "minecraft:text": "not-a-number",
                      "not a valid id": 7
                    }
                  }
                }
                """;

        Map<ItemKey, Long> parsed = ProjectEIntegrationBaselineSource.parseJson(json);

        assertEquals(2, parsed.size());
        assertEquals(256L, parsed.get(item("minecraft:iron_ingot")));
        assertEquals(128L, parsed.get(item("mod:gem")));
        assertTrue(parsed.keySet().stream()
                .noneMatch(key -> key.itemId().getNamespace().startsWith("#")));
    }

    @Test
    void malformedBaselineJsonYieldsEmpty() {
        assertTrue(ProjectEIntegrationBaselineSource.parseJson(null).isEmpty());
        assertTrue(ProjectEIntegrationBaselineSource.parseJson("{\"values\":").isEmpty());
        assertTrue(ProjectEIntegrationBaselineSource.parseJson("[]").isEmpty());
        assertTrue(ProjectEIntegrationBaselineSource.parseJson(
                "{\"values\":{\"before\":[]}}").isEmpty());
    }

    @Test
    void baselineUnavailableWhenPeiAbsent() {
        ProjectEIntegrationBaselineSource source = new ProjectEIntegrationBaselineSource();
        source.prepare(null);

        assertFalse(source.isAvailable());
        assertTrue(source.lookup(item("minecraft:iron_ingot")).isEmpty());
        assertEquals(0, source.entryCount());
        assertTrue(source.fingerprintEntries().contains("external-baseline=unavailable"));
    }

    @Test
    void providerUnavailableLeavesBaselineEmpty() {
        ProjectEIntegrationBaselineSource unavailableBackend =
                new ProjectEIntegrationBaselineSource(() -> false);
        assertFalse(unavailableBackend.isProjectEBackendAvailable());
        assertFalse(unavailableBackend.isAvailable());
        unavailableBackend.prepare(null);
        assertFalse(unavailableBackend.isAvailable());
        assertTrue(unavailableBackend.lookup(item("minecraft:iron_ingot")).isEmpty());
        assertEquals(0, unavailableBackend.entryCount());

        ProjectEIntegrationBaselineSource availableBackend =
                new ProjectEIntegrationBaselineSource(() -> true);
        assertTrue(availableBackend.isProjectEBackendAvailable());
    }

    @Test
    void fingerprintChangesWithValues() {
        List<String> first = ProjectEIntegrationBaselineSource.fingerprintFor(
                Map.of(item("minecraft:iron_ingot"), 256L));
        List<String> second = ProjectEIntegrationBaselineSource.fingerprintFor(
                Map.of(item("minecraft:iron_ingot"), 512L));

        assertNotEquals(first, second);
        assertTrue(first.contains("pe:minecraft:iron_ingot=256"));
        assertTrue(second.contains("pe:minecraft:iron_ingot=512"));
    }

    @Test
    void tagEntriesAreKeptSeparatelyFromDirectValues() {
        String json = """
                {
                  "values": {
                    "before": {
                      "minecraft:iron_ingot": 256,
                      "#forge:ingots": 999,
                      "#forge:missing": 7,
                      "minecraft:zero": 0
                    }
                  }
                }
                """;

        ProjectEIntegrationBaselineSource.ParsedBaseline parsed =
                ProjectEIntegrationBaselineSource.parseBaseline(json);

        assertEquals(1, parsed.values().size());
        assertEquals(256L, parsed.values().get(item("minecraft:iron_ingot")));
        assertEquals(2, parsed.tags().size());
        assertEquals(999L, parsed.tags().get(new ResourceLocation("forge:ingots")));
        assertEquals(7L, parsed.tags().get(new ResourceLocation("forge:missing")));
    }

    @Test
    void tagValuesExpandToMemberItemsAndDirectValuesWin() {
        Map<ItemKey, Long> direct = Map.of(item("minecraft:iron_ingot"), 256L);
        Map<ResourceLocation, Long> tags = Map.of(new ResourceLocation("forge:ingots"), 999L);

        ProjectEIntegrationBaselineSource.TagExpansion expansion =
                ProjectEIntegrationBaselineSource.expandTagValues(direct, tags,
                        tag -> List.of(item("minecraft:iron_ingot"),
                                item("minecraft:gold_ingot")));

        assertEquals(0, expansion.skippedTags());
        assertEquals(2, expansion.values().size());
        assertEquals(256L, expansion.values().get(item("minecraft:iron_ingot")),
                "直接物品条目必须优先于标签展开值");
        assertEquals(999L, expansion.values().get(item("minecraft:gold_ingot")));

        ProjectEIntegrationBaselineSource.TagExpansion tagOnly =
                ProjectEIntegrationBaselineSource.expandTagValues(Map.of(), tags,
                        tag -> List.of(item("minecraft:gold_ingot")));

        assertEquals(1, tagOnly.values().size(), "tag-only 文件必须产生外部基线条目");
        assertEquals(999L, tagOnly.values().get(item("minecraft:gold_ingot")));
        assertEquals(0, tagOnly.skippedTags());
    }

    @Test
    void unresolvedAndThrowingTagsAreCountedInsteadOfSilentlyDropped() {
        Map<ResourceLocation, Long> tags = Map.of(
                new ResourceLocation("forge:missing"), 10L,
                new ResourceLocation("forge:broken"), 20L);

        ProjectEIntegrationBaselineSource.TagExpansion expansion =
                ProjectEIntegrationBaselineSource.expandTagValues(Map.of(), tags, tag -> {
                    if (tag.getPath().equals("broken")) {
                        throw new IllegalStateException("模拟标签解析失败");
                    }
                    return List.of();
                });

        assertTrue(expansion.values().isEmpty());
        assertEquals(2, expansion.skippedTags());
    }

    @Test
    void nullTagExpanderCountsEveryTagAsSkipped() {
        ProjectEIntegrationBaselineSource.TagExpansion expansion =
                ProjectEIntegrationBaselineSource.expandTagValues(
                        Map.of(item("minecraft:iron_ingot"), 1L),
                        Map.of(new ResourceLocation("forge:ingots"), 2L),
                        null);

        assertEquals(1, expansion.skippedTags());
        assertEquals(1L, expansion.values().get(item("minecraft:iron_ingot")));
    }

    @Test
    void candidateOrderIsDeterministicEvenWhenInputOrderChanges() {
        ResourceLocation first = new ResourceLocation("aaa:packs");
        ResourceLocation second = new ResourceLocation("zzz:packs");

        assertEquals(List.of(first, second),
                ProjectEIntegrationBaselineSource.orderedCandidateIds(
                        List.of(second, first)));
        assertEquals(List.of(first, second),
                ProjectEIntegrationBaselineSource.orderedCandidateIds(
                        List.of(first, second)));
    }
}
