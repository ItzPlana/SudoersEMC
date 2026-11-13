package com.itzplana.sudoersemc.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 命令执行结果的纯数据表示，不依赖任何 Minecraft 或 Brigadier 类型。
 *
 * <p>{@code lines} 在构造时被复制为不可变列表，调用方后续修改传入集合不会影响本对象。
 * 传入 {@code null} 列表按空列表处理；列表中的 {@code null} 元素会被忽略，避免命令层在
 * 拼接文本时抛出空指针异常。{@code success} 只描述命令语义是否成功，具体提示文本仍由
 * {@code lines} 承载。</p>
 *
 * @param success 命令是否成功执行
 * @param lines   面向用户的中文输出行；永不为 {@code null}
 */
public record CommandResult(boolean success, List<String> lines) {

    public CommandResult {
        List<String> copied = new ArrayList<>();
        if (lines != null) {
            for (String line : lines) {
                if (line != null) {
                    copied.add(line);
                }
            }
        }
        lines = List.copyOf(copied);
    }

    /**
     * 创建一个成功结果。
     *
     * @param lines 输出行，可为 {@code null} 或包含 {@code null} 元素
     * @return 成功的命令结果
     */
    public static CommandResult ok(String... lines) {
        return new CommandResult(true, lines == null ? List.of() : Arrays.asList(lines));
    }

    /**
     * 创建一个成功结果。
     *
     * @param lines 输出行，可为 {@code null}
     * @return 成功的命令结果
     */
    public static CommandResult ok(List<String> lines) {
        return new CommandResult(true, lines);
    }

    /**
     * 创建一个失败结果。
     *
     * @param message 中文失败提示；{@code null} 时输出空列表
     * @return 失败的命令结果
     */
    public static CommandResult failure(String message) {
        return new CommandResult(false, message == null ? List.of() : List.of(message));
    }

    /**
     * 把所有输出行用换行符连接成单个字符串，便于测试与日志展示。
     *
     * @return 以 {@code "\n"} 分隔的文本；没有输出行时返回空串
     */
    public String joinLines() {
        return String.join("\n", lines);
    }
}
