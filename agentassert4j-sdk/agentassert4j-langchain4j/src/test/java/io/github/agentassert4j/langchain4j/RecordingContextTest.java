package io.github.agentassert4j.langchain4j;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 录制上下文契约测试：ThreadLocal 作用域嵌套与恢复。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class RecordingContextTest {

    @Test
    @DisplayName("作用域嵌套：内层关闭恢复外层，全部关闭后清空")
    void nestingRestoresOuterScope() {
        assertNull(RecordingContext.currentOrNull());
        try (RecordingContext outer = RecordingContext.start("outer-session").withInvocationId("outer-inv")) {
            assertEquals("outer-session", RecordingContext.currentOrNull().sessionId());
            try (RecordingContext inner = RecordingContext.start("inner-session").withInvocationId("inner-inv")) {
                assertEquals("inner-session", RecordingContext.currentOrNull().sessionId());
            }
            assertEquals("outer-session", RecordingContext.currentOrNull().sessionId());
        }
        assertNull(RecordingContext.currentOrNull());
    }

    @Test
    @DisplayName("元数据声明：null 键值跳过，合法键值序列化进记录")
    void metadataDeclaration() {
        try (RecordingContext ctx = RecordingContext.start("s").withMetadata("channel", "app").withMetadata("leak", null).withMetadata(null, "x")) {
            assertEquals(1, ctx.metadata().size());
            assertEquals("app", ctx.metadata().get("channel"));
        }
    }
}
