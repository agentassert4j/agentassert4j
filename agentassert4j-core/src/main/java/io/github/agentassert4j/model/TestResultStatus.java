package io.github.agentassert4j.model;

/**
 * 回归测试结果状态枚举。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public enum TestResultStatus {
    /**
     * LLM 调用与对比流程均成功（verdict 见对比结果）
     */
    SUCCESS,
    /**
     * LLM 调用超时
     */
    TIMEOUT,
    /**
     * LLM API 返回错误（认证失败/限流耗尽/非 2xx）
     */
    API_ERROR,
    /**
     * LLM 调用成功后的处理阶段（指纹提取/对比/候选落库）失败
     */
    ERROR,
    /**
     * 干跑跳过：未发起 LLM 调用
     */
    SKIP
}
