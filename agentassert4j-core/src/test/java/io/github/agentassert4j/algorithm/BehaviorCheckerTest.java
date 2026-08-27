package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BehaviorChecker 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class BehaviorCheckerTest {

    private DeterministicFingerprint fpNoError;
    private DeterministicFingerprint fpHasError;

    @BeforeEach
    void setUp() {
        fpNoError = new DeterministicFingerprint();
        fpNoError.setHasError(false);

        fpHasError = new DeterministicFingerprint();
        fpHasError.setHasError(true);
    }

    @Test
    void mustUseChinese_positive() {
        assertTrue(BehaviorChecker.check("mustUseChinese", fpNoError, "你好世界"));
    }

    @Test
    void mustUseChinese_negative() {
        assertFalse(BehaviorChecker.check("mustUseChinese", fpNoError, "Hello World"));
    }

    @Test
    void mustUseChinese_nullOutput() {
        assertFalse(BehaviorChecker.check("mustUseChinese", fpNoError, null));
    }

    @Test
    void mustUseEnglish_positive() {
        assertTrue(BehaviorChecker.check("mustUseEnglish", fpNoError, "Hello World"));
    }

    @Test
    void mustUseEnglish_negative_containsChinese() {
        assertFalse(BehaviorChecker.check("mustUseEnglish", fpNoError, "Hello 你好"));
    }

    @Test
    void mustUseEnglish_negative_noEnglishLetters() {
        assertFalse(BehaviorChecker.check("mustUseEnglish", fpNoError, "12345!@#"));
    }

    @Test
    void returnsEmptyOnError_noError_anyOutput_passes() {
        assertTrue(BehaviorChecker.check("returnsEmptyOnError", fpNoError, "anything"));
    }

    @Test
    void returnsEmptyOnError_hasError_nullOutput_passes() {
        assertTrue(BehaviorChecker.check("returnsEmptyOnError", fpHasError, null));
    }

    @Test
    void returnsEmptyOnError_hasError_emptyOutput_passes() {
        assertTrue(BehaviorChecker.check("returnsEmptyOnError", fpHasError, ""));
    }

    @Test
    void returnsEmptyOnError_hasError_blankOutput_passes() {
        assertTrue(BehaviorChecker.check("returnsEmptyOnError", fpHasError, "   "));
    }

    @Test
    void returnsEmptyOnError_hasError_emptyArray_passes() {
        assertTrue(BehaviorChecker.check("returnsEmptyOnError", fpHasError, "[]"));
    }

    @Test
    void returnsEmptyOnError_hasError_nonEmptyOutput_fails() {
        assertFalse(BehaviorChecker.check("returnsEmptyOnError", fpHasError, "some error content"));
    }

    @Test
    void returnsErrorCode_hasError_passes() {
        assertTrue(BehaviorChecker.check("returnsErrorCode", fpHasError, "error"));
    }

    @Test
    void returnsErrorCode_noError_fails() {
        assertFalse(BehaviorChecker.check("returnsErrorCode", fpNoError, "ok"));
    }

    @Test
    void noError_noError_passes() {
        assertTrue(BehaviorChecker.check("noError", fpNoError, "ok"));
    }

    @Test
    void noError_hasError_fails() {
        assertFalse(BehaviorChecker.check("noError", fpHasError, "error"));
    }

    @Test
    void jsonOutput_object_passes() {
        assertTrue(BehaviorChecker.check("jsonOutput", fpNoError, "{\"key\":\"value\"}"));
    }

    @Test
    void jsonOutput_array_passes() {
        assertTrue(BehaviorChecker.check("jsonOutput", fpNoError, "[1,2,3]"));
    }

    @Test
    void jsonOutput_plainText_fails() {
        assertFalse(BehaviorChecker.check("jsonOutput", fpNoError, "plain text"));
    }

    @Test
    void jsonOutput_null_fails() {
        assertFalse(BehaviorChecker.check("jsonOutput", fpNoError, null));
    }

    @Test
    void jsonOutput_trimmedWhitespace_passes() {
        assertTrue(BehaviorChecker.check("jsonOutput", fpNoError, "  {\"key\":\"value\"}  "));
    }

    @Test
    void nonEmptyOutput_nonEmpty_passes() {
        assertTrue(BehaviorChecker.check("nonEmptyOutput", fpNoError, "content"));
    }

    @Test
    void nonEmptyOutput_null_fails() {
        assertFalse(BehaviorChecker.check("nonEmptyOutput", fpNoError, null));
    }

    @Test
    void nonEmptyOutput_empty_fails() {
        assertFalse(BehaviorChecker.check("nonEmptyOutput", fpNoError, ""));
    }

    @Test
    void nonEmptyOutput_blank_fails() {
        assertFalse(BehaviorChecker.check("nonEmptyOutput", fpNoError, "   "));
    }

    @Test
    void containsCjk_chinese_passes() {
        assertTrue(BehaviorChecker.check("containsCjk", fpNoError, "你好"));
    }

    @Test
    void containsCjk_hiragana_passes() {
        assertTrue(BehaviorChecker.check("containsCjk", fpNoError, "こんにちは"));
    }

    @Test
    void containsCjk_katakana_passes() {
        assertTrue(BehaviorChecker.check("containsCjk", fpNoError, "カタカナ"));
    }

    @Test
    void containsCjk_latinOnly_fails() {
        assertFalse(BehaviorChecker.check("containsCjk", fpNoError, "Hello"));
    }

    @Test
    void containsCjk_null_fails() {
        assertFalse(BehaviorChecker.check("containsCjk", fpNoError, null));
    }

    @Test
    void unknownBehavior_defaultPasses() {
        assertTrue(BehaviorChecker.check("unknownBehavior123", fpNoError, "anything"));
    }

    @Test
    void unknownBehavior_nullOutput_stillPasses() {
        assertTrue(BehaviorChecker.check("nonexistent", fpNoError, null));
    }

    @Test
    void checkAll_allPass_returnsTrue() {
        assertTrue(BehaviorChecker.checkAll(new HashSet<>(Arrays.asList("nonEmptyOutput", "noError")), fpNoError, "hello"));
    }

    @Test
    void checkAll_oneFails_returnsFalse() {
        assertFalse(BehaviorChecker.checkAll(new HashSet<>(Arrays.asList("nonEmptyOutput", "noError")), fpHasError, "hello"));
    }

    @Test
    void checkAll_emptySet_returnsTrue() {
        assertTrue(BehaviorChecker.checkAll(Collections.emptySet(), fpNoError, "hello"));
    }

    @Test
    void checkAll_nullSet_returnsTrue() {
        assertTrue(BehaviorChecker.checkAll(null, fpNoError, "hello"));
    }

    @Test
    void getBuiltinBehaviorNames_returns8() {
        Set<String> names = BehaviorChecker.getBuiltinBehaviorNames();
        assertEquals(8, names.size());
        assertTrue(names.contains("mustUseChinese"));
        assertTrue(names.contains("mustUseEnglish"));
        assertTrue(names.contains("returnsEmptyOnError"));
        assertTrue(names.contains("returnsErrorCode"));
        assertTrue(names.contains("noError"));
        assertTrue(names.contains("jsonOutput"));
        assertTrue(names.contains("nonEmptyOutput"));
        assertTrue(names.contains("containsCjk"));
    }
}
