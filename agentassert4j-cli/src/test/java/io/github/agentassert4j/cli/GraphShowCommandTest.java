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
        // 节点/边正文走 displayKey 短形——完整键不进正文行
        assertTrue(output.contains("Nodes (2): queryOrder@hash-r-1, refundOrder@hash-r-2"), "节点行必须走短形: " + output);
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
    @DisplayName("无边数据给出三前提与扫描统计，节点全集使空边不等于空图")
    void emptyGraphPrintsSessionHint() {
        saveChainRecord("r-only", "loneSkill", 1000L, null, "{\"k\":\"v\"}");

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("No data-flow edges (scanned 1 record across 1 session"), "空图必须就地披露扫描统计: " + output);
        assertTrue(output.contains("An edge needs all three"), "出边三前提必须逐条列明: " + output);
        assertTrue(output.contains("Nodes (1)"), "节点全集语义：数据在场即有节点，空边不等于空图: " + output);

        assertEquals(0, new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath, "--json"));
        String graphJson = stdout.toString();
        assertTrue(graphJson.contains("\"nodes\":[\"invocation:loneSkill:"), "机器面必须携带 nodes 数组（只有计数不可寻址）: " + graphJson);
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
