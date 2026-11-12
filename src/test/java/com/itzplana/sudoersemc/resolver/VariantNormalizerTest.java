package com.itzplana.sudoersemc.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * {@link VariantNormalizer} 的单元测试。
 *
 * <p>Forge 未启动，所有标签都直接构造；测试只验证 NBT 清洗与规范化行为。</p>
 */
class VariantNormalizerTest {

    private static final ResourceLocation SUDOERSEMC_TOKEN =
            new ResourceLocation("sudoersemc", "token");

    @Test
    void displayOnlyTagNormalisesToNull() {
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"自定义名称\"}");
        display.putInt("color", 0xFFAA00);

        CompoundTag loreEntry = new CompoundTag();
        loreEntry.putString("text", "一段描述");
        ListTag lore = new ListTag();
        lore.add(loreEntry);
        display.put("Lore", lore);

        CompoundTag tag = new CompoundTag();
        tag.put("display", display);

        assertNull(VariantNormalizer.normalize(tag));
    }

    @Test
    void displayIsStrippedAtEveryNestingLevel() {
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"容器内物品名称\"}");

        CompoundTag containerData = new CompoundTag();
        containerData.putInt("Damage", 3);
        containerData.put("display", display);

        CompoundTag tag = new CompoundTag();
        tag.put("BlockEntityTag", containerData);

        assertEquals("{BlockEntityTag:{Damage:3}}", VariantNormalizer.normalize(tag));
    }

    @Test
    void damageIsPreserved() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 17);

        assertEquals("{Damage:17}", VariantNormalizer.normalize(tag));
    }

    @Test
    void enchantmentsArePreservedIncludingNestedStructure() {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:sharpness");
        enchantment.putShort("lvl", (short) 5);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);

        CompoundTag tag = new CompoundTag();
        tag.put("Enchantments", enchantments);

        assertEquals("{Enchantments:[{id:\"minecraft:sharpness\",lvl:5s}]}",
                VariantNormalizer.normalize(tag));
    }

    @Test
    void storedEnchantmentsArePreserved() {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:protection");
        enchantment.putShort("lvl", (short) 4);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);

        CompoundTag tag = new CompoundTag();
        tag.put("StoredEnchantments", enchantments);

        assertEquals("{StoredEnchantments:[{id:\"minecraft:protection\",lvl:4s}]}",
                VariantNormalizer.normalize(tag));
    }

    @Test
    void customModelDataIsStripped() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("CustomModelData", 42);

        assertNull(VariantNormalizer.normalize(tag));
    }

    @Test
    void repairCostHideFlagsAndEnchantmentGlintOverrideAreStripped() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("RepairCost", 3);
        tag.putInt("HideFlags", 127);
        tag.putBoolean("EnchantmentGlintOverride", true);

        assertNull(VariantNormalizer.normalize(tag));
    }

    @Test
    void blockEntityTagIsPreserved() {
        CompoundTag blockEntity = new CompoundTag();
        blockEntity.putString("id", "minecraft:chest");

        CompoundTag tag = new CompoundTag();
        tag.put("BlockEntityTag", blockEntity);

        assertEquals("{BlockEntityTag:{id:\"minecraft:chest\"}}", VariantNormalizer.normalize(tag));
    }

    @Test
    void storedEmcIsPreserved() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("stored_emc", 4096L);

        assertEquals("{stored_emc:4096L}", VariantNormalizer.normalize(tag));
    }

    @Test
    void insertionOrderDoesNotAffectResult() {
        CompoundTag first = new CompoundTag();
        first.putInt("Damage", 1);
        first.putString("Potion", "minecraft:healing");
        first.putLong("stored_emc", 7L);

        CompoundTag second = new CompoundTag();
        second.putLong("stored_emc", 7L);
        second.putString("Potion", "minecraft:healing");
        second.putInt("Damage", 1);

        assertEquals("{Damage:1,Potion:\"minecraft:healing\",stored_emc:7L}",
                VariantNormalizer.normalize(first));
        assertEquals(VariantNormalizer.normalize(first), VariantNormalizer.normalize(second));
    }

    @Test
    void nestedCompoundKeysAreCanonicalised() {
        CompoundTag nestedFirst = new CompoundTag();
        nestedFirst.putInt("z", 1);
        nestedFirst.putInt("a", 2);
        CompoundTag first = new CompoundTag();
        first.put("outer", nestedFirst);

        CompoundTag nestedSecond = new CompoundTag();
        nestedSecond.putInt("a", 2);
        nestedSecond.putInt("z", 1);
        CompoundTag second = new CompoundTag();
        second.put("outer", nestedSecond);

        assertEquals("{outer:{a:2,z:1}}", VariantNormalizer.normalize(first));
        assertEquals(VariantNormalizer.normalize(first), VariantNormalizer.normalize(second));
    }

    @Test
    void attributeModifiersDifferingOnlyByUuidNormaliseToSameString() {
        CompoundTag firstModifier = new CompoundTag();
        firstModifier.putString("AttributeName", "minecraft:generic.attack_damage");
        firstModifier.putString("Name", "sudoersemc:damage_bonus");
        firstModifier.putDouble("Amount", 2.0D);
        firstModifier.putInt("Operation", 0);
        firstModifier.putUUID("UUID", UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ListTag firstModifiers = new ListTag();
        firstModifiers.add(firstModifier);
        CompoundTag first = new CompoundTag();
        first.put("AttributeModifiers", firstModifiers);

        CompoundTag secondModifier = new CompoundTag();
        secondModifier.putUUID("UUID", UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
        secondModifier.putInt("Operation", 0);
        secondModifier.putDouble("Amount", 2.0D);
        secondModifier.putString("Name", "sudoersemc:damage_bonus");
        secondModifier.putString("AttributeName", "minecraft:generic.attack_damage");
        ListTag secondModifiers = new ListTag();
        secondModifiers.add(secondModifier);
        CompoundTag second = new CompoundTag();
        second.put("AttributeModifiers", secondModifiers);

        String firstHash = VariantNormalizer.normalize(first);
        String secondHash = VariantNormalizer.normalize(second);

        assertEquals(firstHash, secondHash);
        assertFalse(firstHash.contains("UUID"));
    }

    @Test
    void canonicalSnbtRoundTripsThroughTagParser() throws Exception {
        CompoundTag enchantment = new CompoundTag();
        enchantment.putString("id", "minecraft:unbreaking");
        enchantment.putShort("lvl", (short) 3);
        ListTag enchantments = new ListTag();
        enchantments.add(enchantment);

        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"会被丢弃\"}");

        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 12);
        tag.put("Enchantments", enchantments);
        tag.putLong("stored_emc", 1024L);
        tag.putByteArray("ByteArray", new byte[]{1, 2});
        tag.putIntArray("IntArray", new int[]{3, 4});
        tag.putLongArray("LongArray", new long[]{5L, 6L});
        tag.putString("weird key:with:colon", "value with \"quote\"");
        tag.put("display", display);

        String normalized = VariantNormalizer.normalize(tag);
        CompoundTag parsed = TagParser.parseTag(normalized);

        assertEquals(normalized, VariantNormalizer.normalize(parsed));
    }

    @Test
    void emptyTagAndNullTagNormaliseToNull() {
        assertNull(VariantNormalizer.normalize(new CompoundTag()));
        assertNull(VariantNormalizer.normalize(null));
    }

    @Test
    void keyForBuildsPlainKeyWhenNothingEconomicRemains() {
        CompoundTag displayOnly = new CompoundTag();
        displayOnly.put("display", new CompoundTag());

        assertEquals(ItemKey.of(SUDOERSEMC_TOKEN), VariantNormalizer.keyFor(SUDOERSEMC_TOKEN, displayOnly));

        CompoundTag damage = new CompoundTag();
        damage.putInt("Damage", 3);

        assertEquals(ItemKey.of(SUDOERSEMC_TOKEN, "{Damage:3}"),
                VariantNormalizer.keyFor(SUDOERSEMC_TOKEN, damage));
    }

    @Test
    void defaultDamageZeroOnlyNormalisesToNull() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 0);

        assertNull(VariantNormalizer.normalize(tag),
                "默认 {Damage:0} 只表示未受损，必须视为无变体");
        assertEquals(ItemKey.of(SUDOERSEMC_TOKEN),
                VariantNormalizer.keyFor(SUDOERSEMC_TOKEN, tag));
    }

    @Test
    void defaultDamageZeroIsStillRecognisedAfterDisplayStripping() {
        CompoundTag display = new CompoundTag();
        display.putString("Name", "{\"text\":\"名称\"}");

        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 0);
        tag.put("display", display);

        assertNull(VariantNormalizer.normalize(tag));
    }

    @Test
    void defaultDamageIsKeptWhenOtherEconomicKeysArePresent() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Damage", 0);
        tag.putLong("stored_emc", 64L);

        assertEquals("{Damage:0,stored_emc:64L}", VariantNormalizer.normalize(tag),
                "存在其它经济字段时 Damage 必须作为组合变体的一部分保留");
    }
}
