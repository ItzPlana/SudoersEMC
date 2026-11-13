package com.itzplana.sudoersemc.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.core.OverflowScope;
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

    @Test
    void captureSnapshotFailsClosedWhenConfigIsNotLoaded() {
        SudoersemcConfig.SnapshotCapture capture = SudoersemcConfig.captureSnapshot();

        assertFalse(capture.validation().valid());
        assertFalse(capture.hasSnapshot());
        assertNull(capture.snapshot(), "失败捕获不得发布任何运行时快照");
    }

    @Test
    void safeDefaultsAreCompleteAndConservative() {
        RuntimeConfigSnapshot snapshot = RuntimeConfigSnapshot.safeDefaults();

        assertEquals(0L, snapshot.generation());
        assertEquals(100, snapshot.itemsPerTick());
        assertEquals(OverflowScope.OWNED_ONLY, snapshot.overflowSettings().scope());
        assertFalse(snapshot.overflowSettings().allowProjectEFileRewrite());
        assertTrue(snapshot.generalEnabled());
        assertTrue(snapshot.solveRecipes());
        assertTrue(snapshot.generateReports());
    }
}
