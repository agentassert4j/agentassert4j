package io.github.agentassert4j.cli;

import io.github.agentassert4j.cli.llm.ProtocolRoutingLlmClient;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * createLlmClient 协议分发测试 — llm.protocol 词表分发行为与未知值就近报错。
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
}
