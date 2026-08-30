package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
        executor = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), null, null);
    }

    @Test
    void buildReplayRequest_replacesSystemPrompt() {
        InteractionRecord baseline = makeBaseline("old prompt hash", "user input");
        TestExecutionConfig config = TestExecutionConfig.defaults();

        LlmRequest request = executor.buildReplayRequest(baseline, "new prompt", null, config);

        assertEquals("new prompt", request.getSystemPrompt());
        assertEquals("user input", request.getUserInput());
    }

    @Test
    void buildReplayRequest_inputOverride_reachesRequest() {
        // 新输入覆盖末位 user 帧（多轮上下文与工具定义仍原样保留）
        InteractionRecord baseline = makeBaseline("old prompt hash", "user input");
        TestExecutionConfig config = TestExecutionConfig.defaults();

        LlmRequest request = executor.buildReplayRequest(baseline, "new prompt", "brand new input", config);

        assertEquals("brand new input", request.getUserInput());
    }

    @Test
    void buildReplayRequest_injectsPreviousTurns() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setTurnIndex(2);
        baseline.setPreviousTurns(Arrays.asList(new TurnContext("user", "q1"), new TurnContext("assistant", "a1"), new TurnContext("tool", "result1")));

        TestExecutionConfig config = TestExecutionConfig.defaults();
        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, config);

        assertNotNull(request.getPreviousTurns());
        assertEquals(3, request.getPreviousTurns().size());
        assertEquals("q1", request.getPreviousTurns().get(0).getContent());
        assertEquals("a1", request.getPreviousTurns().get(1).getContent());
    }

    @Test
    void buildReplayRequest_previousTurnsFieldLevelFidelity() {
        // 任务重放的生命线：tool 轮的关联三元组（toolCallId/toolName/arguments）
        // 必须逐字段保真——丢弃会被服务端 400 拒绝整个请求，变形则重建不出「当时输入」
        InteractionRecord baseline = makeBaseline("hash", "current input");
        TurnContext assistantCall = new TurnContext("assistant", "");
        assistantCall.setToolCallId("call-42");
        assistantCall.setToolName("getOrder");
        assistantCall.setToolArguments("{\"orderId\":\"ORD-001\"}");
        TurnContext toolResult = new TurnContext("tool", "result payload");
        toolResult.setToolCallId("call-42");
        baseline.setPreviousTurns(Arrays.asList(new TurnContext("user", "q1"), assistantCall, toolResult));

        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, TestExecutionConfig.defaults());

        assertEquals(3, request.getPreviousTurns().size());
        TurnContext injectedAssistant = request.getPreviousTurns().get(1);
        assertEquals("assistant", injectedAssistant.getRole());
        assertEquals("call-42", injectedAssistant.getToolCallId(), "toolCallId 是重放请求与原对话对齐的关联键");
        assertEquals("getOrder", injectedAssistant.getToolName());
        assertEquals("{\"orderId\":\"ORD-001\"}", injectedAssistant.getToolArguments());
        TurnContext injectedTool = request.getPreviousTurns().get(2);
        assertEquals("tool", injectedTool.getRole());
        assertEquals("call-42", injectedTool.getToolCallId(), "tool 帧必须携带同一关联键");
        assertEquals("result payload", injectedTool.getContent());
    }

    @Test
    void buildReplayRequest_skipsSystemTurns() {
        // 系统提示属模板域由 systemPrompt 承载：历史轮里的 system 帧注入会产生第二条 system 消息
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setPreviousTurns(Arrays.asList(new TurnContext("user", "q1"), new TurnContext("system", "old system prompt"), new TurnContext("assistant", "a1")));

        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, TestExecutionConfig.defaults());

        assertNotNull(request.getPreviousTurns());
        assertEquals(2, request.getPreviousTurns().size());
        assertEquals("user", request.getPreviousTurns().get(0).getRole());
        assertEquals("assistant", request.getPreviousTurns().get(1).getRole());
    }

    @Test
    void buildReplayRequest_setsModelFromConfig() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        TestExecutionConfig config = new TestExecutionConfig().model("deepseek-chat");

        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, config);

        assertEquals("deepseek-chat", request.getModel());
    }

    @Test
    void buildReplayRequest_noTurns_whenTurnIndexZero() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setTurnIndex(0);

        TestExecutionConfig config = TestExecutionConfig.defaults();
        LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, config);

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
        tc.setArguments(Collections.singletonMap("orderId", "ORD-001"));
        response.setToolCalls(Collections.singletonList(tc));

        InteractionRecord current = executor.buildCurrentRecord(baseline, response, "new prompt", null);

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

        InteractionRecord current = executor.buildCurrentRecord(baseline, response, "prompt", null);

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
        RegressionTestResult result = executor.execute(baseline, "new prompt", null, config);

        assertEquals("rec-1", result.getBaselineRecordId());
        assertEquals("skill-1", result.getInvocationId());
        assertEquals(TestResultStatus.SUCCESS, result.getStatus());
        assertNotNull(result.getComparison());
        assertNotNull(result.getCandidateFingerprint());
    }

    @Test
    void execute_timeout_returnsTimeoutResult() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        stubClient.throwTimeout = true;

        TestExecutionConfig config = TestExecutionConfig.defaults();
        RegressionTestResult result = executor.execute(baseline, "new prompt", null, config);

        assertEquals(TestResultStatus.TIMEOUT, result.getStatus());
        assertEquals("rec-1", result.getBaselineRecordId());
    }

    @Test
    void execute_apiError_returnsApiErrorResult() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        stubClient.throwApiError = true;

        TestExecutionConfig config = TestExecutionConfig.defaults();
        RegressionTestResult result = executor.execute(baseline, "new prompt", null, config);

        assertEquals(TestResultStatus.API_ERROR, result.getStatus());
        assertEquals("rec-1", result.getBaselineRecordId());
        assertTrue(result.getErrorMessage().contains("API error"));
    }

    @Test
    void execute_clientRuntimeException_returnsErrorResultNotEscaped() {
        // 客户端实现的编程错误（NPE/非法状态等）同样转单条 ERROR，不向批量调用方逃逸
        InteractionRecord baseline = makeBaseline("hash", "input");
        stubClient.throwRuntime = true;

        TestExecutionConfig config = TestExecutionConfig.defaults();
        RegressionTestResult result = executor.execute(baseline, "new prompt", null, config);

        assertEquals(TestResultStatus.ERROR, result.getStatus());
        assertEquals("rec-1", result.getBaselineRecordId());
        assertTrue(result.getErrorMessage().contains("IllegalStateException"), "错误消息必须携带异常类型: " + result.getErrorMessage());
    }

    @Test
    void execute_dryRun_returnsSkip() {
        InteractionRecord baseline = makeBaseline("hash", "input");

        TestExecutionConfig config = new TestExecutionConfig().dryRun(true);
        RegressionTestResult result = executor.execute(baseline, "new prompt", null, config);

        assertEquals(TestResultStatus.SKIP, result.getStatus());
        // 确认没调 LLM
        assertEquals(0, stubClient.callCount);
    }

    @Test
    void execute_passesCorrectTimeout() {
        InteractionRecord baseline = makeBaseline("hash", "input");
        stubClient.response = makeTextResponse("hello");

        TestExecutionConfig config = new TestExecutionConfig().timeoutMs(5000);
        executor.execute(baseline, "prompt", null, config);

        assertEquals(5000, stubClient.lastTimeoutMs);
    }

    @Test
    void execute_fingerprintDiff_persistsCandidateForAdjudication() {
        SimpleTestRepo repo = new SimpleTestRepo();
        BaselineManager baselineManager = new BaselineManager(repo);
        RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), baselineManager, null);

        // 基线带工具调用，重放响应为纯文本 → 工具集维度必然差异（非 PASS）
        InteractionRecord baseline = makeBaselineWithToolCall("hash", "input");
        baselineManager.autoEstablishBaseline(baseline, "tester", null);
        String invocationKey = InvocationResolver.resolve(baseline).getInvocationKey();
        stubClient.response = makeTextResponse("plain answer");

        wired.execute(baseline, "new prompt", null, TestExecutionConfig.defaults());

        // 候选必须经持久层落库，否则 approve 在另一进程不可达
        InvocationProfile profile = repo.findInvocationByKey(invocationKey);
        assertNotNull(profile);
        assertNotNull(profile.getCandidateFingerprint());
        assertEquals(BaselineStatus.CANDIDATE, profile.getBaselineStatus());
    }

    @Test
    void execute_fingerprintIdentical_noCandidatePersisted() {
        SimpleTestRepo repo = new SimpleTestRepo();
        BaselineManager baselineManager = new BaselineManager(repo);
        RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), baselineManager, null);

        // 基线与重放响应完全同形 → 指纹相同（PASS），无可裁决对象
        InteractionRecord baseline = makeBaseline("hash", "input");
        baseline.setModelResponse("same answer");
        baselineManager.autoEstablishBaseline(baseline, "tester", null);
        String invocationKey = InvocationResolver.resolve(baseline).getInvocationKey();
        stubClient.response = makeTextResponse("same answer");

        RegressionTestResult result = wired.execute(baseline, "new prompt", null, TestExecutionConfig.defaults());

        assertEquals(Verdict.PASS, result.getComparison().getVerdict());
        assertNull(repo.findInvocationByKey(invocationKey).getCandidateFingerprint());
        assertEquals(BaselineStatus.BASELINE, repo.findInvocationByKey(invocationKey).getBaselineStatus());
    }

    @Test
    void execute_nullBaselineManager_skipsCandidatePersistence() {
        // baselineManager 传 null 的旧用法不受影响
        InteractionRecord baseline = makeBaselineWithToolCall("hash", "input");
        stubClient.response = makeTextResponse("plain answer");

        RegressionTestResult result = executor.execute(baseline, "new prompt", null, TestExecutionConfig.defaults());

        assertNotNull(result.getComparison());
    }

    @Nested
    @DisplayName("重放上下文保真")
    class ReplayContext {

        @Test
        @DisplayName("工具定义随重放携带——不带工具模型无法发起调用，工具维必然假阳性回归")
        void buildReplayRequest_carriesToolDefinitions() {
            InteractionRecord baseline = makeBaseline("hash", "input");
            baseline.setToolsDefinition("[{\"type\":\"function\",\"function\":{\"name\":\"queryOrder\",\"parameters\":{}}}]");

            LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, TestExecutionConfig.defaults());

            assertNotNull(request.getToolDefinitions(), "重放请求必须携带录制的工具定义");
            assertEquals(1, request.getToolDefinitions().size());
            assertTrue(request.getToolDefinitions().get(0).contains("queryOrder"));
        }

        @Test
        @DisplayName("单个工具对象（非数组）也能装载")
        void buildReplayRequest_singleToolObject_wrapped() {
            InteractionRecord baseline = makeBaseline("hash", "input");
            baseline.setToolsDefinition("{\"type\":\"function\",\"function\":{\"name\":\"search\"}}");

            LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, TestExecutionConfig.defaults());

            assertEquals(1, request.getToolDefinitions().size());
            assertTrue(request.getToolDefinitions().get(0).contains("search"));
        }

        @Test
        @DisplayName("工具定义损坏（非法 JSON）时跳过，不中断重放")
        void buildReplayRequest_brokenToolsDefinition_skipped() {
            InteractionRecord baseline = makeBaseline("hash", "input");
            baseline.setToolsDefinition("not-json");

            LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, TestExecutionConfig.defaults());

            assertTrue(request.getToolDefinitions() == null || request.getToolDefinitions().isEmpty(), "损坏的工具定义不得进入重放请求");
        }

        @Test
        @DisplayName("tool 轮次的 toolCallId/toolName 完整复制到重放请求")
        void buildReplayRequest_preservesToolTurnIdentity() {
            InteractionRecord baseline = makeBaseline("hash", "input");
            baseline.setTurnIndex(1);
            TurnContext toolTurn = new TurnContext("tool", "result1");
            toolTurn.setToolCallId("call_abc");
            toolTurn.setToolName("queryOrder");
            baseline.setPreviousTurns(Collections.singletonList(toolTurn));

            LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, TestExecutionConfig.defaults());

            TurnContext copied = request.getPreviousTurns().get(0);
            assertEquals("call_abc", copied.getToolCallId(), "toolCallId 是 tool 消息与调用决策的关联键，重放不得丢弃");
            assertEquals("queryOrder", copied.getToolName());
        }

        @Test
        @DisplayName("轮次副本与基线隔离——修改副本不影响基线")
        void buildReplayRequest_turnCopiesAreIsolated() {
            InteractionRecord baseline = makeBaseline("hash", "input");
            baseline.setTurnIndex(1);
            baseline.setPreviousTurns(Collections.singletonList(new TurnContext("user", "q1")));

            LlmRequest request = executor.buildReplayRequest(baseline, "prompt", null, TestExecutionConfig.defaults());

            request.getPreviousTurns().get(0).setContent("mutated");
            assertEquals("q1", baseline.getPreviousTurns().get(0).getContent());
        }
    }

    @Nested
    @DisplayName("声明式规则接入重放")
    class RulesEnforcement {

        @Test
        @DisplayName("requiredKeywords 缺失 → keywordMatch=false 且判定非 PASS")
        void requiredKeywordMissing_failsComparison() {
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单\"]}}}");
            RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), null, rules);
            stubClient.response = makeTextResponse("回答里没有关键词");

            RegressionTestResult result = wired.execute(makeBaseline("hash", "input"), "prompt", null, TestExecutionConfig.defaults());

            assertFalse(result.getComparison().isKeywordMatch());
            assertNotEquals(Verdict.PASS, result.getComparison().getVerdict());
        }

        @Test
        @DisplayName("requiredKeywords 命中 → 判定不受影响")
        void requiredKeywordPresent_staysPass() {
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"requiredKeywords\":[\"订单\"]}}}");
            RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), null, rules);
            // 基线与重放输出完全同形（含关键词）→ PASS
            InteractionRecord baseline = makeBaseline("hash", "input");
            baseline.setModelResponse("订单已创建");
            stubClient.response = makeTextResponse("订单已创建");

            RegressionTestResult result = wired.execute(baseline, "prompt", null, TestExecutionConfig.defaults());

            assertTrue(result.getComparison().isKeywordMatch());
            assertEquals(Verdict.PASS, result.getComparison().getVerdict());
        }

        @Test
        @DisplayName("forbiddenKeywords 出现 → keywordMatch=false")
        void forbiddenKeywordPresent_failsComparison() {
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"forbiddenKeywords\":[\"密码\"]}}}");
            RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), null, rules);
            stubClient.response = makeTextResponse("请提供密码");

            RegressionTestResult result = wired.execute(makeBaseline("hash", "input"), "prompt", null, TestExecutionConfig.defaults());

            assertFalse(result.getComparison().isKeywordMatch());
        }

        @Test
        @DisplayName("behaviors 约束不满足 → behaviorMatch=false")
        void declaredBehaviorViolated_failsComparison() {
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"skill-1\":{\"behaviors\":[\"mustUseChinese\"]}}}");
            RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), null, rules);
            stubClient.response = makeTextResponse("english only answer");

            RegressionTestResult result = wired.execute(makeBaseline("hash", "input"), "prompt", null, TestExecutionConfig.defaults());

            assertFalse(result.getComparison().isBehaviorMatch());
        }

        @Test
        @DisplayName("规则声明给其他 skill → 本 skill 不受影响")
        void rulesForOtherSkill_notApplied() {
            InvocationRulesConfig rules = InvocationRulesConfig.fromJson("{\"invocations\":{\"other-skill\":{\"requiredKeywords\":[\"订单\"]}}}");
            RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), null, rules);
            InteractionRecord baseline = makeBaseline("hash", "input");
            baseline.setModelResponse("same answer");
            stubClient.response = makeTextResponse("same answer");

            RegressionTestResult result = wired.execute(baseline, "prompt", null, TestExecutionConfig.defaults());

            assertTrue(result.getComparison().isKeywordMatch());
            assertEquals(Verdict.PASS, result.getComparison().getVerdict());
        }
    }

    @Nested
    @DisplayName("后处理异常隔离")
    class ErrorIsolation {

        @Test
        @DisplayName("对比阶段抛 RuntimeException → 转为 ERROR 结果，不向调用方逃逸")
        void comparatorThrows_returnsErrorResult() {
            RegressionTestExecutor wired = new RegressionTestExecutor(stubClient, new ThrowingComparator(), null, null);
            stubClient.response = makeTextResponse("hello");

            RegressionTestResult result = wired.execute(makeBaseline("hash", "input"), "prompt", null, TestExecutionConfig.defaults());

            assertEquals(TestResultStatus.ERROR, result.getStatus());
            assertEquals("rec-1", result.getBaselineRecordId());
            assertNotNull(result.getErrorMessage());
        }

        @Test
        @DisplayName("LLM 调用失败仍走原异常映射（TIMEOUT），与 ERROR 互不混淆")
        void timeoutStillMappedBeforePostProcessing() {
            stubClient.throwTimeout = true;

            RegressionTestResult result = executor.execute(makeBaseline("hash", "input"), "prompt", null, TestExecutionConfig.defaults());

            assertEquals(TestResultStatus.TIMEOUT, result.getStatus());
        }
    }

    /**
     * 对比阶段必然抛异常的桩——验证处理失败的隔离边界。
     */
    static class ThrowingComparator extends DeterministicComparator {

        ThrowingComparator() {
            super(ComparatorConfig.defaults());
        }

        @Override
        public ComparisonResult compare(DeterministicFingerprint baseline, DeterministicFingerprint current, String currentOutput) {
            throw new IllegalStateException("boom from comparator");
        }
    }

    private InteractionRecord makeBaseline(String promptHash, String userInput) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-1");
        r.setInvocationId("skill-1");
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
        tc.setArguments(Collections.singletonMap("orderId", "ORD-001"));
        tc.setSuccess(true);
        r.setToolCalls(Collections.singletonList(tc));
        r.setHasToolCalls(true);
        return r;
    }

    private LlmResponse makeToolCallResponse(String toolName, String argValue) {
        LlmResponse response = new LlmResponse();
        response.setContent(null);

        ToolCallResult tc = new ToolCallResult();
        tc.setToolCallId("call-1");
        tc.setToolName(toolName);
        tc.setArguments(Collections.singletonMap("orderId", argValue));
        response.setToolCalls(Collections.singletonList(tc));
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

    @Nested
    @DisplayName("链式半重放（编排观察记录的专用重放契约）")
    class ChainedReplay {

        private LlmResponse toolDecision(String name, String orderId) {
            LlmResponse response = new LlmResponse();
            ToolCallResult call = new ToolCallResult();
            call.setToolName(name);
            call.setArguments(Collections.singletonMap("orderId", orderId));
            response.setToolCalls(Collections.singletonList(call));
            response.setInputTokens(10);
            response.setOutputTokens(5);
            return response;
        }

        private LlmResponse textDecision(String content) {
            LlmResponse response = new LlmResponse();
            response.setContent(content);
            response.setInputTokens(10);
            response.setOutputTokens(5);
            return response;
        }

        /**
         * 两步编排的基线：get_order → get_logistics，每轮结果齐备（编排观察形态）。
         */
        private InteractionRecord chainBaseline() {
            InteractionRecord r = new InteractionRecord();
            r.setRecordId("rec-chain");
            r.setInvocationId("skill-1");
            r.setSessionId("session-1");
            r.setTemplateHash("hash-old");
            r.setUserInput("查订单 SO-1");
            r.setTurnIndex(0);
            r.setModelResponse("您的订单已发货，请留意查收。");
            r.setHasToolCalls(true);
            r.setToolsDefinition("[{\"type\":\"function\",\"function\":{\"name\":\"get_order\"}}]");
            ToolCall a = new ToolCall();
            a.setToolName("get_order");
            a.setArguments(Collections.singletonMap("orderId", "SO-1"));
            a.setArgTypes(Collections.singletonMap("orderId", "string"));
            a.setResult("{\"status\":\"shipped\"}");
            a.setSuccess(true);
            ToolCall b = new ToolCall();
            b.setToolName("get_logistics");
            b.setArguments(Collections.singletonMap("orderId", "SO-1"));
            b.setArgTypes(Collections.singletonMap("orderId", "string"));
            b.setResult("运输中");
            b.setSuccess(true);
            r.setToolCalls(Arrays.asList(a, b));
            return r;
        }

        private RegressionTestExecutor chainedExecutor(LlmClient client) {
            return new RegressionTestExecutor(client, new DeterministicComparator(ComparatorConfig.defaults()), null, null);
        }

        @Test
        @DisplayName("全匹配端到端：逐轮决策一致 → 末轮四维比对 PASS，调用次数 = 编排轮数 + 1")
        void fullMatch_endToEnd_pass() {
            ScriptedClient client = new ScriptedClient();
            client.script.add(toolDecision("get_order", "SO-1"));
            client.script.add(toolDecision("get_logistics", "SO-1"));
            client.script.add(textDecision("您的订单已发货，请留意查收。"));

            RegressionTestResult result = chainedExecutor(client).execute(chainBaseline(), "new prompt", null, TestExecutionConfig.defaults());

            assertEquals(3, client.requests.size(), "两轮决策 + 末轮收口 = 3 次调用（调用次数闭合）");
            assertEquals(TestResultStatus.SUCCESS, result.getStatus());
            assertEquals(Verdict.PASS, result.getComparison().getVerdict());
            assertEquals(30, result.getInputTokens().intValue(), "token 遥测跨轮聚合");
        }

        @Test
        @DisplayName("分歧即停：第 2 轮决策偏离基线 → 恰好 2 次调用，定位到轮")
        void divergenceAtSecondRound_stopsImmediately() {
            ScriptedClient client = new ScriptedClient();
            client.script.add(toolDecision("get_order", "SO-1"));
            client.script.add(toolDecision("cancel_order", "SO-1"));
            client.script.add(textDecision("不该被用到"));

            RegressionTestResult result = chainedExecutor(client).execute(chainBaseline(), "new prompt", null, TestExecutionConfig.defaults());

            assertEquals(2, client.requests.size(), "分歧后不再发起调用");
            assertEquals(Verdict.CHANGED, result.getComparison().getVerdict());
            String summary = result.getComparison().getSummary();
            assertTrue(summary.contains("第 2 轮"), "定位到分歧轮次: " + summary);
            assertTrue(summary.contains("get_logistics") && summary.contains("cancel_order"), "摘要点名该轮基线决策与实际决策: " + summary);
        }

        @Test
        @DisplayName("逐轮上下文保真：第 2 轮请求携带基线旧结果的合成帧，且不重复提问")
        void contextFidelity_synthesizedFramesCarryBaselineResults() {
            ScriptedClient client = new ScriptedClient();
            client.script.add(toolDecision("get_order", "SO-1"));
            client.script.add(toolDecision("get_logistics", "SO-1"));
            client.script.add(textDecision("您的订单已发货，请留意查收。"));

            chainedExecutor(client).execute(chainBaseline(), "new prompt", null, TestExecutionConfig.defaults());

            LlmRequest first = client.requests.get(0);
            assertEquals("查订单 SO-1", first.getUserInput(), "第 1 轮携带用户输入");
            LlmRequest second = client.requests.get(1);
            assertNull(second.getUserInput(), "第 2 轮从工具结果续起，不重复提问");
            List<TurnContext> turns = second.getPreviousTurns();
            assertEquals(2, turns.size(), "第 2 轮上下文 = 合成的 assistant+tool 帧");
            TurnContext assistant = turns.get(0);
            TurnContext tool = turns.get(1);
            assertEquals("assistant", assistant.getRole());
            assertEquals("get_order", assistant.getToolName());
            assertEquals("tool", tool.getRole());
            assertEquals(assistant.getToolCallId(), tool.getToolCallId(), "assistant 与 tool 帧以合成 id 关联");
            assertEquals("{\"status\":\"shipped\"}", tool.getContent(), "结果帧携带基线录制内容（内容无损）");
            assertNotNull(assistant.getToolArguments(), "assistant 帧携带真实参数 JSON");
        }

        @Test
        @DisplayName("末轮多出工具调用 = 编排变化 → 分歧即停")
        void extraToolCallAtFinalRound_divergence() {
            ScriptedClient client = new ScriptedClient();
            client.script.add(toolDecision("get_order", "SO-1"));
            client.script.add(toolDecision("get_logistics", "SO-1"));
            client.script.add(toolDecision("refund", "SO-1"));

            RegressionTestResult result = chainedExecutor(client).execute(chainBaseline(), "new prompt", null, TestExecutionConfig.defaults());

            assertEquals(3, client.requests.size());
            assertEquals(Verdict.CHANGED, result.getComparison().getVerdict());
            assertTrue(result.getComparison().getSummary().contains("第 3 轮"), "末轮多出的编排定位到收口轮: " + result.getComparison().getSummary());
        }

        @Test
        @DisplayName("结果道具缺失（录制时工具失败）→ 退回单发重放")
        void missingResult_fallsBackToSingleShot() {
            InteractionRecord baseline = chainBaseline();
            baseline.getToolCalls().get(0).setResult(null);
            ScriptedClient client = new ScriptedClient();
            client.script.add(textDecision("您的订单已发货，请留意查收。"));

            RegressionTestResult result = chainedExecutor(client).execute(baseline, "new prompt", null, TestExecutionConfig.defaults());

            assertEquals(1, client.requests.size(), "单发重放只发一次");
            assertFalse(RegressionTestExecutor.isChainReplayable(baseline), "结果道具缺失不再是链式资格");
            assertEquals(TestResultStatus.SUCCESS, result.getStatus());
        }
    }

    /**
     * 可编程脚本桩：第 k 次调用返回 script[k]（越界时重复最后一个），并留存每次请求。
     */
    static class ScriptedClient implements LlmClient {
        final List<LlmResponse> script = new ArrayList<>();
        final List<LlmRequest> requests = new ArrayList<>();

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            requests.add(request);
            int i = Math.min(requests.size() - 1, script.size() - 1);
            return script.get(i);
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

    static class StubLlmClient implements LlmClient {
        LlmResponse response;
        boolean throwTimeout = false;
        boolean throwApiError = false;
        boolean throwRuntime = false;
        int callCount = 0;
        long lastTimeoutMs = 0;

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            callCount++;
            lastTimeoutMs = timeoutMs;
            if (throwTimeout) throw new LlmTimeoutException("timeout");
            if (throwApiError) throw new LlmApiException("API error");
            if (throwRuntime) throw new IllegalStateException("client bug");
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
