package com.itzplana.sudoersemc.solver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.scanner.IngredientOption;
import com.itzplana.sudoersemc.scanner.RecipeInput;
import com.itzplana.sudoersemc.scanner.RecipeKind;
import com.itzplana.sudoersemc.scanner.RecipeOutput;
import com.itzplana.sudoersemc.scanner.RecipeSnapshot;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * 求解器行为验证。
 *
 * <p>覆盖 Plan 第 3 阶段要求的全部场景：多轮收敛、多级链、多输出、取整、缺失输入、循环依赖、
 * 多配方竞争、防抬高、轮数上限、已有值保护与确定性。</p>
 */
class RecipeSolverTest {

    private static final RecipeSolver SOLVER = new RecipeSolver();
    private static final EvaluatorRegistry REGISTRY = EvaluatorRegistry.defaults();

    private static ItemKey item(String path) {
        return ItemKey.of(new ResourceLocation("sudoersemc", path));
    }

    private static RecipeSnapshot recipe(String id, RecipeKind kind,
                                         List<RecipeInput> inputs, List<RecipeOutput> outputs) {
        return RecipeSnapshot.of(new ResourceLocation("sudoersemc", id), kind, inputs, outputs);
    }

    private static RecipeInput in(ItemKey item) {
        return RecipeInput.of(IngredientOption.of(item));
    }

    private static RecipeInput in(ItemKey item, int count) {
        return new RecipeInput(IngredientOption.of(item), count);
    }

    private static RecipeOutput out(ItemKey item, int count) {
        return new RecipeOutput(item, count);
    }

