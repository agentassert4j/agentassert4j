package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.spi.LlmClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProtocolRoutingLlmClient 的解析优先级测试 — 显式配置 &gt; 记录方言提示 &gt;
 * openai-chat 兜底；脏提示值兜底不抛错（重放不被单条脏记录中断）。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class ProtocolRoutingLlmClientTest {

    /**
     * 最小手写桩：记录被调协议标记（chat/name 透传标记）。
     */
    private static final class StubClient implements LlmClient {
        private final String protocol;
        final List<String> calls = new ArrayList<>();

        StubClient(String protocol) {
            this.protocol = protocol;
        }

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) {
            calls.add(request != null ? request.getWireProtocol() : "null");
            LlmResponse response = new LlmResponse();
            response.setContent(protocol);
            return response;
        }

        @Override
        public String name() {
            return protocol;
        }
    }

    private static LlmRequest requestWithProtocol(String wireProtocol) {
        LlmRequest request = new LlmRequest();
        request.setUserInput("hi");
        if (wireProtocol != null) {
            request.setWireProtocol(wireProtocol);
        }
        return request;
    }

    @Test
    @DisplayName("未配置时按记录提示路由：anthropic-messages/openai-responses 各到方言客户端")
    void recordHint_routesToDialectClients() throws Exception {
        StubClient chat = new StubClient("openai-chat");
        StubClient anthropic = new StubClient("anthropic-messages");
        StubClient responses = new StubClient("openai-responses");
        ProtocolRoutingLlmClient router = new ProtocolRoutingLlmClient(null, chat, anthropic, responses);

        assertEquals("anthropic-messages", router.chat(requestWithProtocol("anthropic-messages"), 1000).getContent());
        assertEquals("openai-responses", router.chat(requestWithProtocol("openai-responses"), 1000).getContent());
        assertTrue(anthropic.calls.size() == 1 && responses.calls.size() == 1 && chat.calls.isEmpty(), "提示路由命中对应方言，chat 兜底客户端未被调");
    }

    @Test
    @DisplayName("显式配置覆盖记录提示（跨协议重放是显式意图）")
    void configuredProtocol_overridesRecordHint() throws Exception {
        StubClient chat = new StubClient("openai-chat");
        StubClient anthropic = new StubClient("anthropic-messages");
        ProtocolRoutingLlmClient router = new ProtocolRoutingLlmClient("openai-chat", chat, anthropic, new StubClient("openai-responses"));

        assertEquals("openai-chat", router.chat(requestWithProtocol("anthropic-messages"), 1000).getContent(), "配置 openai-chat 显式覆盖 anthropic 提示");
    }

    @Test
    @DisplayName("无配置无提示 → openai-chat 兜底；脏提示值同样兜底不抛错")
    void noHintNoConfig_fallsBackToChat() throws Exception {
        StubClient chat = new StubClient("openai-chat");
        ProtocolRoutingLlmClient router = new ProtocolRoutingLlmClient(null, chat, new StubClient("anthropic-messages"), new StubClient("openai-responses"));

        assertEquals("openai-chat", router.chat(requestWithProtocol(null), 1000).getContent());
        assertEquals("openai-chat", router.chat(requestWithProtocol("gemini-legacy-dirty"), 1000).getContent(), "历史脏 apiProtocol 值兜底而非抛错");
        assertEquals("openai-chat", router.name());
    }

    @Test
    @DisplayName("未知配置值直接构造（绕过 CLI 校验）也兜底不 NPE")
    void unknownConfiguredProtocol_fallsBackNotNpe() throws Exception {
        StubClient chat = new StubClient("openai-chat");
        ProtocolRoutingLlmClient router = new ProtocolRoutingLlmClient("gemini-unknown", chat, new StubClient("anthropic-messages"), new StubClient("openai-responses"));
        assertEquals("openai-chat", router.chat(requestWithProtocol(null), 1000).getContent(), "CLI 装配点之外的防御：未知配置值按未配置处理走兜底");
    }
}
