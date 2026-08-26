package io.github.agentassert4j.model;

import java.util.List;
import java.util.Map;

/**
 * 交互记录 — Agent 与 LLM 一次完整交互的结构化快照。
 *
 * <p>录制层捕获后通过 Disruptor 异步写入 StorageRepository。
 * 核心算法（分组、指纹、图谱）全部基于此模型。</p>
 */
public class InteractionRecord {

    private String recordId;
    private long timestamp;

    // ====== Prompt 与输入 ======
    /** System Prompt SHA-256 hash（分组依据 + 变更检测） */
    private String systemPromptHash;
    private String userInput;
    /** 多轮对话中的当前轮次索引（0-based） */
    private int turnIndex;

    // ====== LLM 响应 ======
    private String modelRequest;
    private String modelResponse;
    private int inputTokens;
    private int outputTokens;
    private long latencyMs;

    // ====== 工具调用 ======
    private List<ToolCall> toolCalls;
    private boolean hasToolCalls;

    // ====== 分组与关联 ======
    /** 会话 ID（按 sessionId 分组追踪依赖链） */
    private String sessionId;
    /** Skill ID（由 SkillGrouper 分组后回填） */
    private String skillId;

    // ====== 多模态支持 ======
    /** 是否包含多模态输入（图片/音频/视频） */
    private boolean multimodalInput;
    /** 多模态内容（JSON 数组，原样存储和回放） */
    private String multimodalContent;

    // ====== 多轮对话 ======
    /** 前序轮次上下文（用于重放） */
    private List<TurnContext> previousTurns;

    // ========== Getters & Setters ==========

    public String getRecordId() { return recordId; }
    public void setRecordId(String recordId) { this.recordId = recordId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public String getSystemPromptHash() { return systemPromptHash; }
    public void setSystemPromptHash(String systemPromptHash) { this.systemPromptHash = systemPromptHash; }

    public String getUserInput() { return userInput; }
    public void setUserInput(String userInput) { this.userInput = userInput; }

    public int getTurnIndex() { return turnIndex; }
    public void setTurnIndex(int turnIndex) { this.turnIndex = turnIndex; }

    public String getModelRequest() { return modelRequest; }
    public void setModelRequest(String modelRequest) { this.modelRequest = modelRequest; }

    public String getModelResponse() { return modelResponse; }
    public void setModelResponse(String modelResponse) { this.modelResponse = modelResponse; }

    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public List<ToolCall> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCall> toolCalls) { this.toolCalls = toolCalls; }

    public boolean isHasToolCalls() { return hasToolCalls; }
    public void setHasToolCalls(boolean hasToolCalls) { this.hasToolCalls = hasToolCalls; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public boolean isMultimodalInput() { return multimodalInput; }
    public void setMultimodalInput(boolean multimodalInput) { this.multimodalInput = multimodalInput; }

    public String getMultimodalContent() { return multimodalContent; }
    public void setMultimodalContent(String multimodalContent) { this.multimodalContent = multimodalContent; }

    public List<TurnContext> getPreviousTurns() { return previousTurns; }
    public void setPreviousTurns(List<TurnContext> previousTurns) { this.previousTurns = previousTurns; }
}
