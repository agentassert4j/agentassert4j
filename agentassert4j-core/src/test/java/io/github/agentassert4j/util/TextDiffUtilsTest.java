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

    @Test
    void computeAddedRemoved_noChange() {
        Set<String> old = new HashSet<>(Arrays.asList("a", "b"));
        Set<String> nw = new HashSet<>(Arrays.asList("a", "b"));
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertTrue(result.get("added").isEmpty());
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_addedOnly() {
        Set<String> old = Collections.singleton("a");
        Set<String> nw = new HashSet<>(Arrays.asList("a", "b", "c"));
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertEquals(new HashSet<>(Arrays.asList("b", "c")), result.get("added"));
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_removedOnly() {
        Set<String> old = new HashSet<>(Arrays.asList("a", "b", "c"));
        Set<String> nw = Collections.singleton("a");
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertTrue(result.get("added").isEmpty());
        assertEquals(new HashSet<>(Arrays.asList("b", "c")), result.get("removed"));
    }

    @Test
    void computeAddedRemoved_addedAndRemoved() {
        Set<String> old = new HashSet<>(Arrays.asList("a", "b"));
        Set<String> nw = new HashSet<>(Arrays.asList("b", "c"));
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertEquals(Collections.singleton("c"), result.get("added"));
        assertEquals(Collections.singleton("a"), result.get("removed"));
    }

    @Test
    void computeAddedRemoved_nullOld() {
        Set<String> nw = new HashSet<>(Arrays.asList("a", "b"));
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(null, nw);
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), result.get("added"));
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_nullNew() {
        Set<String> old = new HashSet<>(Arrays.asList("a", "b"));
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, null);
        assertTrue(result.get("added").isEmpty());
        assertEquals(new HashSet<>(Arrays.asList("a", "b")), result.get("removed"));
    }

    @Test
    void computeAddedRemoved_bothNull() {
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(null, null);
        assertTrue(result.get("added").isEmpty());
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_emptySets() {
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(Collections.emptySet(), Collections.emptySet());
        assertTrue(result.get("added").isEmpty());
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void intersectionSize_overlap() {
        Set<String> a = new HashSet<>(Arrays.asList("x", "y", "z"));
        Set<String> b = new HashSet<>(Arrays.asList("y", "z", "w"));
        assertEquals(2, TextDiffUtils.intersectionSize(a, b));
    }

    @Test
    void intersectionSize_identical() {
        Set<String> a = new HashSet<>(Arrays.asList("x", "y"));
        assertEquals(2, TextDiffUtils.intersectionSize(a, a));
    }

    @Test
    void intersectionSize_disjoint() {
        Set<String> a = Collections.singleton("a");
        Set<String> b = Collections.singleton("b");
        assertEquals(0, TextDiffUtils.intersectionSize(a, b));
    }

    @Test
    void intersectionSize_nullSet() {
        assertEquals(0, TextDiffUtils.intersectionSize(null, Collections.singleton("a")));
        assertEquals(0, TextDiffUtils.intersectionSize(Collections.singleton("a"), null));
        assertEquals(0, TextDiffUtils.intersectionSize(null, null));
    }

    @Test
    void jaccardSimilarity_identical() {
        Set<String> a = new HashSet<>(Arrays.asList("a", "b", "c"));
        assertEquals(1.0, TextDiffUtils.jaccardSimilarity(a, a), 0.001);
    }

    @Test
    void jaccardSimilarity_disjoint() {
        Set<String> a = new HashSet<>(Arrays.asList("a", "b"));
        Set<String> b = new HashSet<>(Arrays.asList("c", "d"));
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(a, b), 0.001);
    }

    @Test
    void jaccardSimilarity_partial() {
        Set<String> a = new HashSet<>(Arrays.asList("a", "b", "c"));
        Set<String> b = new HashSet<>(Arrays.asList("b", "c", "d"));
        // intersection=2, union=4 → 0.5
        assertEquals(0.5, TextDiffUtils.jaccardSimilarity(a, b), 0.001);
    }

    @Test
    void jaccardSimilarity_bothEmpty() {
        assertEquals(1.0, TextDiffUtils.jaccardSimilarity(Collections.emptySet(), Collections.emptySet()), 0.001);
    }

    @Test
    void jaccardSimilarity_oneEmpty() {
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(Collections.singleton("a"), Collections.emptySet()), 0.001);
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(Collections.emptySet(), Collections.singleton("a")), 0.001);
    }

    @Test
    void jaccardSimilarity_nullSets() {
        assertEquals(1.0, TextDiffUtils.jaccardSimilarity(null, null), 0.001);
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(null, Collections.singleton("a")), 0.001);
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(Collections.singleton("a"), null), 0.001);
    }
}
