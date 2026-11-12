package com.itzplana.sudoersemc.safety;

import com.itzplana.sudoersemc.core.GeneratedEmcEntry;
import com.itzplana.sudoersemc.core.ItemKey;
import com.itzplana.sudoersemc.core.PriceSource;
import com.itzplana.sudoersemc.storage.GeneratedEntryStore;
import com.itzplana.sudoersemc.storage.QuarantinedDropsStore.QuarantinedDrop;
import java.util.List;
import java.util.function.Predicate;
import org.jetbrains.annotations.Nullable;

/**
 * 把管理员批准的隔离降价写入生成条目存储的纯逻辑组件。
 *
 * <p>本类不依赖 Minecraft 服务器、命令层或 ProjectE，因此可以在单元测试中直接验证全部安全
 * 规则。调用顺序是固定的，任何一项拒绝都会阻止写入：</p>
 *
 * <ol>
 *   <li>已批准记录不重复应用；</li>
 *   <li>存储不可用拒绝；</li>
 *   <li>配置关闭立即应用时拒绝；</li>
 *   <li>拟应用值必须为正；</li>
 *   <li>拟应用值不得低于最小 EMC；</li>
 *   <li>ProjectE 手动值（含判定器缺失/异常）一律 fail-closed；</li>
 *   <li>被排除规则命中的物品拒绝，因为下一次扫描会清理它；</li>
 *   <li>最后才写入 {@link PriceSource#APPROVED} 条目。</li>
 * </ol>
 */
public final class ApprovedDropApplier {

    /** 申请结果状态。 */
    public enum Status {
        /** 已写入生成条目存储。 */
        APPLIED,
        /** 隔离记录此前已经批准，未重复写入。 */
        ALREADY_APPLIED,
        /** 物品存在 ProjectE 手动值，或无法确认是否为手动值。 */
        REFUSED_MANUAL,
        /** 物品被定价排除规则命中。 */
        REFUSED_EXCLUDED,
        /** 配置关闭了“批准后立即应用”。 */
        REFUSED_DISABLED,
        /** 拟应用值低于配置的最小 EMC。 */
        REFUSED_BELOW_MIN,
        /** 隔离记录或拟应用值无效。 */
        REFUSED_INVALID,
        /** 生成条目存储不可用。 */
        STORAGE_UNAVAILABLE
    }

    /**
     * 一次申请的结果。
     *
     * @param status  结果状态
     * @param applied 成功写入的条目；其它状态为 {@code null}
     * @param message 面向管理员的中文说明；可为 {@code null}
     */
    public record Result(Status status, @Nullable GeneratedEmcEntry applied,
                         @Nullable String message) {

        public Result {
            if (status == null) {
                throw new IllegalArgumentException("status 不能为 null");
            }
        }
    }

    private ApprovedDropApplier() {
    }

    /**
     * 尝试把一条隔离降价应用为 {@link PriceSource#APPROVED} 条目。
     *
     * @param drop              隔离记录，可为 {@code null}
     * @param store             生成条目存储；{@code null} 返回 {@link Status#STORAGE_UNAVAILABLE}
     * @param applyEnabled      配置开关；{@code false} 时只允许标记审批，不写入数值
     * @param minimumEmc        允许写入的最低 EMC
     * @param manualDetector    ProjectE 手动值判定器；{@code null} 视为不可用并 fail-closed
     * @param exclusionDetector 定价排除判定器；{@code null} 视为不排除
     * @return 结果；永不抛出异常
     */
    public static Result apply(@Nullable QuarantinedDrop drop,
                               @Nullable GeneratedEntryStore store,
                               boolean applyEnabled,
                               long minimumEmc,
                               @Nullable Predicate<ItemKey> manualDetector,
                               @Nullable Predicate<ItemKey> exclusionDetector) {
        if (drop == null || drop.key() == null) {
            return refused(Status.REFUSED_INVALID, "隔离记录为空或缺少物品身份");
        }
        if (drop.approved()) {
            return refused(Status.ALREADY_APPLIED, "该隔离记录已批准过，未重复应用");
        }
        if (store == null) {
            return refused(Status.STORAGE_UNAVAILABLE, "生成条目存储不可用");
        }
        if (!applyEnabled) {
            return refused(Status.REFUSED_DISABLED,
                    "已禁用批准后立即应用（safety.approveAppliesImmediately=false）");
        }

        long proposedValue = drop.proposedValue();
        if (proposedValue <= 0L) {
            return refused(Status.REFUSED_INVALID,
                    "拟应用值无效: " + proposedValue + "（必须大于 0）");
        }
        if (proposedValue < minimumEmc) {
            return refused(Status.REFUSED_BELOW_MIN,
                    "拟应用值 " + proposedValue + " 低于最小 EMC " + minimumEmc);
        }

        ItemKey key = drop.key();
        if (manualDetector == null) {
            return refused(Status.REFUSED_MANUAL,
                    "无法确认 ProjectE 手动值状态，拒绝自动应用（fail-closed）");
        }
        boolean manuallySet;
        try {
            manuallySet = manualDetector.test(key);
        } catch (RuntimeException | LinkageError failure) {
            return refused(Status.REFUSED_MANUAL,
                    "ProjectE 手动值判定失败，按手动值保护处理（" + failure.getClass().getSimpleName() + "）");
        }
        if (manuallySet) {
            return refused(Status.REFUSED_MANUAL,
                    "该物品存在 ProjectE 手动值，SudoersEMC 不会覆盖");
        }

        if (exclusionDetector != null) {
            boolean excluded;
            try {
                excluded = exclusionDetector.test(key);
            } catch (RuntimeException | LinkageError failure) {
                // 排除规则不可判定时按排除处理，避免写入会被下一次扫描清理的数值。
                excluded = true;
            }
            if (excluded) {
                return refused(Status.REFUSED_EXCLUDED,
                        "该物品被排除规则命中；批准后会被下次扫描清理，请先调整排除配置");
            }
        }

        GeneratedEmcEntry entry = new GeneratedEmcEntry(
                key,
                proposedValue,
                PriceSource.APPROVED,
                null,
                true,
                System.currentTimeMillis(),
                List.of());
        try {
            if (!store.stage(entry)) {
                return refused(Status.REFUSED_INVALID,
                        "生成条目存储拒绝了写入（目标条目可能不属于 SudoersEMC）");
            }
        } catch (RuntimeException failure) {
            return refused(Status.STORAGE_UNAVAILABLE,
                    "写入生成条目存储失败: " + failure.getClass().getSimpleName());
        }
        return new Result(Status.APPLIED, entry,
                "已应用 " + proposedValue + "（来源 APPROVED）");
    }

    private static Result refused(Status status, String message) {
        return new Result(status, null, message);
    }
}
