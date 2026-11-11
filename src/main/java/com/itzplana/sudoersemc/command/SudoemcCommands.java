package com.itzplana.sudoersemc.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.function.Function;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * {@code /sudoemc} 命令的 Brigadier 注册薄层。
 *
 * <p>本类只负责参数解析、权限检查和文本输出，所有业务判断都委托给 {@link CommandService}。
 * 所有子命令都要求权限等级 2（管理员），并且不会阻塞服务器线程：状态查询与报告读取只访问
 * 内存数据或已经落盘的文本，全量扫描也只是提交请求，由 {@code ScanManager} 在后续 tick 中
 * 分片执行。</p>
 *
 * <p>命令服务由模组入口在初始化阶段通过 {@link #setService(CommandService)} 注入。未注入时
 * 每个子命令都会输出“命令服务尚未初始化”并返回 0，而不是抛出空指针异常。</p>
 */
public final class SudoemcCommands {

    /** 命令根节点名称。 */
    public static final String ROOT_LITERAL = "sudoemc";

    /** 所有子命令要求的最小权限等级：2 代表管理员。 */
    public static final int REQUIRED_PERMISSION_LEVEL = 2;

    /** 由模组入口注入的命令服务；使用 volatile 保证跨线程可见性。 */
    private static volatile CommandService service;

    private SudoemcCommands() {
    }

    /**
     * 注入命令服务。允许传入 {@code null}，用于关闭或重新初始化命令层。
     *
     * @param newService 命令服务实例，可为 {@code null}
     */
    public static void setService(@Nullable CommandService newService) {
        service = newService;
    }

    /**
     * 构造命令根节点及其全部子命令。
     *
     * @return 可直接注册到 Brigadier 调度器的根节点
     */
    public static LiteralArgumentBuilder<CommandSourceStack> register() {
        return Commands.literal(ROOT_LITERAL)
                .requires(source -> source.hasPermission(REQUIRED_PERMISSION_LEVEL))
                .then(Commands.literal("status")
                        .executes(context -> execute(context.getSource(), CommandService::status)))
                .then(Commands.literal("rescan-all")
                        .executes(context -> execute(context.getSource(), CommandService::rescanAll)))
                .then(Commands.literal("reload")
                        .executes(context -> execute(context.getSource(), CommandService::reloadConfig)))
                .then(Commands.literal("report")
                        .executes(context -> execute(context.getSource(), CommandService::report)))
                .then(Commands.literal("explain")
                        .then(Commands.argument("item", StringArgumentType.string())
                                .executes(SudoemcCommands::explain)))
                .then(Commands.literal("cancel")
                        .executes(context -> execute(context.getSource(), CommandService::cancel)));
    }

    /**
     * 解析 {@code explain} 的物品参数并委托给命令服务。
     */
    private static int explain(CommandContext<CommandSourceStack> context) {
        String item = StringArgumentType.getString(context, "item");
        return execute(context.getSource(), commandService -> commandService.explain(item));
    }

    /**
     * 统一执行命令动作，负责空服务检查、异常兜底与文本输出。
     *
     * @param source 命令来源
     * @param action 需要执行的命令服务动作
     * @return 成功返回 {@link Command#SINGLE_SUCCESS}，失败返回 0
     */
    private static int execute(CommandSourceStack source,
                               Function<CommandService, CommandResult> action) {
        CommandService current = service;
        if (current == null) {
            send(source, "命令服务尚未初始化");
            return 0;
        }

        try {
            CommandResult result = action.apply(current);
            if (result == null) {
                send(source, "命令执行失败: NullPointerException");
                return 0;
            }
            for (String line : result.lines()) {
                send(source, line);
            }
            return result.success() ? Command.SINGLE_SUCCESS : 0;
        } catch (RuntimeException e) {
            String name = e.getClass().getSimpleName();
            if (name.isEmpty()) {
                name = e.getClass().getName();
            }
            send(source, "命令执行失败: " + name);
            return 0;
        }
    }

    /**
     * 向命令来源发送一行反馈文本。
     */
    private static void send(CommandSourceStack source, String line) {
        String safeLine = line == null ? "" : line;
        source.sendSuccess(() -> Component.literal(safeLine), false);
    }
}
