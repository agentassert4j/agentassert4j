package io.github.agentassert4j.model;

import java.util.List;

/**
 * LLM 响应 — 回归测试重放时从 LLM API 获取。
 */
public class LlmResponse {

    private String content;
    private List<ToolCallResult> toolCalls;
    private int inputTokens;
    private int outputTokens;
    private long latencyMs;
    private boolean hasError;
    private String errorMessage;

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public List<ToolCallResult> getToolCalls() { return toolCalls; }
    public void setToolCalls(List<ToolCallResult> toolCalls) { this.toolCalls = toolCalls; }

    public int getInputTokens() { return inputTokens; }
    public void setInputTokens(int inputTokens) { this.inputTokens = inputTokens; }

    public int getOutputTokens() { return outputTokens; }
    public void setOutputTokens(int outputTokens) { this.outputTokens = outputTokens; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public boolean isHasError() { return hasError; }
    public void setHasError(boolean hasError) { this.hasError = hasError; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
