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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // 图节点身份 = 调用点键（声明锚点：invocation:标签:模板哈希）
        assertTrue(output.contains("invocation:queryOrder:hash-r-1"), "节点必须含上游调用点键");
        assertTrue(output.contains("invocation:refundOrder:hash-r-2"), "节点必须含下游调用点键");
        assertTrue(output.contains("invocation:queryOrder:hash-r-1 -> invocation:refundOrder:hash-r-2"), "值流边必须渲染");
        assertTrue(output.contains("HIGH"), "字段值精确匹配是 HIGH 置信度");
        assertTrue(output.contains("Cycles: none"));
    }

    @Test
    @DisplayName("无边数据给出会话链提示而非静默空输出")
    void emptyGraphPrintsSessionHint() {
        saveChainRecord("r-only", "loneSkill", 1000L, null, "{\"k\":\"v\"}");

        int exit = new CommandLine(new AgentAssert4jCli()).execute("graph", "show", "--db", dbPath);

        assertEquals(0, exit);
        String output = stdout.toString();
        assertTrue(output.contains("Note: no edges"), "空图必须解释边的数据来源条件");
        assertTrue(output.contains("Nodes (0)"), "节点派生自边，无边即无节点");
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
        repository.saveInteraction(record);
    }
}
