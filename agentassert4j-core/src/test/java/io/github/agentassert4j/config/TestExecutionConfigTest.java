package io.github.agentassert4j.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestExecutionConfigTest {

    @Test
    void defaults_shouldMatchDocumentSpec() {
        TestExecutionConfig config = TestExecutionConfig.defaults();

        assertEquals(50, config.getMaxTestCases());
        assertEquals(30000, config.getTimeoutMs());
        assertEquals(2, config.getMaxRetries());
        assertEquals(0.0, config.getTemperature());
        assertFalse(config.isDryRun());
        assertNull(config.getModel());
    }

    @Test
    void builder_chainReturnsSameInstance() {
        TestExecutionConfig config = new TestExecutionConfig()
                .maxTestCases(10)
                .timeoutMs(5000)
                .maxRetries(3)
                .temperature(0.5)
                .dryRun(true)
                .model("gpt-4");

        assertAll(
                () -> assertEquals(10, config.getMaxTestCases()),
                () -> assertEquals(5000, config.getTimeoutMs()),
                () -> assertEquals(3, config.getMaxRetries()),
                () -> assertEquals(0.5, config.getTemperature()),
                () -> assertTrue(config.isDryRun()),
                () -> assertEquals("gpt-4", config.getModel())
        );
    }

    @Test
    void validate_clampsMaxTestCasesLowerBound() {
        TestExecutionConfig config = new TestExecutionConfig().maxTestCases(0);
        config.validate();
        assertEquals(1, config.getMaxTestCases());
    }

    @Test
    void validate_clampsMaxTestCasesUpperBound() {
        TestExecutionConfig config = new TestExecutionConfig().maxTestCases(500);
        config.validate();
        assertEquals(200, config.getMaxTestCases());
    }

    @Test
    void validate_clampsMaxTestCasesNegative() {
        TestExecutionConfig config = new TestExecutionConfig().maxTestCases(-10);
        config.validate();
        assertEquals(1, config.getMaxTestCases());
    }

    @Test
    void validate_clampsTimeoutMsLowerBound() {
        TestExecutionConfig config = new TestExecutionConfig().timeoutMs(100);
        config.validate();
        assertEquals(1000, config.getTimeoutMs());
    }

    @Test
    void validate_clampsMaxRetriesLowerBound() {
        TestExecutionConfig config = new TestExecutionConfig().maxRetries(-1);
        config.validate();
        assertEquals(0, config.getMaxRetries());
    }

    @Test
    void validate_clampsMaxRetriesUpperBound() {
        TestExecutionConfig config = new TestExecutionConfig().maxRetries(10);
        config.validate();
        assertEquals(5, config.getMaxRetries());
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
    void validate_validValuesUnchanged() {
        TestExecutionConfig config = new TestExecutionConfig()
                .maxTestCases(100)
                .timeoutMs(15000)
                .maxRetries(1)
                .temperature(0.7);
        config.validate();
        assertAll(
                () -> assertEquals(100, config.getMaxTestCases()),
                () -> assertEquals(15000, config.getTimeoutMs()),
                () -> assertEquals(1, config.getMaxRetries()),
                () -> assertEquals(0.7, config.getTemperature())
        );
    }

    @Test
    void settersWork() {
        TestExecutionConfig config = new TestExecutionConfig();
        config.setMaxTestCases(25);
        config.setTimeoutMs(10000);
        config.setMaxRetries(4);
        config.setTemperature(1.0);
        config.setDryRun(true);
        config.setModel("deepseek-chat");

        assertAll(
                () -> assertEquals(25, config.getMaxTestCases()),
                () -> assertEquals(10000, config.getTimeoutMs()),
                () -> assertEquals(4, config.getMaxRetries()),
                () -> assertEquals(1.0, config.getTemperature()),
                () -> assertTrue(config.isDryRun()),
                () -> assertEquals("deepseek-chat", config.getModel())
        );
    }
}
