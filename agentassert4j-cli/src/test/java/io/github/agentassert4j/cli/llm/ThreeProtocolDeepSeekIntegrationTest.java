package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.TurnContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 三协议发射客户端的 DeepSeek 真机连通测试 — anthropic-messages 走 DeepSeek 的
 * Anthropic 兼容端点、openai-responses 走 Responses 端点（openai-chat 形已有
 * DeepSeekIntegrationTest 覆盖）。验收口径为「请求被真实端点接受 + 响应可解析
 * 归一」，不要求兼容端点回填全部方言字段。
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn test -pl agentassert4j-cli -am "-Dtest=ThreeProtocolDeepSeekIntegrationTest" ^
 *     "-Ddeepseek.api.key=sk-xxx"
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class ThreeProtocolDeepSeekIntegrationTest {

    private static final String CHAT_ENDPOINT = "https://api.deepseek.com";
    private static final String ANTHROPIC_ENDPOINT = "https://api.deepseek.com/anthropic";
    private static final String MODEL = "deepseek-chat";

    private static String apiKey;

    @BeforeAll
    static void setUp() {
        apiKey = System.getProperty("deepseek.api.key");
        assumeTrue(apiKey != null && !apiKey.trim().isEmpty(), "跳过：未提供 -Ddeepseek.api.key");
    }

    private static LlmRequest textRequest(String system, String input) {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(system);
        request.setUserInput(input);
        request.setTemperature(0.0);
        return request;
    }

    @Test
    @DisplayName("Anthropic Messages 兼容端点：文本往返（请求被接受 + 响应归一可解析）")
    void anthropicMessages_textRoundTrip() throws Exception {
        AnthropicMessagesClient client = new AnthropicMessagesClient(ANTHROPIC_ENDPOINT, apiKey, MODEL, 1, null);
        LlmResponse response = client.chat(textRequest("Answer in one word.", "What is the capital of France?"), 30000);
        assertNotNull(response.getContent(), "正文必须可提取: " + response);
        assertTrue(response.getContent().toLowerCase().contains("paris"), "常识问题应命中: " + response.getContent());
        assertEquals("stop", response.getFinishReason());
        assertNotNull(response.getServedModel(), "服务端报告模型在场（别名映射归服务端，不钉具体值）");
        assertTrue(response.getInputTokens() > 0, "usage 总量口径已归一: " + response.getInputTokens());
        assertTrue(response.getOutputTokens() > 0);
    }

    @Test
    @DisplayName("Anthropic Messages 兼容端点：工具调用往返（tool_use 文法被端点接受）")
    void anthropicMessages_toolRoundTrip() throws Exception {
        AnthropicMessagesClient client = new AnthropicMessagesClient(ANTHROPIC_ENDPOINT, apiKey, MODEL, 1, null);
        LlmRequest request = textRequest("You call tools.", "What is the weather in Paris?");
        request.setToolDefinitions(Arrays.asList("{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"description\":\"Get weather\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}}"));
        LlmResponse response = client.chat(request, 30000);
        assertFalse(response.getToolCalls().isEmpty(), "应发起工具调用: " + response);
        assertEquals("get_weather", response.getToolCalls().get(0).getToolName());
        assertNotNull(response.getToolCalls().get(0).getToolCallId(), "配对键在场");
        assertEquals("tool_calls", response.getFinishReason());
    }

    @Test
    @DisplayName("Anthropic Messages 兼容端点：工具历史帧文法（合成发起帧 + 结果帧被接受）")
    void anthropicMessages_historyToolFramesAccepted() throws Exception {
        AnthropicMessagesClient client = new AnthropicMessagesClient(ANTHROPIC_ENDPOINT, apiKey, MODEL, 1, null);
        LlmRequest request = textRequest("Answer in one word.", "Summarize the tool result.");
        TurnContext toolTurn = new TurnContext("tool", "{\"temp_c\":21,\"condition\":\"clear\"}");
        toolTurn.setToolCallId("toolu_e2e");
        toolTurn.setToolName("get_weather");
        request.setPreviousTurns(Arrays.asList(new TurnContext("user", "What is the weather in Paris?"), toolTurn));
        LlmResponse response = client.chat(request, 30000);
        assertNotNull(response.getContent(), "携带工具历史帧的请求被端点接受: " + response);
    }

    @Test
    @DisplayName("OpenAI Responses 端点：文本往返（instructions/input items 文法被接受）")
    void responses_textRoundTrip() throws Exception {
        OpenAiResponsesClient client = new OpenAiResponsesClient(CHAT_ENDPOINT, apiKey, MODEL, 1, null);
        LlmResponse response = client.chat(textRequest("Answer in one word.", "What is the capital of France?"), 30000);
        assertNotNull(response.getContent(), "正文必须可提取: " + response);
        assertTrue(response.getContent().toLowerCase().contains("paris"), "常识问题应命中: " + response.getContent());
        assertEquals("stop", response.getFinishReason(), "completed 派生为 stop");
        assertTrue(response.getInputTokens() > 0);
    }

    @Test
    @DisplayName("OpenAI Responses 端点：工具调用往返（扁平 tools + function_call 文法被接受）")
    void responses_toolRoundTrip() throws Exception {
        OpenAiResponsesClient client = new OpenAiResponsesClient(CHAT_ENDPOINT, apiKey, MODEL, 1, null);
        LlmRequest request = textRequest("You call tools.", "What is the weather in Paris?");
        request.setToolDefinitions(Arrays.asList("{\"type\":\"function\",\"function\":{\"name\":\"get_weather\",\"description\":\"Get weather\",\"parameters\":{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}},\"required\":[\"city\"]}}}"));
        LlmResponse response = client.chat(request, 30000);
        assertFalse(response.getToolCalls().isEmpty(), "应发起工具调用: " + response);
        assertEquals("get_weather", response.getToolCalls().get(0).getToolName());
        assertNotNull(response.getToolCalls().get(0).getToolCallId(), "call_id 在场");
        assertEquals("tool_calls", response.getFinishReason(), "output 含 function_call 派生 tool_calls");
    }

    @Test
    @DisplayName("OpenAI Responses 端点：工具历史帧文法（function_call/output 对被接受）")
    void responses_historyToolFramesAccepted() throws Exception {
        OpenAiResponsesClient client = new OpenAiResponsesClient(CHAT_ENDPOINT, apiKey, MODEL, 1, null);
        LlmRequest request = textRequest("Answer in one word.", "Summarize the tool result.");
        TurnContext toolTurn = new TurnContext("tool", "{\"temp_c\":21,\"condition\":\"clear\"}");
        toolTurn.setToolCallId("call_e2e");
        toolTurn.setToolName("get_weather");
        request.setPreviousTurns(Arrays.asList(new TurnContext("user", "What is the weather in Paris?"), toolTurn));
        LlmResponse response = client.chat(request, 30000);
        assertNotNull(response.getContent(), "携带工具历史帧的请求被端点接受: " + response);
    }
}
