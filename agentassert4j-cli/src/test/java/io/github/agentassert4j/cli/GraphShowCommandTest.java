package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * graph show 命令契约：多轮会话产边渲染、空图提示、注册可达。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class GraphShowCommandTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private String dbPath;
    private final PrintStream originalOut = System.out;
    private ByteArrayOutputStream stdout;

    @BeforeEach
    void setUp() throws Exception {
        dbPath = tempDir.resolve("graph-" + System.nanoTime() + ".db").toString();
        repository = new SqliteStorageRepository(dbPath);
        repository.initialize();
        stdout = new ByteArrayOutputStream();
        System.setOut(new PrintStream(stdout, true, "UTF-8"));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
        repository.close();
    }

    @Test
    @DisplayName("多轮会话值流产出 HIGH 边并渲染，命令经 picocli 注册可达")
    void rendersEdgeFromMultiTurnSession() {
        saveChainRecord("r-1", "queryOrder", 1000L, null, "{\"order_id\":\"SO-77\",\"status\":\"shipped\"}");
        saveChainRecord("r-2", "refundOrder", 2000L, "SO-77", null);

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        // 节点/边正文走 displayKey 短形——完整键不进正文行；节点换行收纳（缩进 2 空格）
        assertTrue(output.contains("Nodes (2):" + System.lineSeparator() + "  queryOrder@hash-r-1, refundOrder@hash-r-2"), "节点行必须换行收纳且走短形: " + output);
        assertTrue(output.contains("queryOrder@hash-r-1 -> refundOrder@hash-r-2  HIGH"), "值流边必须短形渲染并标 HIGH: " + output);
        assertFalse(output.contains(" -> invocation:"), "正文边行不得残留完整键: " + output);
        // HIGH 边证据：命中值（引号包裹）+ 源/目标记录对（ASCII 箭头）
        assertTrue(output.contains("\"SO-77\" (r-1 -> r-2)"), "HIGH 边必须携带证据值与记录对: " + output);
        // 图例：短形 → 完整键逐字映射（完整键是可寻址身份，可直接复制进 --invocation）
        assertTrue(output.contains("queryOrder@hash-r-1 = invocation:queryOrder:hash-r-1"), "图例必须逐字携带完整键: " + output);
        assertTrue(output.contains("refundOrder@hash-r-2 = invocation:refundOrder:hash-r-2"), "图例必须逐字携带完整键: " + output);
        assertTrue(output.contains("Cycles: none"));
    }

    @Test
    @DisplayName("LOW 边仅相邻前缀提示：边行无证据值无记录对")
    void lowEdgeRendersWithoutEvidence() {
        saveChainRecord("r-1", "queryOrder", 1000L, null, "{\"orderId\":\"ORD-1\"}");
        saveChainRecord("r-2", "refundOrder", 2000L, "SOMETHING_ELSE", null);

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("queryOrder@hash-r-1 -> refundOrder@hash-r-2  LOW"), "相邻前缀撞必须渲染 LOW 边: " + output);
        assertFalse(output.contains("(r-1 -> r-2)"), "LOW 是提示不是证据，不得渲染记录对: " + output);
        assertFalse(output.contains("\"ORD-1\""), "LOW 边行不得渲染证据值: " + output);
    }

    @Test
    @DisplayName("无边数据给出全部出边条件与扫描统计，节点全集使空边不等于空图")
    void emptyGraphPrintsSessionHint() {
        saveChainRecord("r-only", "loneSkill", 1000L, null, "{\"k\":\"v\"}");

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("No data-flow edges (scanned 1 record across 1 session"), "空图必须就地披露扫描统计: " + output);
        assertTrue(output.contains("An edge means"), "出边条件必须逐条列明: " + output);
        assertTrue(output.contains("noise filters"), "条件必须披露噪声排除规则: " + output);
        assertTrue(output.contains("Nodes (1)"), "节点全集语义：数据在场即有节点，空边不等于空图: " + output);

        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath, "--json"));
        String graphJson = stdout.toString();
        assertTrue(graphJson.contains("\"nodes\":[\"invocation:loneSkill:"), "机器面必须携带 nodes 数组（只有计数不可寻址）: " + graphJson);
    }

    @Test
    @DisplayName("一轮式工具循环主形态出边：同记录 history 值→响应 tool call 参数，按 tool_use_id 归因到发起记录")
    void wireRoundTripSameRecordHop_attributedToIssuer() {
        // r1：响应发出 get_order（toolCallId=T1）；r2：请求 history 携带 T1 的结果
        // {order:ORD-7}，响应即发起 create_wo(order_id=ORD-7)——值诞生与首次消费
        // 同记录，历史上该跳零边
        InteractionRecord r1 = wireRecord("w-1", 1000L, "getOrder", "hash-w1", null, "T1", null);
        InteractionRecord r2 = wireRecord("w-2", 2000L, "createWork", "hash-w2", "T1", "T2", "ORD-7");
        repository.saveInteractionIfAbsent(r1);
        repository.saveInteractionIfAbsent(r2);

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("getOrder@hash-w1 -> createWork@hash-w2  HIGH"), "主形态必须出边且源头归发起记录: " + output);
        assertTrue(output.contains("\"ORD-7\" (w-1 -> w-2)"), "证据记录对指向发起记录: " + output);
    }

    @Test
    @DisplayName("携带者不冒名源头：中间记录的 history 帧值归因到更早的发起记录")
    void carrierRecordNotAttributedAsSource() {
        // r1 发出 T1；r2 携带 T1 结果（响应纯文本）；r3 的参数用该值——
        // 边必须是 r1→r3（发起方），不得是 r2→r3（携带者）
        InteractionRecord r1 = wireRecord("c-1", 1000L, "lookup", "hash-c1", null, "T1", null);
        InteractionRecord r2 = wireRecord("c-2", 2000L, "narrate", "hash-c2", "T1", null, "VAL-9");
        InteractionRecord r3 = wireRecord("c-3", 3000L, "notify", "hash-c3", null, "T3", "VAL-9");
        r2.setModelResponse("{\"text\":\"中间叙述\"}");
        r2.setToolCalls(new ArrayList<>());
        r2.setHasToolCalls(false);
        repository.saveInteractionIfAbsent(r1);
        repository.saveInteractionIfAbsent(r2);
        repository.saveInteractionIfAbsent(r3);

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("lookup@hash-c1 -> notify@hash-c3  HIGH"), "源头=发起记录 r1: " + output);
        assertFalse(output.contains("narrate@hash-c2 ->"), "携带者不得冒名源头: " + output);
    }

    @Test
    @DisplayName("纯数字 ID 值流：≥6 位整数串过噪声过滤器（订单号形态），小数与短数字仍排除")
    void digitIdValuesFlow() {
        InteractionRecord r1 = wireRecord("d-1", 1000L, "getOrder", "hash-d1", null, "T1", null);
        InteractionRecord r2 = wireRecord("d-2", 2000L, "createWork", "hash-d2", "T1", "T2", "20260930");
        repository.saveInteractionIfAbsent(r1);
        repository.saveInteractionIfAbsent(r2);

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("getOrder@hash-d1 -> createWork@hash-d2  HIGH"), "8 位数字 ID 必须建边: " + output);
        assertTrue(output.contains("20260930"), "命中值必须是数字串本体: " + output);
    }

        /**
     * wire 回灌形态的记录构造：issuerCallId = 本记录响应发出的工具调用 id（无则响应纯文本）；
     * fedBackCallId/fedBackValue = 请求 history 携带的更早调用的结果帧；consumeValue =
     * 本记录响应发出的工具调用实参值（与 fedBackValue 同值即「同记录诞生+消费」）。
     */
    private InteractionRecord wireRecord(String recordId, long ts, String invocationId, String templateHash, String fedBackCallId, String issuerCallId, String value) {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId(recordId);
        record.setSessionId("session-wire");
        record.setTimestamp(ts);
        record.setSeq(ts);
        record.setInvocationId(invocationId);
        record.setTemplateHash(templateHash);
        record.setInvocationKey("invocation:" + invocationId + ":" + templateHash);
        record.setUserInput("输入 " + recordId);
        record.setTurnIndex(0);
        List<TurnContext> turns = new ArrayList<>();
        if (fedBackCallId != null) {
            TurnContext frame = new TurnContext("tool", "{\"ref\":\"" + value + "\"}");
            frame.setToolCallId(fedBackCallId);
            turns.add(frame);
        }
        if (!turns.isEmpty()) {
            record.setPreviousTurns(turns);
        }
        List<ToolCall> calls = new ArrayList<>();
        if (issuerCallId != null) {
            ToolCall call = new ToolCall();
            call.setToolCallId(issuerCallId);
            call.setToolName(invocationId);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("ref", value);
            call.setArguments(args);
            calls.add(call);
        }
        record.setToolCalls(calls);
        record.setHasToolCalls(!calls.isEmpty());
        return record;
    }

    @Test
    @DisplayName("重驱观测记录不进值流图：不产节点不产边（取证面不产仪器伪影）")
    void reDriveObservationsExcludedFromGraph() {
        saveChainRecord("ob-1", "queryOrder", 1000L, null, "{\"order_id\":\"SO-77\"}");
        saveChainRecord("ob-2", "refundOrder", 2000L, "SO-77", null);
        // 观测记录：同会话更晚时间戳，参数值精确引用上游值——若不排除会画出指向观测的边
        InteractionRecord observation = new InteractionRecord();
        observation.setRecordId("ob-obs");
        observation.setSessionId("session-graph");
        observation.setTimestamp(3000L);
        observation.setSeq(3000L);
        observation.setInvocationId("notifyOrder");
        observation.setTemplateHash("hash-ob");
        observation.setInvocationKey("invocation:notifyOrder:hash-ob");
        observation.setUserInput("观测");
        observation.setTurnIndex(0);
        observation.setMetadata("{\"redriveOf\":\"ob-2\",\"redriveTemplateHash\":\"hash-ob\"}");
        ToolCall obsCall = new ToolCall();
        obsCall.setToolName("notify");
        Map<String, Object> obsArgs = new LinkedHashMap<>();
        obsArgs.put("order_id", "SO-77");
        obsCall.setArguments(obsArgs);
        observation.setToolCalls(new ArrayList<>(java.util.Collections.singletonList(obsCall)));
        observation.setHasToolCalls(true);
        repository.saveInteractionIfAbsent(observation);

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("queryOrder@hash-ob-") && output.contains("-> refundOrder@hash-ob-") && output.contains("HIGH"), "业务边保留: " + output);
        assertFalse(output.contains("notifyOrder"), "观测记录不得成为节点或边端点: " + output);
    }

    @Test
    @DisplayName("多值命中：机器面 matchedValues 携带排序全量命中值，人读 evidence 保持首命中代表值")
    void multiValueEdgeCarriesMatchedValues() {
        InteractionRecord upstream = new InteractionRecord();
        upstream.setRecordId("mv-1");
        upstream.setSessionId("session-graph");
        upstream.setTimestamp(1000L);
        upstream.setSeq(1000L);
        upstream.setInvocationId("lookupOrder");
        upstream.setTemplateHash("hash-mv-1");
        upstream.setInvocationKey("invocation:lookupOrder:hash-mv-1");
        upstream.setUserInput("输入 mv-1");
        upstream.setTurnIndex(0);
        upstream.setModelResponse("{\"order_id\":\"SO-77\",\"ticket\":\"TRK-1\"}");
        upstream.setToolCalls(new ArrayList<>());
        upstream.setHasToolCalls(false);
        repository.saveInteractionIfAbsent(upstream);

        InteractionRecord downstream = new InteractionRecord();
        downstream.setRecordId("mv-2");
        downstream.setSessionId("session-graph");
        downstream.setTimestamp(2000L);
        downstream.setSeq(2000L);
        downstream.setInvocationId("shipOrder");
        downstream.setTemplateHash("hash-mv-2");
        downstream.setInvocationKey("invocation:shipOrder:hash-mv-2");
        downstream.setUserInput("输入 mv-2");
        downstream.setTurnIndex(0);
        ToolCall ship = new ToolCall();
        ship.setToolName("ship");
        Map<String, Object> shipArgs = new LinkedHashMap<>();
        shipArgs.put("ref", "SO-77");
        shipArgs.put("ticketRef", "TRK-1");
        ship.setArguments(shipArgs);
        downstream.setToolCalls(new ArrayList<>(java.util.Collections.singletonList(ship)));
        downstream.setHasToolCalls(true);
        repository.saveInteractionIfAbsent(downstream);

        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath, "--json"));
        String graphJson = stdout.toString();
        // 全量命中值按字典序入机器面（SO-77 < TRK-1），取证不再退回记录层手工拼
        assertTrue(graphJson.contains("\"matchedValues\":[\"SO-77\",\"TRK-1\"]"), "机器面必须携带排序全量命中值: " + graphJson);
        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath));
        String human = stdout.toString();
        assertTrue(human.contains("\"SO-77\" (mv-1 -> mv-2)"), "人读 evidence 保持首命中代表值: " + human);
    }

    @Test
    @DisplayName("零边近失诊断：纯数字上游值点名噪声排除，子串包含点名精确相等要求")
    void nearMissDiagnosticsClassifyWhyNoEdge() {
        // 上游叶子是纯数字（isMeaningfulValue 排除）——数字 ID 体系永远无 HIGH 边的事实必须就近可见。
        // 字段名前缀（case/ticket）与参数键前缀（order）刻意错开，避免 LOW 前缀边让边集非空
        saveChainRecord("m-1", "lookupOrder", 1000L, null, "{\"case_id\":1042}");
        saveChainRecord("m-2", "notifyOrder", 2000L, "1042", null);
        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);
        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("Near miss: notifyOrder@"), "零边时必须出现近失诊断: " + output);
        assertTrue(output.contains("noise-excluded"), "纯数字上游值的近失原因必须点名: " + output);

        // 子串包含（值埋在更长文本里）不构成精确相等——条件(2)的行为钉
        saveChainRecord("m-3", "fetchTicket", 3000L, null, "{\"ticket\":\"TRK-9001\"}");
        saveChainRecord("m-4", "closeTicket", 4000L, "ticket TRK-9001 created", null);
        exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);
        assertEquals(0, exit);
        output = stdout.toString();
        assertTrue(output.contains("substrings"), "子串包含的近失原因必须点名: " + output);
    }

    @Test
    @DisplayName("有边库上近失诊断照常在场：诊断与边数解耦，已连通的记录不误报")
    void nearMissesRenderOnGraphsWithEdges_connectedPairsNotFlagged() {
        // 一对连通（HIGH 边）+ 一对大小写不匹配（差在精确相等）同库共存
        saveChainRecord("nm-1", "lookupOrder", 1000L, null, "{\"order_id\":\"ORD-77\"}");
        saveChainRecord("nm-2", "shipOrder", 2000L, "ORD-77", null);
        saveChainRecord("nm-3", "fetchTicket", 3000L, null, "{\"ticket\":\"TRK-9001\"}");
        saveChainRecord("nm-4", "closeTicket", 4000L, "trk-9001", null);

        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath));
        String human = stdout.toString();
        assertTrue(human.contains("\"ORD-77\" (nm-1 -> nm-2)"), "连通对照边必须在场: " + human);
        assertTrue(human.contains("Near misses (1)"), "库里已有边时近失诊断仍必须渲染（此前被零边门控整体吞掉）: " + human);
        assertTrue(human.contains("no argument value equals an upstream value exactly"), "大小写不匹配的近失原因点名: " + human);
        assertFalse(human.contains("Near miss: shipOrder"), "已连通记录不得误报近失: " + human);
        assertFalse(human.contains("Near miss: lookupOrder"), "已连通的上游侧同样不得误报: " + human);

        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath, "--json"));
        String graphJson = stdout.toString();
        assertTrue(graphJson.contains("\"nearMisses\":["), "JSON 面字段恒在场: " + graphJson);
        assertTrue(graphJson.contains("closeTicket"), "近失内容点名未连通记录: " + graphJson);
    }

    /**
     * 同一会话内的链式记录：responseJson 是上游 LLM 回复（含可提取字段值），
     * argValue 是下游工具参数值（与上游字段值相等即 HIGH 边）。
     */
    private void saveChainRecord(String recordId, String invocationId, long timestamp, String argValue, String responseJson) {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId(recordId);
        record.setSessionId("session-graph");
        record.setTimestamp(timestamp);
        record.setSeq(timestamp);
        record.setInvocationId(invocationId);
        record.setTemplateHash("hash-" + recordId);
        record.setUserInput("输入 " + recordId);
        record.setTurnIndex(0);
        record.setModelResponse(responseJson);
        List<ToolCall> calls = new ArrayList<>();
        if (argValue != null) {
            ToolCall call = new ToolCall();
            call.setToolName(invocationId);
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("order_id", argValue);
            call.setArguments(args);
            calls.add(call);
        }
        record.setToolCalls(calls);
        record.setHasToolCalls(!calls.isEmpty());
        repository.saveInteractionIfAbsent(record);
    }
}
