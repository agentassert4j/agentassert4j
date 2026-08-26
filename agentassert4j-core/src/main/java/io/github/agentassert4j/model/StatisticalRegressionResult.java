package io.github.agentassert4j.model;

import io.github.agentassert4j.result.StatisticalVerdict;
import io.github.agentassert4j.result.Verdict;

import java.util.*;

/**
 * 统计回归测试结果 — 对同一基线执行 N 次重放后的聚合结果。
 *
 * <p>核心统计指标：</p>
 * <ul>
 *   <li>各 Verdict 的次数和比率（PASS/DIFF/REGRESSION/TIMEOUT/API_ERROR）</li>
 *   <li>综合统计判定（STABLE/UNSTABLE/FLAKY）</li>
 *   <li>score 均值、标准差、最小值</li>
 *   <li>差异模式分布（哪类差异最频繁）</li>
 * </ul>
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
     * 从采样结果列表聚合统计。
     *
     * @param baselineRecordId    基线记录 ID
     * @param skillId             Skill ID
     * @param samples             采样结果列表
     * @param passThreshold       PASS 一致率阈值
     * @param regressionTolerance REGRESSION 比例上限
     * @return 聚合统计结果
     */
    public static StatisticalRegressionResult aggregate(
            String baselineRecordId, String skillId,
            List<SampleResult> samples,
            double passThreshold, double regressionTolerance) {

        StatisticalRegressionResult result = new StatisticalRegressionResult();
        result.baselineRecordId = baselineRecordId;
        result.skillId = skillId;
        result.samples = Collections.unmodifiableList(new ArrayList<>(samples));
        result.actualSampleCount = samples.size();

        // 空采样列表
        if (samples.isEmpty()) {
            result.verdictCounts = Collections.emptyMap();
            result.verdictRates = Collections.emptyMap();
            result.statisticalVerdict = StatisticalVerdict.STABLE;
            result.averageScore = 0;
            result.scoreStdDev = 0;
            result.minScore = 0;
            result.frequentDiffPatterns = Collections.emptyList();
            return result;
        }

        // 1. 统计各 Verdict 次数
        Map<Verdict, Integer> counts = new LinkedHashMap<>();
        for (Verdict v : Verdict.values()) counts.put(v, 0);
        double scoreSum = 0;
        double scoreSumSq = 0;
        double min = Double.MAX_VALUE;
        for (SampleResult s : samples) {
            Verdict v = s.getVerdict() != null ? s.getVerdict() : Verdict.REGRESSION;
            counts.merge(v, 1, Integer::sum);
            double sc = s.getScore();
            scoreSum += sc;
            scoreSumSq += sc * sc;
            if (sc < min) min = sc;
        }
        result.verdictCounts = Collections.unmodifiableMap(counts);
        result.minScore = min;

        // 2. 计算各 Verdict 比率
        int n = samples.size();
        Map<Verdict, Double> rates = new LinkedHashMap<>();
        for (Map.Entry<Verdict, Integer> e : counts.entrySet()) {
            rates.put(e.getKey(), (double) e.getValue() / n);
        }
        result.verdictRates = Collections.unmodifiableMap(rates);

        // 3. score 均值和标准差
        result.averageScore = scoreSum / n;
        result.scoreStdDev = n > 1
                ? Math.sqrt(Math.max(0, (scoreSumSq - scoreSum * scoreSum / n) / (n - 1)))
                : 0;

        // 4. 统计判定
        double passRate = rates.getOrDefault(Verdict.PASS, 0.0);
        double regressionRate = rates.getOrDefault(Verdict.REGRESSION, 0.0);

        if (regressionRate > regressionTolerance) {
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
        result.frequentDiffPatterns = diffFreq.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(5)
                .map(Map.Entry::getKey)
                .toList();

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
}
