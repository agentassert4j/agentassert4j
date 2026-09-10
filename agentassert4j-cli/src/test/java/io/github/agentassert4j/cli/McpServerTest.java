package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MCP server 协议一致性测试 — 握手/路由/manifest/工具调用/record 摄取/传输鲁棒性。
 *
 * <p>分发器与工具面以进程内实例驱动（真实命令类 + 临时库），传输层用内存管道过真实
 * 读循环——每个出口的应答行必须逐行是合法 JSON 对象（stdout 纯净性）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
class McpServerTest {

    @TempDir
    Path tempDir;

    private String dbPath;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("mcp-test.db").toString();
        dispatcher = new McpDispatcher(McpTools.tools(dbPath), null);
    }

    private static Map<String, Object> parseObject(String json) {
        Object parsed = RecursiveJsonParser.parse(json);
        assertTrue(parsed instanceof Map, "must parse as JSON object: " + json);
        return castMap(parsed);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object parsed) {
        return (Map<String, Object>) parsed;
    }

    private String rpc(String method, String idLiteral, String paramsJson) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + idLiteral + ",\"method\":\"" + method + "\"" + (paramsJson != null ? ",\"params\":" + paramsJson : "") + "}";
    }

    private Map<String, Object> initialize(String protocolVersion) {
        String response = dispatcher.handle(rpc("initialize", "1", "{\"protocolVersion\":\"" + protocolVersion + "\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"0\"}}"));
        Map<String, Object> parsed = parseObject(response);
        assertNull(parsed.get("error"), "initialize must not fail: " + response);
        return castMap(parsed.get("result"));
    }

    private Map<String, Object> callTool(String name, String argumentsJson) {
        String response = dispatcher.handle(rpc("tools/call", "2", "{\"name\":\"" + name + "\",\"arguments\":" + argumentsJson + "}"));
        return castMap(parseObject(response).get("result"));
    }

    private Map<String, Object> errorOf(String response) {
        return castMap(parseObject(response).get("error"));
    }

    private boolean isError(Map<String, Object> toolResult) {
        return Boolean.TRUE.equals(toolResult.get("isError"));
    }

    @SuppressWarnings("unchecked")
    private List<Object> reports(Map<String, Object> toolResult) {
        Map<String, Object> structured = toolResult.get("structuredContent") instanceof Map ? castMap(toolResult.get("structuredContent")) : null;
        if (structured == null || !(structured.get("reports") instanceof List)) {
            return null;
        }
        return (List<Object>) structured.get("reports");
    }

    /**
     * 工具结果的首份报告对象（工具报告行聚合在 structuredContent.reports）。
     */
    private Map<String, Object> firstReport(Map<String, Object> toolResult) {
        List<Object> list = reports(toolResult);
        assertNotNull(list, "structuredContent.reports 必须在场");
        return castMap(list.get(0));
    }

    @Nested
    @DisplayName("握手")
    class Handshake {

        @Test
        @DisplayName("initialize：版本回显、capabilities、serverInfo 与 instructions")
        void initialize_negotiatesAndExposesServerInfo() {
            Map<String, Object> result = initialize("2025-06-18");
            assertEquals("2025-06-18", result.get("protocolVersion"));
            Map<String, Object> capabilities = castMap(result.get("capabilities"));
            Map<String, Object> toolsCapability = castMap(capabilities.get("tools"));
            assertEquals(Boolean.FALSE, toolsCapability.get("listChanged"));
            Map<String, Object> serverInfo = castMap(result.get("serverInfo"));
            assertEquals("agentassert4j", serverInfo.get("name"));
            assertTrue(serverInfo.get("version") instanceof String);
            assertTrue(result.get("instructions") instanceof String);
        }

        @Test
        @DisplayName("未知协议版本：回退到默认支持版本")
        void initialize_unknownVersion_fallsBack() {
            assertEquals("2025-11-25", initialize("1999-01-01").get("protocolVersion"));
        }

        @Test
        @DisplayName("初始化前请求：拒绝并指向 initialize（ping 除外）")
        void preInit_requestsRejected() {
            Map<String, Object> error = errorOf(dispatcher.handle(rpc("tools/list", "1", null)));
            assertEquals(Double.valueOf(-32002), toNumber(error.get("code")));
            assertTrue(String.valueOf(error.get("message")).contains("initialize"));

            Map<String, Object> parsed = parseObject(dispatcher.handle(rpc("ping", "1", null)));
            assertTrue(parsed.containsKey("result"), "ping 在初始化前放行");
        }

        @Test
        @DisplayName("重复 initialize：invalid request")
        void initialize_twice_rejected() {
            initialize("2025-11-25");
            Map<String, Object> error = errorOf(dispatcher.handle(rpc("initialize", "1", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}")));
            assertEquals(Double.valueOf(-32600), toNumber(error.get("code")));
        }

        @Test
        @DisplayName("initialized 通知：无应答")
        void initializedNotification_noResponse() {
            initialize("2025-11-25");
            assertNull(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        }

        private Object toNumber(Object code) {
            return code instanceof Number ? Double.valueOf(((Number) code).doubleValue()) : code;
        }
    }

    @Nested
    @DisplayName("JSON-RPC 路由与校验")
    class Routing {

        @BeforeEach
        void init() {
            initialize("2025-11-25");
        }

        @Test
        @DisplayName("未知方法：-32601")
        void unknownMethod_notFound() {
            assertEquals(Double.valueOf(-32601), toDouble(errorOf(dispatcher.handle(rpc("resources/list", "9", null))).get("code")));
        }

        @Test
        @DisplayName("未知通知与 cancelled：静默忽略")
        void unknownNotifications_ignored() {
            assertNull(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{}}"));
            assertNull(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/whatever\"}"));
        }

        @Test
        @DisplayName("畸形 JSON：-32700 且 id 为 null")
        void malformedJson_parseError() {
            Map<String, Object> parsed = parseObject(dispatcher.handle("{\"jsonrpc\":"));
            assertNull(parsed.get("id"));
            Map<String, Object> error = castMap(parsed.get("error"));
            assertEquals(-32700.0, toDouble(error.get("code")));
        }

        @Test
        @DisplayName("非对象消息与批式数组：-32600")
        void nonObjectMessages_invalidRequest() {
            for (String line : Arrays.asList("42", "\"hello\"", "true", "[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}]")) {
                Map<String, Object> parsed = parseObject(dispatcher.handle(line));
                Map<String, Object> error = castMap(parsed.get("error"));
                assertEquals(-32600.0, toDouble(error.get("code")), "line must be rejected: " + line);
            }
        }

        @Test
        @DisplayName("jsonrpc/method 缺失与 id 非标量：-32600")
        void structuralDefects_invalidRequest() {
            assertEquals(-32600.0, toDouble(errorOf(dispatcher.handle("{\"id\":1,\"method\":\"ping\"}")).get("code")));
            assertEquals(-32600.0, toDouble(errorOf(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":1}")).get("code")));
            Map<String, Object> parsed = parseObject(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"id\":{\"x\":1},\"method\":\"ping\"}"));
            assertEquals(-32600.0, toDouble(castMap(parsed.get("error")).get("code")));
        }

        @Test
        @DisplayName("id 回显保真：字符串/数字/null")
        void idEcho() {
            assertTrue(dispatcher.handle(rpc("ping", "\"abc\"", null)).contains("\"id\":\"abc\""));
            assertTrue(dispatcher.handle(rpc("ping", "7", null)).contains("\"id\":7"));
            assertTrue(dispatcher.handle(rpc("ping", "null", null)).contains("\"id\":null"));
        }

        @Test
        @DisplayName("无 method 的响应帧：静默忽略")
        void responseFrame_ignored() {
            assertNull(dispatcher.handle("{\"jsonrpc\":\"2.0\",\"result\":{}}"));
        }

        @Test
        @DisplayName("ping：空结果对象")
        void ping_emptyResult() {
            Map<String, Object> parsed = parseObject(dispatcher.handle(rpc("ping", "1", null)));
            assertTrue(parsed.get("result") instanceof Map && ((Map<?, ?>) parsed.get("result")).isEmpty());
        }

        private Object toDouble(Object value) {
            return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : value;
        }
    }

    @Nested
    @DisplayName("工具清单 manifest")
    class Manifest {

        @Test
        @DisplayName("12 工具注册序即清单序")
        void tools_registeredInStableOrder() {
            dispatcher.handle(rpc("initialize", "1", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}"));
            Map<String, Object> result = castMap(parseObject(dispatcher.handle(rpc("tools/list", "1", null))).get("result"));
            List<Object> tools = castList(result.get("tools"));
            assertEquals(Arrays.asList("check", "diff", "report", "verify", "doctor", "graph", "record", "record-show", "establish", "accept", "reject", "rollback", "audit", "rules", "member-check", "re-drive", "export"), toolNames(tools));
        }

        @Test
        @DisplayName("inputSchema 均为合法 JSON Schema 对象")
        void inputSchemas_parseAsObjects() {
            for (McpTool tool : McpTools.tools(dbPath)) {
                Map<String, Object> schema = parseObject(tool.inputSchemaJson);
                assertEquals("object", schema.get("type"), tool.name);
            }
        }

        @Test
        @DisplayName("变异动词 description 声明治理写；establish/accept 另声明 agent 身份申报")
        void mutationTools_declareGovernanceWrite() {
            for (McpTool tool : McpTools.tools(dbPath)) {
                boolean mutation = tool.name.equals("establish") || tool.name.equals("accept") || tool.name.equals("reject");
                if (mutation) {
                    assertTrue(tool.description.contains("Governance write"), tool.name);
                }
                if (tool.name.equals("establish") || tool.name.equals("accept")) {
                    assertTrue(tool.description.contains("agent:<name>"), tool.name);
                }
            }
        }

        @Test
        @DisplayName("record description 声明幂等与身份规则")
        void recordTool_declaresSemantics() {
            for (McpTool tool : McpTools.tools(dbPath)) {
                if (tool.name.equals("record")) {
                    assertTrue(tool.description.contains("Idempotent"));
                    assertTrue(tool.description.contains("invocation label"));
                }
            }
        }

        @SuppressWarnings("unchecked")
        private List<Object> castList(Object value) {
            return (List<Object>) value;
        }

        private List<Object> toolNames(List<Object> tools) {
            return Arrays.asList(tools.stream().map(t -> String.valueOf(castMap(t).get("name"))).toArray());
        }
    }

    @Nested
    @DisplayName("工具调用与错误分类法")
    class ToolCalls {

        @BeforeEach
        void init() {
            initialize("2025-11-25");
        }

        @Test
        @DisplayName("未知工具名：协议错误 -32602（规范原文语义）")
        void unknownTool_protocolError() {
            String response = dispatcher.handle(rpc("tools/call", "1", "{\"name\":\"no-such-tool\",\"arguments\":{}}"));
            assertEquals(-32602.0, toDouble(errorOf(response).get("code")));
            assertTrue(String.valueOf(errorOf(response).get("message")).contains("no-such-tool"));
        }

        @Test
        @DisplayName("结构级参数缺陷：-32602")
        void structuralParamDefects_invalidParams() {
            assertEquals(-32602.0, toDouble(errorOf(dispatcher.handle(rpc("tools/call", "1", "{\"arguments\":{}}"))).get("code")));
            assertEquals(-32602.0, toDouble(errorOf(dispatcher.handle(rpc("tools/call", "1", "{\"name\":\"check\",\"arguments\":\"str\"}"))).get("code")));
            assertEquals(-32602.0, toDouble(errorOf(dispatcher.handle(rpc("tools/call", "1", "{\"name\":42,\"arguments\":{}}"))).get("code")));
        }

        @Test
        @DisplayName("check 空库：isError + E-NO-DATA 包络（可自助续行）")
        void check_emptyDb_isErrorWithEnvelope() {
            Map<String, Object> result = callTool("check", "{}");
            assertTrue(isError(result));
            Map<String, Object> structured = castMap(result.get("structuredContent"));
            assertEquals("agentassert4j.error/1", structured.get("schema"));
            assertEquals("E-NO-DATA", structured.get("errorCode"));
            assertTrue(structured.get("hints") instanceof List && !((List<?>) structured.get("hints")).isEmpty());
        }

        @Test
        @DisplayName("check 未建档库：E-GUARD 拒绝判定且不落治理写（读动词 ci 语义）")
        void check_unbaselined_refusedWithGuard() {
            firstReport(callRecord("session-a", "chatcmpl-a"));

            Map<String, Object> result = callTool("check", "{}");
            assertTrue(isError(result));
            Map<String, Object> structured = castMap(result.get("structuredContent"));
            assertEquals("agentassert4j.error/1", structured.get("schema"));
            assertEquals("E-GUARD", structured.get("errorCode"));

            // 拒绝路径零治理写：库中不得出现任何画像
            SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
            try {
                repository.initialize();
                assertTrue(repository.findAllInvocations().isEmpty(), "读动词不得自动建档");
            } finally {
                repository.close();
            }
        }

        @Test
        @DisplayName("arguments 成员缺省：按空对象执行（结构合法不拒）")
        void toolsCall_absentArguments_defaults() {
            String response = dispatcher.handle(rpc("tools/call", "1", "{\"name\":\"doctor\"}"));
            Map<String, Object> result = castMap(parseObject(response).get("result"));
            assertNotNull(result.get("content"));
            assertFalse(isError(result));
        }

        @Test
        @DisplayName("doctor 工具：读动词直调，报告在场")
        void doctor_tool_reports() {
            Map<String, Object> result = callTool("doctor", "{}");
            assertFalse(isError(result));
            assertNotNull(reports(result));
        }

        @Test
        @DisplayName("verify 缺 pack：适配层就近校验 E-USAGE")
        void verify_missingPack_isErrorUsage() {
            Map<String, Object> result = callTool("verify", "{}");
            assertTrue(isError(result));
            assertEquals("E-USAGE", castMap(result.get("structuredContent")).get("errorCode"));
        }

        @Test
        @DisplayName("全链路：record ×2 → establish → check 通过（读动词零治理写可判定）")
        void fullLoop_recordEstablishCheck() {
            firstReport(callRecord("session-a", "chatcmpl-a"));
            Map<String, Object> second = firstReport(callRecord("session-b", "chatcmpl-b"));
            assertEquals("saved", second.get("status"));

            Map<String, Object> established = callTool("establish", "{}");
            assertFalse(isError(established));

            Map<String, Object> check = callTool("check", "{}");
            assertFalse(isError(check), "CHANGED/PASS 由报告承载，不是错误");
            List<Object> checkReports = reports(check);
            assertNotNull(checkReports);
            assertTrue(checkReports.size() >= 2, "至少漂移检测与任务报告在场: " + checkReports);
        }

        @Test
        @DisplayName("record：同 recordId 重发报 duplicate")
        void record_duplicateReported() {
            assertEquals("saved", firstReport(callRecord("session-a", "chatcmpl-a")).get("status"));
            Map<String, Object> again = firstReport(callRecord("session-a", "chatcmpl-a"));
            assertEquals("duplicate", again.get("status"));
        }

        @Test
        @DisplayName("record：wire 字段逐项落库（模板锚/正文/用量/任务键/raw 双列）")
        void record_parsesWireFields() {
            callRecord("session-a", "chatcmpl-a");

            SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
            try {
                repository.initialize();
                List<InteractionRecord> stored = repository.findBySessionId("session-a");
                assertEquals(1, stored.size());
                InteractionRecord record = stored.get(0);
                assertEquals("askFrance", record.getInvocationId());
                assertTrue(record.getInvocationKey().startsWith("invocation:askFrance:"), record.getInvocationKey());
                // 模板原文是瞬态字段：落库经 prompt_texts 以 hash 归档，不在 interactions 行上
                assertEquals("You are a helpful assistant.", repository.findTemplateText(record.getTemplateHash()));
                assertNotNull(record.getTemplateHash());
                assertEquals("What is the capital of France?", record.getUserInput());
                assertEquals(0, record.getTurnIndex());
                assertEquals("Paris.", record.getModelResponse());
                assertEquals("stop", record.getFinishReason());
                assertEquals("deepseek-chat", record.getServedModel());
                assertEquals(10, record.getInputTokens());
                assertEquals(2, record.getOutputTokens());
                assertTrue(record.getMetadata().contains("\"taskKey\":\"capital-quiz\""));
                assertEquals(openAiRequest(), record.getModelRequestRaw());
                assertTrue(record.getModelResponseRaw().contains("chatcmpl-a"));
                assertFalse(record.isHasToolCalls());
            } finally {
                repository.close();
            }
        }

        @Test
        @DisplayName("record：tool_calls 解析与 argTypes 派生")
        void record_toolCallsDerived() {
            String request = "{\"model\":\"deepseek-chat\",\"messages\":[" + "{\"role\":\"system\",\"content\":\"You call tools.\"}," + "{\"role\":\"user\",\"content\":\"Query order 42\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"getOrder\",\"parameters\":{\"type\":\"object\"}}}],\"temperature\":0}";
            String response = "{\"id\":\"chatcmpl-tool\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":" + "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"getOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"42\\\"}\"}}]}," + "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":5}}";
            Map<String, Object> result = firstReport(callTool("record", "{\"sessionId\":\"s-tools\",\"request\":\"" + RecursiveJsonParser.escape(request) + "\",\"response\":\"" + RecursiveJsonParser.escape(response) + "\",\"invocation\":\"orderAgent\"}"));
            assertEquals("saved", result.get("status"));
            assertTrue(Boolean.TRUE.equals(result.get("hasToolCalls")));

            SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
            try {
                repository.initialize();
                InteractionRecord record = repository.findBySessionId("s-tools").get(0);
                assertTrue(record.isHasToolCalls());
                assertEquals("getOrder", record.getToolCalls().get(0).getToolName());
                assertEquals("call_1", record.getToolCalls().get(0).getToolCallId());
                assertFalse(record.getToolCalls().get(0).getArgTypes().isEmpty());
                assertEquals("tool_calls", record.getFinishReason());
                assertTrue(record.getToolsDefinition().contains("getOrder"));
            } finally {
                repository.close();
            }
        }

        @Test
        @DisplayName("record：中文输入逐字存活（UTF-8 全链）")
        void record_chineseInput_survives() {
            String request = "{\"model\":\"deepseek-chat\",\"messages\":[" + "{\"role\":\"system\",\"content\":\"你是订单助手。\"}," + "{\"role\":\"user\",\"content\":\"查订单 ORD-001\"}]}";
            String response = "{\"id\":\"chatcmpl-zh\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":" + "{\"role\":\"assistant\",\"content\":\"订单已发货。\"},\"finish_reason\":\"stop\"}]}";
            callTool("record", "{\"sessionId\":\"s-zh\",\"request\":\"" + RecursiveJsonParser.escape(request) + "\",\"response\":\"" + RecursiveJsonParser.escape(response) + "\"}");
            SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
            try {
                repository.initialize();
                InteractionRecord record = repository.findBySessionId("s-zh").get(0);
                assertEquals("查订单 ORD-001", record.getUserInput());
                assertEquals("订单已发货。", record.getModelResponse());
                assertEquals("你是订单助手。", repository.findTemplateText(record.getTemplateHash()));
            } finally {
                repository.close();
            }
        }

        @Test
        @DisplayName("record：非法 request JSON → isError + E-USAGE 包络")
        void record_badRequestJson_isErrorUsage() {
            Map<String, Object> result = callTool("record", "{\"sessionId\":\"s\",\"request\":\"not json {\",\"response\":\"{}\"}");
            assertTrue(isError(result));
            assertEquals("E-USAGE", castMap(result.get("structuredContent")).get("errorCode"));
        }

        @Test
        @DisplayName("record：缺必填 → isError + E-USAGE")
        void record_missingRequired_isErrorUsage() {
            Map<String, Object> result = callTool("record", "{\"sessionId\":\"s\",\"request\":\"{}\"}");
            assertTrue(isError(result));
            assertEquals("E-USAGE", castMap(result.get("structuredContent")).get("errorCode"));
        }

        @Test
        @DisplayName("record：metadata 非 JSON 对象 → isError + E-USAGE")
        void record_badMetadata_isErrorUsage() {
            Map<String, Object> result = callTool("record", "{\"sessionId\":\"s\",\"request\":\"{}\",\"response\":\"{}\",\"metadata\":\"nope\"}");
            assertTrue(isError(result));
            assertEquals("E-USAGE", castMap(result.get("structuredContent")).get("errorCode"));
        }

        @Test
        @DisplayName("record：多模态末位 user 数组按数组落库")
        void record_multimodalTrailingUser() {
            String request = "{\"model\":\"gpt-4o\",\"messages\":[{\"role\":\"user\",\"content\":[" + "{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]}]}";
            String response = "{\"id\":\"chatcmpl-mm\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"a cat\"},\"finish_reason\":\"stop\"}]}";
            callTool("record", "{\"sessionId\":\"s-mm\",\"request\":\"" + RecursiveJsonParser.escape(request) + "\",\"response\":\"" + RecursiveJsonParser.escape(response) + "\"}");
            SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
            try {
                repository.initialize();
                InteractionRecord record = repository.findBySessionId("s-mm").get(0);
                assertTrue(record.isMultimodalInput());
                assertTrue(record.getUserInput().contains("image_url"));
                assertTrue(record.getInvocationKey().startsWith("adhoc:"), record.getInvocationKey());
            } finally {
                repository.close();
            }
        }

        private Map<String, Object> callRecord(String sessionId, String responseId) {
            return callTool("record", "{\"sessionId\":\"" + sessionId + "\",\"request\":\"" + RecursiveJsonParser.escape(openAiRequest()) + "\",\"response\":\"" + RecursiveJsonParser.escape(openAiResponse(responseId)) + "\",\"invocation\":\"askFrance\",\"taskKey\":\"capital-quiz\"}");
        }

        private String openAiRequest() {
            return "{\"model\":\"deepseek-chat\",\"messages\":[{\"role\":\"system\",\"content\":\"You are a helpful assistant.\"}," + "{\"role\":\"user\",\"content\":\"What is the capital of France?\"}],\"temperature\":0.2}";
        }

        private String openAiResponse(String responseId) {
            return "{\"id\":\"" + responseId + "\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":" + "{\"role\":\"assistant\",\"content\":\"Paris.\"},\"finish_reason\":\"stop\"}]," + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"total_tokens\":12}}";
        }

        private Object toDouble(Object value) {
            return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : value;
        }
    }

    @Nested
    @DisplayName("stdio 传输")
    class Transport {

        @Test
        @DisplayName("stdout 每行都是合法 JSON：混入畸形/空行/CRLF 的全序列")
        void stdoutCarriesOnlyProtocolLines() throws Exception {
            dispatcher.handle(rpc("initialize", "1", "{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}"));
            String input = "\n" + "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\",\"capabilities\":{}}}\r\n" + "garbage line\n" + "\n" + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\r\n" + "42\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new StdioTransport(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, true, StandardCharsets.UTF_8.name()), dispatcher).run();
            String[] lines = out.toString().split("\n");
            assertTrue(lines.length >= 4, "initialize/ping/畸形行/非对象行都应有应答: " + out);
            for (String line : lines) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                Object parsed = RecursiveJsonParser.parse(line);
                assertTrue(parsed instanceof Map, "stdout 只允许协议消息: " + line);
            }
        }

        @Test
        @DisplayName("无换行收尾的残行：按末消息处理")
        void finalLineWithoutNewline_processed() throws Exception {
            String input = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new StdioTransport(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, true, StandardCharsets.UTF_8.name()), new McpDispatcher(McpTools.tools(dbPath), null)).run();
            assertTrue(out.toString().contains("\"result\":{}"));
        }

        @Test
        @DisplayName("--diag：逐消息向 stderr 记 method 与耗时")
        void diag_logsPerMessage() throws Exception {
            ByteArrayOutputStream diagBuffer = new ByteArrayOutputStream();
            McpDispatcher diagDispatcher = new McpDispatcher(McpTools.tools(dbPath), new PrintStream(diagBuffer, true, StandardCharsets.UTF_8.name()));
            diagDispatcher.handle(rpc("ping", "1", null));
            String line = diagBuffer.toString(StandardCharsets.UTF_8.name());
            assertTrue(line.contains("ping"), line);
            assertTrue(line.contains("ms"), line);
        }

        @Test
        @DisplayName("超预算行：拒绝该行且不拖垮后续消息")
        void oversizedLine_rejectedThenRecovers() throws Exception {
            StringBuilder huge = new StringBuilder();
            char[] chunk = new char[1024 * 1024];
            Arrays.fill(chunk, 'x');
            for (int i = 0; i < StdioTransport.MAX_LINE_CHARS / chunk.length + 2; i++) {
                huge.append(chunk);
            }
            String input = huge + "\n" + rpc("ping", "7", null) + "\n";
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new StdioTransport(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), new PrintStream(out, true, StandardCharsets.UTF_8.name()), new McpDispatcher(McpTools.tools(dbPath), null)).run();
            String[] lines = out.toString().split("\n");
            assertEquals(2, lines.length);
            assertTrue(lines[0].contains("-32600") && lines[0].contains("exceeds"), lines[0]);
            assertTrue(lines[1].contains("\"id\":7") && lines[1].contains("\"result\":{}"), lines[1]);
        }
    }
}
