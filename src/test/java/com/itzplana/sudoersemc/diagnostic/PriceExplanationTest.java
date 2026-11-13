package com.itzplana.sudoersemc.diagnostic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.List;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

/**
 * {@link PriceExplanation} 的单元测试。
 *
 * <p>检查解释文本是否包含物品、数值、来源、配方、归属与备选成本，并验证缺失字段统一显示
 * 为“无”。</p>
 */
class PriceExplanationTest {

    private static final ResourceLocation STONE = new ResourceLocation("minecraft", "stone");
    private static final ResourceLocation STONE_BLOCK = new ResourceLocation("minecraft", "stone_block");

    @Test
    void formatTextContainsAllFields() {
        PriceExplanation explanation = PriceExplanation.of(
                ItemKey.of(STONE),
                42L,
                PriceSource.RECIPE,
                STONE_BLOCK,
                true,
                "SudoersEMC 生成",
                List.of(7L, 8L, 9L));

        String text = explanation.formatText();

        assertTrue(text.contains("物品: minecraft:stone"));
        assertTrue(text.contains("EMC : 42"));
        assertTrue(text.contains("来源: RECIPE"));
        assertTrue(text.contains("配方: minecraft:stone_block"));
        assertTrue(text.contains("归属: SudoersEMC 生成"));
        assertTrue(text.contains("备选成本: 7, 8, 9"));
        assertTrue(text.contains("备注: 无"));
        assertEquals(7, text.lines().count(), "解释文本固定为七行");
    }

    @Test
    void nullRecipeIdRendersNone() {
        PriceExplanation explanation = PriceExplanation.of(
                ItemKey.of(STONE), 1L, PriceSource.FALLBACK, null, false, "外部基线", List.of());

        assertTrue(explanation.formatText().contains("配方: 无"));
    }

    @Test
    void emptyAlternativesRenderNone() {
        PriceExplanation explanation = PriceExplanation.of(
                ItemKey.of(STONE), 1L, PriceSource.FALLBACK, null, false, "外部基线", List.of());

        assertTrue(explanation.formatText().contains("备选成本: 无"));
    }

    @Test
    void nullNoteRendersNone() {
        PriceExplanation explanation = PriceExplanation.of(
                ItemKey.of(STONE), 1L, PriceSource.TIER, null, true, "SudoersEMC 生成", List.of(1L));

        assertTrue(explanation.note() == null);
        assertTrue(explanation.formatText().contains("备注: 无"));
    }

    @Test
    void nullOrBlankOwnershipRendersNone() {
        PriceExplanation nullOwnership = PriceExplanation.of(
                ItemKey.of(STONE), 1L, PriceSource.TIER, null, true, null, List.of());
        PriceExplanation blankOwnership = PriceExplanation.of(
                ItemKey.of(STONE), 1L, PriceSource.TIER, null, true, "   ", List.of());

        assertTrue(nullOwnership.formatText().contains("归属: 无"));
        assertTrue(blankOwnership.formatText().contains("归属: 无"));
    }
}
