package io.github.agentassert4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * wire 词表的唯一定义处的契约测试：采样映射的空缺省语义与 function 信封结构。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class OpenAiWireUtilTest {

    @Test
    @DisplayName("采样映射：非空项落规范键，null 与空 stop 跳过，全缺省为空映射")
    void samplingSkipsNullsAndEmptyStop() {
        Map<String, Object> full = OpenAiWireUtil.sampling(0.3, 0.9, 40, 1024, 0.0, 0.0, Arrays.asList("END"));
        assertEquals(0.3, full.get("temperature"));
        assertEquals(0.9, full.get("top_p"));
        assertEquals(40, full.get("top_k"));
        assertEquals(1024, full.get("max_tokens"));
        assertEquals(0.0, full.get("frequency_penalty"));
        assertEquals(0.0, full.get("presence_penalty"));
        assertEquals(Arrays.asList("END"), full.get("stop"));

        Map<String, Object> partial = OpenAiWireUtil.sampling(null, null, null, 512, null, null, null);
        assertEquals(1, partial.size());
        assertEquals(512, partial.get("max_tokens"));

        Map<String, Object> empty = OpenAiWireUtil.sampling(null, null, null, null, null, null, Arrays.asList());
        assertTrue(empty.isEmpty());
    }

    @Test
    @DisplayName("function 信封：三层结构齐备，description 缺省补空串")
    void functionToolEnvelopeShape() {
        Map<String, Object> parameters = new java.util.LinkedHashMap<>();
        parameters.put("orderId", "string");
        Map<String, Object> tool = OpenAiWireUtil.functionTool("getOrder", null, parameters);

        assertEquals("function", tool.get("type"));
        @SuppressWarnings("unchecked") Map<String, Object> function = (Map<String, Object>) tool.get("function");
        assertEquals("getOrder", function.get("name"));
        assertEquals("", function.get("description"));
        assertEquals(parameters, function.get("parameters"));
        assertFalse(function.containsKey("missing"));
    }
}
