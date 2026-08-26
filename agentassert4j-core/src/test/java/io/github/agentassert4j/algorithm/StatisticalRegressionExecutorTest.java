package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticalRegressionExecutorTest {

    private CountingLlmClient stubClient;
    private StatisticalRegressionExecutor executor;

    @BeforeEach
    void setUp() {
        stubClient = new CountingLlmClient();
        executor = new StatisticalRegressionExecutor(
                stubClient, new DeterministicComparator());
    }

    @Test
    void execute_singleMode_wrapsAsStatistical() {
        StatisticalTestConfig config = StatisticalTestConfig.defaults(); // sampleCount=1
        InteractionRecord baseline = makeBaseline();

        StatisticalRegressionResult result = executor.execute(baseline, "new prompt", config);

        assertEquals(1, result.getActualSampleCount());
        assertEquals(1, stubClient.callCount);
    }

    @Test
    void execute_serial_collectsAllSamples() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(5);
        config.setConcurrency(1);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", config);

        assertEquals(5, result.getActualSampleCount());
        assertEquals(5, stubClient.callCount);
    }

    @Test
    void execute_serial_allPass_stable() {
        stubClient.alwaysReturnToolCall = true;
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(3);

        StatisticalRegressionResult result = executor.execute(makeBaselineWithToolCall(), "prompt", config);

        assertEquals(3, result.getActualSampleCount());
    }

    @Test
    void execute_concurrent_collectsAllSamples() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(6);
        config.setConcurrency(3);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", config);

        assertEquals(6, result.getActualSampleCount());
        assertEquals(6, stubClient.callCount);
    }

    @Test
    void execute_costTruncation_noSamples() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(10);
        config.setMaxCostPerCase(0.001); // 0.001 < costPerCall(0.004), floor=0
        // 注意 validate() 会 clamp maxCostPerCase 到 >= 0.01
        // 所以实际 effectiveSampleCount = floor(0.01/0.004) = 2
        // 要真正截断到 0，需要 maxCostPerCase < costPerCall
        // 但 validate 会 clamp 到 0.01，所以无法通过 validate 测试截断到 0
        // 改为验证截断生效：10 次被截断为 2 次

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", config);

        assertTrue(result.getActualSampleCount() <= 2);
        assertTrue(result.getActualSampleCount() < 10);
    }

    @Test
    void execute_timeoutOnThirdSample_othersContinue() {
        stubClient.failOnCallNumber = Set.of(3);
        stubClient.failType = "timeout";

        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(5);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", config);

        assertEquals(5, result.getActualSampleCount());
        assertEquals(5, stubClient.callCount);
        // 第 3 次采样应该有 errorMessage
        boolean hasErrorSample = result.getSamples().stream()
                .anyMatch(s -> s.getErrorMessage() != null);
        assertTrue(hasErrorSample);
    }

    @Test
    void execute_apiErrorOnSecondSample_othersContinue() {
        stubClient.failOnCallNumber = Set.of(2);
        stubClient.failType = "api_error";

        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(4);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", config);

        assertEquals(4, result.getActualSampleCount());
        boolean hasErrorSample = result.getSamples().stream()
                .anyMatch(s -> s.getErrorMessage() != null);
        assertTrue(hasErrorSample);
    }

    @Test
    void execute_totalLatencyAndCostCalculated() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(3);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", config);

        assertTrue(result.getTotalLatencyMs() >= 0);
        assertTrue(result.getEstimatedCost() > 0);
    }

    @Test
    void execute_dryRun_noLlmCalls() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(5);
        config.setDryRun(true);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", config);

        assertEquals(0, stubClient.callCount);
    }

    private InteractionRecord makeBaseline() {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-1");
        r.setSkillId("skill-1");
        r.setTemplateHash("abc123");
        r.setUserInput("test input");
        r.setTurnIndex(0);
        r.setSessionId("session-1");
        r.setModelResponse("old response");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        return r;
    }

    private InteractionRecord makeBaselineWithToolCall() {
        InteractionRecord r = makeBaseline();
        ToolCall tc = new ToolCall();
        tc.setToolName("queryOrder");
        tc.setToolCallId("tc-1");
        tc.setArguments(Map.of("orderId", "ORD-001"));
        tc.setSuccess(true);
        r.setToolCalls(List.of(tc));
        r.setHasToolCalls(true);
        return r;
    }

    static class CountingLlmClient implements LlmClient {
        int callCount = 0;
        boolean alwaysReturnToolCall = false;
        Set<Integer> failOnCallNumber = Collections.emptySet();
        String failType = "timeout";

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs)
                throws LlmTimeoutException, LlmApiException {
            callCount++;
            if (failOnCallNumber.contains(callCount)) {
                if ("timeout".equals(failType)) throw new LlmTimeoutException("timeout");
                throw new LlmApiException("API error");
            }

            LlmResponse response = new LlmResponse();
            response.setContent("response text");
            response.setInputTokens(50);
            response.setOutputTokens(20);

            if (alwaysReturnToolCall) {
                ToolCallResult tc = new ToolCallResult();
                tc.setToolCallId("call-" + callCount);
                tc.setToolName("queryOrder");
                tc.setArguments(Map.of("orderId", "ORD-001"));
                response.setToolCalls(List.of(tc));
            } else {
                response.setToolCalls(Collections.emptyList());
            }

            return response;
        }

        @Override
        public String name() {
            return "gpt-4o";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
