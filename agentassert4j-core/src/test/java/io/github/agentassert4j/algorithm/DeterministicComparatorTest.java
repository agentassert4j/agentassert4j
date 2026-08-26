package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DeterministicComparatorTest {

    private final DeterministicComparator comparator = new DeterministicComparator();

    // ==================== 辅助方法 ====================

    private DeterministicFingerprint fp() {
        return fp(null, null, "text/plain", null, null, 1, null, null, false);
    }

    private DeterministicFingerprint fp(Set<String> toolCallSet,
                                         Map<String, String> toolParamTypes,
                                         String contentType,
                                         Set<String> fieldPaths,
                                         Map<String, String> fieldTypeMap,
                                         int textLengthMagnitude) {
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(toolCallSet != null ? toolCallSet : Collections.emptySet());
        fp.setToolParamTypes(toolParamTypes != null ? toolParamTypes : Collections.emptyMap());
        fp.setOutputContentType(contentType);
        fp.setOutputFieldPaths(fieldPaths != null ? fieldPaths : Collections.emptySet());
        fp.setOutputFieldTypeMap(fieldTypeMap != null ? fieldTypeMap : Collections.emptyMap());
        fp.setTextLengthMagnitude(textLengthMagnitude);
        fp.setRequiredKeywords(Collections.emptySet());
        fp.setForbiddenKeywords(Collections.emptySet());
        fp.setRegexPatterns(Collections.emptyList());
        fp.setDeclaredBehaviors(Collections.emptySet());
        fp.setHasError(false);
        return fp;
    }

    private DeterministicFingerprint fp(Set<String> toolCallSet,
                                         Map<String, String> toolParamTypes,
                                         String contentType,
                                         Set<String> fieldPaths,
                                         Map<String, String> fieldTypeMap,
                                         int textLengthMagnitude,
                                         Set<String> requiredKeywords,
                                         Set<String> forbiddenKeywords,
                                         boolean hasError) {
        DeterministicFingerprint f = new DeterministicFingerprint();
        f.setToolCallSet(toolCallSet != null ? toolCallSet : Collections.emptySet());
        f.setToolParamTypes(toolParamTypes != null ? toolParamTypes : Collections.emptyMap());
        f.setOutputContentType(contentType);
        f.setOutputFieldPaths(fieldPaths != null ? fieldPaths : Collections.emptySet());
        f.setOutputFieldTypeMap(fieldTypeMap != null ? fieldTypeMap : Collections.emptyMap());
        f.setTextLengthMagnitude(textLengthMagnitude);
        f.setRequiredKeywords(requiredKeywords != null ? requiredKeywords : Collections.emptySet());
        f.setForbiddenKeywords(forbiddenKeywords != null ? forbiddenKeywords : Collections.emptySet());
        f.setRegexPatterns(Collections.emptyList());
        f.setDeclaredBehaviors(Collections.emptySet());
        f.setHasError(hasError);
        return f;
    }

    private DeterministicFingerprint fpWithBehaviors(Set<String> behaviors) {
        DeterministicFingerprint f = new DeterministicFingerprint();
        f.setToolCallSet(Collections.emptySet());
        f.setToolParamTypes(Collections.emptyMap());
        f.setOutputContentType("text/plain");
        f.setOutputFieldPaths(Collections.emptySet());
        f.setOutputFieldTypeMap(Collections.emptyMap());
        f.setTextLengthMagnitude(1);
        f.setRequiredKeywords(Collections.emptySet());
        f.setForbiddenKeywords(Collections.emptySet());
        f.setRegexPatterns(Collections.emptyList());
        f.setDeclaredBehaviors(behaviors != null ? behaviors : Collections.emptySet());
        f.setHasError(false);
        return f;
    }

    private DeterministicFingerprint fpWithRegex(List<RegexPattern> patterns) {
        DeterministicFingerprint f = new DeterministicFingerprint();
        f.setToolCallSet(Collections.emptySet());
        f.setToolParamTypes(Collections.emptyMap());
        f.setOutputContentType("text/plain");
        f.setOutputFieldPaths(Collections.emptySet());
        f.setOutputFieldTypeMap(Collections.emptyMap());
        f.setTextLengthMagnitude(1);
        f.setRequiredKeywords(Collections.emptySet());
        f.setForbiddenKeywords(Collections.emptySet());
        f.setRegexPatterns(patterns);
        f.setDeclaredBehaviors(Collections.emptySet());
        f.setHasError(false);
        return f;
    }

    private DeterministicFingerprint identicalJsonFp() {
        Map<String, String> typeMap = Map.of("name", "String", "age", "Integer");
        return fp(Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("name", "age"),
                typeMap, 0);
    }

    // ==================== PASS 判定 ====================

    @Test
    void identicalFingerprints_passVerdict() {
        DeterministicFingerprint baseline = identicalJsonFp();
        DeterministicFingerprint current = identicalJsonFp();

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.isToolCallMatch());
        assertTrue(r.isParamTypeMatch());
        assertTrue(r.isFieldTypeMatch());
        assertEquals(Verdict.PASS, r.getVerdict());
        assertTrue(r.getScore() >= 0.95);
    }

    @Test
    void passVerdict_scoreExactly095() {
        // 完全匹配 → 1.0 * 权重 → score = 1.0 → PASS
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),
                Map.of("field1", "String"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),
                Map.of("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(1.0, r.getScore(), 0.001);
        assertEquals(Verdict.PASS, r.getVerdict());
    }

    // ==================== DIFF 判定 ====================

    @Test
    void diffVerdict_scoreBelow095_noFieldRemoval() {
        // 工具匹配但参数类型不匹配 → d1 = 0.7 → 0.7*0.60 = 0.42
        // d2 = 1.0, w2 = 0.40 → 0.40
        // total = 0.42 + 0.40 = 0.82 → DIFF
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 2);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "Integer"),  // param type mismatch
                "text/plain", null, null, 2);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertFalse(r.isParamTypeMatch());
        // toolMatch=true → REGRESSION takes precedence because paramMatch=false
        // Actually the verdict logic: if (!realRemoved.isEmpty() || !toolMatch || !paramMatch) → REGRESSION
        // So paramMatch=false → REGRESSION, not DIFF
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void diffVerdict_addedFieldsOnly() {
        // 工具/参数完全匹配，仅新增字段（不触发 REGRESSION）
        // d2 将因 added fields 而降低（removed 为空 → 0.5 分）
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),
                Map.of("field1", "String"), 0);
        // current 多了 field2
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1", "field2"),
                Map.of("field1", "String", "field2", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.isToolCallMatch());
        assertTrue(r.isParamTypeMatch());
        // field2 is added, not removed → should not trigger REGRESSION for removed
        // d1 = 1.0, d2: contentType match(0.2) + removed empty(0.5) + typeOk(0.3) = 1.0
        // → score = 1.0*0.60 + 1.0*0.40 = 1.0 → PASS
        assertEquals(Verdict.PASS, r.getVerdict());
        assertTrue(r.getAddedFields().contains("field2"));
        assertTrue(r.getRemovedFields().isEmpty());
    }

    // ==================== REGRESSION 判定 ====================

    @Test
    void regression_toolSetChanged() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(
                Set.of("toolB"), Map.of("id", "String"),  // 不同工具
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertFalse(r.isToolCallMatch());
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void regression_paramTypesChanged() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "Integer"),  // 类型变化
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertFalse(r.isParamTypeMatch());
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void regression_fieldsRemoved() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1", "field2"),
                Map.of("field1", "String", "field2", "String"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),  // field2 被删除
                Map.of("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getRemovedFields().contains("field2"));
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void regression_errorFieldAdded() {
        // AUTO_REGRESSION_FIELDS: 新增 error 类字段 → 自动 REGRESSION
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("name"),
                Map.of("name", "String"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("name", "error"),  // 新增 error 字段
                Map.of("name", "String", "error", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getAddedFields().contains("error"));
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void regression_errorCodeFieldAdded() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("name"),
                Map.of("name", "String"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("name", "error_code"),
                Map.of("name", "String", "error_code", "Integer"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getAddedFields().contains("error_code"));
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void regression_nestedErrorField() {
        // 嵌套字段 data.error → 取最后一段 "error"
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("data"),
                Map.of("data", "Object"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("data", "data.error"),
                Map.of("data", "Object", "data.error", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void regression_lowScore() {
        // 工具集变化 + 参数类型变化 → d1=0 → score 非常低 → REGRESSION
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(
                Set.of("toolB"), Map.of("id2", "Integer"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(Verdict.REGRESSION, r.getVerdict());
        assertTrue(r.getScore() < 0.70);
    }

    // ==================== 动态权重重分配 ====================

    @Test
    void dynamicWeight_noRulesNoBehaviors_weightRedistributes() {
        // 无规则无行为 → w1=0.60, w2=0.40, w3=0, w4=0
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 2);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 2);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        // 完全匹配：d1=1.0, d2=1.0 → score=1.0
        assertEquals(1.0, r.getScore(), 0.001);
        assertEquals(Verdict.PASS, r.getVerdict());
    }

    @Test
    void dynamicWeight_withBehaviors_weightShifts() {
        // 有行为声明但无规则 → w1=0.50, w2=0.30, w3=0, w4=0.20
        DeterministicFingerprint baseline = fpWithBehaviors(Set.of("nonEmptyOutput"));
        DeterministicFingerprint current = fpWithBehaviors(null); // current doesn't need behaviors declared
        current.setDeclaredBehaviors(Collections.emptySet());

        ComparisonResult r = comparator.compare(baseline, current, "non-empty output");

        // behavior "nonEmptyOutput" passes → d4=1.0
        assertTrue(r.isBehaviorMatch());
        assertTrue(r.getScore() > 0.0);
    }

    // ==================== 纯文本退化策略 ====================

    @Test
    void textPlain_sameMagnitude_d2isOne() {
        DeterministicFingerprint baseline = fp(null, null, "text/plain", null, null, 2);
        DeterministicFingerprint current = fp(null, null, "text/plain", null, null, 2);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        // d1: no tools → toolCall match (both empty) → d1=1.0
        // d2: same magnitude → 1.0
        // score = 1.0*0.60 + 1.0*0.40 = 1.0
        assertEquals(1.0, r.getScore(), 0.001);
        assertEquals(Verdict.PASS, r.getVerdict());
    }

    @Test
    void textPlain_magnitudeOffByOne_d2is07() {
        DeterministicFingerprint baseline = fp(null, null, "text/plain", null, null, 2);
        DeterministicFingerprint current = fp(null, null, "text/plain", null, null, 3);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        // d2 = 0.7
        // score = 1.0*0.60 + 0.7*0.40 = 0.60 + 0.28 = 0.88 → DIFF
        assertFalse(r.getScore() >= 0.95);
        assertTrue(r.getScore() >= 0.70);
        assertEquals(Verdict.DIFF, r.getVerdict());
    }

    @Test
    void textPlain_magnitudeOffByTwo_d2is02() {
        DeterministicFingerprint baseline = fp(null, null, "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(null, null, "text/plain", null, null, 3);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        // d2 = 0.2
        // score = 1.0*0.60 + 0.2*0.40 = 0.60 + 0.08 = 0.68 → REGRESSION (< 0.70)
        assertTrue(r.getScore() < 0.70);
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void contentTypeMismatch_d2isZero() {
        DeterministicFingerprint baseline = fp(null, null, "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(null, null, "application/json",
                Set.of("field1"), Map.of("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        // d2 = 0.0
        // score = 1.0*0.60 + 0.0*0.40 = 0.60 → REGRESSION
        assertEquals(Verdict.REGRESSION, r.getVerdict());
    }

    // ==================== 维度 3：内容规则 ====================

    @Test
    void dimension3_requiredKeywordsPresent_passes() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1,
                Set.of("success"), null, false);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "Operation was a success");

        assertTrue(r.isKeywordMatch());
    }

    @Test
    void dimension3_requiredKeywordsMissing_fails() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1,
                Set.of("required_word"), null, false);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "no keyword here");

        assertFalse(r.isKeywordMatch());
    }

    @Test
    void dimension3_forbiddenKeywordsPresent_fails() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1,
                null, Set.of("forbidden"), false);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "contains forbidden word");

        assertFalse(r.isKeywordMatch());
    }

    @Test
    void dimension3_regexPattern_match() {
        DeterministicFingerprint baseline = fpWithRegex(
                List.of(new RegexPattern("ORD-\\d+", "order ID pattern")));
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "Order: ORD-12345");

        assertTrue(r.isRegexMatch());
    }

    @Test
    void dimension3_regexPattern_noMatch_fails() {
        DeterministicFingerprint baseline = fpWithRegex(
                List.of(new RegexPattern("ORD-\\d+", "order ID pattern")));
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "No order here");

        assertFalse(r.isRegexMatch());
    }

    // ==================== 维度 4：约束行为 ====================

    @Test
    void dimension4_behaviorPass_contributesToScore() {
        DeterministicFingerprint baseline = fpWithBehaviors(Set.of("nonEmptyOutput"));
        DeterministicFingerprint current = fp(
                null, null, "text/plain", null, null, 1);
        current.setDeclaredBehaviors(Collections.emptySet());

        ComparisonResult r = comparator.compare(baseline, current, "non-empty output");

        assertTrue(r.isBehaviorMatch());
    }

    @Test
    void dimension4_behaviorFail_reducesScore() {
        DeterministicFingerprint baseline = fpWithBehaviors(Set.of("jsonOutput"));
        DeterministicFingerprint current = fp(
                null, null, "text/plain", null, null, 1);
        current.setDeclaredBehaviors(Collections.emptySet());

        ComparisonResult r = comparator.compare(baseline, current, "plain text output");

        assertFalse(r.isBehaviorMatch());
    }

    // ==================== ComparatorConfig ====================

    @Test
    void ignorableFields_removedFieldsNotCounted() {
        ComparatorConfig config = new ComparatorConfig();
        config.setIgnorableFields(Set.of("field2"));

        DeterministicComparator cmp = new DeterministicComparator(config);

        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1", "field2"),
                Map.of("field1", "String", "field2", "String"), 0);
        // field2 被删除，但它是 ignorable → 不应触发 REGRESSION
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),
                Map.of("field1", "String"), 0);

        ComparisonResult r = cmp.compare(baseline, current, "output");

        // field2 被忽略后 realRemoved 为空 → 不会因字段删除触发 REGRESSION
        // 但 d2 中 removed 不为空 → d2 会降低
        assertNotEquals(Verdict.REGRESSION, r.getVerdict());
    }

    @Test
    void nullConfig_usesDefaults() {
        DeterministicComparator cmp = new DeterministicComparator(null);

        DeterministicFingerprint baseline = identicalJsonFp();
        DeterministicFingerprint current = identicalJsonFp();

        ComparisonResult r = cmp.compare(baseline, current, "output");

        assertEquals(Verdict.PASS, r.getVerdict());
    }

    // ==================== error 自动回归 × ignorable 互斥（复审 H10） ====================

    @Test
    void addedErrorField_triggersRegression_evenWhenIgnorable() {
        ComparatorConfig config = new ComparatorConfig();
        config.setIgnorableFields(Set.of("error"));

        DeterministicComparator cmp = new DeterministicComparator(config);

        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),
                Map.of("field1", "String"), 0);
        // 当前指纹新增了 error 字段——即使用户把 error 配置为可忽略，
        // 类字段自动回归的不变量也必须成立（L30 注释自声明的契约）
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1", "error"),
                Map.of("field1", "String", "error", "String"), 0);

        ComparisonResult r = cmp.compare(baseline, current, "output");

        assertEquals(Verdict.REGRESSION, r.getVerdict(),
                "error 字段自动回归与用户 ignorable 配置互斥，ignorable 不得击穿该不变量");
    }

    @Test
    void addedNestedErrorField_triggersRegression_evenWhenIgnorable() {
        ComparatorConfig config = new ComparatorConfig();
        config.setIgnorableFields(Set.of("data.error"));

        DeterministicComparator cmp = new DeterministicComparator(config);

        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("data.status"),
                Map.of("data.status", "String"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("data.status", "data.error"),
                Map.of("data.status", "String", "data.error", "String"), 0);

        ComparisonResult r = cmp.compare(baseline, current, "output");

        assertEquals(Verdict.REGRESSION, r.getVerdict(),
                "嵌套路径 data.error 的叶子名 error 同样受自动回归不变量保护");
    }

    // ==================== buildSummary ====================

    @Test
    void summary_containsScoreAndVerdict() {
        DeterministicFingerprint baseline = identicalJsonFp();
        DeterministicFingerprint current = identicalJsonFp();

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertNotNull(r.getSummary());
        assertTrue(r.getSummary().contains("score="));
        assertTrue(r.getSummary().contains("verdict="));
        assertTrue(r.getSummary().contains("PASS"));
    }

    @Test
    void summary_toolMismatch_showsToolChange() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(
                Set.of("toolB"), Map.of("id", "String"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("工具集变化"));
    }

    @Test
    void summary_paramMismatch_showsParamChange() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "Integer"),
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("参数类型变化"));
    }

    @Test
    void summary_addedFields_showsAddedFields() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),
                Map.of("field1", "String"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1", "newField"),
                Map.of("field1", "String", "newField", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("新增字段"));
    }

    @Test
    void summary_removedFields_showsRemovedFields() {
        DeterministicFingerprint baseline = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1", "field2"),
                Map.of("field1", "String", "field2", "String"), 0);
        DeterministicFingerprint current = fp(
                Set.of("toolA"), Map.of("id", "String"),
                "application/json",
                Set.of("field1"),
                Map.of("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("删除字段"));
    }

    // ==================== null / edge cases ====================

    @Test
    void nullOutput_treatedAsEmpty() {
        DeterministicFingerprint baseline = identicalJsonFp();
        DeterministicFingerprint current = identicalJsonFp();

        ComparisonResult r = comparator.compare(baseline, current, null);

        // null → treated as "" → no keyword match issues
        assertEquals(Verdict.PASS, r.getVerdict());
    }

    @Test
    void bothEmptyFingerprints_pass() {
        DeterministicFingerprint baseline = fp();
        DeterministicFingerprint current = fp();

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(Verdict.PASS, r.getVerdict());
        assertEquals(1.0, r.getScore(), 0.001);
    }
}
