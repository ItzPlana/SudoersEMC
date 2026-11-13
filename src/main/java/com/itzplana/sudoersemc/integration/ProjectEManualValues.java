package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.logging.LogUtils;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.tags.ITag;
import net.minecraftforge.registries.tags.ITagManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Read-only view of ProjectE's manual EMC overrides in {@code config/ProjectE/custom_emc.json}.
 *
 * <p>The file is owned by ProjectE and may contain plain item ids, NBT-qualified item ids and item
 * tags. SudoersEMC must never overwrite values an administrator configured there, so this class
 * answers a single conservative question: "did a human pin this item's value?".</p>
 *
 * <p>The parser is deliberately forgiving: a missing, unreadable or corrupt file simply yields an
 * empty snapshot plus one warning. Parsing must never throw into ProjectE's mapping pass or into
 * gameplay code.</p>
 *
 * <p>This class intentionally references no {@code moze_intel.projecte} type, so it is safe to load
 * when ProjectE is not installed.</p>
 */
public final class ProjectEManualValues {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Defensive cap so a corrupted/huge file cannot exhaust memory. */
    private static final long MAX_FILE_BYTES = 16L * 1024L * 1024L;

    private final Path configFile;

    private final Set<ResourceLocation> unresolvedTagWarned = ConcurrentHashMap.newKeySet();

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    /** 手工值文件内容的稳定摘要，用于扫描输入指纹的基线通道。 */
    private volatile String contentFingerprint = "missing";

    // Cache key: file mtime + size. Guarded by "this".
    private boolean loaded;
    private boolean lastMissing = true;
    private long lastModified = Long.MIN_VALUE;
    private long lastSize = Long.MIN_VALUE;
    private boolean readFailureWarned;

    public ProjectEManualValues() {
        this(defaultConfigPath());
    }

    /**
     * Test/utility constructor.
     */
    public ProjectEManualValues(Path configFile) {
        this.configFile = Objects.requireNonNull(configFile, "configFile");
    }

    static Path defaultConfigPath() {
        try {
            return FMLPaths.CONFIGDIR.get().resolve("ProjectE").resolve("custom_emc.json");
        } catch (Throwable t) {
            // FML is not initialized (unit tests, tooling). Use the conventional relative path.
            return Paths.get("config", "ProjectE", "custom_emc.json");
        }
    }

    /**
     * Reloads the manual value file when its mtime or size changed since the last successful (or
     * failed) read. Safe to call frequently; a cheap stat is performed, no parsing.
     */
    public void refreshIfChanged() {
        try {
            if (!Files.isRegularFile(configFile)) {
                synchronized (this) {
                    if (loaded && lastMissing) {
                        return;
                    }
                    loaded = true;
                    lastMissing = true;
                    lastModified = Long.MIN_VALUE;
                    lastSize = Long.MIN_VALUE;
                    snapshot = Snapshot.EMPTY;
                    contentFingerprint = "missing";
                }
                LOGGER.warn("ProjectE manual EMC file {} is missing; SudoersEMC will not treat any value as manually set until it appears",
                        configFile);
                return;
            }

            long modified = Files.getLastModifiedTime(configFile).toMillis();
            long size = Files.size(configFile);

            synchronized (this) {
                if (loaded && !lastMissing && modified == lastModified && size == lastSize) {
                    return;
                }
                lastMissing = false;
                lastModified = modified;
                lastSize = size;
                loaded = true;
                readFailureWarned = false;
                loadLocked(modified, size);
            }
        } catch (Throwable t) {
            // Never let config inspection break gameplay or ProjectE's mapping pass.
            synchronized (this) {
                if (!readFailureWarned) {
                    readFailureWarned = true;
                    LOGGER.warn("Could not inspect ProjectE manual EMC file {}; keeping the last loaded manual values (empty if never loaded)",
                            configFile, t);
                }
                contentFingerprint = "unreadable";
            }
        }
    }

