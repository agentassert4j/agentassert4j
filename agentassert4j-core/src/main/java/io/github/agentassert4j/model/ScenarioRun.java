package io.github.agentassert4j.model;

/**
 * 场景执行事实 — 一次场景运行的聚合结果（对应 scenario_runs 表行，只追加）。
 *
 * <p>verdict 取框架词表：STABLE / DRIFTED / INSUFFICIENT。遥测列与
 * interactions 同形（input_tokens=总量归一口径），费用按调用时刻价格表冻结。</p>
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
public class ScenarioRun {

    private String runId;
    private String scenarioId;
    private long startedAt;
    private String verdict;
    private int sampleCount;
    private int passCount;
    private int failCount;
    private int inputTokens;
    private int outputTokens;
    private Integer cacheReadTokens;
    private Integer cacheWriteTokens;
    private Integer reasoningTokens;
    private long latencyMs;
    private Long ttftMs;
    private Double costUsd;
    private String reportRef;
    private String metadata;

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public void setScenarioId(String scenarioId) {
        this.scenarioId = scenarioId;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public String getVerdict() {
        return verdict;
    }

    public void setVerdict(String verdict) {
        this.verdict = verdict;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public int getPassCount() {
        return passCount;
    }

    public void setPassCount(int passCount) {
        this.passCount = passCount;
    }

    public int getFailCount() {
        return failCount;
    }

    public void setFailCount(int failCount) {
        this.failCount = failCount;
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

    public String getReportRef() {
        return reportRef;
    }

    public void setReportRef(String reportRef) {
        this.reportRef = reportRef;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }
}
