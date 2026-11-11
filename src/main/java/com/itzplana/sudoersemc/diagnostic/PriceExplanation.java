package com.itzplana.sudoersemc.diagnostic;

import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 单个物品价格的解释快照，用于 {@code /sudoemc explain} 命令。
 *
 * <p>记录“这个值是多少、由哪条路径产生、是否归 SudoersEMC 所有、还有哪些备选成本”，不包含
 * 任何推导过程或缓存引用。备选成本按调用方给出的顺序保留，空列表与 {@code null} 都视为“没有
 * 备选成本”。</p>
 *
 * @param item             物品身份
 * @param value            当前 EMC 值，必须 {@code >= 0}
 * @param source           产生该值的定价来源
 * @param recipeId         产出该值的配方 id；非配方来源为 {@code null}
 * @param owned            SudoersEMC 是否拥有该条目并可在后续扫描中更新
 * @param ownership        人类可读的归属描述；{@code null} 或空白会显示为“无”
 * @param alternativeCosts 备选成本列表，元素必须非 {@code null} 且 {@code >= 0}
 * @param note             额外备注，可为 {@code null}
 */
public record PriceExplanation(
        ItemKey item,
        long value,
        PriceSource source,
        @Nullable ResourceLocation recipeId,
        boolean owned,
        String ownership,
        List<Long> alternativeCosts,
        @Nullable String note
) {

    public PriceExplanation {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(source, "source");
        if (value < 0L) {
            throw new IllegalArgumentException("EMC 值必须 >= 0，实际为 " + value);
        }
        ownership = ownership == null || ownership.isBlank() ? "无" : ownership;

        List<Long> copiedCosts = new ArrayList<>();
        if (alternativeCosts != null) {
            for (Long cost : alternativeCosts) {
                if (cost == null) {
                    throw new IllegalArgumentException("备选成本不能包含 null");
                }
                if (cost < 0L) {
                    throw new IllegalArgumentException("备选成本必须 >= 0，实际为 " + cost);
                }
                copiedCosts.add(cost);
            }
        }
        alternativeCosts = List.copyOf(copiedCosts);
    }

    /**
     * 构造一条没有备注的解释。
     *
     * @param item             物品身份
     * @param value            当前 EMC 值
     * @param source           定价来源
     * @param recipeId         配方 id，可为 {@code null}
     * @param owned            SudoersEMC 是否拥有该条目
     * @param ownership        归属描述
     * @param alternativeCosts 备选成本，可为 {@code null}
     * @return 备注为空的解释快照
     */
    public static PriceExplanation of(
            ItemKey item,
            long value,
            PriceSource source,
            @Nullable ResourceLocation recipeId,
            boolean owned,
            String ownership,
            List<Long> alternativeCosts) {
        return new PriceExplanation(
                item, value, source, recipeId, owned, ownership, alternativeCosts, null);
    }

    /**
     * 生成多行中文解释文本。
     *
     * <p>缺省字段统一显示为“无”，保证命令输出始终包含全部七行，便于玩家对照与日志粘贴。
     * 备选成本以 {@code ", "} 连接并按原始顺序输出。</p>
     *
     * @return 以 {@code \n} 分隔的解释文本，末尾没有换行符
     */
    public String formatText() {
        StringBuilder text = new StringBuilder();
        text.append("物品: ").append(item).append('\n');
        text.append("EMC : ").append(value).append('\n');
        text.append("来源: ").append(source.name()).append('\n');
        text.append("配方: ").append(recipeId == null ? "无" : recipeId.toString()).append('\n');
        text.append("归属: ").append(ownership).append('\n');
        text.append("备选成本: ").append(formatAlternatives()).append('\n');
        text.append("备注: ").append(note == null || note.isBlank() ? "无" : note);
        return text.toString();
    }

    private String formatAlternatives() {
        if (alternativeCosts.isEmpty()) {
            return "无";
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (Long cost : alternativeCosts) {
            joiner.add(Long.toString(cost));
        }
        return joiner.toString();
    }
}
