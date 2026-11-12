package com.itzplana.sudoersemc.task;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次扫描的协作式取消令牌。
 *
 * <p>{@link ScanManager} 在收到 {@link ScanManager#cancel()} 时设置令牌，真实管线通过
 * {@link #isRequested()} 在长循环内部主动检查。这样取消不再只在 tick 边界生效，物品采集、
 * 配方扫描、NBT 变体生成与求解循环都可以在下一个检查点提前退出。</p>
 *
 * <p>令牌可在任意线程读取；{@link #clear()} 只应在扫描终止阶段由调度器调用，避免把已经
 * 被管线观察到的取消信号过早清除。</p>
 */
public final class ScanCancellation {

    private final AtomicBoolean requested = new AtomicBoolean(false);

    /**
     * 请求取消。可从任意线程调用；重复调用没有副作用。
     */
    public void request() {
        requested.set(true);
    }

    /**
     * 清除取消信号，供下一次扫描复用同一个令牌。
     */
    public void clear() {
        requested.set(false);
    }

    /**
     * @return 是否已经请求取消
     */
    public boolean isRequested() {
        return requested.get();
    }
}
