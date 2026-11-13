package com.itzplana.sudoersemc;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.diagnostic.DebugLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 验证运行时重建使用的调试开关接线。{@link Sudoersemc#applyDebugLogging(boolean)} 是
 * {@code createScanPipeline} 调用的同一个辅助方法。
 */
class DebugLogRebuildTest {

    @AfterEach
    void tearDown() {
        Sudoersemc.applyDebugLogging(false);
    }

    @Test
    void debugToggleFromConfigRebuild() {
        Sudoersemc.applyDebugLogging(true);
        assertTrue(DebugLog.isEnabled());

        Sudoersemc.applyDebugLogging(false);
        assertFalse(DebugLog.isEnabled());
    }
}
