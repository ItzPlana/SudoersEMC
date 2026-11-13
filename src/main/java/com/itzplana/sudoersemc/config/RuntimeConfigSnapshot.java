package com.itzplana.sudoersemc.config;

import com.itzplana.sudoersemc.pricing.PricingSettings;
import com.itzplana.sudoersemc.safety.CreativeItemProtection;
import com.itzplana.sudoersemc.safety.LargeDropProtection;
import com.itzplana.sudoersemc.safety.OverflowProtection;
import com.itzplana.sudoersemc.solver.SolveSettings;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 一次配置读取并通过校验后得到的不可变运行时快照。
 *
 * <p>所有从配置派生的运行时组件只允许消费本快照，不得在构建过程中再次读取
 * {@code ForgeConfigSpec}。这样即使文件监听线程在重建期间接受了一个新配置，单条
 * {@code ScanPipeline} / {@code ScanManager} / {@code CommandService} 也只会看到同一个
 * 配置版本，不会出现“溢出作用域来自新版本、安全阈值来自旧版本”的混用。</p>
 *
 * <p>{@link #generation()} 在每次成功捕获时递增。入口层用它去重同一配置事件产生的多次
 * 重建请求，并让命令重载与扫描请求在同一把运行时锁下串行化。</p>
 *
 * @param generation                   快照代次；{@code 0} 仅用于首次加载失败时的保守默认值
 * @param values                       已通过 {@link ConfigValidator} 完整校验的普通值
 * @param overflowSettings             溢出保护配置
 * @param dropProtectionSettings       大幅降价保护配置
 * @param creativeProtectionSettings   创造物品保护配置
 * @param pricingSettings              兜底定价开关
 * @param solveSettings                配方求解参数
 * @param itemsPerTick                 服务端线程每 tick 最多处理的物品数
 * @param adaptiveBatchSizing          是否启用自适应批量
 * @param generalEnabled               是否创建扫描调度器
 * @param alwaysRescan                 服务器启动时是否强制重扫
 * @param solveRecipes                 是否启用配方求解
 * @param generateReports              是否写出诊断报告
 * @param nbtScanVariants              是否扫描 NBT 变体
 * @param maxNbtVariantsPerItem        每个物品最多扫描的 NBT 变体数
 */
public record RuntimeConfigSnapshot(
        long generation,
        ConfigValidator.Values values,
        OverflowProtection.Settings overflowSettings,
        LargeDropProtection.Settings dropProtectionSettings,
        CreativeItemProtection.Settings creativeProtectionSettings,
        PricingSettings pricingSettings,
        SolveSettings solveSettings,
        int itemsPerTick,
        boolean adaptiveBatchSizing,
        boolean generalEnabled,
        boolean alwaysRescan,
        boolean solveRecipes,
        boolean generateReports,
        boolean nbtScanVariants,
        int maxNbtVariantsPerItem
) {

    public RuntimeConfigSnapshot {
        if (generation < 0L) {
            throw new IllegalArgumentException("generation 必须 >= 0，实际为 " + generation);
        }
        if (itemsPerTick < 1) {
            throw new IllegalArgumentException("itemsPerTick 必须 >= 1，实际为 " + itemsPerTick);
        }
        if (maxNbtVariantsPerItem < 1) {
            throw new IllegalArgumentException(
                    "maxNbtVariantsPerItem 必须 >= 1，实际为 " + maxNbtVariantsPerItem);
        }
        Objects.requireNonNull(values, "values");
        Objects.requireNonNull(overflowSettings, "overflowSettings");
        Objects.requireNonNull(dropProtectionSettings, "dropProtectionSettings");
        Objects.requireNonNull(creativeProtectionSettings, "creativeProtectionSettings");
        Objects.requireNonNull(pricingSettings, "pricingSettings");
        Objects.requireNonNull(solveSettings, "solveSettings");
    }

    /**
     * 配置尚未加载或首次加载即非法时使用的保守默认值。
     *
     * <p>该快照不来自任何 Forge 值，因此即使配置读取失败，模组仍能以与旧行为一致的安全
     * 默认值启动；修正配置文件后，配置事件会捕获新的有效快照并替换它。</p>
     *
     * @return generation 为 {@code 0} 的保守默认快照
     */
    public static RuntimeConfigSnapshot safeDefaults() {
        ConfigValidator.Values values = new ConfigValidator.Values(
                100,
                SolveSettings.DEFAULT_MAX_PASSES,
                SolveSettings.DEFAULT_MINIMUM_EMC,
                true,
                OverflowProtection.DEFAULT_THRESHOLD,
                OverflowProtection.DEFAULT_RESET_MIN,
                OverflowProtection.DEFAULT_RESET_MAX,
                OverflowProtection.DEFAULT_BUCKET_COUNT,
                OverflowProtection.DEFAULT_WEIGHT_CAP,
                "owned_only",
                false,
                100,
                0.5D,
                List.of(),
                List.of(),
                List.of());
        return new RuntimeConfigSnapshot(
                0L,
                values,
                new OverflowProtection.Settings(),
                LargeDropProtection.Settings.defaults(),
                new CreativeItemProtection.Settings(true, Set.of(), Map.of(), Set.of()),
                PricingSettings.defaults(),
                SolveSettings.defaults(),
                values.itemsPerTick(),
                true,
                true,
                false,
                true,
                true,
                true,
                100);
    }

    /**
     * 返回一个仅代次不同的副本，供测试构造连续配置事件。
     *
     * @param newGeneration 新代次，必须 {@code >= 0}
     * @return 其余字段完全相同的新快照
     */
    public RuntimeConfigSnapshot withGeneration(long newGeneration) {
        return new RuntimeConfigSnapshot(
                newGeneration,
                values,
                overflowSettings,
                dropProtectionSettings,
                creativeProtectionSettings,
                pricingSettings,
                solveSettings,
                itemsPerTick,
                adaptiveBatchSizing,
                generalEnabled,
                alwaysRescan,
                solveRecipes,
                generateReports,
                nbtScanVariants,
                maxNbtVariantsPerItem);
    }
}
