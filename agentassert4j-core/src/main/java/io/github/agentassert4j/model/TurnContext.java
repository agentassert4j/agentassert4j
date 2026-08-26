package io.github.agentassert4j.model;

/**
 * 多轮对话上下文 — InteractionRecord.previousTurns 的元素。
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
     * role=tool 时有值
     */
    private String toolCallId;
    /**
     * role=tool 时有值
     */
    private String toolName;

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
}
