package io.github.agentassert4j.model;

import java.util.List;
import java.util.Set;

/**
 * ImpactAnalyzer 返回值 — 变更影响分析结果。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class AnalysisResult {

    private Set<String> directSkills;
    private Set<String> allAffectedSkills;
    private List<InteractionRecord> testCases;
    /**
     * 冷启动引导提示
     */
    private String message;
    private boolean hasBaseline;
    /**
     * 存储查询失败的退化标志——与冷启动（合法空数据）严格区分
     */
    private boolean error;

    public AnalysisResult() {
    }

    public AnalysisResult(Set<String> directSkills, Set<String> allAffectedSkills,
                          List<InteractionRecord> testCases) {
        this.directSkills = directSkills;
        this.allAffectedSkills = allAffectedSkills;
        this.testCases = testCases;
        this.hasBaseline = true;
    }

    public static AnalysisResult noBaseline(String message) {
        AnalysisResult r = new AnalysisResult();
        r.message = message;
        r.hasBaseline = false;
        return r;
    }

    public static AnalysisResult error(String message) {
        AnalysisResult r = new AnalysisResult();
        r.message = message;
        r.hasBaseline = false;
        r.error = true;
        return r;
    }

    public Set<String> getDirectSkills() {
        return directSkills;
    }

    public void setDirectSkills(Set<String> directSkills) {
        this.directSkills = directSkills;
    }

    public Set<String> getAllAffectedSkills() {
        return allAffectedSkills;
    }

    public void setAllAffectedSkills(Set<String> allAffectedSkills) {
        this.allAffectedSkills = allAffectedSkills;
    }

    public List<InteractionRecord> getTestCases() {
        return testCases;
    }

    public void setTestCases(List<InteractionRecord> testCases) {
        this.testCases = testCases;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isHasBaseline() {
        return hasBaseline;
    }

    public void setHasBaseline(boolean hasBaseline) {
        this.hasBaseline = hasBaseline;
    }

    public boolean isError() {
        return error;
    }

    public void setError(boolean error) {
        this.error = error;
    }
}
