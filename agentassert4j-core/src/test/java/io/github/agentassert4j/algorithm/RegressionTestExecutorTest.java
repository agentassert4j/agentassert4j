package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RegressionTestExecutor 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class RegressionTestExecutorTest {

    private StubLlmClient stubClient;
    private RegressionTestExecutor executor;

    @BeforeEach
    void setUp() {
        stubClient = new StubLlmClient();
        executor = new RegressionTestExecutor(
                stubClient,
                new DeterministicComparator(),
                null
        );
    }

    @Test
    void buildReplayRequest_replacesSystemPrompt() {
        InteractionRecord baseline = makeBaseline("old prompt hash", "user input");
        TestExecutionConfig config = TestExecutionConfig.defaults();

        LlmRequest request = executor.buildReplayRequest(baseline, "new prompt", config);

        assertEquals("new prompt", request.getSystemPrompt());
        assertEquals("user input", request.getUserInput());
    }

    @Test
    void buildReplayRequest_injectsPreviousTurns() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setTurnIndex(2);
        baseline.setPreviousTurns(List.of(
                new TurnContext("user", "q1"),
                new TurnContext("assistant", "a1"),
                new TurnContext("tool", "result1")
        ));

        TestExecutionConfig config = TestExecutionConfig.defaults();
        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", config);

        assertNotNull(request.getPreviousTurns());
        assertEquals(3, request.getPreviousTurns().size());
        assertEquals("q1", request.getPreviousTurns().get(0).getContent());
        assertEquals("a1", request.getPreviousTurns().get(1).getContent());
    }

    @Test
    void buildReplayRequest_setsModelFromConfig() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        TestExecutionConfig config = new TestExecutionConfig().model("deepseek-chat");

        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", config);

        assertEquals("deepseek-chat", request.getModel());
    }

    @Test
    void buildReplayRequest_noTurns_whenTurnIndexZero() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setTurnIndex(0);

        TestExecutionConfig config = TestExecutionConfig.defaults();
        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", config);

        // previousTurns 应为 null 或空
        assertTrue(request.getPreviousTurns() == null || request.getPreviousTurns().isEmpty());
    }

    @Test
    void buildCurrentRecord_extractsToolCalls() {
        InteractionRecord baseline = makeBaseline("hash", "input");

        LlmResponse response = new LlmResponse();
        response.setContent("response text");
        response.setInputTokens(100);
        response.setOutputTokens(50);

        ToolCallResult tc = new ToolCallResult();
        tc.setToolCallId("call_1");
        tc.setToolName("queryOrder");
        tc.setArguments(Map.of("orderId", "ORD-001"));
        response.setToolCalls(List.of(tc));

        InteractionRecord current = executor.buildCurrentRecord(baseline, response, "new prompt");

        assertNotNull(current.getRecordId());
        assertNotNull(current.getTemplateHash());
        assertEquals("input", current.getUserInput());
        assertEquals("response text", current.getModelResponse());
        assertEquals(100, current.getInputTokens());
        assertEquals(50, current.getOutputTokens());
        assertTrue(current.isHasToolCalls());
        assertEquals(1, current.getToolCalls().size());
        assertEquals("queryOrder", current.getToolCalls().get(0).getToolName());
        assertEquals("call_1", current.getToolCalls().get(0).getToolCallId());
        assertTrue(current.getToolCalls().get(0).isSuccess());
    }

    @Test
    void buildCurrentRecord_noToolCalls() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setMultimodalInput(true);
        baseline.setMultimodalContent("[{\"type\":\"image\"}]");

        LlmResponse response = new LlmResponse();
        response.setContent("just text");
        response.setToolCalls(Collections.emptyList());

        InteractionRecord current = executor.buildCurrentRecord(baseline, response, "prompt");

        assertFalse(current.isHasToolCalls());
        assertTrue(current.getToolCalls().isEmpty());
        assertTrue(current.isMultimodalInput());
        assertEquals("[{\"type\":\"image\"}]", current.getMultimodalContent());
    }

    @Test
    void execute_success_returnsComparisonResult() {
        InteractionRecord baseline = makeBaselineWithToolCall("hash", "input");

        stubClient.response = makeToolCallResponse("queryOrder", "ORD-001");

        TestExecutionConfig config = TestExecutionConfig.defaults();
        RegressionTestResult result = executor.execute(baseline, "new prompt", config);

        assertEquals("rec-1", result.getBaselineRecordId());
        assertEquals("skill-1", result.getSkillId());
        assertEquals(TestResultStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getComparison());
        assertNotNull(result.getCandidateFingerprint());
    }

    @Test
    void execute_timeout_returnsTimeoutResult() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        stubClient.throwTimeout = true;

        TestExecutionConfig config = TestExecutionConfig.defaults();
        RegressionTestResult result = executor.execute(baseline, "new prompt", config);

        assertEquals(TestResultStatus.TIMEOUT, result.getStatus());
        assertEquals("rec-1", result.getBaselineRecordId());
    }

    @Test
    void execute_apiError_returnsApiErrorResult() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        stubClient.throwApiError = true;

        TestExecutionConfig config = TestExecutionConfig.defaults();
        RegressionTestResult result = executor.execute(baseline, "new prompt", config);

        assertEquals(TestResultStatus.API_ERROR, result.getStatus());
        assertEquals("rec-1", result.getBaselineRecordId());
        assertTrue(result.getErrorMessage().contains("API error"));
    }

    @Test
    void execute_dryRun_returnsSkip() {
        InteractionRecord baseline = makeBaseline("hash", "input");

        TestExecutionConfig config = new TestExecutionConfig().dryRun(true);
        RegressionTestResult result = executor.execute(baseline, "new prompt", config);

        assertEquals(TestResultStatus.SKIP, result.getStatus());
        // 确认没调 LLM
        assertEquals(0, stubClient.callCount);
    }

    @Test
    void execute_passesCorrectTimeout() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        stubClient.response = makeTextResponse("hello");

        TestExecutionConfig config = new TestExecutionConfig().timeoutMs(5000);
        executor.execute(baseline, "prompt", config);

        assertEquals(5000, stubClient.lastTimeoutMs);
    }

    @Test
    void execute_fingerprintDiff_persistsCandidateForAdjudication() {
        SimpleTestRepo repo = new SimpleTestRepo();
        BaselineManager baselineManager = new BaselineManager(repo);
        RegressionTestExecutor wired = new RegressionTestExecutor(
                stubClient, new DeterministicComparator(), baselineManager);

        // 基线带工具调用，重放响应为纯文本 → 工具集维度必然差异（非 PASS）
        InteractionRecord baseline = makeBaselineWithToolCall("hash", "input");
        baselineManager.autoEstablishBaseline(baseline);
        String groupKey = DeterministicSkillGrouper.group(baseline).getGroupKey();
        stubClient.response = makeTextResponse("plain answer");

        wired.execute(baseline, "new prompt", TestExecutionConfig.defaults());

        // 候选必须经持久层落库，否则 approve 在另一进程不可达
        SkillProfile profile = repo.findSkillByGroupKey(groupKey);
        assertNotNull(profile);
        assertNotNull(profile.getCandidateFingerprint());
        assertEquals(BaselineStatus.CANDIDATE, profile.getBaselineStatus());
    }

    @Test
    void execute_fingerprintIdentical_noCandidatePersisted() {
        SimpleTestRepo repo = new SimpleTestRepo();
        BaselineManager baselineManager = new BaselineManager(repo);
        RegressionTestExecutor wired = new RegressionTestExecutor(
                stubClient, new DeterministicComparator(), baselineManager);

        // 基线与重放响应完全同形 → 指纹相同（PASS），无可裁决对象
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setModelResponse("same answer");
        baselineManager.autoEstablishBaseline(baseline);
        String groupKey = DeterministicSkillGrouper.group(baseline).getGroupKey();
        stubClient.response = makeTextResponse("same answer");

        RegressionTestResult result = wired.execute(baseline, "new prompt", TestExecutionConfig.defaults());

        assertEquals(Verdict.PASS, result.getComparison().getVerdict());
        assertNull(repo.findSkillByGroupKey(groupKey).getCandidateFingerprint());
        assertEquals(BaselineStatus.BASELINE, repo.findSkillByGroupKey(groupKey).getBaselineStatus());
    }

    @Test
    void execute_nullBaselineManager_skipsCandidatePersistence() {
        // baselineManager 传 null 的旧用法不受影响
        InteractionRecord baseline = makeBaselineWithToolCall("hash", "input");
        stubClient.response = makeTextResponse("plain answer");

        RegressionTestResult result = executor.execute(baseline, "new prompt", TestExecutionConfig.defaults());

        assertNotNull(result.getComparison());
    }

    private InteractionRecord makeBaseline(String promptHash, String userInput) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-1");
        r.setSkillId("skill-1");
        r.setTemplateHash(promptHash);
        r.setUserInput(userInput);
        r.setTurnIndex(0);
        r.setSessionId("session-1");
        r.setModelResponse("old response");
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        return r;
    }

    private InteractionRecord makeBaselineWithToolCall(String promptHash, String userInput) {
        InteractionRecord r = makeBaseline(promptHash, userInput);
        ToolCall tc = new ToolCall();
        tc.setToolName("queryOrder");
        tc.setToolCallId("tc-1");
        tc.setArguments(Map.of("orderId", "ORD-001"));
        tc.setSuccess(true);
        r.setToolCalls(List.of(tc));
        r.setHasToolCalls(true);
        return r;
    }

    private LlmResponse makeToolCallResponse(String toolName, String argValue) {
        LlmResponse response = new LlmResponse();
        response.setContent(null);

        ToolCallResult tc = new ToolCallResult();
        tc.setToolCallId("call-1");
        tc.setToolName(toolName);
        tc.setArguments(Map.of("orderId", argValue));
        response.setToolCalls(List.of(tc));
        response.setInputTokens(50);
        response.setOutputTokens(20);
        return response;
    }

    private LlmResponse makeTextResponse(String text) {
        LlmResponse response = new LlmResponse();
        response.setContent(text);
        response.setToolCalls(Collections.emptyList());
        response.setInputTokens(10);
        response.setOutputTokens(5);
        return response;
    }

    static class StubLlmClient implements LlmClient {
        LlmResponse response;
        boolean throwTimeout = false;
        boolean throwApiError = false;
        int callCount = 0;
        long lastTimeoutMs = 0;

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs)
                throws LlmTimeoutException, LlmApiException {
            callCount++;
            lastTimeoutMs = timeoutMs;
            if (throwTimeout) throw new LlmTimeoutException("timeout");
            if (throwApiError) throw new LlmApiException("API error");
            return response;
        }

        @Override
        public String name() {
            return "stub";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
