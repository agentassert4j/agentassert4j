package io.github.agentassert4j.model;

/**
 * 回归测试结果状态枚举。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public enum TestResultStatus {
    SUCCESS,
    TIMEOUT,
    API_ERROR,
    /**
     * LLM 调用成功后的处理阶段（指纹提取/对比/候选落库）失败
     */
    ERROR,
    SKIP
}
