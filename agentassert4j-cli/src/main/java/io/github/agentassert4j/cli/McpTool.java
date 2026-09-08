package io.github.agentassert4j.cli;

/**
 * MCP 工具条目 — manifest（name/description/inputSchema）与执行器的绑定。
 *
 * <p>manifest 即面向 AI 的文档：description 讲清做什么、前置条件、PASS/CHANGED 语义；
 * 变异动词另声明使用要求（治理写、应在人类指示后调用、agent 以 approver 申报身份）。
 * inputSchemaJson 是合法 JSON Schema（2020-12 语义）的对象文本，分发层原样拼入
 * tools/list 响应。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class McpTool {

    final String name;
    final String description;
    final String inputSchemaJson;
    final McpToolHandler handler;

    private McpTool(String name, String description, String inputSchemaJson, McpToolHandler handler) {
        this.name = name;
        this.description = description;
        this.inputSchemaJson = inputSchemaJson;
        this.handler = handler;
    }

    static McpTool of(String name, String description, String inputSchemaJson, McpToolHandler handler) {
        return new McpTool(name, description, inputSchemaJson, handler);
    }
}
