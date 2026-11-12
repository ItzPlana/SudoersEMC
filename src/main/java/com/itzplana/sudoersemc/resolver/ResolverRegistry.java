package com.itzplana.sudoersemc.resolver;

import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有序的 NBT 材料解析器注册表。
 *
 * <p>解析按注册顺序进行，第一个同时满足 {@link NbtMaterialResolver#supports(ItemStack)}
 * 且返回非空结果的解析器获胜。解析器可能来自第三方模组，因此 {@link RuntimeException}
 * 会被记录并跳过，单个解析器失败不会中断后续解析；{@link Error}（例如
 * {@link OutOfMemoryError}）不会被吞掉，会继续向上传播，避免资源耗尽后留下不一致状态。</p>
 *
 * <p>本类线程安全：内部使用写时复制列表，允许在扫描线程读取的同时安全注册。</p>
 */
public final class ResolverRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/resolver/ResolverRegistry");

    private final List<NbtMaterialResolver> resolvers = new CopyOnWriteArrayList<>();

    /**
     * 按顺序注册一个解析器。
     *
     * @param resolver 解析器实例
     * @throws NullPointerException 当 {@code resolver} 为 {@code null}
     */
    public void register(NbtMaterialResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        resolvers.add(resolver);
    }

    /**
     * 依次尝试所有解析器。
     *
     * <p>{@code supports} 或 {@code resolve} 抛出 {@link RuntimeException} 时只记录警告并
     * 继续尝试下一个解析器，防止某个模组的错误数据破坏整个扫描流程。</p>
     *
     * @param stack 待定价的堆叠
     * @return 第一个成功解析出的 EMC；没有解析器能够定价时返回空
     */
    public OptionalLong resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty() || resolvers.isEmpty()) {
            return OptionalLong.empty();
        }
        for (NbtMaterialResolver resolver : resolvers) {
            try {
                if (!resolver.supports(stack)) {
                    continue;
                }
                OptionalLong value = resolver.resolve(stack);
                if (value != null && value.isPresent()) {
                    return value;
                }
            } catch (RuntimeException t) {
                LOGGER.warn("NBT 材料解析器 {} 处理物品 {} 时抛出异常，已跳过",
                        resolver.getClass().getName(), stack.getItem(), t);
            }
        }
        return OptionalLong.empty();
    }

    /**
     * 创建一个空注册表。
     *
     * <p>内置解析器不会被自动注册，调用方需要显式注册 {@link DefaultMaterialResolver}，
     * 这样集成层可以完全控制解析顺序。</p>
     *
     * @return 不包含任何解析器的注册表
     */
    public static ResolverRegistry defaults() {
        return new ResolverRegistry();
    }
}
