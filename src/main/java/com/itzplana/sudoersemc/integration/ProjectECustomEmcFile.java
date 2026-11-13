package com.itzplana.sudoersemc.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.storage.JsonFileIO;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * ProjectE {@code config/ProjectE/custom_emc.json} 的受控改写器。
 *
 * <p>只有当管理员显式选择 {@code entire_custom_emc} 作用域并允许改写 ProjectE 文件时，上层才会
 * 调用本类。改写遵循以下约束：</p>
 * <ul>
 *   <li><strong>红线：</strong>只重置 {@code owned} 判定为 SudoersEMC 自有的条目；管理员手动
 *       配置的条目即使超过阈值也永远保持原值，只计数并汇总告警；</li>
 *   <li>只处理与 {@link ProjectEManualValues} 相同结构中的条目：顶层 {@code entries} 数组，或
 *       直接以数组为根；条目格式为 {@code {"item": "...", "emc": 123}}；</li>
 *   <li>只有严格大于阈值的数字 EMC（JSON 数字或数字字符串，解析口径与
 *       {@link ProjectEManualValues} 一致）才会进入 {@link OverflowProtection#applyIdempotent(long)}；</li>
 *   <li>使用 Gson 树模型原地替换数值，未知字段与键顺序保持不变；</li>
 *   <li>写入前先做时间戳备份，再原子替换；没有任何改动时不触碰磁盘；</li>
 *   <li>文件缺失、内容损坏或结构不符合预期时保持原文件不变，绝不创建或覆盖。</li>
 * </ul>
 *
 * <p>本类不引用任何 {@code moze_intel.projecte} 类型，因此即使 ProjectE 未安装也可以安全加载。</p>
 */
public final class ProjectECustomEmcFile {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** 防御性上限，避免损坏或异常巨大的文件耗尽内存。 */
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;

    private static final String ENTRIES_KEY = "entries";
    private static final String ITEM_KEY = "item";
    private static final String EMC_KEY = "emc";

    private final Path file;

    /**
     * 生产环境构造器，使用与 {@link ProjectEManualValues} 相同的路径解析。
     */
    public ProjectECustomEmcFile() {
        this(ProjectEManualValues.defaultConfigPath());
    }

    /**
     * 测试/嵌入构造器。
     *
     * @param file 要改写的 custom_emc.json 路径
     */
    public ProjectECustomEmcFile(Path file) {
        this.file = Objects.requireNonNull(file, "file");
    }

    /**
     * @return 被管理的文件路径，主要用于诊断与测试
     */
    public Path file() {
        return file;
    }

    /**
     * 改写所有严格超过阈值且归 SudoersEMC 所有的条目。
     *
     * <p>非自有（管理员手动）条目始终保留原值，只计入 {@link RewriteResult#notOwned()}。</p>
     *
     * @param protection 溢出策略，不能为 {@code null}
     * @param owned      归属判定，不能为 {@code null}
     * @return 改写统计；文件缺失、损坏或无需改动时 {@code wroteFile} 为 {@code false}
     */
    public RewriteResult rewriteAboveThreshold(OverflowProtection protection,
                                               Predicate<ItemKey> owned) {
        return rewriteAboveThreshold(protection, owned, null);
    }

    /**
     * 改写所有严格超过阈值且归 SudoersEMC 所有的条目，并在成功写盘后逐条通知监听器。
     *
     * <p>通知发生在文件替换成功之后，因此不会为失败或被取消的写入产生审计记录。
     * 非自有条目永远不会被写入，只计入 {@link RewriteResult#notOwned()}。</p>
     *
     * @param protection 溢出策略，不能为 {@code null}
     * @param owned      归属判定，不能为 {@code null}
     * @param listener   每条实际重置的回调，允许为 {@code null}
     * @return 改写统计；文件缺失、损坏或无需改动时 {@code wroteFile} 为 {@code false}
     */
    public RewriteResult rewriteAboveThreshold(OverflowProtection protection,
                                               Predicate<ItemKey> owned,
                                               @Nullable ResetListener listener) {
        Objects.requireNonNull(protection, "protection");
        Objects.requireNonNull(owned, "owned");
        RewriteResult nothing = new RewriteResult(0, 0, 0, false, null);

        if (!Files.isRegularFile(file)) {
            LOGGER.debug("ProjectE 手动 EMC 文件 {} 不存在，跳过改写", file);
            return nothing;
        }

        String json;
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_BYTES) {
                LOGGER.warn("ProjectE 手动 EMC 文件 {} 为 {} 字节，超过 {} 字节安全上限，跳过改写",
                        file, size, MAX_FILE_BYTES);
                return nothing;
            }
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (Throwable failure) {
            LOGGER.warn("无法读取 ProjectE 手动 EMC 文件 {}，保持原文件不变", file, failure);
            return nothing;
        }

        JsonElement rootElement;
        try {
            rootElement = JsonParser.parseString(json);
        } catch (Throwable failure) {
            // 损坏的 JSON 绝不覆盖：ProjectE 的手动配置比本轮溢出保护更重要。
            LOGGER.warn("无法解析 ProjectE 手动 EMC 文件 {}，保持原文件不变", file, failure);
            return nothing;
        }

        JsonObject root = null;
        JsonArray entries;
        if (rootElement.isJsonArray()) {
            entries = rootElement.getAsJsonArray();
        } else if (rootElement.isJsonObject()) {
            root = rootElement.getAsJsonObject();
            JsonElement entriesElement = root.get(ENTRIES_KEY);
            if (entriesElement == null) {
                LOGGER.warn("ProjectE 手动 EMC 文件 {} 缺少 \"{}\" 数组，跳过改写", file, ENTRIES_KEY);
                return nothing;
            }
            if (!entriesElement.isJsonArray()) {
                LOGGER.warn("ProjectE 手动 EMC 文件 {} 的 \"{}\" 不是数组，跳过改写", file, ENTRIES_KEY);
                return nothing;
            }
            entries = entriesElement.getAsJsonArray();
        } else {
            LOGGER.debug("ProjectE 手动 EMC 文件 {} 不是 JSON 对象或数组，跳过改写", file);
            return nothing;
        }

        int skipped = 0;
        int notOwned = 0;
        List<PendingReset> pendingResets = new ArrayList<>();

        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                skipped++;
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            JsonElement itemElement = entry.get(ITEM_KEY);
            if (itemElement == null || !itemElement.isJsonPrimitive()
                    || !itemElement.getAsJsonPrimitive().isString()) {
                skipped++;
                continue;
            }
            ItemKey key = parseItemKey(itemElement.getAsString());
            if (key == null) {
                // 标签条目或非法物品 id：无法建立归属关系，原样保留。
                skipped++;
                continue;
            }
            Long currentValue = integerValue(entry.get(EMC_KEY));
            if (currentValue == null) {
                // 非数字、负数或无法解析：ProjectE 可能有自己的解释，保持原样。
                skipped++;
                continue;
            }
            if (!protection.isOverflow(currentValue)) {
                continue;
            }

            long resetValue;
            try {
                resetValue = protection.applyIdempotent(currentValue);
            } catch (RuntimeException failure) {
                LOGGER.debug("计算物品 {} 的溢出回落值失败，保持原值", key, failure);
                skipped++;
                continue;
            }
            if (resetValue == currentValue) {
                // 保护被禁用或策略未改变数值：不算一次改写。
                skipped++;
                continue;
            }

            boolean isOwned;
            try {
                isOwned = owned.test(key);
            } catch (RuntimeException failure) {
                // 归属判定异常时按非自有处理，避免误改管理员配置。
                LOGGER.debug("判定物品 {} 的归属失败，按非自有处理", key, failure);
                isOwned = false;
            }
            if (!isOwned) {
                // 红线：管理员手动值永不改写，只计数并在下面汇总告警。
                notOwned++;
                continue;
            }

            // 原地替换数值，保留条目中的其它字段与键顺序。
            entry.add(EMC_KEY, new JsonPrimitive(resetValue));
            pendingResets.add(new PendingReset(key, currentValue, resetValue));
        }

        if (notOwned > 0) {
            // 汇总一条告警，避免逐条刷屏；非自有条目始终不会被写入。
            LOGGER.warn("ProjectE 手动 EMC 文件 {} 中有 {} 个超限条目不属于 SudoersEMC，"
                            + "已保持原值（管理员手动配置永不改写）",
                    file, notOwned);
        }

        if (pendingResets.isEmpty()) {
            return new RewriteResult(0, skipped, notOwned, false, null);
        }

        Path backup = JsonFileIO.backupIfExistsAndReturn(file);
        if (backup == null) {
            LOGGER.warn("无法备份 ProjectE 手动 EMC 文件 {}，取消本次改写", file);
            return new RewriteResult(0, skipped, notOwned, false, null);
        }

        Object document = root == null ? entries : root;
        if (!JsonFileIO.writeAtomicallyAndReport(file, document)) {
            LOGGER.warn("写入 ProjectE 手动 EMC 文件 {} 失败，原文件保持不变（备份：{}）", file, backup);
            return new RewriteResult(0, skipped, notOwned, false, backup);
        }

        notifyResets(listener, pendingResets);
        LOGGER.info("已改写 ProjectE 手动 EMC 文件 {}：{} 个条目回落，{} 个条目跳过，"
                        + "{} 个非自有条目（备份：{}）",
                file, pendingResets.size(), skipped, notOwned, backup);
        return new RewriteResult(pendingResets.size(), skipped, notOwned, true, backup);
    }

    private static void notifyResets(@Nullable ResetListener listener, List<PendingReset> resets) {
        if (listener == null) {
            return;
        }
        for (PendingReset reset : resets) {
            try {
                listener.onReset(reset.item(), reset.originalValue(), reset.resetValue());
            } catch (RuntimeException failure) {
                LOGGER.warn("记录 ProjectE 手动 EMC 改写的审计回调失败（物品 {}）", reset.item(), failure);
            }
        }
    }

    /**
     * 解析 {@code minecraft:stone} 或 {@code minecraft:diamond_sword{Damage:0}}。
     *
     * <p>标签条目（以 {@code #} 开头）无法映射到单个 {@link ItemKey}，返回 {@code null}。</p>
     */
    private static @Nullable ItemKey parseItemKey(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty() || value.charAt(0) == '#') {
            return null;
        }
        int nbtStart = value.indexOf('{');
        String idPart = nbtStart >= 0 ? value.substring(0, nbtStart).trim() : value;
        String variant = nbtStart >= 0 ? value.substring(nbtStart).trim() : null;
        ResourceLocation itemId = ResourceLocation.tryParse(idPart);
        if (itemId == null) {
            return null;
        }
        return ItemKey.of(itemId, variant == null || variant.isEmpty() ? null : variant);
    }

    /**
     * 解析 EMC 数值，口径与 {@link ProjectEManualValues} 保持一致：JSON 数字或十进制数字字符串。
     *
     * <p>JSON 数字按 ProjectE 的 {@code long} 语义解析（小数截断，超大值抛异常时视为无效）；
     * 字符串会先去除首尾空白再按十进制整数解析。布尔、空白字符串、负数和越界数值都返回
     * {@code null}。接受这两种形式是为了避免 ProjectE 能识别的手动值（例如
     * {@code "emc": "6000"}）被静默跳过，导致超限条目永远无法进入归属判定与汇总告警。</p>
     */
    private static @Nullable Long integerValue(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (primitive.isNumber()) {
            try {
                long value = primitive.getAsLong();
                return value >= 0L ? value : null;
            } catch (RuntimeException failure) {
                return null;
            }
        }
        if (primitive.isString()) {
            try {
                long value = Long.parseLong(primitive.getAsString().trim());
                return value >= 0L ? value : null;
            } catch (NumberFormatException failure) {
                return null;
            }
        }
        return null;
    }

    /**
     * 一条等待写盘后审计的重置。
     */
    private record PendingReset(ItemKey item, long originalValue, long resetValue) {
    }

    /**
     * 改写结果的汇总。
     *
     * @param rewritten 实际写回的新值数量
     * @param skipped   格式不合法、非数字、负数或未产生数值变化的条目数量
     * @param notOwned  超过阈值但不属于 SudoersEMC 的条目数量；这些条目始终保留原值
     * @param wroteFile 是否真正替换了磁盘文件
     * @param backup    本次改写前创建的备份路径；未写盘时为 {@code null}
     */
    public record RewriteResult(int rewritten, int skipped, int notOwned, boolean wroteFile,
                                @Nullable Path backup) {

        public RewriteResult {
            if (rewritten < 0 || skipped < 0 || notOwned < 0) {
                throw new IllegalArgumentException("rewrite counters must be >= 0");
            }
        }
    }

    /**
     * 每条实际重置的审计回调。
     */
    @FunctionalInterface
    public interface ResetListener {

        /**
         * @param item          被改写的物品
         * @param originalValue 改写前的值
         * @param resetValue    写回的新值
         */
        void onReset(ItemKey item, long originalValue, long resetValue);
    }
}
