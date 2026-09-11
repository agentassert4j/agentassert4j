package io.github.agentassert4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * LlmFinishReason 规范词表契约：finish_reason 列的合法取值封闭且稳定，
 * 各方言归一函数只允许落库本词表的线上值。
 *
 * @author axy-yxa
 * @since 2026-09-10
 */
class LlmFinishReasonTest {

    @Test
    @DisplayName("线上值封闭集合与存储词表逐字一致")
    void wireNamesAreTheStoredVocabulary() {
        Set<String> wireNames = new HashSet<>();
        for (LlmFinishReason reason : LlmFinishReason.values()) {
            wireNames.add(reason.wireName());
        }
        assertEquals(new HashSet<>(Arrays.asList("stop", "tool_calls", "max_tokens", "content_filter", "other")), wireNames, "finish_reason 规范词表变更属于判定语义变更，须经专项评审");
    }
}
