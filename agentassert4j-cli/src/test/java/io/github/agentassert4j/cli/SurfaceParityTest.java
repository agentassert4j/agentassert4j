package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 退出码与缩域两等价类的跨面钉 —— 同一失败条件在 CLI 与 MCP 两入口必须报同一
 * 错误码；同一选择器串在 status / report / replay 与 MCP status 解析出同一目标
 * 键集。登记表真源：guide/spec/equivalence.md。
 *
 * @author axy-yxa
 * @since 2026-09-17
 */
class SurfaceParityTest {

    private static final Pattern KEY_PATTERN = Pattern.compile("\"invocationKey\":\"([^\"]+)\"");

    @TempDir
    Path tempDir;

    private final PrintStream originalOut = System.out;
    private String dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("surface-parity.db").toString();
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    @Nested
    @DisplayName("退出码等价：同一条件 CLI 与 MCP 同码")
    class ExitCodeParity {

        @Test
        @DisplayName("空库：CLI replay 与 MCP check 都报 E-NO-DATA")
        void emptyDb_sameCodeBothFaces() {
            String cliCode = cliErrorCode("replay", "--db", dbPath, "--json");
            String mcpCode = mcpErrorCode("check", "{}");
            assertEquals("E-NO-DATA", cliCode, "CLI 空库错误码");
            assertEquals("E-NO-DATA", mcpCode, "MCP 空库错误码");
            assertEquals(cliCode, mcpCode, "同一条件两入口必须同码");
        }

        @Test
        @DisplayName("未建档库判定：CLI replay --ci 与 MCP check 都报 E-GUARD")
        void unbaselined_sameCodeBothFaces() {
            seedRecords("session-1", 1000L);

            String cliCode = cliErrorCode("replay", "--db", dbPath, "--ci", "--json");
            String mcpCode = mcpErrorCode("check", "{}");
            assertEquals("E-GUARD", cliCode, "CLI 未建档判定错误码");
            assertEquals("E-GUARD", mcpCode, "MCP 未建档判定错误码");
            assertEquals(cliCode, mcpCode, "同一条件两入口必须同码");
        }

        @Test
        @DisplayName("record-show 未命中：CLI 与 MCP 都报 E-NO-DATA")
        void recordShowMiss_sameCodeBothFaces() {
            seedRecords("session-1", 1000L);

            String cliCode = cliErrorCode("record", "show", "--db", dbPath, "--record-id", "no-such-record", "--json");
            String mcpCode = mcpErrorCode("record-show", "{\"recordId\":\"no-such-record\"}");
            assertEquals("E-NO-DATA", cliCode, "CLI 记录未命中错误码");
            assertEquals("E-NO-DATA", mcpCode, "MCP 记录未命中错误码");
            assertEquals(cliCode, mcpCode, "同一条件两入口必须同码");
        }
    }

    @Nested
    @DisplayName("缩域等价：同一选择器串跨命令同目标集")
    class NarrowingParity {

        @Test
        @DisplayName("--invocation order：缩域族三格同键集；目标族歧义报错列同一候选集")
        void labelSelector_familiesResolveConsistently() {
            seedRecords("session-1", 1000L);
            seedRecords("session-2", 2000L);

            Set<String> byStatus = invocationKeysOfCli(0, "status", "--db", dbPath, "--invocation", "order", "--json");
            Set<String> byMcpReport = invocationKeysOfMcp("report", "{\"invocation\":\"order\"}");

            assertEquals(byStatus, byMcpReport, "CLI status 与 MCP report 的缩域目标集必须一致");
            assertEquals(2, byStatus.size(), "缩域族：标签 order 扇出到两个模板键: " + byStatus);
            assertTrue(byStatus.contains("invocation:order:hash-a") && byStatus.contains("invocation:order:hash-b"), "两个 order 键都在场: " + byStatus);
            assertFalse(byStatus.contains("invocation:stock:hash-c"), "其他标签的键不得混入: " + byStatus);

            // 目标族（replay --invocation）：多键标签按歧义响亮拒绝，且披露的候选键集
            // 必须与缩域族解析出的集合一致——同一解析真源在两族上的投影
            String envelope = cliErrorEnvelope("replay", "--db", dbPath, "--invocation", "order", "--json");
            assertTrue(envelope.contains("\"errorCode\":\"E-USAGE\""), "多键标签=目标族歧义: " + envelope);
            for (String key : byStatus) {
                assertTrue(envelope.contains(key), "歧义披露必须列全候选键 " + key + ": " + envelope);
            }
        }
    }

