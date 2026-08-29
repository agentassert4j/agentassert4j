package io.github.agentassert4j.model;

/**
 * 统计回归测试配置 — 扩展基础执行配置，增加统计采样参数。
 *
 * <p>两种模式共存：</p>
 * <ul>
 *   <li>单次模式（默认）：sampleCount=1，行为与 RegressionTestExecutor 完全一致</li>
 *   <li>统计模式：sampleCount>1，对同一基线执行多次重放，聚合统计</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class StatisticalTestConfig {

    /**
     * 单次 LLM 调用超时（毫秒）
     */
    private long timeoutMs = 30000;

    /**
     * LLM 采样温度
     */
    private double temperature = 0.0;

    /**
     * 干跑模式
     */
    private boolean dryRun = false;

    /**
     * 覆盖默认模型
     */
    private String model;

    /**
     * 采样次数。默认 1（单次模式）。范围 [1, 100]
     */
    private int sampleCount = 1;

    /**
     * PASS 一致率阈值。默认 1.0（100%）。范围 (0.0, 1.0]
     */
    private double passThreshold = 1.0;

    /**
     * CHANGED 比例上限（行为翻转容忍线）：占比超过即判 FLAKY。默认 0.0。范围 [0.0, 1.0)
     */
    private double regressionTolerance = 0.0;

    /**
     * 并发度。默认 1（串行）。范围 [1, 10]
     */
    private int concurrency = 1;

    /**
     * 单个用例最大 API 费用上限（美元）。默认 1.0
     */
    private double maxCostPerCase = 1.0;

    /**
     * 整轮统计测试的 LLM 调用次数上限；0 = 不限（默认）
     */
    private int maxTotalCalls = 0;

    /**
     * 整轮统计测试的 token 消耗上限（输入+输出合计）；0 = 不限（默认）。
     * 串行模式逐次扣减，并发模式按批粒度扣减
     */
    private long maxTotalTokens = 0;

    public StatisticalTestConfig() {
    }

    public static StatisticalTestConfig defaults() {
        return new StatisticalTestConfig();
    }

    /**
     * 是否为统计模式
     */
    public boolean isStatisticalMode() {
        return sampleCount > 1;
    }

    /**
     * 钳位校验 — 确保所有参数在合法范围内。
     * 越界参数自动修正，不抛异常（退化不中断）。
     */
    public void validate() {
        timeoutMs = Math.max(1000, timeoutMs);
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        sampleCount = Math.max(1, Math.min(100, sampleCount));
        passThreshold = Math.max(0.01, Math.min(1.0, passThreshold));
        regressionTolerance = Math.max(0.0, Math.min(0.99, regressionTolerance));
        concurrency = Math.max(1, Math.min(10, concurrency));
        maxCostPerCase = Math.max(0.01, maxCostPerCase);
        // 预算上限：负数按 0（不限）处理，非钳到 1——单次调用本身不受预算约束
        maxTotalCalls = Math.max(0, maxTotalCalls);
        maxTotalTokens = Math.max(0, maxTotalTokens);
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getSampleCount() {
        return sampleCount;
    }

    public void setSampleCount(int sampleCount) {
        this.sampleCount = sampleCount;
    }

    public double getPassThreshold() {
        return passThreshold;
    }

    public void setPassThreshold(double passThreshold) {
        this.passThreshold = passThreshold;
    }

    public double getRegressionTolerance() {
        return regressionTolerance;
    }

    public void setRegressionTolerance(double regressionTolerance) {
        this.regressionTolerance = regressionTolerance;
    }

    public int getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(int concurrency) {
        this.concurrency = concurrency;
    }

    public double getMaxCostPerCase() {
        return maxCostPerCase;
    }

    public void setMaxCostPerCase(double maxCostPerCase) {
        this.maxCostPerCase = maxCostPerCase;
    }

    public int getMaxTotalCalls() {
        return maxTotalCalls;
    }

    public void setMaxTotalCalls(int maxTotalCalls) {
        this.maxTotalCalls = maxTotalCalls;
    }

    public long getMaxTotalTokens() {
        return maxTotalTokens;
    }

    public void setMaxTotalTokens(long maxTotalTokens) {
        this.maxTotalTokens = maxTotalTokens;
    }
}
