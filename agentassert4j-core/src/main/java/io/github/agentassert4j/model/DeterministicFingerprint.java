package io.github.agentassert4j.model;

import java.util.List;
import java.util.Map;
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
 * <p><b>TODO: [值对象缺失]</b> 当前未实现 equals/hashCode，跨存储层反序列化后的指纹对象比较
 * 使用引用相等（==）而非值相等。当前 BaselineManagerTest 中测试能通过是因为直接持有对象引用。
 * 一旦出现跨反序列化的值比较需求，必须先实现 equals/hashCode。
 * 实现需注意 Set 和 Map 字段的顺序无关比较。</p>
 */
public class DeterministicFingerprint {

    private Set<String> toolCallSet;
    private Map<String, String> toolParamTypes;
    private Map<String, Boolean> toolParamRequired;

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

    public Map<String, Boolean> getToolParamRequired() {
        return toolParamRequired;
    }

    public void setToolParamRequired(Map<String, Boolean> toolParamRequired) {
        this.toolParamRequired = toolParamRequired;
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
}
