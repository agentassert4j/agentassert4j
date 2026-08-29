package io.github.agentassert4j.result;

import java.util.Set;

/**
 * 对比结果 — DeterministicComparator.compare() 的返回值。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ComparisonResult {

    private boolean toolCallMatch;
    private boolean paramTypeMatch;
    private Set<String> addedFields;
    private Set<String> removedFields;
    private boolean fieldTypeMatch;
    /**
     * 输出结构维整体结论：contentType、纯文本长度数量级、字段集增删、字段类型的合取。
     * 供机器消费方（--json dims、统计聚合）免于从明细字段二次拼装
     */
    private boolean structureMatch = true;
    private boolean keywordMatch;
    private boolean regexMatch;
    private boolean behaviorMatch;

    private double score;
    private Verdict verdict;
    private String summary;

    public boolean isToolCallMatch() {
        return toolCallMatch;
    }

    public void setToolCallMatch(boolean toolCallMatch) {
        this.toolCallMatch = toolCallMatch;
    }

    public boolean isParamTypeMatch() {
        return paramTypeMatch;
    }

    public void setParamTypeMatch(boolean paramTypeMatch) {
        this.paramTypeMatch = paramTypeMatch;
    }

    public Set<String> getAddedFields() {
        return addedFields;
    }

    public void setAddedFields(Set<String> addedFields) {
        this.addedFields = addedFields;
    }

    public Set<String> getRemovedFields() {
        return removedFields;
    }

    public void setRemovedFields(Set<String> removedFields) {
        this.removedFields = removedFields;
    }

    public boolean isFieldTypeMatch() {
        return fieldTypeMatch;
    }

    public void setFieldTypeMatch(boolean fieldTypeMatch) {
        this.fieldTypeMatch = fieldTypeMatch;
    }

    public boolean isStructureMatch() {
        return structureMatch;
    }

    public void setStructureMatch(boolean structureMatch) {
        this.structureMatch = structureMatch;
    }

    public boolean isKeywordMatch() {
        return keywordMatch;
    }

    public void setKeywordMatch(boolean keywordMatch) {
        this.keywordMatch = keywordMatch;
    }

    public boolean isRegexMatch() {
        return regexMatch;
    }

    public void setRegexMatch(boolean regexMatch) {
        this.regexMatch = regexMatch;
    }

    public boolean isBehaviorMatch() {
        return behaviorMatch;
    }

    public void setBehaviorMatch(boolean behaviorMatch) {
        this.behaviorMatch = behaviorMatch;
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    public Verdict getVerdict() {
        return verdict;
    }

    public void setVerdict(Verdict verdict) {
        this.verdict = verdict;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }
}
