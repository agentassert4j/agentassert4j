package io.github.agentassert4j.model;

import io.github.agentassert4j.result.ComparisonResult;

/**
 * 回归测试结果 — RegressionTestExecutor.execute() 返回值。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class RegressionTestResult {

    private String baselineRecordId;
    private String skillId;
    private ComparisonResult comparison;
    private DeterministicFingerprint candidateFingerprint;
    private TestResultStatus status;
    private String errorMessage;

    public RegressionTestResult() {
        this.status = TestResultStatus.SUCCESS;
    }

    public static RegressionTestResult timeout(String recordId) {
        RegressionTestResult r = new RegressionTestResult();
        r.baselineRecordId = recordId;
        r.status = TestResultStatus.TIMEOUT;
        return r;
    }

    public static RegressionTestResult apiError(String recordId, String msg) {
        RegressionTestResult r = new RegressionTestResult();
        r.baselineRecordId = recordId;
        r.status = TestResultStatus.API_ERROR;
        r.errorMessage = msg;
        return r;
    }

    public String getBaselineRecordId() {
        return baselineRecordId;
    }

    public void setBaselineRecordId(String baselineRecordId) {
        this.baselineRecordId = baselineRecordId;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public ComparisonResult getComparison() {
        return comparison;
    }

    public void setComparison(ComparisonResult comparison) {
        this.comparison = comparison;
    }

    public DeterministicFingerprint getCandidateFingerprint() {
        return candidateFingerprint;
    }

    public void setCandidateFingerprint(DeterministicFingerprint candidateFingerprint) {
        this.candidateFingerprint = candidateFingerprint;
    }

    public TestResultStatus getStatus() {
        return status;
    }

    public void setStatus(TestResultStatus status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
