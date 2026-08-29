package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeterministicComparator 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class DeterministicComparatorTest {

    private final DeterministicComparator comparator = new DeterministicComparator(ComparatorConfig.defaults());

    private DeterministicFingerprint fp() {
        return fp(null, null, "text/plain", null, null, 1, null, null, false);
    }

    private static Map<String, String> stringMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    private DeterministicFingerprint fp(Set<String> toolCallSet, Map<String, String> toolParamTypes, String contentType, Set<String> fieldPaths, Map<String, String> fieldTypeMap, int textLengthMagnitude) {
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

    private DeterministicFingerprint fp(Set<String> toolCallSet, Map<String, String> toolParamTypes, String contentType, Set<String> fieldPaths, Map<String, String> fieldTypeMap, int textLengthMagnitude, Set<String> requiredKeywords, Set<String> forbiddenKeywords, boolean hasError) {
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
        Map<String, String> typeMap = stringMap("name", "String", "age", "Integer");
        return fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("name", "age")), typeMap, 0);
    }

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
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(1.0, r.getScore(), 0.001);
        assertEquals(Verdict.PASS, r.getVerdict());
    }

    @Test
    void changedVerdict_paramTypeMismatch() {
        // 二值语义：参数类型变化是可行动差异 → CHANGED（不再区分严重度）
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 2);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "Integer"),  // param type mismatch
                "text/plain", null, null, 2);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertFalse(r.isParamTypeMatch());
        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void changedVerdict_addedFieldsOnly() {
        // 二值语义：字段集相等才算无差异——新增字段也是输出结构维的可行动差异
        // （旧三态下新增字段不影响 verdict 的口径随权重退役一并废除）
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);
        // current 多了 field2
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("field1", "field2")), stringMap("field1", "String", "field2", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.isToolCallMatch());
        assertTrue(r.isParamTypeMatch());
        assertEquals(Verdict.CHANGED, r.getVerdict());
        assertTrue(r.getAddedFields().contains("field2"));
        assertTrue(r.getRemovedFields().isEmpty());
    }

    @Test
    void changed_toolSetChanged() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(Collections.singleton("toolB"), Collections.singletonMap("id", "String"),  // 不同工具
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertFalse(r.isToolCallMatch());
        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void changed_paramTypesChanged() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "Integer"),  // 类型变化
                "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertFalse(r.isParamTypeMatch());
        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void changed_fieldsRemoved() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("field1", "field2")), stringMap("field1", "String", "field2", "String"), 0);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"),  // field2 被删除
                Collections.singletonMap("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getRemovedFields().contains("field2"));
        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void changed_errorFieldAdded() {
        // error 类字段与普通字段同权：二值语义下直判规则退役，
        // 「新增 error 字段」= 输出结构维的可行动差异 → CHANGED
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("name"), Collections.singletonMap("name", "String"), 0);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("name", "error")),  // 新增 error 字段
                stringMap("name", "String", "error", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getAddedFields().contains("error"));
        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void changed_nestedErrorField() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("data"), Collections.singletonMap("data", "Object"), 0);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("data", "data.error")), stringMap("data", "Object", "data.error", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void changed_lowScore() {
        // 工具集变化 + 参数类型变化 → 多维差异 → CHANGED；score 仅作展示参考
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(Collections.singleton("toolB"), Collections.singletonMap("id2", "Integer"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(Verdict.CHANGED, r.getVerdict());
        assertTrue(r.getScore() < 0.70);
    }

    @Test
    void dynamicWeight_noRulesNoBehaviors_weightRedistributes() {
        // 无规则无行为 → w1=0.60, w2=0.40, w3=0, w4=0
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 2);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 2);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        // 完全匹配：d1=1.0, d2=1.0 → score=1.0
        assertEquals(1.0, r.getScore(), 0.001);
        assertEquals(Verdict.PASS, r.getVerdict());
    }

    @Test
    void dynamicWeight_withBehaviors_weightShifts() {
        // 有行为声明但无规则 → w1=0.50, w2=0.30, w3=0, w4=0.20
        DeterministicFingerprint baseline = fpWithBehaviors(Collections.singleton("nonEmptyOutput"));
        DeterministicFingerprint current = fpWithBehaviors(null); // current doesn't need behaviors declared
        current.setDeclaredBehaviors(Collections.emptySet());

        ComparisonResult r = comparator.compare(baseline, current, "non-empty output");

        // behavior "nonEmptyOutput" passes → d4=1.0
        assertTrue(r.isBehaviorMatch());
        assertTrue(r.getScore() > 0.0);
    }

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
    void textPlain_magnitudeOffByOne_changed() {
        DeterministicFingerprint baseline = fp(null, null, "text/plain", null, null, 2);
        DeterministicFingerprint current = fp(null, null, "text/plain", null, null, 3);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        // 纯文本仅长度数量级可察：数量级变化 = 输出结构维差异 → CHANGED
        // （旧三态下 ±1 档落在 DIFF 区间，二值化后同为非 PASS，退出码形状不变）
        assertTrue(r.getScore() >= 0.70);
        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void textPlain_magnitudeOffByTwo_changed() {
        DeterministicFingerprint baseline = fp(null, null, "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(null, null, "text/plain", null, null, 3);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void contentTypeMismatch_changed() {
        DeterministicFingerprint baseline = fp(null, null, "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(null, null, "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertEquals(Verdict.CHANGED, r.getVerdict());
    }

    @Test
    void dimension3_requiredKeywordsPresent_passes() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1, Collections.singleton("success"), null, false);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "Operation was a success");

        assertTrue(r.isKeywordMatch());
    }

    @Test
    void dimension3_requiredKeywordsMissing_fails() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1, Collections.singleton("required_word"), null, false);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "no keyword here");

        assertFalse(r.isKeywordMatch());
    }

    @Test
    void dimension3_forbiddenKeywordsPresent_fails() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1, null, Collections.singleton("forbidden"), false);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "contains forbidden word");

        assertFalse(r.isKeywordMatch());
    }

    @Test
    void dimension3_regexPattern_match() {
        DeterministicFingerprint baseline = fpWithRegex(Collections.singletonList(new RegexPattern("ORD-\\d+", "order ID pattern")));
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "Order: ORD-12345");

        assertTrue(r.isRegexMatch());
    }

    @Test
    void dimension3_regexPattern_noMatch_fails() {
        DeterministicFingerprint baseline = fpWithRegex(Collections.singletonList(new RegexPattern("ORD-\\d+", "order ID pattern")));
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "No order here");

        assertFalse(r.isRegexMatch());
    }

    @Test
    void dimension4_behaviorPass_contributesToScore() {
        DeterministicFingerprint baseline = fpWithBehaviors(Collections.singleton("nonEmptyOutput"));
        DeterministicFingerprint current = fp(null, null, "text/plain", null, null, 1);
        current.setDeclaredBehaviors(Collections.emptySet());

        ComparisonResult r = comparator.compare(baseline, current, "non-empty output");

        assertTrue(r.isBehaviorMatch());
    }

    @Test
    void dimension4_behaviorFail_reducesScore() {
        DeterministicFingerprint baseline = fpWithBehaviors(Collections.singleton("jsonOutput"));
        DeterministicFingerprint current = fp(null, null, "text/plain", null, null, 1);
        current.setDeclaredBehaviors(Collections.emptySet());

        ComparisonResult r = comparator.compare(baseline, current, "plain text output");

        assertFalse(r.isBehaviorMatch());
    }

    @Test
    void ignorableFields_removedFieldsNotCounted() {
        ComparatorConfig config = new ComparatorConfig();
        config.setIgnorableFields(Collections.singleton("field2"));

        DeterministicComparator cmp = new DeterministicComparator(config);

        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("field1", "field2")), stringMap("field1", "String", "field2", "String"), 0);
        // field2 被删除，但它是 ignorable → 归一化后无差异 → PASS
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);

        ComparisonResult r = cmp.compare(baseline, current, "output");

        assertEquals(Verdict.PASS, r.getVerdict());
        assertTrue(r.getRemovedFields().isEmpty());
    }

    @Test
    void nullConfig_usesDefaults() {
        DeterministicComparator cmp = new DeterministicComparator(null);

        DeterministicFingerprint baseline = identicalJsonFp();
        DeterministicFingerprint current = identicalJsonFp();

        ComparisonResult r = cmp.compare(baseline, current, "output");

        assertEquals(Verdict.PASS, r.getVerdict());
    }

    @Test
    void verdictEnum_isBinary() {
        // 判定枚举的面契约：只有 PASS/CHANGED 两值——
        // 三态消亡后任何「中间严重度」的回归都应被视为语义回退
        assertEquals(2, Verdict.values().length);
        assertNotNull(Verdict.valueOf("PASS"));
        assertNotNull(Verdict.valueOf("CHANGED"));
    }

    @Test
    void addedErrorField_ignorableConfig_honored() {
        ComparatorConfig config = new ComparatorConfig();
        config.setIgnorableFields(Collections.singleton("error"));

        DeterministicComparator cmp = new DeterministicComparator(config);

        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);
        // 二值语义下 error 直判规则退役：error 是普通字段，用户把它配置为可忽略
        // 即声明「该字段的出现不构成行为差异」，归一化后无差异 → PASS
        // （旧三态下「error 自动回归优先于 ignorable」的不变量随之废除）
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("field1", "error")), stringMap("field1", "String", "error", "String"), 0);

        ComparisonResult r = cmp.compare(baseline, current, "output");

        assertEquals(Verdict.PASS, r.getVerdict(), "ignorableFields 归一化覆盖一切字段（含 error 类叶子名），这是用户显式声明的口径");
    }

    @Test
    void addedNestedErrorField_ignorableConfig_honored() {
        ComparatorConfig config = new ComparatorConfig();
        config.setIgnorableFields(Collections.singleton("data.error"));

        DeterministicComparator cmp = new DeterministicComparator(config);

        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("data.status"), Collections.singletonMap("data.status", "String"), 0);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("data.status", "data.error")), stringMap("data.status", "String", "data.error", "String"), 0);

        ComparisonResult r = cmp.compare(baseline, current, "output");

        assertEquals(Verdict.PASS, r.getVerdict(), "嵌套路径 data.error 同样受 ignorableFields 归一化覆盖");
    }

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
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(Collections.singleton("toolB"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("工具集变化"));
    }

    @Test
    void summary_paramMismatch_showsParamChange() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "text/plain", null, null, 1);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "Integer"), "text/plain", null, null, 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("参数类型变化"));
    }

    @Test
    void summary_addedFields_showsAddedFields() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("field1", "newField")), stringMap("field1", "String", "newField", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("新增字段"));
    }

    @Test
    void summary_removedFields_showsRemovedFields() {
        DeterministicFingerprint baseline = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", new HashSet<>(Arrays.asList("field1", "field2")), stringMap("field1", "String", "field2", "String"), 0);
        DeterministicFingerprint current = fp(Collections.singleton("toolA"), Collections.singletonMap("id", "String"), "application/json", Collections.singleton("field1"), Collections.singletonMap("field1", "String"), 0);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertTrue(r.getSummary().contains("删除字段"));
    }

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

    @Test
    void summary_includesFieldTypeMismatch() {
        // 字段类型变化参与打分就必须出现在摘要里——否则用户只拿到一个不可解释的分数
        DeterministicFingerprint baseline = fp(null, null, "application/json", Collections.singleton("$.orderId"), stringMap("$.orderId", "string"), 1);
        DeterministicFingerprint current = fp(null, null, "application/json", Collections.singleton("$.orderId"), stringMap("$.orderId", "integer"), 1);

        ComparisonResult r = comparator.compare(baseline, current, "output");

        assertFalse(r.isFieldTypeMatch());
        assertTrue(r.getSummary().contains("字段类型变化"), "类型不匹配必须可见于摘要: " + r.getSummary());
    }
}
