package io.github.agentassert4j.model;

/**
 * LLM wire 协议方言的封闭词表 — 单一值集同时约束配置键 llm.protocol、
 * record 摄取工具的 protocol 参数与交互记录的 apiProtocol 落库值，
 * 四处同形杜绝双真源。wireName 是冻结的线上值；新增协议 = 公开契约变更，
 * 须同步 spec 与测试钉。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public enum LlmWireProtocol {

    /**
     * OpenAI Chat Completions 兼容方言（含 DeepSeek/Qwen/vLLM 等 OpenAI 兼容端点）。
     * 内部范式记录与请求的 wire 表示恒为该形——其余协议只在客户端边界与摄取边界转换。
     */
    OPENAI_CHAT("openai-chat"),

    /**
     * Anthropic Messages 方言（Anthropic 官方端点及各厂商的 Messages 兼容端点）。
     */
    ANTHROPIC_MESSAGES("anthropic-messages"),

    /**
     * OpenAI Responses 方言。有状态成员（previous_response_id/store）不属于本框架
     * 的支持面——重放是无状态全量输入。
     */
    OPENAI_RESPONSES("openai-responses");

    private final String wireName;

    LlmWireProtocol(String wireName) {
        this.wireName = wireName;
    }

    /**
     * 线上值（配置键、工具参数、apiProtocol 列共用的冻结词形）。
     */
    public String wireName() {
        return wireName;
    }

    /**
     * 线上值解析；null/未知值返回 null，由调用方决定错误文案与处置——
     * 配置面（客户端构造）与摄取面（工具参数校验）的报错语境不同。
     */
    public static LlmWireProtocol fromWireName(String value) {
        if (value == null) {
            return null;
        }
        for (LlmWireProtocol protocol : values()) {
            if (protocol.wireName.equals(value)) {
                return protocol;
            }
        }
        return null;
    }

    /**
     * 全部合法线上值（逗号分隔）——未知值报错文案的统一取材处，避免各调用点手抄漏项。
     */
    public static String legalWireNames() {
        StringBuilder sb = new StringBuilder();
        for (LlmWireProtocol protocol : values()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(protocol.wireName);
        }
        return sb.toString();
    }
}
