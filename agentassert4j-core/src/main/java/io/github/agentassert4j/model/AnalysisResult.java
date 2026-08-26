package io.github.agentassert4j.model;

import java.util.Set;
import java.util.List;

/**
 * ImpactAnalyzer 返回值 — 变更影响分析结果。
 */
public class AnalysisResult {

    private Set<String> directSkills;
    private Set<String> allAffectedSkills;
    private List<InteractionRecord> testCases;
    /** 冷启动引导提示 */
    private String message;
    private boolean hasBaseline;

    public AnalysisResult() {}

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

    public Set<String> getDirectSkills() { return directSkills; }
    public void setDirectSkills(Set<String> directSkills) { this.directSkills = directSkills; }

    public Set<String> getAllAffectedSkills() { return allAffectedSkills; }
    public void setAllAffectedSkills(Set<String> allAffectedSkills) { this.allAffectedSkills = allAffectedSkills; }

    public List<InteractionRecord> getTestCases() { return testCases; }
    public void setTestCases(List<InteractionRecord> testCases) { this.testCases = testCases; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public boolean isHasBaseline() { return hasBaseline; }
    public void setHasBaseline(boolean hasBaseline) { this.hasBaseline = hasBaseline; }
}
