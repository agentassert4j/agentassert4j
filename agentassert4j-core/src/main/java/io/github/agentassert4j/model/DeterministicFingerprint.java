package io.github.agentassert4j.model;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 确定性行为指纹 — 四维度结构化指纹，零 AI 依赖。
 *
 * <ul>
 *   <li>维度 1（40%）：工具调用 — 全自动提取</li>
 *   <li>维度 2（25%）：输出结构 — 全自动提取</li>
 *   <li>维度 3（20%）：内容规则 — 用户声明式配置</li>
 *   <li>维度 4（15%）：约束行为 — 用户声明式配置</li>
 * </ul>
 *
 * <p>值对象语义：equals/hashCode 覆盖全部维度字段，供跨存储层反序列化后的指纹值比较；
 * Set/Map 字段天然顺序无关，regex 列表按声明顺序比较。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class DeterministicFingerprint {

    private Set<String> toolCallSet;
    private Map<String, String> toolParamTypes;

    private String outputContentType;
    private Set<String> outputFieldPaths;
    private Map<String, String> outputFieldTypeMap;
    /**
     * 纯文本输出退化为长度数量级（100字→2, 1000字→3）
     */
    private int textLengthMagnitude;

    private Set<String> requiredKeywords;
    private Set<String> forbiddenKeywords;
    private List<RegexPattern> regexPatterns;

    private Set<String> declaredBehaviors;
    private boolean hasError;

    public Set<String> getToolCallSet() {
        return toolCallSet;
    }

    public void setToolCallSet(Set<String> toolCallSet) {
        this.toolCallSet = toolCallSet;
    }

    public Map<String, String> getToolParamTypes() {
        return toolParamTypes;
    }

    public void setToolParamTypes(Map<String, String> toolParamTypes) {
        this.toolParamTypes = toolParamTypes;
    }

    public String getOutputContentType() {
        return outputContentType;
    }

    public void setOutputContentType(String outputContentType) {
        this.outputContentType = outputContentType;
    }

    public Set<String> getOutputFieldPaths() {
        return outputFieldPaths;
    }

    public void setOutputFieldPaths(Set<String> outputFieldPaths) {
        this.outputFieldPaths = outputFieldPaths;
    }

    public Map<String, String> getOutputFieldTypeMap() {
        return outputFieldTypeMap;
    }

    public void setOutputFieldTypeMap(Map<String, String> outputFieldTypeMap) {
        this.outputFieldTypeMap = outputFieldTypeMap;
    }

    public int getTextLengthMagnitude() {
        return textLengthMagnitude;
    }

    public void setTextLengthMagnitude(int textLengthMagnitude) {
        this.textLengthMagnitude = textLengthMagnitude;
    }

    public Set<String> getRequiredKeywords() {
        return requiredKeywords;
    }

    public void setRequiredKeywords(Set<String> requiredKeywords) {
        this.requiredKeywords = requiredKeywords;
    }

    public Set<String> getForbiddenKeywords() {
        return forbiddenKeywords;
    }

    public void setForbiddenKeywords(Set<String> forbiddenKeywords) {
        this.forbiddenKeywords = forbiddenKeywords;
    }

    public List<RegexPattern> getRegexPatterns() {
        return regexPatterns;
    }

    public void setRegexPatterns(List<RegexPattern> regexPatterns) {
        this.regexPatterns = regexPatterns;
    }

    public Set<String> getDeclaredBehaviors() {
        return declaredBehaviors;
    }

    public void setDeclaredBehaviors(Set<String> declaredBehaviors) {
        this.declaredBehaviors = declaredBehaviors;
    }

    public boolean isHasError() {
        return hasError;
    }

    public void setHasError(boolean hasError) {
        this.hasError = hasError;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DeterministicFingerprint)) return false;
        DeterministicFingerprint other = (DeterministicFingerprint) o;
        return textLengthMagnitude == other.textLengthMagnitude && hasError == other.hasError && Objects.equals(toolCallSet, other.toolCallSet) && Objects.equals(toolParamTypes, other.toolParamTypes) && Objects.equals(outputContentType, other.outputContentType) && Objects.equals(outputFieldPaths, other.outputFieldPaths) && Objects.equals(outputFieldTypeMap, other.outputFieldTypeMap) && Objects.equals(requiredKeywords, other.requiredKeywords) && Objects.equals(forbiddenKeywords, other.forbiddenKeywords) && Objects.equals(regexPatterns, other.regexPatterns) && Objects.equals(declaredBehaviors, other.declaredBehaviors);
    }

    @Override
    public int hashCode() {
        return Objects.hash(toolCallSet, toolParamTypes, outputContentType, outputFieldPaths, outputFieldTypeMap, textLengthMagnitude, requiredKeywords, forbiddenKeywords, regexPatterns, declaredBehaviors, hasError);
    }
}
