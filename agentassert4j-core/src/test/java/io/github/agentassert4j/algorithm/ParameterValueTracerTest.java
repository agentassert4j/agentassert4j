package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.spi.StorageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ParameterValueTracerTest {

    private ParameterValueTracer tracer;

    @BeforeEach
    void setUp() {
        tracer = new ParameterValueTracer();
    }

    // ==================== 辅助方法 ====================

    private InteractionRecord record(String skillId, String modelResponse,
                                      List<ToolCall> toolCalls) {
        InteractionRecord r = new InteractionRecord();
        r.setSkillId(skillId);
        r.setModelResponse(modelResponse);
        r.setToolCalls(toolCalls);
        r.setHasToolCalls(toolCalls != null && !toolCalls.isEmpty());
        r.setTemplateHash("hash");
        return r;
    }

    private InteractionRecord record(String skillId, String modelResponse,
                                      List<ToolCall> toolCalls, long timestamp) {
        InteractionRecord r = record(skillId, modelResponse, toolCalls);
        r.setTimestamp(timestamp);
        return r;
    }

    private ToolCall tc(String name, Map<String, Object> arguments) {
        ToolCall tc = new ToolCall();
        tc.setToolName(name);
        tc.setArguments(arguments);
        tc.setSuccess(true);
        return tc;
    }

    // ==================== isMeaningfulValue ====================

    @Test
    void isMeaningfulValue_normalString() {
        assertTrue(tracer.isMeaningfulValue("ORD-2024-001"));
    }

    @Test
    void isMeaningfulValue_pureNumber_excluded() {
        assertFalse(tracer.isMeaningfulValue("12345"));
    }

    @Test
    void isMeaningfulValue_decimal_excluded() {
        assertFalse(tracer.isMeaningfulValue("123.45"));
    }

    @Test
    void isMeaningfulValue_negativeNumber_excluded() {
        assertFalse(tracer.isMeaningfulValue("-42"));
    }

    @Test
    void isMeaningfulValue_shortValue_excluded() {
        assertFalse(tracer.isMeaningfulValue("ab")); // length < 3
    }

    @Test
    void isMeaningfulValue_null_excluded() {
        assertFalse(tracer.isMeaningfulValue(null));
    }

    @Test
    void isMeaningfulValue_exactly3chars_passes() {
        assertTrue(tracer.isMeaningfulValue("abc"));
    }

    // ==================== extractPrefix ====================

    @Test
    void extractPrefix_camelCase() {
        assertEquals("order", tracer.extractPrefix("orderId"));
    }

    @Test
    void extractPrefix_underscore() {
        assertEquals("order", tracer.extractPrefix("order_ref"));
    }

    @Test
    void extractPrefix_hyphen() {
        assertEquals("order", tracer.extractPrefix("order-ref"));
    }

    @Test
    void extractPrefix_singleWord() {
        assertEquals("name", tracer.extractPrefix("name"));
    }

    @Test
    void extractPrefix_null() {
        assertEquals("", tracer.extractPrefix(null));
    }

    @Test
    void extractPrefix_empty() {
        assertEquals("", tracer.extractPrefix(""));
    }

    // ==================== extractFieldValues ====================

    @Test
    void extractFieldValues_jsonObject() {
        InteractionRecord r = record("s1",
                "{\"orderId\":\"ORD-001\",\"amount\":99.9}", null);
        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("ORD-001"));
        // number values: 99.9 → "99.9"
        assertTrue(values.stream().anyMatch(v -> v.contains("99")));
    }

    @Test
    void extractFieldValues_nestedJson() {
        InteractionRecord r = record("s1",
                "{\"user\":{\"name\":\"Bob\",\"address\":{\"city\":\"NYC\"}}}", null);
        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("Bob"));
        assertTrue(values.contains("NYC"));
    }

    @Test
    void extractFieldValues_nullResponse() {
        InteractionRecord r = record("s1", null, null);
        assertTrue(tracer.extractFieldValues(r).isEmpty());
    }

    @Test
    void extractFieldValues_plainText() {
        // 非 JSON 文本，RecursiveJsonParser.parse() 返回 null
        InteractionRecord r = record("s1", "Hello World", null);
        // "Hello World" 不是 JSON，parse 返回 null
        Set<String> values = tracer.extractFieldValues(r);
        assertTrue(values.isEmpty());
    }

    @Test
    void extractFieldValues_jsonArray() {
        InteractionRecord r = record("s1",
                "[{\"id\":\"A1\"},{\"id\":\"B2\"}]", null);
        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("A1"));
        assertTrue(values.contains("B2"));
    }

    @Test
    void extractFieldValues_nullRecord() {
        assertTrue(tracer.extractFieldValues(null).isEmpty());
    }

    // ==================== extractArgValues ====================

    @Test
    void extractArgValues_withArguments() {
        InteractionRecord r = record("s1", null,
                List.of(tc("tool", Map.of("orderId", (Object) "ORD-001", "limit", 10))));

        Set<String> values = tracer.extractArgValues(r);
        assertTrue(values.contains("ORD-001"));
        assertTrue(values.contains("10"));
    }

    @Test
    void extractArgValues_noToolCalls() {
        InteractionRecord r = record("s1", null, null);
        assertTrue(tracer.extractArgValues(r).isEmpty());
    }

    @Test
    void extractArgValues_nullArguments() {
        ToolCall tc = new ToolCall();
        tc.setToolName("tool");
        tc.setArguments(null);
        InteractionRecord r = record("s1", null, List.of(tc));
        assertTrue(tracer.extractArgValues(r).isEmpty());
    }

    // ==================== extractFieldNames ====================

    @Test
    void extractFieldNames_jsonObject() {
        InteractionRecord r = record("s1",
                "{\"orderId\":\"ORD-001\",\"amount\":100}", null);
        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("orderId"));
        assertTrue(names.contains("amount"));
    }

    @Test
    void extractFieldNames_nestedJson() {
        InteractionRecord r = record("s1",
                "{\"user\":{\"name\":\"Bob\"}}", null);
        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("user"));
        assertTrue(names.contains("name"));
    }

    @Test
    void extractFieldNames_nullResponse() {
        InteractionRecord r = record("s1", null, null);
        assertTrue(tracer.extractFieldNames(r).isEmpty());
    }

    // ==================== extractArgNames ====================

    @Test
    void extractArgNames_withArguments() {
        InteractionRecord r = record("s1", null,
                List.of(tc("tool", Map.of("orderId", (Object) "x", "limit", 10))));

        Set<String> names = tracer.extractArgNames(r);
        assertTrue(names.contains("orderId"));
        assertTrue(names.contains("limit"));
    }

    @Test
    void extractArgNames_noToolCalls() {
        InteractionRecord r = record("s1", null, null);
        assertTrue(tracer.extractArgNames(r).isEmpty());
    }

    // ==================== traceDependency ====================

    @Test
    void traceDependency_valueMatch_highConfidence() {
        // prev 返回 {"orderId":"ORD-001"}，curr 参数 {orderRef: "ORD-001"}
        InteractionRecord prev = record("skillA",
                "{\"orderId\":\"ORD-001\"}", List.of(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok",
                List.of(tc("toolB", Map.of("orderRef", (Object) "ORD-001"))), 2000);

        tracer.traceDependency(List.of(prev, curr));

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
        assertEquals(1, tracer.getGraph().edgeCount());
        assertEquals(Confidence.HIGH, tracer.getGraph().getAllEdges().get(0).getConfidence());
    }

    @Test
    void traceDependency_namePrefixMatch_lowConfidence() {
        // prev 返回 {"orderId":"ORD-001"}，curr 参数 {orderRef: "SOMETHING_ELSE"}
        // 值不匹配但前缀 "order" 匹配
        InteractionRecord prev = record("skillA",
                "{\"orderId\":\"ORD-001\"}", List.of(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok",
                List.of(tc("toolB", Map.of("orderRef", (Object) "SOMETHING_ELSE"))), 2000);

        tracer.traceDependency(List.of(prev, curr));

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
        assertEquals(Confidence.LOW, tracer.getGraph().getAllEdges().get(0).getConfidence());
    }

    @Test
    void traceDependency_noMatch_noEdge() {
        // prev 返回 {"amount":100}，curr 参数 {name: "test"}
        // 值不匹配，前缀不匹配
        InteractionRecord prev = record("skillA",
                "{\"amount\":100}", List.of(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok",
                List.of(tc("toolB", Map.of("name", (Object) "test"))), 2000);

        tracer.traceDependency(List.of(prev, curr));

        assertEquals(0, tracer.getGraph().edgeCount());
    }

    @Test
    void traceDependency_sameSkill_noEdge() {
        InteractionRecord r1 = record("skillA", "{}",
                List.of(tc("tool", null)), 1000);
        InteractionRecord r2 = record("skillA", "{}",
                List.of(tc("tool", null)), 2000);

        tracer.traceDependency(List.of(r1, r2));

        assertEquals(0, tracer.getGraph().edgeCount());
    }

    @Test
    void traceDependency_nullChain() {
        assertDoesNotThrow(() -> tracer.traceDependency(null));
    }

    @Test
    void traceDependency_singleRecord() {
        InteractionRecord r = record("skillA", "{}", null, 1000);
        assertDoesNotThrow(() -> tracer.traceDependency(List.of(r)));
    }

    @Test
    void traceDependency_emptyChain() {
        assertDoesNotThrow(() -> tracer.traceDependency(Collections.emptyList()));
    }

    @Test
    void traceDependency_chain_multipleSteps() {
        // A → B → C，A 返回值在 B 参数中使用，B 返回值在 C 参数中使用
        InteractionRecord rA = record("skillA",
                "{\"orderId\":\"ORD-001\"}", List.of(tc("tA", null)), 1000);
        InteractionRecord rB = record("skillB",
                "{\"shipId\":\"SHIP-001\"}",
                List.of(tc("tB", Map.of("orderId", (Object) "ORD-001"))), 2000);
        InteractionRecord rC = record("skillC", "ok",
                List.of(tc("tC", Map.of("shipId", (Object) "SHIP-001"))), 3000);

        tracer.traceDependency(List.of(rA, rB, rC));

        // A → B, B → C
        assertEquals(2, tracer.getGraph().edgeCount());
        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
        assertTrue(tracer.getGraph().getSuccessors("skillB").contains("skillC"));
    }

    // ==================== rebuildGraph ====================

    @Test
    void rebuildGraph_withSimpleRepository() {
        // 创建简单内存仓库
        StorageRepository repo = new SimpleTestRepo(
                List.of("session1"),
                Map.of("session1", List.of(
                        record("skillA", "{\"orderId\":\"ORD-001\"}",
                                List.of(tc("tA", null)), 1000),
                        record("skillB", "ok",
                                List.of(tc("tB", Map.of("orderId", (Object) "ORD-001"))), 2000)
                ))
        );

        tracer.rebuildGraph(repo);

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
    }

    @Test
    void getGraph_returnsInstance() {
        InMemoryDependencyGraph custom = new InMemoryDependencyGraph();
        ParameterValueTracer t = new ParameterValueTracer(custom);
        assertSame(custom, t.getGraph());
    }

    // ==================== 简单测试仓库 ====================

    private static class SimpleTestRepo implements StorageRepository {
        private final List<String> sessionIds;
        private final Map<String, List<InteractionRecord>> data;

        SimpleTestRepo(List<String> sessionIds, Map<String, List<InteractionRecord>> data) {
            this.sessionIds = sessionIds;
            this.data = data;
        }

        @Override public String type() { return "test"; }
        @Override public void initialize() {}
        @Override public void close() {}
        @Override public void saveInteraction(InteractionRecord r) {}
        @Override public void saveInteractions(List<InteractionRecord> records) {}
        @Override public List<InteractionRecord> findBySkillId(String skillId) { return Collections.emptyList(); }
        @Override public List<InteractionRecord> findByTemplateHash(String hash) { return Collections.emptyList(); }
        @Override public Set<String> findSkillIdsByTemplateHash(String hash) { return Collections.emptySet(); }
        @Override public List<InteractionRecord> findBySessionId(String sessionId) {
            return data.getOrDefault(sessionId, Collections.emptyList());
        }
        @Override public List<String> findAllSessionIds() { return sessionIds; }
        @Override public void saveSkillProfile(io.github.agentassert4j.model.SkillProfile p) {}
        @Override public io.github.agentassert4j.model.SkillProfile findSkillByGroupKey(String key) { return null; }
        @Override public List<io.github.agentassert4j.model.SkillProfile> findAllSkills() { return Collections.emptyList(); }
        @Override public void saveTemplateText(String hash, String templateText) {}
        @Override public String findTemplateText(String hash) { return null; }
        @Override public void saveGraph(String graphJson) {}
        @Override public String loadGraph() { return null; }
        @Override public void archiveBaseline(String skillId, io.github.agentassert4j.model.DeterministicFingerprint fp, String tag) {}
        @Override public io.github.agentassert4j.model.ArchivedBaseline findArchivedBaseline(String skillId, String tag) { return null; }
    }
}
