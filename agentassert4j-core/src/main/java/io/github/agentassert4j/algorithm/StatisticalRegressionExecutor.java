package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 统计回归测试执行器 — 对同一基线执行 N 次 LLM 重放，聚合统计结果。
 *
 * <p>sampleCount=1 时与 {@link RegressionTestExecutor} 完全等价；
 * maxCostPerCase/maxTotalTokens/maxTotalCalls 三重预算硬限制，超出自动截断采样；
 * 任何一次 LLM 调用失败不中断其余采样。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class StatisticalRegressionExecutor {

    private final LlmClient llmClient;
    private final RegressionTestExecutor singleExecutor;

    /**
     * 构造器。
     *
     * @param llmClient  LLM 客户端
     * @param comparator 确定性对比器
     * @param rules      声明式规则配置，传入后每次采样都按 skillId 注入维度 3-4 规则（null 跳过）
     */
    public StatisticalRegressionExecutor(LlmClient llmClient, DeterministicComparator comparator, SkillRulesConfig rules) {
        this.llmClient = llmClient;
        this.singleExecutor = new RegressionTestExecutor(llmClient, comparator, null, rules);
    }

    /**
     * 执行统计回归测试。
     *
     * @param baseline        历史交互基线
     * @param newSystemPrompt 新 System Prompt
     * @param userInput       每次采样的用户输入；null = 原样复用基线记录的历史输入（重放语义），
     *                        场景层传入新输入实现「新输入的首次调用」
     * @param config          统计测试配置
     * @return 聚合统计结果
     */
    public StatisticalRegressionResult execute(InteractionRecord baseline, String newSystemPrompt, String userInput, StatisticalTestConfig config) {

        config.validate();

        // 单次模式：直接委托给 RegressionTestExecutor
        if (!config.isStatisticalMode()) {
            return executeSingleAsStatistical(baseline, newSystemPrompt, userInput, config);
        }

        // ====== 统计模式 ======

        // 成本预估
        double costPerCall = CostEstimator.estimateCostPerCall(llmClient.name());
        int maxSamplesByCost = (int) Math.floor(config.getMaxCostPerCase() / costPerCall);
        int effectiveSampleCount = Math.min(config.getSampleCount(), maxSamplesByCost);
        // 调用数预算在发放前截断（确定性）；token 预算需按实际消耗在采样间扣减
        if (config.getMaxTotalCalls() > 0) {
            effectiveSampleCount = Math.min(effectiveSampleCount, config.getMaxTotalCalls());
        }

        if (effectiveSampleCount <= 0) {
            // 成本限制太严格，无法执行任何采样
            return StatisticalRegressionResult.aggregate(baseline.getRecordId(), baseline.getSkillId(), Collections.emptyList(), config.getPassThreshold(), config.getRegressionTolerance());
        }

        // 执行 N 次采样
        List<SampleResult> samples;
        long totalStart = System.currentTimeMillis();

        if (config.getConcurrency() > 1) {
            samples = executeConcurrent(baseline, newSystemPrompt, userInput, config, effectiveSampleCount);
        } else {
            samples = new ArrayList<>(effectiveSampleCount);
            long consumedTokens = 0;
            for (int i = 0; i < effectiveSampleCount; i++) {
                if (tokenBudgetExhausted(config, consumedTokens)) {
                    // 预算耗尽后不再发调用，占位样本保持分母口径完整
                    samples.add(budgetSample(i + 1, config));
                    continue;
                }
                SampleResult sample = executeOneSample(baseline, newSystemPrompt, userInput, i + 1, config);
                consumedTokens += tokensOf(sample);
                samples.add(sample);
            }
        }

        long totalLatency = System.currentTimeMillis() - totalStart;

        // 聚合统计
        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate(baseline.getRecordId(), baseline.getSkillId(), samples, config.getPassThreshold(), config.getRegressionTolerance());
        result.setTotalLatencyMs(totalLatency);
        // 费用按实际发起的调用数估算：预算占位样本未发调用，不计入
        int issuedCalls = samples.size() - countBudgetSamples(samples);
        result.setEstimatedCost(issuedCalls * costPerCall);

        return result;
    }

    private static int countBudgetSamples(List<SampleResult> samples) {
        int count = 0;
        for (SampleResult sample : samples) {
            if (sample.isBudgetPlaceholder()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 执行单次采样 — 复用 RegressionTestExecutor 的核心逻辑。
     *
     * <p>退化不中断：任何异常都转换为 SampleResult，不向外抛出。</p>
     */
    SampleResult executeOneSample(InteractionRecord baseline, String newSystemPrompt, String userInput, int sampleIndex, StatisticalTestConfig config) {

        long start = System.currentTimeMillis();

        try {
            RegressionTestResult single = singleExecutor.execute(baseline, newSystemPrompt, userInput, toTestExecutionConfig(config));

            long latency = System.currentTimeMillis() - start;

            if (single.getStatus() == TestResultStatus.TIMEOUT) {
                return errorSample(sampleIndex, latency, "LLM timeout");
            }
            if (single.getStatus() == TestResultStatus.API_ERROR) {
                return errorSample(sampleIndex, latency, "API error: " + single.getErrorMessage());
            }
            if (single.getStatus() == TestResultStatus.ERROR) {
                return errorSample(sampleIndex, latency, "Processing error: " + single.getErrorMessage());
            }
            if (single.getStatus() == TestResultStatus.SKIP) {
                return errorSample(sampleIndex, latency, "Dry run skip");
            }

            SampleResult judged = new SampleResult(sampleIndex, single.getComparison().getVerdict(), single.getComparison().getScore(), single.getComparison().getVerdict() != Verdict.PASS ? single.getComparison().getSummary() : null, latency);
            // token 消耗随样本上抛，供整轮 token 预算扣减与场景层遥测聚合；
            // 缓存/思考 token 可空（供应商未返回保持 null）
            judged.setInputTokens(single.getInputTokens());
            judged.setOutputTokens(single.getOutputTokens());
            judged.setCacheReadTokens(single.getCacheReadTokens());
            judged.setCacheWriteTokens(single.getCacheWriteTokens());
            judged.setReasoningTokens(single.getReasoningTokens());
            return judged;

        } catch (Exception e) {
            // 防御性：任何意外异常都不中断其余采样
            return errorSample(sampleIndex, System.currentTimeMillis() - start, "Unexpected: " + e.getMessage());
        }
    }

    /**
     * 并发执行采样 — 分批提交，避免 API 速率限制。
     *
     * <p>使用裸 Thread + join，零额外依赖。</p>
     */
    private List<SampleResult> executeConcurrent(InteractionRecord baseline, String newSystemPrompt, String userInput, StatisticalTestConfig config, int totalCount) {

        int batchSize = config.getConcurrency();
        List<SampleResult> allSamples = new ArrayList<>(totalCount);
        long consumedTokens = 0;

        for (int batchStart = 0; batchStart < totalCount; batchStart += batchSize) {
            if (tokenBudgetExhausted(config, consumedTokens)) {
                // 预算耗尽：剩余槽位不再发调用，占位样本补齐分母口径
                //（并发模式按批粒度扣减，无法逐次精确截停）
                for (int i = batchStart; i < totalCount; i++) {
                    allSamples.add(budgetSample(i + 1, config));
                }
                break;
            }
            int batchEnd = Math.min(batchStart + batchSize, totalCount);
            int batchCount = batchEnd - batchStart;

            SampleResult[] batchResults = new SampleResult[batchCount];
            Thread[] threads = new Thread[batchCount];

            for (int i = 0; i < batchCount; i++) {
                final int sampleIndex = batchStart + i + 1;
                final int resultIndex = i;
                threads[i] = new Thread(() -> {
                    batchResults[resultIndex] = executeOneSample(baseline, newSystemPrompt, userInput, sampleIndex, config);
                });
                threads[i].setName("agentassert4j-statistical-" + sampleIndex);
                threads[i].setDaemon(true);
                threads[i].start();
            }

            // 等待当前批次完成
            for (Thread t : threads) {
                try {
                    t.join(config.getTimeoutMs() + 5000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            // 收集结果：null 槽位 = 线程超时或中断未产出，计为错误样本——
            // 聚合分母必须反映实际发出的每一次采样，静默缩小会稀释错误占比
            for (int i = 0; i < batchCount; i++) {
                SampleResult collected;
                if (batchResults[i] != null) {
                    collected = batchResults[i];
                } else {
                    collected = new SampleResult();
                    collected.setSampleIndex(batchStart + i + 1);
                    collected.setScore(0.0);
                    collected.setErrorMessage("采样线程未在超时预算内返回");
                }
                consumedTokens += tokensOf(collected);
                allSamples.add(collected);
            }
        }

        return allSamples;
    }

    private static boolean tokenBudgetExhausted(StatisticalTestConfig config, long consumedTokens) {
        return config.getMaxTotalTokens() > 0 && consumedTokens >= config.getMaxTotalTokens();
    }

    private static long tokensOf(SampleResult sample) {
        // 只计输入+输出：缓存读 token 是输入 token 的已命中子集，纳入会重复扣减预算
        return (sample.getInputTokens() != null ? sample.getInputTokens() : 0) + (sample.getOutputTokens() != null ? sample.getOutputTokens() : 0);
    }

    /**
     * 非判定样本（超时/API 错误/执行错误/跳过/意外异常）的统一形态：
     * verdict 为空——聚合侧据此剔除出判定分母，错误原因随样本上抛。
     */
    private static SampleResult errorSample(int sampleIndex, long latencyMs, String message) {
        SampleResult sr = new SampleResult();
        sr.setSampleIndex(sampleIndex);
        sr.setScore(0.0);
        sr.setErrorMessage(message);
        sr.setLatencyMs(latencyMs);
        return sr;
    }

    /**
     * 预算耗尽后的占位样本：verdict 为空（不计入判定分母），错误消息写明预算原因。
     */
    private static SampleResult budgetSample(int sampleIndex, StatisticalTestConfig config) {
        SampleResult sr = new SampleResult();
        sr.setSampleIndex(sampleIndex);
        sr.setScore(0.0);
        sr.setErrorMessage("Token 预算已耗尽（maxTotalTokens=" + config.getMaxTotalTokens() + "），未发起调用");
        sr.setBudgetPlaceholder(true);
        return sr;
    }

    /**
     * 单次模式包装 — 与 RegressionTestExecutor 单次执行等价。
     */
    private StatisticalRegressionResult executeSingleAsStatistical(InteractionRecord baseline, String newSystemPrompt, String userInput, StatisticalTestConfig config) {

        SampleResult sample = executeOneSample(baseline, newSystemPrompt, userInput, 1, config);

        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate(baseline.getRecordId(), baseline.getSkillId(), Collections.singletonList(sample), 1.0, 0.0);

        result.setTotalLatencyMs(sample.getLatencyMs());
        return result;
    }

    /**
     * StatisticalTestConfig → TestExecutionConfig 转换。
     */
    private TestExecutionConfig toTestExecutionConfig(StatisticalTestConfig config) {
        return new TestExecutionConfig().timeoutMs(config.getTimeoutMs()).temperature(config.getTemperature()).dryRun(config.isDryRun()).model(config.getModel());
    }
}
