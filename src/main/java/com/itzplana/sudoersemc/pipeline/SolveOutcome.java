package com.itzplana.sudoersemc.pipeline;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.solver.SolveResult;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 纯计算求解阶段的不可变结果。
 *
 * <p>{@link ScanPipeline#solveOnly(PipelineSnapshot)} 只做归属分离、种子构建、多轮
 * “配方求解 → 兜底补种”收敛、候选去重与未解析统计，不写存储、不触发溢出审计、不写报告或
 * 状态文件。所有字段在构造时做防御性复制，因此可以安全地从工作线程交给服务器线程。</p>
 *
 * <p>{@link #startedAtNanos()} 与 {@link #durationMillis()} 只用于诊断计时；它们不参与
 * 求解的确定性比较。比较两次纯求解结果时应使用 {@link #sameComputation(SolveOutcome)}。</p>
 *
 * @param effectiveSnapshot  已完成归属分离、实际参与求解的快照
 * @param candidates         最终候选（已排除受保护物品），按物品升序
 * @param solveResult        最后一轮求解结果，包含轨迹与配方统计
 * @param unresolved         收敛后仍无 EMC 的物品数量
 * @param solverRounds       实际执行的求解轮数
 * @param cacheHits          本轮求解器缓存命中数
 * @param cacheMisses        本轮求解器缓存未命中数
 * @param ownershipDetected  检测到的外部修改条目数
 * @param protectedItems     因外部修改/手工值而受保护的物品集合
 * @param resolverApplied    第三方 resolver 实际命中的物品数
 * @param cancelled          求解过程中是否观察到取消
 * @param failed             求解过程中是否发生未预期异常
 * @param failureDetail      失败摘要；未失败时为空串
 * @param startedAtNanos     求解开始的 {@link System#nanoTime()}，供应用阶段计算总耗时
 * @param durationMillis     求解阶段自身耗时毫秒数
 */
public record SolveOutcome(
        PipelineSnapshot effectiveSnapshot,
        List<ScanPipeline.Candidate> candidates,
        SolveResult solveResult,
        int unresolved,
        int solverRounds,
        int cacheHits,
        int cacheMisses,
        int ownershipDetected,
        Set<ItemKey> protectedItems,
        int resolverApplied,
        boolean cancelled,
        boolean failed,
        String failureDetail,
        long startedAtNanos,
        long durationMillis
) {

    public SolveOutcome {
        effectiveSnapshot = Objects.requireNonNull(effectiveSnapshot, "effectiveSnapshot");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        solveResult = Objects.requireNonNull(solveResult, "solveResult");
        requireNonNegative("unresolved", unresolved);
        requireNonNegative("solverRounds", solverRounds);
        requireNonNegative("cacheHits", cacheHits);
        requireNonNegative("cacheMisses", cacheMisses);
        requireNonNegative("ownershipDetected", ownershipDetected);
        requireNonNegative("resolverApplied", resolverApplied);
        protectedItems = protectedItems == null
                ? Set.of()
                : Collections.unmodifiableSet(new TreeSet<>(protectedItems));
        failureDetail = failureDetail == null ? "" : failureDetail;
        if (durationMillis < 0L) {
            throw new IllegalArgumentException("durationMillis 必须 >= 0，实际为 " + durationMillis);
        }
    }

    /**
     * @return 候选条目数量
     */
    public int candidateCount() {
        return candidates.size();
    }

    /**
     * @return 每个候选的备选成本映射；没有候选时为空
     */
    public Map<ItemKey, List<Long>> alternativeCosts() {
        Map<ItemKey, List<Long>> costs = new TreeMap<>();
        for (ScanPipeline.Candidate candidate : candidates) {
            costs.put(candidate.item(), candidate.alternativeCosts());
        }
        return Collections.unmodifiableMap(costs);
    }

    /**
     * 比较两次纯求解结果是否一致，忽略仅用于诊断的计时字段。
     *
     * @param other 另一次求解结果，可为 {@code null}
     * @return 候选、轨迹、计数与保护集合全部相等时返回 {@code true}
     */
    public boolean sameComputation(SolveOutcome other) {
        if (other == null) {
            return false;
        }
        return candidates.equals(other.candidates)
                && solveResult.values().equals(other.solveResult.values())
                && solveResult.traces().equals(other.solveResult.traces())
                && solveResult.unresolvedRecipes().equals(other.solveResult.unresolvedRecipes())
                && solveResult.unresolvedItems().equals(other.solveResult.unresolvedItems())
                && solveResult.passesUsed() == other.solveResult.passesUsed()
                && solveResult.converged() == other.solveResult.converged()
                && solveResult.recipesEvaluated() == other.solveResult.recipesEvaluated()
                && unresolved == other.unresolved
                && solverRounds == other.solverRounds
                && ownershipDetected == other.ownershipDetected
                && protectedItems.equals(other.protectedItems)
                && resolverApplied == other.resolverApplied
                && cancelled == other.cancelled
                && failed == other.failed
                && failureDetail.equals(other.failureDetail);
    }

    /**
     * 构造一次失败结果，供 {@link ScanPipeline#applySolved(PipelineSnapshot, SolveOutcome)}
     * 写出部分报告并返回全零结果。
     */
    static SolveOutcome failed(PipelineSnapshot snapshot, long startedAtNanos,
                               String detail, int solverRounds, int cacheHits, int cacheMisses) {
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
        return new SolveOutcome(data, List.of(), emptySolveResult(), 0,
                solverRounds, cacheHits, cacheMisses, 0, Set.of(), 0,
                false, true, detail, startedAtNanos, elapsedMillis(startedAtNanos));
    }

    static SolveOutcome cancelled(PipelineSnapshot snapshot, SolveResult lastResult,
                                  int solverRounds, int cacheHits, int cacheMisses,
                                  int ownershipDetected, Set<ItemKey> protectedItems,
                                  int resolverApplied, long startedAtNanos) {
        PipelineSnapshot data = snapshot == null ? PipelineSnapshot.empty() : snapshot;
        return new SolveOutcome(data, List.of(),
                lastResult == null ? emptySolveResult() : lastResult, 0,
                solverRounds, cacheHits, cacheMisses, ownershipDetected,
                protectedItems, resolverApplied, true, false, "",
                startedAtNanos, elapsedMillis(startedAtNanos));
    }

    static SolveResult emptySolveResult() {
        return new SolveResult(Map.of(), Map.of(), List.of(), Set.of(), 0, false, 0L, 0);
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    private static void requireNonNegative(String name, int value) {
        if (value < 0) {
            throw new IllegalArgumentException(name + " 必须 >= 0，实际为 " + value);
        }
    }
}