    private void loadLocked(long modified, long size) {
        if (size > MAX_FILE_BYTES) {
            snapshot = Snapshot.EMPTY;
            contentFingerprint = "oversized";
            LOGGER.warn("ProjectE manual EMC file {} is {} bytes, above the {} byte safety limit; ignoring it",
                    configFile, size, MAX_FILE_BYTES);
            return;
        }

        String json;
        try {
            json = Files.readString(configFile, StandardCharsets.UTF_8);
        } catch (Throwable t) {
            snapshot = Snapshot.EMPTY;
            contentFingerprint = "unreadable";
            if (!readFailureWarned) {
                readFailureWarned = true;
                LOGGER.warn("Could not read ProjectE manual EMC file {}; assuming no manual values", configFile, t);
            }
            return;
        }

        try {
            Snapshot parsed = parse(json);
            snapshot = parsed;
            contentFingerprint = "sha256:" + ScanFingerprint.hash(List.of(json));
            LOGGER.info("Loaded {} manual ProjectE EMC entries from {} ({} item, {} tag)",
                    parsed.itemEntryCount() + parsed.tagEntryCount(), configFile,
                    parsed.itemEntryCount(), parsed.tagEntryCount());
        } catch (Throwable t) {
            snapshot = Snapshot.EMPTY;
            contentFingerprint = "unparsable";
            // One warning per distinct file version (the cache prevents retries until mtime/size change).
            LOGGER.warn("Failed to parse ProjectE manual EMC file {}; assuming no manual values", configFile, t);
        }
    }

    /**
     * @return {@code true} when the item is covered by a manual ProjectE value and therefore must
     *         not be written by the generator. Conservative by design: a plain item entry protects
     *         every NBT variant of that item.
     */
    public boolean isManuallySet(ItemKey key) {
        refreshIfChanged();
        return isManuallySetInSnapshot(key);
    }

    /**
     * 刷新一次文件状态并返回可复用的批量判定器。
     *
     * <p>扫描阶段会遍历大量物品；如果每个物品都调用 {@link #isManuallySet(ItemKey)}，就会
     * 每件物品执行一次文件 stat。该方法是批量调用入口：只在创建判定器时刷新一次，后续判定
     * 只读取当前不可变快照。手工文件在两次扫描之间变化时，下一次扫描会重新创建判定器。</p>
     *
     * @return 基于当前快照的手工值判定器，永不为 {@code null}
     */
    public Predicate<ItemKey> snapshotPredicate() {
        refreshIfChanged();
        return this::isManuallySetInSnapshot;
    }

