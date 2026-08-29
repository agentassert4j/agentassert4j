package io.github.agentassert4j.springai1;

import io.github.agentassert4j.model.InteractionRecord;
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
 * 装饰器视角一次 call = 完整工具回合（初始请求 + 最终聚合响应）。编排观察装饰
 * 把回路内的每次工具调用（名称/参数/结果）按序捕获进同一条记录，工具维度
 * 业务零改动即恢复；业务侧关闭内部工具执行时，每个 LLM 轮次各自成记录。</p>
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
        RecordingContext context = RecordingContext.currentOrNull();
        // 梯 2 编排观察：换装观察回调（options 副本），内部回路的工具调用按序进缓冲
        ToolInvocationObserver observer = new ToolInvocationObserver();
        Prompt observed = observer.decorate(prompt);
        ChatResponse response = delegate.call(observed);
        recordQuietly(prompt, response, System.currentTimeMillis() - start, null, context, observer);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.currentTimeMillis();
        // 会话标注必须在调用线程捕获：聚合回调发生在异步完成信号线程，
        // 那里取不到业务线程的 ThreadLocal
        RecordingContext context = RecordingContext.currentOrNull();
        ToolInvocationObserver observer = new ToolInvocationObserver();
        Prompt observed = observer.decorate(prompt);
        AtomicLong ttft = new AtomicLong(-1);
        Flux<ChatResponse> source = delegate.stream(observed).doOnNext(chunk -> ttft.compareAndSet(-1, System.currentTimeMillis() - start));
        return new MessageAggregator().aggregate(source, aggregated -> recordQuietly(prompt, aggregated, System.currentTimeMillis() - start, ttft.get(), context, observer));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    private void recordQuietly(Prompt prompt, ChatResponse response, long latencyMs, Long ttftMs, RecordingContext context, ToolInvocationObserver observer) {
        try {
            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, response, latencyMs, ttftMs, context, observer.snapshot());
            recorder.intercept(record);
        } catch (Exception e) {
            log.warn("旁路录制失败（不影响业务调用）: {}", e.getMessage());
        }
    }
}
