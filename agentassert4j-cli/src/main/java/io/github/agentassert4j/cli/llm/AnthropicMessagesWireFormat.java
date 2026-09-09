package io.github.agentassert4j.cli.llm;

import java.util.Map;

/**
 * Anthropic Messages 方言的 wire 归一 — finish_reason 词表映射、usage 口径求和、
 * image 块源拆解。wire 摄取（MCP record）与重放客户端共用同一归一器——
 * 同一方言不得有两套词表。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public final class AnthropicMessagesWireFormat {

    private AnthropicMessagesWireFormat() {
    }

    /**
     * stop_reason 归一为内部固定枚举：end_turn、stop_sequence → stop；
     * tool_use → tool_calls；max_tokens → max_tokens；refusal → content_filter；
     * 未知值归 other（内部词表加值零迁移）。
     */
    public static String normalizeFinishReason(String stopReason) {
        if (stopReason == null || stopReason.isEmpty()) return null;
        switch (stopReason) {
            case "end_turn":
            case "stop_sequence":
                return "stop";
            case "tool_use":
                return "tool_calls";
            case "max_tokens":
                return "max_tokens";
            case "refusal":
                return "content_filter";
            default:
                return "other";
        }
    }

    /**
     * input_tokens 总量口径：Anthropic 的 input_tokens 是非缓存口径，总量 =
     * input_tokens + cache_creation_input_tokens + cache_read_input_tokens
     * （缺项按 0 计，null 容器按空）。
     */
    public static int totalInputTokens(Map<?, ?> usage) {
        return intOrZero(usage, "input_tokens") + intOrZero(usage, "cache_creation_input_tokens") + intOrZero(usage, "cache_read_input_tokens");
    }

    private static int intOrZero(Map<?, ?> usage, String key) {
        Object value = usage != null ? usage.get(key) : null;
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /**
     * image 块的 source（{type:"base64",media_type,data}）拆解为范式 data-URI
     * （data:{media_type};base64,{data}）；形态不符返回 null，由调用方丢弃并告警
     * （宁缺勿非法——不可转换的 part 不进请求）。
     */
    public static String imageDataUri(Object source) {
        if (!(source instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) source;
        Object mediaType = map.get("media_type");
        Object data = map.get("data");
        if (!(mediaType instanceof String) || !(data instanceof String)) {
            return null;
        }
        return "data:" + mediaType + ";base64," + data;
    }
}
