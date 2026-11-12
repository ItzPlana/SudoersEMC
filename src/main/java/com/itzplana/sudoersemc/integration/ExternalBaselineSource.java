package com.itzplana.sudoersemc.integration;

import com.itzplana.sudoersemc.core.ItemKey;
import java.util.List;
import java.util.OptionalLong;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

/**
 * 外部 EMC 基线来源。
 *
 * <p>实现只能返回“可能存在、但当前后端尚未观察到”的保守值。所有值都会作为
 * {@link com.itzplana.sudoersemc.core.PriceSource#EXTERNAL_BASELINE} 候选进入
 * {@code ScanPipeline.addFallbackCandidates}，因此仍然会经过排除、手动值、最小 EMC、
 * 大幅降价与溢出保护，绝不能绕过这些安全层。</p>
 *
 * <p>实现必须避免抛出异常：{@link #prepare(MinecraftServer)} 与 {@link #lookup(ItemKey)}
 * 的任何失败都应在内部降级为空结果。{@link #fingerprintEntries()} 返回稳定、可排序的
 * 字符串，用于触发输入变化后的重新扫描。</p>
 */
public interface ExternalBaselineSource {

    /**
     * @return 稳定的来源 id，用于日志与指纹
     */
    String id();

    /**
     * @return 当前是否已经加载出至少一条可用基线
     */
    boolean isAvailable();

    /**
     * 在扫描采集前准备数据。允许传入 {@code null} 服务器（测试或服务器尚未就绪）。
     *
     * @param server 当前服务器，可为 {@code null}
     */
    default void prepare(@Nullable MinecraftServer server) {
    }

    /**
     * 查询单个物品的外部基线值。
     *
     * @param item 物品身份，允许为 {@code null}
     * @return 严格大于 0 的值；没有基线时为空
     */
    OptionalLong lookup(@Nullable ItemKey item);

    /**
     * @return 用于扫描指纹的稳定条目；不可用时也必须返回固定标记
     */
    List<String> fingerprintEntries();

    /**
     * @return 当前缓存的基线条目数量；默认 0，供诊断计数器使用
     */
    default int entryCount() {
        return 0;
    }

    /**
     * @return 永远不可用、永远返回空结果的共享实例
     */
    static ExternalBaselineSource unavailable() {
        return UnavailableExternalBaselineSource.INSTANCE;
    }
}
