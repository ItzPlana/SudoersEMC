package com.itzplana.sudoersemc.diagnostic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 可热重载的诊断调试日志。
 *
 * <p>默认关闭。关闭时所有入口只做一次 {@code volatile boolean} 读取并直接返回，不构造
 * {@link Supplier} 之外的对象、不拼接字符串、不做任何 map 查询。调用方在高频循环中应使用
 * {@link Supplier} 重载或先检查 {@link #isEnabled()}，避免在实参位置提前拼接字符串。</p>
 *
 * <p>开启后按类别使用固定窗口 token bucket 限流：每个类别每秒最多
 * {@link #MAX_PER_SECOND} 条。被抑制的条目只增加计数，扫描结束时可通过 {@link #summary()}
 * 输出 {@code suppressed=...} 汇总，避免大型整合包刷屏。</p>
 */
public final class DebugLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("sudoersemc/debug");

    /** 每个类别每秒最多输出的条数。 */
    public static final int MAX_PER_SECOND = 20;

    private static final long WINDOW_NANOS = 1_000_000_000L;

    private static final ConcurrentHashMap<String, Rate> RATES = new ConcurrentHashMap<>();

    private static volatile boolean enabled;

    /** 测试可注入固定时钟；生产环境始终使用 {@link System#nanoTime()}。 */
    private static volatile LongSupplier nanoClock = System::nanoTime;

    private DebugLog() {
    }

    /**
     * 更新调试日志开关。关闭时清空限流状态，避免长时间占用内存。
     *
     * @param value {@code true} 开启
     */
    public static void setEnabled(boolean value) {
        enabled = value;
        if (!value) {
            RATES.clear();
        }
    }

    /**
     * @return 当前是否开启调试日志
     */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * 输出一条已构造好的消息。高频路径请优先使用 {@link #log(String, Supplier)}。
     */
    public static void log(String category, String message) {
        if (!enabled) {
            return;
        }
        if (!allow(category)) {
            return;
        }
        LOGGER.debug("[{}] {}", category, message);
    }

    /**
     * 输出一条惰性构造的消息。关闭时不会调用 {@code message.get()}。
     */
    public static void log(String category, Supplier<String> message) {
        if (!enabled) {
            return;
        }
        if (!allow(category)) {
            return;
        }
        String text = message == null ? "" : message.get();
        LOGGER.debug("[{}] {}", category, text);
    }

    /**
     * 采样输出：仅当 {@code count} 是 {@code every} 的正整数倍时尝试输出。
     *
     * <p>关闭时不会执行取模之后的消息构造，也不会调用 {@link Supplier#get()}。</p>
     */
    public static void sampled(String category, long count, long every, Supplier<String> message) {
        if (!enabled || every <= 0L || count % every != 0L) {
            return;
        }
        log(category, message);
    }

    /**
     * 前 {@code head} 条 + 每 {@code every} 条采样一次。用于单个扫描阶段既想看开头又不想刷屏。
     */
    public static void headOrSampled(String category, long count, long head, long every,
                                     Supplier<String> message) {
        if (!enabled) {
            return;
        }
        if (count <= head || (every > 0L && count % every == 0L)) {
            log(category, message);
        }
    }

    /**
     * @return 指定类别实际输出的条数
     */
    public static long emittedCount(String category) {
        Rate rate = RATES.get(category);
        return rate == null ? 0L : rate.emitted();
    }

    /**
     * @return 指定类别因限流被抑制的条数
     */
    public static long suppressedCount(String category) {
        Rate rate = RATES.get(category);
        return rate == null ? 0L : rate.suppressed();
    }

    /**
     * @return 指定类别尝试输出的总条数
     */
    public static long totalCount(String category) {
        Rate rate = RATES.get(category);
        return rate == null ? 0L : rate.total();
    }

    /**
     * @return 所有类别的汇总，格式为 {@code debug category=... total=... emitted=... suppressed=...}
     */
    public static String summary() {
        List<Map.Entry<String, Rate>> entries = new ArrayList<>(RATES.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, Rate> entry : entries) {
            if (builder.length() > 0) {
                builder.append("; ");
            }
            Rate rate = entry.getValue();
            builder.append("debug category=").append(entry.getKey())
                    .append(" total=").append(rate.total())
                    .append(" emitted=").append(rate.emitted())
                    .append(" suppressed=").append(rate.suppressed());
        }
        return builder.toString();
    }

    /**
     * 测试辅助：注入固定时钟并清空旧统计。
     */
    static void resetForTesting() {
        setEnabled(false);
        nanoClock = System::nanoTime;
    }

    /**
     * 测试辅助：注入固定时钟。传入 {@code null} 恢复系统时钟。
     */
    static void setNanoClockForTesting(LongSupplier clock) {
        nanoClock = clock == null ? System::nanoTime : clock;
    }

    private static boolean allow(String category) {
        String key = category == null ? "general" : category;
        long now = nanoClock.getAsLong();
        Rate rate = RATES.computeIfAbsent(key, ignored -> new Rate(now));
        return rate.tryAcquire(now);
    }

    private static final class Rate {

        private long windowStart;
        private int windowEmitted;
        private long total;
        private long emitted;
        private long suppressed;

        private Rate(long windowStart) {
            this.windowStart = windowStart;
        }

        private synchronized boolean tryAcquire(long now) {
            total++;
            if (now - windowStart >= WINDOW_NANOS) {
                windowStart = now;
                windowEmitted = 0;
            }
            if (windowEmitted >= MAX_PER_SECOND) {
                suppressed++;
                return false;
            }
            windowEmitted++;
            emitted++;
            return true;
        }

        private synchronized long total() {
            return total;
        }

        private synchronized long emitted() {
            return emitted;
        }

        private synchronized long suppressed() {
            return suppressed;
        }
    }
}
