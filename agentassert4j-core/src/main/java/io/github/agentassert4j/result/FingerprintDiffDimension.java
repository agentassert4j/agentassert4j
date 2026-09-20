package io.github.agentassert4j.result;

/**
 * 指纹差异维度 — 基线与候选指纹逐维对照的封闭词表（wire 值冻结）。
 *
 * <p>维度描述指纹自身的字段面（工具集合/参数类型/输出结构/内容规则等），
 * 与 task-report 的 comparisonMetrics 五布尔（判定失配标记）是两个投影：
 * 前者回答「指纹哪里不一样」，后者回答「判定哪维没对上」。</p>
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
public enum FingerprintDiffDimension {

    TOOL_SET("toolSet"), PARAM_TYPES("paramTypes"), OUTPUT_CONTENT_TYPE("outputContentType"), OUTPUT_FIELDS("outputFields"), FIELD_TYPES("fieldTypes"), OUTPUT_LENGTH_MAGNITUDE("outputLengthMagnitude"), REQUIRED_KEYWORDS("requiredKeywords"), FORBIDDEN_KEYWORDS("forbiddenKeywords"), REGEX_RULE_COUNT("regexRuleCount"), DECLARED_BEHAVIORS("declaredBehaviors"), ERROR_MARKER("errorMarker");

    private final String wireName;

    FingerprintDiffDimension(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
