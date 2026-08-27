package io.github.agentassert4j.springai1;

import io.github.agentassert4j.spi.RecordingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring AI 1.x ChatModel 录制装饰器 — 旁路捕获真实调用，业务透传零干预。
 *
 * <p>用法：用本类包住真实 ChatModel 并注册为 Bean，所有 ChatClient/直调流量
 * 即进入旁路录制管道：</p>
 * <pre>{@code
 * ChatModel real = new OpenAiChatModel(openAiApi);
 * ChatModel recorded = RecordingChatModel.wrap(real, interactionRecorder);
 * }</pre>
 *
 * <p>粒度说明：1.x 默认在 ChatModel 内部执行完整工具回路（internalToolExecutionEnabled），
 * 装饰器视角一次 call = 完整工具回合（初始请求 + 最终聚合响应，中间轮次在
 * provider 实现内部不可见）；业务侧关闭内部工具执行时，每个 LLM 轮次各自成记录。</p>
 *
 * <p>录制失败只记 WARN 不抛出——框架任何故障不影响业务调用。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public final class RecordingChatModel implements ChatModel {

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
     * 包裹真实 ChatModel；recorder 通常为 {@code io.github.agentassert4j.recorder.InteractionRecorder}。
     */
    public static RecordingChatModel wrap(ChatModel delegate, RecordingInterceptor recorder) {
        return new RecordingChatModel(delegate, recorder);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        long start = System.currentTimeMillis();
        ChatResponse response = delegate.call(prompt);
        recordQuietly(prompt, response, System.currentTimeMillis() - start, null);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.currentTimeMillis();
        AtomicLong ttft = new AtomicLong(-1);
        Flux<ChatResponse> source = delegate.stream(prompt).doOnNext(chunk -> ttft.compareAndSet(-1, System.currentTimeMillis() - start));
        return new MessageAggregator().aggregate(source, aggregated -> recordQuietly(prompt, aggregated, System.currentTimeMillis() - start, ttft.get()));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void recordQuietly(Prompt prompt, ChatResponse response, long latencyMs, Long ttftMs) {
        try {
            recorder.intercept(SpringAiRecordMapper.toRecord(prompt, response, latencyMs, ttftMs));
        } catch (Exception e) {
            log.warn("旁路录制失败（不影响业务调用）: {}", e.getMessage());
        }
    }
}
