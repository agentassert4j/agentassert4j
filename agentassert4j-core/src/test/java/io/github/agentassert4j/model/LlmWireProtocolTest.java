package io.github.agentassert4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LlmWireProtocol 枚举契约测试 — 封闭词表的线上值钉死与解析往返。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class LlmWireProtocolTest {

    @Test
    @DisplayName("wireName 三值钉死（配置键/工具参数/apiProtocol 列共用词形）")
    void wireNames_frozen() {
        assertEquals("openai-chat", LlmWireProtocol.OPENAI_CHAT.wireName());
        assertEquals("anthropic-messages", LlmWireProtocol.ANTHROPIC_MESSAGES.wireName());
        assertEquals("openai-responses", LlmWireProtocol.OPENAI_RESPONSES.wireName());
    }

    @Test
    @DisplayName("fromWireName 全值往返；null 与未知值返回 null")
    void fromWireName_roundTripAndUnknown() {
        for (LlmWireProtocol protocol : LlmWireProtocol.values()) {
            assertSame(protocol, LlmWireProtocol.fromWireName(protocol.wireName()), protocol.wireName() + " 必须往返");
        }
        assertNull(LlmWireProtocol.fromWireName(null));
        assertNull(LlmWireProtocol.fromWireName("gemini"));
        assertNull(LlmWireProtocol.fromWireName(""));
        assertNull(LlmWireProtocol.fromWireName("OpenAI-Chat"), "词形匹配区分大小写（笔误就近报错优于静默容错）");
    }

    @Test
    @DisplayName("legalWireNames 恰含全部合法值（报错文案统一取材处不漏项）")
    void legalWireNames_listsAll() {
        String legal = LlmWireProtocol.legalWireNames();
        List<String> expected = Arrays.asList("openai-chat", "anthropic-messages", "openai-responses");
        for (String value : expected) {
            assertTrue(legal.contains(value), "legalWireNames 必须含 " + value + ": " + legal);
        }
        assertEquals(expected.size(), legal.split(", ").length, "恰三值无多余: " + legal);
    }
}
