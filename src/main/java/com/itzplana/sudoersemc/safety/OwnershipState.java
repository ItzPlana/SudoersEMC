package com.itzplana.sudoersemc.safety;

/**
 * 单个物品的归属判定结果。
 *
 * <p>归属描述的是“这个 EMC 值是否由 SudoersEMC 生成、并且仍由本模组负责”，而不是数值本身。
 * 判定时必须同时参考持久化记录中的 {@code owned} 标记以及记录值与当前值的比较结果。</p>
 *
 * <h2>为什么不能只看数值</h2>
 * <p>绝不能仅凭当前数值推断归属：外部系统（ProjectE、其他模组或管理员手工编辑）完全可能把
 * 值改成与记录相同的数字。此时数值虽然没有变化，但归属仍属于本模组；反过来，也不能因为当前
 * 值与记录不同就断言“不是我们的”，那只能说明值被外部改动过。归属的唯一可靠来源是持久化
 * 记录本身。</p>
 */
public enum OwnershipState {

    /** 已有记录、记录中 {@code owned=true}，且后端返回的当前值与记录值完全一致。 */
    OWNED_BY_US,

    /** 已有记录、记录中 {@code owned=true}，但后端返回的当前值与记录值不同，说明值被外部改动。 */
    EXTERNALLY_MODIFIED,

    /** 没有任何记录，或记录中 {@code owned=false}，即该值不由 SudoersEMC 负责。 */
    NOT_OWNED,

    /** 后端不可用，无法读取当前值，因此无法完成“记录值 vs 当前值”的比较。 */
    UNKNOWN
}
