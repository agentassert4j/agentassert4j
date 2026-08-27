package io.github.agentassert4j.model;

import java.util.Map;

/**
 * LLM 返回的工具调用决策（重放时使用）。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ToolCallResult {

    private String toolCallId;
    private String toolName;
    private Map<String, Object> arguments;

    public String getToolCallId() {
        return toolCallId;
    }

    public void setToolCallId(String toolCallId) {
        this.toolCallId = toolCallId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }

    public void setArguments(Map<String, Object> arguments) {
        this.arguments = arguments;
    }
}
