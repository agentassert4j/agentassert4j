package io.github.agentassert4j.config;

/**
 * 回归测试执行配置 — 控制 RegressionTestExecutor 的行为。
 *
 * <p>所有字段带安全默认值（退化不中断）。</p>
 *
 * <h3>配置项说明</h3>
 * <ul>
 *   <li>{@code timeoutMs} — 单次 LLM 调用超时（毫秒）</li>
 *   <li>{@code temperature} — LLM 采样温度（建议 0.0 确定性输出）</li>
 *   <li>{@code maxTokens} — 发射请求的 max_tokens 兜底上限（null 时客户端按内置默认兜底）</li>
 *   <li>{@code dryRun} — 干跑模式（不调 LLM，只输出会用哪些用例）</li>
 *   <li>{@code model} — 覆盖默认模型（null 时使用 LlmClient 配置的默认模型）</li>
 * </ul>
 *
 * <p>重试次数是 LlmClient 实现的传输层策略，由客户端构造时指定，
 * 不属于单次执行配置；测试用例数量上限由批量调度方（CLI）控制。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class TestExecutionConfig {

    private long timeoutMs = 30000;
    /**
     * null 表示不发送该参数——部分推理模型（OpenAI o 系）只接受默认采样温度，
     * 显式发送 0.0 会被服务端 400 拒绝
     */
    private Double temperature = 0.0;
    /**
     * 发射请求的 max_tokens 兜底上限。Anthropic Messages 文法必填、基线记录未携带
     * 时按此值填充；null = 客户端内置默认（4096）。OpenAI 系文法可选、不发送
     */
    private Integer maxTokens;
    private boolean dryRun = false;
    private String model;
    /**
     * 发射端点与显式配置的 wire 协议（llm.endpoint / llm.protocol 的执行侧投影，
     * 仅诊断披露用——路由真源在 ProtocolRoutingLlmClient）
     */
    private String endpoint;
    private String wireProtocol;

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
     * 非 finite 的 temperature（NaN/Infinity）没有「最近合法值」，置为 null（请求省略该成员）。
     */
    public void validate() {
        timeoutMs = Math.max(1000, timeoutMs);
        if (temperature != null && !Double.isFinite(temperature)) {
            temperature = null;
        } else if (temperature != null) {
            temperature = Math.max(0.0, Math.min(2.0, temperature));
        }
        // 非正数 max_tokens 是配置笔误（API 必 400）——置回 null 走客户端内置兜底，
        // 而不是把必然失败的请求发给端点
        if (maxTokens != null && maxTokens < 1) {
            maxTokens = null;
        }
    }

    public TestExecutionConfig timeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
        return this;
    }

    public TestExecutionConfig temperature(Double temperature) {
        this.temperature = temperature;
        return this;
    }

    public TestExecutionConfig maxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
        return this;
    }

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public TestExecutionConfig dryRun(boolean dryRun) {
        this.dryRun = dryRun;
        return this;
    }

    public TestExecutionConfig model(String model) {
        this.model = model;
        return this;
    }

    public long getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Double getTemperature() {
        return temperature;
    }

    public void setTemperature(Double temperature) {
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

    public TestExecutionConfig endpoint(String endpoint) {
        this.endpoint = endpoint;
        return this;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public TestExecutionConfig wireProtocol(String wireProtocol) {
        this.wireProtocol = wireProtocol;
        return this;
    }

    public String getWireProtocol() {
        return wireProtocol;
    }
}
