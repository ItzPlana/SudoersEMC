package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import net.minecraft.resources.ResourceLocation;

/**
 * Iterative recipe solver: repeatedly scans recipes, propagating known EMC to unpriced items until
 * values stop changing or the pass limit is reached.
 *
 * <h2>Pass and snapshot semantics</h2>
 * <p>Within a pass every recipe is evaluated against the state <strong>as of the start of that
 * pass</strong>; candidates produced during the pass are published only after all recipes have been
 * evaluated. Downstream recipes therefore only see the previous pass's results: a chain
 * {@code A → B → C} advances one link per pass, independent of recipe submission order. Recipes are
 * always evaluated in ascending order of their id's string form, so identical input yields identical
 * output.</p>
 *
 * <h2>Competing recipes</h2>
 * <p>Several recipes may produce the same item in one pass. The solver collects every valid positive
 * cost for that item and always picks the lowest; ties are broken by
 * {@link SolveSettings#selectionStrategy()} (default: lexicographic id; optionally prefer simpler
 * recipes or an explicit stable id). The remaining distinct costs are recorded in
 * {@link SolveTrace#alternativeCosts()}, sorted ascending and capped by
 * {@link SolveSettings#maxAlternativeCostsPerItem()}.</p>
 *
 * <h2>Evaluation cache</h2>
 * <p>A single recipe's evaluation is cached by "recipe content + evaluation policy + input-value
 * snapshot + evaluator registry". The cache is a bounded LRU with default capacity
 * {@value #DEFAULT_EVALUATION_CACHE_CAPACITY}; changed input values naturally produce a new key, so
 * reuse across passes and across scans never returns stale results and never grows unbounded.</p>
 *
 * <h2>No-price-increase rule (exact semantics)</h2>
 * <ul>
 *   <li>Items that already had EMC before the run never receive a new value; the seed wins outright.</li>
 *   <li>Items with a seed value: always win, no generated value is published.</li>
 *   <li>Items for which {@link SolveContext#isDenied(ItemKey)} is {@code true}: no generated value is
 *       published, and their value is never used as a downstream recipe input.</li>
 *   <li>Items with no generated value yet this run: publish this pass's lowest valid cost directly.</li>
 *   <li>Items already generated this run: a lower cost always replaces the old value; an equal cost
 *       changes nothing; a higher cost replaces only when
 *       {@link SolveSettings#allowValueIncrease()} is {@code true}, otherwise the old value is kept.
 *       Under the default configuration a value can only drop or stay the same within a run.</li>
 * </ul>
 *
 * <h2>Zero values and cycles</h2>
 * <p>A recipe whose cost is {@code <= 0} produces no payable value: the item is not written, and if no
 * other positive-cost recipe exists it enters {@link SolveResult#unresolvedItems()} and the recipe
 * enters the unresolved list. After the passes, an iterative (non-recursive) Kosaraju algorithm
 * detects strongly connected components in the "unpriced item" dependency graph; items on a cycle are
 * written to {@link SolveResult#cyclicItems()} rather than retried forever.</p>
 *
 * <p>A {@link RuntimeException} thrown by a single recipe is isolated: it is counted and the remaining
 * recipes continue; one bad recipe never aborts the whole solve.</p>
 *
 * <p>{@link SolveContext} is the working state of one run; callers should create a fresh instance per
 * solve. The solver does not clear generated values already present in it.</p>
 */
public final class RecipeSolver {

    /**
     * Default cache capacity for a single solver instance.
     *
     * <p>The cache is a bounded LRU backed by an access-ordered {@link LinkedHashMap}; once capacity is
     * exceeded the least-recently-used entry is evicted. The key includes recipe content, evaluation
     * policy, evaluator registry and the input-value snapshot, so input changes invalidate naturally;
     * the bound guarantees reuse across scans cannot grow without limit. 8192 covers a typical
     * modpack's single-pass recipe graph.</p>
     */
    public static final int DEFAULT_EVALUATION_CACHE_CAPACITY = 8_192;

    /** How many recipes are processed between cooperative cancellation checks. */
    static final int CANCEL_CHECK_INTERVAL = 64;

    private final EvaluationPolicy basePolicy;
    private final int evaluationCacheCapacity;
    private final Map<EvaluationCacheKey, RecipeEvaluation> evaluationCache;
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong cacheMisses = new AtomicLong();

