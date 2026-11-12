package com.itzplana.sudoersemc.command;

import com.itzplana.sudoersemc.config.ReloadDecision;

/**
 * 配置重载动作。
 *
 * <p>命令层通过该接口与运行时重建解耦：生产环境注入模组入口的重建逻辑，单元测试
 * 可以直接返回期望的 {@link ReloadDecision}。实现方不得抛出受检异常；运行时异常会
 * 被 {@link CommandService#reloadConfig()} 转换为统一的失败结果。</p>
 */
@FunctionalInterface
public interface ReloadAction {

    /**
     * 执行一次配置重载。
     *
     * @return 处理决策；返回 {@code null} 时命令层按 {@link ReloadDecision#REJECT} 处理
     */
    ReloadDecision reload();
}
