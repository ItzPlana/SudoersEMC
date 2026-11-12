package com.itzplana.sudoersemc.resolver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.Test;

/**
 * {@link ResolverRegistry} 的单元测试，重点验证解析顺序与异常隔离。
 */
class ResolverRegistryTest {

    private static final ItemStack STACK;

    static {
        // Forge 的 ItemStack 静态初始化需要 BuiltInRegistries，因此测试前必须先完成引导。
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        STACK = new ItemStack(Items.STONE);
    }

    @Test
    void firstMatchingResolverWins() {
        StubResolver first = new StubResolver(true, OptionalLong.of(10L));
        StubResolver second = new StubResolver(true, OptionalLong.of(20L));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(first);
        registry.register(second);

        assertEquals(OptionalLong.of(10L), registry.resolve(STACK));
        assertEquals(0, second.resolveCalls);
    }

    @Test
    void supportingResolverReturningEmptyDoesNotStopSearch() {
        StubResolver empty = new StubResolver(true, OptionalLong.empty());
        StubResolver working = new StubResolver(true, OptionalLong.of(40L));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(empty);
        registry.register(working);

        assertEquals(OptionalLong.of(40L), registry.resolve(STACK));
        assertEquals(1, working.resolveCalls);
    }

    @Test
    void throwingResolverIsSkippedAndNextOneIsTried() {
        StubResolver broken = new StubResolver(true, OptionalLong.of(1L),
                new IllegalStateException("模拟第三方解析器故障"));
        StubResolver working = new StubResolver(true, OptionalLong.of(20L));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(broken);
        registry.register(working);

        assertEquals(OptionalLong.of(20L), registry.resolve(STACK));
        assertEquals(1, working.resolveCalls);
    }

    @Test
    void resolverWhoseSupportsThrowsIsSkipped() {
        NbtMaterialResolver broken = new NbtMaterialResolver() {
            @Override
            public boolean supports(ItemStack stack) {
                throw new IllegalStateException("模拟 supports 故障");
            }

            @Override
            public OptionalLong resolve(ItemStack stack) {
                return OptionalLong.of(1L);
            }
        };
        StubResolver working = new StubResolver(true, OptionalLong.of(30L));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(broken);
        registry.register(working);

        assertEquals(OptionalLong.of(30L), registry.resolve(STACK));
    }

    @Test
    void errorIsNotSwallowed() {
        NbtMaterialResolver fatal = new NbtMaterialResolver() {
            @Override
            public boolean supports(ItemStack stack) {
                return true;
            }

            @Override
            public OptionalLong resolve(ItemStack stack) {
                throw new AssertionError("模拟 JVM 错误");
            }
        };
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(fatal);

        assertThrows(AssertionError.class, () -> registry.resolve(STACK));
    }

    @Test
    void emptyRegistryReturnsEmpty() {
        assertTrue(ResolverRegistry.defaults().resolve(STACK).isEmpty());
    }

    @Test
    void nonSupportingResolverIsNotConsulted() {
        StubResolver nonSupporting = new StubResolver(false, OptionalLong.of(10L));
        StubResolver supporting = new StubResolver(true, OptionalLong.of(20L));
        ResolverRegistry registry = ResolverRegistry.defaults();
        registry.register(nonSupporting);
        registry.register(supporting);

        assertEquals(OptionalLong.of(20L), registry.resolve(STACK));
        assertEquals(0, nonSupporting.resolveCalls);
    }

    @Test
    void nullRegistrationIsRejected() {
        ResolverRegistry registry = ResolverRegistry.defaults();
        assertThrows(NullPointerException.class, () -> registry.register(null));
    }

    @Test
    void fingerprintEncodesRegistrationOrder() {
        ResolverRegistry alphaFirst = ResolverRegistry.defaults();
        alphaFirst.register(new AlphaResolver());
        alphaFirst.register(new BetaResolver());

        ResolverRegistry betaFirst = ResolverRegistry.defaults();
        betaFirst.register(new BetaResolver());
        betaFirst.register(new AlphaResolver());

        assertEquals(2, alphaFirst.fingerprintEntries().size());
        assertTrue(alphaFirst.fingerprintEntries().get(0).startsWith("resolver[0]:"));
        assertTrue(alphaFirst.fingerprintEntries().get(0).endsWith("AlphaResolver"));
        assertTrue(alphaFirst.fingerprintEntries().get(1).startsWith("resolver[1]:"));
        assertTrue(alphaFirst.fingerprintEntries().get(1).endsWith("BetaResolver"));
        assertNotEquals(alphaFirst.fingerprintEntries(), betaFirst.fingerprintEntries(),
                "注册顺序变化必须改变指纹以触发重扫");
    }

    private static final class AlphaResolver implements NbtMaterialResolver {

        @Override
        public boolean supports(ItemStack stack) {
            return false;
        }

        @Override
        public OptionalLong resolve(ItemStack stack) {
            return OptionalLong.empty();
        }
    }

    private static final class BetaResolver implements NbtMaterialResolver {

        @Override
        public boolean supports(ItemStack stack) {
            return false;
        }

        @Override
        public OptionalLong resolve(ItemStack stack) {
            return OptionalLong.empty();
        }
    }

    private static final class StubResolver implements NbtMaterialResolver {

        private final boolean supports;
        private final OptionalLong value;
        private final RuntimeException failure;
        private int resolveCalls;

        private StubResolver(boolean supports, OptionalLong value) {
            this(supports, value, null);
        }

        private StubResolver(boolean supports, OptionalLong value, RuntimeException failure) {
            this.supports = supports;
            this.value = value;
            this.failure = failure;
        }

        @Override
        public boolean supports(ItemStack stack) {
            return supports;
        }

        @Override
        public OptionalLong resolve(ItemStack stack) {
            resolveCalls++;
            if (failure != null) {
                throw failure;
            }
            return value;
        }
    }
}
