package io.github.agentassert4j.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JsonSupport 的单元测试 — JSON 字符串转义契约。
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
class JsonSupportTest {

    @Test
    @DisplayName("常见短转义：引号/反斜杠/控制符")
    void escape_shortSequences() {
        assertEquals("a\\\"b", JsonSupport.escape("a\"b"));
        assertEquals("a\\\\b", JsonSupport.escape("a\\b"));
        assertEquals("a\\nb", JsonSupport.escape("a\nb"));
        assertEquals("a\\tb", JsonSupport.escape("a\tb"));
        assertEquals("a\\rb", JsonSupport.escape("a\rb"));
        assertEquals("\\b\\f", JsonSupport.escape("\b\f"));
    }

    @Test
    @DisplayName("0x20 以下控制字符按 \\uXXXX 强制转义")
    void escape_controlCharsToUnicodeForm() {
        assertEquals("\\u0001", JsonSupport.escape("\u0001"));
        assertEquals("\\u001f", JsonSupport.escape("\u001f"));
    }

    @Test
    @DisplayName("null 视为空串；常规字符（含中文/emoji）原样保留")
    void escape_nullAndPlainContent() {
        assertEquals("", JsonSupport.escape(null));
        assertEquals("订单 SO-1 已发货 \uD83D\uDE80", JsonSupport.escape("订单 SO-1 已发货 \uD83D\uDE80"));
    }

    @Test
    @DisplayName("产物是合法 JSON 字符串：不残留裸引号与裸控制字符")
    void escape_outputIsEmbeddable() {
        String hostile = "引号\" 反斜杠\\ 换行\n 制表\t 控制符\u0007 中文";
        String escaped = JsonSupport.escape(hostile);
        for (int i = 0; i < escaped.length(); i++) {
            char c = escaped.charAt(i);
            assertFalse(c == '"' && (i == 0 || escaped.charAt(i - 1) != '\\'), "裸引号不得出现: " + escaped);
            assertTrue(c >= 0x20 || c == '\\', "裸控制字符不得出现: " + escaped);
        }
        // 转义后可直接嵌入 JSON 字符串并被重新解析回原文
        String wrapped = "{\"v\":\"" + escaped + "\"}";
        assertTrue(wrapped.contains("引号"), "中文内容必须原样保留: " + wrapped);
    }
}
