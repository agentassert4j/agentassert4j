package io.github.agentassert4j.cli;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MCP 通道的包络措辞通道化——工具结果里的 CLI 命令指称替换为 MCP 工具名。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
final class McpVerbs {

    /**
     * CLI 命令路径 → MCP 工具名（同路径多工具时注册序最前者认领）。
     */
    private static final Map<String, String> COMMAND_TO_TOOL = new LinkedHashMap<>();

    /**
     * 按命令路径长度降序排列的重写规则（最长路径先替换，防 record show 被 record 截断）。
     */
    private static final List<Pattern> BACKTICKED = new ArrayList<>();
    private static final List<Pattern> BARE = new ArrayList<>();
    private static final List<String> TOOL_FORM = new ArrayList<>();

    static {
        for (McpTool tool : McpTools.tools(null)) {
            for (String path : tool.cliCommands) {
                COMMAND_TO_TOOL.putIfAbsent(path, tool.name);
            }
        }
        List<String> paths = new ArrayList<>(COMMAND_TO_TOOL.keySet());
        paths.sort(Comparator.comparingInt(String::length).reversed());
        for (String path : paths) {
            // 反引号码形（可含参数，整段消费避免嵌套反引号）与裸形（hints 的 nextAction）
            BACKTICKED.add(Pattern.compile("`agentassert4j " + path + "([^`]*)`"));
            BARE.add(Pattern.compile("agentassert4j " + path));
            TOOL_FORM.add("the `" + COMMAND_TO_TOOL.get(path) + "` tool");
        }
    }

    private McpVerbs() {
    }

    /**
     * 派生的包络通道化词表（CLI 命令路径 → MCP 工具名），供一致性测试对照。
     */
    static Map<String, String> commandToTool() {
        return COMMAND_TO_TOOL;
    }

    /**
     * --ci 逃生舱是 CLI 旗标（MCP 的 check/diff 恒为 ci 语义），该子句在 MCP 面不可达，随映射一并摘除。
     */
    static String channelize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (int i = 0; i < BACKTICKED.size(); i++) {
            result = replaceAll(result, BACKTICKED.get(i), TOOL_FORM.get(i) + "$1");
            result = replaceAll(result, BARE.get(i), TOOL_FORM.get(i));
        }
        result = result.replace("; or drop --ci to auto-establish", "");
        result = result.replace(", or drop --ci to auto-establish", "");
        return result;
    }

    private static String replaceAll(String text, Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(text);
        return matcher.replaceAll(replacement);
    }
}
