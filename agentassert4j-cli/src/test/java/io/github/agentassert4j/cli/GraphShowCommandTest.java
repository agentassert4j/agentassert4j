package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
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
