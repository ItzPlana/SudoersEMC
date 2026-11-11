package com.itzplana.sudoersemc.diagnostic;

import com.itzplana.sudoersemc.storage.SudoersemcPaths;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 诊断报告的生成与持久化入口。
 *
 * <p>报告以纯文本写入 {@code config/sudoersemc/last_report.txt}，便于管理员直接打开查看，也方便
 * 日志采集工具按行解析。写入过程与 JSON 存储不同：{@link com.itzplana.sudoersemc.storage.JsonFileIO}
 * 只处理 JSON，因此这里先把内容写入 {@code last_report.txt.tmp}，再用原子移动替换目标文件；
 * 文件系统不支持原子移动时退回普通替换。</p>
 *
 * <p>所有公开方法都不会抛出异常：报告是诊断辅助信息，写入或读取失败只记录警告，绝不能因此
 * 中断扫描或服务器关闭流程。</p>
 */
public final class ReportManager {

    private static final Logger LOGGER =
            LoggerFactory.getLogger("sudoersemc/diagnostic/ReportManager");

    /** 相对于数据目录的报告文件名。 */
    public static final String FILE_NAME = "last_report.txt";

    private static final String TEMP_SUFFIX = ".tmp";

    private final Path dataDir;

    /**
     * 生产环境构造函数，使用 {@link SudoersemcPaths#dataDir()}。
     *
     * <p>构造过程不访问磁盘，目录在真正写入时才创建。</p>
     */
    public ReportManager() {
        this(SudoersemcPaths.dataDir());
    }

    /**
     * 测试/嵌入式构造函数。
     *
     * @param dataDir 报告所在目录；目录不存在时在写入时自动创建
     */
    public ReportManager(Path dataDir) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
    }

    /**
     * @return 报告文件的完整路径（文件未必存在）
     */
    public Path reportFile() {
        return dataDir.resolve(FILE_NAME);
    }

    /**
     * 把报告格式化为文本并原子替换旧报告。
     *
     * <p>{@code report} 为 {@code null} 时忽略本次调用。写入失败时清理临时文件并记录警告，
     * 旧报告保持不变。</p>
     *
     * @param report 待写入的诊断快照
     */
    public void writeReport(DiagnosticReport report) {
        if (report == null) {
            LOGGER.warn("拒绝写入 null 诊断报告");
            return;
        }
        Path target = reportFile();
        Path temp = dataDir.resolve(FILE_NAME + TEMP_SUFFIX);
        try {
            Files.createDirectories(dataDir);
            Files.writeString(
                    temp,
                    report.formatText(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException e) {
                LOGGER.debug("文件系统不支持原子替换 {}，改用普通替换", target);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("写入诊断报告 {} 失败", target, e);
            try {
                Files.deleteIfExists(temp);
            } catch (IOException | RuntimeException ignored) {
                // 临时文件残留无害，下次写入会覆盖它。
            }
        }
    }

    /**
     * 读取上一次写入的报告文本。
     *
     * @return 报告内容；文件不存在、不可读或不是普通文件时返回空 {@link Optional}
     */
    public Optional<String> readLastReport() {
        Path target = reportFile();
        try {
            if (!Files.isRegularFile(target)) {
                return Optional.empty();
            }
            return Optional.of(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("读取诊断报告 {} 失败", target, e);
            return Optional.empty();
        }
    }
}
