package com.itzplana.sudoersemc.solver;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import net.minecraft.resources.ResourceLocation;

/**
 * 递归配方求解器：反复扫描配方，把已知 EMC 传播给尚未定价的物品，直到数值不再变化或达到轮数上限。
 *
 * <h2>轮次与快照语义</h2>
 * <p>每一轮开始时，所有配方都基于<strong>本轮开始前</strong>的状态求值，本轮产生的候选值在全部配方
 * 评估完成后才统一写入。因此下游配方只能看到上一轮的结果：{@code A → B → C} 这样的链条每轮前进
 * 一环，结果与配方提交顺序无关。配方始终按 id 的字符串形式升序评估，保证同输入必然得到同输出。</p>
 *
 * <h2>多配方竞争</h2>
 * <p>同一轮中，多条配方可能产出同一个物品。求解器收集该物品的全部有效正值成本，选择最低者；
 * 成本相同时选择 id 字典序最小的配方。其余不同成本写入 {@link SolveTrace#alternativeCosts()}，
 * 按升序排列并受 {@link SolveSettings#maxAlternativeCostsPerItem()} 限制。</p>
 *
 * <h2>不抬价规则（精确语义）</h2>
 * <ul>
 *   <li>运行开始前已有 EMC 的物品永远不发布新值，种子值完全胜出。</li>
 *   <li>已有种子值的物品：永远胜出，不发布生成值。</li>
 *   <li>{@link SolveContext#isDenied(ItemKey)} 为 {@code true} 的物品：不发布生成值，且其数值
 *       不会作为下游配方的输入。</li>
 *   <li>本次运行中尚未生成值的物品：直接发布本轮最低有效成本。</li>
 *   <li>本次运行中已经生成值的物品：更低成本总是替换旧值；相同成本不产生变化；
 *       更高成本仅在 {@link SolveSettings#allowValueIncrease()} 为 {@code true} 时替换，
 *       否则保留旧值。因此默认配置下本轮值只会下降或保持不变。</li>
 * </ul>
 *
 * <h2>零值与循环</h2>
 * <p>成本 {@code <= 0} 的配方不会产生可支付的值：该物品不会写入，若没有其它正值配方，则进入
 * {@link SolveResult#unresolvedItems()} 并让对应配方进入未解析列表。轮次结束后使用迭代式
 * Kosaraju 算法（无递归）在“未定价物品”依赖图上检测强连通分量，成环物品会写入
 * {@link SolveResult#cyclicItems()}，不会无限重试。</p>
 *
 * <p>求解器对单条配方抛出的 {@link RuntimeException} 做隔离：计数后继续处理其余配方，绝不因一条
 * 坏配方中断整轮求解。</p>
 *
 * <p>{@link SolveContext} 表示一次运行的工作状态，调用方应为每次求解创建新实例；求解器不会清理
 * 其中已有的生成值。</p>
 */
public final class RecipeSolver {

    private final EvaluationPolicy basePolicy;

    /**
     * 使用 {@link EvaluationPolicy#defaults()} 作为基础策略创建求解器。
     */
    public RecipeSolver() {
        this(EvaluationPolicy.defaults());
    }

    /**
     * 使用自定义基础策略创建求解器。
     *
     * <p>基础策略控制是否抵扣容器残留、是否给副产物定价；{@link SolveSettings#minimumEmc()} 会在每次
     * 求解时覆盖基础策略中的最小值。多输出配方在 {@code priceSecondaryOutputs == true} 时会把同一个
     * 单位值发布给所有输出物品，否则只发布主输出。</p>
     *
     * @param basePolicy 基础评估策略，不能为 {@code null}
     */
    public RecipeSolver(EvaluationPolicy basePolicy) {
        this.basePolicy = Objects.requireNonNull(basePolicy, "basePolicy");
    }

