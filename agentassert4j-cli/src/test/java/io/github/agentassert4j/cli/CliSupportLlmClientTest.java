package io.github.agentassert4j.cli;

import com.sun.net.httpserver.HttpServer;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.cli.llm.ProtocolRoutingLlmClient;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.*;

/**
 * createLlmClient 协议分发测试 — llm.protocol 词表分发行为与未知值就近报错；
 * 健康探测口径统一为传输层可达性（2xx/404/405 算可达，鉴权不在探测面）。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class CliSupportLlmClientTest {

    private static AgentAssert4jConfig configWithProtocol(String protocol) {
        AgentAssert4jConfig config = new AgentAssert4jConfig();
        AgentAssert4jConfig.LlmConfig llm = new AgentAssert4jConfig.LlmConfig();
        if (protocol != null) {
            llm.setProtocol(protocol);
        }
        llm.setEndpoint("https://api.deepseek.com");
        llm.setApiKey("k");
        llm.setModel("deepseek-chat");
        config.setLlm(llm);
        return config;
    }

    @Test
    @DisplayName("缺省与显式配置 → 协议路由客户端（方言按配置/记录提示解析）")
    void defaultsToRoutingClient() {
        assertTrue(CliSupport.createLlmClient(configWithProtocol(null)) instanceof ProtocolRoutingLlmClient, "未配置 protocol 构造路由客户端（推导：记录方言优先，无则 openai-chat）");
        assertTrue(CliSupport.createLlmClient(configWithProtocol("openai-chat")) instanceof ProtocolRoutingLlmClient);
    }

    @Test
    @DisplayName("未知 protocol → E-USAGE 且消息列全部合法值（配置错误就近可见）")
    void unknownProtocol_failsUsageListingValues() {
        CliFailureException e = assertThrows(CliFailureException.class, () -> CliSupport.createLlmClient(configWithProtocol("openai/cjom")));
        assertEquals(CliErrorCode.E_USAGE, e.errorCode);
        assertTrue(e.getMessage().contains("openai/cjom"), e.getMessage());
        String combined = e.getMessage() + " " + e.hint;
        assertTrue(combined.contains("openai-chat") && combined.contains("anthropic-messages") && combined.contains("openai-responses"), "错误与指引必须列三合法值: " + combined);
    }

    @Test
    @DisplayName("两新协议值合法（构造路由客户端，发射按该方言路由）")
    void protocolValues_accepted() {
        assertTrue(CliSupport.createLlmClient(configWithProtocol("anthropic-messages")) instanceof ProtocolRoutingLlmClient);
        assertTrue(CliSupport.createLlmClient(configWithProtocol("openai-responses")) instanceof ProtocolRoutingLlmClient);
    }

    @Test
    @DisplayName("isAvailable 口径=传输层可达：200/404/405 可达，500 不可达")
    void isAvailable_transportReachability() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final int[] code = {200};
        server.createContext("/v1/models", exchange -> {
            byte[] body = "{}".getBytes("UTF-8");
            exchange.sendResponseHeaders(code[0], body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();
        OpenAiCompatibleClient client;
        try {
            client = new OpenAiCompatibleClient("http://127.0.0.1:" + server.getAddress().getPort(), "k", "m", 0, null);
            code[0] = 200;
            assertTrue(client.isAvailable(), "200 算可达");
            code[0] = 404;
            assertTrue(client.isAvailable(), "404（models 端点不存在的兼容端点）也算可达");
            code[0] = 405;
            assertTrue(client.isAvailable(), "405（方法不允许）也算可达");
            code[0] = 500;
            assertFalse(client.isAvailable(), "5xx 是服务端故障，不算可达");
        } finally {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("连接被拒 → isAvailable false（不抛异常）")
    void isAvailable_connectionRefusedFalse() {
        OpenAiCompatibleClient client = new OpenAiCompatibleClient("http://127.0.0.1:1", "k", "m", 0, null);
        assertFalse(client.isAvailable());
    }
}