    /**
     * Snapshot-only variant for bulk callers that already invoked {@link #refreshIfChanged()} once
     * (for example ProjectE's mapping pass). Avoids one file stat per item.
     */
    boolean isManuallySetInSnapshot(@Nullable ItemKey key) {
        if (key == null) {
            return false;
        }
        Snapshot current = snapshot;

        if (current.itemValues().containsKey(key)) {
            return true;
        }
        if (key.hasVariant()) {
            // A plain entry for the base item protects all of its variants as well.
            if (current.itemValues().containsKey(ItemKey.of(key.itemId()))) {
                return true;
            }
            if (matchesVariant(current, key)) {
                return true;
            }
        }

        for (ResourceLocation tagId : current.tagIds()) {
            if (tagContains(tagId, key.itemId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return immutable snapshot of explicitly listed item values (plain and NBT-qualified), for
     *         diagnostics. Tag entries cannot be represented as {@link ItemKey}s and are available
     *         through {@link #manualTagIds()}.
     */
    public Map<ItemKey, Long> manualValues() {
        refreshIfChanged();
        return snapshot.itemValues();
    }

    /**
     * @return immutable snapshot of manual item tag ids, without the leading {@code #}.
     */
    public Set<ResourceLocation> manualTagIds() {
        refreshIfChanged();
        return snapshot.tagIds();
    }

    /**
     * 返回手工值文件内容的稳定摘要，供扫描输入指纹的基线通道使用。
     *
     * <p>摘要覆盖原始 JSON，因此同一 item id 的数值变化、tag 条目变化以及格式之外的内容变化
     * 都会使指纹失效。文件缺失、超限、不可读或无法解析时返回固定降级标记，绝不把“读不到”
     * 当作“未变化”。</p>
     *
     * @return 以 {@code sha256:} 开头的摘要或固定降级标记
     */
    public String contentFingerprint() {
        refreshIfChanged();
        return contentFingerprint;
    }

    private static boolean matchesVariant(Snapshot current, ItemKey key) {
        List<VariantValue> candidates = current.variantsByItem().get(key.itemId());
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        CompoundTag query = tryParseVariant(key.variantHash());
        for (VariantValue candidate : candidates) {
            if (candidate.key().equals(key)) {
                return true;
            }
            // The manual file and ItemKey may format the same SNBT differently.
            if (query != null && candidate.parsedTag() != null && query.equals(candidate.parsedTag())) {
                return true;
            }
        }
        return false;
    }

    private boolean tagContains(ResourceLocation tagId, ResourceLocation itemId) {
        try {
            Item item = ForgeRegistries.ITEMS.getValue(itemId);
            if (item == null) {
                // Unknown/absent modded item: nothing can match.
                return false;
            }
            ITagManager<Item> tagManager = ForgeRegistries.ITEMS.tags();
            if (tagManager == null) {
                return false;
            }
            TagKey<Item> tagKey = tagManager.createTagKey(tagId);
            ITag<Item> tag = tagManager.getTag(tagKey);
            if (tag != null && tag.isBound()) {
                return tag.contains(item);
            }
            // Tags are bound on datapack reload. If this runs before tags are available we cannot
            // resolve membership; fall back to exact-id protection and note it once per tag.
            if (unresolvedTagWarned.add(tagId)) {
                LOGGER.debug("ProjectE manual EMC tag #{} is not bound in the item registry; only exact item ids from the manual file are protected",
                        tagId);
            }
            return false;
        } catch (Throwable t) {
            if (unresolvedTagWarned.add(tagId)) {
                LOGGER.debug("Could not resolve ProjectE manual EMC tag #{}; only exact item ids from the manual file are protected",
                        tagId, t);
            }
            return false;
        }
    }

    private Snapshot parse(String json) {
        JsonElement rootElement = JsonParser.parseString(json);
        if (!rootElement.isJsonObject() && !rootElement.isJsonArray()) {
            throw new IllegalArgumentException("root must be a JSON object or array");
        }

        JsonElement entriesElement;
        if (rootElement.isJsonArray()) {
            entriesElement = rootElement;
        } else {
            JsonObject root = rootElement.getAsJsonObject();
            entriesElement = root.get("entries");
            if (entriesElement == null) {
                return Snapshot.EMPTY;
            }
        }
        if (!entriesElement.isJsonArray()) {
            throw new IllegalArgumentException("\"entries\" must be an array");
        }

        Map<ItemKey, Long> itemValues = new LinkedHashMap<>();
        Set<ResourceLocation> tagIds = new LinkedHashSet<>();
        int skipped = 0;
        String firstProblem = null;

        JsonArray entries = entriesElement.getAsJsonArray();
        for (JsonElement element : entries) {
            if (!element.isJsonObject()) {
                skipped++;
                firstProblem = firstProblem == null ? "entry is not an object" : firstProblem;
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            JsonElement itemElement = entry.get("item");
            if (itemElement == null || !itemElement.isJsonPrimitive() || !itemElement.getAsJsonPrimitive().isString()) {
                skipped++;
                firstProblem = firstProblem == null ? "\"item\" is missing or not a string" : firstProblem;
                continue;
            }
            Long emc = parseEmc(entry.get("emc"));
            if (emc == null) {
                skipped++;
                firstProblem = firstProblem == null ? "\"emc\" is missing, negative or not a number" : firstProblem;
                continue;
            }

            ParsedSpec spec = parseSpec(itemElement.getAsString());
            if (spec == null) {
                skipped++;
                firstProblem = firstProblem == null ? "invalid item id: " + itemElement.getAsString() : firstProblem;
                continue;
            }

            if (spec.tagId() != null) {
                tagIds.add(spec.tagId());
            } else {
                itemValues.put(new ItemKey(spec.itemId(), spec.variantHash()), emc);
            }
        }

        if (skipped > 0) {
            // Single aggregated warning per file version.
            LOGGER.warn("Skipped {} malformed entries in ProjectE manual EMC file {} (first problem: {})",
                    skipped, configFile, firstProblem);
        }

        Map<ResourceLocation, List<VariantValue>> variantsByItem = new HashMap<>();
        for (Map.Entry<ItemKey, Long> entry : itemValues.entrySet()) {
            ItemKey key = entry.getKey();
            if (!key.hasVariant()) {
                continue;
            }
            variantsByItem.computeIfAbsent(key.itemId(), ignored -> new ArrayList<>())
                    .add(new VariantValue(key, tryParseVariant(key.variantHash())));
        }

        return new Snapshot(itemValues, variantsByItem, tagIds, itemValues.size(), tagIds.size());
    }

    private static @Nullable Long parseEmc(@Nullable JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        try {
            long value;
            if (primitive.isNumber()) {
                value = primitive.getAsLong();
            } else if (primitive.isString()) {
                value = Long.parseLong(primitive.getAsString().trim());
            } else {
                return null;
            }
            return value >= 0 ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Parses {@code minecraft:stone}, {@code minecraft:diamond_sword{Damage:0}} or
     * {@code #forge:ingots/iron}.
     */
    private static @Nullable ParsedSpec parseSpec(String raw) {
        String value = raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.charAt(0) == '#') {
            ResourceLocation tagId = ResourceLocation.tryParse(value.substring(1).trim());
            return tagId == null ? null : new ParsedSpec(null, null, tagId);
        }

        int nbtStart = value.indexOf('{');
        String idPart = nbtStart >= 0 ? value.substring(0, nbtStart).trim() : value;
        String variant = nbtStart >= 0 ? value.substring(nbtStart).trim() : null;
        ResourceLocation itemId = ResourceLocation.tryParse(idPart);
        if (itemId == null) {
            return null;
        }
        if (variant != null && variant.isEmpty()) {
            variant = null;
        }
        return new ParsedSpec(itemId, variant, null);
    }

    private static @Nullable CompoundTag tryParseVariant(@Nullable String variant) {
        if (variant == null || variant.isBlank()) {
            return null;
        }
        try {
            return TagParser.parseTag(variant);
        } catch (Throwable t) {
            // Keep the raw string for exact matching; semantic comparison is simply unavailable.
            return null;
        }
    }

    private record ParsedSpec(@Nullable ResourceLocation itemId, @Nullable String variantHash,
                              @Nullable ResourceLocation tagId) {
    }

    private record VariantValue(ItemKey key, @Nullable CompoundTag parsedTag) {
    }

    private record Snapshot(Map<ItemKey, Long> itemValues,
                            Map<ResourceLocation, List<VariantValue>> variantsByItem,
                            Set<ResourceLocation> tagIds,
                            int itemEntryCount,
                            int tagEntryCount) {

        static final Snapshot EMPTY = new Snapshot(Map.of(), Map.of(), Set.of(), 0, 0);

        private Snapshot {
            itemValues = Map.copyOf(itemValues);
            Map<ResourceLocation, List<VariantValue>> copiedVariants = new HashMap<>();
            variantsByItem.forEach((itemId, variants) -> copiedVariants.put(itemId, List.copyOf(variants)));
            variantsByItem = Map.copyOf(copiedVariants);
            tagIds = Set.copyOf(tagIds);
        }
    }
}