    private void seedRecords(String sessionId, long baseTs) {
        SqliteStorageRepository repo = new SqliteStorageRepository(dbPath);
        try {
            repo.initialize();
            saveRecord(repo, "r-a-" + sessionId, "order", "hash-a", sessionId, baseTs + 1);
            saveRecord(repo, "r-b-" + sessionId, "order", "hash-b", sessionId, baseTs + 2);
            saveRecord(repo, "r-c-" + sessionId, "stock", "hash-c", sessionId, baseTs + 3);
        } finally {
            repo.close();
        }
    }

    private static void saveRecord(SqliteStorageRepository repo, String recordId, String label, String templateHash, String sessionId, long ts) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(ts);
        r.setSeq(ts);
        r.setInvocationId(label);
        r.setInvocationKey("invocation:" + label + ":" + templateHash);
        r.setTemplateHash(templateHash);
        r.setUserInput("查订单");
        r.setTurnIndex(0);
        r.setModelResponse("{\"result\":\"ok-" + label + "\"}");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repo.saveInteractionIfAbsent(r);
    }

    private String cliErrorEnvelope(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        int exit = new CommandLine(new AgentAssert4jCli()).execute(args);
        String text = out.toString().trim();
        String lastLine = text.contains("\n") ? text.substring(text.lastIndexOf('\n') + 1) : text;
        assertTrue(lastLine.startsWith("{\"schema\":\"agentassert4j.error/1\""), "失败必须以机器包络收尾 stdout (exit " + exit + "): " + text + " | stderr: " + err);
        return lastLine;
    }

    private String cliErrorCode(String... args) {
        return stringField(cliErrorEnvelope(args), "errorCode");
    }

    private Set<String> invocationKeysOfCli(int expectedExit, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        int exit = new CommandLine(new AgentAssert4jCli()).execute(args);
        assertEquals(expectedExit, exit, "缩域读命令退出码: " + out + " | stderr: " + err);
        return keysOf(out.toString());
    }

    @SuppressWarnings("unchecked")
    private String mcpErrorCode(String tool, String argumentsJson) {
        McpDispatcher dispatcher = new McpDispatcher(McpTools.tools(dbPath), null);
        dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
        String response = dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}}");
        Object parsed = RecursiveJsonParser.parse(response);
        assertTrue(parsed instanceof Map, "工具调用应答必须是 JSON 对象: " + response);
        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) parsed).get("result");
        assertEquals(Boolean.TRUE, result.get("isError"), "预期失败结果: " + response);
        Map<String, Object> structured = result.get("structuredContent") instanceof Map ? (Map<String, Object>) result.get("structuredContent") : null;
        assertNotNull(structured, "失败结果必须携带 structuredContent 包络: " + response);
        assertEquals("agentassert4j.error/1", structured.get("schema"), "包络 schema: " + response);
        return String.valueOf(structured.get("errorCode"));
    }

    @SuppressWarnings("unchecked")
    private Set<String> invocationKeysOfMcp(String tool, String argumentsJson) {
        McpDispatcher dispatcher = new McpDispatcher(McpTools.tools(dbPath), null);
        dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":" + "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}}");
        String response = dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\"," + "\"params\":{\"name\":\"" + tool + "\",\"arguments\":" + argumentsJson + "}}");
        Object parsed = RecursiveJsonParser.parse(response);
        assertTrue(parsed instanceof Map, "工具调用应答必须是 JSON 对象: " + response);
        Map<String, Object> result = (Map<String, Object>) ((Map<String, Object>) parsed).get("result");
        assertNotEquals(Boolean.TRUE, result.get("isError"), "缩域读动词必须成功: " + response);
        Object structured = result.get("structuredContent");
        assertNotNull(structured, "结构化报告必须在场: " + response);
        return keysOf(String.valueOf(RecursiveJsonParser.serialize(structured)));
    }

    private static Set<String> keysOf(String jsonText) {
        Set<String> keys = new HashSet<>();
        Matcher m = KEY_PATTERN.matcher(jsonText);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static String stringField(String jsonText, String field) {
        Matcher m = Pattern.compile("\"" + field + "\":\"([^\"]+)\"").matcher(jsonText);
        assertTrue(m.find(), field + " 必须在场: " + jsonText);
        return m.group(1);
    }
}
