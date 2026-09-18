package io.github.agentassert4j.langchain4j;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.RecordingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * LangChain4j ChatModel 录制装饰器 — 旁路捕获真实调用，业务透传零干预。
 *
 * <p>用法：用本类包住真实 ChatModel（或注册为 Bean 由 starter 自动包装），所有
 * AiServices/直调流量即进入旁路录制管道：</p>
 * <pre>{@code
 * ChatModel real = OpenAiChatModel.builder()...build();
 * ChatModel recorded = RecordingChatModel.wrap(real, interactionRecorder);
 * }</pre>
 *
 * <p>拦截点钉在 doChat 单点：chat(ChatRequest) 与带选项的 chat(ChatRequest,
 * ChatRequestOptions) 等全部入口在 LangChain4j 各版本都经默认模板方法汇聚到
 * this.doChat——单点覆写即覆盖所有路径，且只在编译地板已存在的符号上落笔。
 * doChat 内委托 delegate.chat 而非 delegate.doChat：被包模型把工作放在 chat()
 * 的非规范实现也照常工作，且内模型自带的监听器恰好触发一次（本装饰器的
 * listeners() 返回空清单，正是为了避免装饰层模板再触发一遍造成双计）。</p>
 *
 * <p>粒度说明：LangChain4j 的工具回路编排发生在 ChatModel 之外（AiServices 层），
 * 每个 LLM 轮次各自成记录——发起帧轮的响应携带 toolCalls，工具结果出现在下一轮
 * 请求历史的 tool 角色轮次中。delegate 同时实现 StreamingChatModel 时流式入口
 * 同样录制（handler 经动态代理聚合转发）；否则流式调用显式抛出
 * UnsupportedOperationException，与非流式模型的既有语义同形。</p>
 *
 * <p>录制失败只记 WARN 不抛出——框架任何故障不影响业务调用。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
public final class RecordingChatModel implements ChatModel, StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(RecordingChatModel.class);

    private final ChatModel delegate;
    private final RecordingInterceptor recorder;

    private RecordingChatModel(ChatModel delegate, RecordingInterceptor recorder) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate ChatModel must not be null");
        }
        if (recorder == null) {
            throw new IllegalArgumentException("recorder must not be null");
        }
        this.delegate = delegate;
        this.recorder = recorder;
    }

    /**
     * 包住真实 ChatModel；recorder 通常为 {@code io.github.agentassert4j.recorder.InteractionRecorder}。
     */
    public static RecordingChatModel wrap(ChatModel delegate, RecordingInterceptor recorder) {
        return new RecordingChatModel(delegate, recorder);
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
        long start = System.currentTimeMillis();
        // 会话标注必须在调用线程捕获：流式聚合回调发生在异步完成线程，
        // 那里取不到业务线程的 ThreadLocal
        RecordingContext context = RecordingContext.currentOrNull();
        ChatResponse response = delegate.chat(chatRequest);
        recordQuietly(chatRequest, response, System.currentTimeMillis() - start, null, context);
        return response;
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        StreamingChatModel streamingDelegate = streamingDelegateOrThrow();
        long start = System.currentTimeMillis();
        RecordingContext context = RecordingContext.currentOrNull();
        streamingDelegate.chat(chatRequest, StreamingHandlerProxy.wrap(handler, (response, ttftMs, latencyMs) -> recordQuietly(chatRequest, response, latencyMs, ttftMs, context)));
    }

    private StreamingChatModel streamingDelegateOrThrow() {
        if (delegate instanceof StreamingChatModel) {
            return (StreamingChatModel) delegate;
        }
        throw new UnsupportedOperationException("wrapped ChatModel does not implement StreamingChatModel");
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        // 空清单是双触发防线：录制委托走 delegate.chat，内模型模板触发其自带
        // 监听器恰一次；此处若透传，装饰层模板会再触发一遍。LangChain4j 没有
        // 运行时加监听器的入口（build 时烤入内模型），空清单不损失任何功能
        return Collections.emptyList();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private void recordQuietly(ChatRequest request, ChatResponse response, long latencyMs, Long ttftMs, RecordingContext context) {
        try {
            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, response, latencyMs, ttftMs, context);
            recorder.intercept(record);
        } catch (Exception e) {
            log.warn("Side-channel recording failed (business call unaffected): {}", e.getMessage());
        }
    }
}
