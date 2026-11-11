package com.itzplana.sudoersemc.task;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一次扫描输入指纹的不可变集合。
 *
 * <p>五个字段分别覆盖物品、配方、标签、配置与基线来源。每个字段都是对应输入集合经过
 * “过滤空值 → 升序排序 → 以 {@code \u0000} 拼接 → SHA-256 → 取十六进制前 32 位”得到的
 * 摘要。排序保证同一组输入不受遍历顺序影响，空字节分隔避免 {@code ["ab","c"]} 与
 * {@code ["a","bc"]} 产生相同摘要。</p>
 *
 * <p>下一次启动时重新计算指纹并用 {@link #equalsFingerprint(ScanFingerprint)} 比较；五个
 * 字段完全一致时可以跳过重新扫描并复用上次结果，避免无意义的全量注册表遍历。指纹只描述
 * 输入内容，不含时间戳、随机数或对象身份，因此同一组输入在任何进程中都会得到相同结果。</p>
 *
 * @param itemFingerprint     物品注册表输入摘要
 * @param recipeFingerprint   配方输入摘要
 * @param tagFingerprint      标签输入摘要
 * @param configFingerprint   影响定价的配置摘要
 * @param baselineFingerprint 外部基线来源摘要
 */
public record ScanFingerprint(
        String itemFingerprint,
        String recipeFingerprint,
        String tagFingerprint,
        String configFingerprint,
        String baselineFingerprint
) {

    /**
     * 空输入集合的固定摘要。使用固定字符串而不是空串，便于日志中区分“没有输入”与
     * “摘要计算失败”。
     */
    public static final String EMPTY = "empty";

    /** SHA-256 十六进制摘要保留的字符数。 */
    private static final int HASH_LENGTH = 32;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    public ScanFingerprint {
        itemFingerprint = normalize(itemFingerprint);
        recipeFingerprint = normalize(recipeFingerprint);
        tagFingerprint = normalize(tagFingerprint);
        configFingerprint = normalize(configFingerprint);
        baselineFingerprint = normalize(baselineFingerprint);
    }

    /**
     * 计算一组字符串输入的稳定摘要。
     *
     * <p>{@code null} 元素会被跳过；剩余元素按自然顺序升序排序后以空字节拼接。输入为空、
     * 只包含 {@code null}，或 {@code values} 本身为 {@code null} 时返回
     * {@link #EMPTY}。重复元素不会被去重，因为重复本身也是输入内容的一部分。</p>
     *
     * @param values 输入值集合，可为 {@code null}
     * @return 32 位小写十六进制摘要，或 {@link #EMPTY}
     */
    public static String hash(Iterable<String> values) {
        if (values == null) {
            return EMPTY;
        }

        List<String> collected = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                collected.add(value);
            }
        }
        if (collected.isEmpty()) {
            return EMPTY;
        }

        Collections.sort(collected);
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < collected.size(); i++) {
            if (i > 0) {
                joined.append('\u0000');
            }
            joined.append(collected.get(i));
        }

        byte[] digest = sha256(joined.toString());
        StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte value : digest) {
            hex.append(HEX_DIGITS[(value >>> 4) & 0x0F]);
            hex.append(HEX_DIGITS[value & 0x0F]);
        }
        return hex.substring(0, HASH_LENGTH);
    }

    /**
     * 构建五个通道的指纹。
     *
     * @param items    物品输入
     * @param recipes  配方输入
     * @param tags     标签输入
     * @param config   配置输入
     * @param baseline 基线输入
     * @return 各通道分别取摘要后的指纹集合
     */
    public static ScanFingerprint of(Iterable<String> items,
                                     Iterable<String> recipes,
                                     Iterable<String> tags,
                                     Iterable<String> config,
                                     Iterable<String> baseline) {
        return new ScanFingerprint(
                hash(items),
                hash(recipes),
                hash(tags),
                hash(config),
                hash(baseline));
    }

    /**
     * 比较两个指纹是否描述同一组扫描输入。
     *
     * @param other 待比较对象，可为 {@code null}
     * @return 五个字段全部相等时返回 {@code true}；{@code other} 为 {@code null} 时返回
     *         {@code false}
     */
    public boolean equalsFingerprint(ScanFingerprint other) {
        return other != null
                && Objects.equals(itemFingerprint, other.itemFingerprint)
                && Objects.equals(recipeFingerprint, other.recipeFingerprint)
                && Objects.equals(tagFingerprint, other.tagFingerprint)
                && Objects.equals(configFingerprint, other.configFingerprint)
                && Objects.equals(baselineFingerprint, other.baselineFingerprint);
    }

    /**
     * @return 五个通道都等于 {@link #EMPTY} 时返回 {@code true}
     */
    public boolean isEmpty() {
        return EMPTY.equals(itemFingerprint)
                && EMPTY.equals(recipeFingerprint)
                && EMPTY.equals(tagFingerprint)
                && EMPTY.equals(configFingerprint)
                && EMPTY.equals(baselineFingerprint);
    }

    /**
     * 将 {@code null} 归一化为空摘要；空串保持原样，因为它不是由 {@link #hash} 产生的值。
     */
    private static String normalize(String value) {
        return value == null ? EMPTY : value;
    }

    /**
     * 计算 SHA-256。JDK 必须提供该算法；缺失属于运行环境损坏，因此转换为
     * {@link IllegalStateException} 而不是受检异常。
     */
    private static byte[] sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
