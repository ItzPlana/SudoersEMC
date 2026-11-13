package com.itzplana.sudoersemc.scanner;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * 按「级别 + 键」限流的日志助手。
 *
 * <p>同一个键在同级别下最多输出一次，避免损坏的第三方集成在每轮扫描或每次
 * {@code rescan-all} 时刷屏。键集合使用并发集合，因此静态实例可以安全地跨读取器实例共享。</p>
 *
 * <p>该类仅为扫描器内部诊断服务；测试可以通过 {@link #emittedCount()} 与
 * {@link #hasEmitted(String, String)} 验证限流行为。</p>
 */
final class OnceLogger {

    private final Logger logger;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    /**
     * @param logger 实际输出日志的 SLF4J 记录器
     */
    OnceLogger(Logger logger) {
        this.logger = logger;
    }

    /**
     * 首次以 warn 级别输出指定键，后续重复调用不再输出。
     *
     * @param key     稳定的事件键
     * @param message 日志内容
     * @param cause   失败原因，允许为 {@code null}
     * @return 本次是否真正输出了日志
     */
    boolean warn(String key, String message, @Nullable Throwable cause) {
        if (!seen.add("W|" + key)) {
            return false;
        }
        logger.warn(message, cause);
        return true;
    }

    /**
     * 首次以 debug 级别输出指定键，后续重复调用不再输出。
     *
     * @param key     稳定的事件键
     * @param message 日志内容
     * @param cause   失败原因，允许为 {@code null}
     * @return 本次是否真正输出了日志
     */
    boolean debug(String key, String message, @Nullable Throwable cause) {
        if (!seen.add("D|" + key)) {
            return false;
        }
        logger.debug(message, cause);
        return true;
    }

    /**
     * @param level 级别标识，{@code "W"} 或 {@code "D"}
     * @param key   事件键
     * @return 该级别与键的组合是否已经输出过
     */
    boolean hasEmitted(String level, String key) {
        return seen.contains(level + "|" + key);
    }

    /**
     * @return 已记录的不同「级别 + 键」数量
     */
    int emittedCount() {
        return seen.size();
    }

    /**
     * 清空限流状态。仅供单元测试隔离使用。
     */
    void reset() {
        seen.clear();
    }
}
