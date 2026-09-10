package io.github.agentassert4j.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CLI↔MCP 面一致性契约测试 — MCP 工具面是 CLI 命令面的完整封装，这层封装
 * 关系以每个 McpTool 显式声明的 cliCommands 为真源。本测试双向钉死：
 * ①每个可执行 CLI 命令都被某个工具声明（漏声明 = MCP 面缺能力，当场红）；
 * ②每条声明都指向真实的 picocli 命令路径（防声明拼错）；
 * ③被声明命令的 CLI 参数被对应工具的入参覆盖（豁免显式登记）。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class CliMcpParityTest {

    /** CLI 专属命令（封装无意义：completion 出补全脚本，mcp 本身就是 server）。 */
    private static final Set<String> CLI_ONLY = new HashSet<>(Arrays.asList("completion", "mcp"));

    /** 纯父容器命令（自身不可执行，子命令各自覆盖）。 */
    private static final Set<String> PARENT_CONTAINERS = new HashSet<>(Arrays.asList("graph", "record"));

    /** 全局参数豁免：--db 由 server/调用方绑定、--json 是 MCP 原生形态、--help/-V/--version 是 picocli 内建。 */
    private static final Set<String> GLOBAL_OPTION_EXCEPTIONS = new HashSet<>(Arrays.asList("--db", "--json", "--help", "-h", "-V", "--version"));

    /** 命令级参数豁免（模式开关折叠为工具本身的存在，参数无对应物）。 */
    private static final Map<String, Set<String>> OPTION_EXCEPTIONS = new HashMap<>();

    static {
        OPTION_EXCEPTIONS.put("replay", new HashSet<>(Arrays.asList("--ci", "--re-drive", "--member-check")));
    }

    /** picocli 枚举：根命令的全部子命令路径（别名按命令实例去重，含嵌套子命令）。 */
    private static Map<String, CommandLine> cliCommands() {
        Map<Object, String> canonicalByInstance = new LinkedHashMap<>();
        Map<String, CommandLine> result = new LinkedHashMap<>();
        CommandLine root = new CommandLine(new AgentAssert4jCli());
        for (Map.Entry<String, CommandLine> entry : root.getSubcommands().entrySet()) {
            Object instance = entry.getValue().getCommand();
            if (!canonicalByInstance.containsKey(instance)) {
                canonicalByInstance.put(instance, entry.getKey());
                result.put(entry.getKey(), entry.getValue());
                for (Map.Entry<String, CommandLine> sub : entry.getValue().getSubcommands().entrySet()) {
                    result.put(entry.getKey() + " " + sub.getKey(), sub.getValue());
                }
            }
        }
        return result;
    }

    /** 工具注册表：name → 声明的 CLI 命令路径集合。 */
    private static Map<String, Set<String>> declaredCliCommandsByTool() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        for (McpTool tool : McpTools.tools(null)) {
            result.put(tool.name, new HashSet<>(tool.cliCommands));
        }
        return result;
    }

    /** 全部工具声明的 CLI 命令路径并集。 */
    private static Set<String> allDeclaredPaths() {
        Set<String> paths = new HashSet<>();
        for (Set<String> cmds : declaredCliCommandsByTool().values()) {
            paths.addAll(cmds);
        }
        return paths;
    }

    @Test
    @DisplayName("每个可执行 CLI 命令都被某个工具声明（漏声明 = MCP 面缺能力）")
    void everyExecutableCliCommandIsDeclared() {
        List<String> gaps = new ArrayList<>();
        for (Map.Entry<String, CommandLine> entry : cliCommands().entrySet()) {
            String path = entry.getKey();
            if (CLI_ONLY.contains(path) || PARENT_CONTAINERS.contains(path) || !(entry.getValue().getCommand() instanceof Callable)) {
                continue; // CLI 专属或纯父容器（子命令各自覆盖）
            }
            if (!allDeclaredPaths().contains(path)) {
                gaps.add(path);
            }
        }
        assertTrue(gaps.isEmpty(), "可执行 CLI 命令未被任何 MCP 工具声明封装: " + gaps);
    }

    @Test
    @DisplayName("每条声明都指向真实的 picocli 命令路径（防声明拼错）")
    void everyDeclaredPathIsReal() {
        List<String> fake = new ArrayList<>();
        for (String path : allDeclaredPaths()) {
            if (!cliCommands().containsKey(path)) {
                fake.add(path);
            }
        }
        assertTrue(fake.isEmpty(), "声明了不存在的 CLI 命令路径: " + fake);
    }

    @Test
    @DisplayName("被声明命令的 CLI 参数被映射工具的入参覆盖（豁免除外）")
    void mappedToolParamsCoverCliOptions() {
        Map<String, CommandLine> commands = cliCommands();
        Map<String, Set<String>> toolParams = new LinkedHashMap<>();
        for (McpTool tool : McpTools.tools(null)) {
            Set<String> props = new HashSet<>();
            Matcher matcher = Pattern.compile("\"([a-zA-Z]+)\":\\s*\\{\"type\"").matcher(tool.inputSchemaJson);
            while (matcher.find()) {
                props.add(matcher.group(1));
            }
            for (String cmd : tool.cliCommands) {
                toolParams.computeIfAbsent(cmd, k -> new HashSet<>()).addAll(props);
            }
        }
        List<String> gaps = new ArrayList<>();
        for (String path : allDeclaredPaths()) {
            CommandLine commandLine = commands.get(path);
            if (commandLine == null) {
                gaps.add(path + ": declared but no such CLI command");
                continue;
            }
            Set<String> exceptions = OPTION_EXCEPTIONS.getOrDefault(path, new HashSet<>());
            Set<String> covered = toolParams.getOrDefault(path, new HashSet<>());
            for (String option : optionNames(commandLine)) {
                if (GLOBAL_OPTION_EXCEPTIONS.contains(option) || exceptions.contains(option)) {
                    continue;
                }
                String param = toCamel(option);
                if (!covered.contains(param)) {
                    gaps.add(path + " " + option + " -> " + param);
                }
            }
        }
        assertTrue(gaps.isEmpty(), "CLI 参数未被映射工具入参覆盖: " + gaps);
    }

    private static Set<String> optionNames(CommandLine commandLine) {
        return new HashSet<>(commandLine.getCommandSpec().optionsMap().keySet());
    }

    private static String toCamel(String optionName) {
        String name = optionName.startsWith("--") ? optionName.substring(2) : optionName;
        String[] parts = name.split("-");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }
}
