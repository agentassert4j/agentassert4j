package io.github.agentassert4j.util;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

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
        Set<String> old = Set.of("a", "b");
        Set<String> nw = Set.of("a", "b");
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertTrue(result.get("added").isEmpty());
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_addedOnly() {
        Set<String> old = Set.of("a");
        Set<String> nw = Set.of("a", "b", "c");
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertEquals(Set.of("b", "c"), result.get("added"));
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_removedOnly() {
        Set<String> old = Set.of("a", "b", "c");
        Set<String> nw = Set.of("a");
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertTrue(result.get("added").isEmpty());
        assertEquals(Set.of("b", "c"), result.get("removed"));
    }

    @Test
    void computeAddedRemoved_addedAndRemoved() {
        Set<String> old = Set.of("a", "b");
        Set<String> nw = Set.of("b", "c");
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, nw);
        assertEquals(Set.of("c"), result.get("added"));
        assertEquals(Set.of("a"), result.get("removed"));
    }

    @Test
    void computeAddedRemoved_nullOld() {
        Set<String> nw = Set.of("a", "b");
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(null, nw);
        assertEquals(Set.of("a", "b"), result.get("added"));
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_nullNew() {
        Set<String> old = Set.of("a", "b");
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(old, null);
        assertTrue(result.get("added").isEmpty());
        assertEquals(Set.of("a", "b"), result.get("removed"));
    }

    @Test
    void computeAddedRemoved_bothNull() {
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(null, null);
        assertTrue(result.get("added").isEmpty());
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void computeAddedRemoved_emptySets() {
        Map<String, Set<String>> result = TextDiffUtils.computeAddedRemoved(Set.of(), Set.of());
        assertTrue(result.get("added").isEmpty());
        assertTrue(result.get("removed").isEmpty());
    }

    @Test
    void intersectionSize_overlap() {
        Set<String> a = Set.of("x", "y", "z");
        Set<String> b = Set.of("y", "z", "w");
        assertEquals(2, TextDiffUtils.intersectionSize(a, b));
    }

    @Test
    void intersectionSize_identical() {
        Set<String> a = Set.of("x", "y");
        assertEquals(2, TextDiffUtils.intersectionSize(a, a));
    }

    @Test
    void intersectionSize_disjoint() {
        Set<String> a = Set.of("a");
        Set<String> b = Set.of("b");
        assertEquals(0, TextDiffUtils.intersectionSize(a, b));
    }

    @Test
    void intersectionSize_nullSet() {
        assertEquals(0, TextDiffUtils.intersectionSize(null, Set.of("a")));
        assertEquals(0, TextDiffUtils.intersectionSize(Set.of("a"), null));
        assertEquals(0, TextDiffUtils.intersectionSize(null, null));
    }

    @Test
    void jaccardSimilarity_identical() {
        Set<String> a = Set.of("a", "b", "c");
        assertEquals(1.0, TextDiffUtils.jaccardSimilarity(a, a), 0.001);
    }

    @Test
    void jaccardSimilarity_disjoint() {
        Set<String> a = Set.of("a", "b");
        Set<String> b = Set.of("c", "d");
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(a, b), 0.001);
    }

    @Test
    void jaccardSimilarity_partial() {
        Set<String> a = Set.of("a", "b", "c");
        Set<String> b = Set.of("b", "c", "d");
        // intersection=2, union=4 → 0.5
        assertEquals(0.5, TextDiffUtils.jaccardSimilarity(a, b), 0.001);
    }

    @Test
    void jaccardSimilarity_bothEmpty() {
        assertEquals(1.0, TextDiffUtils.jaccardSimilarity(Set.of(), Set.of()), 0.001);
    }

    @Test
    void jaccardSimilarity_oneEmpty() {
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(Set.of("a"), Set.of()), 0.001);
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(Set.of(), Set.of("a")), 0.001);
    }

    @Test
    void jaccardSimilarity_nullSets() {
        assertEquals(1.0, TextDiffUtils.jaccardSimilarity(null, null), 0.001);
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(null, Set.of("a")), 0.001);
        assertEquals(0.0, TextDiffUtils.jaccardSimilarity(Set.of("a"), null), 0.001);
    }
}
