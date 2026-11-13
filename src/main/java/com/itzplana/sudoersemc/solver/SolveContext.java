package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * Mutable working state of one solve run; also implements {@link EmcLookup} so it can be handed
 * directly to evaluators.
 *
 * <p>State has three layers: {@code knownValues} is the EMC snapshot that existed before the run and is
 * read-only for the whole run; {@code provisionalValues} holds fallback prices pre-seeded for owned leaf
 * entries, used only for input lookup and never blocking a recipe override; {@code generatedValues} holds
 * values produced this run. Lookup prefers generated over provisional over known, but at publication the
 * solver first checks {@link #hasSeedValue(ItemKey)}, so pre-existing EMC is never overwritten.</p>
 *
 * <p>{@code deniedItems} is the set of items the safety layer forbids pricing: lookup always returns
 * {@code 0} for them and the solver never publishes a generated value, so an excluded item's value cannot
 * feed downstream recipes and bypass creative-item protection.</p>
 *
 * <p>This class makes no thread-safety guarantee; a solve runs on a single thread. Item maps use
 * {@link TreeMap} so diagnostic output order depends only on item identity, not hash-table iteration
 * order.</p>
 */
public final class SolveContext implements EmcLookup {

    private final Map<ItemKey, Long> knownValues;
    private final Set<ItemKey> deniedItems;
    private final NavigableMap<ItemKey, Long> provisionalValues = new TreeMap<>();
    private final NavigableMap<ItemKey, Long> generatedValues = new TreeMap<>();
    private final NavigableMap<ItemKey, SolveTrace> traces = new TreeMap<>();
    private int currentPass = 1;

    /**
     * Creates a context from an existing EMC snapshot.
     *
     * <p>Only positive values are kept: an entry with EMC 0 or negative is equivalent to "no value";
     * keeping it would wrongly shadow later recipe calculation.</p>
     *
     * @param knownValues EMC that existed before the run; may be empty
     */
    public SolveContext(Map<ItemKey, Long> knownValues) {
        this(knownValues, Set.of());
    }

    /**
     * Creates a context from an existing EMC snapshot and a set of items forbidden from pricing.
     *
     * <p>Only positive existing values are kept: an entry with EMC 0 or negative is equivalent to "no
     * value"; keeping it would wrongly shadow later recipe calculation. Denied items can neither be used
     * as evaluator inputs nor published by the solver.</p>
     *
     * @param knownValues EMC that existed before the run; may be empty
     * @param deniedItems items the safety layer forbids pricing; may be empty
     */
    public SolveContext(Map<ItemKey, Long> knownValues, Set<? extends ItemKey> deniedItems) {
        Objects.requireNonNull(knownValues, "knownValues");
        NavigableMap<ItemKey, Long> copy = new TreeMap<>();
        for (Map.Entry<ItemKey, Long> entry : knownValues.entrySet()) {
            ItemKey item = Objects.requireNonNull(entry.getKey(), "knownValues 的键不能为 null");
            Long value = Objects.requireNonNull(entry.getValue(), "knownValues 的值不能为 null");
            if (value > 0L) {
                copy.put(item, value);
            }
        }
        this.knownValues = Collections.unmodifiableMap(copy);

        Set<ItemKey> deniedCopy = new TreeSet<>();
        if (deniedItems != null) {
            for (ItemKey item : deniedItems) {
                deniedCopy.add(Objects.requireNonNull(item, "deniedItems 的元素不能为 null"));
            }
        }
        this.deniedItems = Collections.unmodifiableSet(deniedCopy);
    }

    /**
     * Creates a context from an existing EMC snapshot plus generated values already owned by this mod at
     * the start of the run.
     *
     * <p>Owned values go into {@code generatedValues}, not {@code knownValues}: they are still generated
     * values for this solve, so the solver may replace them when a new recipe cost is lower, and they are
     * not treated by {@link #hasSeedValue(ItemKey)} as seeds that always win. Drop/overflow protection from
     * old to new value is applied by the caller before writing.</p>
     *
     * @param knownValues             EMC provided by external sources before the run; may be empty
     * @param initialGeneratedValues  generated values owned by this mod before the run and allowed to update this run; may be empty
     */
    public SolveContext(Map<ItemKey, Long> knownValues,
                        Map<ItemKey, Long> initialGeneratedValues) {
        this(knownValues);
        Objects.requireNonNull(initialGeneratedValues, "initialGeneratedValues");
        for (Map.Entry<ItemKey, Long> entry : initialGeneratedValues.entrySet()) {
            ItemKey item = Objects.requireNonNull(
                    entry.getKey(), "initialGeneratedValues 的键不能为 null");
            Long value = Objects.requireNonNull(
                    entry.getValue(), "initialGeneratedValues 的值不能为 null");
            if (value > 0L) {
                generatedValues.put(item, value);
            }
        }
    }

    /**
     * @return an empty context with no existing EMC
     */
    public static SolveContext empty() {
        return new SolveContext(Map.of());
    }

    /**
     * Looks up EMC in "generated first, provisional next, known as fallback" order.
     *
     * @return a positive value; 0 when the item is unknown
     */
    @Override
    public long emcOf(ItemKey item) {
        if (item == null || deniedItems.contains(item)) {
            return 0L;
        }
        Long generated = generatedValues.get(item);
        if (generated != null) {
            return generated;
        }
        Long provisional = provisionalValues.get(item);
        if (provisional != null) {
            return provisional;
        }
        return knownValues.getOrDefault(item, 0L);
    }

    /**
     * @return {@code true} when the item already has a positive value (from the snapshot or this run)
     */
    @Override
    public boolean isKnown(ItemKey item) {
        return emcOf(item) > 0L;
    }

    /**
     * @return {@code true} when the item already had EMC at the start of the run; such items are never rewritten by the solver
     */
    public boolean hasSeedValue(ItemKey item) {
        return item != null && knownValues.containsKey(item);
    }

    /**
     * @return {@code true} when the safety layer forbids pricing the item; the solver must not publish its value
     */
    public boolean isDenied(ItemKey item) {
        return item != null && deniedItems.contains(item);
    }

    /**
     * @return read-only set of pricing-denied items, sorted ascending by {@link ItemKey}
     */
    public Set<ItemKey> deniedItems() {
        return deniedItems;
    }

    /**
     * @return the value generated this run, or {@code null} if none yet
     */
    @Nullable
    public Long generatedValue(ItemKey item) {
        return item == null ? null : generatedValues.get(item);
    }

    /**
     * @return the read-only EMC snapshot from the start of the run
     */
    public Map<ItemKey, Long> knownValues() {
        return knownValues;
    }

    /**
     * @return read-only view of values generated this run, sorted ascending by {@link ItemKey}
     */
    public Map<ItemKey, Long> generatedValues() {
        return Collections.unmodifiableMap(generatedValues);
    }

    /**
     * Pre-seeds a fallback value used only for input lookup.
     *
     * <p>Unlike {@code knownValues}, a provisional value does not make {@link #hasSeedValue(ItemKey)}
     * return {@code true}, so the solver may still publish a recipe value for the same item and override
     * it; it also does not appear in {@link #generatedValues()}, and final candidates are still produced
     * by the caller via the fallback pricing chain. This passes fresh fallback prices of owned leaf
     * entries into multi-level recipe chains in the same round.</p>
     *
     * @param item  item identity, must not be {@code null}
     * @param value fallback value, must be {@code > 0}
     */
    public void putProvisional(ItemKey item, long value) {
        Objects.requireNonNull(item, "item");
        if (value <= 0L) {
            throw new IllegalArgumentException("预置值必须 > 0，实际为 " + value);
        }
        provisionalValues.put(item, value);
    }

    /**
     * @return read-only view of provisional values, sorted ascending by {@link ItemKey}
     */
    public Map<ItemKey, Long> provisionalValues() {
        return Collections.unmodifiableMap(provisionalValues);
    }

    /**
     * @return read-only view of pricing traces for generated values, sorted ascending by {@link ItemKey}
     */
    public Map<ItemKey, SolveTrace> traces() {
        return Collections.unmodifiableMap(traces);
    }

    /**
     * Begins a new iteration pass. The solver calls this at the start of each pass so subsequent
     * {@link #putGenerated} records the correct pass.
     *
     * @param pass pass number, must be {@code >= 1}
     */
    public void enterPass(int pass) {
        if (pass < 1) {
            throw new IllegalArgumentException("轮次必须从 1 开始，实际为 " + pass);
        }
        this.currentPass = pass;
    }

    /**
     * @return the current pass number
     */
    public int currentPass() {
        return currentPass;
    }

    /**
     * Publishes a generated value, recording a trace with the current pass and empty alternative costs.
     *
     * <p>The solver internally uses the overload with pass and alternative costs; this convenience method
     * is for callers building test state or diagnostic snapshots.</p>
     *
     * @param item     the item being priced
     * @param value    unit EMC, must be {@code > 0}
     * @param recipeId the recipe that produced the value
     * @param source   the value source
     */
    public void putGenerated(ItemKey item, long value, ResourceLocation recipeId, PriceSource source) {
        putGenerated(item, value, recipeId, source, currentPass, java.util.List.of());
    }

    /**
     * Publishes a generated value and writes a full trace.
     *
     * <p>Only positive values are accepted: 0 or negative means "cannot pay" and would shadow existing
     * EMC if written, so it is rejected outright. Whether a lower old generated value may be overwritten
     * is decided by the solver via {@link SolveSettings#allowValueIncrease()}; this method only stores.</p>
     *
     * @param item             the item being priced
     * @param value            unit EMC, must be {@code > 0}
     * @param recipeId         the recipe that produced the value
     * @param source           the value source
     * @param pass             the writing pass, must be {@code >= 1}
     * @param alternativeCosts other valid costs in the same pass, may be empty
     */
    public void putGenerated(ItemKey item, long value, ResourceLocation recipeId, PriceSource source,
                             int pass, java.util.List<Long> alternativeCosts) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(recipeId, "recipeId");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(alternativeCosts, "alternativeCosts");
        if (value <= 0L) {
            throw new IllegalArgumentException("生成值必须 > 0，实际为 " + value);
        }
        if (pass < 1) {
            throw new IllegalArgumentException("轮次必须从 1 开始，实际为 " + pass);
        }
        if (deniedItems.contains(item)) {
            // Items excluded by the safety layer must not enter generated values, or a later pass would use them as downstream inputs.
            return;
        }
        generatedValues.put(item, value);
        traces.put(item, new SolveTrace(item, value, source, recipeId, pass, alternativeCosts));
    }
}
