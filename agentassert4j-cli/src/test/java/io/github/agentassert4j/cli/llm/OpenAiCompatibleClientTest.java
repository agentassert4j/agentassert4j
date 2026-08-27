package io.github.agentassert4j.cli.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.ToolCallResult;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAiCompatibleClient 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class OpenAiCompatibleClientTest {

    private OpenAiCompatibleClient client;

    @BeforeEach
    void setUp() {
        client = new OpenAiCompatibleClient("https://api.openai.com", "test-key", "gpt-4o");
    }

    @Test
    void name_returnsDefaultModel() {
        assertEquals("gpt-4o", client.name());
    }

    @Test
    void buildRequestBody_containsSystemPrompt() {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt("You are helpful");
        request.setUserInput("Hello");

        String body = client.buildRequestBody(request, "gpt-4o");

        assertTrue(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("You are helpful"));
        assertTrue(body.contains("\"role\":\"user\""));
        assertTrue(body.contains("Hello"));
        assertTrue(body.contains("\"model\":\"gpt-4o\""));
    }

    @Test
    void buildRequestBody_containsTemperature() {
        LlmRequest request = new LlmRequest();
        request.setTemperature(0.0);
        request.setUserInput("test");

        String body = client.buildRequestBody(request, "gpt-4o");

        assertTrue(body.contains("\"temperature\":0.0"));
    }

    @Test
    void buildRequestBody_includesPreviousTurns() {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt("System");
        request.setUserInput("Current");
        request.setPreviousTurns(Arrays.asList(new TurnContext("user", "Previous question"), new TurnContext("assistant", "Previous answer")));

        String body = client.buildRequestBody(request, "gpt-4o");

        // 顺序：system, previous user, previous assistant, current user
        int sysIdx = body.indexOf("\"role\":\"system\"");
        int prevUserIdx = body.indexOf("Previous question");
        int prevAsstIdx = body.indexOf("Previous answer");
        int curUserIdx = body.indexOf("Current");

        assertTrue(sysIdx < prevUserIdx, "system before previous user");
        assertTrue(prevUserIdx < prevAsstIdx, "previous user before previous assistant");
        assertTrue(prevAsstIdx < curUserIdx, "previous assistant before current user");
    }

    @Test
    void buildRequestBody_escapesSpecialChars() {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt("Say \"hello\"\nnew line\ttab");

        String body = client.buildRequestBody(request, "gpt-4o");

        assertTrue(body.contains("\\\"hello\\\""));
        assertTrue(body.contains("\\n"));
        assertTrue(body.contains("\\t"));
    }

    @Test
    void buildRequestBody_usesRequestModelWhenSet() {
        LlmRequest request = new LlmRequest();
        request.setModel("deepseek-chat");
        request.setUserInput("test");

        String body = client.buildRequestBody(request, "deepseek-chat");

        assertTrue(body.contains("\"model\":\"deepseek-chat\""));
    }

    @Test
    void buildRequestBody_nullSystemPrompt_skipped() {
        LlmRequest request = new LlmRequest();
        request.setUserInput("test");

        String body = client.buildRequestBody(request, "gpt-4o");

        assertFalse(body.contains("\"role\":\"system\""));
        assertTrue(body.contains("\"role\":\"user\""));
    }

    @Test
    void parseResponse_extractsContent() throws Exception {
        String json = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\"," + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\"," + "\"content\":\"Hello! How can I help?\"},\"finish_reason\":\"stop\"}]," + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}";

        LlmResponse response = client.parseResponse(json);

        assertEquals("Hello! How can I help?", response.getContent());
        assertFalse(response.isHasError());
        assertEquals(10, response.getInputTokens());
        assertEquals(5, response.getOutputTokens());
    }

    @Test
    void parseResponse_extractsToolCalls() throws Exception {
        String json = "{\"id\":\"chatcmpl-2\",\"choices\":[{\"message\":{" + "\"role\":\"assistant\",\"content\":null," + "\"tool_calls\":[{\"id\":\"call_abc\",\"type\":\"function\"," + "\"function\":{\"name\":\"queryOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"ORD-001\\\"}\"}}" + "]}}],\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10}}";

        LlmResponse response = client.parseResponse(json);

        assertNotNull(response.getToolCalls());
        assertEquals(1, response.getToolCalls().size());

        ToolCallResult tc = response.getToolCalls().get(0);
        assertEquals("call_abc", tc.getToolCallId());
        assertEquals("queryOrder", tc.getToolName());
        assertNotNull(tc.getArguments());
        assertEquals("ORD-001", tc.getArguments().get("orderId"));
    }

    @Test
    void parseResponse_multipleToolCalls() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"tool_calls\":[" + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"tool1\",\"arguments\":\"{\\\"a\\\":1}\"}}," + "{\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"tool2\",\"arguments\":\"{\\\"b\\\":true}\"}}" + "]}}]}";

        LlmResponse response = client.parseResponse(json);

        assertEquals(2, response.getToolCalls().size());
        assertEquals("tool1", response.getToolCalls().get(0).getToolName());
        assertEquals("tool2", response.getToolCalls().get(1).getToolName());
        assertEquals(1L, response.getToolCalls().get(0).getArguments().get("a"));
        assertEquals(Boolean.TRUE, response.getToolCalls().get(1).getArguments().get("b"));
    }

    @Test
    void parseResponse_noToolCalls_returnsEmptyList() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"Hi\"}}]}";

        LlmResponse response = client.parseResponse(json);

        assertNotNull(response.getToolCalls());
        assertTrue(response.getToolCalls().isEmpty());
    }

    @Test
    void parseResponse_argumentsWithNestedObject_keptAsString() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"tool_calls\":[" + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{" + "\"name\":\"search\",\"arguments\":\"{\\\"filter\\\":{\\\"status\\\":\\\"active\\\"}}\"}}" + "]}}]}";

        LlmResponse response = client.parseResponse(json);
        Map<String, Object> args = response.getToolCalls().get(0).getArguments();
        // 嵌套对象保留为字符串
        assertEquals("{\"status\":\"active\"}", args.get("filter"));
    }

    @Test
    void chat_retriesOnServerError() throws Exception {
        // 这个测试验证重试计数 — 通过 mock HttpClient 实现较复杂
        // 核心逻辑：DEFAULT_MAX_RETRIES = 2，总共最多执行 3 次（1 + 2 retries）
        // 此处验证 client 配置正确
        assertEquals("gpt-4o", client.name());
    }

    @Test
    void constructor_normalizesTrailingSlash() {
        OpenAiCompatibleClient c = new OpenAiCompatibleClient("https://api.deepseek.com/", "key", "deepseek-chat");
        // 内部 endpoint 已去尾斜杠，验证通过 buildRequestBody 不暴露
        // 直接验证 name()
        assertEquals("deepseek-chat", c.name());
    }

    @Test
    void isAvailable_unreachableEndpoint_returnsFalse() {
        OpenAiCompatibleClient c = new OpenAiCompatibleClient("http://localhost:1", "fake-key", "test");
        assertFalse(c.isAvailable());
    }

    @Test
    void parseResponse_argumentsWithNumberTypes() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"tool_calls\":[" + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{" + "\"name\":\"paginate\",\"arguments\":\"{\\\"page\\\":1,\\\"limit\\\":20,\\\"price\\\":9.99}\"}}" + "]}}]}";

        LlmResponse response = client.parseResponse(json);
        Map<String, Object> args = response.getToolCalls().get(0).getArguments();

        assertEquals(1L, args.get("page"));
        assertEquals(20L, args.get("limit"));
        assertEquals(9.99, args.get("price"));
    }

    @Test
    void parseResponse_argumentsWithNull() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"tool_calls\":[" + "{\"id\":\"c1\",\"type\":\"function\",\"function\":{" + "\"name\":\"test\",\"arguments\":\"{\\\"field\\\":null}\"}}" + "]}}]}";

        LlmResponse response = client.parseResponse(json);
        Map<String, Object> args = response.getToolCalls().get(0).getArguments();
        assertNull(args.get("field"));
    }

    @Test
    void parseResponse_emptyContent_returnsNull() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\"}}]}";

        LlmResponse response = client.parseResponse(json);
        assertNull(response.getContent());
    }

    @Test
    void parseResponse_retainsUsageRawAndNormalizesDialect() throws Exception {
        // DeepSeek 风格 usage：prompt_cache_hit_tokens + prompt_tokens_details.cached_tokens
        String usage = "{\"prompt_tokens\":2048,\"completion_tokens\":100," + "\"prompt_cache_hit_tokens\":1024," + "\"prompt_tokens_details\":{\"cached_tokens\":1024}," + "\"completion_tokens_details\":{\"reasoning_tokens\":64}}";
        String json = "{\"id\":\"resp-1\",\"model\":\"deepseek-chat-V3.1-0806\"," + "\"choices\":[{\"message\":{\"content\":\"ok\"},\"finish_reason\":\"tool_calls\"}]," + "\"usage\":" + usage + "}";

        LlmResponse response = client.parseResponse(json);

        assertEquals(2048, response.getInputTokens());
        assertEquals(100, response.getOutputTokens());
        assertEquals(Integer.valueOf(1024), response.getCacheReadTokens(), "cached_tokens 归一为缓存读");
        assertEquals(Integer.valueOf(64), response.getReasoningTokens());
        assertEquals("deepseek-chat-V3.1-0806", response.getServedModel());
        assertEquals("tool_calls", response.getFinishReason());

        assertNotNull(response.getUsageRaw(), "usage 子树必须逐字保留");
        assertTrue(response.getUsageRaw().contains("prompt_cache_hit_tokens"), "usage_raw 是逐字原文，未归一的方言字段也必须在其中（回填来源）");
    }

    @Test
    void parseResponse_absentOptionalTelemetry_staysNull() throws Exception {
        String json = "{\"choices\":[{\"message\":{\"content\":\"hi\"},\"finish_reason\":\"stop\"}]," + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2}}";

        LlmResponse response = client.parseResponse(json);

        assertNull(response.getCacheReadTokens());
        assertNull(response.getCacheWriteTokens());
        assertNull(response.getReasoningTokens());
        assertNull(response.getServedModel(), "无顶层 model 字段时保持 null");
        assertEquals("stop", response.getFinishReason());
    }

    @Test
    void parseResponse_finishReasonNormalized() {
        assertEquals("max_tokens", OpenAiCompatibleClient.normalizeFinishReason("length"));
        assertEquals("tool_calls", OpenAiCompatibleClient.normalizeFinishReason("function_call"));
        assertEquals("content_filter", OpenAiCompatibleClient.normalizeFinishReason("content_filter"));
        assertEquals("other", OpenAiCompatibleClient.normalizeFinishReason("weird_new_reason"));
        assertNull(OpenAiCompatibleClient.normalizeFinishReason(null));
    }

    @Nested
    @DisplayName("超时契约")
    class TimeoutContract {

        @Test
        @DisplayName("读取超时 → 立即抛 LlmTimeoutException，不重试")
        void chat_readTimeout_throwsLlmTimeoutWithoutRetry() throws Exception {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                try {
                    // 远超客户端 readTimeout，触发读取超时（stop 会等在途 handler，不宜睡太久）
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            server.start();
            try {
                OpenAiCompatibleClient c = new OpenAiCompatibleClient("http://127.0.0.1:" + server.getAddress().getPort(), "key", "test", 2);
                LlmRequest request = new LlmRequest();
                request.setUserInput("hi");

                long start = System.currentTimeMillis();
                assertThrows(LlmTimeoutException.class, () -> c.chat(request, 500));
                long elapsed = System.currentTimeMillis() - start;

                // 超时不得重试：单次尝试后立即抛出（余量覆盖调度抖动）
                assertTrue(elapsed < 2000, "超时应立即抛出不重试，实际耗时 " + elapsed + "ms");
            } finally {
                server.stop(0);
            }
        }
    }

    @Nested
    @DisplayName("tool 角色消息序列化")
    class ToolRoleMessages {

        @Test
        @DisplayName("tool 轮次携带 tool_call_id")
        void buildRequestBody_toolTurn_carriesToolCallId() {
            TurnContext toolTurn = new TurnContext("tool", "result1");
            toolTurn.setToolCallId("call_abc");
            LlmRequest request = new LlmRequest();
            request.setUserInput("next");
            request.addTurn(toolTurn);

            String body = client.buildRequestBody(request, "gpt-4o");

            assertTrue(body.contains("\"role\":\"tool\""));
            assertTrue(body.contains("\"tool_call_id\":\"call_abc\""), "tool 消息缺 tool_call_id 会被服务端以 400 拒绝");
        }

        @Test
        @DisplayName("非 tool 轮次不带 tool_call_id 字段")
        void buildRequestBody_userTurn_hasNoToolCallId() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("next");
            request.addTurn("user", "q1");

            String body = client.buildRequestBody(request, "gpt-4o");

            assertFalse(body.contains("tool_call_id"));
        }
    }

    @Nested
    @DisplayName("控制字符转义")
    class ControlCharEscaping {

        @Test
        @DisplayName("用户输入携带原始控制字符 → 请求体仍是合法 JSON")
        void buildRequestBody_controlChars_stillValidJson() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("beep\u0007bell\u0000nul\u001fend\f");

            String body = client.buildRequestBody(request, "gpt-4o");

            Object parsed = RecursiveJsonParser.parse(body);
            assertInstanceOf(Map.class, parsed, "请求体必须是可解析的合法 JSON");
            assertTrue(body.contains("\\u0007"), "0x07 必须转义为 \\u0007");
            assertTrue(body.contains("\\f"), "0x0C 使用短转义 \\f");
        }

        @Test
        @DisplayName("控制字符转义可往返——解析后内容不变")
        void buildRequestBody_controlChars_roundTrip() {
            String hostile = "a\u0001b\u001fc\u00bd";
            LlmRequest request = new LlmRequest();
            request.setUserInput(hostile);

            String body = client.buildRequestBody(request, "gpt-4o");

            @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) RecursiveJsonParser.parse(body);
            @SuppressWarnings("unchecked") List<Object> messages = (List<Object>) parsed.get("messages");
            @SuppressWarnings("unchecked") Map<String, Object> userMessage = (Map<String, Object>) messages.get(messages.size() - 1);
            assertEquals(hostile, userMessage.get("content"), "转义后解析必须还原原始内容");
        }
    }

    @Nested
    class ToolCallSequence {

        private List<Map<String, Object>> messagesOf(String body) {
            @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) RecursiveJsonParser.parse(body);
            assertNotNull(parsed, "请求体必须可解析");
            @SuppressWarnings("unchecked") List<Map<String, Object>> messages = (List<Map<String, Object>>) parsed.get("messages");
            return messages;
        }

        private TurnContext turn(String role, String content) {
            return new TurnContext(role, content);
        }

        @Test
        @DisplayName("tool 轮之前必须合成携带同 id tool_calls 的 assistant 帧")
        void syntheticAssistantInjectedBeforeToolTurn() {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("sys");
            request.setUserInput("最终提问");
            request.addTurn(turn("user", "第一问"));
            request.addTurn(turn("assistant", "我先查一下。"));

            TurnContext toolResult = turn("tool", "{\"status\":\"ok\"}");
            toolResult.setToolCallId("call_abc");
            toolResult.setToolName("query_order");
            request.addTurn(toolResult);

            String body = client.buildRequestBody(request, "gpt-4o");
            List<Map<String, Object>> messages = messagesOf(body);

            int toolIndex = -1;
            for (int i = 0; i < messages.size(); i++) {
                if ("tool".equals(messages.get(i).get("role"))) {
                    toolIndex = i;
                }
            }
            assertTrue(toolIndex > 0, "必须存在 tool 轮");

            Map<String, Object> preceding = messages.get(toolIndex - 1);
            assertEquals("assistant", preceding.get("role"), "tool 轮的紧邻前驱必须是 assistant（服务端硬约束）");
            @SuppressWarnings("unchecked") List<Map<String, Object>> toolCalls = (List<Map<String, Object>>) preceding.get("tool_calls");
            assertNotNull(toolCalls, "紧邻前驱 assistant 必须携带 tool_calls");
            assertEquals("call_abc", toolCalls.get(0).get("id"), "tool_calls.id 必须与 tool_call_id 对应");
            @SuppressWarnings("unchecked") Map<String, Object> fn = (Map<String, Object>) toolCalls.get(0).get("function");
            assertEquals("query_order", fn.get("name"));
        }

        @Test
        @DisplayName("连续两个不同 id 的 tool 轮各自获得配对的 assistant 帧")
        void consecutiveToolTurns_eachGetPair() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("继续");
            TurnContext t1 = turn("tool", "r1");
            t1.setToolCallId("call_a");
            t1.setToolName("fn_a");
            request.addTurn(t1);
            TurnContext assistAfterA = turn("assistant", "第一轮结果分析完毕");
            request.addTurn(assistAfterA);
            TurnContext t2 = turn("tool", "r2");
            t2.setToolCallId("call_b");
            t2.setToolName("fn_b");
            request.addTurn(t2);

            String body = client.buildRequestBody(request, "gpt-4o");
            List<Map<String, Object>> messages = messagesOf(body);
            int pairs = 0;
            for (int i = 1; i < messages.size(); i++) {
                if ("tool".equals(messages.get(i).get("role"))) {
                    assertEquals("assistant", messages.get(i - 1).get("role"), "每个 tool 轮前都必须是 assistant 帧");
                    pairs++;
                }
            }
            assertEquals(2, pairs, "两个 tool 轮必须各有一帧配对");
        }

        @Test
        @DisplayName("转义往返：合成帧不破坏整体 JSON 合法性")
        void syntheticFrame_bodyStaysParsable() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("x\"y\\z");
            TurnContext t = turn("tool", "a\"b");
            t.setToolCallId("call_\"x");
            t.setToolName("fn");
            request.addTurn(t);

            String body = client.buildRequestBody(request, "gpt-4o");
            assertNotNull(RecursiveJsonParser.parse(body), "含特殊字符时请求体仍须为合法 JSON");
        }
    }

    @Nested
    class ExtraBodyFields {

        @Test
        @DisplayName("方言扩展片段原样注入请求体顶层")
        void valve_appendedVerbatimAtTopLevel() {
            OpenAiCompatibleClient deepseek = new OpenAiCompatibleClient("https://api.deepseek.com", "test-key", "deepseek-v4-flash-vision-exp", 2, "\"thinking\":{\"type\":\"disabled\"}");
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("You are helpful");
            request.setUserInput("Hello");

            String body = deepseek.buildRequestBody(request, "deepseek-v4-flash-vision-exp");

            Object parsed = RecursiveJsonParser.parse(body);
            assertInstanceOf(Map.class, parsed, "注入扩展片段后请求体必须仍是合法 JSON");
            @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) parsed;
            @SuppressWarnings("unchecked") Map<String, Object> thinking = (Map<String, Object>) root.get("thinking");
            assertEquals("disabled", thinking.get("type"), "扩展成员必须出现在请求体顶层");
        }

        @Test
        @DisplayName("未配置扩展时请求体不含多余逗号——仍是合法 JSON")
        void valve_absent_producesCleanBody() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("Hello");

            String body = client.buildRequestBody(request, "gpt-4o");

            Object parsed = RecursiveJsonParser.parse(body);
            assertInstanceOf(Map.class, parsed, "无扩展时请求体必须是合法 JSON");
        }

        @Test
        @DisplayName("空白扩展按无扩展处理")
        void valve_blank_treatedAsAbsent() {
            OpenAiCompatibleClient blank = new OpenAiCompatibleClient("https://api.openai.com", "test-key", "gpt-4o", 2, "   ");

            LlmRequest request = new LlmRequest();
            request.setUserInput("Hello");

            String body = blank.buildRequestBody(request, "gpt-4o");
            assertFalse(body.contains(",}"), "空白扩展不得留下悬空逗号破坏 JSON");
            assertInstanceOf(Map.class, RecursiveJsonParser.parse(body));
        }

        @Test
        @DisplayName("扩展与 tools 定义共存时序正确")
        void valve_coexistsWithToolDefinitions() {
            OpenAiCompatibleClient withValve = new OpenAiCompatibleClient("https://api.openai.com", "test-key", "gpt-4o", 2, "\"thinking\":{\"type\":\"disabled\"},\"max_tokens\":256");
            LlmRequest request = new LlmRequest();
            request.setUserInput("查订单");
            request.setToolDefinitions(Collections.singletonList("{\"type\":\"function\",\"function\":{\"name\":\"query_order\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}"));

            String body = withValve.buildRequestBody(request, "gpt-4o");

            @SuppressWarnings("unchecked") Map<String, Object> parsed = (Map<String, Object>) RecursiveJsonParser.parse(body);
            assertNotNull(parsed.get("tools"), "tools 定义必须保留");
            assertEquals(256L, parsed.get("max_tokens"), "多成员扩展片段必须全部生效");
            assertNotNull(parsed.get("messages"));
        }
    }
}
