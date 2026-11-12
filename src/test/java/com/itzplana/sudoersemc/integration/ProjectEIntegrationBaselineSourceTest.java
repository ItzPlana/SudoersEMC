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
    void fingerprintChangesWithValues() {
        List<String> first = ProjectEIntegrationBaselineSource.fingerprintFor(
                Map.of(item("minecraft:iron_ingot"), 256L));
        List<String> second = ProjectEIntegrationBaselineSource.fingerprintFor(
                Map.of(item("minecraft:iron_ingot"), 512L));

        assertNotEquals(first, second);
        assertTrue(first.contains("pe:minecraft:iron_ingot=256"));
        assertTrue(second.contains("pe:minecraft:iron_ingot=512"));
    }
}
