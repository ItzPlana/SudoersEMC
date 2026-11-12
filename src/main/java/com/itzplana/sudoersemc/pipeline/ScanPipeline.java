package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.OverflowScope;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.diagnostic.DiagnosticReport;
import com.itzplana.sudoersemc.diagnostic.ReportManager;
import com.itzplana.sudoersemc.integration.EmcProvider;
import com.itzplana.sudoersemc.pricing.FallbackPricing;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowPipeline;
import com.itzplana.sudoersemc.safety.PriceDropDecision;
import com.itzplana.sudoersemc.scanner.ItemScanner;
import com.itzplana.sudoersemc.scanner.ItemSnapshot;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
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
 * <p><strong>异常契约：</strong>{@link #collect(MinecraftServer)} 在服务器未就绪或采集失败时
 * 抛出 {@link IllegalStateException}，由 {@code ScanManager} 转为 {@code FAILED}，绝不把失败
 * 伪装成“成功的空扫描”；{@link #solveAndApply(PipelineSnapshot)} 仍不向调用方抛出异常，
 * 任何未预期失败记录日志后返回全零结果，保证服务器主循环不被单次扫描拖垮。</p>
 */
public final class ScanPipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/pipeline/ScanPipeline");

    private final GeneratedEntryStore store;
    private final ScanStateStore stateStore;
    private final ReportManager reportManager;
    private final LargeDropProtection dropProtection;
    private final CreativeItemProtection creativeProtection;
    private final OverflowPipeline overflowPipeline;
    private final FallbackPricing fallbackPricing;
    private final SolveSettings solveSettings;

    private final AtomicReference<PipelineSnapshot> stashedSnapshot = new AtomicReference<>();

    private volatile EmcProvider emcProvider;
    private volatile OverflowScope overflowScope = OverflowScope.OWNED_ONLY;
    private volatile int lastSolvedCount;
    private volatile int lastUnresolvedCount;
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
            };

            List<ItemSnapshot> items = new ItemScanner(query, Set.of(), Set.of()).scan();

            RecipeScanner recipeScanner = RecipeScanner.withGoety().orElseGet(RecipeScanner::new);
            RecipeScanner.ScanResult scanResult = recipeScanner.scan(server.getRecipeManager());

            Map<ItemKey, Long> existingValues = new TreeMap<>();
            for (ItemSnapshot item : items) {
                if (item.hasExistingEmc()) {
                    existingValues.put(item.key(), item.existingValue());
                }
            }

            return new PipelineSnapshot(
                    items,
                    scanResult.recipes(),
                    existingValues,
                    scanResult.unsupported(),
                    scanResult.invalid(),
                    scanResult.failed());
        } catch (Throwable failure) {
            LOGGER.error("SudoersEMC 扫描采集失败", failure);
            throw new IllegalStateException("SudoersEMC 扫描采集失败", failure);
        }
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
     * 求解快照并为可定价物品写入生成值。
     *
     * <p>本方法不访问注册表，只使用快照中的纯数据。处理顺序固定为：</p>
     * <ol>
     *   <li>用已有 EMC 作为种子运行配方求解器，求解器产出的值以 {@link PriceSource#RECIPE} 记录；</li>
     *   <li>对既没有既有 EMC、也没有被求解器定价的物品，依次尝试创造物品保护固定值与兜底定价链；</li>
     *   <li>每个候选值先经过大幅降价保护，再经过溢出保护，最后才写入存储；</li>
     *   <li>更新扫描指纹、写出诊断报告并返回汇总结果。</li>
     * </ol>
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
            SolveContext context = new SolveContext(data.existingValues());
            SolveResult solveResult = new RecipeSolver().solve(
                    data.recipes(), context, EvaluatorRegistry.defaults(), solveSettings);

            List<Candidate> candidates = new ArrayList<>();
            int solvedByRecipe = addRecipeCandidates(solveResult, candidates);
            int solvedByFallback = addFallbackCandidates(data, solveResult, candidates);

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

            int overflowResets = overflowPipeline == null
                    ? 0
                    : Math.max(0, overflowPipeline.resetsApplied() - overflowBefore);
            int unresolved = solveResult.unresolvedItems().size();
            long durationMillis = elapsedMillis(startedAtNanos);

            lastSolvedCount = applied;
            lastUnresolvedCount = unresolved;
            lastDurationMillis = durationMillis;

            boolean appliedToStore = store != null;
            updateScanState(data, durationMillis, applied, unresolved);
            writeDiagnosticReport(data, solveResult, applied, quarantined, overflowResets,
                    durationMillis, appliedToStore, sourceCounts);

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
     * 把求解器产出的配方值转换为候选条目。
     *
     * <p>求解器生成值具有权威性：这些物品不会再进入兜底定价链，来源固定为
     * {@link PriceSource#RECIPE}。配方 id 来自对应的 {@link SolveTrace}，缺失时允许为
     * {@code null}，不影响数值本身。</p>
     *
     * @return 追加的候选数量
     */
    private static int addRecipeCandidates(SolveResult solveResult, List<Candidate> candidates) {
        int added = 0;
        for (Map.Entry<ItemKey, Long> entry : solveResult.values().entrySet()) {
            long value = entry.getValue();
            if (value <= 0L) {
                continue;
            }
            SolveTrace trace = solveResult.traces().get(entry.getKey());
            ResourceLocation recipeId = trace == null ? null : trace.recipeId();
            candidates.add(new Candidate(entry.getKey(), value, PriceSource.RECIPE, recipeId));
            added++;
        }
        return added;
    }

    /**
     * 为求解器没有覆盖的物品追加兜底候选。
     *
     * <p>只处理既不在既有 EMC 中、也不在求解结果中的物品。创造物品保护决定物品是否允许自动
     * 定价；固定值优先于兜底定价链。兜底链返回非正值或空结果时不产生候选。</p>
     *
     * @return 追加的候选数量
     */
    private int addFallbackCandidates(PipelineSnapshot snapshot, SolveResult solveResult,
                                      List<Candidate> candidates) {
        int added = 0;
        for (ItemSnapshot itemSnapshot : snapshot.items()) {
            ItemKey item = itemSnapshot.key();
            if (snapshot.existingValues().containsKey(item) || solveResult.values().containsKey(item)) {
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
                candidates.add(new Candidate(item, decision.specialValue(), PriceSource.TIER, null));
                added++;
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
            if (pricedValue.value() <= 0L) {
                continue;
            }
            candidates.add(new Candidate(item, pricedValue.value(), pricedValue.source(), null));
            added++;
        }
        return added;
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
     * 更新增量扫描状态。状态存储自身不会抛出异常，这里仍捕获运行时异常以保护结果返回路径。
     */
    private void updateScanState(PipelineSnapshot snapshot, long durationMillis,
                                 int solvedCount, int unresolvedCount) {
        if (stateStore == null) {
            return;
        }
        try {
            stateStore.update(fingerprint(snapshot), durationMillis, solvedCount, unresolvedCount);
        } catch (RuntimeException failure) {
            LOGGER.warn("更新扫描状态失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * 写出诊断报告。报告写入失败只影响诊断文件，不影响本轮数值结果。
     */
    private void writeDiagnosticReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                       int applied, int quarantined, int overflowResets,
                                       long durationMillis, boolean appliedToStore,
                                       Map<String, Long> sourceCounts) {
        if (reportManager == null) {
            return;
        }
        try {
            reportManager.writeReport(buildReport(snapshot, solveResult, quarantined,
                    overflowResets, durationMillis, appliedToStore, sourceCounts));
        } catch (RuntimeException failure) {
            LOGGER.warn("写入诊断报告失败，本轮结果仍然有效", failure);
        }
    }

    /**
     * 根据本轮运行数据构造诊断报告。
     *
     * <p>标准字段只统计实际应用的条目；求解器的失败配方、轮数与干跑标记等放入
     * {@code extraCounters}，便于后续解析器在不改变报告结构的情况下读取。</p>
     */
    private static DiagnosticReport buildReport(PipelineSnapshot snapshot, SolveResult solveResult,
                                                int quarantined, int overflowResets,
                                                long durationMillis, boolean appliedToStore,
                                                Map<String, Long> sourceCounts) {
        Map<String, Long> extraCounters = new LinkedHashMap<>();
        extraCounters.put("unsupportedRecipes", (long) snapshot.unsupportedRecipes());
        extraCounters.put("invalidRecipes", (long) snapshot.invalidRecipes());
        extraCounters.put("failedRecipes", (long) snapshot.failedRecipes());
        extraCounters.put("solverPasses", (long) solveResult.passesUsed());
        extraCounters.put("recipesEvaluated", (long) solveResult.recipesEvaluated());
        extraCounters.put("solverConverged", solveResult.converged() ? 1L : 0L);
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
                solveResult.unresolvedItems().size(),
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
     * @param item     物品身份
     * @param value    经过求解或兜底定价得到的原始数值，保证 {@code > 0}
     * @param source   数值来源
     * @param recipeId 产生该值的配方，兜底定价结果为 {@code null}
     */
    private record Candidate(ItemKey item, long value, PriceSource source,
                             @Nullable ResourceLocation recipeId) {
    }
}