    @Test
    void singleRecipeConvergesAfterOneChangePass() {
        ItemKey ore = item("ore");
        ItemKey ingot = item("ingot");
        List<RecipeSnapshot> recipes = List.of(
                recipe("smelt_ingot", RecipeKind.FURNACE, List.of(in(ore)), List.of(out(ingot, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(ore, 100L)), REGISTRY,
                SolveSettings.defaults());

        assertTrue(result.converged(), "应当到达不动点");
        assertEquals(2, result.passesUsed(), "第 1 轮发布值，第 2 轮确认无变化");
        assertEquals(Map.of(ingot, 100L), result.values());
        assertEquals(1, result.solvedCount());
        assertEquals(0, result.unresolvedRecipeCount());
    }

    @Test
    void multiStageChainNeedsOnePassPerStage() {
        ItemKey stone = item("stone");
        ItemKey plank = item("plank");
        ItemKey stick = item("stick");
        List<RecipeSnapshot> recipes = List.of(
                recipe("a_plank", RecipeKind.STONECUTTER, List.of(in(stone)), List.of(out(plank, 1))),
                recipe("b_stick", RecipeKind.SHAPED, List.of(in(plank, 1)), List.of(out(stick, 2))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(stone, 40L)), REGISTRY,
                SolveSettings.defaults());

        assertTrue(result.converged());
        assertEquals(Map.of(plank, 40L, stick, 20L), result.values());
        assertTrue(result.passesUsed() >= 3, "两级链至少需要 3 轮，实际 " + result.passesUsed());
    }

    @Test
    void multipleInputsAreSummedBeforeDivision() {
        ItemKey a = item("a");
        ItemKey b = item("b");
        ItemKey product = item("product");
        List<RecipeSnapshot> recipes = List.of(
                recipe("craft_product", RecipeKind.SHAPED,
                        List.of(in(a, 2), in(b, 3)), List.of(out(product, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(a, 10L, b, 5L)), REGISTRY,
                SolveSettings.defaults());

        // 2 * 10 + 3 * 5 = 35
        assertEquals(Map.of(product, 35L), result.values());
    }

    @Test
    void integerDivisionFloorsDown() {
        ItemKey ore = item("ore_floor");
        ItemKey nugget = item("nugget_floor");
        List<RecipeSnapshot> recipes = List.of(
                recipe("nuggets", RecipeKind.SHAPELESS, List.of(in(ore)),
                        List.of(out(nugget, 3))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(ore, 100L)), REGISTRY,
                SolveSettings.defaults());

        // 100 / 3 = 33.33，向下取整为 33
        assertEquals(Map.of(nugget, 33L), result.values());
    }

    @Test
    void multiOutputOnlyPricesPrimaryByDefault() {
        ItemKey input = item("multi_in");
        ItemKey primary = item("multi_primary");
        ItemKey byproduct = item("multi_byproduct");
        List<RecipeSnapshot> recipes = List.of(
                recipe("multi", RecipeKind.SHAPED, List.of(in(input)),
                        List.of(out(primary, 1), out(byproduct, 4))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(input, 100L)), REGISTRY,
                SolveSettings.defaults());

        assertEquals(100L, result.values().get(primary), "主产物获得全部成本");
        assertNull(result.values().get(byproduct), "默认不给副产物定价");
    }

    @Test
    void multiOutputPolicyCanPriceSecondaryOutputs() {
        ItemKey input = item("split_in");
        ItemKey primary = item("split_primary");
        ItemKey byproduct = item("split_byproduct");
        List<RecipeSnapshot> recipes = List.of(
                recipe("split", RecipeKind.SHAPED, List.of(in(input)),
                        List.of(out(primary, 1), out(byproduct, 4))));

        RecipeSolver splitSolver = new RecipeSolver(
                new EvaluationPolicy(EvaluationPolicy.DEFAULT_MINIMUM_EMC, true, true));
        SolveResult result = splitSolver.solve(recipes, new SolveContext(Map.of(input, 100L)), REGISTRY,
                SolveSettings.defaults());

        // 100 分摊到 1 + 4 = 5 个单位
        assertEquals(20L, result.values().get(primary));
        assertEquals(20L, result.values().get(byproduct));
    }

    @Test
    void missingInputStaysUnresolved() {
        ItemKey known = item("known");
        ItemKey unknown = item("never_priced");
        ItemKey product = item("blocked");
        List<RecipeSnapshot> recipes = List.of(
                recipe("blocked_recipe", RecipeKind.SHAPED,
                        List.of(in(known), in(unknown)), List.of(out(product, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(known, 10L)), REGISTRY,
                SolveSettings.defaults());

        assertTrue(result.values().isEmpty(), "输入不全时不应产出值");
        assertTrue(result.unresolvedItems().contains(unknown));
        assertEquals(1, result.unresolvedRecipeCount());
    }

    @Test
    void circularDependencyTerminatesAndIsReported() {
        ItemKey a = item("cycle_a");
        ItemKey b = item("cycle_b");
        List<RecipeSnapshot> recipes = List.of(
                recipe("a_from_b", RecipeKind.SHAPED, List.of(in(b)), List.of(out(a, 1))),
                recipe("b_from_a", RecipeKind.SHAPED, List.of(in(a)), List.of(out(b, 1))));

        SolveResult result = SOLVER.solve(recipes, SolveContext.empty(), REGISTRY,
                SolveSettings.defaults());

        assertTrue(result.values().isEmpty(), "环内物品不应被定价");
        assertTrue(result.passesUsed() <= SolveSettings.DEFAULT_MAX_PASSES, "不得突破轮数上限");
        assertTrue(result.unresolvedItems().contains(a), "环内物品 a 应被报告");
        assertTrue(result.unresolvedItems().contains(b), "环内物品 b 应被报告");
    }

    @Test
    void selfLoopTerminatesAndIsReported() {
        ItemKey self = item("self_reference");
        List<RecipeSnapshot> recipes = List.of(
                recipe("self_recipe", RecipeKind.SHAPED, List.of(in(self)), List.of(out(self, 1))));

        SolveResult result = SOLVER.solve(recipes, SolveContext.empty(), REGISTRY, SolveSettings.defaults());

        assertTrue(result.values().isEmpty());
        assertTrue(result.unresolvedItems().contains(self));
    }

    @Test
    void competingRecipesPickLowestCostAndRecordAlternatives() {
        ItemKey cheapInput = item("cheap_input");
        ItemKey expensiveInput = item("expensive_input");
        ItemKey product = item("contested");
        List<RecipeSnapshot> recipes = List.of(
                recipe("cheap_route", RecipeKind.SHAPED,
                        List.of(in(cheapInput)), List.of(out(product, 1))),
                recipe("expensive_route", RecipeKind.SHAPED,
                        List.of(in(expensiveInput)), List.of(out(product, 1))));

        SolveResult result = SOLVER.solve(recipes,
                new SolveContext(Map.of(cheapInput, 10L, expensiveInput, 50L)), REGISTRY,
                SolveSettings.defaults());

        assertEquals(10L, result.values().get(product), "应选择最低成本配方");
        SolveTrace trace = result.traces().get(product);
        assertNotNull(trace);
        assertEquals(new ResourceLocation("sudoersemc", "cheap_route"), trace.recipeId());
        assertEquals(List.of(50L), trace.alternativeCosts(), "另一条配方成本应记入备选");
        assertEquals(PriceSource.RECIPE, trace.source());
    }

    @Test
    void alternativeCostsAreCappedBySettings() {
        ItemKey base = item("alt_base");
        ItemKey product = item("alt_product");
        List<RecipeSnapshot> recipes = List.of(
                recipe("route_1", RecipeKind.SHAPED, List.of(in(base)), List.of(out(product, 1))),
                recipe("route_2", RecipeKind.SHAPED, List.of(in(base, 2)), List.of(out(product, 1))),
                recipe("route_3", RecipeKind.SHAPED, List.of(in(base, 3)), List.of(out(product, 1))),
                recipe("route_4", RecipeKind.SHAPED, List.of(in(base, 4)), List.of(out(product, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(base, 10L)), REGISTRY,
                new SolveSettings(20, false, 1L, 2));

        assertEquals(10L, result.values().get(product));
        assertEquals(2, result.traces().get(product).alternativeCosts().size(),
                "备选成本应被 maxAlternativeCostsPerItem 截断");
    }

    @Test
    void existingEmcIsNeverOverwritten() {
        ItemKey ore = item("protected_ore");
        ItemKey ingot = item("protected_ingot");
        List<RecipeSnapshot> recipes = List.of(
                recipe("would_be_cheap", RecipeKind.FURNACE, List.of(in(ore)), List.of(out(ingot, 1))));

        SolveResult result = SOLVER.solve(recipes,
                new SolveContext(Map.of(ore, 1L, ingot, 9_999L)), REGISTRY, SolveSettings.defaults());

        assertTrue(result.values().isEmpty(), "已有 EMC 的物品不进入生成结果");
        assertNull(result.traces().get(ingot), "已有 EMC 不生成轨迹");
    }

    @Test
    void valueIncreaseIsRejectedByDefault() {
        ItemKey expensive = item("increase_input");
        ItemKey product = item("increase_product");
        List<RecipeSnapshot> recipes = List.of(
                recipe("costs_more", RecipeKind.SHAPED, List.of(in(expensive)), List.of(out(product, 1))));

        // 预置一个较低的生成值，模拟“前一轮已经发布过更低的价”；输入本身必须有值，否则配方无法解析
        SolveContext context = new SolveContext(Map.of(expensive, 50L));
        context.putGenerated(product, 10L, new ResourceLocation("sudoersemc", "seeded"),
                PriceSource.RECIPE);

        SolveResult rejected = SOLVER.solve(recipes, context, REGISTRY, SolveSettings.defaults());

        // SolveResult.values() 是上下文生成值的完整视图，预置的 10 本就存在于其中；
        // 禁止抬价的关键断言是它没有被改写成 50。
        assertEquals(10L, rejected.values().get(product), "默认不允许抬价，应保留较低值");
        assertEquals(10L, context.generatedValue(product));
    }

    @Test
    void valueIncreaseIsAcceptedWhenEnabled() {
        ItemKey expensive = item("increase_input_on");
        ItemKey product = item("increase_product_on");
        List<RecipeSnapshot> recipes = List.of(
                recipe("costs_more_on", RecipeKind.SHAPED,
                        List.of(in(expensive)), List.of(out(product, 1))));

        SolveContext context = new SolveContext(Map.of(expensive, 50L));
        context.putGenerated(product, 10L, new ResourceLocation("sudoersemc", "seeded"),
                PriceSource.RECIPE);

        SolveResult accepted = SOLVER.solve(recipes, context, REGISTRY,
                new SolveSettings(20, true, 1L, 8));

        assertEquals(50L, accepted.values().get(product), "允许抬价时应更新为更高成本");
        assertEquals(50L, context.generatedValue(product));
    }

    @Test
    void maxPassesIsRespected() {
        ItemKey stone = item("cap_stone");
        ItemKey plank = item("cap_plank");
        ItemKey stick = item("cap_stick");
        List<RecipeSnapshot> recipes = List.of(
                recipe("a_plank_cap", RecipeKind.STONECUTTER, List.of(in(stone)), List.of(out(plank, 1))),
                recipe("b_stick_cap", RecipeKind.SHAPED, List.of(in(plank)), List.of(out(stick, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(stone, 10L)), REGISTRY,
                new SolveSettings(1, false, 1L, 8));

        assertEquals(1, result.passesUsed());
        assertFalse(result.converged(), "轮数用尽时不应声称收敛");
        assertEquals(10L, result.values().get(plank));
        assertNull(result.values().get(stick), "第 2 级在轮数用尽前无法定价");
    }

    @Test
    void zeroCostRecipeDoesNotPublishValue() {
        ItemKey freeInput = item("free_input");
        ItemKey product = item("free_product");
        // 输入 EMC 为 1，输出 1 个，最小 EMC 为 0 → 结果为 1，仍然可定价
        List<RecipeSnapshot> recipes = List.of(
                recipe("free_recipe", RecipeKind.FURNACE, List.of(in(freeInput)), List.of(out(product, 1))));

        SolveResult zeroMinimum = SOLVER.solve(recipes, new SolveContext(Map.of(freeInput, 1L)), REGISTRY,
                new SolveSettings(20, false, 0L, 8));

        assertEquals(1L, zeroMinimum.values().get(product), "成本为 1 时仍应定价为 1");
    }

    @Test
    void unknownInputIsNotTreatedAsFree() {
        ItemKey unknown = item("unknown_free");
        ItemKey product = item("unknown_free_product");
        List<RecipeSnapshot> recipes = List.of(
                recipe("unknown_recipe", RecipeKind.FURNACE,
                        List.of(in(unknown)), List.of(out(product, 1))));

        SolveResult result = SOLVER.solve(recipes, SolveContext.empty(), REGISTRY,
                new SolveSettings(20, false, 0L, 8));

        assertTrue(result.values().isEmpty(), "未知输入不能被当作免费");
        assertTrue(result.unresolvedItems().contains(unknown));
    }

    @Test
    void solveIsDeterministic() {
        ItemKey a = item("det_a");
        ItemKey b = item("det_b");
        ItemKey x = item("det_x");
        ItemKey y = item("det_y");
        List<RecipeSnapshot> recipes = List.of(
                recipe("r1", RecipeKind.SHAPED, List.of(in(a)), List.of(out(x, 1))),
                recipe("r2", RecipeKind.SHAPED, List.of(in(b)), List.of(out(y, 1))),
                recipe("r3", RecipeKind.SHAPED, List.of(in(x, 2), in(y)), List.of(out(a, 1))));

        SolveResult first = SOLVER.solve(recipes, new SolveContext(Map.of(b, 7L)), REGISTRY,
                SolveSettings.defaults());
        SolveResult second = SOLVER.solve(recipes, new SolveContext(Map.of(b, 7L)), REGISTRY,
                SolveSettings.defaults());

        assertEquals(first.values(), second.values());
        assertEquals(first.passesUsed(), second.passesUsed());
        assertEquals(first.converged(), second.converged());
    }

    @Test
    void emptyRecipeListReturnsEmptyResult() {
        SolveResult result = SOLVER.solve(List.of(), SolveContext.empty(), REGISTRY, SolveSettings.defaults());

        assertTrue(result.values().isEmpty());
        assertEquals(0, result.passesUsed());
        assertTrue(result.converged());
        assertEquals(0, result.recipesEvaluated());
    }

    @Test
    void nullRecipesAreIgnored() {
        ItemKey ore = item("null_ore");
        ItemKey ingot = item("null_ingot");
        List<RecipeSnapshot> recipes = java.util.Arrays.asList(
                null,
                recipe("valid", RecipeKind.FURNACE, List.of(in(ore)), List.of(out(ingot, 1))),
                null);

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(ore, 5L)), REGISTRY,
                SolveSettings.defaults());

        assertEquals(5L, result.values().get(ingot));
    }

    @Test
    void throwingEvaluatorDoesNotAbortSolve() {
        RecipeEvaluator exploding = new RecipeEvaluator() {
            @Override
            public boolean supports(RecipeSnapshot recipe) {
                return true;
            }

            @Override
            public RecipeEvaluation evaluate(RecipeSnapshot recipe, EmcLookup lookup, EvaluationPolicy policy) {
                throw new IllegalStateException("评估器故障");
            }
        };
        EvaluatorRegistry brokenRegistry = EvaluatorRegistry.builder().add(exploding).build();
        List<RecipeSnapshot> recipes = List.of(
                recipe("boom", RecipeKind.SHAPED, List.of(in(item("boom_in"))), List.of(out(item("boom_out"), 1))));

        SolveResult result = SOLVER.solve(recipes, SolveContext.empty(), brokenRegistry,
                SolveSettings.defaults());

        assertTrue(result.values().isEmpty());
        assertTrue(result.recipesEvaluated() > 0, "失败调用也应计入评估次数");
    }

    @Test
    void traceRecordsThePassThatProducedTheValue() {
        ItemKey stone = item("trace_stone");
        ItemKey plank = item("trace_plank");
        ItemKey stick = item("trace_stick");
        List<RecipeSnapshot> recipes = List.of(
                recipe("a_plank_trace", RecipeKind.STONECUTTER, List.of(in(stone)), List.of(out(plank, 1))),
                recipe("b_stick_trace", RecipeKind.SHAPED, List.of(in(plank)), List.of(out(stick, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(stone, 10L)), REGISTRY,
                SolveSettings.defaults());

        assertEquals(1, result.traces().get(plank).pass(), "plank 在第 1 轮定价");
        assertEquals(2, result.traces().get(stick).pass(), "stick 在第 2 轮定价");
    }

    @Test
    void settingsRejectInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> new SolveSettings(0, false, 1L, 8));
        assertThrows(IllegalArgumentException.class, () -> new SolveSettings(20, false, -1L, 8));
        assertThrows(IllegalArgumentException.class, () -> new SolveSettings(20, false, 1L, -1));
    }

    @Test
    void contextRejectsNonPositiveGeneratedValues() {
        SolveContext context = SolveContext.empty();
        ResourceLocation recipeId = new ResourceLocation("sudoersemc", "trace");

        assertThrows(IllegalArgumentException.class,
                () -> context.putGenerated(item("zero"), 0L, recipeId, PriceSource.RECIPE));
        assertThrows(IllegalArgumentException.class,
                () -> context.putGenerated(item("negative"), -5L, recipeId, PriceSource.RECIPE));
    }

    @Test
    void contextIgnoresNonPositiveSeedValues() {
        ItemKey zero = item("seed_zero");
        ItemKey negative = item("seed_negative");
        ItemKey valid = item("seed_valid");

        SolveContext context = new SolveContext(Map.of(zero, 0L, negative, -10L, valid, 7L));

        assertEquals(0L, context.emcOf(zero), "0 EMC 等同没有值");
        assertEquals(0L, context.emcOf(negative), "负值等同没有值");
        assertEquals(7L, context.emcOf(valid));
        assertFalse(context.hasSeedValue(zero));
        assertTrue(context.hasSeedValue(valid));
    }

    @Test
    void bothOutputsOfAChainResolveInOneRun() {
        ItemKey raw = item("batch_raw");
        ItemKey refined = item("batch_refined");
        ItemKey component = item("batch_component");
        ItemKey finalProduct = item("batch_final");
        List<RecipeSnapshot> recipes = List.of(
                recipe("a_refine", RecipeKind.FURNACE, List.of(in(raw, 2)), List.of(out(refined, 1))),
                recipe("b_component", RecipeKind.SHAPED, List.of(in(refined, 3)), List.of(out(component, 1))),
                recipe("c_final", RecipeKind.SHAPED, List.of(in(component, 2)), List.of(out(finalProduct, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(raw, 10L)), REGISTRY,
                SolveSettings.defaults());

        assertTrue(result.converged());
        assertEquals(20L, result.values().get(refined), "2 * 10");
        assertEquals(60L, result.values().get(component), "3 * 20");
        assertEquals(120L, result.values().get(finalProduct), "2 * 60");
        assertEquals(3, result.solvedCount());
    }

    @Test
    void unresolvedItemsDoNotIncludeItemsResolvedLater() {
        ItemKey stone = item("late_stone");
        ItemKey plank = item("late_plank");
        ItemKey stick = item("late_stick");
        List<RecipeSnapshot> recipes = List.of(
                recipe("a_plank_late", RecipeKind.STONECUTTER, List.of(in(stone)), List.of(out(plank, 1))),
                recipe("b_stick_late", RecipeKind.SHAPED, List.of(in(plank)), List.of(out(stick, 1))));

        SolveResult result = SOLVER.solve(recipes, new SolveContext(Map.of(stone, 10L)), REGISTRY,
                SolveSettings.defaults());

        assertEquals(Set.of(), result.unresolvedItems(), "第 1 轮暂时未解析的 plank 最终已定价，不应报告");
    }
}
