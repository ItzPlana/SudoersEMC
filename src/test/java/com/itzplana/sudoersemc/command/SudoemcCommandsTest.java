package com.itzplana.sudoersemc.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.itzplana.sudoersemc.config.ReloadDecision;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SudoemcCommands} 的 Brigadier 命令树测试。
 *
 * <p>测试直接构造 {@link CommandDispatcher} 和权限等级不同的 {@link CommandSourceStack}，
 * 验证命令树注册、权限谓词、参数解析与 {@link SudoemcCommands#setService(CommandService)}
 * 生命周期，不启动 Forge 服务器。</p>
 */
class SudoemcCommandsTest {

    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        SudoemcCommands.setService(null);
        dispatcher = new CommandDispatcher<>();
        dispatcher.register(SudoemcCommands.register());
    }

    @AfterEach
    void tearDown() {
        SudoemcCommands.setService(null);
    }

    @Test
    void rootRegistersAllSubcommands() {
        CommandNode<CommandSourceStack> root = root();

        for (String subcommand : List.of("status", "rescan-all", "reload", "report", "explain", "cancel")) {
            assertNotNull(root.getChild(subcommand), "缺少子命令 " + subcommand);
        }
        assertNotNull(root.getChild("explain").getChild("item"), "explain 必须带 item 参数");
    }

    @Test
    void rootRequiresPermissionLevelTwo() {
        CommandNode<CommandSourceStack> root = root();

        assertTrue(root.canUse(source(2)));
        assertTrue(root.canUse(source(4)));
        assertFalse(root.canUse(source(1)));
        assertFalse(root.canUse(source(0)));
    }

    @Test
    void nonAdminCannotExecuteCommand() {
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("sudoemc status", source(1)));
    }

    @Test
    void explainUsesResourceLocationArgument() throws CommandSyntaxException {
        CommandContext<CommandSourceStack> context =
                dispatcher.parse("sudoemc explain minecraft:stone", source(2))
                        .getContext()
                        .build("sudoemc explain minecraft:stone");

        assertEquals(new ResourceLocation("minecraft", "stone"),
                ResourceLocationArgument.getId(context, "item"));
    }

    @Test
    void explainWithoutArgumentIsRejected() {
        assertThrows(CommandSyntaxException.class,
                () -> dispatcher.execute("sudoemc explain", source(2)));
    }

    @Test
    void missingServiceReportsUninitialized() throws CommandSyntaxException {
        List<String> messages = new ArrayList<>();

        int result = dispatcher.execute("sudoemc status", source(2, messages));

        assertEquals(0, result);
        assertEquals(List.of("命令服务尚未初始化"), messages);
    }

    @Test
    void setServiceLifecycleControlsExecution() throws CommandSyntaxException {
        SudoemcCommands.setService(new CommandService(null, null, null, null));
        List<String> initialized = new ArrayList<>();
        assertEquals(1, dispatcher.execute("sudoemc status", source(2, initialized)));
        assertEquals(List.of("扫描服务不可用"), initialized);

        SudoemcCommands.setService(null);
        List<String> cleared = new ArrayList<>();
        assertEquals(0, dispatcher.execute("sudoemc status", source(2, cleared)));
        assertEquals(List.of("命令服务尚未初始化"), cleared);
    }

    @Test
    void rejectedReloadReturnsFailureExitCode() throws CommandSyntaxException {
        SudoemcCommands.setService(new CommandService(
                null, null, null, null, () -> ReloadDecision.REJECT));
        List<String> messages = new ArrayList<>();

        int result = dispatcher.execute("sudoemc reload", source(2, messages));

        assertEquals(0, result);
        assertEquals(List.of("配置校验失败，已保留上一份有效配置"), messages);
    }

    private CommandNode<CommandSourceStack> root() {
        CommandNode<CommandSourceStack> root = dispatcher.getRoot().getChild(SudoemcCommands.ROOT_LITERAL);
        assertNotNull(root, "/" + SudoemcCommands.ROOT_LITERAL + " 未注册");
        return root;
    }

    private static CommandSourceStack source(int permissionLevel) {
        return source(permissionLevel, new ArrayList<>());
    }

    private static CommandSourceStack source(int permissionLevel, List<String> messages) {
        CommandSource source = new CommandSource() {
            @Override
            public void sendSystemMessage(Component component) {
                messages.add(component.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        return new CommandSourceStack(
                source,
                Vec3.ZERO,
                Vec2.ZERO,
                null,
                permissionLevel,
                "test",
                Component.literal("test"),
                null,
                null);
    }
}
