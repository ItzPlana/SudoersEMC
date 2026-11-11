package com.itzplana.sudoersemc.config;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 配置重载协调器。
 *
 * <p>校验通过时通知所有已注册的监听器重建组件；校验失败时保留旧组件实例，
 * 只记录警告，避免用非法配置重建缓存或采样器。</p>
 *
 * <p>监听器列表使用写时复制，允许在回调期间注册新监听器；单个监听器抛出的
 * 异常会被捕获并记录，不会影响其余监听器。{@link #lastResult()} 使用
 * {@code volatile} 保存，可安全地从其他线程读取。</p>
 */
public final class ConfigWatcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigWatcher.class);

    /**
     * 配置重载监听器。
     *
     * <p>只在 {@link ConfigValidationResult#valid()} 为 {@code true} 时被调用；
     * 实现方应保证回调快速返回，不要在回调中执行耗时扫描。</p>
     */
    @FunctionalInterface
    public interface RebuildListener {

        /**
         * 在配置通过校验后触发组件重建。
         *
         * @param result 通过校验的结果，可能包含警告
         */
        void onReload(ConfigValidationResult result);
    }

    private final List<RebuildListener> listeners = new CopyOnWriteArrayList<>();
    private final Set<String> alreadyLogged = ConcurrentHashMap.newKeySet();

    private volatile ConfigValidationResult lastResult = ConfigValidationResult.ok();

    /**
     * 注册重载监听器。
     *
     * @param listener 监听器，不能为 {@code null}
     * @throws NullPointerException 当 {@code listener} 为 {@code null} 时
     */
    public void register(RebuildListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 记录一次配置校验结果并在通过时通知监听器。
     *
     * <p>无论结果是否合法都会更新 {@link #lastResult()}。结果非法时不调用任何
     * 监听器，保留现有组件实例，并只记录一条汇总警告。</p>
     *
     * @param result 校验结果，不能为 {@code null}
     * @throws NullPointerException 当 {@code result} 为 {@code null} 时
     */
    public void fireReload(ConfigValidationResult result) {
        Objects.requireNonNull(result, "result");
        lastResult = result;

        if (!result.valid()) {
            LOGGER.warn("[config] 配置校验未通过，保留现有组件实例，继续使用上一个有效配置：\n{}",
                    result.format());
            return;
        }

        for (RebuildListener listener : listeners) {
            try {
                listener.onReload(result);
            } catch (Exception exception) {
                LOGGER.warn("[config] 配置重载监听器抛出异常，已忽略该监听器", exception);
            }
        }
    }

    /**
     * 记录校验结果中的冲突警告。
     *
     * <p>同一条警告文本在本实例生命周期内只记录一次，重复调用不会刷屏。</p>
     *
     * @param result 校验结果，不能为 {@code null}
     * @throws NullPointerException 当 {@code result} 为 {@code null} 时
     */
    public void logConflictWarnings(ConfigValidationResult result) {
        Objects.requireNonNull(result, "result");
        for (String warning : result.warnings()) {
            if (alreadyLogged.add(warning)) {
                LOGGER.warn("[config] {}", warning);
            }
        }
    }

    /**
     * @return 最近一次收到的校验结果；初始为通过且无警告的结果，永不为 {@code null}
     */
    public ConfigValidationResult lastResult() {
        return lastResult;
    }

    /**
     * @return 当前已注册的监听器数量
     */
    public int listenerCount() {
        return listeners.size();
    }

    /**
     * 已记录过的不同警告文本数量，供诊断与测试观察去重状态。
     *
     * @return 去重集合的大小
     */
    int loggedWarningCount() {
        return alreadyLogged.size();
    }
}
