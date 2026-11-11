package com.itzplana.sudoersemc.resolver;

import java.util.OptionalLong;
import net.minecraft.world.item.ItemStack;

/**
 * 为带有 NBT 变体的物品堆叠提供价格解析扩展点。
 *
 * <p>模组专属集成可以实现本接口，并通过 {@link ResolverRegistry#register(NbtMaterialResolver)}
 * 注册，从而在不修改核心定价引擎的前提下为某个变体定价。解析器必须自行判断
 * {@link #supports(ItemStack)}，只对能够识别且可定价的堆叠返回非空结果。</p>
 *
 * <p>约束：{@link #resolve(ItemStack)} 返回的 EMC 必须大于 0；无法定价时应返回
 * {@link OptionalLong#empty()}，不得返回 0 或负数。实现不得修改传入的堆叠。</p>
 */
public interface NbtMaterialResolver {

    /**
     * 判断当前解析器是否认识这个堆叠。
     *
     * <p>该方法应保持轻量，只检查标签结构或模组标记，不要执行完整的配方计算。实现可以
     * 在返回 {@code true} 后仍让 {@link #resolve(ItemStack)} 返回空结果。</p>
     *
     * @param stack 待检查的堆叠，可能为 {@code null}
     * @return 解析器是否愿意尝试为这个堆叠定价
     */
    boolean supports(ItemStack stack);

    /**
     * 计算堆叠的 EMC。
     *
     * <p>只有在 {@link #supports(ItemStack)} 返回 {@code true} 时注册表才会调用本方法。
     * 返回值必须大于 0；无法确定价格时必须返回空结果。</p>
     *
     * @param stack 待定价的堆叠，可能为 {@code null}
     * @return 大于 0 的 EMC，或空结果
     */
    OptionalLong resolve(ItemStack stack);
}
