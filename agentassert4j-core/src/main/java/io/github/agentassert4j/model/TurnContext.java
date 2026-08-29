package io.github.agentassert4j.model;

/**
 * 多轮对话上下文 — InteractionRecord.previousTurns 的元素。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class TurnContext {

    /**
     * "user" | "assistant" | "tool"
     */
    private String role;
    /**
     * 文本内容
     */
    private String content;
    /**
     * 工具调用的关联键：tool 结果帧必带；assistant 工具调用帧（链式半重放的合成帧）同样携带
     */
    private String toolCallId;
    /**
     * 工具名：tool 结果帧与 assistant 工具调用帧（链式合成帧）均携带
     */
    private String toolName;
    /**
     * assistant 发起工具调用帧的实际参数 JSON（仅 assistant 角色且携带 toolCallId 的帧使用）。
     * 历史录制轮没有该载体，为 null——渲染层以 "{}" 占位；链式半重放的合成帧携带真值
     * （「当时输入」重建要求内容无损）
     */
    private String toolArguments;

    public TurnContext() {
    }

    public TurnContext(String role, String content) {
        this.role = role;
        this.content = content;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

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

    public String getToolArguments() {
        return toolArguments;
    }

    public void setToolArguments(String toolArguments) {
        this.toolArguments = toolArguments;
    }
}
