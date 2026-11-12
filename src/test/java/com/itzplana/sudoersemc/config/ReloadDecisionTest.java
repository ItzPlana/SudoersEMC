package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link ReloadDecision} 的纯逻辑测试。
 *
 * <p>决策只依赖扫描状态与校验结果，不涉及 Forge 配置或服务器对象。</p>
 */
class ReloadDecisionTest {

    @Test
    void validConfigAppliesImmediatelyWhenScanIsIdle() {
        assertEquals(ReloadDecision.APPLY_NOW, ReloadDecision.decide(false, true));
    }

    @Test
    void validConfigDefersWhileScanIsRunning() {
        assertEquals(ReloadDecision.DEFER, ReloadDecision.decide(true, true));
    }

    @Test
    void invalidConfigIsRejectedRegardlessOfScanState() {
        assertEquals(ReloadDecision.REJECT, ReloadDecision.decide(false, false));
        assertEquals(ReloadDecision.REJECT, ReloadDecision.decide(true, false));
    }
}
