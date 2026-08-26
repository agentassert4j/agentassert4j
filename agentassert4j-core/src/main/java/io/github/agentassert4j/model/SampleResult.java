package io.github.agentassert4j.model;

import io.github.agentassert4j.result.Verdict;

/**
 * 单次采样结果 — 统计回归中每一次 LLM 重放的原子结果。
 *
 * <p>包含该次重放的 Verdict、score、以及差异摘要，
 * 供 StatisticalRegressionResult 聚合统计。</p>
 */
public class SampleResult {

    /** 采样序号（从 1 开始） */
    private int sampleIndex;

    /** 该次采样的 Verdict */
    private Verdict verdict;

    /** 该次采样的加权分 */
    private double score;

    /** 差异摘要（仅 Verdict != PASS 时有值） */
    private String diffSummary;

    /** 执行耗时（毫秒） */
    private long latencyMs;

    /** LLM API 错误信息（仅异常时有值） */
    private String errorMessage;

    public SampleResult() {}

    public SampleResult(int sampleIndex, Verdict verdict, double score,
                        String diffSummary, long latencyMs) {
        this.sampleIndex = sampleIndex;
        this.verdict = verdict;
        this.score = score;
        this.diffSummary = diffSummary;
        this.latencyMs = latencyMs;
    }

    public int getSampleIndex() { return sampleIndex; }
    public void setSampleIndex(int sampleIndex) { this.sampleIndex = sampleIndex; }

    public Verdict getVerdict() { return verdict; }
    public void setVerdict(Verdict verdict) { this.verdict = verdict; }

    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }

    public String getDiffSummary() { return diffSummary; }
    public void setDiffSummary(String diffSummary) { this.diffSummary = diffSummary; }

    public long getLatencyMs() { return latencyMs; }
    public void setLatencyMs(long latencyMs) { this.latencyMs = latencyMs; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
}
