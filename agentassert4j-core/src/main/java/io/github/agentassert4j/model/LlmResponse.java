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

    /** 响应报告的实际服务模型版本快照（requested vs served 惯例）；协议不报告时为 null */
    private String servedModel;
    /** 归一化结束原因枚举（TEXT）：stop/tool_calls/max_tokens/content_filter/other；捕获层归一 */
    private String finishReason;
    /** usage 子树原文逐字保留——未来一切新遥测列的回填来源（承重墙） */
    private String usageRaw;
    /** 缓存读命中 token；供应商不报告时为 null */
    private Integer cacheReadTokens;
    /** 缓存写穿透 token（仅 Anthropic 分计费）；不报告时为 null */
    private Integer cacheWriteTokens;
    /** 思考模型推理 token；不报告时为 null */
    private Integer reasoningTokens;

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

    public String getServedModel() { return servedModel; }
    public void setServedModel(String servedModel) { this.servedModel = servedModel; }

    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String finishReason) { this.finishReason = finishReason; }

    public String getUsageRaw() { return usageRaw; }
    public void setUsageRaw(String usageRaw) { this.usageRaw = usageRaw; }

    public Integer getCacheReadTokens() { return cacheReadTokens; }
    public void setCacheReadTokens(Integer cacheReadTokens) { this.cacheReadTokens = cacheReadTokens; }

    public Integer getCacheWriteTokens() { return cacheWriteTokens; }
    public void setCacheWriteTokens(Integer cacheWriteTokens) { this.cacheWriteTokens = cacheWriteTokens; }

    public Integer getReasoningTokens() { return reasoningTokens; }
    public void setReasoningTokens(Integer reasoningTokens) { this.reasoningTokens = reasoningTokens; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public boolean isHasError() { return hasError; }
    public void setHasError(boolean hasError) { this.hasError = hasError; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
