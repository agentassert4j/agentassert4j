package io.github.agentassert4j.springai2;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.RecordingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.MessageAggregator;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Spring AI 2.x ChatModel 录制装饰器 — 旁路捕获真实调用，业务透传零干预。
 *
 * <p>用法：用本类包住真实 ChatModel 并注册为 Bean：</p>
 * <pre>{@code
 * ChatModel real = new OpenAiChatModel(openAiApi);
 * ChatModel recorded = RecordingChatModel.wrap(real, interactionRecorder);
 * }</pre>
 *
 * <p>粒度说明：2.x 的工具调用循环由 ChatClient 的 ToolCallingAdvisor 在
 * ChatModel 之上驱动，每个 LLM 轮次都是一次独立 call——装饰器天然逐轮可见，
 * 无需关闭任何执行选项。</p>
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
    /**
     * 工具维失明告警的实例级防刷屏开关——只需提醒一次，后续调用不再重复
     */
    private final AtomicBoolean toolBlindnessWarned = new AtomicBoolean(false);

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
        ChatResponse response = delegate.call(prompt);
        recordQuietly(prompt, response, System.currentTimeMillis() - start, null, context);
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        long start = System.currentTimeMillis();
        // 会话标注必须在调用线程捕获：聚合回调发生在异步完成信号线程，
        // 那里取不到业务线程的 ThreadLocal
        RecordingContext context = RecordingContext.currentOrNull();
        AtomicLong ttft = new AtomicLong(-1);
        Flux<ChatResponse> source = delegate.stream(prompt).doOnNext(chunk -> ttft.compareAndSet(-1, System.currentTimeMillis() - start));
        return new MessageAggregator().aggregate(source, aggregated -> recordQuietly(prompt, aggregated, System.currentTimeMillis() - start, ttft.get(), context));
    }

    @Override
    public ChatOptions getOptions() {
        return delegate.getOptions();
    }

    private void recordQuietly(Prompt prompt, ChatResponse response, long latencyMs, Long ttftMs, RecordingContext context) {
        try {
            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, response, latencyMs, ttftMs, context);
            warnIfToolDimensionBlind(prompt, record);
            recorder.intercept(record);
        } catch (Exception e) {
            log.warn("旁路录制失败（不影响业务调用）: {}", e.getMessage());
        }
    }

    /**
     * 内部工具执行检测告警：请求携带工具定义而响应无可见工具调用——模型编排的 toolCalls
     * 很可能被模型实现内部的 ToolCallingManager 回路消费，工具维度失明。
     * ChatClient Advisor 链驱动的逐轮循环不受影响（中间轮次自带 toolCalls，
     * 不会触发本告警）。一次性 WARN 止血；正路是声明 skillId 保证分组身份，
     * 或等待编排观察装饰恢复工具维度与重放对称性。
     */
    private void warnIfToolDimensionBlind(Prompt prompt, InteractionRecord record) {
        if (toolBlindnessWarned.get() || record.isHasToolCalls()) {
            return;
        }
        ChatOptions options = prompt.getOptions();
        if (!(options instanceof ToolCallingChatOptions)) {
            return;
        }
        ToolCallingChatOptions toolOptions = (ToolCallingChatOptions) options;
        if (toolOptions.getToolCallbacks() == null || toolOptions.getToolCallbacks().isEmpty()) {
            return;
        }
        toolBlindnessWarned.set(true);
        log.warn("检测到请求携带工具定义而响应无可见 toolCalls：模型编排的调用很可能被内部工具回路消费，本记录的工具维度缺失（分组与指纹只能依赖文本/结构维）。可在 RecordingContext 声明 skillId 保证分组身份，或将工具循环改为业务自持/编排观察方式以恢复工具维度。");
    }
}
