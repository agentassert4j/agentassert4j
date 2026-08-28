package io.github.agentassert4j.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TestExecutionConfig 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class TestExecutionConfigTest {

    @Test
    void defaults_shouldMatchDocumentSpec() {
        TestExecutionConfig config = TestExecutionConfig.defaults();

        assertEquals(30000, config.getTimeoutMs());
        assertEquals(0.0, config.getTemperature());
        assertFalse(config.isDryRun());
        assertNull(config.getModel());
    }

    @Test
    void builder_chainReturnsSameInstance() {
        TestExecutionConfig config = new TestExecutionConfig().timeoutMs(5000).temperature(0.5).dryRun(true).model("gpt-4");

        assertAll(() -> assertEquals(5000, config.getTimeoutMs()), () -> assertEquals(0.5, config.getTemperature()), () -> assertTrue(config.isDryRun()), () -> assertEquals("gpt-4", config.getModel()));
    }

    @Test
    void validate_clampsTimeoutMsLowerBound() {
        TestExecutionConfig config = new TestExecutionConfig().timeoutMs(100);
        config.validate();
        assertEquals(1000, config.getTimeoutMs());
    }

    @Test
    void validate_clampsTemperatureLowerBound() {
        TestExecutionConfig config = new TestExecutionConfig().temperature(-0.5);
        config.validate();
        assertEquals(0.0, config.getTemperature());
    }

    @Test
    void validate_clampsTemperatureUpperBound() {
        TestExecutionConfig config = new TestExecutionConfig().temperature(3.0);
        config.validate();
        assertEquals(2.0, config.getTemperature());
    }

    @Test
    void validate_nonFiniteTemperature_becomesNull() {
        // NaN/Infinity 没有「最近合法值」——JSON 也无对应字面量，置 null 让请求省略该成员
        TestExecutionConfig nan = new TestExecutionConfig().temperature(Double.NaN);
        nan.validate();
        assertNull(nan.getTemperature());

        TestExecutionConfig inf = new TestExecutionConfig().temperature(Double.POSITIVE_INFINITY);
        inf.validate();
        assertNull(inf.getTemperature());
    }

    @Test
    void validate_validValuesUnchanged() {
        TestExecutionConfig config = new TestExecutionConfig().timeoutMs(15000).temperature(0.7);
        config.validate();
        assertAll(() -> assertEquals(15000, config.getTimeoutMs()), () -> assertEquals(0.7, config.getTemperature()));
    }

    @Test
    void settersWork() {
        TestExecutionConfig config = new TestExecutionConfig();
        config.setTimeoutMs(10000);
        config.setTemperature(1.0);
        config.setDryRun(true);
        config.setModel("deepseek-chat");

        assertAll(() -> assertEquals(10000, config.getTimeoutMs()), () -> assertEquals(1.0, config.getTemperature()), () -> assertTrue(config.isDryRun()), () -> assertEquals("deepseek-chat", config.getModel()));
    }
}
