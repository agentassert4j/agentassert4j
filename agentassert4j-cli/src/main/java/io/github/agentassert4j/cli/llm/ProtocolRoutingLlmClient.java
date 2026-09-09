package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.LlmWireProtocol;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;

/**
 * 协议路由客户端 — 重放面的单一 LlmClient 装配：每次调用按三级优先解析
 * wire 方言并分派到对应方言客户端。
 *
 * <p>解析优先级：显式配置的 {@code llm.protocol}（跨协议重放是显式意图）&gt;
 * 请求携带的记录方言提示（LlmRequest.wireProtocol，重放装配侧塞入基线记录的
 * apiProtocol——同协议原样重放零配置）&gt; openai-chat 兜底。提示值不在封闭
 * 词表内（历史脏数据）时同样兜底，不抛错——重放不被单条脏记录中断。</p>
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public final class ProtocolRoutingLlmClient implements LlmClient {

    private final String configuredProtocol;
    private final LlmClient openAiChat;
    private final LlmClient anthropicMessages;
    private final LlmClient openAiResponses;

    /**
     * @param configuredProtocol 显式配置的协议线上值（null = 未配置，走推导）
     * @param openAiChat         openai-chat 方言客户端（兜底路由目标）
     * @param anthropicMessages  anthropic-messages 方言客户端
     * @param openAiResponses    openai-responses 方言客户端
     */
    public ProtocolRoutingLlmClient(String configuredProtocol, LlmClient openAiChat, LlmClient anthropicMessages, LlmClient openAiResponses) {
        this.configuredProtocol = configuredProtocol;
        this.openAiChat = openAiChat;
        this.anthropicMessages = anthropicMessages;
        this.openAiResponses = openAiResponses;
    }

    @Override
    public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
        return route(request != null ? request.getWireProtocol() : null).chat(request, timeoutMs);
    }

    @Override
    public String name() {
        return route(null).name();
    }

    @Override
    public boolean isAvailable() {
        return route(null).isAvailable();
    }

    private LlmClient route(String recordProtocolHint) {
        // 显式配置优先；配置值不在词表内（防御直接构造本类的调用方——CLI 装配点
        // 已先行校验报错）时按未配置处理，走记录提示与兜底，不抛错
        LlmWireProtocol configured = LlmWireProtocol.fromWireName(configuredProtocol);
        String resolved;
        if (configured != null) {
            resolved = configured.wireName();
        } else {
            LlmWireProtocol hinted = LlmWireProtocol.fromWireName(recordProtocolHint);
            resolved = hinted != null ? hinted.wireName() : LlmWireProtocol.OPENAI_CHAT.wireName();
        }
        switch (LlmWireProtocol.fromWireName(resolved)) {
            case ANTHROPIC_MESSAGES:
                return anthropicMessages;
            case OPENAI_RESPONSES:
                return openAiResponses;
            default:
                return openAiChat;
        }
    }
}
