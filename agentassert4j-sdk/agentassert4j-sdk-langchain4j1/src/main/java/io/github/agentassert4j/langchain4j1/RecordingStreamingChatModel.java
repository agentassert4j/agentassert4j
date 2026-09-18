package io.github.agentassert4j.langchain4j1;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
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
 * LangChain4j StreamingChatModel 录制装饰器 — 仅实现流式接口的模型的专用包装。
 *
 * <p>LangChain4j 主流模型类把阻塞与流式拆成两个独立类（如 OpenAiStreamingChatModel
 * 只实现 StreamingChatModel），此类承接这类 Bean；同时实现两个接口的混合模型由
 * {@link RecordingChatModel} 一次收全（其流式入口在 delegate 实现流式接口时照常
 * 录制）。拦截点与防双触发策略同 RecordingChatModel：钉在 doChat 单点、doChat 内
 * 委托 delegate.chat、listeners() 返回空清单。</p>
 *
 * <p>录制失败只记 WARN 不抛出——框架任何故障不影响业务调用。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
public final class RecordingStreamingChatModel implements StreamingChatModel {

    private static final Logger log = LoggerFactory.getLogger(RecordingStreamingChatModel.class);

    private final StreamingChatModel delegate;
    private final RecordingInterceptor recorder;

    private RecordingStreamingChatModel(StreamingChatModel delegate, RecordingInterceptor recorder) {
        if (delegate == null) {
            throw new IllegalArgumentException("delegate StreamingChatModel must not be null");
        }
        if (recorder == null) {
            throw new IllegalArgumentException("recorder must not be null");
        }
        this.delegate = delegate;
        this.recorder = recorder;
    }

    /**
     * 包住仅实现流式接口的真实模型；recorder 通常为
     * {@code io.github.agentassert4j.recorder.InteractionRecorder}。
     */
    public static RecordingStreamingChatModel wrapStreaming(StreamingChatModel delegate, RecordingInterceptor recorder) {
        return new RecordingStreamingChatModel(delegate, recorder);
    }

    @Override
    public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
        long start = System.currentTimeMillis();
        // 会话标注必须在调用线程捕获：聚合完成回调发生在异步完成信号线程，
        // 那里取不到业务线程的 ThreadLocal
        RecordingContext context = RecordingContext.currentOrNull();
        delegate.chat(chatRequest, StreamingHandlerProxy.wrap(handler, (response, ttftMs, latencyMs) -> recordQuietly(chatRequest, response, latencyMs, ttftMs, context)));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        // 空清单是双触发防线：录制委托走 delegate.chat，内模型模板触发其自带
        // 监听器恰一次；此处若透传，装饰层模板会再触发一遍
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
