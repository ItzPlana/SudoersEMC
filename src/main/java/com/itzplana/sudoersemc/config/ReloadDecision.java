package com.itzplana.sudoersemc.config;

/**
 * 一次配置重载请求的处理决策。
 *
 * <p>校验与扫描状态共同决定结果：校验失败时保留上一份有效配置；扫描进行中时把重建
 * 推迟到扫描结束；两者都允许时才立即替换运行时组件。本枚举是纯逻辑，不依赖 Forge
 * 或服务器状态，因此可以脱离游戏环境做单元测试。</p>
 */
public enum ReloadDecision {

    /** 校验通过且没有扫描在运行，可以立即重建运行时组件。 */
    APPLY_NOW,

    /** 校验通过但扫描正在进行，重建推迟到扫描结束后执行。 */
    DEFER,

    /** 校验失败，保留现有组件并继续使用上一份有效配置。 */
    REJECT;

    /**
     * 根据扫描状态与校验结果选择处理方式。
     *
     * <p>校验失败永远优先于扫描状态：即使扫描正在进行，也不应把非法配置挂起等待。</p>
     *
     * @param scanRunning     是否有扫描正在进行
     * @param validationPassed 配置是否通过校验
     * @return 对应的处理决策，永不为 {@code null}
     */
    public static ReloadDecision decide(boolean scanRunning, boolean validationPassed) {
        if (!validationPassed) {
            return REJECT;
        }
        return scanRunning ? DEFER : APPLY_NOW;
    }
}
