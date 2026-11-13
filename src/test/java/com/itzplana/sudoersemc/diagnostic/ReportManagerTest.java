package com.itzplana.sudoersemc.diagnostic;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ReportManager} 的单元测试。
 *
 * <p>验证纯文本报告的写入/读取往返、覆盖写入、缺失文件处理，以及报告文件确实位于调用方
 * 指定的数据目录中。</p>
 */
class ReportManagerTest {

    @TempDir
    Path tempDir;

    private static DiagnosticReport report(long totalRegisteredItems) {
        return new DiagnosticReport(
                totalRegisteredItems,
                0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
                Map.of());
    }

    @Test
    void writeThenReadRoundTrip() {
        ReportManager manager = new ReportManager(tempDir);
        DiagnosticReport report = report(1234L);

        manager.writeReport(report);

        Optional<String> text = manager.readLastReport();
        assertTrue(text.isPresent());
        assertEquals(report.formatText(), text.orElseThrow());
    }

    @Test
    void readLastReportIsEmptyWhenFileAbsent() {
        ReportManager manager = new ReportManager(tempDir);

        assertTrue(manager.readLastReport().isEmpty());
        assertFalse(Files.exists(manager.reportFile()));
    }

    @Test
    void writingOverExistingReportReplacesIt() {
        ReportManager manager = new ReportManager(tempDir);

        manager.writeReport(report(1111L));
        manager.writeReport(report(2222L));

        String text = manager.readLastReport().orElseThrow();
        assertTrue(text.contains("2222"));
        assertFalse(text.contains("1111"));
        assertTrue(text.contains("SudoersEMC 诊断报告"));
    }

    @Test
    void reportFileLivesInSuppliedDirectory() {
        ReportManager manager = new ReportManager(tempDir);

        assertEquals(tempDir.resolve(ReportManager.FILE_NAME), manager.reportFile());
        assertEquals(tempDir, manager.reportFile().getParent());

        manager.writeReport(report(1L));

        assertTrue(Files.isRegularFile(manager.reportFile()));
        assertTrue(Files.exists(tempDir.resolve(ReportManager.FILE_NAME)));
    }

    @Test
    void writeReportIgnoresNull() {
        ReportManager manager = new ReportManager(tempDir);

        assertDoesNotThrow(() -> manager.writeReport(null));
        assertFalse(Files.exists(manager.reportFile()));
    }

    @Test
    void concurrentWritesLeaveOneCompleteReport() throws Exception {
        ReportManager manager = new ReportManager(tempDir);
        int writers = 8;
        int writesPerWriter = 20;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int writer = 0; writer < writers; writer++) {
                long base = writer * 1000L;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < writesPerWriter; i++) {
                        manager.writeReport(report(base + i));
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        String text = manager.readLastReport().orElseThrow();
        assertTrue(text.startsWith("===== SudoersEMC 诊断报告 ====="));
        assertTrue(text.endsWith("================================"));
        assertFalse(Files.exists(tempDir.resolve(ReportManager.FILE_NAME + ".tmp")),
                "写入完成后不应残留共享临时文件");
    }
}
