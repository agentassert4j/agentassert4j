package io.github.agentassert4j.cli.llm;

import com.sun.net.httpserver.HttpServer;
import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * AnthropicMessagesClient 的组装与解析矩阵 — 请求体逐字段钉（system 顶层/max_tokens
 * 兜定/工具帧逐对重建/多模态 data-URI 拆解）、帧守卫敌对（缺配对键跳过+告警/同配对
 * 去重）、协议头与端点路径、响应归一（正文拼接/工具调用/usage 三项求和/finish 归一）。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class AnthropicMessagesClientTest {

    private AnthropicMessagesClient client;

    private PrintStream savedErr;
    private ByteArrayOutputStream errBuffer;

    @BeforeEach
    void setUp() {
        client = new AnthropicMessagesClient("https://api.anthropic.com", "test-key", "claude-3-5", AnthropicMessagesClient.DEFAULT_MAX_TOKENS, null);
        // 捕获 System.err 验证跳帧告警的可见性（还原于 afterEach）
        savedErr = System.err;
        errBuffer = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errBuffer, true));
    }

    @AfterEach
    void tearDown() {
        System.setErr(savedErr);
    }

    private String err() {
        return new String(errBuffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static LlmRequest baseRequest() {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt("You are a helpful assistant.");
        request.setUserInput("What is the capital of France?");
        request.setTemperature(0.2);
        return request;
    }

    @Nested
    @DisplayName("请求体组装")
    class RequestBody {

        @Test
        @DisplayName("标准请求逐字段钉：model/max_tokens 兜定/system 顶层/末位 user/温度")
        void goldenRequest() {
            String body = client.buildRequestBody(baseRequest(), "claude-3-5");
            Map<?, ?> parsed = (Map<?, ?>) RecursiveJsonParser.parse(body);
            assertEquals("claude-3-5", parsed.get("model"));
            assertEquals(4096, ((Number) parsed.get("max_tokens")).intValue(), "max_tokens 必填兜定常量");
            assertEquals("You are a helpful assistant.", parsed.get("system"), "system 恒走顶层成员");
            assertEquals(0.2, ((Number) parsed.get("temperature")).doubleValue(), 1e-9);
            List<?> messages = (List<?>) parsed.get("messages");
            assertEquals(1, messages.size(), "无历史轮时只有末位 user 消息: " + body);
            Map<?, ?> user = (Map<?, ?>) messages.get(0);
            assertEquals("user", user.get("role"));
            assertEquals("What is the capital of France?", user.get("content"));
            assertNull(parsed.get("tools"), "无工具定义不携带 tools 成员");
        }

        @Test
        @DisplayName("温度 null 省略（不携带成员）；历史 system 帧跳过防双 system")
        void nullTemperatureAndHistorySystemSkipped() {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("sys");
            request.setUserInput("hi");
            request.setPreviousTurns(Arrays.asList(new TurnContext("system", "old system"), new TurnContext("user", "previous question")));
            String body = client.buildRequestBody(request, "claude-3-5");
            Map<?, ?> parsed = (Map<?, ?>) RecursiveJsonParser.parse(body);
            assertNull(parsed.get("temperature"));
            assertEquals("sys", parsed.get("system"), "system 只取模板位，历史 system 帧不并入");
            List<?> messages = (List<?>) parsed.get("messages");
            // 历史 user 轮与末位输入相邻同角色：合并为单条 user 的 content 块数组（规范交替形）
            assertEquals(1, messages.size(), "连续 user 帧合并为单条消息: " + body);
            List<?> content = (List<?>) ((Map<?, ?>) messages.get(0)).get("content");
            assertEquals(2, content.size(), "两个文本帧合并为两个 text 块: " + body);
            assertEquals("previous question", ((Map<?, ?>) content.get(0)).get("text"));
            assertEquals("hi", ((Map<?, ?>) content.get(1)).get("text"));
        }

        @Test
        @DisplayName("历史工具轮逐对重建：合成 assistant[tool_use] 发起帧 + 紧随 user[tool_result]")
        void historyToolFrame_synthesizesPair() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("continue");
            TurnContext toolTurn = new TurnContext("tool", "ORDER-42 found");
            toolTurn.setToolCallId("toolu_1");
            toolTurn.setToolName("getOrder");
            request.setPreviousTurns(Arrays.asList(new TurnContext("user", "Query order 42"), toolTurn));
            String body = client.buildRequestBody(request, "claude-3-5");
            List<?> messages = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("messages");
            // tool_result 帧与末位文本输入相邻同角色：合并进单条 user（tool_result 块前置）
            assertEquals(3, messages.size(), "user 轮 + 合成发起帧 + user[tool_result+text]: " + body);
            Map<?, ?> synthesized = (Map<?, ?>) messages.get(1);
            assertEquals("assistant", synthesized.get("role"));
            Map<?, ?> toolUse = (Map<?, ?>) ((List<?>) synthesized.get("content")).get(0);
            assertEquals("tool_use", toolUse.get("type"));
            assertEquals("toolu_1", toolUse.get("id"));
            assertEquals("getOrder", toolUse.get("name"));
            assertEquals(Collections.emptyMap(), toolUse.get("input"), "无载体时以空对象占位");
            Map<?, ?> merged = (Map<?, ?>) messages.get(2);
            assertEquals("user", merged.get("role"));
            List<?> mergedBlocks = (List<?>) merged.get("content");
            assertEquals(2, mergedBlocks.size());
            Map<?, ?> toolResult = (Map<?, ?>) mergedBlocks.get(0);
            assertEquals("tool_result", toolResult.get("type"), "tool_result 块按文法要求前置于文本块");
            assertEquals("toolu_1", toolResult.get("tool_use_id"));
            assertEquals("ORDER-42 found", toolResult.get("content"));
            Map<?, ?> textBlock = (Map<?, ?>) mergedBlocks.get(1);
            assertEquals("text", textBlock.get("type"));
            assertEquals("continue", textBlock.get("text"));
        }

        @Test
        @DisplayName("链式合成帧自带真值：tool_use 无损携带 arguments，紧随 tool 帧不重复合成")
        void chainFrame_carriesArguments_noDuplicate() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("continue");
            TurnContext chainFrame = new TurnContext("assistant", "");
            chainFrame.setToolCallId("toolu_1");
            chainFrame.setToolName("getOrder");
            chainFrame.setToolArguments("{\"orderId\":\"42\"}");
            TurnContext resultFrame = new TurnContext("tool", "ORDER-42 found");
            resultFrame.setToolCallId("toolu_1");
            request.setPreviousTurns(Arrays.asList(chainFrame, resultFrame));
            String body = client.buildRequestBody(request, "claude-3-5");
            List<?> messages = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("messages");
            assertEquals(2, messages.size(), "发起帧（真值）+ user[tool_result+text 合并]，无重复合成: " + body);
            Map<?, ?> toolUse = (Map<?, ?>) ((List<?>) ((Map<?, ?>) messages.get(0)).get("content")).get(0);
            Map<?, ?> input = (Map<?, ?>) toolUse.get("input");
            assertEquals("42", input.get("orderId"), "arguments 真值无损");
        }

        @Test
        @DisplayName("缺配对键的 tool 帧跳过并可见告警")
        void toolFrameWithoutCallId_skippedWithWarning() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("hi");
            request.setPreviousTurns(Arrays.asList(new TurnContext("tool", "orphan result")));
            String body = client.buildRequestBody(request, "claude-3-5");
            List<?> messages = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("messages");
            assertEquals(1, messages.size(), "孤儿 tool 帧被跳过: " + body);
            assertTrue(err().contains("Warning"), "跳帧必须可见告警: " + err());
        }

        @Test
        @DisplayName("多模态 data-URI 拆解回 base64 source；http URL 图像丢弃告警")
        void multimodal_dataUriSplit_andHttpUrlDropped() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("[{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}},{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/cat.png\"}}]");
            request.setMultimodalInput(true);
            String body = client.buildRequestBody(request, "claude-3-5");
            List<?> messages = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("messages");
            List<?> blocks = (List<?>) ((Map<?, ?>) messages.get(0)).get("content");
            assertEquals(2, blocks.size(), "text 块 + base64 图像块（http URL 丢弃）: " + body);
            Map<?, ?> image = (Map<?, ?>) blocks.get(1);
            assertEquals("image", image.get("type"));
            Map<?, ?> source = (Map<?, ?>) image.get("source");
            assertEquals("base64", source.get("type"));
            assertEquals("image/png", source.get("media_type"));
            assertEquals("AAAA", source.get("data"));
            assertTrue(err().contains("Warning"), "http URL 丢弃必须告警: " + err());
        }

        @Test
        @DisplayName("多模态 part 全部被丢弃 → content 空字符串（不产出空 content 数组）")
        void multimodal_allDropped_emptyStringContent() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("[{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/cat.png\"}}]");
            request.setMultimodalInput(true);
            String body = client.buildRequestBody(request, "claude-3-5");
            List<?> messages = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("messages");
            assertEquals("", ((Map<?, ?>) messages.get(0)).get("content"), "全丢退化为空字符串 content: " + body);
        }

        @Test
        @DisplayName("范式工具定义转扁平形；损坏定义宁可不带")
        void tools_paradigmToFlat_damagedSkipped() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("hi");
            request.setToolDefinitions(Arrays.asList("{\"type\":\"function\",\"function\":{\"name\":\"getOrder\",\"description\":\"Fetch\",\"parameters\":{\"type\":\"object\"}}}", "not json"));
            String body = client.buildRequestBody(request, "claude-3-5");
            Map<?, ?> parsed = (Map<?, ?>) RecursiveJsonParser.parse(body);
            List<?> tools = (List<?>) parsed.get("tools");
            assertEquals(1, tools.size(), "损坏定义跳过: " + body);
            Map<?, ?> tool = (Map<?, ?>) tools.get(0);
            assertEquals("getOrder", tool.get("name"));
            assertEquals("Fetch", tool.get("description"));
            assertTrue(tool.containsKey("input_schema"), "嵌套 parameters 转扁平 input_schema");
            assertFalse(tool.containsKey("function"));
        }

        @Test
        @DisplayName("input_schema 方言必填字段矫正：无参工具补空对象 schema")
        void tools_noArgTool_getsEmptyObjectSchema() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("hi");
            request.setToolDefinitions(Arrays.asList("{\"type\":\"function\",\"function\":{\"name\":\"ping\",\"description\":\"No-op\"}}"));
            String body = client.buildRequestBody(request, "claude-3-5");
            List<?> tools = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("tools");
            Map<?, ?> schema = (Map<?, ?>) ((Map<?, ?>) tools.get(0)).get("input_schema");
            assertEquals("object", schema.get("type"), "该文法必填 input_schema，无参工具补空对象 schema: " + body);
            assertNotNull(schema.get("properties"));
        }

        @Test
        @DisplayName("input_schema 方言必填字段矫正：缺 type 补 object 且保留原键；显式 type 不覆盖")
        void tools_schemaTypeRectified_onlyWhenAbsent() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("hi");
            request.setToolDefinitions(Arrays.asList("{\"type\":\"function\",\"function\":{\"name\":\"a\",\"parameters\":{\"properties\":{\"x\":{\"type\":\"string\"}}}}}", "{\"type\":\"function\",\"function\":{\"name\":\"b\",\"parameters\":{\"type\":\"string\"}}}"));
            String body = client.buildRequestBody(request, "claude-3-5");
            List<?> tools = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("tools");
            Map<?, ?> first = (Map<?, ?>) ((Map<?, ?>) tools.get(0)).get("input_schema");
            assertEquals("object", first.get("type"), "缺 type 补 object");
            Map<?, ?> properties = (Map<?, ?>) first.get("properties");
            Map<?, ?> x = (Map<?, ?>) properties.get("x");
            assertEquals("string", x.get("type"), "原键保留");
            assertEquals("string", ((Map<?, ?>) ((Map<?, ?>) tools.get(1)).get("input_schema")).get("type"), "显式 type 原样透传不覆盖");
        }

        @Test
        @DisplayName("参数损坏（非对象 schema）的定义跳过")
        void tools_nonObjectSchema_skipped() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("hi");
            request.setToolDefinitions(Arrays.asList("{\"type\":\"function\",\"function\":{\"name\":\"broken\",\"parameters\":\"not a schema\"}}"));
            String body = client.buildRequestBody(request, "claude-3-5");
            assertNull(((Map<?, ?>) RecursiveJsonParser.parse(body)).get("tools"), "损坏 schema 定义宁可不带");
        }
    }

    @Nested
    @DisplayName("HTTP 管道与响应解析")
    class HttpResponse {

        @Test
        @DisplayName("端点路径与协议头（x-api-key + anthropic-version），Bearer 不出现")
        void endpointAndHeaders() throws Exception {
            AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();
            AtomicReference<String> capturedPath = new AtomicReference<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                capturedHeaders.set(exchange.getRequestHeaders());
                capturedPath.set(exchange.getRequestURI().getPath());
                byte[] body = ("{\"id\":\"m1\",\"model\":\"claude-3-5\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                AnthropicMessagesClient c = new AnthropicMessagesClient("http://127.0.0.1:" + server.getAddress().getPort(), "sk-x", "claude-3-5", 0, null);
                LlmRequest request = new LlmRequest();
                request.setUserInput("hi");
                LlmResponse response = c.chat(request, 5000);
                assertEquals("/v1/messages", capturedPath.get());
                assertEquals("sk-x", capturedHeaders.get().get("x-api-key").get(0));
                assertEquals(AnthropicMessagesClient.ANTHROPIC_VERSION, capturedHeaders.get().get("anthropic-version").get(0));
                assertNull(capturedHeaders.get().get("Authorization"), "该方言不用 Bearer");
                assertEquals("ok", response.getContent());
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("响应归一：text 块拼接/tool_use 工具调用/usage 三项求和/finish 归一")
        void parseResponse_fullContract() throws Exception {
            String body = "{\"id\":\"m2\",\"model\":\"claude-3-5\",\"content\":[" + "{\"type\":\"text\",\"text\":\"Looking up. \"},{\"type\":\"text\",\"text\":\"Done.\"}," + "{\"type\":\"tool_use\",\"id\":\"toolu_9\",\"name\":\"getOrder\",\"input\":{\"orderId\":\"42\"}}]," + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":2}}";
            LlmResponse response = client.parseResponse(body);
            assertEquals("Looking up. Done.", response.getContent(), "text 块空串直拼");
            assertEquals(1, response.getToolCalls().size());
            assertEquals("toolu_9", response.getToolCalls().get(0).getToolCallId());
            assertEquals("getOrder", response.getToolCalls().get(0).getToolName());
            assertEquals("42", response.getToolCalls().get(0).getArguments().get("orderId"));
            assertEquals(15, response.getInputTokens(), "总量=10+3+2 三项求和");
            assertEquals(5, response.getOutputTokens());
            assertEquals(Integer.valueOf(2), response.getCacheReadTokens());
            assertEquals(Integer.valueOf(3), response.getCacheWriteTokens());
            assertEquals("tool_calls", response.getFinishReason());
            assertEquals("claude-3-5", response.getServedModel());
            assertTrue(response.getUsageRaw().contains("input_tokens"));
        }

        @Test
        @DisplayName("非 JSON 对象响应体抛 LlmApiException")
        void parseResponse_invalidBodyThrows() {
            assertThrows(io.github.agentassert4j.spi.LlmApiException.class, () -> client.parseResponse("not json {"));
        }
    }
}
