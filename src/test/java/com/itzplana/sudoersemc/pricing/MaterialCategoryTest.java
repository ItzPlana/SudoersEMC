package com.itzplana.sudoersemc.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Material category recognition tests.
 *
 * <p>Tag parsing must support both namespaced and non-namespaced, singular and plural forms, returning
 * {@link MaterialCategory#UNKNOWN} rather than throwing when unrecognised.</p>
 */
class MaterialCategoryTest {

    @Test
    void recognisesEveryDocumentedCategory() {
        assertEquals(MaterialCategory.INGOT, MaterialCategory.fromTagPath("forge:ingots/iron"));
        assertEquals(MaterialCategory.NUGGET, MaterialCategory.fromTagPath("forge:nuggets/iron"));
        assertEquals(MaterialCategory.GEM, MaterialCategory.fromTagPath("forge:gems/diamond"));
        assertEquals(MaterialCategory.DUST, MaterialCategory.fromTagPath("forge:dusts/redstone"));
        assertEquals(MaterialCategory.RAW_MATERIAL, MaterialCategory.fromTagPath("forge:raw_materials/iron"));
        assertEquals(MaterialCategory.STORAGE_BLOCK, MaterialCategory.fromTagPath("forge:storage_blocks/iron"));
        assertEquals(MaterialCategory.PLATE, MaterialCategory.fromTagPath("forge:plates/iron"));
        assertEquals(MaterialCategory.ROD, MaterialCategory.fromTagPath("forge:rods/iron"));
        assertEquals(MaterialCategory.ORE, MaterialCategory.fromTagPath("forge:ores/iron"));
    }

    @Test
    void acceptsSingularForm() {
        assertEquals(MaterialCategory.INGOT, MaterialCategory.fromTagPath("forge:ingot/gold"));
        assertEquals(MaterialCategory.GEM, MaterialCategory.fromTagPath("forge:gem/emerald"));
        assertEquals(MaterialCategory.ORE, MaterialCategory.fromTagPath("forge:ore/copper"));
    }

    @Test
    void acceptsModdedNamespaces() {
        assertEquals(MaterialCategory.PLATE, MaterialCategory.fromTagPath("create:plates/iron"));
        assertEquals(MaterialCategory.ROD, MaterialCategory.fromTagPath("thermal:rods/tin"));
        assertEquals(MaterialCategory.DUST, MaterialCategory.fromTagPath("mekanism:dusts/osmium"));
    }

    @Test
    void acceptsPathsWithoutNamespace() {
        assertEquals(MaterialCategory.INGOT, MaterialCategory.fromTagPath("ingots/iron"));
        assertEquals(MaterialCategory.STORAGE_BLOCK, MaterialCategory.fromTagPath("storage_blocks/gold"));
    }

    @Test
    void deeperTagsStillResolveByFirstSegment() {
        assertEquals(MaterialCategory.ORE, MaterialCategory.fromTagPath("forge:ores/iron/deepslate"));
        assertEquals(MaterialCategory.STORAGE_BLOCK, MaterialCategory.fromTagPath("forge:storage_blocks/raw/iron"));
    }

    @Test
    void isCaseInsensitiveAndNormalisesHyphens() {
        assertEquals(MaterialCategory.INGOT, MaterialCategory.fromTagPath("FORGE:INGOTS/IRON"));
        assertEquals(MaterialCategory.RAW_MATERIAL, MaterialCategory.fromTagPath("forge:raw-materials/copper"));
        assertEquals(MaterialCategory.STORAGE_BLOCK, MaterialCategory.fromTagPath("forge:storage-block/copper"));
    }

    @Test
    void unknownPathsBecomeUnknownInsteadOfThrowing() {
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.fromTagPath(null));
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.fromTagPath(""));
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.fromTagPath("   "));
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.fromTagPath("forge:"));
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.fromTagPath("forge:/iron"));
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.fromTagPath("forge:miscellaneous/thing"));
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.fromTagPath("forge:logs/oak"));
    }

    @Test
    void categoryCountIsStable() {
        // Categories are anchors for relative prices; a count change would affect config compatibility, so the current design is locked here.
        assertEquals(10, MaterialCategory.values().length);
        assertEquals(MaterialCategory.UNKNOWN, MaterialCategory.values()[MaterialCategory.values().length - 1]);
    }
}
