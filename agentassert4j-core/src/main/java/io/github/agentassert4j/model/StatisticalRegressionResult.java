package io.github.agentassert4j.model;

import io.github.agentassert4j.result.StatisticalVerdict;
import io.github.agentassert4j.result.Verdict;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 统计回归测试结果 — 对同一基线执行 N 次重放后的聚合结果。
 *
 * <p>核心统计指标：</p>
 * <ul>
 *   <li>各 Verdict 的次数和比率（PASS/CHANGED；TIMEOUT/API_ERROR 等基础设施样本 verdict 为 null）</li>
 *   <li>综合统计判定（STABLE/UNSTABLE/FLAKY）</li>
 *   <li>score 均值、标准差、最小值</li>
 *   <li>差异模式分布（哪类差异最频繁）</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class StatisticalRegressionResult {

    private String baselineRecordId;
    private String skillId;

    /**
     * 所有采样结果（不可变）
     */
    private List<SampleResult> samples = new ArrayList<>();

    /**
     * 实际采样次数
     */
    private int actualSampleCount;

    /**
     * 非判定样本数（verdict 为 null）：基础设施错误（超时/API 错误/执行错误/跳过）
     * 与预算占位样本都计入——占位未发起调用不是错误，但同属「未产出判定结论」的样本，
     * 均不参与判定比率
     */
    private int errorSampleCount;

    /**
     * 各 Verdict 的次数统计（不可变）
     */
    private Map<Verdict, Integer> verdictCounts = new LinkedHashMap<>();

    /**
     * 各 Verdict 的比率（不可变）
     */
    private Map<Verdict, Double> verdictRates = new LinkedHashMap<>();

    /**
     * 综合统计判定
     */
    private StatisticalVerdict statisticalVerdict;

    /**
     * score 均值
     */
    private double averageScore;

    /**
     * score 标准差
     */
    private double scoreStdDev;

    /**
     * score 最小值
     */
    private double minScore;

    /**
     * 差异模式摘要：最频繁出现的差异类型
     */
    private List<String> frequentDiffPatterns = new ArrayList<>();

    /**
     * 总耗时（毫秒）
     */
    private long totalLatencyMs;

    /**
     * 总 API 费用估算（美元）
     */
    private double estimatedCost;

    /**
     * true = 串行采样因连续 stallThreshold 轮同一失败差异早停，剩余轮次未发放；
     * actualSampleCount 为实际发放轮数（判定基于已发放样本，不影响退出码语义）
     */
    private boolean stalled;

    /**
     * 从采样结果列表聚合统计。
     *
     * @param baselineRecordId    基线记录 ID
     * @param skillId             Skill ID
     * @param samples             采样结果列表
     * @param passThreshold       PASS 一致率阈值
     * @param regressionTolerance CHANGED 比例上限（行为翻转容忍线）
     * @return 聚合统计结果
     */
    public static StatisticalRegressionResult aggregate(String baselineRecordId, String skillId, List<SampleResult> samples, double passThreshold, double regressionTolerance) {

        StatisticalRegressionResult result = new StatisticalRegressionResult();
        result.baselineRecordId = baselineRecordId;
        result.skillId = skillId;
        result.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        result.actualSampleCount = samples.size();

        // 空采样列表：无样本在数学上不可判定，绝不能默认稳定（CI 会静默放行）
        if (samples.isEmpty()) {
            result.verdictCounts = Collections.emptyMap();
            result.verdictRates = Collections.emptyMap();
            result.statisticalVerdict = StatisticalVerdict.INSUFFICIENT_SAMPLES;
            result.averageScore = 0;
            result.scoreStdDev = 0;
            result.minScore = 0;
            result.frequentDiffPatterns = Collections.emptyList();
            return result;
        }

        // 基础设施错误样本（verdict 为 null：超时/API 错误/执行错误/跳过）不参与
        // 判定比率——网络抖动不是行为回归，混入分母会污染 FLAKY 语义
        List<SampleResult> judged = new ArrayList<>();
        for (SampleResult s : samples) {
            if (s.getVerdict() != null) {
                judged.add(s);
            }
        }
        result.errorSampleCount = samples.size() - judged.size();
        if (judged.isEmpty()) {
            result.verdictCounts = Collections.emptyMap();
            result.verdictRates = Collections.emptyMap();
            result.statisticalVerdict = StatisticalVerdict.INSUFFICIENT_SAMPLES;
            result.averageScore = 0;
            result.scoreStdDev = 0;
            result.minScore = 0;
            result.frequentDiffPatterns = Collections.emptyList();
            return result;
        }

        // 1. 统计各 Verdict 次数（仅判定样本）
        Map<Verdict, Integer> counts = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) counts.put(v, 0);
        double scoreSum = 0;
        double scoreSumSq = 0;
        double min = Double.MAX_VALUE;
        for (SampleResult s : judged) {
            Verdict v = s.getVerdict();
            counts.merge(v, 1, Integer::sum);
            double sc = s.getScore();
            scoreSum += sc;
            scoreSumSq += sc * sc;
            if (sc < min) min = sc;
        }
        result.verdictCounts = Collections.unmodifiableMap(counts);
        result.minScore = min;

        // 2. 计算各 Verdict 比率（分母为判定样本数）
        int n = judged.size();
        Map<Verdict, Double> rates = new LinkedHashMap<>();
        for (Map.Entry<Verdict, Integer> e : counts.entrySet()) {
            rates.put(e.getKey(), (double) e.getValue() / n);
        }
        result.verdictRates = Collections.unmodifiableMap(rates);

        // 3. score 均值和标准差
        result.averageScore = scoreSum / n;
        result.scoreStdDev = n > 1 ? Math.sqrt(Math.max(0, (scoreSumSq - scoreSum * scoreSum / n) / (n - 1))) : 0;

        // 4. 统计判定（二值语义：CHANGED 占比超容忍线 = 行为本身在翻转）
        double passRate = rates.getOrDefault(Verdict.PASS, 0.0);
        double changedRate = rates.getOrDefault(Verdict.CHANGED, 0.0);

        if (changedRate > regressionTolerance) {
            result.statisticalVerdict = StatisticalVerdict.FLAKY;
        } else if (passRate >= passThreshold) {
            result.statisticalVerdict = StatisticalVerdict.STABLE;
        } else {
            result.statisticalVerdict = StatisticalVerdict.UNSTABLE;
        }

        // 5. 差异模式提取（非 PASS 采样中出现频次最高的差异摘要，top 5）
        Map<String, Integer> diffFreq = new LinkedHashMap<>();
        for (SampleResult s : samples) {
            if (s.getVerdict() != Verdict.PASS && s.getDiffSummary() != null) {
                diffFreq.merge(s.getDiffSummary(), 1, Integer::sum);
            }
        }
        result.frequentDiffPatterns = diffFreq.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5).map(Map.Entry::getKey).collect(Collectors.toList());

        return result;
    }

    public String getBaselineRecordId() {
        return baselineRecordId;
    }

    public void setBaselineRecordId(String id) {
        this.baselineRecordId = id;
    }

    public String getSkillId() {
        return skillId;
    }

    public void setSkillId(String skillId) {
        this.skillId = skillId;
    }

    public List<SampleResult> getSamples() {
        return samples;
    }

    public int getActualSampleCount() {
        return actualSampleCount;
    }

    public int getErrorSampleCount() {
        return errorSampleCount;
    }

    public void setActualSampleCount(int n) {
        this.actualSampleCount = n;
    }

    public Map<Verdict, Integer> getVerdictCounts() {
        return verdictCounts;
    }

    public Map<Verdict, Double> getVerdictRates() {
        return verdictRates;
    }

    public StatisticalVerdict getStatisticalVerdict() {
        return statisticalVerdict;
    }

    public double getAverageScore() {
        return averageScore;
    }

    public double getScoreStdDev() {
        return scoreStdDev;
    }

    public double getMinScore() {
        return minScore;
    }

    public List<String> getFrequentDiffPatterns() {
        return frequentDiffPatterns;
    }

    public long getTotalLatencyMs() {
        return totalLatencyMs;
    }

    public void setTotalLatencyMs(long ms) {
        this.totalLatencyMs = ms;
    }

    public double getEstimatedCost() {
        return estimatedCost;
    }

    public void setEstimatedCost(double cost) {
        this.estimatedCost = cost;
    }

    public boolean isStalled() {
        return stalled;
    }

    public void setStalled(boolean stalled) {
        this.stalled = stalled;
    }
}
