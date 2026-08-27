package io.github.agentassert4j.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * HashUtil 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class HashUtilTest {

    @Test
    void sha256_nullInput_returnsUnknown() {
        assertEquals("unknown", HashUtil.sha256(null));
    }

    @Test
    void sha256_emptyString() {
        // SHA-256 of empty string is well-known
        String result = HashUtil.sha256("");
        assertNotNull(result);
        assertEquals(64, result.length()); // 256 bits = 64 hex chars
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", result);
    }

    @Test
    void sha256_helloWorld() {
        // SHA-256("hello") is well-known
        String result = HashUtil.sha256("hello");
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", result);
    }

    @Test
    void sha256_deterministic() {
        String a = HashUtil.sha256("test-input");
        String b = HashUtil.sha256("test-input");
        assertEquals(a, b);
    }

    @Test
    void sha256_differentInputs_differentOutputs() {
        String a = HashUtil.sha256("input-a");
        String b = HashUtil.sha256("input-b");
        assertNotEquals(a, b);
    }

    @Test
    void sha256_threadLocalReuse() throws Exception {
        // 在同一线程中多次调用应该返回一致结果
        String first = HashUtil.sha256("reuse-test");
        String second = HashUtil.sha256("reuse-test");
        String third = HashUtil.sha256("other-input");
        String fourth = HashUtil.sha256("reuse-test");
        assertEquals(first, second);
        assertEquals(first, fourth);
        assertNotEquals(first, third);
    }

    @Test
    void sha256_concurrentThreads() throws Exception {
        String input = "concurrent-test";
        String expected = HashUtil.sha256(input);

        int threadCount = 8;
        Thread[] threads = new Thread[threadCount];
        String[] results = new String[threadCount];
        Exception[] errors = new Exception[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            threads[i] = new Thread(() -> {
                try {
                    results[idx] = HashUtil.sha256(input);
                } catch (Exception e) {
                    errors[idx] = e;
                }
            });
        }

        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        for (int i = 0; i < threadCount; i++) {
            assertNull(errors[i], "Thread " + i + " threw exception");
            assertEquals(expected, results[i], "Thread " + i + " produced different hash");
        }
    }

    @Test
    void sha256_chineseCharacters() {
        String result = HashUtil.sha256("中文测试");
        assertNotNull(result);
        assertEquals(64, result.length());
        // 验证确定性
        assertEquals(result, HashUtil.sha256("中文测试"));
    }

    @Test
    void sha256_longInput() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10000; i++) {
            sb.append("a");
        }
        String result = HashUtil.sha256(sb.toString());
        assertNotNull(result);
        assertEquals(64, result.length());
    }
}
