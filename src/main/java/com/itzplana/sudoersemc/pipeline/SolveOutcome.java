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
 * Immutable result of the pure computation solve phase.
 *
 * <p>{@link ScanPipeline#solveOnly(PipelineSnapshot)} only does ownership separation, seed building,
 * multi-round "recipe solve → fallback re-seed" convergence, candidate deduplication and unresolved
 * statistics; it does not write the store, trigger overflow audit, or write reports or state files. All
 * fields are defensively copied at construction, so it can be safely handed from the worker thread to the
 * server thread.</p>
 *
 * <p>{@link #startedAtNanos()} and {@link #durationMillis()} are only for diagnostic timing; they do not
 * participate in the solve's deterministic comparison. Use {@link #sameComputation(SolveOutcome)} to
 * compare two pure solve results.</p>
 *
 * @param effectiveSnapshot  the snapshot that actually participated in solving, after ownership separation
 * @param candidates         final candidates (protected items excluded), ascending by item
 * @param solveResult        the last round's solve result, including traces and recipe statistics
 * @param unresolved         number of items still without EMC after convergence
 * @param solverRounds       number of solve rounds actually executed
 * @param cacheHits          solver cache hits this run
 * @param cacheMisses        solver cache misses this run
 * @param ownershipDetected  number of externally modified entries detected
 * @param protectedItems     set of items protected due to external modification/manual values
 * @param resolverApplied    number of items actually hit by third-party resolvers
 * @param cancelled          whether cancellation was observed during solving
 * @param failed             whether an unexpected exception occurred during solving
 * @param failureDetail      failure summary; empty string when not failed
 * @param startedAtNanos     {@link System#nanoTime()} at solve start, for the apply phase to compute total elapsed time
 * @param durationMillis     the solve phase's own elapsed millis
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
     * @return number of candidate entries
     */
    public int candidateCount() {
        return candidates.size();
    }

    /**
     * @return alternative-cost map per candidate; empty when there are no candidates
     */
    public Map<ItemKey, List<Long>> alternativeCosts() {
        Map<ItemKey, List<Long>> costs = new TreeMap<>();
        for (ScanPipeline.Candidate candidate : candidates) {
            costs.put(candidate.item(), candidate.alternativeCosts());
        }
        return Collections.unmodifiableMap(costs);
    }

    /**
     * Whether two pure solve results agree, ignoring timing fields used only for diagnostics.
     *
     * @param other the other solve result, may be {@code null}
     * @return {@code true} when candidates, traces, counts and the protected set are all equal
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
     * Constructs a failed result, for {@link ScanPipeline#applySolved(PipelineSnapshot, SolveOutcome)} to
     * write a partial report and return an all-zero result.
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
