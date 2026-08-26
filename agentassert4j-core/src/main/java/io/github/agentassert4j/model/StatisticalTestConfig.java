package io.github.agentassert4j.model;

/**
 * 统计回归测试配置 — 扩展基础执行配置，增加统计采样参数。
 *
 * <p>两种模式共存：</p>
 * <ul>
 *   <li>单次模式（默认）：sampleCount=1，行为与 RegressionTestExecutor 完全一致</li>
 *   <li>统计模式：sampleCount>1，对同一基线执行多次重放，聚合统计</li>
 * </ul>
 */
public class StatisticalTestConfig {

    // ====== 基础配置（与 TestExecutionConfig 一致）======

    /** 单次测试用例上限 */
    private int maxTestCases = 50;

    /** 单次 LLM 调用超时（毫秒） */
    private long timeoutMs = 30000;

    /** API 失败重试次数 */
    private int maxRetries = 2;

    /** LLM 采样温度 */
    private double temperature = 0.0;

    /** 干跑模式 */
    private boolean dryRun = false;

    /** 覆盖默认模型 */
    private String model;

    // ====== 统计模式配置（新增）======

    /** 采样次数。默认 1（单次模式）。范围 [1, 100] */
    private int sampleCount = 1;

    /** PASS 一致率阈值。默认 1.0（100%）。范围 (0.0, 1.0] */
    private double passThreshold = 1.0;

    /** REGRESSION 比例上限。默认 0.0。范围 [0.0, 1.0) */
    private double regressionTolerance = 0.0;

    /** 并发度。默认 1（串行）。范围 [1, 10] */
    private int concurrency = 1;

    /** 单个用例最大 API 费用上限（美元）。默认 1.0 */
    private double maxCostPerCase = 1.0;

    public StatisticalTestConfig() {}

    public static StatisticalTestConfig defaults() {
        return new StatisticalTestConfig();
    }

    /** 是否为统计模式 */
    public boolean isStatisticalMode() {
        return sampleCount > 1;
    }

    /**
     * 钳位校验 — 确保所有参数在合法范围内。
     * 越界参数自动修正，不抛异常（退化不中断 R10）。
     */
    public void validate() {
        maxTestCases = Math.max(1, Math.min(200, maxTestCases));
        timeoutMs = Math.max(1000, timeoutMs);
        maxRetries = Math.max(0, Math.min(5, maxRetries));
        temperature = Math.max(0.0, Math.min(2.0, temperature));
        sampleCount = Math.max(1, Math.min(100, sampleCount));
        passThreshold = Math.max(0.01, Math.min(1.0, passThreshold));
        regressionTolerance = Math.max(0.0, Math.min(0.99, regressionTolerance));
        concurrency = Math.max(1, Math.min(10, concurrency));
        maxCostPerCase = Math.max(0.01, maxCostPerCase);
    }

    // ========== Getters & Setters ==========

    public int getMaxTestCases() { return maxTestCases; }
    public void setMaxTestCases(int maxTestCases) { this.maxTestCases = maxTestCases; }

    public long getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(long timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public boolean isDryRun() { return dryRun; }
    public void setDryRun(boolean dryRun) { this.dryRun = dryRun; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }

    public double getPassThreshold() { return passThreshold; }
    public void setPassThreshold(double passThreshold) { this.passThreshold = passThreshold; }

    public double getRegressionTolerance() { return regressionTolerance; }
    public void setRegressionTolerance(double regressionTolerance) { this.regressionTolerance = regressionTolerance; }

    public int getConcurrency() { return concurrency; }
    public void setConcurrency(int concurrency) { this.concurrency = concurrency; }

    public double getMaxCostPerCase() { return maxCostPerCase; }
    public void setMaxCostPerCase(double maxCostPerCase) { this.maxCostPerCase = maxCostPerCase; }
}
