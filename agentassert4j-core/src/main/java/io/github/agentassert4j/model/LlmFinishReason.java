package io.github.agentassert4j.model;

/**
 * 结束原因规范词表 — interactions.finish_reason 列的唯一取值源。各 wire 方言
 * （openai-chat / anthropic-messages / openai-responses）与 Spring AI 框架枚举
 * 各自的映射函数负责归一到本词表，同一规范值不得在消费方手写第二份。
 *
 * @author axy-yxa
 * @since 2026-09-10
 */
public enum LlmFinishReason {

    /**
     * 正常完成
     */
    STOP("stop"),
    /**
     * 模型发起工具调用
     */
    TOOL_CALLS("tool_calls"),
    /**
     * 长度/输出上限截断
     */
    MAX_TOKENS("max_tokens"),
    /**
     * 内容过滤拦截
     */
    CONTENT_FILTER("content_filter"),
    /**
     * 无法归入上列的其余终止形态
     */
    OTHER("other");

    private final String wireName;

    LlmFinishReason(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 线上存储值（finish_reason 列的合法取值）
     */
    public String wireName() {
        return wireName;
    }
}
