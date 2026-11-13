package com.itzplana.sudoersemc.command;

/**
 * 隔离降价审批动作。
 *
 * <p>命令层只负责参数解析与文本输出，真正的“批准持久化记录 → 应用拟写入值 → 落盘”由模组入口
 * 注入的实现完成。实现必须返回 {@link CommandResult}，不得把异常传播到 Brigadier。</p>
 */
@FunctionalInterface
public interface QuarantineApprovalAction {

    /**
     * 批准并应用某个物品当前的隔离降价。
     *
     * @param itemId 物品 id 原文，例如 {@code minecraft:stone}
     * @return 命令回执；不能为 {@code null}
     */
    CommandResult approve(String itemId);
}
