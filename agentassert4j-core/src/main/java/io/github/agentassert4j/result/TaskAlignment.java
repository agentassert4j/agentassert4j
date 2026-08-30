package io.github.agentassert4j.result;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务对齐结果 — 基线链 × 新链按调用点对齐的逐步判定（纯比较，零 LLM 调用）。
 *
 * <p>两侧指纹现场重提后经确定性对比器得出配对判定；缺步骤/新增步骤是行为差异，
 * 与配对 CHANGED 同归入链级 CHANGED。对齐不消费任何存档指纹。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public class TaskAlignment {

    /**
     * 步骤对齐类别
     */
    public enum StepKind {
        /**
         * 两侧都有该调用点，逐对判定
         */
        MATCHED,
        /**
         * 基线有、新链无 = 缺步骤（行为差异）
         */
        MISSING,
        /**
         * 新链有、基线无 = 新增步骤（行为差异）
         */
        ADDED
    }

    private Verdict verdict;
    private Long baselineTime;
    private Long newChainTime;
    /**
     * 链内任一记录携带会话前缀（真实再执行对照须重演前缀，否则差异源于上下文缺失）
     */
    private boolean prefixDependent;
    private final List<StepAlignment> steps = new ArrayList<>();

    public Verdict getVerdict() {
        return verdict;
    }

    public void setVerdict(Verdict verdict) {
        this.verdict = verdict;
    }

    public Long getBaselineTime() {
        return baselineTime;
    }

    public void setBaselineTime(Long baselineTime) {
        this.baselineTime = baselineTime;
    }

    public Long getNewChainTime() {
        return newChainTime;
    }

    public void setNewChainTime(Long newChainTime) {
        this.newChainTime = newChainTime;
    }

    public boolean isPrefixDependent() {
        return prefixDependent;
    }

    public void setPrefixDependent(boolean prefixDependent) {
        this.prefixDependent = prefixDependent;
    }

    public List<StepAlignment> getSteps() {
        return steps;
    }

    /**
     * 单步骤对齐 — 一个调用点在两侧的配对判定
     */
    public static class StepAlignment {

        private StepKind kind;
        private String invocationKey;
        private String baselineRecordId;
        private String newRecordId;
        /**
         * 仅 MATCHED 且配对数 ≥1 时有值；配对全部 PASS 时该步骤 verdict=PASS
         */
        private Verdict verdict;
        /**
         * 配对中首个非 PASS 的对比结果（差异明细），全 PASS 时为首个配对的 PASS 结果
         */
        private ComparisonResult comparison;
        /**
         * 两侧记录数不齐时未被配对的富余侧计数（1:1 规范序配对，富余不判差异）
         */
        private int surplusCount;
        private String baselineModelResponse;
        private String newModelResponse;

        public StepKind getKind() {
            return kind;
        }

        public void setKind(StepKind kind) {
            this.kind = kind;
        }

        public String getInvocationKey() {
            return invocationKey;
        }

        public void setInvocationKey(String invocationKey) {
            this.invocationKey = invocationKey;
        }

        public String getBaselineRecordId() {
            return baselineRecordId;
        }

        public void setBaselineRecordId(String baselineRecordId) {
            this.baselineRecordId = baselineRecordId;
        }

        public String getNewRecordId() {
            return newRecordId;
        }

        public void setNewRecordId(String newRecordId) {
            this.newRecordId = newRecordId;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public void setVerdict(Verdict verdict) {
            this.verdict = verdict;
        }

        public ComparisonResult getComparison() {
            return comparison;
        }

        public void setComparison(ComparisonResult comparison) {
            this.comparison = comparison;
        }

        public int getSurplusCount() {
            return surplusCount;
        }

        public void setSurplusCount(int surplusCount) {
            this.surplusCount = surplusCount;
        }

        public String getBaselineModelResponse() {
            return baselineModelResponse;
        }

        public void setBaselineModelResponse(String baselineModelResponse) {
            this.baselineModelResponse = baselineModelResponse;
        }

        public String getNewModelResponse() {
            return newModelResponse;
        }

        public void setNewModelResponse(String newModelResponse) {
            this.newModelResponse = newModelResponse;
        }
    }
}
