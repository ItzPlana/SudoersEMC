package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link ScanFingerprint} 的稳定性与字段比较验证。
 *
 * <p>重点验证顺序无关性、内容敏感性、空输入常量、空元素过滤，以及
 * {@link ScanFingerprint#equalsFingerprint(ScanFingerprint)} 对五个字段的完整比较。</p>
 */
class ScanFingerprintTest {

    @Test
    void hashIsOrderIndependent() {
        assertEquals(
                ScanFingerprint.hash(List.of("minecraft:stone", "minecraft:dirt")),
                ScanFingerprint.hash(List.of("minecraft:dirt", "minecraft:stone")),
                "排序后同一组输入必须得到相同摘要");
    }

    @Test
    void differentContentProducesDifferentHash() {
        assertNotEquals(
                ScanFingerprint.hash(List.of("minecraft:stone", "minecraft:dirt")),
                ScanFingerprint.hash(List.of("minecraft:stone", "minecraft:gravel")));
    }

    @Test
    void separatorPreventsConcatenationCollisions() {
        assertNotEquals(
                ScanFingerprint.hash(List.of("ab", "c")),
                ScanFingerprint.hash(List.of("a", "bc")),
                "空字节分隔符必须阻止边界移动造成的碰撞");
    }

    @Test
    void emptyInputUsesEmptyConstant() {
        assertEquals("empty", ScanFingerprint.hash(List.of()));
        assertEquals("empty", ScanFingerprint.hash(null));
    }

    @Test
    void nullElementsAreSkipped() {
        assertEquals(
                ScanFingerprint.hash(List.of("minecraft:stone", "minecraft:dirt")),
                ScanFingerprint.hash(Arrays.asList("minecraft:stone", null, "minecraft:dirt")));
    }

    @Test
    void hashIsTruncatedSha256Hex() {
        String hash = ScanFingerprint.hash(List.of("minecraft:stone"));

        assertEquals(32, hash.length());
        assertTrue(hash.matches("[0-9a-f]{32}"), "摘要应为小写十六进制的前 32 位");
    }

    @Test
    void equalsFingerprintComparesEveryField() {
        ScanFingerprint base = new ScanFingerprint(
                "items-1", "recipes-1", "tags-1", "config-1", "baseline-1");

        assertTrue(base.equalsFingerprint(
                new ScanFingerprint("items-1", "recipes-1", "tags-1", "config-1", "baseline-1")));
        assertFalse(base.equalsFingerprint(
                new ScanFingerprint("items-2", "recipes-1", "tags-1", "config-1", "baseline-1")));
        assertFalse(base.equalsFingerprint(
                new ScanFingerprint("items-1", "recipes-2", "tags-1", "config-1", "baseline-1")));
        assertFalse(base.equalsFingerprint(
                new ScanFingerprint("items-1", "recipes-1", "tags-2", "config-1", "baseline-1")));
        assertFalse(base.equalsFingerprint(
                new ScanFingerprint("items-1", "recipes-1", "tags-1", "config-2", "baseline-1")));
        assertFalse(base.equalsFingerprint(
                new ScanFingerprint("items-1", "recipes-1", "tags-1", "config-1", "baseline-2")));
        assertFalse(base.equalsFingerprint(null), "null 必须安全地返回 false");
    }

    @Test
    void isEmptyOnlyWhenEveryFieldIsEmptyConstant() {
        assertTrue(new ScanFingerprint("empty", "empty", "empty", "empty", "empty").isEmpty());
        assertFalse(new ScanFingerprint("items-1", "empty", "empty", "empty", "empty").isEmpty());
        assertFalse(new ScanFingerprint("empty", "empty", "empty", "empty", "").isEmpty());
        assertTrue(ScanFingerprint.of(List.of(), List.of(), List.of(), List.of(), List.of()).isEmpty());
    }

    @Test
    void nullFieldIsNormalizedToEmptyConstant() {
        ScanFingerprint fingerprint = new ScanFingerprint(null, null, null, null, null);

        assertTrue(fingerprint.isEmpty());
    }

    @Test
    void ofHashesEachChannelIndependently() {
        ScanFingerprint fingerprint = ScanFingerprint.of(
                List.of("minecraft:stone"),
                List.of("recipe:a"),
                List.of("tag:x"),
                List.of("config=1"),
                List.of("baseline=2"));

        assertFalse(fingerprint.isEmpty());
        assertEquals(ScanFingerprint.hash(List.of("minecraft:stone")), fingerprint.itemFingerprint());
        assertEquals(ScanFingerprint.hash(List.of("recipe:a")), fingerprint.recipeFingerprint());
        assertEquals(ScanFingerprint.hash(List.of("tag:x")), fingerprint.tagFingerprint());
        assertEquals(ScanFingerprint.hash(List.of("config=1")), fingerprint.configFingerprint());
        assertEquals(ScanFingerprint.hash(List.of("baseline=2")), fingerprint.baselineFingerprint());
    }
}