    /**
     * Creates a solver using {@link EvaluationPolicy#defaults()} as the base policy.
     */
    public RecipeSolver() {
        this(EvaluationPolicy.defaults(), DEFAULT_EVALUATION_CACHE_CAPACITY);
    }

    /**
     * Creates a solver with a custom base policy.
     *
     * <p>The base policy controls whether container remainders are credited and whether byproducts are
     * priced; {@link SolveSettings#minimumEmc()} overrides the policy's minimum on every solve. With
     * {@code priceSecondaryOutputs == true} a multi-output recipe publishes the same unit value to every
     * output item; otherwise only the primary output is published.</p>
     *
     * @param basePolicy base evaluation policy, must not be {@code null}
     */
    public RecipeSolver(EvaluationPolicy basePolicy) {
        this(basePolicy, DEFAULT_EVALUATION_CACHE_CAPACITY);
    }

    /**
     * Test/embedded constructor: allows specifying the evaluation cache capacity.
     *
     * @param basePolicy           base evaluation policy, must not be {@code null}
     * @param evaluationCacheCapacity cache capacity, must be {@code >= 0}; 0 disables the cache
     */
    public RecipeSolver(EvaluationPolicy basePolicy, int evaluationCacheCapacity) {
        this.basePolicy = Objects.requireNonNull(basePolicy, "basePolicy");
        if (evaluationCacheCapacity < 0) {
            throw new IllegalArgumentException(
                    "evaluationCacheCapacity 必须 >= 0，实际为 " + evaluationCacheCapacity);
        }
        this.evaluationCacheCapacity = evaluationCacheCapacity;
        this.evaluationCache = Collections.synchronizedMap(
                new LinkedHashMap<>(16, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<EvaluationCacheKey, RecipeEvaluation> eldest) {
                        return size() > RecipeSolver.this.evaluationCacheCapacity;
                    }
                });
    }

    /**
     * @return cumulative cache hits (accumulated across solve calls)
     */
    public long cacheHits() {
        return cacheHits.get();
    }

    /**
     * @return cumulative cache misses (accumulated across solve calls)
     */
    public long cacheMisses() {
        return cacheMisses.get();
    }

    /**
     * @return current number of cached entries, never above the capacity given at construction
     */
    public int cacheSize() {
        synchronized (evaluationCache) {
            return evaluationCache.size();
        }
    }

    /**
     * Clears the evaluation cache. Hit/miss counters are not reset, so callers can compute per-scan
     * hit rates.
     */
    public void clearEvaluationCache() {
        synchronized (evaluationCache) {
            evaluationCache.clear();
        }
    }

    /**
     * Runs a complete recipe solve.
     *
     * <p>This method does not modify the supplied recipe list; all writes go into {@code context}. The
     * values, traces and diagnostic sets in the returned result are immutable copies.</p>
     *
     * @param recipes  recipes to evaluate; may be empty, {@code null} elements are ignored
     * @param context  working state for this run; existing EMC is read from it and generated values written to it
     * @param registry evaluator registry
     * @param settings convergence and diagnostic parameters
     * @return immutable solve result; returns normally even if every recipe fails
     */
    public SolveResult solve(List<RecipeSnapshot> recipes, SolveContext context,
                             EvaluatorRegistry registry, SolveSettings settings) {
        return solve(recipes, context, registry, settings, () -> false);
    }

    /**
     * Solve entry point with a cooperative cancellation check.
     *
     * <p>The check runs every {@value #CANCEL_CHECK_INTERVAL} recipes, far cheaper than a single recipe
     * evaluation. On cancellation the remaining recipes of the current pass are skipped and the partial
     * result produced so far is returned; callers should discard that result (the pipeline will not write
     * it to the store) and use it only for a partial diagnostic report.</p>
     *
     * @param recipes      recipes to evaluate; may be empty, {@code null} elements are ignored
     * @param context      working state for this run
     * @param registry     evaluator registry
     * @param settings     convergence and diagnostic parameters
     * @param cancellation cancellation check; stop as soon as it returns {@code true}
     * @return immutable solve result; on cancellation {@code converged=false} and only the processed part is included
     */
    public SolveResult solve(List<RecipeSnapshot> recipes, SolveContext context,
                             EvaluatorRegistry registry, SolveSettings settings,
                             BooleanSupplier cancellation) {
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(cancellation, "cancellation");

        long startedAtNanos = System.nanoTime();
        EvaluationPolicy policy = basePolicy.withMinimumEmc(settings.minimumEmc());
        List<RecipeSnapshot> orderedRecipes = recipes.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing((RecipeSnapshot recipe) -> recipe.id().toString()))
                .toList();

        if (orderedRecipes.isEmpty()) {
            return new SolveResult(context.generatedValues(), context.traces(), List.of(), Set.of(),
                    0, true, elapsedMillis(startedAtNanos), 0, Set.of());
        }

        int recipesEvaluated = 0;
        int passesUsed = 0;
        boolean converged = false;
        boolean cancelled = false;

        // Unresolved inputs observed across the whole run; items priced later are filtered out at the end.
        Set<ItemKey> observedUnresolvedInputs = new TreeSet<>();
        // Valid but zero-cost outputs; only reported as unresolved if they still have no positive value at the end.
        Set<ItemKey> zeroCostItems = new TreeSet<>();
        // Last pass's "recipe -> unresolved inputs" map, used for cycle detection.
        Map<ResourceLocation, List<ItemKey>> lastUnresolvedInputs = new LinkedHashMap<>();

        for (int pass = 1; pass <= settings.maxPasses(); pass++) {
            if (cancellation.getAsBoolean()) {
                cancelled = true;
                break;
            }
            context.enterPass(pass);
            passesUsed = pass;
            boolean valueChanged = false;
            NavigableMap<ItemKey, List<CostCandidate>> candidatesByItem = new TreeMap<>();
            lastUnresolvedInputs.clear();

            int processedInPass = 0;
            for (RecipeSnapshot recipe : orderedRecipes) {
                if (processedInPass % CANCEL_CHECK_INTERVAL == 0 && cancellation.getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                processedInPass++;
                recipesEvaluated++;
                EvaluationCacheKey cacheKey = new EvaluationCacheKey(
                        recipe, policy, inputValueSnapshot(recipe, context), registry);
                RecipeEvaluation evaluation = cacheGet(cacheKey);
                if (evaluation != null) {
                    cacheHits.incrementAndGet();
                } else {
                    cacheMisses.incrementAndGet();
                    try {
                        evaluation = registry.evaluate(recipe, context, policy);
                    } catch (RuntimeException exception) {
                        // The registry already isolates evaluator failures; this is a second safety net so the solve loop never aborts.
                        evaluation = RecipeEvaluation.invalid(
                                "solver-evaluator-error:" + exception.getClass().getSimpleName());
                    }
                    if (evaluation == null) {
                        evaluation = RecipeEvaluation.invalid("evaluator-returned-null");
                    }
                    cachePut(cacheKey, evaluation);
                }

                if (evaluation.valid()) {
                    long unitValue = evaluation.outputUnitEmc();
                    List<ItemKey> targets = publicationTargets(recipe, policy);
                    if (unitValue > 0L) {
                        for (ItemKey target : targets) {
                            candidatesByItem.computeIfAbsent(target, key -> new ArrayList<>())
                                    .add(new CostCandidate(unitValue, recipe.id(), recipe));
                        }
                    } else {
                        // A free recipe yields no payable value; record it and only report if no positive value appears by the end.
                        zeroCostItems.addAll(targets);
                    }
                } else if (!evaluation.unresolvedInputs().isEmpty()) {
                    List<ItemKey> unresolved = List.copyOf(evaluation.unresolvedInputs());
                    lastUnresolvedInputs.put(recipe.id(), unresolved);
                    observedUnresolvedInputs.addAll(unresolved);
                }
            }

            if (cancelled) {
                // Do not publish a partially evaluated pass; a half-evaluated recipe graph is not a valid intermediate state.
                break;
            }

            // Publish only after every recipe in this pass is evaluated, so results are invisible within the same pass.
            Comparator<CostCandidate> candidateOrder = candidateOrder(settings.selectionStrategy());
            for (Map.Entry<ItemKey, List<CostCandidate>> entry : candidatesByItem.entrySet()) {
                ItemKey item = entry.getKey();
                if (context.hasSeedValue(item)) {
                    // A pre-existing EMC seed always wins; not even a trace is generated.
                    continue;
                }
                if (context.isDenied(item)) {
                    // Safety-denied items are never published and never become downstream recipe inputs.
                    continue;
                }
                List<CostCandidate> candidates = entry.getValue();
                candidates.sort(candidateOrder);
                long bestCost = candidates.get(0).cost();
                Long current = context.generatedValue(item);
                boolean publish;
                if (current == null || bestCost < current) {
                    publish = true;
                } else if (bestCost > current) {
                    publish = settings.allowValueIncrease();
                } else {
                    publish = false;
                }
                if (publish) {
                    context.putGenerated(item, bestCost, candidates.get(0).recipeId(), PriceSource.RECIPE,
                            pass, alternativeCosts(candidates, settings));
                    valueChanged = true;
                }
            }

            if (!valueChanged) {
                // A full pass with no value change means a fixed point has been reached.
                converged = true;
                break;
            }
        }

        Set<ItemKey> unresolvedItems = new TreeSet<>();
        for (ItemKey item : observedUnresolvedInputs) {
            if (!context.isKnown(item)) {
                unresolvedItems.add(item);
            }
        }
        for (ItemKey item : zeroCostItems) {
            if (!context.isKnown(item)) {
                unresolvedItems.add(item);
            }
        }

        NavigableSet<ResourceLocation> unresolvedRecipeIds =
                new TreeSet<>(Comparator.comparing(ResourceLocation::toString));
        for (RecipeSnapshot recipe : orderedRecipes) {
            List<ItemKey> targets = publicationTargets(recipe, policy);
            boolean unresolved = targets.isEmpty();
            for (ItemKey target : targets) {
                if (!context.isKnown(target)) {
                    unresolved = true;
                    unresolvedItems.add(target);
                }
            }
            if (unresolved) {
                unresolvedRecipeIds.add(recipe.id());
            }
        }

        // Iterative cycle detection: only analyse items still unpriced at the end, recording those truly on a cycle.
        NavigableMap<ItemKey, NavigableSet<ItemKey>> dependencyGraph =
                buildDependencyGraph(orderedRecipes, policy, context, lastUnresolvedInputs);
        Set<ItemKey> cyclicItems = findCyclicItems(unresolvedItems, dependencyGraph);

        return new SolveResult(
                context.generatedValues(),
                context.traces(),
                List.copyOf(unresolvedRecipeIds),
                unresolvedItems,
                passesUsed,
                converged,
                elapsedMillis(startedAtNanos),
                recipesEvaluated,
                cyclicItems);
    }

    /**
     * Reads the evaluation cache; a capacity of 0 is always treated as a miss.
     */
    private RecipeEvaluation cacheGet(EvaluationCacheKey key) {
        if (evaluationCacheCapacity == 0) {
            return null;
        }
        synchronized (evaluationCache) {
            return evaluationCache.get(key);
        }
    }

    /**
     * Writes the evaluation cache; a capacity of 0 discards the entry. The access-ordered LRU is
     * maintained by {@code removeEldestEntry} from construction, so reuse across scans cannot grow unbounded.
     */
    private void cachePut(EvaluationCacheKey key, RecipeEvaluation evaluation) {
        if (evaluationCacheCapacity == 0) {
            return;
        }
        synchronized (evaluationCache) {
            evaluationCache.put(key, evaluation);
        }
    }

    /**
     * Builds a stable snapshot of "every input value an evaluator might read for this recipe".
     *
     * <p>Keys cover all input-slot candidates and container-remainder items; values come from the current
     * {@link SolveContext} (generated values first). Any input-value change alters the cache key and thus
     * invalidates naturally; unresolved candidates participate as {@code 0}.</p>
     */
    private static List<String> inputValueSnapshot(RecipeSnapshot recipe, SolveContext context) {
        NavigableMap<String, Long> values = new TreeMap<>();
        for (RecipeInput input : recipe.inputs()) {
            for (ItemKey candidate : input.option().candidates()) {
                values.put(candidate.toString(), context.emcOf(candidate));
            }
        }
        for (RecipeOutput remainder : recipe.containerRemainders()) {
            values.put("remainder:" + remainder.item(), context.emcOf(remainder.item()));
        }
        List<String> snapshot = new ArrayList<>(values.size());
        values.forEach((key, value) -> snapshot.add(key + "=" + value));
        return List.copyOf(snapshot);
    }

    /**
     * Builds the candidate ordering for a selection strategy. Every strategy uses cost as the primary
     * key, never sacrificing the lowest-cost semantics.
     */
    private static Comparator<CostCandidate> candidateOrder(RecipeSelectionStrategy strategy) {
        Comparator<CostCandidate> byId =
                Comparator.comparing((CostCandidate candidate) -> candidate.recipeId().toString());
        Comparator<CostCandidate> byCost = Comparator.comparingLong(CostCandidate::cost);
        if (strategy != RecipeSelectionStrategy.PREFER_SIMPLE) {
            return byCost.thenComparing(byId);
        }
        return byCost
                .thenComparingInt(candidate -> candidate.recipe().inputs().size())
                .thenComparingInt(candidate -> candidate.recipe().outputs().size())
                .thenComparingInt(candidate -> candidate.recipe().containerRemainders().size())
                .thenComparingInt(candidate -> candidate.recipe().catalyst() ? 1 : 0)
                .thenComparing(byId);
    }

    /**
     * Computes the output items a recipe must publish a value for.
     *
     * <p>The default policy prices only the primary output; when byproduct pricing is explicitly enabled,
     * every distinct output item receives the same unit value.</p>
     */
    public static List<ItemKey> publicationTargets(RecipeSnapshot recipe, EvaluationPolicy policy) {
        Objects.requireNonNull(recipe, "recipe");
        Objects.requireNonNull(policy, "policy");
        if (!recipe.hasOutputs()) {
            return List.of();
        }
        if (!policy.priceSecondaryOutputs()) {
            return List.of(recipe.outputs().get(0).item());
        }
        NavigableSet<ItemKey> uniqueTargets = new TreeSet<>();
        for (RecipeOutput output : recipe.outputs()) {
            uniqueTargets.add(output.item());
        }
        return List.copyOf(uniqueTargets);
    }

    /**
     * Collects the distinct valid costs other than the lowest, sorted ascending and truncated to the
     * configured limit.
     */
    private static List<Long> alternativeCosts(List<CostCandidate> sortedCandidates,
                                               SolveSettings settings) {
        int limit = settings.maxAlternativeCostsPerItem();
        if (limit == 0 || sortedCandidates.size() <= 1) {
            return List.of();
        }
        long bestCost = sortedCandidates.get(0).cost();
        NavigableSet<Long> distinct = new TreeSet<>();
        for (CostCandidate candidate : sortedCandidates) {
            if (candidate.cost() != bestCost) {
                distinct.add(candidate.cost());
            }
        }
        List<Long> alternatives = new ArrayList<>(Math.min(limit, distinct.size()));
        for (Long cost : distinct) {
            if (alternatives.size() >= limit) {
                break;
            }
            alternatives.add(cost);
        }
        return List.copyOf(alternatives);
    }

    /**
     * Builds the "output item -> unsatisfiable input items" dependency graph, keeping only nodes still
     * unpriced at the end.
     */
    private static NavigableMap<ItemKey, NavigableSet<ItemKey>> buildDependencyGraph(
            List<RecipeSnapshot> recipes,
            EvaluationPolicy policy,
            SolveContext context,
            Map<ResourceLocation, List<ItemKey>> lastUnresolvedInputs) {
        NavigableMap<ItemKey, NavigableSet<ItemKey>> graph = new TreeMap<>();
        for (RecipeSnapshot recipe : recipes) {
            List<ItemKey> unresolvedInputs = lastUnresolvedInputs.get(recipe.id());
            if (unresolvedInputs == null || unresolvedInputs.isEmpty()) {
                continue;
            }
            for (ItemKey target : publicationTargets(recipe, policy)) {
                if (context.isKnown(target)) {
                    continue;
                }
                NavigableSet<ItemKey> edges =
                        graph.computeIfAbsent(target, key -> new TreeSet<>());
                for (ItemKey input : unresolvedInputs) {
                    if (!context.isKnown(input)) {
                        edges.add(input);
                        graph.computeIfAbsent(input, key -> new TreeSet<>());
                    }
                }
            }
        }
        return graph;
    }

    /**
     * Finds every item on a cycle using an iterative Kosaraju algorithm, so recursion depth is not
     * bounded by recipe-chain length.
     *
     * @param nodes     items to analyse (still unpriced at the end)
     * @param adjacency edges from output items to their blocking inputs
     * @return items in a strongly connected component of size greater than 1, or with a self-dependency
     */
    private static Set<ItemKey> findCyclicItems(
            Set<ItemKey> nodes, Map<ItemKey, ? extends Set<ItemKey>> adjacency) {
        if (nodes.isEmpty()) {
            return Set.of();
        }

        Map<ItemKey, Set<ItemKey>> reverse = new TreeMap<>();
        for (ItemKey node : nodes) {
            reverse.put(node, new TreeSet<>());
        }
        for (ItemKey node : nodes) {
            for (ItemKey dependency : dependenciesOf(adjacency, node)) {
                if (nodes.contains(dependency)) {
                    reverse.get(dependency).add(node);
                }
            }
        }

        // First traversal: record nodes in finish order.
        List<ItemKey> finishOrder = new ArrayList<>(nodes.size());
        Set<ItemKey> visited = new HashSet<>();
        for (ItemKey start : nodes) {
            if (!visited.add(start)) {
                continue;
            }
            Deque<ItemKey> nodeStack = new ArrayDeque<>();
            Deque<Iterator<ItemKey>> iteratorStack = new ArrayDeque<>();
            nodeStack.push(start);
            iteratorStack.push(dependenciesOf(adjacency, start).iterator());
            while (!nodeStack.isEmpty()) {
                Iterator<ItemKey> iterator = iteratorStack.peek();
                if (iterator.hasNext()) {
                    ItemKey next = iterator.next();
                    if (nodes.contains(next) && visited.add(next)) {
                        nodeStack.push(next);
                        iteratorStack.push(dependenciesOf(adjacency, next).iterator());
                    }
                } else {
                    finishOrder.add(nodeStack.pop());
                    iteratorStack.pop();
                }
            }
        }

        // Second traversal: collect strongly connected components on the reversed graph.
        Set<ItemKey> cyclic = new TreeSet<>();
        Set<ItemKey> assigned = new HashSet<>();
        for (int index = finishOrder.size() - 1; index >= 0; index--) {
            ItemKey start = finishOrder.get(index);
            if (!assigned.add(start)) {
                continue;
            }
            List<ItemKey> component = new ArrayList<>();
            Deque<ItemKey> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                ItemKey node = stack.pop();
                component.add(node);
                for (ItemKey predecessor : reverse.getOrDefault(node, Set.of())) {
                    if (assigned.add(predecessor)) {
                        stack.push(predecessor);
                    }
                }
            }
            boolean selfLoop = component.size() == 1
                    && dependenciesOf(adjacency, start).contains(start);
            if (component.size() > 1 || selfLoop) {
                cyclic.addAll(component);
            }
        }
        return cyclic;
    }
    /**
     * Reads dependency edges; a missing node is treated as having no outgoing edges.
     */
    private static Set<ItemKey> dependenciesOf(
            Map<ItemKey, ? extends Set<ItemKey>> adjacency, ItemKey node) {
        Set<ItemKey> dependencies = adjacency.get(node);
        return dependencies == null ? Set.of() : dependencies;
    }

    private static long elapsedMillis(long startedAtNanos) {
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    /**
     * The unit cost one recipe gives for one output item within a pass.
     *
     * <p>{@code recipe} is only used to compare recipe complexity under the selection strategy; it takes
     * no part in the economic calculation.</p>
     */
    private record CostCandidate(long cost, ResourceLocation recipeId, RecipeSnapshot recipe) {
    }

    /**
     * Evaluation cache key.
     *
     * <p>The recipe record is compared by value (id, kind, inputs, outputs, remainders, catalyst and
     * attributes), together with the evaluation policy, the input-value snapshot and the evaluator
     * registry identity, so any input change that affects a single recipe's evaluation causes a miss.
     * The registry is compared by object identity to avoid wrongly reusing results across different
     * evaluator combinations.</p>
     */
    private record EvaluationCacheKey(
            RecipeSnapshot recipe,
            EvaluationPolicy policy,
            List<String> inputValues,
            EvaluatorRegistry registry) {
    }
}
