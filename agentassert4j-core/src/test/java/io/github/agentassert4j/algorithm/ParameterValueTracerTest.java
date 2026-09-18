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
        tracer = new ParameterValueTracer(new InMemoryDependencyGraph());
    }

    private InteractionRecord record(String invocationId, String modelResponse, List<ToolCall> toolCalls, long timestamp) {
        InteractionRecord r = new InteractionRecord();
        r.setInvocationId(invocationId);
        r.setInvocationKey("invocation:" + invocationId + ":hash");
        r.setModelResponse(modelResponse);
        r.setToolCalls(toolCalls);
        r.setHasToolCalls(toolCalls != null && !toolCalls.isEmpty());
        r.setTemplateHash("hash");
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
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-001\",\"amount\":99.9}", null, 0L);
        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("ORD-001"));
        // 数值：99.9 → "99.9"
        assertTrue(values.stream().anyMatch(v -> v.contains("99")));
    }

    @Test
    void extractFieldValues_nestedJson() {
        InteractionRecord r = record("s1", "{\"user\":{\"name\":\"Bob\",\"address\":{\"city\":\"NYC\"}}}", null, 0L);
        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("Bob"));
        assertTrue(values.contains("NYC"));
    }

    @Test
    void extractFieldValues_nullResponse() {
        InteractionRecord r = record("s1", null, null, 0L);
        assertTrue(tracer.extractFieldValues(r).isEmpty());
    }

    @Test
    void extractFieldValues_plainText() {
        // 非 JSON 文本，RecursiveJsonParser.parse() 返回 null
        InteractionRecord r = record("s1", "Hello World", null, 0L);
        // "Hello World" 不是 JSON，parse 返回 null
        Set<String> values = tracer.extractFieldValues(r);
        assertTrue(values.isEmpty());
    }

    @Test
    void extractFieldValues_jsonArray() {
        InteractionRecord r = record("s1", "[{\"id\":\"A1\"},{\"id\":\"B2\"}]", null, 0L);
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
        // 字段值的唯一来源=录制的工具返回；模型回复文本在结果可用时不得混入
        ToolCall call = tc("query", null);
        call.setResult("{\"orderId\":\"ORD-9\"}");
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-1\"}", Collections.singletonList(call), 0L);

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
        InteractionRecord r = record("s1", "已处理", Arrays.asList(first, second), 0L);

        Set<String> values = tracer.extractFieldValues(r);

        assertTrue(values.contains("ORD-1"));
        assertTrue(values.contains("SHIP-2"));
    }

    @Test
    void extractFieldValues_toolResultPlainYield_replyNotMined() {
        // 值源按记录形状二选一：带录制结果的记录即使解析不出叶子值，也不改挖回复文本
        ToolCall call = tc("query", null);
        call.setResult("订单已发货，请注意查收");
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-1\"}", Collections.singletonList(call), 0L);

        assertTrue(tracer.extractFieldValues(r).isEmpty());
    }

    @Test
    void extractFieldValues_toolResultNull_usesModelResponse() {
        // 未捕获工具结果的记录：值源=模型回复文本
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-1\"}", Collections.singletonList(tc("query", null)), 0L);

        assertTrue(tracer.extractFieldValues(r).contains("ORD-1"));
    }

    @Test
    void extractFieldNames_prefersToolResult_overModelResponse() {
        ToolCall call = tc("query", null);
        call.setResult("{\"orderId\":\"ORD-9\"}");
        InteractionRecord r = record("s1", "{\"legacyField\":\"x\"}", Collections.singletonList(call), 0L);

        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("orderId"));
        assertFalse(names.contains("legacyField"));
    }

    @Test
    void extractArgValues_withArguments() {
        InteractionRecord r = record("s1", null, Arrays.asList(tc("tool", objectMap("orderId", "ORD-001", "limit", 10))), 0L);

        Set<String> values = tracer.extractArgValues(r);
        assertTrue(values.contains("ORD-001"));
        assertTrue(values.contains("10"));
    }

    @Test
    void extractArgValues_noToolCalls() {
        InteractionRecord r = record("s1", null, null, 0L);
        assertTrue(tracer.extractArgValues(r).isEmpty());
    }

    @Test
    void extractArgValues_nullArguments() {
        ToolCall tc = new ToolCall();
        tc.setToolName("tool");
        tc.setArguments(null);
        InteractionRecord r = record("s1", null, Collections.singletonList(tc), 0L);
        assertTrue(tracer.extractArgValues(r).isEmpty());
    }

    @Test
    void extractFieldNames_jsonObject() {
        InteractionRecord r = record("s1", "{\"orderId\":\"ORD-001\",\"amount\":100}", null, 0L);
        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("orderId"));
        assertTrue(names.contains("amount"));
    }

    @Test
    void extractFieldNames_nestedJson() {
        InteractionRecord r = record("s1", "{\"user\":{\"name\":\"Bob\"}}", null, 0L);
        Set<String> names = tracer.extractFieldNames(r);

        assertTrue(names.contains("user"));
        assertTrue(names.contains("name"));
    }

    @Test
    void extractFieldNames_nullResponse() {
        InteractionRecord r = record("s1", null, null, 0L);
        assertTrue(tracer.extractFieldNames(r).isEmpty());
    }

    @Test
    void extractArgNames_withArguments() {
        InteractionRecord r = record("s1", null, Arrays.asList(tc("tool", objectMap("orderId", "x", "limit", 10))), 0L);

        Set<String> names = tracer.extractArgNames(r);
        assertTrue(names.contains("orderId"));
        assertTrue(names.contains("limit"));
    }

    @Test
    void extractArgNames_noToolCalls() {
        InteractionRecord r = record("s1", null, null, 0L);
        assertTrue(tracer.extractArgNames(r).isEmpty());
    }

    @Test
    void traceDependency_valueMatch_highConfidence() {
        // prev 返回 {"orderId":"ORD-001"}，curr 参数 {orderRef: "ORD-001"}
        InteractionRecord prev = record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok", Collections.singletonList(tc("toolB", Collections.singletonMap("orderRef", (Object) "ORD-001"))), 2000);

        tracer.traceDependency(Arrays.asList(prev, curr));

        assertTrue(hasEdge(tracer.getGraph(), "invocation:" + "skillA" + ":hash", "invocation:" + "skillB" + ":hash"));
        assertEquals(1, tracer.getGraph().getAllEdges().size());
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

        assertTrue(hasEdge(tracer.getGraph(), "invocation:" + "skillA" + ":hash", "invocation:" + "skillB" + ":hash"));
        assertEquals(Confidence.HIGH, tracer.getGraph().getAllEdges().get(0).getConfidence());
    }

    @Test
    void traceDependency_namePrefixMatch_lowConfidence() {
        // prev 返回 {"orderId":"ORD-001"}，curr 参数 {orderRef: "SOMETHING_ELSE"}
        // 值不匹配但前缀 "order" 匹配
        InteractionRecord prev = record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok", Collections.singletonList(tc("toolB", Collections.singletonMap("orderRef", (Object) "SOMETHING_ELSE"))), 2000);

        tracer.traceDependency(Arrays.asList(prev, curr));

        assertTrue(hasEdge(tracer.getGraph(), "invocation:" + "skillA" + ":hash", "invocation:" + "skillB" + ":hash"));
        assertEquals(Confidence.LOW, tracer.getGraph().getAllEdges().get(0).getConfidence());
    }

    @Test
    void traceDependency_noMatch_noEdge() {
        // prev 返回 {"amount":100}，curr 参数 {name: "test"}
        // 值不匹配，前缀不匹配
        InteractionRecord prev = record("skillA", "{\"amount\":100}", Collections.singletonList(tc("toolA", null)), 1000);
        InteractionRecord curr = record("skillB", "ok", Collections.singletonList(tc("toolB", Collections.singletonMap("name", (Object) "test"))), 2000);

        tracer.traceDependency(Arrays.asList(prev, curr));

        assertEquals(0, tracer.getGraph().getAllEdges().size());
    }

    @Test
    void traceDependency_sameSkill_noEdge() {
        InteractionRecord r1 = record("skillA", "{}", Collections.singletonList(tc("tool", null)), 1000);
        InteractionRecord r2 = record("skillA", "{}", Collections.singletonList(tc("tool", null)), 2000);

        tracer.traceDependency(Arrays.asList(r1, r2));

        assertEquals(0, tracer.getGraph().getAllEdges().size());
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
        assertEquals(2, tracer.getGraph().getAllEdges().size());
        assertTrue(hasEdge(tracer.getGraph(), "invocation:" + "skillA" + ":hash", "invocation:" + "skillB" + ":hash"));
        assertTrue(hasEdge(tracer.getGraph(), "invocation:" + "skillB" + ":hash", "invocation:" + "skillC" + ":hash"));
    }

    @Test
    void rebuildGraph_withSimpleRepository() {
        // 创建简单内存仓库
        StorageRepository repo = new SimpleTestRepo(Collections.singletonList("session1"), Collections.singletonMap("session1", Arrays.asList(record("skillA", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tA", null)), 1000), record("skillB", "ok", Collections.singletonList(tc("tB", Collections.singletonMap("orderId", (Object) "ORD-001"))), 2000))));

        tracer.rebuildGraph(repo);

        assertTrue(hasEdge(tracer.getGraph(), "invocation:" + "skillA" + ":hash", "invocation:" + "skillB" + ":hash"));
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

        assertTrue(hasEdge(tracer.getGraph(), "invocation:" + "skillA" + ":hash", "invocation:" + "skillB" + ":hash"), "同 timestamp 时必须按 recordId 平局决胜，保证依赖边方向确定");
    }

    @Test
    void traceDependency_nonAdjacentProvenance_highEdgeWithEvidence() {
        // j=i-2 的输出值进 i 的参数：非相邻直接溯源（值产生后隔步被引用的典型形态）
        InteractionRecord source = record("producer", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tP", null)), 1000);
        source.setRecordId("rec-producer");
        InteractionRecord middle = record("noise", "{\"alpha\":\"beta-val\"}", null, 2000);
        middle.setRecordId("rec-noise");
        InteractionRecord consumer = record("consumer", "ok", Collections.singletonList(tc("tC", Collections.singletonMap("orderRef", (Object) "ORD-001"))), 3000);
        consumer.setRecordId("rec-consumer");

        tracer.traceDependency(Arrays.asList(source, middle, consumer));

        GraphEdge edge = findEdge(tracer.getGraph(), "invocation:producer:hash", "invocation:consumer:hash");
        assertNotNull(edge, "非相邻对值匹配必须建直接溯源 HIGH 边");
        assertEquals(Confidence.HIGH, edge.getConfidence());
        assertEquals("ORD-001", edge.getEvidenceValue());
        assertEquals("rec-producer", edge.getEvidenceSourceRecordId());
        assertEquals("rec-consumer", edge.getEvidenceTargetRecordId());
    }

    @Test
    void traceDependency_lowOnlyAdjacent_nonAdjacentPrefixNoEdge() {
        // 非相邻对只有字段名前缀相撞 → 不建边：LOW 维持仅相邻原义，不随 all-pairs 放宽
        InteractionRecord source = record("producer", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tP", null)), 1000);
        InteractionRecord middle = record("noise", "{\"gamma\":\"delta-val\"}", null, 2000);
        InteractionRecord consumer = record("consumer", "ok", Collections.singletonList(tc("tC", Collections.singletonMap("orderRef", (Object) "SOMETHING_ELSE"))), 3000);

        tracer.traceDependency(Arrays.asList(source, middle, consumer));

        assertEquals(0, tracer.getGraph().getAllEdges().size(), "非相邻对的前缀相撞不建边");
    }

    @Test
    void traceDependency_evidenceDeterministic_acrossRebuilds() {
        // 同夹具两次重建：边集、置信度、证据三元组全等——溯源证据可复现
        InteractionRecord a = record("producer", "{\"orderId\":\"ORD-001\",\"shipId\":\"SHIP-9\"}", Collections.singletonList(tc("tP", null)), 1000);
        a.setRecordId("rec-a");
        InteractionRecord b = record("mid", "{\"other\":\"val-x\"}", Collections.singletonList(tc("tM", Collections.singletonMap("orderId", (Object) "ORD-001"))), 2000);
        b.setRecordId("rec-b");
        InteractionRecord c = record("consumer", "ok", Collections.singletonList(tc("tC", objectMap("shipId", "SHIP-9", "other", "val-x"))), 3000);
        c.setRecordId("rec-c");

        InMemoryDependencyGraph first = new InMemoryDependencyGraph();
        new ParameterValueTracer(first).traceDependency(Arrays.asList(a, b, c));
        InMemoryDependencyGraph second = new InMemoryDependencyGraph();
        new ParameterValueTracer(second).traceDependency(Arrays.asList(a, b, c));

        assertEquals(3, first.getAllEdges().size(), "夹具应产出三条 HIGH 边（含两条非相邻）");
        assertEquals(edgeSignature(first), edgeSignature(second), "两次重建的边集与证据必须全等");
    }

    @Test
    void traceDependency_sameKeyMultiExecution_singleEdgeEarliestEvidence() {
        // 同键两次执行产出同值、第三方消费：键级聚合单边，HIGH→HIGH 保留最早证据
        InteractionRecord firstExec = record("lookup", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tL", null)), 1000);
        firstExec.setRecordId("rec-lookup1");
        InteractionRecord secondExec = record("lookup", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tL", null)), 2000);
        secondExec.setRecordId("rec-lookup2");
        InteractionRecord consumer = record("refund", "ok", Collections.singletonList(tc("tR", Collections.singletonMap("orderRef", (Object) "ORD-001"))), 3000);
        consumer.setRecordId("rec-refund");

        tracer.traceDependency(Arrays.asList(firstExec, secondExec, consumer));

        GraphEdge edge = findEdge(tracer.getGraph(), "invocation:lookup:hash", "invocation:refund:hash");
        assertNotNull(edge, "同键执行与消费方之间必须建边");
        assertEquals(1, tracer.getGraph().getAllEdges().size(), "同键两次执行必须键级聚合为单边");
        assertEquals("rec-lookup1", edge.getEvidenceSourceRecordId(), "HIGH→HIGH 保留最早证据");
        assertEquals("rec-refund", edge.getEvidenceTargetRecordId());
    }

    @Test
    void traceDependency_lowUpgradedToHigh_replacesEvidence() {
        // 先 LOW（相邻前缀撞）后 HIGH（后续同目标键执行值匹配）：置信度升级、证据替换为 HIGH 例
        InteractionRecord producer = record("producer", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tP", null)), 1000);
        producer.setRecordId("rec-p");
        InteractionRecord draft = record("refund", "ok", Collections.singletonList(tc("tR1", Collections.singletonMap("orderRef", (Object) "SOMETHING_ELSE"))), 2000);
        draft.setRecordId("rec-draft");
        InteractionRecord finalExec = record("refund", "ok", Collections.singletonList(tc("tR2", Collections.singletonMap("orderId", (Object) "ORD-001"))), 3000);
        finalExec.setRecordId("rec-final");

        tracer.traceDependency(Arrays.asList(producer, draft, finalExec));

        GraphEdge edge = findEdge(tracer.getGraph(), "invocation:producer:hash", "invocation:refund:hash");
        assertNotNull(edge);
        assertEquals(Confidence.HIGH, edge.getConfidence(), "LOW 必须被后续 HIGH 升级");
        assertEquals("ORD-001", edge.getEvidenceValue(), "升级时证据替换为 HIGH 例");
        assertEquals("rec-p", edge.getEvidenceSourceRecordId());
        assertEquals("rec-final", edge.getEvidenceTargetRecordId());
    }

    @Test
    void traceDependency_perRoundToolTurnResult_highEdgeFromHistoryTurn() {
        // 逐轮成记录形状：发起帧轮不带结果，工具返回住在下一轮请求历史的 tool 角色帧
        // ——该形状的值源必须同样支撑 HIGH 溯源边（LangChain4j 面 / 关闭内部执行的
        // Spring AI 面的采集形状）
        InteractionRecord frameRound = record("order", null, Collections.singletonList(tc("getOrder", Collections.singletonMap("orderId", (Object) "SO-77"))), 1000);
        frameRound.setRecordId("rec-frame");
        InteractionRecord resultRound = record("order", "已退款 REF-8841", null, 2000);
        resultRound.setRecordId("rec-result");
        TurnContext toolTurn = new TurnContext("tool", "{\"orderId\":\"SO-77\",\"refundId\":\"REF-8841\"}");
        toolTurn.setToolName("getOrder");
        resultRound.setPreviousTurns(Collections.singletonList(toolTurn));
        InteractionRecord logisticsFrame = record("logistics", null, Collections.singletonList(tc("traceLogistics", Collections.singletonMap("refundId", (Object) "REF-8841"))), 3000);
        logisticsFrame.setRecordId("rec-logi");

        tracer.traceDependency(Arrays.asList(frameRound, resultRound, logisticsFrame));

        GraphEdge edge = findEdge(tracer.getGraph(), "invocation:order:hash", "invocation:logistics:hash");
        assertNotNull(edge, "工具结果住在 previousTurns 的逐轮形状也必须出 HIGH 溯源边");
        assertEquals(Confidence.HIGH, edge.getConfidence());
        assertEquals("REF-8841", edge.getEvidenceValue());
        assertEquals("rec-result", edge.getEvidenceSourceRecordId(), "证据源 = 携带 tool 结果帧的结果轮");
    }

    @Test
    void extractFieldValues_userOrAssistantTurnsNotValueSource() {
        // 历史轮次只有 user/assistant 文本时不走 tool 帧值源：人类输入不是值的上游，
        // 值源兜底回到模型回复文本
        InteractionRecord r = record("chat", "{\"refundId\":\"REF-OK\"}", null, 1000);
        r.setPreviousTurns(Arrays.asList(new TurnContext("user", "{\"refundId\":\"REF-USER\"}"), new TurnContext("assistant", "{\"refundId\":\"REF-ASSISTANT\"}")));

        Set<String> values = tracer.extractFieldValues(r);

        assertFalse(values.contains("REF-USER"));
        assertFalse(values.contains("REF-ASSISTANT"));
        assertTrue(values.contains("REF-OK"), "无 tool 帧时模型回复仍是值源");
    }

    @Test
    void traceDependency_sameKeyValueFlow_noSelfEdgeNoCycle() {
        // 自环守卫：同键前执行产出值被后执行消费 → 不建 K→K 边、不触发环
        InteractionRecord first = record("lookup", "{\"orderId\":\"ORD-001\"}", Collections.singletonList(tc("tL", null)), 1000);
        InteractionRecord second = record("lookup", "ok", Collections.singletonList(tc("tL", Collections.singletonMap("orderId", (Object) "ORD-001"))), 2000);

        tracer.traceDependency(Arrays.asList(first, second));

        assertEquals(0, tracer.getGraph().getAllEdges().size(), "同键对不建边：自环会污染溯源图并误触环检测");
        assertTrue(tracer.getGraph().detectCycles().isEmpty());
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
        public void initialize() {
        }

        @Override
        public void close() {
        }

        @Override
        public boolean saveInteractionIfAbsent(InteractionRecord r) {
            return false;
        }

        @Override
        public void saveInteractions(List<InteractionRecord> records) {
        }

        @Override
        public List<InteractionRecord> findByInvocationId(String invocationId) {
            return Collections.emptyList();
        }

        @Override
        public List<InteractionRecord> findByInvocationKey(String invocationKey) {
            return Collections.emptyList();
        }

        @Override
        public InteractionRecord findByRecordId(String recordId) {
            return null; // 测试桩不承载按 id 精确查询
        }

        public List<InteractionRecord> findBySessionId(String sessionId) {
            return data.getOrDefault(sessionId, Collections.emptyList());
        }

        @Override
        public List<String> findAllSessionIds() {
            return sessionIds;
        }

        @Override
        public void saveInvocationProfile(InvocationProfile p) {
        }

        @Override
        public InvocationProfile findInvocationByKey(String key) {
            return null;
        }

        @Override
        public List<InvocationProfile> findAllInvocations() {
            return Collections.emptyList();
        }

        @Override
        public String findTemplateText(String hash) {
            return null;
        }

        @Override
        public void archiveTemplateVersion(ArchivedTemplateVersion archived) {
        }

        @Override
        public ArchivedTemplateVersion findArchivedVersion(String invocationKey, String versionTag) {
            return null;
        }

        @Override
        public List<ArchivedTemplateVersion> findArchivedVersions(String invocationKey) {
            return Collections.emptyList();
        }

        @Override
        public void appendGovernanceEvent(GovernanceEvent event) {
        }

        @Override
        public List<GovernanceEvent> findGovernanceEvents() {
            return Collections.emptyList();
        }

    }

    /**
     * 边存在性断言助手：精简后的图 API 以边枚举为唯一读取入口
     */
    private static boolean hasEdge(InMemoryDependencyGraph g, String src, String tgt) {
        return g.getAllEdges().stream().anyMatch(e -> e.getSource().equals(src) && e.getTarget().equals(tgt));
    }

    /**
     * 按边枚举序取指定边的断言助手（含证据断言场景）。
     */
    private static GraphEdge findEdge(InMemoryDependencyGraph g, String src, String tgt) {
        return g.getAllEdges().stream().filter(e -> e.getSource().equals(src) && e.getTarget().equals(tgt)).findFirst().orElse(null);
    }

    /**
     * 边签名：源>目标|置信|证据三元组，按邻接表枚举序——确定性比对的依据。
     */
    private static List<String> edgeSignature(InMemoryDependencyGraph g) {
        List<String> out = new ArrayList<>();
        for (GraphEdge e : g.getAllEdges()) {
            out.add(e.getSource() + ">" + e.getTarget() + "|" + e.getConfidence() + "|" + e.getEvidenceValue() + "|" + e.getEvidenceSourceRecordId() + "|" + e.getEvidenceTargetRecordId());
        }
        return out;
    }
}
