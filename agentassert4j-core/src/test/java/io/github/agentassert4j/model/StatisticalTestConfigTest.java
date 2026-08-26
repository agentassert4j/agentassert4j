package io.github.agentassert4j.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StatisticalTestConfigTest {

    @Test
    void defaults_singleMode() {
        StatisticalTestConfig config = StatisticalTestConfig.defaults();
        assertFalse(config.isStatisticalMode());
        assertEquals(1, config.getSampleCount());
    }

    @Test
    void sampleCount2_isStatisticalMode() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(2);
        assertTrue(config.isStatisticalMode());
    }

    @Test
    void validate_clampsSampleCount() {
        StatisticalTestConfig c = new StatisticalTestConfig();
        c.setSampleCount(0);
        c.validate();
        assertEquals(1, c.getSampleCount());

        c.setSampleCount(200);
        c.validate();
        assertEquals(100, c.getSampleCount());
    }

    @Test
    void validate_clampsPassThreshold() {
        StatisticalTestConfig c = new StatisticalTestConfig();
        c.setPassThreshold(0.0);
        c.validate();
        assertEquals(0.01, c.getPassThreshold(), 0.001);

        c.setPassThreshold(1.5);
        c.validate();
        assertEquals(1.0, c.getPassThreshold(), 0.001);
    }

    @Test
    void validate_clampsRegressionTolerance() {
        StatisticalTestConfig c = new StatisticalTestConfig();
        c.setRegressionTolerance(-0.1);
        c.validate();
        assertEquals(0.0, c.getRegressionTolerance(), 0.001);

        c.setRegressionTolerance(1.0);
        c.validate();
        assertEquals(0.99, c.getRegressionTolerance(), 0.001);
    }

    @Test
    void validate_clampsConcurrency() {
        StatisticalTestConfig c = new StatisticalTestConfig();
        c.setConcurrency(0);
        c.validate();
        assertEquals(1, c.getConcurrency());

        c.setConcurrency(20);
        c.validate();
        assertEquals(10, c.getConcurrency());
    }

    @Test
    void validate_clampsMaxCostPerCase() {
        StatisticalTestConfig c = new StatisticalTestConfig();
        c.setMaxCostPerCase(0.0);
        c.validate();
        assertEquals(0.01, c.getMaxCostPerCase(), 0.001);
    }

    @Test
    void defaults_allFieldsCorrect() {
        StatisticalTestConfig c = StatisticalTestConfig.defaults();
        assertEquals(50, c.getMaxTestCases());
        assertEquals(30000, c.getTimeoutMs());
        assertEquals(2, c.getMaxRetries());
        assertEquals(0.0, c.getTemperature());
        assertFalse(c.isDryRun());
        assertNull(c.getModel());
        assertEquals(1.0, c.getPassThreshold(), 0.001);
        assertEquals(0.0, c.getRegressionTolerance(), 0.001);
        assertEquals(1.0, c.getMaxCostPerCase(), 0.001);
    }
}
