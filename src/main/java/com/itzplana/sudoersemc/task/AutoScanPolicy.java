package com.itzplana.sudoersemc.task;

/**
 * 服务器启动时自动扫描的纯策略判定。
 *
 * <p>本类不依赖 Minecraft 类型，只根据配置开关、上次扫描时刻与已生成条目数量回答
 * “本次启动是否应请求一次扫描”。实际扫描仍由 {@link ScanManager} 调度，本类不产生副作用。</p>
 */
public final class AutoScanPolicy {

    private AutoScanPolicy() {
    }

    /**
     * 判断服务器启动时是否应请求自动扫描。
     *
     * <p>判定顺序固定：</p>
     * <ol>
     *   <li>{@code enabled == false}：自动扫描总开关关闭，返回 {@code false}；</li>
     *   <li>{@code alwaysRescan == true}：管理员要求每次启动都重扫，返回 {@code true}；</li>
     *   <li>{@code lastScanEpochMillis <= 0}：从未扫描过，返回 {@code true}；</li>
     *   <li>{@code generatedEntryCount <= 0}：没有任何已生成条目（例如缓存缺失或被清空），
     *       返回 {@code true}；</li>
     *   <li>其余情况视为已有可用缓存，返回 {@code false}。</li>
     * </ol>
     *
     * @param enabled             自动扫描总开关
     * @param alwaysRescan        是否每次启动都强制重扫
     * @param lastScanEpochMillis 上次扫描完成的 epoch 毫秒，{@code 0} 表示从未扫描
     * @param generatedEntryCount 当前已生成条目数量
     * @return 应请求自动扫描时返回 {@code true}
     */
    public static boolean shouldScan(boolean enabled, boolean alwaysRescan,
                                     long lastScanEpochMillis, int generatedEntryCount) {
        // 旧调用点无法计算指纹，保持 Phase 22 的行为：有缓存即跳过。
        return shouldScan(enabled, alwaysRescan, lastScanEpochMillis, generatedEntryCount, true);
    }

    /**
     * 判断服务器启动时是否应请求自动扫描，并把当前输入指纹是否变化纳入判定。
     *
     * <p>判定顺序固定：</p>
     * <ol>
     *   <li>{@code enabled == false}：自动扫描总开关关闭，返回 {@code false}；</li>
     *   <li>{@code alwaysRescan == true}：管理员要求每次启动都重扫，返回 {@code true}；</li>
     *   <li>{@code lastScanEpochMillis <= 0}：从未扫描过，返回 {@code true}；</li>
     *   <li>{@code generatedEntryCount <= 0}：没有任何已生成条目，返回 {@code true}；</li>
     *   <li>{@code fingerprintsMatch == false}：物品、配方、标签、配置或外部基线发生变化，
     *       返回 {@code true}；</li>
     *   <li>其余情况视为输入未变化且已有可用缓存，返回 {@code false}。</li>
     * </ol>
     *
     * <p>指纹计算失败时调用方应传入 {@code false}，让本方法退化为“需要扫描”，绝不阻止启动。</p>
     *
     * @param enabled              自动扫描总开关
     * @param alwaysRescan         是否每次启动都强制重扫
     * @param lastScanEpochMillis  上次扫描完成的 epoch 毫秒，{@code 0} 表示从未扫描
     * @param generatedEntryCount  当前已生成条目数量
     * @param fingerprintsMatch    当前输入指纹是否与保存的指纹完全一致
     * @return 应请求自动扫描时返回 {@code true}
     */
    public static boolean shouldScan(boolean enabled, boolean alwaysRescan,
                                     long lastScanEpochMillis, int generatedEntryCount,
                                     boolean fingerprintsMatch) {
        if (!enabled) {
            return false;
        }
        if (alwaysRescan) {
            return true;
        }
        if (lastScanEpochMillis <= 0L) {
            return true;
        }
        if (generatedEntryCount <= 0) {
            return true;
        }
        return !fingerprintsMatch;
    }
}
