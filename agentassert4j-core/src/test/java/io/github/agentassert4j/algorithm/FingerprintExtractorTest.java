package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FingerprintExtractor 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class FingerprintExtractorTest {

    private InteractionRecord record(List<ToolCall> toolCalls, String modelResponse) {
        InteractionRecord r = new InteractionRecord();
        r.setToolCalls(toolCalls);
        r.setModelResponse(modelResponse);
        return r;
    }

    private ToolCall tc(String name, Map<String, String> argTypes) {
        return tc(name, argTypes, true);
    }

    private ToolCall tc(String name, Map<String, String> argTypes, boolean success) {
        ToolCall tc = new ToolCall();
        tc.setToolName(name);
        tc.setArgTypes(argTypes);
        tc.setSuccess(success);
        return tc;
    }

    @Test
    void dim1_singleTool_extractsToolCallSet() {
        InteractionRecord r = record(Collections.singletonList(tc("queryOrder", Collections.singletonMap("orderId", "String"))), "{\"result\":\"ok\"}");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals(Collections.singleton("queryOrder"), fp.getToolCallSet());
    }

    @Test
    void dim1_multiTool_extractsAllNames() {
        InteractionRecord r = record(Arrays.asList(tc("toolA", null), tc("toolB", null)), "{}");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals(new HashSet<>(Arrays.asList("toolA", "toolB")), fp.getToolCallSet());
    }

    @Test
    void dim1_extractsParamTypes() {
        InteractionRecord r = record(Arrays.asList(tc("tool", stringMap("orderId", "String", "limit", "Integer"))), "{}");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals(stringMap("orderid", "string", "limit", "integer"), fp.getToolParamTypes());
    }

    @Test
    void dim1_multiTool_mergesParamTypes() {
        InteractionRecord r = record(Arrays.asList(tc("toolA", Collections.singletonMap("a", "String")), tc("toolB", Collections.singletonMap("b", "Integer"))), "{}");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals(2, fp.getToolParamTypes().size());
        assertEquals("string", fp.getToolParamTypes().get("a"));
        assertEquals("integer", fp.getToolParamTypes().get("b"));
    }

    @Test
    void dim1_noToolCalls_emptySets() {
        InteractionRecord r = record(null, "hello");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertNotNull(fp.getToolCallSet());
        assertTrue(fp.getToolCallSet().isEmpty());
        assertTrue(fp.getToolParamTypes().isEmpty());
    }

    @Test
    void dim1_emptyToolCalls_emptySets() {
        InteractionRecord r = record(Collections.emptyList(), "hello");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertTrue(fp.getToolCallSet().isEmpty());
    }

    @Test
    void dim1_toolCallNoArgTypes_emptyParamTypes() {
        InteractionRecord r = record(Collections.singletonList(tc("toolA", null)), "{}");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertTrue(fp.getToolParamTypes().isEmpty());
    }

    @Test
    void dim2_jsonObject_contentTypeAndFields() {
        String json = "{\"name\":\"Alice\",\"age\":30,\"active\":true}";
        InteractionRecord r = record(null, json);

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals("application/json", fp.getOutputContentType());
        assertFalse(fp.getOutputFieldPaths().isEmpty());
        assertFalse(fp.getOutputFieldTypeMap().isEmpty());
        assertEquals(0, fp.getTextLengthMagnitude());
    }

    @Test
    void dim2_jsonArray_contentTypeAndFields() {
        String json = "[{\"id\":1,\"name\":\"A\"},{\"id\":2,\"name\":\"B\"}]";
        InteractionRecord r = record(null, json);

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals("application/json", fp.getOutputContentType());
        assertFalse(fp.getOutputFieldPaths().isEmpty());
    }

    @Test
    void dim2_nestedJson_extractsDeepPaths() {
        String json = "{\"user\":{\"name\":\"Bob\",\"address\":{\"city\":\"NYC\"}}}";
        InteractionRecord r = record(null, json);

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertTrue(fp.getOutputFieldPaths().stream().anyMatch(p -> p.contains("user")));
        assertTrue(fp.getOutputFieldPaths().stream().anyMatch(p -> p.contains("address")));
    }

    @Test
    void dim2_plainText_contentTypeTextPlain() {
        InteractionRecord r = record(null, "This is a plain text response");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals("text/plain", fp.getOutputContentType());
        assertTrue(fp.getOutputFieldPaths().isEmpty());
        assertTrue(fp.getOutputFieldTypeMap().isEmpty());
    }

    @Test
    void dim2_plainText_textLengthMagnitude() {
        // 1-9 字 → magnitude 1
        InteractionRecord r1 = record(null, "hello");
        assertEquals(1, FingerprintExtractor.extract(r1).getTextLengthMagnitude());

        // 10-99 字 → magnitude 2
        InteractionRecord r2 = record(null, repeat("a", 50));
        assertEquals(2, FingerprintExtractor.extract(r2).getTextLengthMagnitude());

        // 100-999 字 → magnitude 3
        InteractionRecord r3 = record(null, repeat("a", 500));
        assertEquals(3, FingerprintExtractor.extract(r3).getTextLengthMagnitude());

        // 1000+ 字 → magnitude 4
        InteractionRecord r4 = record(null, repeat("a", 1000));
        assertEquals(4, FingerprintExtractor.extract(r4).getTextLengthMagnitude());
    }

    @Test
    void dim2_nullResponse_textPlainZeroMagnitude() {
        InteractionRecord r = record(null, null);

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals("text/plain", fp.getOutputContentType());
        assertEquals(0, fp.getTextLengthMagnitude());
        assertTrue(fp.getOutputFieldPaths().isEmpty());
    }

    @Test
    void dim2_blankResponse_textPlainZeroMagnitude() {
        InteractionRecord r = record(null, "   ");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals("text/plain", fp.getOutputContentType());
        assertEquals(0, fp.getTextLengthMagnitude());
    }

    @Test
    void dim2_emptyString_textPlainZeroMagnitude() {
        InteractionRecord r = record(null, "");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertEquals("text/plain", fp.getOutputContentType());
        assertEquals(0, fp.getTextLengthMagnitude());
    }

    @Test
    void dim3_emptyByDefault() {
        InteractionRecord r = record(null, "hello");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertTrue(fp.getRequiredKeywords().isEmpty());
        assertTrue(fp.getForbiddenKeywords().isEmpty());
        assertTrue(fp.getRegexPatterns().isEmpty());
    }

    @Test
    void dim4_emptyBehaviorsByDefault() {
        InteractionRecord r = record(null, "hello");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertTrue(fp.getDeclaredBehaviors().isEmpty());
    }

    @Test
    void hasError_trueWhenToolCallFailed() {
        InteractionRecord r = record(Collections.singletonList(tc("tool", null, false)), // success = false
                "error");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertTrue(fp.isHasError());
    }

    @Test
    void hasError_falseWhenAllToolCallsSucceed() {
        InteractionRecord r = record(Collections.singletonList(tc("tool", null, true)), "ok");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertFalse(fp.isHasError());
    }

    @Test
    void hasError_falseWhenNoToolCalls() {
        InteractionRecord r = record(null, "hello");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        assertFalse(fp.isHasError());
    }

    @Test
    void extractWithRules_overridesDim3And4() {
        InteractionRecord r = record(null, "hello");

        // 构建 InvocationRulesConfig
        String rulesJson = "{\"invocations\":{\"testSkill\":{" + "\"requiredKeywords\":[\"keyword1\"]," + "\"forbiddenKeywords\":[\"badword\"]," + "\"behaviors\":[\"nonEmptyOutput\"]}}}";
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson(rulesJson);

        DeterministicFingerprint fp = FingerprintExtractor.extract(r, rules, "testSkill");

        assertEquals(Collections.singleton("keyword1"), fp.getRequiredKeywords());
        assertEquals(Collections.singleton("badword"), fp.getForbiddenKeywords());
        assertEquals(Collections.singleton("nonEmptyOutput"), fp.getDeclaredBehaviors());
    }

    @Test
    void extractWithRules_nullConfig_defaultToEmpty() {
        InteractionRecord r = record(null, "hello");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r, null, null);

        assertTrue(fp.getRequiredKeywords().isEmpty());
        assertTrue(fp.getForbiddenKeywords().isEmpty());
        assertTrue(fp.getDeclaredBehaviors().isEmpty());
    }

    @Test
    void extractWithRules_nullInvocationId_fallsBackToEmptyKey() {
        // 未声明分组（无业务标签）统一落到空键规则——场景层对 templateHash
        // 绑定的未声明分组注入断言依赖此契约
        InteractionRecord r = record(null, "hello");

        String rulesJson = "{\"invocations\":{\"\":{" + "\"requiredKeywords\":[\"keyword1\"]," + "\"behaviors\":[\"nonEmptyOutput\"]}}}";
        InvocationRulesConfig rules = InvocationRulesConfig.fromJson(rulesJson);

        DeterministicFingerprint fp = FingerprintExtractor.extract(r, rules, null);

        assertEquals(Collections.singleton("keyword1"), fp.getRequiredKeywords());
        assertEquals(Collections.singleton("nonEmptyOutput"), fp.getDeclaredBehaviors());
    }

    @Test
    void fullExtraction_toolSkill_jsonOutput() {
        InteractionRecord r = record(Collections.singletonList(tc("queryOrder", Collections.singletonMap("orderId", "String"), true)), "{\"orderId\":\"ORD-001\",\"amount\":99.9,\"items\":[{\"name\":\"Widget\"}]}");

        DeterministicFingerprint fp = FingerprintExtractor.extract(r);

        // 维度 1
        assertEquals(Collections.singleton("queryOrder"), fp.getToolCallSet());
        assertEquals(Collections.singletonMap("orderid", "string"), fp.getToolParamTypes());

        // 维度 2
        assertEquals("application/json", fp.getOutputContentType());
        assertTrue(fp.getOutputFieldPaths().stream().anyMatch(p -> p.contains("orderId")));
        assertTrue(fp.getOutputFieldPaths().stream().anyMatch(p -> p.contains("amount")));

        // 维度 3 & 4 空
        assertTrue(fp.getRequiredKeywords().isEmpty());
        assertTrue(fp.getDeclaredBehaviors().isEmpty());

        // hasError
        assertFalse(fp.isHasError());
    }

    private static String repeat(String s, int n) {
        StringBuilder sb = new StringBuilder(s.length() * n);
        for (int i = 0; i < n; i++) {
            sb.append(s);
        }
        return sb.toString();
    }

    private static Map<String, String> stringMap(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

}
