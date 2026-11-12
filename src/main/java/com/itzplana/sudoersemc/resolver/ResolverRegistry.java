package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.diagnostic.DebugLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有序的 NBT 材料解析器注册表。
 *
 * <p>解析按注册顺序进行，第一个同时满足 {@link NbtMaterialResolver#supports(ItemStack)}
 * 且返回非空结果的解析器获胜。解析器可能来自第三方模组，因此任何异常都会被记录并跳过，
 * 绝不向调用方传播；单个解析器失败不会中断后续解析。</p>
 *
 * <p>本类线程安全：内部使用写时复制列表，允许在扫描线程读取的同时安全注册。</p>
 */
public final class ResolverRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/resolver/ResolverRegistry");

    private final List<NbtMaterialResolver> resolvers = new CopyOnWriteArrayList<>();
    private final AtomicLong resolveCalls = new AtomicLong();

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
     * 按顺序注册一个解析器，同一实例只注册一次。
     *
     * <p>第三方模组可能在构造器与 common setup 各注册一次；去重避免同一个解析器被重复调用。
     * 方法对注册操作加锁，保证“检查 + 添加”在并发注册时也是原子的。</p>
     *
     * @param resolver 解析器实例
     * @return {@code true} 表示本次新增，{@code false} 表示已存在
     * @throws NullPointerException 当 {@code resolver} 为 {@code null}
     */
    public synchronized boolean registerIfAbsent(NbtMaterialResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (resolvers.contains(resolver)) {
            return false;
        }
        return resolvers.add(resolver);
    }

    /**
     * @return 当前注册的解析器数量
     */
    public int size() {
        return resolvers.size();
    }

    /**
     * 返回稳定的注册表指纹：按类名排序的 {@code resolver:<class>} 列表。
     *
     * <p>新增/移除 resolver 会改变扫描基线指纹，从而允许下一次启动触发重扫。</p>
     *
     * @return 不可变、已排序的指纹条目
     */
    public List<String> fingerprintEntries() {
        List<String> entries = new ArrayList<>(resolvers.size());
        for (NbtMaterialResolver resolver : resolvers) {
            entries.add("resolver:" + resolver.getClass().getName());
        }
        Collections.sort(entries);
        return List.copyOf(entries);
    }

    /**
     * 依次尝试所有解析器。
     *
     * <p>{@code supports} 或 {@code resolve} 抛出异常时只记录警告并继续尝试下一个解析器，
     * 防止某个模组的错误数据破坏整个扫描流程。</p>
     *
     * @param stack 待定价的堆叠
     * @return 第一个成功解析出的 EMC；没有解析器能够定价时返回空
     */
    public OptionalLong resolve(ItemStack stack) {
        if (stack == null || stack.isEmpty() || resolvers.isEmpty()) {
            return OptionalLong.empty();
        }
        long callIndex = 0L;
        if (DebugLog.isEnabled()) {
            callIndex = resolveCalls.incrementAndGet();
        }
        for (NbtMaterialResolver resolver : resolvers) {
            try {
                if (!resolver.supports(stack)) {
                    continue;
                }
                OptionalLong value = resolver.resolve(stack);
                if (value != null && value.isPresent() && value.getAsLong() > 0L) {
                    if (DebugLog.isEnabled()) {
                        final long index = callIndex;
                        DebugLog.headOrSampled("resolver", index, 20L, 500L,
                                () -> "hit item=" + stack.getItem()
                                        + " resolver=" + resolver.getClass().getName()
                                        + " value=" + value.getAsLong());
                    }
                    return value;
                }
                if (value != null && value.isPresent() && DebugLog.isEnabled()) {
                    final long index = callIndex;
                    DebugLog.headOrSampled("resolver", index, 20L, 500L,
                            () -> "ignored non-positive value=" + value.getAsLong()
                                    + " resolver=" + resolver.getClass().getName());
                }
            } catch (Throwable t) {
                LOGGER.warn("NBT 材料解析器 {} 处理物品 {} 时抛出异常，已跳过",
                        resolver.getClass().getName(), stack.getItem(), t);
                if (DebugLog.isEnabled()) {
                    final long index = callIndex;
                    DebugLog.headOrSampled("resolver", index, 20L, 500L,
                            () -> "error resolver=" + resolver.getClass().getName()
                                    + " item=" + stack.getItem()
                                    + " message=" + t.getClass().getSimpleName());
                }
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
