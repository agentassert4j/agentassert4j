package io.github.agentassert4j.cli;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MCP 工具条目 — manifest（name/description/inputSchema）与执行器的绑定。
 *
 * <p>manifest 即面向 AI 的文档：description 讲清做什么、前置条件、PASS/CHANGED 语义；
 * 变异动词另声明使用要求（治理写、应在人类指示后调用、agent 以 approver 申报身份）。
 * inputSchemaJson 是合法 JSON Schema（2020-12 语义）的对象文本，分发层原样拼入
 * tools/list 响应。</p>
 *
 * <p>cliCommands 显式声明本工具封装的 CLI 命令路径（如 "baseline"、"record show"）——
 * MCP 面是 CLI 面的一层封装，这层封装关系必须声明而非隐含：包络通道化词表与
 * 面一致性测试都以此为真源派生。声明为空 = MCP 专属工具（CLI 无对应命令）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class McpTool {

    final String name;
    final String description;
    final String inputSchemaJson;
    final List<String> cliCommands;
    final McpToolHandler handler;

    private McpTool(String name, String description, String inputSchemaJson, List<String> cliCommands, McpToolHandler handler) {
        this.name = name;
        this.description = description;
        this.inputSchemaJson = inputSchemaJson;
        this.cliCommands = Collections.unmodifiableList(new ArrayList<>(cliCommands));
        this.handler = handler;
    }

    static McpTool of(String name, String description, String inputSchemaJson, List<String> cliCommands, McpToolHandler handler) {
        return new McpTool(name, description, inputSchemaJson, cliCommands, handler);
    }
}
