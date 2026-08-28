package io.github.agentassert4j.model;

import java.util.List;

/**
 * 交互记录 — Agent 与 LLM 一次完整交互的结构化快照。
 *
 * <p>录制层捕获后通过 Disruptor 异步写入 StorageRepository。
 * 核心算法（分组、指纹、图谱）全部基于此模型。</p>
 *
 * <p>字段集与 v1 schema 的 interactions 列一一对应：
 * 只在调用时刻可知的观测事实（模型身份、采样参数、原文、token 遥测）
 * 必须由捕获侧填充，事后无法重建。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class InteractionRecord {

    private String recordId;
    private long timestamp;

    /**
     * 录制进程内单调序号（Disruptor 序号透传），与 timestamp 组成确定性排序键
     */
    private long seq;

    /**
     * 模板 ID（SDK 显式声明的模板标识，如 "order-extract/v2"）
     */
    private String templateId;
    /**
     * 模板文本 SHA-256 hash（分组依据 + 变更检测）
     */
    private String templateHash;
    /**
     * 模板原文（瞬态字段）：捕获侧随记录携带到存储侧，以 templateHash 为键
     * 归档进 prompt_texts 表（模板文本库），不对应 interactions 列——
     * 是本类字段集与 interactions 列一一对应规则的唯一例外
     */
    private String templateText;
    /**
     * 变量取值指纹（同模板不同变量形态的区分键）
     */
    // TODO: 捕获侧未接线——该列暂恒为 null，待 SDK 捕获层补齐模板变量指纹的口径与写入，接线后移除本标注
    private String variablesFingerprint;

    /**
     * 协议枚举（TEXT）：openai-chat / anthropic-messages / openai-responses / gemini-native
     */
    // TODO: 捕获侧未接线——该列暂恒为 null，待 SDK 捕获层按适配线补写协议标识，接线后移除本标注
    private String apiProtocol;
    /**
     * 供应商标识：openai / anthropic / deepseek / qwen / ollama / vllm / custom
     */
    private String provider;
    /**
     * 请求的模型 ID
     */
    private String model;
    /**
     * 响应报告的实际服务模型（版本化快照，如 gpt-4o-2024-08-06）
     */
    private String servedModel;
    /**
     * API base URL——同模型不同部署点行为可不同
     */
    // TODO: 捕获侧未接线——该列暂恒为 null，待 SDK 捕获层从请求配置提取端点地址，接线后移除本标注
    private String endpoint;

    private String userInput;
    /**
     * 多轮对话中的当前轮次索引（0-based）
     */
    private int turnIndex;

    /**
     * 发送的 tools 定义 JSON 数组原文（重放完备性）
     */
    private String toolsDefinition;
    /**
     * 采样参数 JSON 打包（temperature/top_p/max_tokens/stop/seed 等）
     */
    private String samplingParams;
    /**
     * 请求原文逐字保留（未来一切新概念列的回填来源）
     */
    private String modelRequestRaw;

    /**
     * 归一化结束原因枚举（TEXT）：stop/tool_calls/max_tokens/content_filter/other
     */
    private String finishReason;
    private String modelResponse;
    /**
     * 响应原文逐字保留（与请求 raw 对称的保险）
     */
    private String modelResponseRaw;

    /**
     * 总处理输入 token（OpenAI 语义直接取 prompt_tokens；Anthropic 需含缓存读写合成）
     */
    private int inputTokens;
    private int outputTokens;
    /**
     * 缓存读命中 token；供应商不报告时为 null
     */
    private Integer cacheReadTokens;
    /**
     * 缓存写穿透 token（仅 Anthropic 分计费）；不报告时为 null
     */
    private Integer cacheWriteTokens;
    /**
     * 思考模型推理 token；不报告时为 null
     */
    private Integer reasoningTokens;
    /**
     * usage 子树原文逐字保留
     */
    private String usageRaw;
    private long latencyMs;
    /**
     * 流式首 token 延迟（毫秒）；非流式调用为 null
     */
    private Long ttftMs;
    /**
     * 调用时刻冻结的费用（USD）；无价格表时为 null
     */
    // TODO: 捕获侧未接线——该列暂恒为 null，费用计算依赖随版本刷新的价格快照，待价格数据链接线后移除本标注
    private Double costUsd;

    private List<ToolCall> toolCalls;
    private boolean hasToolCalls;

    /**
     * 会话 ID（按 sessionId 分组追踪依赖链）
     */
    private String sessionId;
    /**
     * Skill ID（由 SkillGrouper 分组后回填）
     */
    private String skillId;
    /**
     * 确定性分组键（DeterministicSkillGrouper 生成）
     */
    private String groupKey;

    /**
     * 是否包含多模态输入（图片/音频/视频）
     */
    private boolean multimodalInput;
    /**
     * 多模态内容（JSON 数组，原样存储和回放）
     */
    private String multimodalContent;

    /**
     * 前序轮次上下文（用于重放）
     */
    private List<TurnContext> previousTurns;

    /**
     * 自由元数据 JSON（OTel attributes / Langfuse metadata 惯例）
     */
    private String metadata;
    /**
     * 录制方 SDK 版本（数据法医学）
     */
    private String recorderVersion;

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getSeq() {
        return seq;
    }

    public void setSeq(long seq) {
        this.seq = seq;
    }

    public String getTemplateId() {
        return templateId;
    }

    public void setTemplateId(String templateId) {
        this.templateId = templateId;
    }

    public String getTemplateHash() {
        return templateHash;
    }

    public void setTemplateHash(String templateHash) {
        this.templateHash = templateHash;
    }

    public String getTemplateText() {
        return templateText;
    }

    public void setTemplateText(String templateText) {
        this.templateText = templateText;
    }

    public String getVariablesFingerprint() {
        return variablesFingerprint;
    }

    public void setVariablesFingerprint(String variablesFingerprint) {
        this.variablesFingerprint = variablesFingerprint;
    }

    public String getApiProtocol() {
        return apiProtocol;
    }

    public void setApiProtocol(String apiProtocol) {
        this.apiProtocol = apiProtocol;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getServedModel() {
        return servedModel;
    }

    public void setServedModel(String servedModel) {
        this.servedModel = servedModel;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getUserInput() {
        return userInput;
    }

    public void setUserInput(String userInput) {
        this.userInput = userInput;
    }

    public int getTurnIndex() {
        return turnIndex;
    }

    public void setTurnIndex(int turnIndex) {
        this.turnIndex = turnIndex;
    }

    public String getToolsDefinition() {
        return toolsDefinition;
    }

    public void setToolsDefinition(String toolsDefinition) {
        this.toolsDefinition = toolsDefinition;
    }

    public String getSamplingParams() {
        return samplingParams;
    }

    public void setSamplingParams(String samplingParams) {
        this.samplingParams = samplingParams;
    }

    public String getModelRequestRaw() {
        return modelRequestRaw;
    }

    public void setModelRequestRaw(String modelRequestRaw) {
        this.modelRequestRaw = modelRequestRaw;
    }

    public String getFinishReason() {
        return finishReason;
    }

    public void setFinishReason(String finishReason) {
        this.finishReason = finishReason;
    }

    public String getModelResponse() {
        return modelResponse;
    }

    public void setModelResponse(String modelResponse) {
        this.modelResponse = modelResponse;
    }

    public String getModelResponseRaw() {
        return modelResponseRaw;
    }

    public void setModelResponseRaw(String modelResponseRaw) {
        this.modelResponseRaw = modelResponseRaw;
    }

    public int getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(int inputTokens) {
        this.inputTokens = inputTokens;
    }

    public int getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(int outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getCacheReadTokens() {
        return cacheReadTokens;
    }

    public void setCacheReadTokens(Integer cacheReadTokens) {
        this.cacheReadTokens = cacheReadTokens;
    }

    public Integer getCacheWriteTokens() {
        return cacheWriteTokens;
    }

    public void setCacheWriteTokens(Integer cacheWriteTokens) {
        this.cacheWriteTokens = cacheWriteTokens;
    }

    public Integer getReasoningTokens() {
        return reasoningTokens;
    }

    public void setReasoningTokens(Integer reasoningTokens) {
        this.reasoningTokens = reasoningTokens;
    }

    public String getUsageRaw() {
        return usageRaw;
    }

    public void setUsageRaw(String usageRaw) {
        this.usageRaw = usageRaw;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Long getTtftMs() {
        return ttftMs;
    }

    public void setTtftMs(Long ttftMs) {
        this.ttftMs = ttftMs;
    }

    public Double getCostUsd() {
        return costUsd;
    }

    public void setCostUsd(Double costUsd) {
        this.costUsd = costUsd;
    }

    public List<ToolCall> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<ToolCall> toolCalls) {
        this.toolCalls = toolCalls;
    }

    public boolean isHasToolCalls() {
        return hasToolCalls;
    }

    public void setHasToolCalls(boolean hasToolCalls) {
        this.hasToolCalls = hasToolCalls;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public String getGroupKey() {
        return groupKey;
    }

    public void setGroupKey(String groupKey) {
        this.groupKey = groupKey;
    }


    public boolean isMultimodalInput() {
        return multimodalInput;
    }

    public void setMultimodalInput(boolean multimodalInput) {
        this.multimodalInput = multimodalInput;
    }

    public String getMultimodalContent() {
        return multimodalContent;
    }

    public void setMultimodalContent(String multimodalContent) {
        this.multimodalContent = multimodalContent;
    }

    public List<TurnContext> getPreviousTurns() {
        return previousTurns;
    }

    public void setPreviousTurns(List<TurnContext> previousTurns) {
        this.previousTurns = previousTurns;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    public String getRecorderVersion() {
        return recorderVersion;
    }

    public void setRecorderVersion(String recorderVersion) {
        this.recorderVersion = recorderVersion;
    }
}
