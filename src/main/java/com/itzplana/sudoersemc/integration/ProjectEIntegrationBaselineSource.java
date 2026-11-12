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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraftforge.fml.ModList;
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
 * <p>只读取 {@code values.before} 中形如 {@code namespace:path -> 正整数} 的条目。{@code #tag}
 * 键会被忽略，留给标签定价处理；单条损坏只跳过该条，单个文件损坏只跳过该文件。整体失败时
 * 降级为空基线，并用限流 warning 记录一次。</p>
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
    private final AtomicLong lookupCalls = new AtomicLong();
    private final BooleanSupplier backendAvailabilityProbe;
    private volatile long lastWarningAtMillis;

    public ProjectEIntegrationBaselineSource() {
        this(() -> OptionalIntegrationManager.provider()
                .filter(EmcProvider::isAvailable)
                .isPresent());
    }

    /**
     * 测试/嵌入式构造器：注入后端可用性判定。
     */
    ProjectEIntegrationBaselineSource(@Nullable BooleanSupplier backendAvailabilityProbe) {
        this.backendAvailabilityProbe = backendAvailabilityProbe == null
                ? () -> false
                : backendAvailabilityProbe;
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
            return;
        }
        try {
            ResourceManager resources = server.getResourceManager();
            Map<ResourceLocation, Resource> candidates = resources.listResources(
                    RESOURCE_DIRECTORY, id -> id.getPath().endsWith(".json"));
            Map<ItemKey, Long> loaded = new TreeMap<>();
            int failedFiles = 0;
            for (Map.Entry<ResourceLocation, Resource> entry : candidates.entrySet()) {
                if (!parseResource(entry.getValue(), loaded)) {
                    failedFiles++;
                }
            }
            values = loaded.isEmpty() ? Map.of() : Collections.unmodifiableMap(loaded);
            fingerprint = loaded.isEmpty() ? EMPTY_FINGERPRINT : fingerprintFor(loaded);
            if (!loaded.isEmpty()) {
                LOGGER.info("[external-baseline] 读取到 {} 条 ProjectE-Integration 外部基线", loaded.size());
            } else if (failedFiles > 0) {
                warnThrottled("[external-baseline] 所有 PEI 基线资源均无法解析，按空基线处理", null);
            } else {
                LOGGER.debug("[external-baseline] PEI 未提供任何 pe_custom_conversions 基线");
            }
        } catch (Throwable failure) {
            values = Map.of();
            fingerprint = ERROR_FINGERPRINT;
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
     * @return 当前缓存的不可变基线映射；主要供诊断/测试读取
     */
    public Map<ItemKey, Long> values() {
        return values;
    }

    /**
     * 解析一份 PEI JSON 文本。格式错误时返回空映射，不抛出异常。
     *
     * @param json JSON 文本，允许为 {@code null}
     * @return 不可变、按键排序的基线映射
     */
    public static Map<ItemKey, Long> parseJson(@Nullable String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            JsonElement root = JsonParser.parseReader(new StringReader(json));
            Map<ItemKey, Long> parsed = new TreeMap<>();
            collectValues(root, parsed);
            return parsed.isEmpty() ? Map.of() : Collections.unmodifiableMap(parsed);
        } catch (Throwable failure) {
            LOGGER.warn("[external-baseline] 无法解析 PEI 基线 JSON，按空处理：{}",
                    failure.getMessage());
            return Map.of();
        }
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

    private static boolean parseResource(@Nullable Resource resource, Map<ItemKey, Long> target) {
        if (resource == null) {
            return false;
        }
        try (InputStream stream = resource.open();
             InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            collectValues(root, target);
            return true;
        } catch (Throwable failure) {
            LOGGER.debug("[external-baseline] 跳过无法解析的 PEI 基线资源 {}：{}",
                    resource.sourcePackId(), failure.getMessage());
            return false;
        }
    }

    private static void collectValues(@Nullable JsonElement root, Map<ItemKey, Long> target) {
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
            if (rawKey == null || rawKey.startsWith("#")) {
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
            ResourceLocation id = ResourceLocation.tryParse(rawKey);
            if (id == null) {
                continue;
            }
            target.put(ItemKey.of(id), value);
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
}
