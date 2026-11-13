package com.itzplana.sudoersemc.scanner;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.Objects;
import org.jetbrains.annotations.Nullable;

/**
 * One produced stack, or one returned container.
 *
 * @param item      produced item identity
 * @param count     produced amount; always {@code >= 1}
 * @param outputNbt 输出堆叠的 SNBT 表示；堆叠没有 NBT 时为 {@code null}
 */
public record RecipeOutput(ItemKey item, int count, @Nullable String outputNbt) {

    public RecipeOutput {
        Objects.requireNonNull(item, "item");
        if (count < 1) {
            throw new IllegalArgumentException("Recipe output count must be >= 1, got " + count);
        }
        if (outputNbt != null) {
            // SNBT "{}"、"{ }" 等空白形式都表示空标签；统一归一为 null，
            // 避免下游把空标签当成真实变体生成 variantHash。
            String compact = outputNbt.replaceAll("\\s+", "");
            if (compact.isEmpty() || "{}".equals(compact)) {
                outputNbt = null;
            }
        }
    }

    /**
     * 向后兼容构造函数：没有输出 NBT 时等价于 {@code outputNbt = null}。
     *
     * @param item  produced item identity
     * @param count produced amount; always {@code >= 1}
     */
    public RecipeOutput(ItemKey item, int count) {
        this(item, count, null);
    }

    /**
     * 创建不带 NBT 的单个产物。
     */
    public static RecipeOutput of(ItemKey item) {
        return new RecipeOutput(item, 1, null);
    }

    /**
     * 创建带 SNBT 的配方产物。
     *
     * @param item      produced item identity
     * @param count     produced amount; always {@code >= 1}
     * @param outputNbt 输出堆叠的 SNBT，允许为 {@code null}
     */
    public static RecipeOutput of(ItemKey item, int count, @Nullable String outputNbt) {
        return new RecipeOutput(item, count, outputNbt);
    }

    /**
     * @return 输出堆叠是否携带 NBT
     */
    public boolean hasOutputNbt() {
        return outputNbt != null;
    }
}