    /**
     * 执行一次完整的配方求解。
     *
     * <p>方法本身不修改传入的配方列表；所有写入都发生在 {@code context} 中。返回结果中的值、轨迹和
     * 诊断集合都是不可变副本。</p>
     *
     * @param recipes  待评估配方；允许为空，{@code null} 元素会被忽略
     * @param context  本次运行的工作状态，已有 EMC 从这里读取，生成值写入这里
     * @param registry 评估器注册表
     * @param settings 收敛与诊断参数
     * @return 不可变的求解结果；即使所有配方都失败也会正常返回
     */
    public SolveResult solve(List<RecipeSnapshot> recipes, SolveContext context,
                             EvaluatorRegistry registry, SolveSettings settings) {
        Objects.requireNonNull(recipes, "recipes");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(settings, "settings");

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

        // 整个运行期间观察到的未解析输入，最终会过滤掉后来被定价的物品。
        Set<ItemKey> observedUnresolvedInputs = new TreeSet<>();
        // 有效但成本为 0 的输出，只有最终仍无正值时才进入未解析诊断。
        Set<ItemKey> zeroCostItems = new TreeSet<>();
        // 最后一轮的“配方 -> 未解析输入”，供循环检测使用。
        Map<ResourceLocation, List<ItemKey>> lastUnresolvedInputs = new LinkedHashMap<>();

        for (int pass = 1; pass <= settings.maxPasses(); pass++) {
            context.enterPass(pass);
            passesUsed = pass;
            boolean valueChanged = false;
            NavigableMap<ItemKey, List<CostCandidate>> candidatesByItem = new TreeMap<>();
            lastUnresolvedInputs.clear();

            for (RecipeSnapshot recipe : orderedRecipes) {
                recipesEvaluated++;
                RecipeEvaluation evaluation;
                try {
                    evaluation = registry.evaluate(recipe, context, policy);
                } catch (RuntimeException exception) {
                    // 评估器已经做了同样的隔离，这里再兜底一次，保证求解循环绝不中断。
                    evaluation = RecipeEvaluation.invalid(
                            "solver-evaluator-error:" + exception.getClass().getSimpleName());
                }
                if (evaluation == null) {
                    evaluation = RecipeEvaluation.invalid("evaluator-returned-null");
                }

                if (evaluation.valid()) {
                    long unitValue = evaluation.outputUnitEmc();
                    List<ItemKey> targets = publicationTargets(recipe, policy);
                    if (unitValue > 0L) {
                        for (ItemKey target : targets) {
                            candidatesByItem.computeIfAbsent(target, key -> new ArrayList<>())
                                    .add(new CostCandidate(unitValue, recipe.id()));
                        }
                    } else {
                        // 免费配方不产生可支付的值，先记录，最终仍无正值时再报告。
                        zeroCostItems.addAll(targets);
                    }
                } else if (!evaluation.unresolvedInputs().isEmpty()) {
                    List<ItemKey> unresolved = List.copyOf(evaluation.unresolvedInputs());
                    lastUnresolvedInputs.put(recipe.id(), unresolved);
                    observedUnresolvedInputs.addAll(unresolved);
                }
            }

            // 本轮所有配方都评估完成后统一写入，确保同一轮内互不可见。
            for (Map.Entry<ItemKey, List<CostCandidate>> entry : candidatesByItem.entrySet()) {
                ItemKey item = entry.getKey();
                if (context.hasSeedValue(item)) {
                    // 已有 EMC 永远胜出，连轨迹都不生成。
                    continue;
                }
                if (context.isDenied(item)) {
                    // 安全层禁止定价的物品不发布，也不会成为下游配方的输入。
                    continue;
                }
                List<CostCandidate> candidates = entry.getValue();
                candidates.sort(Comparator
                        .comparingLong(CostCandidate::cost)
                        .thenComparing(candidate -> candidate.recipeId().toString()));
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
                // 完整的一轮没有任何数值变化，说明已经到达不动点。
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

        // 迭代式循环检测：只分析最终仍未定价的物品，单独记录真正成环的物品。
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
     * 计算一条配方需要发布值的输出物品。
     *
     * <p>默认策略只给主输出定价；显式开启副产物定价时，所有不同输出物品都会收到同一个单位值。</p>
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
     * 收集除最低成本以外的不同有效成本，升序截断到配置上限。
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
     * 构建“输出物品 -> 无法满足的输入物品”的依赖图。只保留最终仍未定价的节点。
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
     * 使用迭代式 Kosaraju 算法找出所有处于循环中的物品，避免递归深度受配方链长度影响。
     *
     * @param nodes     参与分析的物品（最终仍未定价）
     * @param adjacency 输出物品到阻塞输入的边
     * @return 位于大小大于 1 的强连通分量中，或存在自依赖的物品
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

        // 第一次遍历：按完成顺序记录节点。
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

        // 第二次遍历：在反向图上收集强连通分量。
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
     * 读取依赖边，缺失节点视为没有出边。
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
     * 一轮内某条配方对某个输出物品给出的单位成本。
     */
    private record CostCandidate(long cost, ResourceLocation recipeId) {
    }
}
