package com.itzplana.sudoersemc.integration;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.diagnostic.DebugLog;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITag;
import net.minecraftforge.registries.tags.ITagManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 从 ProjectE-Integration（模组 id {@code projecteintegration}）的数据包中读取外部基线。
 *
 * <p>本类刻意不 import 任何 ProjectE-Integration 类型：PEI 只通过 {@link ModList#isLoaded(String)}
 * 检测，数据则来自标准资源管理器中的 {@code pe_custom_conversions} 目录下的 JSON 文件。
 * 这样 PEI 未安装时类加载与运行都不需要它的 jar。</p>
 *
 * <p>读取 {@code values.before} 中形如 {@code namespace:path -> 正整数} 的条目。{@code #tag}
 * 条目会通过物品标签展开为标签内的元素物品；直接物品条目始终优先于标签展开值。标签未绑定、
 * 为空或解析失败时计入 {@link #skippedTagCount()} 并记录限流警告，不会静默丢掉。
 * 单条损坏只跳过该条，单个文件损坏只跳过该文件。整体失败时降级为空基线，并用限流 warning
 * 记录一次。</p>
 *
 * <p><strong>线程模型：</strong>标签展开读取 Forge 物品标签注册表，{@link #prepare(MinecraftServer)}
 * 必须由服务器线程调用（与 {@code ScanPipeline} 的采集阶段一致）。</p>
 */
public final class ProjectEIntegrationBaselineSource implements ExternalBaselineSource {

    public static final String MOD_ID = "projecteintegration";

    /** 资源目录，与 PEI 的 datagen 输出保持一致。 */
    static final String RESOURCE_DIRECTORY = "pe_custom_conversions";

    private static final Logger LOGGER =
            LoggerFactory.getLogger("sudoersemc/integration/ProjectEIntegrationBaselineSource");

    private static final long WARNING_INTERVAL_MILLIS = 60_000L;

    private static final List<String> UNAVAILABLE_FINGERPRINT =
            List.of("external-baseline=unavailable");
    private static final List<String> EMPTY_FINGERPRINT =
            List.of("external-baseline=empty");
    private static final List<String> ERROR_FINGERPRINT =
            List.of("external-baseline=error");

    private volatile Map<ItemKey, Long> values = Map.of();
    private volatile List<String> fingerprint = UNAVAILABLE_FINGERPRINT;
    private volatile int skippedTagCount;
    private final AtomicLong lookupCalls = new AtomicLong();
    private final BooleanSupplier backendAvailabilityProbe;
    private final Function<ResourceLocation, ? extends Collection<ItemKey>> tagExpander;
    private volatile long lastWarningAtMillis;

    public ProjectEIntegrationBaselineSource() {
        this(() -> OptionalIntegrationManager.provider()
                        .filter(EmcProvider::isAvailable)
                        .isPresent(),
                ProjectEIntegrationBaselineSource::resolveForgeItemTag);
    }

    /**
     * 测试/嵌入式构造器：注入后端可用性判定，标签展开仍使用 Forge 物品标签注册表。
     */
    ProjectEIntegrationBaselineSource(@Nullable BooleanSupplier backendAvailabilityProbe) {
        this(backendAvailabilityProbe, ProjectEIntegrationBaselineSource::resolveForgeItemTag);
    }

    /**
     * 测试/嵌入式构造器：同时注入后端可用性判定与标签展开器。
     *
     * @param backendAvailabilityProbe 后端可用性判定，{@code null} 视为不可用
     * @param tagExpander              标签 id → 元素物品；{@code null} 表示不展开任何标签
     */
    ProjectEIntegrationBaselineSource(
            @Nullable BooleanSupplier backendAvailabilityProbe,
            @Nullable Function<ResourceLocation, ? extends Collection<ItemKey>> tagExpander) {
        this.backendAvailabilityProbe = backendAvailabilityProbe == null
                ? () -> false
                : backendAvailabilityProbe;
        this.tagExpander = tagExpander == null ? tagId -> List.of() : tagExpander;
    }

    @Override
    public String id() {
        return MOD_ID;
    }

    @Override
    public boolean isAvailable() {
        return !values.isEmpty();
    }

    @Override
    public void prepare(@Nullable MinecraftServer server) {
        if (server == null || !isPeiLoaded() || !isProjectEBackendAvailable()) {
            values = Map.of();
            fingerprint = UNAVAILABLE_FINGERPRINT;
            skippedTagCount = 0;
            return;
        }
        try {
            ResourceManager resources = server.getResourceManager();
            Map<ResourceLocation, Resource> candidates = resources.listResources(
                    RESOURCE_DIRECTORY, id -> id.getPath().endsWith(".json"));
            Map<ItemKey, Long> directValues = new TreeMap<>();
            Map<ResourceLocation, Long> tagValues = new TreeMap<>();
            int failedFiles = 0;
            // 确定性顺序：同一物品在多个文件中出现时，覆盖结果不依赖 ResourceManager 的遍历实现。
            for (ResourceLocation resourceId : orderedCandidateIds(candidates.keySet())) {
                ParsedBaseline parsed = parseResource(candidates.get(resourceId));
                if (parsed == null) {
                    failedFiles++;
                    continue;
                }
                directValues.putAll(parsed.values());
                tagValues.putAll(parsed.tags());
            }

            TagExpansion expansion = expandTagValues(directValues, tagValues, tagExpander);
            skippedTagCount = expansion.skippedTags();
            Map<ItemKey, Long> loaded = expansion.values();
            values = loaded.isEmpty() ? Map.of() : Collections.unmodifiableMap(loaded);
            fingerprint = loaded.isEmpty() ? EMPTY_FINGERPRINT : fingerprintFor(loaded);
            if (!loaded.isEmpty()) {
                LOGGER.info(
                        "[external-baseline] 读取到 {} 条 ProjectE-Integration 外部基线"
                                + "（直接条目 {}，标签条目 {}）",
                        loaded.size(), directValues.size(), tagValues.size());
            }
            if (skippedTagCount > 0) {
                warnThrottled("[external-baseline] 有 " + skippedTagCount
                        + " 个 ProjectE-Integration #tag 基线无法展开（标签未绑定、为空或解析失败），已跳过",
                        null);
            }
            if (loaded.isEmpty() && failedFiles > 0) {
                warnThrottled("[external-baseline] 所有 PEI 基线资源均无法解析，按空基线处理", null);
            } else if (loaded.isEmpty()) {
                LOGGER.debug("[external-baseline] PEI 未提供任何 pe_custom_conversions 基线");
            }
        } catch (Throwable failure) {
            values = Map.of();
            fingerprint = ERROR_FINGERPRINT;
            skippedTagCount = 0;
            warnThrottled("[external-baseline] 读取 ProjectE-Integration 外部基线失败，按空基线处理",
                    failure);
        }
    }

    @Override
    public OptionalLong lookup(@Nullable ItemKey item) {
        if (item == null) {
            return OptionalLong.empty();
        }
        Long value = values.get(item);
        if (DebugLog.isEnabled()) {
            long count = lookupCalls.incrementAndGet();
            if (count <= 20L || count % 500L == 0L) {
                DebugLog.log("external-baseline",
                        () -> "lookup item=" + item + " hit=" + (value != null));
            }
        }
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    @Override
    public List<String> fingerprintEntries() {
        return fingerprint;
    }

    @Override
    public int entryCount() {
        return values.size();
    }

    /**
     * @return 本次 {@link #prepare(MinecraftServer)} 中无法展开的 {@code #tag} 条目数；
     *         未准备或准备失败时为 0
     */
    public int skippedTagCount() {
        return skippedTagCount;
    }

    /**
     * @return 当前缓存的不可变基线映射；主要供诊断/测试读取
     */
    public Map<ItemKey, Long> values() {
        return values;
    }

    /**
     * 解析一份 PEI JSON 文本中的直接物品条目。{@code #tag} 条目不会出现在返回值中。
     *
     * <p>需要完整信息的调用方应使用 {@link #parseBaseline(String)}。</p>
     *
     * @param json JSON 文本，允许为 {@code null}
     * @return 不可变、按键排序的直接物品基线映射
     */
    public static Map<ItemKey, Long> parseJson(@Nullable String json) {
        return parseBaseline(json).values();
    }

    /**
     * 解析一份 PEI JSON 文本，同时保留直接物品条目与 {@code #tag} 条目。
     *
     * <p>纯 JSON 解析；不访问物品注册表。标签展开由 {@link #expandTagValues} 在服务器线程完成。</p>
     *
     * @param json JSON 文本，允许为 {@code null}
     * @return 解析结果；格式错误时返回空结果
     */
    static ParsedBaseline parseBaseline(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return ParsedBaseline.EMPTY;
        }
        try {
            JsonElement root = JsonParser.parseReader(new StringReader(json));
            Map<ItemKey, Long> items = new TreeMap<>();
            Map<ResourceLocation, Long> tags = new TreeMap<>();
            collectValues(root, items, tags);
            return new ParsedBaseline(items, tags);
        } catch (Throwable failure) {
            LOGGER.warn("[external-baseline] 无法解析 PEI 基线 JSON，按空处理：{}",
                    failure.getMessage());
            return ParsedBaseline.EMPTY;
        }
    }

    /**
     * 把 {@code #tag} 条目展开为元素物品并并入直接条目。
     *
     * <p>直接物品条目永远优先：标签展开只填补没有直接值的物品。多个标签包含同一物品时，
     * 按标签 id 排序后第一个命中的标签获胜，保证结果可复现。空标签、未绑定标签与展开异常
     * 都会计入 {@code skippedTags}，不会静默丢失。</p>
     *
     * @param directValues 直接物品条目，允许为 {@code null}
     * @param tagValues    标签条目，允许为 {@code null}
     * @param expander     标签展开器，允许为 {@code null}（表示全部标签都跳过）
     * @return 合并后的有序映射与跳过计数
     */
    static TagExpansion expandTagValues(@Nullable Map<ItemKey, Long> directValues,
                                        @Nullable Map<ResourceLocation, Long> tagValues,
                                        @Nullable Function<ResourceLocation,
                                                ? extends Collection<ItemKey>> expander) {
        Map<ItemKey, Long> merged = new TreeMap<>();
        if (directValues != null) {
            merged.putAll(directValues);
        }
        if (tagValues == null || tagValues.isEmpty()) {
            return new TagExpansion(merged, 0);
        }
        int skipped = 0;
        for (Map.Entry<ResourceLocation, Long> entry : new TreeMap<>(tagValues).entrySet()) {
            ResourceLocation tagId = entry.getKey();
            Long value = entry.getValue();
            if (tagId == null || value == null || value <= 0L || expander == null) {
                skipped++;
                continue;
            }
            Collection<ItemKey> members;
            try {
                members = expander.apply(tagId);
            } catch (Throwable failure) {
                members = null;
            }
            if (members == null || members.isEmpty()) {
                skipped++;
                continue;
            }
            for (ItemKey member : members) {
                if (member != null) {
                    merged.putIfAbsent(member, value);
                }
            }
        }
        return new TagExpansion(merged, skipped);
    }

    /**
     * 按 {@link ResourceLocation} 的字符串形式排序，保证多个数据包/文件覆盖同一物品时结果稳定。
     *
     * @param ids 候选资源 id，允许为 {@code null}
     * @return 去空、排序后的不可变列表
     */
    static List<ResourceLocation> orderedCandidateIds(@Nullable Collection<ResourceLocation> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<ResourceLocation> ordered = new ArrayList<>(ids.size());
        for (ResourceLocation id : ids) {
            if (id != null) {
                ordered.add(id);
            }
        }
        ordered.sort(Comparator.comparing(ResourceLocation::toString));
        return List.copyOf(ordered);
    }

    /**
     * 为一组基线值生成稳定的指纹条目。
     *
     * @param values 基线映射，允许为 {@code null}
     * @return 排序后的 {@code pe:<item>=<value>} 条目；空映射返回固定标记
     */
    public static List<String> fingerprintFor(@Nullable Map<ItemKey, Long> values) {
        if (values == null || values.isEmpty()) {
            return EMPTY_FINGERPRINT;
        }
        List<String> entries = new ArrayList<>(values.size());
        for (Map.Entry<ItemKey, Long> entry : new TreeMap<>(values).entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0L) {
                entries.add("pe:" + entry.getKey() + "=" + entry.getValue());
            }
        }
        if (entries.isEmpty()) {
            return EMPTY_FINGERPRINT;
        }
        Collections.sort(entries);
        return List.copyOf(entries);
    }

    @Nullable
    private static ParsedBaseline parseResource(@Nullable Resource resource) {
        if (resource == null) {
            return null;
        }
        try (InputStream stream = resource.open();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            Map<ItemKey, Long> items = new TreeMap<>();
            Map<ResourceLocation, Long> tags = new TreeMap<>();
            collectValues(root, items, tags);
            return new ParsedBaseline(items, tags);
        } catch (Throwable failure) {
            LOGGER.debug("[external-baseline] 跳过无法解析的 PEI 基线资源 {}：{}",
                    resource.sourcePackId(), failure.getMessage());
            return null;
        }
    }

    private static void collectValues(@Nullable JsonElement root,
                                      Map<ItemKey, Long> itemTarget,
                                      Map<ResourceLocation, Long> tagTarget) {
        if (root == null || !root.isJsonObject()) {
            return;
        }
        JsonObject object = root.getAsJsonObject();
        JsonElement valuesElement = object.get("values");
        if (valuesElement == null || !valuesElement.isJsonObject()) {
            return;
        }
        JsonElement beforeElement = valuesElement.getAsJsonObject().get("before");
        if (beforeElement == null || !beforeElement.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> entry : beforeElement.getAsJsonObject().entrySet()) {
            String rawKey = entry.getKey();
            if (rawKey == null || rawKey.isBlank()) {
                continue;
            }
            JsonElement valueElement = entry.getValue();
            if (valueElement == null || !valueElement.isJsonPrimitive()) {
                continue;
            }
            JsonPrimitive primitive = valueElement.getAsJsonPrimitive();
            if (!primitive.isNumber()) {
                continue;
            }
            long value;
            try {
                value = primitive.getAsLong();
            } catch (RuntimeException ignored) {
                continue;
            }
            if (value <= 0L) {
                continue;
            }
            if (rawKey.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(rawKey.substring(1));
                if (tagId != null) {
                    tagTarget.put(tagId, value);
                }
                continue;
            }
            ResourceLocation id = ResourceLocation.tryParse(rawKey);
            if (id != null) {
                itemTarget.put(ItemKey.of(id), value);
            }
        }
    }

    /**
     * 生产环境标签展开：读取 Forge 物品标签注册表。标签尚未绑定或解析失败时返回空集合，
     * 由调用方计入 skipped-tag。
     */
    static Collection<ItemKey> resolveForgeItemTag(@Nullable ResourceLocation tagId) {
        if (tagId == null) {
            return List.of();
        }
        try {
            ITagManager<Item> tagManager = ForgeRegistries.ITEMS.tags();
            if (tagManager == null) {
                return List.of();
            }
            TagKey<Item> tagKey = tagManager.createTagKey(tagId);
            ITag<Item> tag = tagManager.getTag(tagKey);
            if (tag == null || !tag.isBound() || tag.isEmpty()) {
                return List.of();
            }
            List<ItemKey> members = new ArrayList<>(tag.size());
            for (Item item : tag) {
                ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
                if (id != null) {
                    members.add(ItemKey.of(id));
                }
            }
            return members;
        } catch (Throwable failure) {
            return List.of();
        }
    }

    private static boolean isPeiLoaded() {
        try {
            return ModList.get().isLoaded(MOD_ID);
        } catch (Throwable failure) {
            return false;
        }
    }

    /**
     * ProjectE 仅“存在 jar”还不够：EMC API/provider 必须真的初始化成功。provider 为空时
     * 读取 baseline 只会产生无法生效的值，因此按不可用处理。
     */
    boolean isProjectEBackendAvailable() {
        try {
            return backendAvailabilityProbe.getAsBoolean();
        } catch (Throwable failure) {
            return false;
        }
    }

    private void warnThrottled(String message, @Nullable Throwable failure) {
        long now = System.currentTimeMillis();
        long last = lastWarningAtMillis;
        if (last > 0L && now - last < WARNING_INTERVAL_MILLIS) {
            return;
        }
        lastWarningAtMillis = now;
        if (failure == null) {
            LOGGER.warn(message);
        } else {
            LOGGER.warn(message, failure);
        }
    }

    /**
     * 一份 PEI 基线文件的解析结果。
     *
     * @param values 直接物品条目
     * @param tags   {@code #tag} 条目（键不含 {@code #}）
     */
    record ParsedBaseline(Map<ItemKey, Long> values, Map<ResourceLocation, Long> tags) {

        static final ParsedBaseline EMPTY = new ParsedBaseline(Map.of(), Map.of());

        ParsedBaseline {
            values = values == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new TreeMap<>(values));
            tags = tags == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new TreeMap<>(tags));
        }
    }

    /**
     * 标签展开结果。
     *
     * @param values      合并后的有序基线映射
     * @param skippedTags 无法展开的标签条目数
     */
    record TagExpansion(Map<ItemKey, Long> values, int skippedTags) {
    }
}
