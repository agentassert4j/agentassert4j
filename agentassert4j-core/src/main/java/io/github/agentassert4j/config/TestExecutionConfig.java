package io.github.agentassert4j.config;

/**
 * 回归测试执行配置 — 控制 RegressionTestExecutor 的行为。
 *
 * <p>所有字段带安全默认值（退化不中断）。</p>
 *
 * <h3>配置项说明</h3>
 * <ul>
 *   <li>{@code maxTestCases} — 单次测试用例上限（防止意外大量调用）</li>
 *   <li>{@code timeoutMs} — 单次 LLM 调用超时（毫秒）</li>
 *   <li>{@code maxRetries} — API 失败重试次数</li>
 *   <li>{@code temperature} — LLM 采样温度（建议 0.0 确定性输出）</li>
 *   <li>{@code dryRun} — 干跑模式（不调 LLM，只输出会用哪些用例）</li>
 *   <li>{@code model} — 覆盖默认模型（null 时使用 LlmClient 配置的默认模型）</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class TestExecutionConfig {

    private int maxTestCases = 50;
    private long timeoutMs = 30000;
    private int maxRetries = 2;
    private double temperature = 0.0;
    private boolean dryRun = false;
    private String model;

    public TestExecutionConfig() {
    }

    /**
     * 返回带安全默认值的配置
     */
    public static TestExecutionConfig defaults() {
        return new TestExecutionConfig();
    }

    /**
     * 钳位校验 — 确保所有参数在合法范围内。
     * 越界参数自动修正到最近合法值，不抛异常（退化不中断）。
     */
    public void validate() {
        maxTestCases = Math.max(1, Math.min(200, maxTestCases));
        timeoutMs = Math.max(1000, timeoutMs);
        maxRetries = Math.max(0, Math.min(5, maxRetries));
        temperature = Math.max(0.0, Math.min(2.0, temperature));
    }

    public TestExecutionConfig maxTestCases(int maxTestCases) {
        this.maxTestCases = maxTestCases;
        return this;
    }

    public TestExecutionConfig timeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    public TestExecutionConfig maxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
        return this;
    }

    public TestExecutionConfig temperature(double temperature) {
        this.temperature = temperature;
        return this;
    }

    public TestExecutionConfig dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public TestExecutionConfig model(String model) {
        this.model = model;
        return this;
    }

    public int getMaxTestCases() {
        return maxTestCases;
    }

    public void setMaxTestCases(int maxTestCases) {
        this.maxTestCases = maxTestCases;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
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
}
