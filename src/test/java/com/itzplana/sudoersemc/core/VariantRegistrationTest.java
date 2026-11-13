package com.itzplana.sudoersemc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link VariantRegistration}.
 *
 * <p>No Minecraft bootstrap required: only verifies the canonical key, raw-SNBT deduplication, ordering and count
 * capping.</p>
 */
class VariantRegistrationTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");

    @Test
    void baseItemHasNoNbtRegistrations() {
        assertTrue(VariantRegistration.registrationNbts(ItemKey.of(STONE), List.of("{Marker:1}")).isEmpty());
    }

    @Test
    void canonicalNbtComesFirstAndObservedValuesAreDedupedAndSorted() {
        ItemKey key = ItemKey.of(STONE, "{Marker:1}");

        List<String> registrations = VariantRegistration.registrationNbts(
                key, List.of("{Marker:1,display:{Name:\"b\"}}", "{Marker:1}", "  ", "{Marker:1,display:{Name:\"a\"}}"));

        assertEquals(List.of(
                "{Marker:1}",
                "{Marker:1,display:{Name:\"a\"}}",
                "{Marker:1,display:{Name:\"b\"}}"
        ), registrations);
    }

    @Test
    void observedValuesAreCappedAndStable() {
        ItemKey key = ItemKey.of(STONE, "{Marker:1}");
        List<String> observed = new ArrayList<>();
        for (int index = 0; index < VariantRegistration.MAX_OBSERVED_NBT_PER_VARIANT + 10; index++) {
            observed.add("{Marker:1,Index:" + index + "}");
        }

        List<String> registrations = VariantRegistration.registrationNbts(key, observed);

        assertEquals(VariantRegistration.MAX_OBSERVED_NBT_PER_VARIANT + 1, registrations.size(),
                "规范 key 之外最多保留固定数量的原始形态");
        assertTrue(registrations.contains("{Marker:1,Index:0}"), "应保留字典序最小的原始形态");
    }
}
