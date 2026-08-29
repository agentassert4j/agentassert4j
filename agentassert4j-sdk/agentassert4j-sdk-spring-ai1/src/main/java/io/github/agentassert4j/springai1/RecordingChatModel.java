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
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicBoolean;
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
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
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
     * 内部工具执行检测告警：请求携带工具定义且内部工具执行开启时，模型编排的 toolCalls
     * 在装饰器视角不可见（被 ChatModel 内部回路消费），响应无可见工具调用 →
     * 该记录的工具维度失明。一次性 WARN 止血；恢复工具维的正路是关闭内部执行
     * 自持工具循环（每轮各自成记录、重放对称）或等待编排观察装饰。
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
        // 官方旋钮解析（null 按默认开启）：业务已关闭内部执行时，无工具调用的响应
        // 是模型的正常决策而非回路消费，不告警
        if (!ToolCallingChatOptions.isInternalToolExecutionEnabled(options)) {
            return;
        }
        toolBlindnessWarned.set(true);
        log.warn("检测到请求携带工具定义且 Spring AI 内部工具执行开启：模型编排的 toolCalls 被内部回路消费，本记录的工具维度缺失（分组与指纹只能依赖文本/结构维）。可在 RecordingContext 声明 skillId 保证分组身份；将 internalToolExecutionEnabled 设为 false 自持工具循环可恢复工具维度与重放对称性。");
    }
}
