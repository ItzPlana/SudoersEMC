package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link SudoersemcConfig} 的失败关闭行为测试。
 *
 * <p>测试不启动 Forge，配置 spec 处于未加载状态。此时 {@code reloadFromDisk()} 必须返回
 * 明确的失败，而不是像旧实现那样报告成功。</p>
 */
class SudoersemcConfigTest {

    @Test
    void reloadFromDiskFailsWhenConfigIsNotLoaded() {
        ConfigValidationResult result = SudoersemcConfig.reloadFromDisk();

        assertFalse(result.valid());
        assertEquals(List.of("配置尚未加载"), result.errors());
    }
}
