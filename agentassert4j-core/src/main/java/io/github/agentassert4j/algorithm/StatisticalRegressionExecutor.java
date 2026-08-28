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
 * <p>设计原则：</p>
 * <ul>
 *   <li>单次等价：sampleCount=1 时行为与 RegressionTestExecutor 完全等价</li>
 *   <li>成本安全：maxCostPerCase 硬限制，超出自动截断采样</li>
 *   <li>退化不中断：任何一次 LLM 调用失败不影响其余采样</li>
 * </ul>
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
     */
    public StatisticalRegressionExecutor(LlmClient llmClient, DeterministicComparator comparator) {
        this(llmClient, comparator, null);
    }

    /**
     * 构造器（带声明式规则）。
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
     * @param config          统计测试配置
     * @return 聚合统计结果
     */
    public StatisticalRegressionResult execute(InteractionRecord baseline, String newSystemPrompt, StatisticalTestConfig config) {

        config.validate();

        // 单次模式：直接委托给 RegressionTestExecutor
        if (!config.isStatisticalMode()) {
            return executeSingleAsStatistical(baseline, newSystemPrompt, config);
        }

        // ====== 统计模式 ======

        // 成本预估
        double costPerCall = CostEstimator.estimateCostPerCall(llmClient.name());
        int maxSamplesByCost = (int) Math.floor(config.getMaxCostPerCase() / costPerCall);
        int effectiveSampleCount = Math.min(config.getSampleCount(), maxSamplesByCost);

        if (effectiveSampleCount <= 0) {
            // 成本限制太严格，无法执行任何采样
            return StatisticalRegressionResult.aggregate(baseline.getRecordId(), baseline.getSkillId(), Collections.emptyList(), config.getPassThreshold(), config.getRegressionTolerance());
        }

        // 执行 N 次采样
        List<SampleResult> samples;
        long totalStart = System.currentTimeMillis();

        if (config.getConcurrency() > 1) {
            samples = executeConcurrent(baseline, newSystemPrompt, config, effectiveSampleCount);
        } else {
            samples = new ArrayList<>(effectiveSampleCount);
            for (int i = 0; i < effectiveSampleCount; i++) {
                samples.add(executeOneSample(baseline, newSystemPrompt, i + 1, config));
            }
        }

        long totalLatency = System.currentTimeMillis() - totalStart;

        // 聚合统计
        StatisticalRegressionResult result = StatisticalRegressionResult.aggregate(baseline.getRecordId(), baseline.getSkillId(), samples, config.getPassThreshold(), config.getRegressionTolerance());
        result.setTotalLatencyMs(totalLatency);
        result.setEstimatedCost(samples.size() * costPerCall);

        return result;
    }

    /**
     * 执行单次采样 — 复用 RegressionTestExecutor 的核心逻辑。
     *
     * <p>退化不中断：任何异常都转换为 SampleResult，不向外抛出。</p>
     */
    SampleResult executeOneSample(InteractionRecord baseline, String newSystemPrompt, int sampleIndex, StatisticalTestConfig config) {

        long start = System.currentTimeMillis();

        try {
            RegressionTestResult single = singleExecutor.execute(baseline, newSystemPrompt, toTestExecutionConfig(config));

            long latency = System.currentTimeMillis() - start;

            if (single.getStatus() == TestResultStatus.TIMEOUT) {
                SampleResult sr = new SampleResult();
                sr.setSampleIndex(sampleIndex);
                sr.setScore(0.0);
                sr.setErrorMessage("LLM timeout");
                sr.setLatencyMs(latency);
                return sr;
            }
            if (single.getStatus() == TestResultStatus.API_ERROR) {
                SampleResult sr = new SampleResult();
                sr.setSampleIndex(sampleIndex);
                sr.setScore(0.0);
                sr.setErrorMessage("API error: " + single.getErrorMessage());
                sr.setLatencyMs(latency);
                return sr;
            }
            if (single.getStatus() == TestResultStatus.ERROR) {
                SampleResult sr = new SampleResult();
                sr.setSampleIndex(sampleIndex);
                sr.setScore(0.0);
                sr.setErrorMessage("Processing error: " + single.getErrorMessage());
                sr.setLatencyMs(latency);
                return sr;
            }
            if (single.getStatus() == TestResultStatus.SKIP) {
                SampleResult sr = new SampleResult();
                sr.setSampleIndex(sampleIndex);
                sr.setScore(0.0);
                sr.setErrorMessage("Dry run skip");
                sr.setLatencyMs(latency);
                return sr;
            }

            return new SampleResult(sampleIndex, single.getComparison().getVerdict(), single.getComparison().getScore(), single.getComparison().getVerdict() != Verdict.PASS ? single.getComparison().getSummary() : null, latency);

        } catch (Exception e) {
            // 防御性：任何意外异常都不中断其余采样
            long latency = System.currentTimeMillis() - start;
            SampleResult sr = new SampleResult();
            sr.setSampleIndex(sampleIndex);
            sr.setScore(0.0);
            sr.setErrorMessage("Unexpected: " + e.getMessage());
            sr.setLatencyMs(latency);
            return sr;
        }
    }

    /**
     * 并发执行采样 — 分批提交，避免 API 速率限制。
     *
     * <p>使用裸 Thread + join，零额外依赖。</p>
     */
    private List<SampleResult> executeConcurrent(InteractionRecord baseline, String newSystemPrompt, StatisticalTestConfig config, int totalCount) {

        int batchSize = config.getConcurrency();
        List<SampleResult> allSamples = new ArrayList<>(totalCount);

        for (int batchStart = 0; batchStart < totalCount; batchStart += batchSize) {
            int batchEnd = Math.min(batchStart + batchSize, totalCount);
            int batchCount = batchEnd - batchStart;

            SampleResult[] batchResults = new SampleResult[batchCount];
            Thread[] threads = new Thread[batchCount];

            for (int i = 0; i < batchCount; i++) {
                final int sampleIndex = batchStart + i + 1;
                final int resultIndex = i;
                threads[i] = new Thread(() -> {
                    batchResults[resultIndex] = executeOneSample(baseline, newSystemPrompt, sampleIndex, config);
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
                if (batchResults[i] != null) {
                    allSamples.add(batchResults[i]);
                } else {
                    SampleResult lost = new SampleResult();
                    lost.setSampleIndex(batchStart + i + 1);
                    lost.setScore(0.0);
                    lost.setErrorMessage("采样线程未在超时预算内返回");
                    allSamples.add(lost);
                }
            }
        }

        return allSamples;
    }

    /**
     * 单次模式包装 — 与 RegressionTestExecutor 单次执行等价。
     */
    private StatisticalRegressionResult executeSingleAsStatistical(InteractionRecord baseline, String newSystemPrompt, StatisticalTestConfig config) {

        SampleResult sample = executeOneSample(baseline, newSystemPrompt, 1, config);

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
