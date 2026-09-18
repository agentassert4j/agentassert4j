package io.github.agentassert4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 工具结果方言归一的契约钉：字符串字面量解一层，其余形态透传。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class ToolResultNormalizerTest {

    @Test
    @DisplayName("字符串字面量解码一层还原语义原文")
    void stringLiteralDecodedOneLayer() {
        assertEquals("REFUND-8841", ToolResultNormalizer.normalize("\"REFUND-8841\""));
        assertEquals("ok", ToolResultNormalizer.normalize(RecursiveJsonParser.serialize("ok")));
        assertEquals("退款\n多行 \"引号\"", ToolResultNormalizer.normalize(RecursiveJsonParser.serialize("退款\n多行 \"引号\"")));
    }

    @Test
    @DisplayName("对象/数组/纯文本原样保留，null 与空串原样返回")
    void otherShapesPassThrough() {
        assertEquals("{\"orderId\":8841}", ToolResultNormalizer.normalize("{\"orderId\":8841}"));
        assertEquals("[1,2]", ToolResultNormalizer.normalize("[1,2]"));
        assertEquals("订单已发货 REF-1", ToolResultNormalizer.normalize("订单已发货 REF-1"));
        assertNull(ToolResultNormalizer.normalize(null));
        assertEquals("", ToolResultNormalizer.normalize(""));
    }
}
