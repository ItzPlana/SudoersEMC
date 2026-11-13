package com.itzplana.sudoersemc.task;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Startup auto-scan decision tests for {@link AutoScanPolicy}.
 *
 * <p>Covers five states — master switch, forced rescan, never scanned, empty store and existing cache — ensuring the
 * server does not rescan redundantly at startup, nor silently skip when the cache is missing.</p>
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
