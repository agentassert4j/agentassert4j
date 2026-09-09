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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * OpenAiResponsesClient 的组装与解析矩阵 — 请求体逐字段钉（instructions/input
 * items 文法/function_call 对/扁平 tools/max_output_tokens 缺省不报）、帧守卫
 * 敌对（缺配对键跳过+告警/同配对去重/system 帧跳过）、多模态直通、协议头与端点
 * 路径、响应归一（output_text 拼接/function_call/usage/finish 派生）。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class OpenAiResponsesClientTest {

    private OpenAiResponsesClient client;

    private PrintStream savedErr;
    private ByteArrayOutputStream errBuffer;

    @BeforeEach
    void setUp() {
        client = new OpenAiResponsesClient("https://api.openai.com", "test-key", "gpt-4o", 0, null);
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
        @DisplayName("标准请求逐字段钉：instructions/input items/max_output_tokens 缺省不报")
        void goldenRequest() {
            String body = client.buildRequestBody(baseRequest(), "gpt-4o");
            Map<?, ?> parsed = (Map<?, ?>) RecursiveJsonParser.parse(body);
            assertEquals("gpt-4o", parsed.get("model"));
            assertEquals("You are a helpful assistant.", parsed.get("instructions"), "system 恒走 instructions");
            assertEquals(0.2, ((Number) parsed.get("temperature")).doubleValue(), 1e-9);
            assertNull(parsed.get("max_output_tokens"), "该文法无必填上限，缺省不报");
            List<?> items = (List<?>) parsed.get("input");
            assertEquals(1, items.size(), "无历史轮时只有末位 user message item: " + body);
            Map<?, ?> message = (Map<?, ?>) items.get(0);
            assertEquals("message", message.get("type"));
            assertEquals("user", message.get("role"));
            Map<?, ?> part = (Map<?, ?>) ((List<?>) message.get("content")).get(0);
            assertEquals("input_text", part.get("type"));
            assertEquals("What is the capital of France?", part.get("text"));
            assertNull(parsed.get("previous_response_id"), "有状态成员不携带（无状态全量输入）");
            assertNull(parsed.get("store"), "有状态成员不携带");
        }

        @Test
        @DisplayName("历史轮 message item 的 part 类型按角色区分（user=input_text/assistant=output_text）；system 帧跳过")
        void messageItems_rolePartTypes_andSystemSkipped() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("continue");
            request.setPreviousTurns(Arrays.asList(new TurnContext("system", "old"), new TurnContext("user", "question"), new TurnContext("assistant", "answer")));
            String body = client.buildRequestBody(request, "gpt-4o");
            List<?> items = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("input");
            assertEquals(3, items.size(), "历史 system 帧跳过 + user/assistant 两轮 + 末位输入: " + body);
            Map<?, ?> userPart = (Map<?, ?>) ((List<?>) ((Map<?, ?>) items.get(0)).get("content")).get(0);
            assertEquals("input_text", userPart.get("type"));
            Map<?, ?> assistantPart = (Map<?, ?>) ((List<?>) ((Map<?, ?>) items.get(1)).get("content")).get(0);
            assertEquals("output_text", assistantPart.get("type"));
        }

        @Test
        @DisplayName("历史工具轮逐对重建：合成 function_call item + 紧随 function_call_output")
        void historyToolFrame_synthesizesPair() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("continue");
            TurnContext toolTurn = new TurnContext("tool", "ORDER-42 found");
            toolTurn.setToolCallId("call_1");
            toolTurn.setToolName("getOrder");
            request.setPreviousTurns(Arrays.asList(new TurnContext("user", "Query order 42"), toolTurn));
            String body = client.buildRequestBody(request, "gpt-4o");
            List<?> items = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("input");
            assertEquals(4, items.size(), "user 轮 + 合成发起 item + 输出 item + 末位输入: " + body);
            Map<?, ?> functionCall = (Map<?, ?>) items.get(1);
            assertEquals("function_call", functionCall.get("type"));
            assertEquals("call_1", functionCall.get("call_id"));
            assertEquals("getOrder", functionCall.get("name"));
            assertEquals("{}", functionCall.get("arguments"), "无载体时以空对象字符串占位");
            Map<?, ?> output = (Map<?, ?>) items.get(2);
            assertEquals("function_call_output", output.get("type"));
            assertEquals("call_1", output.get("call_id"));
            assertEquals("ORDER-42 found", output.get("output"));
        }

        @Test
        @DisplayName("链式合成帧自带真值：arguments 无损（字符串形），紧随输出帧不重复合成")
        void chainFrame_carriesArguments_noDuplicate() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("continue");
            TurnContext chainFrame = new TurnContext("assistant", "");
            chainFrame.setToolCallId("call_1");
            chainFrame.setToolName("getOrder");
            chainFrame.setToolArguments("{\"orderId\":\"42\"}");
            TurnContext outputFrame = new TurnContext("tool", "ORDER-42 found");
            outputFrame.setToolCallId("call_1");
            request.setPreviousTurns(Arrays.asList(chainFrame, outputFrame));
            String body = client.buildRequestBody(request, "gpt-4o");
            List<?> items = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("input");
            assertEquals(3, items.size(), "发起 item（真值）+ 输出 item + 末位输入: " + body);
            Map<?, ?> functionCall = (Map<?, ?>) items.get(0);
            assertEquals("{\"orderId\":\"42\"}", functionCall.get("arguments"), "arguments 真值无损（字符串形）");
        }

        @Test
        @DisplayName("缺配对键的 tool 帧跳过并可见告警")
        void toolFrameWithoutCallId_skippedWithWarning() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("hi");
            request.setPreviousTurns(Arrays.asList(new TurnContext("tool", "orphan result")));
            String body = client.buildRequestBody(request, "gpt-4o");
            List<?> items = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("input");
            assertEquals(1, items.size(), "孤儿 tool 帧被跳过: " + body);
            assertTrue(err().contains("Warning"), "跳帧必须可见告警: " + err());
        }

        @Test
        @DisplayName("多模态 image_url 直通（url 与 data-URI 同形收）")
        void multimodal_passthrough() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("[{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,AAAA\"}}]");
            request.setMultimodalInput(true);
            String body = client.buildRequestBody(request, "gpt-4o");
            List<?> items = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("input");
            List<?> parts = (List<?>) ((Map<?, ?>) items.get(0)).get("content");
            Map<?, ?> image = (Map<?, ?>) parts.get(1);
            assertEquals("input_image", image.get("type"));
            assertEquals("data:image/png;base64,AAAA", image.get("image_url"), "直通不加形态转换");
        }

        @Test
        @DisplayName("范式工具定义转扁平形（type 在顶层）；损坏定义宁可不带")
        void tools_paradigmToFlat_damagedSkipped() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("hi");
            request.setToolDefinitions(Arrays.asList("{\"type\":\"function\",\"function\":{\"name\":\"getOrder\",\"description\":\"Fetch\",\"parameters\":{\"type\":\"object\"}}}", "not json"));
            String body = client.buildRequestBody(request, "gpt-4o");
            List<?> tools = (List<?>) ((Map<?, ?>) RecursiveJsonParser.parse(body)).get("tools");
            assertEquals(1, tools.size(), "损坏定义跳过: " + body);
            Map<?, ?> tool = (Map<?, ?>) tools.get(0);
            assertEquals("function", tool.get("type"), "扁平形：type 在顶层而非 function 嵌套");
            assertEquals("getOrder", tool.get("name"));
            assertTrue(tool.containsKey("parameters"));
            assertFalse(tool.containsKey("function"));
        }
    }

    @Nested
    @DisplayName("HTTP 管道与响应解析")
    class HttpResponse {

        @Test
        @DisplayName("端点路径与 Bearer 头（该方言无 anthropic 头）")
        void endpointAndHeaders() throws Exception {
            AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();
            AtomicReference<String> capturedPath = new AtomicReference<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                capturedHeaders.set(exchange.getRequestHeaders());
                capturedPath.set(exchange.getRequestURI().getPath());
                byte[] body = ("{\"id\":\"r1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o\"," + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]," + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            try {
                OpenAiResponsesClient c = new OpenAiResponsesClient("http://127.0.0.1:" + server.getAddress().getPort(), "sk-y", "gpt-4o", 0, null);
                LlmRequest request = new LlmRequest();
                request.setUserInput("hi");
                LlmResponse response = c.chat(request, 5000);
                assertEquals("/v1/responses", capturedPath.get());
                assertEquals("Bearer sk-y", capturedHeaders.get().get("Authorization").get(0));
                assertNull(capturedHeaders.get().get("x-api-key"), "该方言不用 x-api-key");
                assertEquals("ok", response.getContent());
            } finally {
                server.stop(0);
            }
        }

        @Test
        @DisplayName("响应归一：output_text 拼接/function_call 工具调用/usage 四计数/finish 派生")
        void parseResponse_fullContract() throws Exception {
            String body = "{\"id\":\"r2\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o\"," + "\"output\":[" + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Looking up.\"}]}," + "{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"getOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"42\\\"}\"}]," + "\"usage\":{\"input_tokens\":10,\"output_tokens\":5,\"input_tokens_details\":{\"cached_tokens\":3},\"output_tokens_details\":{\"reasoning_tokens\":4}}}";
            LlmResponse response = client.parseResponse(body);
            assertEquals("Looking up.", response.getContent());
            assertEquals(1, response.getToolCalls().size());
            assertEquals("call_9", response.getToolCalls().get(0).getToolCallId());
            assertEquals("getOrder", response.getToolCalls().get(0).getToolName());
            assertEquals("42", response.getToolCalls().get(0).getArguments().get("orderId"), "arguments 字符串二次解析");
            assertEquals(10, response.getInputTokens());
            assertEquals(5, response.getOutputTokens());
            assertEquals(Integer.valueOf(3), response.getCacheReadTokens());
            assertEquals(Integer.valueOf(4), response.getReasoningTokens());
            assertEquals("tool_calls", response.getFinishReason(), "output 含 function_call 时 finish 恒 tool_calls");
        }

        @Test
        @DisplayName("incomplete 响应的 finish 派生（reason=max_output_tokens → max_tokens）")
        void parseResponse_incompleteDerivesMaxTokens() throws Exception {
            String body = "{\"id\":\"r3\",\"object\":\"response\",\"status\":\"incomplete\",\"incomplete_details\":{\"reason\":\"max_output_tokens\"},\"output\":[]}";
            assertEquals("max_tokens", client.parseResponse(body).getFinishReason());
        }

        @Test
        @DisplayName("非 JSON 对象响应体抛 LlmApiException")
        void parseResponse_invalidBodyThrows() {
            assertThrows(io.github.agentassert4j.spi.LlmApiException.class, () -> client.parseResponse("not json {"));
        }
    }
}
