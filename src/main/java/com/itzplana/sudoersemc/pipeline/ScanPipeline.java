package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.integration.ProjectECustomEmcFile;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
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
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
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
 * 计算，但不会写入任何持久化条目。其余协作者为 {@code null} 时按“关闭该能力”处理：降价保护与
 * 创造物品保护使用安全默认值，兜底定价与溢出保护在缺失时直接跳过。这样单元测试可以在没有完整
 * 运行时环境的情况下验证管线主干。</p>
 *
 * <p><strong>异常契约：</strong>{@link #collect(MinecraftServer)} 与
 * {@link #solveAndApply(PipelineSnapshot)} 都不会向调用方抛出异常。任何失败都会记录日志并返回
 * 空快照或全零结果，保证服务器主循环与 {@code ScanManager} 状态机不会被单次扫描拖垮。</p>
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
    private final ScanStateStore stateStore;
    private final ReportManager reportManager;
    private final LargeDropProtection dropProtection;
    private final CreativeItemProtection creativeProtection;
    private final OverflowPipeline overflowPipeline;
    private final FallbackPricing fallbackPricing;
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
     * 设置溢出保护作用域。
     *
     * <p>生产环境默认 {@link OverflowScope#OWNED_ONLY}，只允许改写 SudoersEMC 自己拥有的条目。
     * 传入 {@code null} 时恢复该默认值，绝不隐式扩大作用域。</p>
     *
     * @param scope 作用域，允许为 {@code null}
     */
    public void setOverflowScope(@Nullable OverflowScope scope) {
        this.overflowScope = scope == null ? OverflowScope.OWNED_ONLY : scope;
    }

    /**
     * 设置是否允许改写 ProjectE 的 {@code custom_emc.json}。
     *
     * <p>默认 {@code false}。只有同时启用 {@link OverflowScope#ENTIRE_CUSTOM_EMC} 作用域时才会
     * 产生实际文件写入；这是避免误改管理员手动配置的第二道保险。</p>
     *
     * @param enabled 是否允许改写 ProjectE 文件
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
     * @param server 当前服务器实例，允许为 {@code null}
     * @return 采集结果；任何失败或服务器未就绪时返回空快照
     */
    public PipelineSnapshot collect(@Nullable MinecraftServer server) {
        if (server == null) {
            LOGGER.warn("SudoersEMC 扫描采集被调用时服务器尚未就绪，返回空快照");
            return PipelineSnapshot.empty();
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
            };

            List<ItemSnapshot> items = new ItemScanner(query, Set.of(), Set.of()).scan();

            RecipeScanner recipeScanner = RecipeScanner.withGoety().orElseGet(RecipeScanner::new);
            RecipeScanner.ScanResult scanResult = recipeScanner.scan(server.getRecipeManager());

            List<ItemSnapshot> variantItems = collectVariantSnapshots(
                    items, scanResult.recipes(), nbtVariantsEnabled, maxNbtVariantsPerItem);
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
        } catch (Throwable failure) {
            LOGGER.error("SudoersEMC 扫描采集失败，本轮按空快照处理", failure);
            return PipelineSnapshot.empty();
        }
    }

    /**
     * 根据配方输出中观测到的 SNBT 生成 NBT 变体快照。
     *
     * <p>处理顺序固定：先按物品 id 汇总所有 {@link RecipeOutput#outputNbt()}，再把 SNBT
     * 解析回 {@link CompoundTag}，交给 {@link VariantScanner} 规范化、去重并限制数量。只有
     * 已在基础物品快照中出现过的物品才会生成变体，因此忽略规则与注册表缺失物品不会被
     * 变体扫描重新引入。</p>
     *
     * <p>变体快照的 {@code hasExistingEmc} 固定为 {@code false}、{@code existingValue} 固定为
     * {@code 0}，标签路径与稀有度继承基础物品。结果按 {@link ItemKey} 排序（物品 id，然后
     * variantHash），保证相同输入得到稳定的输出顺序；无法解析或无法规范化的标签会被跳过。</p>
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
        if (!enabled || baseItems == null || baseItems.isEmpty()
                || recipes == null || recipes.isEmpty()) {
            return List.of();
        }
        int limit = Math.max(1, Math.min(maxNbtVariantsPerItem, VariantScanner.HARD_VARIANT_CEILING));

        Map<ResourceLocation, List<String>> observedByItem = new TreeMap<>();
        for (RecipeSnapshot recipe : recipes) {
            for (RecipeOutput output : recipe.outputs()) {
                String outputNbt = output.outputNbt();
                if (outputNbt == null || outputNbt.isBlank()) {
                    continue;
                }
                observedByItem
                        .computeIfAbsent(output.item().itemId(), id -> new ArrayList<>())
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

        VariantScanner variantScanner = new VariantScanner();
        Set<ItemKey> seen = new HashSet<>();
        List<ItemSnapshot> variants = new ArrayList<>();
        for (Map.Entry<ResourceLocation, List<String>> entry : observedByItem.entrySet()) {
            ItemSnapshot base = baseByItem.get(entry.getKey());
            if (base == null) {
                // 基础物品未被扫描（被忽略或未注册）时不生成变体，避免绕过排除规则。
                continue;
            }
            Item item = BuiltInRegistries.ITEM.get(entry.getKey());
            if (item == null || item == Items.AIR) {
                continue;
            }

            List<CompoundTag> observedTags = new ArrayList<>(entry.getValue().size());
            for (String snbt : entry.getValue()) {
                try {
                    observedTags.add(TagParser.parseTag(snbt));
                } catch (Exception failure) {
                    LOGGER.debug("无法解析配方输出 SNBT，跳过该观测变体：{}", snbt, failure);
                }
            }
            if (observedTags.isEmpty()) {
                continue;
            }

            List<ItemKey> variantKeys;
            try {
                variantKeys = variantScanner.scan(new ItemStack(item), observedTags, limit);
            } catch (RuntimeException failure) {
                LOGGER.debug("生成物品 {} 的 NBT 变体失败，跳过", entry.getKey(), failure);
                continue;
            }
            for (ItemKey variantKey : variantKeys) {
                if (!variantKey.hasVariant() || !seen.add(variantKey)) {
                    continue;
                }
                variants.add(ItemSnapshot.of(
                        variantKey, false, 0L, base.tagPaths(), base.rarity()));
            }
        }

        if (variants.isEmpty()) {
            return List.of();
        }
        variants.sort(Comparator.comparing(ItemSnapshot::key));
        return List.copyOf(variants);
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
     * @return 分离后的物品、既有值与刷新数量
     */
    static OwnershipSeparation separateOwnedEntries(List<ItemSnapshot> items,
                                                    Predicate<ItemKey> owned) {
        List<ItemSnapshot> separated = new ArrayList<>(items.size());
        Map<ItemKey, Long> existingValues = new TreeMap<>();
        int refreshedOwned = 0;
        for (ItemSnapshot item : items) {
            ItemKey key = item.key();
            if (owned.test(key)) {
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
        return new OwnershipSeparation(separated, existingValues, refreshedOwned);
    }

    /**
     * 在应用阶段执行归属分离。
     *
     * <p>分离放在应用阶段而不是采集阶段：采集只如实记录后端观察到的状态，是否归 SudoersEMC
     * 所有由持久化存储决定。这样即使调用方直接构造带既有值的快照，也会在写入前正确刷新
     * 自有条目；干跑模式没有存储，保持原始语义不变。</p>
     *
     * @param snapshot 采集结果，不能为 {@code null}
     * @return 分离后的快照；无需刷新时返回原实例
     */
    private PipelineSnapshot applyOwnershipSeparation(PipelineSnapshot snapshot) {
        if (store == null) {
            return snapshot;
        }
        OwnershipSeparation separation = separateOwnedEntries(snapshot.items(), store::isOwned);
        if (separation.refreshedOwned() == 0) {
            return snapshot;
        }
        LOGGER.debug("SudoersEMC 归属分离刷新了 {} 个自有条目，本轮将重新求解其数值",
                separation.refreshedOwned());
        return new PipelineSnapshot(
                separation.items(),
                snapshot.recipes(),
                separation.existingValues(),
                snapshot.unsupportedRecipes(),
                snapshot.invalidRecipes(),
                snapshot.failedRecipes());
    }

    /**
     * 归属分离结果。
     *
     * @param items          分离后的物品快照，保持输入顺序
     * @param existingValues 仍然受保护的非自有既有值，按键升序排列
     * @param refreshedOwned 被清除既有值的自有条目数量
     */
    record OwnershipSeparation(List<ItemSnapshot> items, Map<ItemKey, Long> existingValues,
                               int refreshedOwned) {

        OwnershipSeparation {
            items = List.copyOf(items);
            existingValues = Collections.unmodifiableMap(new TreeMap<>(existingValues));
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
     * <p>存储为 {@code null} 时进入干跑模式：计数与报告照常生成，但不会产生任何持久化写入。</p>
     *
     * @param snapshot 采集结果，允许为 {@code null}
     * @return 汇总结果；任何未预期异常都会转为全零结果
     */
    public PipelineResult solveAndApply(@Nullable PipelineSnapshot snapshot) {
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
        long startedAtNanos = System.nanoTime();

        try {
            data = applyOwnershipSeparation(data);
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

            SolveResult lastSolveResult = skippedSolveResult();
            int roundsUsed = 0;
            for (int round = 1; round <= MAX_SOLVE_ROUNDS; round++) {
                roundsUsed = round;
                SolveResult roundResult = solveRound(data, seeds, protection.deniedItems());
                lastSolveResult = roundResult;

                boolean solvedProgress = mergeSolvedValues(roundResult, recipeCandidates, seeds,
                        protection.deniedItems(), protection.specialValues());
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
                    boolean staged = store.stage(new GeneratedEmcEntry(
                            candidate.item(),
                            finalValue,
                            candidate.source(),
                            candidate.recipeId(),
                            true,
                            System.currentTimeMillis()));
                    if (!staged) {
                        LOGGER.debug("物品 {} 的生成值未被存储接受，可能已有非 SudoersEMC 拥有的条目",
                                candidate.item());
                        continue;
                    }
                }

                applied++;
                sourceCounts.merge(candidate.source().name(), 1L, Long::sum);
            }

            rewriteProjectECustomEmcIfEnabled();

            int overflowResets = overflowPipeline == null
                    ? 0
                    : Math.max(0, overflowPipeline.resetsApplied() - overflowBefore);
            int unresolved = countUnresolved(lastSolveResult, seeds);
            long durationMillis = elapsedMillis(startedAtNanos);

            lastSolvedCount = applied;
            lastUnresolvedCount = unresolved;
            lastDurationMillis = durationMillis;

            boolean appliedToStore = store != null;
            updateScanState(data, durationMillis, applied, unresolved, roundsUsed);
            writeDiagnosticReport(data, lastSolveResult, unresolved, applied, quarantined,
                    overflowResets, durationMillis, roundsUsed, appliedToStore, sourceCounts);

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
     * @return 本轮是否新增或更新了任何种子
     */
    private boolean mergeSolvedValues(SolveResult solveResult,
                                      Map<ItemKey, Candidate> recipeCandidates,
                                      Map<ItemKey, Long> seeds,
                                      Set<ItemKey> deniedItems,
                                      Map<ItemKey, Long> specialValues) {
        boolean changed = false;
        boolean allowIncrease = solveSettings.allowValueIncrease();
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            ItemKey item = entry.getKey();
            long solvedValue = entry.getValue();
            if (solvedValue <= 0L || deniedItems.contains(item)) {
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
            Candidate existing = recipeCandidates.get(item);
            if (existing == null || mergedValue < existing.value()
                    || allowIncrease || source == PriceSource.SPECIAL_VALUE) {
                recipeCandidates.put(item,
                        new Candidate(item, mergedValue, source, recipeId));
            }
        }
        return changed;
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
     * <p>只处理既没有正值既有 EMC、也不在当前种子中的物品；0 值既有 EMC 等同于“没有值”。
     * 创造物品保护决定物品是否允许自动定价；固定值优先于兜底定价链。兜底链返回非正值、
     * 低于最小 EMC 或空结果时不产生候选。命中值同时写入 {@code seeds}，使下一轮配方求解
     * 能够把兜底价格传播到上游中间产物。</p>
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
                continue;
            }

            if (decision.specialValue() > 0L) {
                if (decision.specialValue() >= minimumEmc) {
                    Candidate candidate = new Candidate(
                            item, decision.specialValue(), PriceSource.SPECIAL_VALUE, null);
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
     * 在作用域与显式开关都允许时改写 ProjectE 的 {@code custom_emc.json}。
     *
     * <p>干跑模式（{@code store == null}）一律跳过，避免测试或预览路径意外写入真实配置目录。
     * 文件改写失败只记录警告，绝不中断本轮扫描。</p>
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
            ProjectECustomEmcFile.RewriteResult result = target.rewriteAboveThreshold(
                    overflowPipeline.protection(),
                    store::isOwned,
                    // ENTIRE_CUSTOM_EMC 本身就是改写整个文件的显式 opt-in。
                    true,
                    overflowPipeline::recordCustomEmcReset);
            if (result.wroteFile()) {
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
                                       boolean appliedToStore, Map<String, Long> sourceCounts) {
        if (!generateReportsEnabled || reportManager == null) {
            return;
        }
        try {
            reportManager.writeReport(buildReport(snapshot, solveResult, unresolvedCount,
                    quarantined, overflowResets, durationMillis, solverRounds, appliedToStore,
                    sourceCounts));
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
                                                Map<String, Long> sourceCounts) {
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
        extraCounters.put("dryRun", appliedToStore ? 0L : 1L);

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
     * @param item     物品身份
     * @param value    经过求解或兜底定价得到的原始数值，保证 {@code > 0}
     * @param source   数值来源
     * @param recipeId 产生该值的配方，兜底定价结果为 {@code null}
     */
    record Candidate(ItemKey item, long value, PriceSource source,
                     @Nullable ResourceLocation recipeId) {
    }
}
