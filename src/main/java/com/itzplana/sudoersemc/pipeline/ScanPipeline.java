package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.core.VariantRegistration;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.ProjectECustomEmcFile;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.pricing.TierPricing;
import com.itzplana.sudoersemc.resolver.VariantNormalizer;
import com.itzplana.sudoersemc.resolver.VariantScanner;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.PriceDropDecision;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeScanner;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import com.itzplana.sudoersemc.solver.EvaluatorRegistry;
import com.itzplana.sudoersemc.solver.RecipeSolver;
import com.itzplana.sudoersemc.solver.SolveContext;
import com.itzplana.sudoersemc.solver.SolveResult;
import com.itzplana.sudoersemc.solver.SolveSettings;
import com.itzplana.sudoersemc.solver.SolveTrace;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore;
import com.itzplana.sudoersemc.storage.ScanStateStore;
import com.itzplana.sudoersemc.task.ScanFingerprint;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 把扫描、配方求解、兜底定价、安全层与持久化串联起来的运行时管线。
 *
 * <p>一次完整扫描分为两个阶段：</p>
 * <ol>
 *   <li>{@link #collect(MinecraftServer)} 在服务器线程读取物品注册表与配方管理器，产出不可变的
 *       {@link PipelineSnapshot}；</li>
 *   <li>{@link #solveAndApply(PipelineSnapshot)} 只做纯计算与存储写入，不再访问注册表。</li>
 * </ol>
 *
 * <p>{@link com.itzplana.sudoersemc.task.ScanManager} 会在采集阶段与阶段之间跨越多个 tick，
 * 因此本类提供 {@link #stash(PipelineSnapshot)} 与 {@link #applyStashed()} 配对使用：
 * 采集回调只负责暂存快照，应用回调再取出并执行求解。相比让外部 lambda 捕获数组，显式暂存字段
 * 更容易测试、诊断与避免重复应用。</p>
 *
 * <p><strong>空值语义：</strong>{@code store} 为 {@code null} 表示干跑模式，所有计数与报告照常
 * 计算，但不会写入任何持久化条目，也不会写隔离记录文件。其余协作者为 {@code null} 时按
 * “关闭该能力”处理：降价保护与创造物品保护使用安全默认值，兜底定价与溢出保护在缺失时直接
 * 跳过。这样单元测试可以在没有完整运行时环境的情况下验证管线主干。</p>
 *
 * <p><strong>异常契约：</strong>{@link #collect(MinecraftServer)} 在服务器未就绪或采集失败时
 * 抛出 {@link IllegalStateException}，由 {@code ScanManager} 转为 {@code FAILED}，绝不把失败
 * 伪装成“成功的空扫描”；{@link #solveAndApply(PipelineSnapshot)} 仍不向调用方抛出异常，
 * 任何未预期失败记录日志后返回全零结果，保证服务器主循环不被单次扫描拖垮。</p>
 */
public final class ScanPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanPipeline");

    /**
     * 兜底链贯通的最大求解轮数。
     *
     * <p>取 4 的原因：第一轮负责给只能依靠 TIER/TAG/RARITY/FALLBACK 定价的叶子物品写入种子，
     * 每次兜底补种后都需要重新求解，让新种子向配方上游传播。按求解器 {@code maxPasses=1}
     * 的最坏情况估算，4 轮可以覆盖“兜底种子 + 3 层中间产物”的常见模组链
     * （例如矿石 → 锭 → 齿轮/块）；默认 {@code maxPasses=20} 时通常 2 到 3 轮即收敛。
     * 循环内不会给任何配方输出补种，只有收敛后的最终兜底才会处理它们，避免兜底值抢占
     * 本应由配方成本决定的中间产物。上限同时把异常配方图的开销限制在常量级。</p>
     */
    static final int MAX_SOLVE_ROUNDS = 4;

    private final GeneratedEntryStore store;
    private volatile QuarantinedDropsStore quarantinedDropsStore;
    private final ScanStateStore stateStore;
    private final ReportManager reportManager;
    private final LargeDropProtection dropProtection;
    private final CreativeItemProtection creativeProtection;
    private final OverflowPipeline overflowPipeline;
    private final FallbackPricing fallbackPricing;
    private final TierPricing tierPricing;
    private final SolveSettings solveSettings;
    private volatile boolean solveRecipesEnabled = true;
    private volatile boolean generateReportsEnabled = true;
    private volatile boolean nbtVariantsEnabled = true;
    private volatile int maxNbtVariantsPerItem = 100;

    private final AtomicReference<PipelineSnapshot> stashedSnapshot = new AtomicReference<>();

    private volatile EmcProvider emcProvider;
    private volatile OverflowScope overflowScope = OverflowScope.OWNED_ONLY;
    private volatile boolean allowProjectEFileRewrite;
    private volatile Path projectECustomEmcFile;
    private volatile int lastSolvedCount;
    private volatile int lastUnresolvedCount;
    private volatile int lastSolverRoundCount;
    private volatile long lastDurationMillis;

    /**
     * 创建管线。
     *
     * @param store              生成值存储；{@code null} 表示干跑模式，不写入任何条目
     * @param stateStore         扫描状态存储；{@code null} 时不更新增量扫描指纹
     * @param reportManager      诊断报告管理器；{@code null} 时不写报告
     * @param dropProtection     大幅降价保护；{@code null} 时使用 {@link LargeDropProtection.Settings#defaults()}
     * @param creativeProtection 创造物品保护；{@code null} 时使用启用但不含任何排除项的默认配置
     * @param overflowPipeline   溢出保护管线；{@code null} 时跳过溢出处理
     * @param fallbackPricing    兜底定价链；{@code null} 时跳过兜底定价
     * @param solveSettings      配方求解参数；{@code null} 时使用 {@link SolveSettings#defaults()}
     */
    public ScanPipeline(
            @Nullable GeneratedEntryStore store,
            @Nullable ScanStateStore stateStore,
            @Nullable ReportManager reportManager,
            @Nullable LargeDropProtection dropProtection,
            @Nullable CreativeItemProtection creativeProtection,
            @Nullable OverflowPipeline overflowPipeline,
            @Nullable FallbackPricing fallbackPricing,
            @Nullable SolveSettings solveSettings
    ) {
        this.store = store;
        this.stateStore = stateStore;
        this.reportManager = reportManager;
        this.dropProtection = dropProtection == null
                ? new LargeDropProtection(LargeDropProtection.Settings.defaults())
                : dropProtection;
        this.creativeProtection = creativeProtection == null
                ? new CreativeItemProtection(new CreativeItemProtection.Settings(
                        true, Set.of(), Map.of(), Set.of()))
                : creativeProtection;
        this.overflowPipeline = overflowPipeline;
        this.fallbackPricing = fallbackPricing;
        this.tierPricing = fallbackPricing == null ? TierPricing.defaults() : fallbackPricing.tierPricing();
        this.solveSettings = solveSettings == null ? SolveSettings.defaults() : solveSettings;
    }

    /**
     * 注册 ProjectE（或其它后端）的 EMC 查询实现。
     *
     * <p>采集阶段通过该提供者判断物品是否已有 EMC。传入 {@code null} 表示没有后端，此时所有
     * 物品都会被当作“没有既有值”，管线仍然可以仅依靠配方与兜底定价工作。</p>
     *
     * @param provider EMC 后端查询接口，允许为 {@code null}
     */
    public void setEmcProvider(@Nullable EmcProvider provider) {
        this.emcProvider = provider;
    }

    /**
     * 注入隔离记录存储。
     *
     * <p>注入后，每当大幅降价保护判定隔离时，管线会把旧值、拟写入值、降幅、原因与时间戳
     * 写入该存储，供管理员通过 {@code /sudoemc approve} 审批。传入 {@code null} 时只保留原有
     * 的内存计数与日志行为。干跑模式（生成条目存储为 {@code null}）永远不会写隔离文件。</p>
     *
     * @param newStore 隔离记录存储，允许为 {@code null}
     */
    public void setQuarantinedDropsStore(@Nullable QuarantinedDropsStore newStore) {
        this.quarantinedDropsStore = newStore;
    }

    /**
     * 设置溢出保护作用域。
     *
     * <p>生产环境默认 {@link OverflowScope#OWNED_ONLY}，只允许改写 SudoersEMC 自己拥有的条目。
     * {@link OverflowScope#ENTIRE_CUSTOM_EMC} 仅扩大扫描范围到 ProjectE 的
     * {@code custom_emc.json}，不会授权改写非自有条目；传入 {@code null} 时恢复默认值，
     * 绝不隐式扩大作用域。</p>
     *
     * @param scope 作用域，允许为 {@code null}
     */
    public void setOverflowScope(@Nullable OverflowScope scope) {
        this.overflowScope = scope == null ? OverflowScope.OWNED_ONLY : scope;
    }

    /**
     * 设置是否允许改写 ProjectE 的 {@code custom_emc.json} 中归 SudoersEMC 所有的条目。
     *
     * <p>默认 {@code false}。只有同时启用 {@link OverflowScope#ENTIRE_CUSTOM_EMC} 作用域时才会
     * 产生实际文件写入；这是避免误改管理员手动配置的第二道保险。无论该开关如何设置，
     * 非自有（管理员手动）条目都不会被改写。</p>
     *
     * @param enabled 是否允许改写 ProjectE 文件中的自有条目
     */
    public void setProjectEFileRewriteEnabled(boolean enabled) {
        this.allowProjectEFileRewrite = enabled;
    }

    /**
     * 设置是否启用配方求解。
     *
     * <p>关闭后管线不再运行 {@link RecipeSolver}，只使用快照中的既有值与兜底定价链。
     * 该开关在每次扫描开始时读取，重建管线后立即生效。</p>
     *
     * @param enabled {@code true} 表示启用配方求解；默认启用
     */
    public void setSolveRecipesEnabled(boolean enabled) {
        this.solveRecipesEnabled = enabled;
    }

    /**
     * 设置是否写出诊断报告。
     *
     * <p>关闭后 {@code last_report.txt} 不再更新，但本轮扫描的计数、状态存储与持久化
     * 行为保持不变。该开关在每次扫描开始时读取，重建管线后立即生效。</p>
     *
     * @param enabled {@code true} 表示写出诊断报告；默认启用
     */
    public void setGenerateReportsEnabled(boolean enabled) {
        this.generateReportsEnabled = enabled;
    }

    /**
     * 设置是否扫描并生成 NBT 变体快照。
     *
     * <p>关闭后 {@link #collect(MinecraftServer)} 完全不生成变体，基础物品快照不受影响。
     * 该开关在每次采集开始时读取，重建管线后立即生效。</p>
     *
     * @param enabled {@code true} 表示启用 NBT 变体扫描；默认启用
     */
    public void setNbtVariantsEnabled(boolean enabled) {
        this.nbtVariantsEnabled = enabled;
    }

    /**
     * 设置每个物品最多保留的 NBT 变体数量。
     *
     * <p>取值会被限制在 {@code [1, }{@link VariantScanner#HARD_VARIANT_CEILING}{@code ]}
     * 内，避免异常配置导致变体数量失控。该上限在每次采集开始时读取。</p>
     *
     * @param maxVariantsPerItem 每个物品最多保留的变体数
     */
    public void setMaxNbtVariantsPerItem(int maxVariantsPerItem) {
        this.maxNbtVariantsPerItem = Math.max(
                1, Math.min(maxVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));
    }

    /**
     * 覆盖 ProjectE 手动 EMC 文件路径，主要用于测试或嵌入式场景。
     *
     * <p>传入 {@code null} 时恢复生产路径（{@code config/ProjectE/custom_emc.json}）。</p>
     *
     * @param file 自定义路径，允许为 {@code null}
     */
    public void setProjectECustomEmcFile(@Nullable Path file) {
        this.projectECustomEmcFile = file;
    }

    /**
     * 采集一次扫描所需的全部数据。
     *
     * <p><strong>必须由服务器线程调用。</strong>本方法读取物品注册表与配方管理器，并把实时对象
     * 转换为不可变的 {@link ItemSnapshot} / {@link RecipeSnapshot}。返回后所有数据都可以安全地
     * 脱离服务器线程处理。</p>
     *
     * <p>服务器未就绪、注册表/配方扫描失败或出现未预期异常时抛出
     * {@link IllegalStateException}。调用方（{@code ScanManager}）会把它转换为扫描失败，
     * 从而避免用空快照覆盖上一次有效的扫描状态。</p>
     *
     * @param server 当前服务器实例，允许为 {@code null}
     * @return 本次采集的不可变快照
     * @throws IllegalStateException 服务器未就绪或采集过程失败时抛出
     */
    public PipelineSnapshot collect(@Nullable MinecraftServer server) {
        if (server == null) {
            LOGGER.warn("SudoersEMC 扫描采集被调用时服务器尚未就绪，本次扫描失败");
            throw new IllegalStateException("扫描采集失败：服务器尚未就绪");
        }

        try {
            ItemScanner.EmcQuery query = new ItemScanner.EmcQuery() {
                @Override
                public boolean hasValue(ItemStack stack) {
                    EmcProvider provider = emcProvider;
                    if (provider == null) {
                        return false;
                    }
                    try {
                        return provider.isAvailable() && provider.hasValue(stack);
                    } catch (RuntimeException | LinkageError failure) {
                        LOGGER.debug("EMC 后端查询物品是否有值失败，按无值处理", failure);
                        return false;
                    }
                }

                @Override
                public long getValue(ItemStack stack) {
                    EmcProvider provider = emcProvider;
                    if (provider == null) {
                        return 0L;
                    }
                    try {
                        if (!provider.isAvailable()) {
                            return 0L;
                        }
                        return Math.max(0L, provider.getValue(stack));
                    } catch (RuntimeException | LinkageError failure) {
                        LOGGER.debug("EMC 后端读取物品数值失败，按 0 处理", failure);
                        return 0L;
                    }
                }

                @Override
                public long getExactValue(ItemStack stack) {
                    EmcProvider provider = emcProvider;
                    if (provider == null) {
                        return 0L;
                    }
                    try {
                        if (!provider.isAvailable()) {
                            return 0L;
                        }
                        return Math.max(0L, provider.getExactValue(stack));
                    } catch (RuntimeException | LinkageError failure) {
                        LOGGER.debug("EMC 后端读取精确 NBT 数值失败，按 0 处理", failure);
                        return 0L;
                    }
                }
            };

            List<ItemSnapshot> items = new ItemScanner(query, Set.of(), Set.of()).scan();

            RecipeScanner recipeScanner = RecipeScanner.withGoety().orElseGet(RecipeScanner::new);
            RecipeScanner.ScanResult scanResult = recipeScanner.scan(server.getRecipeManager());

            return collectSnapshotData(items, scanResult, query);
        } catch (Throwable failure) {
            LOGGER.error("SudoersEMC 扫描采集失败", failure);
            throw new IllegalStateException("SudoersEMC 扫描采集失败", failure);
        }
    }

    /**
     * 把已完成的物品与配方扫描结果组装成不可变快照。
     *
     * <p>该方法从管线字段读取 NBT 变体开关与单物品上限，是 {@link #collect(MinecraftServer)}
     * 的可测试接缝：生产路径与测试都走同一段变体生成与既有值查询逻辑。</p>
     *
     * @param items      基础物品扫描结果，不能为 null
     * @param scanResult 配方扫描结果，不能为 null
     * @param emcQuery   后端查询；允许为 {@code null}（测试或后端缺失时按无既有值处理）
     * @return 组装后的快照
     */
    PipelineSnapshot collectSnapshotData(List<ItemSnapshot> items,
                                         RecipeScanner.ScanResult scanResult,
                                         @Nullable ItemScanner.EmcQuery emcQuery) {
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(scanResult, "scanResult");
        List<ItemSnapshot> variantItems = collectVariantSnapshots(
                items, scanResult.recipes(), nbtVariantsEnabled, maxNbtVariantsPerItem, emcQuery);
        List<ItemSnapshot> allItems = items;
        if (!variantItems.isEmpty()) {
            List<ItemSnapshot> combined = new ArrayList<>(items.size() + variantItems.size());
            combined.addAll(items);
            combined.addAll(variantItems);
            allItems = List.copyOf(combined);
        }

        Map<ItemKey, Long> existingValues = new TreeMap<>();
        for (ItemSnapshot item : allItems) {
            if (item.hasExistingEmc()) {
                existingValues.put(item.key(), item.existingValue());
            }
        }

        return new PipelineSnapshot(
                allItems,
                scanResult.recipes(),
                existingValues,
                scanResult.unsupported(),
                scanResult.invalid(),
                scanResult.failed());
    }

    /**
     * 根据配方输出中观测到的 SNBT 生成 NBT 变体快照。
     *
     * <p>先按物品 id 汇总并去重原始 SNBT，再逐个解析、规范化；每个物品只保留字典序最小的
     * {@code maxNbtVariantsPerItem} 个变体。解析是流式的：同一时刻只持有一个临时
     * {@link CompoundTag}，不会把与配方数成正比的解析结果全部留在内存中。全部物品共享
     * {@link VariantScanner#HARD_TOTAL_VARIANT_CEILING} 的总预算。只有已在基础物品快照中
     * 出现过的物品才会生成变体，因此忽略规则与注册表缺失物品不会被变体扫描重新引入。</p>
     *
     * <p>变体快照的 {@code hasExistingEmc}/{@code existingValue} 来自对带原始 NBT 堆叠的
     * 精确查询：只要任一观测原始 SNBT 已有 NBT 专属 EMC，就取最大值作为既有值，避免覆盖
     * 其它模组或 ProjectE NBT 处理器注册的值。标签路径与稀有度继承基础物品。</p>
     *
     * @param baseItems            基础物品快照，保持扫描器返回的顺序
     * @param recipes              本轮可用配方
     * @param enabled              是否启用变体扫描
     * @param maxNbtVariantsPerItem 每个物品最多保留的变体数
     * @return 不可变的变体快照列表；未启用、无观测变体或全部无法解析时为空
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem) {
        return collectVariantSnapshots(baseItems, recipes, enabled, maxNbtVariantsPerItem, null);
    }

    /**
     * 带 EMC 精确查询的变体采集重载。
     *
     * @param emcQuery 精确 NBT 查询；{@code null} 时按无既有值处理
     */
    static List<ItemSnapshot> collectVariantSnapshots(List<ItemSnapshot> baseItems,
                                                      List<RecipeSnapshot> recipes,
                                                      boolean enabled,
                                                      int maxNbtVariantsPerItem,
                                                      @Nullable ItemScanner.EmcQuery emcQuery) {
        if (!enabled || baseItems == null || baseItems.isEmpty()
                || recipes == null || recipes.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxNbtVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));

        Map<ResourceLocation, TreeSet<String>> observedByItem = new TreeMap<>();
        for (RecipeSnapshot recipe : recipes) {
            for (RecipeOutput output : recipe.outputs()) {
                String outputNbt = output.outputNbt();
                if (outputNbt == null || outputNbt.isBlank()) {
                    continue;
                }
                observedByItem
                        .computeIfAbsent(output.item().itemId(), id -> new TreeSet<>())
                        .add(outputNbt);
            }
        }
        if (observedByItem.isEmpty()) {
            return List.of();
        }

        Map<ResourceLocation, ItemSnapshot> baseByItem = new LinkedHashMap<>();
        for (ItemSnapshot item : baseItems) {
            if (!item.key().hasVariant()) {
                baseByItem.putIfAbsent(item.registryId(), item);
            }
        }

        Set<ItemKey> seen = new HashSet<>();
        List<ItemSnapshot> variants = new ArrayList<>();
        int totalBudget = VariantScanner.HARD_TOTAL_VARIANT_CEILING;
        boolean budgetExhausted = false;

        for (Map.Entry<ResourceLocation, TreeSet<String>> entry : observedByItem.entrySet()) {
            ItemSnapshot base = baseByItem.get(entry.getKey());
            if (base == null) {
                // 基础物品未被扫描（被忽略或未注册）时不生成变体，避免绕过排除规则。
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(entry.getKey());
            if (item == null || item == Items.AIR) {
                continue;
            }

            // 原始 SNBT 已按字典序去重；逐个解析并即时裁剪到 limit，保持结果与配方顺序无关。
            TreeSet<ItemKey> uniqueKeys = new TreeSet<>();
            Map<ItemKey, TreeSet<String>> rawFormsByKey = new LinkedHashMap<>();
            for (String snbt : entry.getValue()) {
                CompoundTag tag;
                try {
                    tag = TagParser.parseTag(snbt);
                } catch (Exception failure) {
                    LOGGER.debug("无法解析配方输出 SNBT，跳过该观测变体：{}", snbt, failure);
                    continue;
                }
                ItemKey key = VariantNormalizer.keyFor(entry.getKey(), tag);
                if (!key.hasVariant()) {
                    continue;
                }
                uniqueKeys.add(key);
                TreeSet<String> forms = rawFormsByKey.computeIfAbsent(key, ignored -> new TreeSet<>());
                if (forms.size() < VariantRegistration.MAX_OBSERVED_NBT_PER_VARIANT) {
                    forms.add(snbt);
                }
                if (uniqueKeys.size() > limit) {
                    ItemKey removed = uniqueKeys.pollLast();
                    rawFormsByKey.remove(removed);
                }
            }
            if (uniqueKeys.isEmpty()) {
                continue;
            }

            for (ItemKey variantKey : uniqueKeys) {
                if (!seen.add(variantKey)) {
                    continue;
                }
                if (variants.size() >= totalBudget) {
                    budgetExhausted = true;
                    break;
                }
                long existingValue = queryVariantExistingValue(
                        item, variantKey, rawFormsByKey.getOrDefault(variantKey, new TreeSet<>()), emcQuery);
                variants.add(ItemSnapshot.of(
                        variantKey, existingValue > 0L, existingValue, base.tagPaths(), base.rarity()));
            }
            if (variants.size() >= totalBudget) {
                budgetExhausted = true;
                break;
            }
        }

        if (budgetExhausted) {
            LOGGER.debug("NBT 变体生成达到全局上限 {}，后续物品不再采集", totalBudget);
        }
        if (variants.isEmpty()) {
            return List.of();
        }
        variants.sort(Comparator.comparing(ItemSnapshot::key));
        return List.copyOf(variants);
    }

    /**
     * 查询一个变体身份下所有观测原始 NBT 的精确既有值。
     *
     * <p>同一经济变体可能对应多个原始 SNBT；采用最大值可避免把任何一个已存在的 NBT 专属
     * 值当成“无值”后覆盖。任一查询失败只按该形态无值处理。</p>
     *
     * @return 精确 NBT 既有值；没有专属映射时返回 {@code 0}
     */
    private static long queryVariantExistingValue(Item item, ItemKey variantKey,
                                                  Iterable<String> rawSnbts,
                                                  @Nullable ItemScanner.EmcQuery emcQuery) {
        if (emcQuery == null) {
            return 0L;
        }
        long maxValue = 0L;
        for (String snbt : rawSnbts) {
            try {
                CompoundTag tag = TagParser.parseTag(snbt);
                ItemStack probe = new ItemStack(item);
                probe.setTag(tag);
                long value = Math.max(0L, emcQuery.getExactValue(probe));
                if (value > maxValue) {
                    maxValue = value;
                }
            } catch (Exception failure) {
                LOGGER.debug("查询变体 {} 的精确 NBT 既有值失败，跳过该形态：{}", variantKey, snbt, failure);
            }
        }
        return maxValue;
    }

    /**
     * 暂存采集结果，等待应用阶段取用。
     *
     * <p>该方法是“stash + applyStashed”接线方式的一半，专门服务于
     * {@link com.itzplana.sudoersemc.task.ScanManager} 跨 tick 的阶段划分。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     */
    public void stash(@Nullable PipelineSnapshot snapshot) {
        stashedSnapshot.set(snapshot);
    }

    /**
     * 取出并应用最近一次暂存的快照。
     *
     * <p>快照在取出后立即清空，因此重复调用不会把同一批结果写入两次。没有暂存快照时返回全零
     * 结果并记录警告，不会抛出异常。</p>
     *
     * @return 本次应用的结果；没有暂存数据时为 {@link PipelineResult#zeroed(long)}
     */
    public PipelineResult applyStashed() {
        PipelineSnapshot snapshot = stashedSnapshot.getAndSet(null);
        if (snapshot == null) {
            LOGGER.warn("SudoersEMC 应用阶段没有可用的暂存快照，跳过本轮写入");
            return PipelineResult.zeroed(0L);
        }
        return solveAndApply(snapshot);
    }

    /**
     * 把存储中归 SudoersEMC 所有的物品从“已有 EMC”中分离出来。
     *
     * <p>返回的物品快照保持输入顺序；自有条目会被重建为不含既有值的形式，标签与稀有度原样
     * 保留，后续求解与兜底定价因此可以重新计算它们。非自有条目保持原样，其既有值继续写入
     * {@code existingValues}，确保手工值或外部后端值不会被覆盖。</p>
     *
     * @param items 本次扫描到的物品快照，不能为 {@code null}
     * @param owned 归属判定；返回 {@code true} 表示该物品由 SudoersEMC 所有
     * @return 分离后的物品、仍受保护的既有值、刷新数量与全部自有物品键
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned) {
        List<ItemSnapshot> separated = new ArrayList<>(items.size());
        Map<ItemKey, Long> existingValues = new TreeMap<>();
        Set<ItemKey> ownedItems = new TreeSet<>();
        int refreshedOwned = 0;
        for (ItemSnapshot item : items) {
            ItemKey key = item.key();
            if (owned.test(key)) {
                ownedItems.add(key);
                if (item.hasExistingEmc() || item.existingValue() > 0L) {
                    refreshedOwned++;
                }
                separated.add(ItemSnapshot.of(key, false, 0L, item.tagPaths(), item.rarity()));
                continue;
            }
            separated.add(item);
            if (item.hasExistingEmc()) {
                existingValues.put(key, item.existingValue());
            }
        }
        return new OwnershipSeparation(separated, existingValues, refreshedOwned, ownedItems);
    }

    /**
     * 在应用阶段执行归属分离。
     *
     * <p>分离放在应用阶段而不是采集阶段：采集只如实记录后端观察到的状态，是否归 SudoersEMC
     * 所有由持久化存储决定。这样即使调用方直接构造带既有值的快照，也会在写入前正确刷新
     * 自有条目；干跑模式没有存储，保持原始语义不变。</p>
     *
     * @param snapshot 采集结果，不能为 {@code null}
     * @return 分离后的快照与自有物品键；无需刷新时快照返回原实例
     */
    private AppliedSeparation applyOwnershipSeparation(PipelineSnapshot snapshot) {
        if (store == null) {
            return new AppliedSeparation(snapshot, Set.of(), 0);
        }
        OwnershipSeparation separation = separateOwnedEntries(snapshot.items(), store::isOwned);
        if (separation.refreshedOwned() == 0) {
            return new AppliedSeparation(snapshot, separation.ownedItems(), 0);
        }
        LOGGER.debug("SudoersEMC 归属分离刷新了 {} 个自有条目，本轮将重新求解其数值",
                separation.refreshedOwned());
        return new AppliedSeparation(
                new PipelineSnapshot(
                        separation.items(),
                        snapshot.recipes(),
                        separation.existingValues(),
                        snapshot.unsupportedRecipes(),
                        snapshot.invalidRecipes(),
                        snapshot.failedRecipes()),
                separation.ownedItems(),
                separation.refreshedOwned());
    }

    /**
     * 归属分离的应用结果。
     *
     * @param snapshot       分离后的快照
     * @param ownedItems     本轮扫描中归 SudoersEMC 所有的物品键
     * @param refreshedOwned 被清除既有值的自有条目数量
     */
    private record AppliedSeparation(PipelineSnapshot snapshot, Set<ItemKey> ownedItems,
                                     int refreshedOwned) {

        AppliedSeparation {
            ownedItems = Collections.unmodifiableSet(new TreeSet<>(ownedItems));
        }
    }

    /**
     * 归属分离结果。
     *
     * @param items          分离后的物品快照，保持输入顺序
     * @param existingValues 仍然受保护的非自有既有值，按键升序排列
     * @param refreshedOwned 被清除既有值的自有条目数量
     * @param ownedItems     本轮扫描中归 SudoersEMC 所有的物品键，按键升序排列
     */
    record OwnershipSeparation(List<ItemSnapshot> items, Map<ItemKey, Long> existingValues,
                               int refreshedOwned, Set<ItemKey> ownedItems) {

        OwnershipSeparation {
            items = List.copyOf(items);
            existingValues = Collections.unmodifiableMap(new TreeMap<>(existingValues));
            ownedItems = Collections.unmodifiableSet(new TreeSet<>(ownedItems));
        }
    }

    /**
     * 求解快照并为可定价物品写入生成值。
     *
     * <p>本方法不访问注册表，只使用快照中的纯数据。处理顺序固定为：</p>
     * <ol>
     *   <li>以已有 EMC 与创造保护固定值为初始种子，最多执行 {@link #MAX_SOLVE_ROUNDS} 轮
     *       “配方求解 → 兜底补种”循环：循环内只给没有配方的叶子物品补种，把兜底值沿配方链
     *       向上传播；本轮既没有新增求解值也没有新增兜底值时提前收敛；</li>
     *   <li>收敛后再对仍无值的物品执行一次最终兜底，避免兜底种子抢占配方输出；</li>
     *   <li>统一合并候选：同一物品只保留一条，配方/固定值来源优先于任何兜底来源；</li>
     *   <li>每个候选值先经过大幅降价保护，再经过溢出保护，最后才写入存储；</li>
     *   <li>更新扫描指纹、写出诊断报告并返回汇总结果。安全层、存储写入与报告只执行一次。</li>
     * </ol>
     *
     * <p><strong>归属分离：</strong>应用阶段会先把存储中 {@code owned=true} 的条目对应物品
     * 视为没有既有 EMC，并从既有值映射中移除。采集阶段仍如实记录后端观察到的值，因为
     * {@link ItemScanner} 无法区分 ProjectE 原生值与 SudoersEMC 自己写回的值；只有在这里
     * 依据持久化存储的归属信息做一次分离，自有值才能随配方变化刷新，外部值仍然受到保护。</p>
     *
     * <p><strong>多级自有链条：</strong>分离会移除自有原料的旧值，而兜底定价原本发生在求解之后，
     * 导致依赖这些原料的配方产物拿不到新值。现在会在求解前把自有叶子条目的兜底价作为预置值
     * 注入 {@link SolveContext}：配方仍然可以覆盖它，下游配方则能在同一轮看到它。求解结束后
     * 才为仍未定价的条目生成兜底候选，计数语义保持不变。</p>
     *
     * <p>存储为 {@code null} 时进入干跑模式：计数与报告照常生成，但不会产生任何持久化写入。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     * @return 汇总结果；任何未预期异常都会转为全零结果
     */
    public PipelineResult solveAndApply(@Nullable PipelineSnapshot snapshot) {
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
        // 持久化隔离存储已经接管审计；每轮扫描开始时清空内存列表，避免长期自动扫描无限累积。
        dropProtection.clearQuarantine();
        long startedAtNanos = System.nanoTime();

        try {
            AppliedSeparation separation = applyOwnershipSeparation(data);
            data = separation.snapshot();
            long minimumEmc = solveSettings.minimumEmc();

            Map<ItemKey, Candidate> recipeCandidates = new TreeMap<>();
            Map<ItemKey, Candidate> fallbackCandidates = new TreeMap<>();
            Map<ItemKey, Long> seeds = initialSeeds(data);

            CreativeProtectionPlan protection = planCreativeProtection(data, seeds, minimumEmc);
            for (Map.Entry<ItemKey, Long> entry : protection.specialValues().entrySet()) {
                ItemKey item = entry.getKey();
                long value = entry.getValue();
                seeds.put(item, value);
                recipeCandidates.put(item,
                        new Candidate(item, value, PriceSource.SPECIAL_VALUE, null));
            }
            Set<ItemKey> recipeOutputs = recipeOutputItems(data);
            Set<ItemKey> variantItems = variantItemKeys(data.items());
            Map<ItemKey, List<String>> variantRegistrationNbts =
                    collectVariantRegistrationNbts(data.recipes(), variantItems);

            SolveResult lastSolveResult = skippedSolveResult();
            int roundsUsed = 0;
            for (int round = 1; round <= MAX_SOLVE_ROUNDS; round++) {
                roundsUsed = round;
                SolveResult roundResult = solveRound(data, seeds, protection.deniedItems());
                lastSolveResult = roundResult;

                boolean solvedProgress = mergeSolvedValues(roundResult, recipeCandidates, seeds,
                        protection.deniedItems(), protection.specialValues(), variantItems);
                int fallbackAdded = addFallbackCandidates(
                        data, seeds, fallbackCandidates, recipeOutputs);
                if (!solvedProgress && fallbackAdded == 0) {
                    break;
                }
            }
            lastSolverRoundCount = roundsUsed;

            // 收敛后再兜底：配方输出在循环内不会被兜底种子抢先定价，此处只处理仍然无值的物品。
            addFallbackCandidates(data, seeds, fallbackCandidates, Set.of());

            List<Candidate> candidates =
                    selectFinalCandidates(recipeCandidates, fallbackCandidates, minimumEmc);
            int solvedByRecipe = 0;
            int solvedByFallback = 0;
            for (Candidate candidate : candidates) {
                if (candidate.source() == PriceSource.RECIPE) {
                    solvedByRecipe++;
                } else {
                    solvedByFallback++;
                }
            }

            Map<ItemKey, GeneratedEmcEntry> previousEntries =
                    store == null ? Map.of() : store.snapshotAll();
            int overflowBefore = overflowPipeline == null ? 0 : overflowPipeline.resetsApplied();

            int applied = 0;
            int quarantined = 0;
            Map<String, Long> sourceCounts = new LinkedHashMap<>();

            for (Candidate candidate : candidates) {
                GeneratedEmcEntry previous = previousEntries.get(candidate.item());
                long previousValue = previous == null ? 0L : previous.value();

                PriceDropDecision decision = evaluateDropProtection(candidate, previousValue);
                if (!decision.allowed()) {
                    quarantined++;
                    recordQuarantine(candidate, decision);
                    LOGGER.debug("物品 {} 的拟写入值 {} 因大幅降价被隔离（旧值 {}，降幅 {}）",
                            candidate.item(), candidate.value(), previousValue, decision.dropRatio());
                    continue;
                }

                long finalValue = applyOverflowProtection(candidate);
                if (finalValue <= 0L) {
                    LOGGER.debug("物品 {} 经过溢出保护后数值为 {}，跳过写入", candidate.item(), finalValue);
                    continue;
                }

                if (store != null) {
                    // 溢出保护已经改变数值时，求解器给出的备选成本不再与最终值可比，
                    // 继续持久化会让 /sudoemc explain 展示误导性的对比结果。
                    List<Long> alternativeCosts = finalValue == candidate.value()
                            ? candidate.alternativeCosts()
                            : List.of();
                    boolean staged = store.stage(new GeneratedEmcEntry(
                            candidate.item(),
                            finalValue,
                            candidate.source(),
                            candidate.recipeId(),
                            true,
                            System.currentTimeMillis(),
                            alternativeCosts),
                            variantRegistrationNbts.getOrDefault(candidate.item(), List.of()));
                    if (!staged) {
                        LOGGER.debug("物品 {} 的生成值未被存储接受，可能已有非 SudoersEMC 拥有的条目",
                                candidate.item());
                        continue;
                    }
                }

                applied++;
                sourceCounts.merge(candidate.source().name(), 1L, Long::sum);
            }

            Set<ItemKey> candidateKeys = new HashSet<>(candidates.size());
            for (Candidate candidate : candidates) {
                candidateKeys.add(candidate.item());
            }
            int retiredEntries = retireStaleOwnedEntries(data, candidateKeys, data.existingValues());

            rewriteProjectECustomEmcIfEnabled();

            int overflowResets = overflowPipeline == null
                    ? 0
                    : Math.max(0, overflowPipeline.resetsApplied() - overflowBefore);
            if (store != null) {
                // 每轮扫描结束即刷新审计日志，崩溃时最多丢失当前这一轮。
                persistOverflowAudit();
            }
            int unresolved = countUnresolved(lastSolveResult, seeds);
            long durationMillis = elapsedMillis(startedAtNanos);

            lastSolvedCount = applied;
            lastUnresolvedCount = unresolved;
            lastDurationMillis = durationMillis;

            boolean appliedToStore = store != null;
            updateScanState(data, durationMillis, applied, unresolved, roundsUsed);
            writeDiagnosticReport(data, lastSolveResult, unresolved, applied, quarantined,
                    overflowResets, durationMillis, roundsUsed, appliedToStore, sourceCounts,
                    separation.refreshedOwned(), retiredEntries);

            return new PipelineResult(
                    solvedByRecipe,
                    solvedByFallback,
                    applied,
                    quarantined,
                    overflowResets,
                    unresolved,
                    durationMillis,
                    appliedToStore,
                    sourceCounts);
        } catch (Throwable failure) {
            long durationMillis = elapsedMillis(startedAtNanos);
            lastSolvedCount = 0;
            lastUnresolvedCount = 0;
            lastSolverRoundCount = 0;
            lastDurationMillis = durationMillis;
            LOGGER.error("SudoersEMC 扫描管线求解与应用失败，本轮返回全零结果", failure);
            return PipelineResult.zeroed(durationMillis);
        }
    }

    /**
     * 清理本轮完全没有参与定价的自有条目。
     *
     * <p>物品被新配置排除、配方被移除或物品从注册表消失后，旧的自有值不应继续生效。判定口径：
     * 条目 {@code owned=true}，且既不在本轮最终候选中，也不在本轮 {@code existingValues} 中，
     * 即视为完全没有参与定价。非自有条目（手动配置或外部后端写入）永远不会删除。</p>
     *
     * <p>以下情况直接跳过，避免把“本轮没有计算”误判成“条目陈旧”：干跑模式
     * （{@code store == null}）、配方求解被禁用、本轮没有任何候选，或快照没有任何物品
     * （例如服务器尚未就绪）。</p>
     *
     * @param snapshot       本轮采集结果
     * @param pricedKeys     本轮选出的候选物品集合
     * @param existingValues 本轮受保护的既有值映射
     * @return 实际删除的自有条目数量
     */
    private int retireStaleOwnedEntries(PipelineSnapshot snapshot, Set<ItemKey> pricedKeys,
                                        Map<ItemKey, Long> existingValues) {
        if (store == null) {
            return 0;
        }
        if (!solveRecipesEnabled) {
            LOGGER.debug("配方求解已禁用，跳过陈旧自有条目清理");
            return 0;
        }
        if (pricedKeys.isEmpty()) {
            LOGGER.debug("本轮没有任何候选，跳过陈旧自有条目清理");
            return 0;
        }
        if (snapshot.items().isEmpty()) {
            LOGGER.debug("本轮快照没有任何物品，跳过陈旧自有条目清理");
            return 0;
        }
        int retired = 0;
        try {
            for (Map.Entry<ItemKey, GeneratedEmcEntry> entry : store.snapshotAll().entrySet()) {
                GeneratedEmcEntry value = entry.getValue();
                if (value == null || !value.owned()) {
                    continue;
                }
                ItemKey key = entry.getKey();
                if (pricedKeys.contains(key) || existingValues.containsKey(key)) {
                    continue;
                }
                if (store.removeOwned(key)) {
                    retired++;
                }
            }
        } catch (RuntimeException failure) {
            LOGGER.warn("清理陈旧自有条目失败，本轮扫描结果仍然有效", failure);
        }
        LOGGER.debug("本轮扫描清理检查完成：retired={}", retired);
        if (retired > 0) {
            LOGGER.info("SudoersEMC 清理了 {} 个不再参与定价的自有 EMC 条目", retired);
        }
        return retired;
    }

    /**
     * @return 最近一次成功运行中实际写入存储（或干跑模式下本应写入）的条目数量
     */
    public int lastSolvedCount() {
        return lastSolvedCount;
    }

    /**
     * @return 最近一次运行结束时求解器仍未解析的物品数量
     */
    public int lastUnresolvedCount() {
        return lastUnresolvedCount;
    }

    /**
     * @return 最近一次运行的耗时毫秒数
     */
    public long lastDurationMillis() {
        return lastDurationMillis;
    }

    /**
     * @return 最近一次 {@link #solveAndApply(PipelineSnapshot)} 实际执行的求解轮数；
     *         空世界或第一轮就无新增值时为 {@code 1}
     */
    int lastSolverRoundCount() {
        return lastSolverRoundCount;
    }

    /**
     * 构造初始求解种子。
     *
     * <p>只有严格大于 0 的既有 EMC 才能成为种子：0 或负值等同于“没有值”，
     * 否则会遮蔽后续的配方与兜底定价。</p>
     */
    private static Map<ItemKey, Long> initialSeeds(PipelineSnapshot snapshot) {
        Map<ItemKey, Long> seeds = new TreeMap<>();
        for (Map.Entry<ItemKey, Long> entry : snapshot.existingValues().entrySet()) {
            Long value = entry.getValue();
            if (value != null && value > 0L) {
                seeds.put(entry.getKey(), value);
            }
        }
        return seeds;
    }

    /**
     * 执行一轮配方求解。
     *
     * <p>关闭配方求解时返回空结果，但保留“每轮兜底补种”的循环结构：第一轮兜底命中后，
     * 第二轮没有新增值会立即退出。</p>
     */
    private SolveResult solveRound(PipelineSnapshot data, Map<ItemKey, Long> seeds,
                                   Set<ItemKey> deniedItems) {
        if (!solveRecipesEnabled) {
            LOGGER.debug("配方求解已按配置禁用，本轮仅使用既有值与兜底定价");
            return skippedSolveResult();
        }
        SolveContext context = new SolveContext(seeds, deniedItems);
        return new RecipeSolver().solve(
                data.recipes(), context, EvaluatorRegistry.defaults(), solveSettings);
    }

    /**
     * 把一轮求解结果并入种子与配方候选。
     *
     * <p>同键取较小值，防止后续轮次抬高已生成值；仅当
     * {@link SolveSettings#allowValueIncrease()} 为 {@code true} 时才接受新值覆盖。
     * 被创造保护拒绝的物品会被丢弃；配置了固定值的物品始终使用固定值，并记录为
     * {@link PriceSource#SPECIAL_VALUE}。</p>
     *
     * <p>变体输出只允许发布到本轮真实采集到的变体快照：{@code nbt.scanNbtVariants=false}
     * 或被排除的基础物品不会经由配方求解重新进入持久化。</p>
     *
     * @param acceptedVariants 本轮通过采集门禁的变体身份
     * @return 本轮是否新增或更新了任何种子
     */
    private boolean mergeSolvedValues(SolveResult solveResult,
                                      Map<ItemKey, Candidate> recipeCandidates,
                                      Map<ItemKey, Long> seeds,
                                      Set<ItemKey> deniedItems,
                                      Map<ItemKey, Long> specialValues,
                                      Set<ItemKey> acceptedVariants) {
        boolean changed = false;
        boolean allowIncrease = solveSettings.allowValueIncrease();
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            ItemKey item = entry.getKey();
            if (isExcludedFromPricing(item)) {
                LOGGER.debug("物品 {} 被排除规则命中，跳过配方候选", item);
                continue;
            }
            long solvedValue = entry.getValue();
            if (solvedValue <= 0L || deniedItems.contains(item)) {
                continue;
            }
            if (item.hasVariant() && !acceptedVariants.contains(item)) {
                continue;
            }

            Long specialValue = specialValues.get(item);
            PriceSource source = PriceSource.RECIPE;
            if (specialValue != null) {
                solvedValue = specialValue;
                source = PriceSource.SPECIAL_VALUE;
            }

            Long previous = seeds.get(item);
            long mergedValue;
            if (previous == null || source == PriceSource.SPECIAL_VALUE || allowIncrease) {
                mergedValue = solvedValue;
            } else {
                mergedValue = Math.min(previous, solvedValue);
            }
            if (previous == null || mergedValue != previous) {
                seeds.put(item, mergedValue);
                changed = true;
            }

            SolveTrace trace = source == PriceSource.RECIPE
                    ? solveResult.traces().get(item)
                    : null;
            ResourceLocation recipeId = trace == null ? null : trace.recipeId();
            List<Long> alternativeCosts = trace == null ? List.of() : trace.alternativeCosts();
            Candidate existing = recipeCandidates.get(item);
            if (existing == null || mergedValue < existing.value()
                    || allowIncrease || source == PriceSource.SPECIAL_VALUE) {
                recipeCandidates.put(item,
                        new Candidate(item, mergedValue, source, recipeId, alternativeCosts));
            }
        }
        return changed;
    }

    /**
     * 判断物品是否被排除规则明确拒绝自动定价（配方路径）。
     *
     * <p>配方候选没有特殊固定值语义，因此两类排除都直接生效：</p>
     * <ul>
     *   <li>{@link TierPricing#price} 返回 {@code Optional.of(0L)} 表示层级规则明确排除；</li>
     *   <li>{@link CreativeItemProtection#decide} 返回 {@code priceable=false} 表示创造物品保护排除。</li>
     * </ul>
     *
     * <p>兜底路径需要让 {@code safety.creativeItemSpecialValues} 的固定值优先于层级排除，
     * 因此那里先取得 {@link CreativeItemProtection.Decision}，再单独调用 {@link #isTierExcluded}。</p>
     *
     * <p>变体的 {@link ItemKey} 与基础物品共享物品 id，因此排除规则天然覆盖全部 NBT 变体。</p>
     *
     * @param item 待判定物品身份
     * @return {@code true} 表示该物品不得进入配方候选
     */
    private boolean isExcludedFromPricing(ItemKey item) {
        if (item == null) {
            return false;
        }
        if (isTierExcluded(item)) {
            return true;
        }
        try {
            CreativeItemProtection.Decision decision = creativeProtection.decide(item, true, true);
            return !decision.priceable();
        } catch (RuntimeException failure) {
            LOGGER.warn("排除规则判定失败，按排除处理：{}", item, failure);
            return true;
        }
    }

    /**
     * 判断物品是否被层级定价规则明确排除。
     *
     * <p>{@link TierPricing#price} 返回 {@code Optional.of(0L)}（或其它非正值）时视为明确排除。
     * 兜底路径只在没有特殊固定值时调用本方法，从而保持
     * {@code safety.creativeItemSpecialValues} 对层级排除项的优先级；显式排除与创造命名空间
     * 仍由 {@link CreativeItemProtection} 判定。</p>
     *
     * @param item 待判定物品身份
     * @return {@code true} 表示层级规则明确拒绝自动定价
     */
    private boolean isTierExcluded(ItemKey item) {
        if (item == null) {
            return false;
        }
        try {
            Optional<Long> tierValue = tierPricing.price(item);
            return tierValue.isPresent() && tierValue.get() <= 0L;
        } catch (RuntimeException failure) {
            LOGGER.warn("层级排除判定失败，按排除处理：{}", item, failure);
            return true;
        }
    }

    /**
     * 合并配方与兜底候选，并执行最终去重与下限过滤。
     *
     * <p>同一个 {@link ItemKey} 只保留一条：配方来源优先于任何兜底来源
     * （TIER/TAG/RARITY/FALLBACK）。候选值必须大于 0 且不低于给定下限。</p>
     *
     * @param recipeCandidates   配方求解产生的候选，允许为空
     * @param fallbackCandidates 兜底定价产生的候选，允许为空
     * @param minimumEmc         最小 EMC
     * @return 按物品身份升序排列的最终候选
     */
    static List<Candidate> selectFinalCandidates(Map<ItemKey, Candidate> recipeCandidates,
                                                 Map<ItemKey, Candidate> fallbackCandidates,
                                                 long minimumEmc) {
        Map<ItemKey, Candidate> merged = new TreeMap<>(fallbackCandidates);
        merged.putAll(recipeCandidates);
        List<Candidate> selected = new ArrayList<>(merged.size());
        for (Candidate candidate : merged.values()) {
            if (candidate.value() > 0L && candidate.value() >= minimumEmc) {
                selected.add(candidate);
            }
        }
        return List.copyOf(selected);
    }

    /**
     * 统计收敛后仍未定价的物品数量。
     *
     * <p>求解器报告的未解析物品可能已经被后续兜底轮次补种，因此这里再按最终种子过滤一次。</p>
     */
    private static int countUnresolved(SolveResult solveResult, Map<ItemKey, Long> seeds) {
        int unresolved = 0;
        for (ItemKey item : solveResult.unresolvedItems()) {
            Long seed = seeds.get(item);
            if (seed == null || seed <= 0L) {
                unresolved++;
            }
        }
        return unresolved;
    }

    /**
     * @return 本轮物品快照中所有带变体哈希的身份
     */
    private static Set<ItemKey> variantItemKeys(List<ItemSnapshot> items) {
        Set<ItemKey> keys = new HashSet<>();
        for (ItemSnapshot item : items) {
            if (item.key().hasVariant()) {
                keys.add(item.key());
            }
        }
        return keys;
    }

    /**
     * 从配方输出收集“变体身份 -> 观测到的原始 SNBT”。
     *
     * <p>求解阶段只携带规范化身份，ProjectE 注册需要原始 NBT。这里只汇总本轮真实采集到的
     * 变体，按经济身份截断原始形态，随后随生成条目一并持久化。</p>
     *
     * @param recipes         本轮可用配方
     * @param acceptedVariants 本轮通过采集门禁的变体身份
     */
    private static Map<ItemKey, List<String>> collectVariantRegistrationNbts(
            List<RecipeSnapshot> recipes, Set<ItemKey> acceptedVariants) {
        if (acceptedVariants.isEmpty()) {
            return Map.of();
        }
        Map<ItemKey, TreeSet<String>> observed = new TreeMap<>();
        for (RecipeSnapshot recipe : recipes) {
            for (RecipeOutput output : recipe.outputs()) {
                ItemKey key = output.item();
                String snbt = output.outputNbt();
                if (!key.hasVariant() || !acceptedVariants.contains(key)
                        || snbt == null || snbt.isBlank()) {
                    continue;
                }
                TreeSet<String> forms = observed.computeIfAbsent(key, ignored -> new TreeSet<>());
                if (forms.size() < VariantRegistration.MAX_OBSERVED_NBT_PER_VARIANT) {
                    forms.add(snbt);
                }
            }
        }
        if (observed.isEmpty()) {
            return Map.of();
        }
        Map<ItemKey, List<String>> registrations = new TreeMap<>();
        observed.forEach((key, forms) -> registrations.put(key, List.copyOf(forms)));
        return Map.copyOf(registrations);
    }

    /**
     * 构造“跳过配方求解”的空结果。
     *
     * <p>该结果不包含任何生成值、轨迹或未解析物品，表示本轮没有评估配方；用于
     * {@code recipe.solveRecipes=false} 时保持下游候选收集与诊断计数结构不变。</p>
     */
    private static SolveResult skippedSolveResult() {
        return new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0);
    }

    /**
     * 为仍无种子的物品追加兜底候选，并把命中值并入种子。
     *
     * <p>只处理既不在既有 EMC 中、也不在当前种子中的物品。创造物品保护决定物品是否允许自动
     * 定价；特殊固定值优先于层级排除与兜底定价链，但显式排除与创造命名空间仍然优先。
     * 兜底链返回非正值、低于最小 EMC 或空结果时不产生候选。命中值同时写入 {@code seeds}，
     * 使下一轮配方求解能够把兜底价格传播到上游中间产物。</p>
     *
     * @param deferredItems 本次调用暂不处理的物品；循环内传入所有配方输出，最终兜底传入空集
     * @return 本轮新增的兜底候选数量
     */
    private int addFallbackCandidates(PipelineSnapshot snapshot, Map<ItemKey, Long> seeds,
                                      Map<ItemKey, Candidate> candidates,
                                      Set<ItemKey> deferredItems) {
        int added = 0;
        long minimumEmc = solveSettings.minimumEmc();
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            if (seeds.containsKey(item) || deferredItems.contains(item)) {
                continue;
            }
            Long existingValue = snapshot.existingValues().get(item);
            if (existingValue != null && existingValue > 0L) {
                continue;
            }
            CreativeItemProtection.Decision decision;
            try {
                decision = creativeProtection.decide(item, false, false);
            } catch (RuntimeException failure) {
                LOGGER.warn("创造物品保护判定失败，跳过物品 {} 的兜底定价", item, failure);
                continue;
            }
            if (!decision.priceable()) {
                LOGGER.debug("物品 {} 被创造物品保护排除，跳过兜底定价", item);
                continue;
            }
            if (decision.specialValue() <= 0L && isTierExcluded(item)) {
                LOGGER.debug("物品 {} 被层级排除规则命中，跳过兜底定价", item);
                continue;
            }

            if (decision.specialValue() > 0L) {
                if (decision.specialValue() >= minimumEmc) {
                    Candidate candidate = new Candidate(
                            item, decision.specialValue(), PriceSource.TIER, null);
                    candidates.put(item, candidate);
                    seeds.put(item, candidate.value());
                    added++;
                }
                continue;
            }

            if (fallbackPricing == null) {
                continue;
            }

            Optional<FallbackPricing.PricedValue> priced;
            try {
                priced = fallbackPricing.price(new FallbackPricing.PricingRequest(
                        item, itemSnapshot.tagPaths(), itemSnapshot.rarity(), 0L, 0L));
            } catch (RuntimeException failure) {
                LOGGER.warn("兜底定价失败，跳过物品 {}", item, failure);
                continue;
            }
            if (priced.isEmpty()) {
                continue;
            }

            FallbackPricing.PricedValue pricedValue = priced.get();
            if (pricedValue.value() <= 0L || pricedValue.value() < minimumEmc) {
                continue;
            }
            Candidate candidate =
                    new Candidate(item, pricedValue.value(), pricedValue.source(), null);
            candidates.put(item, candidate);
            seeds.put(item, candidate.value());
            added++;
        }
        return added;
    }

    /**
     * 把一次被隔离的降价写入持久化隔离记录。
     *
     * <p>干跑模式（生成条目存储为 {@code null}）或未注入隔离存储时不产生任何磁盘写入，原有
     * 的计数与日志行为保持不变。记录失败只影响隔离文件，不影响本轮扫描结果。</p>
     *
     * @param candidate 被隔离的候选值
     * @param decision  降价保护判定结果
     */
    private void recordQuarantine(Candidate candidate, PriceDropDecision decision) {
        QuarantinedDropsStore target = quarantinedDropsStore;
        if (target == null || store == null) {
            return;
        }
        try {
            target.record(new QuarantinedDropsStore.QuarantinedDrop(
                    candidate.item(),
                    decision.previousValue(),
                    decision.proposedValue(),
                    decision.dropRatio(),
                    decision.reason(),
                    System.currentTimeMillis(),
                    // 审批状态由 QuarantinedDropsStore.record 在“同一经济判定”时保留。
                    false));
        } catch (RuntimeException failure) {
            LOGGER.warn("写入隔离记录失败，物品 {} 的隔离计数仍然有效", candidate.item(), failure);
        }
    }

    /**
     * 汇总创造物品保护在本轮扫描中的判定结果。
     *
     * <p>被排除的物品既不会获得配方/兜底值，也不会作为求解器的下游输入。固定值只在没有
     * 正值既有 EMC 时生效：外部/手动值仍然优先，自有条目会先经过归属分离再重新定价。</p>
     *
     * @param snapshot 当前扫描快照
     * @param seeds    初始种子，用于判断既有 EMC 是否受保护
     * @param minimumEmc 生成值下限
     * @return 禁止定价物品与生效的固定值
     */
    private CreativeProtectionPlan planCreativeProtection(PipelineSnapshot snapshot,
                                                          Map<ItemKey, Long> seeds,
                                                          long minimumEmc) {
        Set<ItemKey> deniedItems = new HashSet<>();
        Map<ItemKey, Long> specialValues = new LinkedHashMap<>();
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            CreativeItemProtection.Decision decision;
            try {
                decision = creativeProtection.decide(item, false, false);
            } catch (RuntimeException failure) {
                LOGGER.warn("创造物品保护判定失败，跳过物品 {}", item, failure);
                continue;
            }
            if (!decision.priceable()) {
                deniedItems.add(item);
                continue;
            }
            long specialValue = decision.specialValue();
            if (specialValue <= 0L) {
                continue;
            }
            if (specialValue < minimumEmc) {
                LOGGER.debug("物品 {} 的固定值 {} 低于最小 EMC {}，跳过",
                        item, specialValue, minimumEmc);
                continue;
            }
            if (seeds.containsKey(item)) {
                // 既有 EMC（外部/手动值）优先于配置固定值。
                continue;
            }
            specialValues.put(item, specialValue);
        }
        return new CreativeProtectionPlan(deniedItems, specialValues);
    }

    /**
     * 收集所有会由配方产出的物品。
     *
     * <p>多轮兜底只给“叶子”物品补种：配方输出必须等待求解器按材料成本定价，否则第一轮
     * 兜底种子会让 {@link RecipeSolver} 跳过发布，配方值永远无法覆盖兜底价。</p>
     */
    private static Set<ItemKey> recipeOutputItems(PipelineSnapshot snapshot) {
        Set<ItemKey> outputs = new HashSet<>();
        for (RecipeSnapshot recipe : snapshot.recipes()) {
            if (recipe == null || recipe.outputs() == null) {
                continue;
            }
            for (RecipeOutput output : recipe.outputs()) {
                if (output != null && output.item() != null) {
                    outputs.add(output.item());
                }
            }
        }
        return outputs;
    }

    /**
     * 创造物品保护在本轮扫描中的预计算结果。
     *
     * @param deniedItems  禁止自动定价的物品
     * @param specialValues 需要作为初始种子生效的固定值
     */
    private record CreativeProtectionPlan(Set<ItemKey> deniedItems,
                                          Map<ItemKey, Long> specialValues) {

        CreativeProtectionPlan {
            deniedItems = Set.copyOf(deniedItems);
            specialValues = Map.copyOf(specialValues);
        }
    }

    /**
     * 判定一次拟写入值是否被大幅降价保护隔离。
     *
     * <p>保护本身抛出异常时选择放行并记录警告：保护层是安全辅助，不应因为单个物品的异常状态
     * 阻断整轮扫描。放行结果使用 {@code protection-error} 作为机器可读原因。</p>
     */
    private PriceDropDecision evaluateDropProtection(Candidate candidate, long previousValue) {
        try {
            return dropProtection.evaluate(candidate.item(), previousValue, candidate.value());
        } catch (RuntimeException failure) {
            LOGGER.warn("大幅降价保护判定失败，放行物品 {}", candidate.item(), failure);
            return PriceDropDecision.allowed(
                    previousValue, candidate.value(), 0.0D, "protection-error");
        }
    }

    /**
     * 对候选值应用溢出保护并写入审计日志。
     *
     * <p>没有配置溢出管线时原值返回。管线内部已经保证不抛异常，这里仍然兜底一层，避免未来实现
     * 变化破坏“整轮扫描绝不中断”的契约。</p>
     */
    private long applyOverflowProtection(Candidate candidate) {
        if (overflowPipeline == null) {
            return candidate.value();
        }
        try {
            return overflowPipeline.applyAndAudit(
                    candidate.item(), candidate.value(), overflowScope);
        } catch (RuntimeException failure) {
            LOGGER.warn("溢出保护执行失败，保留物品 {} 的原值 {}", candidate.item(), candidate.value(), failure);
            return candidate.value();
        }
    }

    /**
     * 在作用域与显式开关都允许时处理 ProjectE 的 {@code custom_emc.json}。
     *
     * <p>红线：只重置 {@code store::isOwned} 的自有条目；管理员手动配置的条目即使超过阈值也
     * 永不改写。{@link OverflowScope#ENTIRE_CUSTOM_EMC} 仅表示扫描整个文件，不构成改写非自有
     * 条目的授权。干跑模式（{@code store == null}）一律跳过，避免测试或预览路径意外写入真实
     * 配置目录。文件改写成功时立即落盘审计；失败只记录警告，绝不中断本轮扫描。</p>
     */
    private void rewriteProjectECustomEmcIfEnabled() {
        if (overflowScope != OverflowScope.ENTIRE_CUSTOM_EMC || !allowProjectEFileRewrite) {
            return;
        }
        if (overflowPipeline == null) {
            return;
        }
        if (store == null) {
            LOGGER.debug("干跑模式跳过 ProjectE custom_emc.json 改写");
            return;
        }
        try {
            ProjectECustomEmcFile target = projectECustomEmcFile == null
                    ? new ProjectECustomEmcFile()
                    : new ProjectECustomEmcFile(projectECustomEmcFile);
            // 生产接线固定只允许自有条目：ENTIRE_CUSTOM_EMC 不是改写管理员手动值的授权。
            ProjectECustomEmcFile.RewriteResult result = target.rewriteAboveThreshold(
                    overflowPipeline.protection(),
                    store::isOwned,
                    overflowPipeline::recordCustomEmcReset);
            if (result.wroteFile()) {
                // 破坏性写盘已成功，立即落盘审计，避免进程崩溃时只剩 .bak 备份。
                overflowPipeline.flushAudit();
                LOGGER.info("ProjectE custom_emc.json 受控改写完成：rewritten={} skipped={} notOwned={} backup={}",
                        result.rewritten(), result.skipped(), result.notOwned(), result.backup());
            } else {
                LOGGER.debug("ProjectE custom_emc.json 本轮无需改写：skipped={} notOwned={}",
                        result.skipped(), result.notOwned());
            }
        } catch (Throwable failure) {
            LOGGER.warn("改写 ProjectE custom_emc.json 失败，本轮扫描结果仍然有效", failure);
        }
    }

    /**
     * 把本轮新增的溢出审计记录原子落盘。
     *
     * <p>审计日志不再只在优雅关服时保存：每轮扫描应用完成后都会刷新，崩溃或强杀最多丢失
     * 当前这一轮的记录。写入失败由 {@link OverflowPipeline} 记录警告并保留 dirty 标记，
     * 下一轮扫描会重试；任何失败都不影响已经应用的扫描结果。</p>
     */
    private void persistOverflowAudit() {
        if (overflowPipeline == null) {
            return;
        }
        try {
            overflowPipeline.saveAuditIfDirty();
        } catch (RuntimeException failure) {
            LOGGER.warn("保存溢出审计日志失败，本轮结果仍然有效，下一轮将重试", failure);
        }
    }

    /**
     * 更新增量扫描状态。状态存储自身不会抛出异常，这里仍捕获运行时异常以保护结果返回路径。
     */
    private void updateScanState(PipelineSnapshot snapshot, long durationMillis,
                                 int solvedCount, int unresolvedCount, int solverRounds) {
        if (stateStore == null) {
            return;
        }
        try {
            stateStore.update(fingerprint(snapshot), durationMillis, solvedCount, unresolvedCount,
                    solverRounds);
        } catch (RuntimeException failure) {
            LOGGER.warn("更新扫描状态失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * 写出诊断报告。报告写入失败只影响诊断文件，不影响本轮数值结果。
     */
    private void writeDiagnosticReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                       int unresolvedCount, int applied, int quarantined,
                                       int overflowResets, long durationMillis, int solverRounds,
                                       boolean appliedToStore, Map<String, Long> sourceCounts,
                                       int refreshedOwned, int retiredEntries) {
        if (!generateReportsEnabled || reportManager == null) {
            return;
        }
        try {
            reportManager.writeReport(buildReport(snapshot, solveResult, unresolvedCount,
                    quarantined, overflowResets, durationMillis, solverRounds, appliedToStore,
                    sourceCounts, refreshedOwned, retiredEntries));
        } catch (RuntimeException failure) {
            LOGGER.warn("写入诊断报告失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * 根据本轮运行数据构造诊断报告。
     *
     * <p>标准字段只统计实际应用的条目；求解器的失败配方、轮数与干跑标记等放入
     * {@code extraCounters}，便于后续解析器在不改变报告结构的情况下读取。未解析物品数
     * 使用多轮收敛后的最终计数，而不是某一轮的中间结果。</p>
     */
    private static DiagnosticReport buildReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                                int unresolvedCount, int quarantined,
                                                int overflowResets, long durationMillis,
                                                int solverRounds, boolean appliedToStore,
                                                Map<String, Long> sourceCounts, int refreshedOwned,
                                                int retiredEntries) {
        Map<String, Long> extraCounters = new LinkedHashMap<>();
        extraCounters.put("unsupportedRecipes", (long) snapshot.unsupportedRecipes());
        extraCounters.put("invalidRecipes", (long) snapshot.invalidRecipes());
        extraCounters.put("failedRecipes", (long) snapshot.failedRecipes());
        extraCounters.put("solverPasses", (long) solveResult.passesUsed());
        extraCounters.put("solverRounds", (long) solverRounds);
        extraCounters.put("recipesEvaluated", (long) solveResult.recipesEvaluated());
        extraCounters.put("solverConverged", solveResult.converged() ? 1L : 0L);
        extraCounters.put("specialValues",
                sourceCounts.getOrDefault(PriceSource.SPECIAL_VALUE.name(), 0L));
        extraCounters.put("overflowResets", (long) overflowResets);
        extraCounters.put("ownedRefreshed", (long) refreshedOwned);
        extraCounters.put("dryRun", appliedToStore ? 0L : 1L);
        extraCounters.put("retiredEntries", (long) retiredEntries);

        return new DiagnosticReport(
                snapshot.itemCount(),
                snapshot.existingValues().size(),
                sourceCounts.getOrDefault(PriceSource.RECIPE.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.TAG.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.RARITY.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.TIER.name(), 0L),
                sourceCounts.getOrDefault(PriceSource.FALLBACK.name(), 0L),
                unresolvedCount,
                quarantined,
                snapshot.failedRecipes(),
                durationMillis,
                usedHeapBytes(),
                extraCounters);
    }

    /**
     * 计算扫描指纹：物品 id、配方 id 升序摘要；标签、配置与基线通道本轮为空。
     */
    private static ScanFingerprint fingerprint(PipelineSnapshot snapshot) {
        List<String> itemIds = new ArrayList<>(snapshot.items().size());
        for (ItemSnapshot item : snapshot.items()) {
            itemIds.add(item.registryId().toString());
        }
        List<String> recipeIds = new ArrayList<>(snapshot.recipes().size());
        for (RecipeSnapshot recipe : snapshot.recipes()) {
            recipeIds.add(recipe.id().toString());
        }
        return ScanFingerprint.of(itemIds, recipeIds, List.of(), List.of(), List.of());
    }

    private static long usedHeapBytes() {
        Runtime runtime = Runtime.getRuntime();
        return Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * 一条待写入的候选值。
     *
     * <p>可见性为包内可见，便于同包测试直接验证“同一物品只保留配方来源”的合并规则。</p>
     *
     * @param item             物品身份
     * @param value            经过求解或兜底定价得到的原始数值，保证 {@code > 0}
     * @param source           数值来源
     * @param recipeId         产生该值的配方，兜底定价结果为 {@code null}
     * @param alternativeCosts 同一轮求解中的其它配方成本；兜底来源为空列表
     */
    record Candidate(ItemKey item, long value, PriceSource source,
                     @Nullable ResourceLocation recipeId, List<Long> alternativeCosts) {

        Candidate {
            alternativeCosts = alternativeCosts == null ? List.of() : List.copyOf(alternativeCosts);
        }

        /**
         * 兼容旧测试与调用点的构造器：不携带备选成本。
         */
        Candidate(ItemKey item, long value, PriceSource source,
                  @Nullable ResourceLocation recipeId) {
            this(item, value, source, recipeId, List.of());
        }
    }
}
