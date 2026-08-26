package io.github.agentassert4j.model;

import java.util.Map;

/**
 * 工具调用记录 — LLM 决策调用一个工具的完整信息。
 *
 * <p>argTypes 获取策略：
 * <ol>
 *   <li>优先级 1（精确）：SDK / 框架提供</li>
 *   <li>优先级 2（推断）：从实际参数值推断类型</li>
 *   <li>优先级 3（默认）：全部默认 String</li>
 * </ol>
 * 接入层负责填充 argTypes，核心算法只消费。</p>
 */
public class ToolCall {

    private String toolName;
    private String toolCallId;
    private Map<String, Object> arguments;
    /** 参数类型签名，如 {"orderId":"String","limit":"Integer"} */
    private Map<String, String> argTypes;
    private String result;
    private boolean success;

    public String getToolName() { return toolName; }
    public void setToolName(String toolName) { this.toolName = toolName; }

    public String getToolCallId() { return toolCallId; }
    public void setToolCallId(String toolCallId) { this.toolCallId = toolCallId; }

    public Map<String, Object> getArguments() { return arguments; }
    public void setArguments(Map<String, Object> arguments) { this.arguments = arguments; }

    public Map<String, String> getArgTypes() { return argTypes; }
    public void setArgTypes(Map<String, String> argTypes) { this.argTypes = argTypes; }

    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }
}
