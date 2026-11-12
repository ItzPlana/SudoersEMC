package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link AutoScanPolicy} 的启动自动扫描判定测试。
 *
 * <p>覆盖总开关、强制重扫、从未扫描、空存储与已有缓存五类状态，确保服务器启动时不会
 * 重复扫描，也不会在缺少缓存时静默跳过。</p>
 */
class AutoScanPolicyTest {

    @Test
    void disabledSwitchNeverScans() {
        assertFalse(AutoScanPolicy.shouldScan(false, false, 0L, 0),
                "总开关关闭时，即使从未扫描也不得自动扫描");
        assertFalse(AutoScanPolicy.shouldScan(false, true, 123L, 456),
                "总开关关闭时，alwaysRescan 也必须被忽略");
    }

    @Test
    void alwaysRescanWinsOverExistingCache() {
        assertTrue(AutoScanPolicy.shouldScan(true, true, 123L, 456),
                "alwaysRescan=true 时每次启动都应重扫");
    }

    @Test
    void neverScannedServerRequestsScan() {
        assertTrue(AutoScanPolicy.shouldScan(true, false, 0L, 456),
                "上次扫描时刻为 0 表示从未扫描，应请求扫描");
    }

    @Test
    void emptyGeneratedStoreRequestsScan() {
        assertTrue(AutoScanPolicy.shouldScan(true, false, 123L, 0),
                "没有任何已生成条目时应请求扫描");
    }

    @Test
    void existingCacheWithoutAlwaysRescanSkipsScan() {
        assertFalse(AutoScanPolicy.shouldScan(true, false, 123L, 456),
                "已有扫描时刻与生成条目时默认复用缓存");
    }

    @Test
    void matchingFingerprintSkipsScan() {
        assertFalse(AutoScanPolicy.shouldScan(true, false, 123L, 456, true),
                "输入指纹完全一致时应跳过重复扫描");
    }

    @Test
    void changedFingerprintForcesScan() {
        assertTrue(AutoScanPolicy.shouldScan(true, false, 123L, 456, false),
                "任一输入通道变化都必须重新扫描");
    }

    @Test
    void alwaysRescanIgnoresMatchingFingerprint() {
        assertTrue(AutoScanPolicy.shouldScan(true, true, 123L, 456, true),
                "alwaysRescan=true 时即使指纹一致也要重扫");
    }
}
