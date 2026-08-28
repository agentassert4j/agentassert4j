package io.github.agentassert4j.util;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TextDiffUtils 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class TextDiffUtilsTest {

    @Test
    void diff_sameText_returnsNull() {
        assertNull(TextDiffUtils.diff("hello", "hello"));
    }

    @Test
    void diff_sameNullText_returnsNull() {
        assertNull(TextDiffUtils.diff(null, null));
    }

    @Test
    void diff_nullToContent() {
        String result = TextDiffUtils.diff(null, "hello");
        assertNotNull(result);
        assertTrue(result.contains("文本发生变化"));
    }

    @Test
    void diff_contentToNull() {
        String result = TextDiffUtils.diff("hello", null);
        assertNotNull(result);
        assertTrue(result.contains("文本发生变化"));
    }

    @Test
    void diff_addedLines() {
        String old = "line1\nline2";
        String nw = "line1\nline2\nline3";
        String result = TextDiffUtils.diff(old, nw);
        assertNotNull(result);
        assertTrue(result.contains("文本发生变化"));
    }

    @Test
    void diff_removedLines() {
        String old = "line1\nline2\nline3";
        String nw = "line1\nline3";
        String result = TextDiffUtils.diff(old, nw);
        assertNotNull(result);
        assertTrue(result.contains("文本发生变化"));
    }

    @Test
    void diff_changedLine() {
        String old = "line1\nold\nline3";
        String nw = "line1\nnew\nline3";
        String result = TextDiffUtils.diff(old, nw);
        assertNotNull(result);
        assertTrue(result.contains("~"));
    }

    @Test
    void diff_completelyDifferent() {
        String result = TextDiffUtils.diff("aaa", "bbb");
        assertNotNull(result);
        assertTrue(result.contains("文本发生变化"));
    }
}
