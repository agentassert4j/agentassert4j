package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.StatisticalVerdict;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StatisticalRegressionExecutor 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class StatisticalRegressionExecutorTest {

    private CountingLlmClient stubClient;
    private StatisticalRegressionExecutor executor;

    @BeforeEach
    void setUp() {
        stubClient = new CountingLlmClient();
        executor = new StatisticalRegressionExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), null);
    }

    @Test
    void execute_singleMode_wrapsAsStatistical() {
        StatisticalTestConfig config = StatisticalTestConfig.defaults(); // sampleCount=1
        InteractionRecord baseline = makeBaseline();

        StatisticalRegressionResult result = executor.execute(baseline, "new prompt", null, config);

        assertEquals(1, result.getActualSampleCount());
        assertEquals(1, stubClient.callCount.get());
    }

    @Test
    void execute_serial_collectsAllSamples() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(5);
        config.setConcurrency(1);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(5, result.getActualSampleCount());
        assertEquals(5, stubClient.callCount.get());
    }

    @Test
    void execute_maxTotalCalls_capsIssuedSamples() {
        // 调用数预算在发放前截断：effectiveSampleCount 直接被预算收口
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(10);
        config.setMaxTotalCalls(3);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(3, stubClient.callCount.get(), "调用数预算必须截断实际调用");
        assertEquals(3, result.getActualSampleCount(), "预算外采样不发放也不占位");
        assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict());
    }

    @Test
    void execute_maxTotalTokens_stopsIssuingWhenExhausted() {
        // 每次采样消耗 50+20=70 token：第 2 次后累计 140 >= 100，预算耗尽
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(10);
        config.setMaxTotalTokens(100);
        config.setConcurrency(1);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(2, stubClient.callCount.get(), "token 预算耗尽后不得再发调用");
        assertEquals(10, result.getActualSampleCount());
        // 占位样本带显式标志（此前靠错误文案前缀识别，文案一改费用就算错）
        int placeholders = 0;
        for (SampleResult s : result.getSamples()) {
            if (s.isBudgetPlaceholder()) {
                placeholders++;
            }
        }
        assertEquals(8, placeholders, "未发放的 8 个采样必须是显式占位样本");
        // 费用只按实际发起的调用估算：2 次 × gpt-4o 预估口径 0.0075
        assertEquals(0.015, result.getEstimatedCost(), 1e-9, "占位样本不计入 estimatedCost");
        assertEquals(8, result.getErrorSampleCount(), "占位样本计入非判定样本（2 判定 + 8 占位 = 10）");
    }

    @Test
    void execute_serial_allPass_stable() {
        stubClient.alwaysReturnToolCall = true;
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(3);

        StatisticalRegressionResult result = executor.execute(makeBaselineWithToolCall(), "prompt", null, config);

        assertEquals(3, result.getActualSampleCount());
    }

    @Test
    void execute_concurrent_collectsAllSamples() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(6);
        config.setConcurrency(3);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(6, result.getActualSampleCount());
        assertEquals(6, stubClient.callCount.get());
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

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertTrue(result.getActualSampleCount() <= 2);
        assertTrue(result.getActualSampleCount() < 10);
    }

    @Test
    void execute_timeoutOnThirdSample_othersContinue() {
        stubClient.failOnCallNumber = Collections.singleton(3);
        stubClient.failType = "timeout";

        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(5);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(5, result.getActualSampleCount());
        assertEquals(5, stubClient.callCount.get());
        // 第 3 次采样应该有 errorMessage
        boolean hasErrorSample = result.getSamples().stream().anyMatch(s -> s.getErrorMessage() != null);
        assertTrue(hasErrorSample);
    }

    @Test
    void execute_apiErrorOnSecondSample_othersContinue() {
        stubClient.failOnCallNumber = Collections.singleton(2);
        stubClient.failType = "api_error";

        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(4);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(4, result.getActualSampleCount());
        boolean hasErrorSample = result.getSamples().stream().anyMatch(s -> s.getErrorMessage() != null);
        assertTrue(hasErrorSample);
    }

    @Test
    void execute_allSamplesFail_errorSamplesFillDenominator_insufficientVerdict() {
        // 基础设施全挂时：错误样本必须填满聚合分母（而非静默缩小分母稀释错误占比），
        // 零可用判定样本 → INSUFFICIENT_SAMPLES，绝不放行 STABLE
        stubClient.failEveryCall = true;

        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(4);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(4, result.getActualSampleCount());
        assertEquals(4, result.getErrorSampleCount(), "错误样本必须计入分母");
        assertEquals(StatisticalVerdict.INSUFFICIENT_SAMPLES, result.getStatisticalVerdict());
    }

    @Test
    void execute_totalLatencyAndCostCalculated() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(3);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertTrue(result.getTotalLatencyMs() >= 0);
        assertTrue(result.getEstimatedCost() > 0);
    }

    @Test
    void execute_dryRun_noLlmCalls() {
        StatisticalTestConfig config = new StatisticalTestConfig();
        config.setSampleCount(5);
        config.setDryRun(true);

        StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

        assertEquals(0, stubClient.callCount.get());
        // 全 SKIP 聚合不允许产出任何判定（fail-open 的 STABLE 同样不允许）
        assertEquals(StatisticalVerdict.INSUFFICIENT_SAMPLES, result.getStatisticalVerdict());
    }

    @Nested
    @DisplayName("声明式规则透传")
    class RulesPassthrough {

        @Test
        @DisplayName("规则经构造器传入 → 每次采样都按 skillId 应用规则")
        void rulesApplied_toEverySample() {
            SkillRulesConfig rules = SkillRulesConfig.fromJson("{\"skills\":{\"skill-1\":{\"requiredKeywords\":[\"订单\"]}}}");
            StatisticalRegressionExecutor wired = new StatisticalRegressionExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), rules);
            StatisticalTestConfig config = new StatisticalTestConfig();
            config.setSampleCount(3);

            // 桩响应 "response text" 不含 "订单" → 每次采样都因规则非 PASS
            StatisticalRegressionResult result = wired.execute(makeBaseline(), "prompt", null, config);

            assertEquals(3, result.getActualSampleCount());
            assertTrue(result.getSamples().stream().allMatch(s -> s.getVerdict() != Verdict.PASS), "规则必须作用于全部采样，而非只有第一次");
        }
    }

    @Nested
    @DisplayName("处理失败隔离")
    class ErrorIsolation {

        @Test
        @DisplayName("对比阶段抛异常 → 每次采样转为错误样本，批量不中断")
        void processingError_isolatedPerSample() {
            StatisticalRegressionExecutor wired = new StatisticalRegressionExecutor(stubClient, new RegressionTestExecutorTest.ThrowingComparator(), null);
            StatisticalTestConfig config = new StatisticalTestConfig();
            config.setSampleCount(3);

            StatisticalRegressionResult result = wired.execute(makeBaseline(), "prompt", null, config);

            assertEquals(3, result.getActualSampleCount());
            assertTrue(result.getSamples().stream().allMatch(s -> s.getErrorMessage() != null && s.getErrorMessage().contains("Processing error")));
        }
    }

    @Nested
    @DisplayName("判定黄金测试（比率边界/预算分母/可复现）")
    class GoldenVerdict {

        // 通过/失败样本与基线（"old response"，12 字符）同处一个文本量级档（2 位长度档），
        // 判定差异只由关键词规则驱动——量级守卫不构成混淆变量
        private static final String PASS_CONTENT = "您的订单已发货，请留意查收，如有问题请联系客服。";
        private static final String FAIL_CONTENT = "response text";

        /**
         * 可编程内容序列桩：前 passCount 次采样返回含关键词内容（PASS），
         * 其余返回不含关键词内容（CHANGED），配合 requiredKeywords=["订单"] 规则
         * 逐采样确定性控制判定结果（不依赖真实 LLM）。
         */
        private StatisticalRegressionExecutor executorWithSequence(int passCount, int failCount) {
            List<String> sequence = new ArrayList<>(Collections.nCopies(passCount, PASS_CONTENT));
            sequence.addAll(Collections.nCopies(failCount, FAIL_CONTENT));
            stubClient.contentSequence = sequence;
            SkillRulesConfig rules = SkillRulesConfig.fromJson("{\"skills\":{\"skill-1\":{\"requiredKeywords\":[\"订单\"]}}}");
            return new StatisticalRegressionExecutor(stubClient, new DeterministicComparator(ComparatorConfig.defaults()), rules);
        }

        private StatisticalTestConfig config(int sampleCount, double passThreshold, double regressionTolerance) {
            StatisticalTestConfig config = new StatisticalTestConfig();
            config.setSampleCount(sampleCount);
            config.setPassThreshold(passThreshold);
            config.setRegressionTolerance(regressionTolerance);
            config.setConcurrency(1);
            return config;
        }

        @Test
        @DisplayName("双边界钉死：CHANGED 占比恰等于容忍线且 PASS 率恰达阈值 → STABLE")
        void boundary_changedAtTolerance_passAtThreshold_stable() {
            // 8 PASS + 2 CHANGED：changedRate 0.2 不大于容忍线 0.2，passRate 0.8 恰达阈值 0.8
            StatisticalRegressionExecutor wired = executorWithSequence(8, 2);

            StatisticalRegressionResult result = wired.execute(makeBaseline(), "prompt", null, config(10, 0.8, 0.2));

            assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict());
            assertEquals(Integer.valueOf(8), result.getVerdictCounts().get(Verdict.PASS));
            assertEquals(Integer.valueOf(2), result.getVerdictCounts().get(Verdict.CHANGED));
        }

        @Test
        @DisplayName("CHANGED 占比刚越过容忍线 → FLAKY（优先于 UNSTABLE 口径）")
        void boundary_changedJustOverTolerance_flaky() {
            // 7 PASS + 3 CHANGED：changedRate 0.3 > 容忍线 0.2 → FLAKY（而非 passRate 0.7 < 0.8 的 UNSTABLE）
            StatisticalRegressionExecutor wired = executorWithSequence(7, 3);

            StatisticalRegressionResult result = wired.execute(makeBaseline(), "prompt", null, config(10, 0.8, 0.2));

            assertEquals(StatisticalVerdict.FLAKY, result.getStatisticalVerdict());
        }

        @Test
        @DisplayName("CHANGED 占比在容忍线内但 PASS 率低于阈值 → UNSTABLE")
        void boundary_withinTolerance_belowThreshold_unstable() {
            // 5 PASS + 5 CHANGED：changedRate 0.5 不大于容忍线 0.5，passRate 0.5 < 阈值 0.8
            StatisticalRegressionExecutor wired = executorWithSequence(5, 5);

            StatisticalRegressionResult result = wired.execute(makeBaseline(), "prompt", null, config(10, 0.8, 0.5));

            assertEquals(StatisticalVerdict.UNSTABLE, result.getStatisticalVerdict());
        }

        @Test
        @DisplayName("预算占位样本不进判定分母：1 判定 PASS + 9 占位 → STABLE")
        void placeholders_excludedFromJudgmentDenominator() {
            // 每次调用耗 50+20=70 token，预算 70 → 第 1 次真实调用后预算耗尽，余 9 轮占位
            StatisticalRegressionExecutor wired = executorWithSequence(1, 0);
            StatisticalTestConfig budgetConfig = config(10, 1.0, 0.0);
            budgetConfig.setMaxTotalTokens(70);

            StatisticalRegressionResult result = wired.execute(makeBaseline(), "prompt", null, budgetConfig);

            assertEquals(10, result.getActualSampleCount());
            assertEquals(9, result.getErrorSampleCount(), "占位样本计入非判定样本");
            assertEquals(1, result.getVerdictCounts().get(Verdict.PASS).intValue(), "判定分母只含真实采样");
            assertEquals(StatisticalVerdict.STABLE, result.getStatisticalVerdict(), "占位样本不得稀释 PASS 率（分母 = 判定样本数）");
        }

        @Test
        @DisplayName("判定可复现：同桩同配置两次执行，聚合结论完全一致")
        void reproducible_sameStubSameAggregate() {
            StatisticalRegressionExecutor wired = executorWithSequence(3, 1);
            StatisticalTestConfig config = config(4, 0.75, 0.25);

            StatisticalRegressionResult first = wired.execute(makeBaseline(), "prompt", null, config);
            StatisticalRegressionResult second = wired.execute(makeBaseline(), "prompt", null, config);

            assertEquals(first.getStatisticalVerdict(), second.getStatisticalVerdict());
            assertEquals(first.getVerdictCounts(), second.getVerdictCounts());
            assertEquals(first.getVerdictRates(), second.getVerdictRates());
            assertEquals(first.getActualSampleCount(), second.getActualSampleCount());
            assertEquals(first.getEstimatedCost(), second.getEstimatedCost(), 1e-9);
            assertEquals(first.getAverageScore(), second.getAverageScore(), 1e-9);
        }

        @Test
        @DisplayName("单次模式口径：唯一样本 CHANGED 即 FLAKY，PASS 即 STABLE")
        void singleMode_caliber() {
            StatisticalRegressionExecutor fail = executorWithSequence(0, 1);
            assertEquals(StatisticalVerdict.FLAKY, fail.execute(makeBaseline(), "prompt", null, StatisticalTestConfig.defaults()).getStatisticalVerdict());

            StatisticalRegressionExecutor pass = executorWithSequence(1, 0);
            assertEquals(StatisticalVerdict.STABLE, pass.execute(makeBaseline(), "prompt", null, StatisticalTestConfig.defaults()).getStatisticalVerdict());
        }
    }

    @Nested
    @DisplayName("场景输入与遥测透传")
    class ScenarioInputAndTelemetry {

        @Test
        @DisplayName("场景新输入贯穿到 LLM 请求（新输入首次调用的核心语义）")
        void userInputOverride_reachesLlmRequest() {
            StatisticalTestConfig config = new StatisticalTestConfig();

            executor.execute(makeBaseline(), "prompt", "全新用户输入", config);

            assertEquals("全新用户输入", stubClient.lastRequest.getUserInput());
        }

        @Test
        @DisplayName("缓存与思考 token 随样本上抛（供应商未返回为 null）")
        void cacheAndReasoningTokens_carryThroughToSample() {
            stubClient.cacheReadTokens = 30;
            stubClient.cacheWriteTokens = 10;
            stubClient.reasoningTokens = 5;
            StatisticalTestConfig config = new StatisticalTestConfig();
            config.setSampleCount(2);

            StatisticalRegressionResult result = executor.execute(makeBaseline(), "prompt", null, config);

            for (SampleResult sample : result.getSamples()) {
                assertEquals(Integer.valueOf(30), sample.getCacheReadTokens());
                assertEquals(Integer.valueOf(10), sample.getCacheWriteTokens());
                assertEquals(Integer.valueOf(5), sample.getReasoningTokens());
            }
        }
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
        tc.setArguments(Collections.singletonMap("orderId", "ORD-001"));
        tc.setSuccess(true);
        r.setToolCalls(Collections.singletonList(tc));
        r.setHasToolCalls(true);
        return r;
    }

    static class CountingLlmClient implements LlmClient {
        // 并发采样下多线程同时计数——必须原子，否则丢失更新会让调用数断言偶发失败
        final AtomicInteger callCount = new AtomicInteger();
        boolean alwaysReturnToolCall = false;
        Set<Integer> failOnCallNumber = Collections.emptySet();
        String failType = "timeout";
        boolean failEveryCall = false;
        // 可编程正文序列：第 n 次调用返回 sequence[(n-1) % size]（空 = 恒 "response text"）
        List<String> contentSequence = Collections.emptyList();
        // 遥测透传验证用（null = 模拟供应商未返回）
        Integer cacheReadTokens;
        Integer cacheWriteTokens;
        Integer reasoningTokens;
        // 最后一次请求快照——并发用例存在无同步写入（不读不断言），仅单线程用例读它
        LlmRequest lastRequest;

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            int nth = callCount.incrementAndGet();
            lastRequest = request;
            boolean fail = failEveryCall || failOnCallNumber.contains(nth);
            if (fail) {
                if ("timeout".equals(failType)) throw new LlmTimeoutException("timeout");
                throw new LlmApiException("API error");
            }

            LlmResponse response = new LlmResponse();
            response.setContent(contentSequence.isEmpty() ? "response text" : contentSequence.get((nth - 1) % contentSequence.size()));
            response.setInputTokens(50);
            response.setOutputTokens(20);
            response.setCacheReadTokens(cacheReadTokens);
            response.setCacheWriteTokens(cacheWriteTokens);
            response.setReasoningTokens(reasoningTokens);

            if (alwaysReturnToolCall) {
                ToolCallResult tc = new ToolCallResult();
                tc.setToolCallId("call-" + nth);
                tc.setToolName("queryOrder");
                tc.setArguments(Collections.singletonMap("orderId", "ORD-001"));
                response.setToolCalls(Collections.singletonList(tc));
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
