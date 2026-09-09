package io.github.agentassert4j.cli.llm;

/**
 * OpenAI Responses 方言的 wire 归一 — finish 语义派生。Responses 响应没有
 * finish_reason 字段，语义由 status 与 output 形态派生；wire 摄取（MCP record）
 * 与重放客户端共用同一派生器——同一方言不得有两套词表。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public final class OpenAiResponsesWireFormat {

    private OpenAiResponsesWireFormat() {
    }

    /**
     * finish 派生（内部固定枚举）：output 含 function_call item → tool_calls
     * （无论 status——截断在工具调用上的响应按工具调用语义归类）；status=completed
     * → stop；incomplete 且 reason=max_output_tokens → max_tokens；incomplete
     * 其他 reason → content_filter；其余（failed 等未完成态）→ other。
     */
    public static String deriveFinishReason(String status, String incompleteReason, boolean outputHasFunctionCall) {
        if (outputHasFunctionCall) {
            return "tool_calls";
        }
        if ("completed".equals(status)) {
            return "stop";
        }
        if ("incomplete".equals(status)) {
            return "max_output_tokens".equals(incompleteReason) ? "max_tokens" : "content_filter";
        }
        return "other";
    }
}
