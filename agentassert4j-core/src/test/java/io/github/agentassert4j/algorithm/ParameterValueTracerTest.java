package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.StorageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ParameterValueTracer 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class ParameterValueTracerTest {

    private ParameterValueTracer tracer;

    @BeforeEach
    void setUp() {
        tracer = new ParameterValueTracer();
    }

    private InteractionRecord record(String skillId, String modelResponse, List<ToolCall> toolCalls) {
        InteractionRecord r = new InteractionRecord();
        r.setSkillId(skillId);
        r.setModelResponse(modelResponse);
        r.setToolCalls(toolCalls);
        r.setHasToolCalls(toolCalls != null && !toolCalls.isEmpty());
        r.setTemplateHash("hash");
        return r;
    }

    private InteractionRecord record(String skillId, String modelResponse, List<ToolCall> toolCalls, long timestamp) {
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

    @Test
    void extractFieldValues_jsonObject() {
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-001\",\"amount\":99.9}", null);
        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("ORD-001"));
        // number values: 99.9 → "99.9"
        assertTrue(values.stream().anyMatch(v -> v.contains("99")));
    }

    @Test
    void extractFieldValues_nestedJson() {
        InteractionRecord r = record("s1", "{\"user\":{\"name\":\"Bob\",\"address\":{\"city\":\"NYC\"}}}", null);
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
        InteractionRecord r = record("s1", "[{\"id\":\"A1\"},{\"id\":\"B2\"}]", null);
        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("A1"));
        assertTrue(values.contains("B2"));
    }

    @Test
    void extractFieldValues_nullRecord() {
        assertTrue(tracer.extractFieldValues(null).isEmpty());
    }

    @Test
    void extractFieldValues_prefersToolResult_overModelResponse() {
        // 字段值真源=录制的工具返回；模型回复文本在结果可用时不得混入
        ToolCall call = tc("query", null);
        call.setResult("{\"orderId\":\"ORD-9\"}");
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-1\"}", Collections.singletonList(call));

        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("ORD-9"));
        assertFalse(values.contains("ORD-1"));
    }

    @Test
    void extractFieldValues_multipleToolResults_merged() {
        ToolCall first = tc("a", null);
        first.setResult("{\"orderId\":\"ORD-1\"}");
        ToolCall second = tc("b", null);
        second.setResult("{\"shipId\":\"SHIP-2\"}");
        InteractionRecord r = record("s1", "已处理", Arrays.asList(first, second));

        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("ORD-1"));
        assertTrue(values.contains("SHIP-2"));
    }

    @Test
    void extractFieldValues_toolResultPlainYield_replyNotMined() {
        // 值源按记录形状二选一：带录制结果的记录即使解析不出叶子值，也不改挖回复文本
        ToolCall call = tc("query", null);
        call.setResult("订单已发货，请注意查收");
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-1\"}", Collections.singletonList(call));

        assertTrue(tracer.extractFieldValues(r).isEmpty());
    }

    @Test
    void extractFieldValues_toolResultNull_usesModelResponse() {
        // 未捕获工具结果的记录：值源=模型回复文本
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-1\"}", Collections.singletonList(tc("query", null)));

        assertTrue(tracer.extractFieldValues(r).contains("ORD-1"));
    }

    @Test
    void extractFieldNames_prefersToolResult_overModelResponse() {
        ToolCall call = tc("query", null);
        call.setResult("{\"orderId\":\"ORD-9\"}");
        InteractionRecord r = record("s1", "{\"legacyField\":\"x\"}", Collections.singletonList(call));

        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("orderId"));
        assertFalse(names.contains("legacyField"));
    }

    @Test
    void extractArgValues_withArguments() {
        InteractionRecord r = record("s1", null, Arrays.asList(tc("tool", objectMap("orderId", "ORD-001", "limit", 10))));

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
        InteractionRecord r = record("s1", null, Collections.singletonList(tc));
        assertTrue(tracer.extractArgValues(r).isEmpty());
    }

    @Test
    void extractFieldNames_jsonObject() {
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-001\",\"amount\":100}", null);
        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("orderId"));
        assertTrue(names.contains("amount"));
    }

    @Test
    void extractFieldNames_nestedJson() {
        InteractionRecord r = record("s1", "{\"user\":{\"name\":\"Bob\"}}", null);
        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("user"));
        assertTrue(names.contains("name"));
    }

    @Test
    void extractFieldNames_nullResponse() {
        InteractionRecord r = record("s1", null, null);
        assertTrue(tracer.extractFieldNames(r).isEmpty());
    }

    @Test
    void extractArgNames_withArguments() {
        InteractionRecord r = record("s1", null, Arrays.asList(tc("tool", objectMap("orderId", "x", "limit", 10))));

        Set<String> names = tracer.extractArgNames(r);
        assertTrue(names.contains("orderId"));
        assertTrue(names.contains("limit"));
    }

    @Test
    void extractArgNames_noToolCalls() {
        InteractionRecord r = record("s1", null, null);
        assertTrue(tracer.extractArgNames(r).isEmpty());
    }

    @Test
    void traceDependency_valueMatch_highConfidence() {
        // prev 返回 {"orderId":"ORD-001"}，curr 参数 {orderRef: "ORD-001"}
        InteractionRecord prev = record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok", Collections.singletonList(tc("toolB", Collections.singletonMap("orderRef", (Object) "ORD-001"))), 2000);

        tracer.traceDependency(Arrays.asList(prev, curr));

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
        assertEquals(1, tracer.getGraph().edgeCount());
        assertEquals(Confidence.HIGH, tracer.getGraph().getAllEdges().get(0).getConfidence());
    }

    @Test
    void traceDependency_highEdge_fromRecordedToolResult() {
        // 前序模型回复是纯文本，订单号只存在于录制的工具返回里——HIGH 边以录制结果为值源
        ToolCall prevCall = tc("toolA", null);
        prevCall.setResult("{\"orderId\":\"ORD-001\"}");
        InteractionRecord prev = record("skillA", "查询完成", Collections.singletonList(prevCall), 1000);
        InteractionRecord curr = record("skillB", "ok", Collections.singletonList(tc("toolB", Collections.singletonMap("orderRef", (Object) "ORD-001"))), 2000);

        tracer.traceDependency(Arrays.asList(prev, curr));

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
        assertEquals(Confidence.HIGH, tracer.getGraph().getAllEdges().get(0).getConfidence());
    }

    @Test
    void traceDependency_namePrefixMatch_lowConfidence() {
        // prev 返回 {"orderId":"ORD-001"}，curr 参数 {orderRef: "SOMETHING_ELSE"}
        // 值不匹配但前缀 "order" 匹配
        InteractionRecord prev = record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok", Collections.singletonList(tc("toolB", Collections.singletonMap("orderRef", (Object) "SOMETHING_ELSE"))), 2000);

        tracer.traceDependency(Arrays.asList(prev, curr));

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
        assertEquals(Confidence.LOW, tracer.getGraph().getAllEdges().get(0).getConfidence());
    }

    @Test
    void traceDependency_noMatch_noEdge() {
        // prev 返回 {"amount":100}，curr 参数 {name: "test"}
        // 值不匹配，前缀不匹配
        InteractionRecord prev = record("skillA", "{\"amount\":100}", Collections.singletonList(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok", Collections.singletonList(tc("toolB", Collections.singletonMap("name", (Object) "test"))), 2000);

        tracer.traceDependency(Arrays.asList(prev, curr));

        assertEquals(0, tracer.getGraph().edgeCount());
    }

    @Test
    void traceDependency_sameSkill_noEdge() {
        InteractionRecord r1 = record("skillA", "{}", Collections.singletonList(tc("tool", null)), 1000);
        InteractionRecord r2 = record("skillA", "{}", Collections.singletonList(tc("tool", null)), 2000);

        tracer.traceDependency(Arrays.asList(r1, r2));

        assertEquals(0, tracer.getGraph().edgeCount());
    }

    @Test
    void traceDependency_nullChain() {
        assertDoesNotThrow(() -> tracer.traceDependency(null));
    }

    @Test
    void traceDependency_singleRecord() {
        InteractionRecord r = record("skillA", "{}", null, 1000);
        assertDoesNotThrow(() -> tracer.traceDependency(Collections.singletonList(r)));
    }

    @Test
    void traceDependency_emptyChain() {
        assertDoesNotThrow(() -> tracer.traceDependency(Collections.emptyList()));
    }

    @Test
    void traceDependency_chain_multipleSteps() {
        // A → B → C，A 返回值在 B 参数中使用，B 返回值在 C 参数中使用
        InteractionRecord rA = record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tA", null)), 1000);
        InteractionRecord rB = record("skillB", "{\"shipId\":\"SHIP-001\"}", Collections.singletonList(tc("tB", Collections.singletonMap("orderId", (Object) "ORD-001"))), 2000);
        InteractionRecord rC = record("skillC", "ok", Collections.singletonList(tc("tC", Collections.singletonMap("shipId", (Object) "SHIP-001"))), 3000);

        tracer.traceDependency(Arrays.asList(rA, rB, rC));

        // A → B, B → C
        assertEquals(2, tracer.getGraph().edgeCount());
        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
        assertTrue(tracer.getGraph().getSuccessors("skillB").contains("skillC"));
    }

    @Test
    void rebuildGraph_withSimpleRepository() {
        // 创建简单内存仓库
        StorageRepository repo = new SimpleTestRepo(Collections.singletonList("session1"), Collections.singletonMap("session1", Arrays.asList(record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tA", null)), 1000), record("skillB", "ok", Collections.singletonList(tc("tB", Collections.singletonMap("orderId", (Object) "ORD-001"))), 2000))));

        tracer.rebuildGraph(repo);

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"));
    }

    @Test
    void getGraph_returnsInstance() {
        InMemoryDependencyGraph custom = new InMemoryDependencyGraph();
        ParameterValueTracer t = new ParameterValueTracer(custom);
        assertSame(custom, t.getGraph());
    }

    @Test
    void rebuildGraph_sameTimestamp_orderDeterministicByRecordId() {
        // 同毫秒交互必须可复现：时间戳相同则由记录 ID 决定次序；
        // 存储返回顺序故意与 ID 次序相反，证明排序与返回顺序无关。
        InteractionRecord first = record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tA", null)), 1000);
        first.setRecordId("a-first");
        InteractionRecord second = record("skillB", "ok", Collections.singletonList(tc("tB", Collections.singletonMap("orderId", (Object) "ORD-001"))), 1000);
        second.setRecordId("b-second");

        StorageRepository repo = new SimpleTestRepo(Collections.singletonList("session1"), Collections.singletonMap("session1", Arrays.asList(second, first)));

        tracer.rebuildGraph(repo);

        assertTrue(tracer.getGraph().getSuccessors("skillA").contains("skillB"), "同 timestamp 时必须按 recordId 平局决胜，保证依赖边方向确定");
    }

    private static Map<String, Object> objectMap(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    private static class SimpleTestRepo implements StorageRepository {
        private final List<String> sessionIds;
        private final Map<String, List<InteractionRecord>> data;

        SimpleTestRepo(List<String> sessionIds, Map<String, List<InteractionRecord>> data) {
            this.sessionIds = sessionIds;
            this.data = data;
        }

        @Override
        public String type() {
            return "test";
        }

        @Override
        public void initialize() {
        }

        @Override
        public void close() {
        }

        @Override
        public void saveInteraction(InteractionRecord r) {
        }

        @Override
        public void saveInteractions(List<InteractionRecord> records) {
        }

        @Override
        public List<InteractionRecord> findBySkillId(String skillId) {
            return Collections.emptyList();
        }

        @Override
        public List<InteractionRecord> findByTemplateHash(String hash) {
            return Collections.emptyList();
        }

        @Override
        public Set<String> findSkillIdsByTemplateHash(String hash) {
            return Collections.emptySet();
        }

        @Override
        public List<InteractionRecord> findBySessionId(String sessionId) {
            return data.getOrDefault(sessionId, Collections.emptyList());
        }

        @Override
        public List<String> findAllSessionIds() {
            return sessionIds;
        }

        @Override
        public void saveSkillProfile(SkillProfile p) {
        }

        @Override
        public SkillProfile findSkillByGroupKey(String key) {
            return null;
        }

        @Override
        public List<SkillProfile> findAllSkills() {
            return Collections.emptyList();
        }

        @Override
        public void saveTemplateText(String hash, String templateText) {
        }

        @Override
        public String findTemplateText(String hash) {
            return null;
        }

        @Override
        public void saveGraph(String graphJson) {
        }

        @Override
        public String loadGraph() {
            return null;
        }

        @Override
        public void archiveBaseline(ArchivedBaseline archived) {
        }

        @Override
        public ArchivedBaseline findArchivedBaseline(String skillId, String tag) {
            return null;
        }

        @Override
        public List<ArchivedBaseline> findArchivedBaselines(String skillId) {
            return Collections.emptyList();
        }
    }
}
