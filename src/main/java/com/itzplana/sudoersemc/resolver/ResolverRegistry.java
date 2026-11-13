package com.itzplana.sudoersemc.resolver;

import com.itzplana.sudoersemc.diagnostic.DebugLog;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ordered registry of NBT material resolvers.
 *
 * <p>Resolution proceeds in registration order; the first resolver that both satisfies
 * {@link NbtMaterialResolver#supports(ItemStack)} and returns a non-empty result wins. Resolvers may
 * come from third-party mods, so a {@link RuntimeException} is logged and skipped — a single resolver
 * failure does not abort subsequent resolution; an {@link Error} (e.g. {@link OutOfMemoryError}) is not
 * swallowed and propagates upward, avoiding an inconsistent state after resource exhaustion.</p>
 *
 * <p>This class is thread-safe: it uses a copy-on-write list internally, allowing safe registration
 * while the scan thread reads.</p>
 */
public final class ResolverRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/resolver/ResolverRegistry");

    private final List<NbtMaterialResolver> resolvers = new CopyOnWriteArrayList<>();
    private final AtomicLong resolveCalls = new AtomicLong();

    /**
     * Registers a resolver in order.
     *
     * @param resolver the resolver instance
     * @throws NullPointerException when {@code resolver} is {@code null}
     */
    public void register(NbtMaterialResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        resolvers.add(resolver);
    }

    /**
     * Registers a resolver in order, registering the same instance only once.
     *
     * <p>A third-party mod may register once in its constructor and once in common setup; deduplication
     * avoids the same resolver being called repeatedly. The method locks the registration operation so
     * "check + add" is atomic even under concurrent registration.</p>
     *
     * @param resolver the resolver instance
     * @return {@code true} if newly added, {@code false} if already present
     * @throws NullPointerException when {@code resolver} is {@code null}
     */
    public synchronized boolean registerIfAbsent(NbtMaterialResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (resolvers.contains(resolver)) {
            return false;
        }
        return resolvers.add(resolver);
    }

    /**
     * @return the number of currently registered resolvers
     */
    public int size() {
        return resolvers.size();
    }

    /**
     * Returns a stable registry fingerprint: a list of {@code resolver[index]:<class>} in registration
     * order.
     *
     * <p>Resolution depends on registration order (the first resolver returning a value > 0 wins), so an
     * order change must change the scan baseline fingerprint and trigger a rescan; adding/removing a
     * resolver also changes the fingerprint.</p>
     *
     * @return immutable fingerprint entries in registration order
     */
    public List<String> fingerprintEntries() {
        List<String> entries = new ArrayList<>(resolvers.size());
        int index = 0;
        for (NbtMaterialResolver resolver : resolvers) {
            entries.add("resolver[" + index + "]:" + resolver.getClass().getName());
            index++;
        }
        return List.copyOf(entries);
    }

    /**
     * Tries all resolvers in turn.
     *
     * <p>When {@code supports} or {@code resolve} throws a {@link RuntimeException}, only a warning is
     * logged and the next resolver is tried, preventing one mod's bad data from breaking the whole scan.</p>
     *
     * @param stack the stack to price
     * @return the first successfully resolved EMC; empty when no resolver can price
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
            } catch (RuntimeException t) {
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
     * Creates an empty registry.
     *
     * <p>Built-in resolvers are not auto-registered; the caller must explicitly register
     * {@link DefaultMaterialResolver}, so the integration layer fully controls resolution order.</p>
     *
     * @return a registry containing no resolvers
     */
    public static ResolverRegistry defaults() {
        return new ResolverRegistry();
    }
}
