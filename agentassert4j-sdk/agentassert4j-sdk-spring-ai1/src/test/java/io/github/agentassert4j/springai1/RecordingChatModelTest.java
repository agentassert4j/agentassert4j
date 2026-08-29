package io.github.agentassert4j.springai1;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.RecordingInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 录制装饰器行为契约：透传零干预、旁路记录、异常隔离。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class RecordingChatModelTest {

    private static final class CapturingInterceptor implements RecordingInterceptor {
        final List<InteractionRecord> records = new CopyOnWriteArrayList<>();

        @Override
        public void intercept(InteractionRecord record) {
            records.add(record);
        }
    }

    private static class StubChatModel implements ChatModel {
        ChatResponse next;
        Flux<ChatResponse> nextStream;

        @Override
        public ChatResponse call(Prompt prompt) {
            return next;
        }

        @Override
        public Flux<ChatResponse> stream(Prompt prompt) {
            return nextStream;
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    private static ChatResponse textResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content), ChatGenerationMetadata.builder().finishReason("STOP").build())), ChatResponseMetadata.builder().model("deepseek-v4-flash").usage(new DefaultUsage(10, 5)).build());
    }

    @Test
    @DisplayName("call 透传原响应并旁路落一条完整记录")
    void callDelegatesAndRecords() {
        StubChatModel stub = new StubChatModel();
        stub.next = textResponse("已发货");
        CapturingInterceptor interceptor = new CapturingInterceptor();
        RecordingChatModel model = RecordingChatModel.wrap(stub, interceptor);
        Prompt prompt = new Prompt(List.of(new UserMessage("订单 SO-1 在哪")));

        ChatResponse response = model.call(prompt);

        assertSame(stub.next, response, "业务响应原样透传");
        assertEquals(1, interceptor.records.size());
        InteractionRecord record = interceptor.records.get(0);
        assertEquals("订单 SO-1 在哪", record.getUserInput());
        assertEquals("已发货", record.getModelResponse());
        assertEquals("deepseek-v4-flash", record.getServedModel());
        assertEquals(10, record.getInputTokens());
        assertEquals("stop", record.getFinishReason());
        assertTrue(record.getLatencyMs() >= 0);
        assertNotNull(record.getRecorderVersion());
    }

    @Test
    @DisplayName("录制器抛异常不影响业务调用")
    void recorderFailureDoesNotAffectBusiness() {
        StubChatModel stub = new StubChatModel();
        stub.next = textResponse("ok");
        RecordingInterceptor broken = record -> {
            throw new IllegalStateException("storage down");
        };
        RecordingChatModel model = RecordingChatModel.wrap(stub, broken);

        ChatResponse response = model.call(new Prompt(List.of(new UserMessage("hi"))));

        assertEquals("ok", response.getResult().getOutput().getText(), "录制故障必须被隔离，业务拿到正常响应");
    }

    @Test
    @DisplayName("流式调用聚合成单条记录并测得首 token 延迟")
    void streamAggregatesAndRecords() {
        StubChatModel stub = new StubChatModel();
        stub.nextStream = Flux.just(textResponse("你"), textResponse("好"));
        CapturingInterceptor interceptor = new CapturingInterceptor();
        RecordingChatModel model = RecordingChatModel.wrap(stub, interceptor);
        Prompt prompt = new Prompt(List.of(new UserMessage("打个招呼")));

        model.stream(prompt).blockLast();

        assertEquals(1, interceptor.records.size(), "一次流式调用只落一条聚合记录");
        InteractionRecord record = interceptor.records.get(0);
        assertEquals("你好", record.getModelResponse(), "分片正文聚合");
        assertNotNull(record.getTtftMs(), "流式调用必须带首 token 延迟");
        assertTrue(record.getTtftMs() >= 0);
    }

    @Test
    @DisplayName("录音上下文内的调用携带会话与技能标注")
    void recordingContextAnnotatesRecords() {
        StubChatModel stub = new StubChatModel();
        stub.next = textResponse("ok");
        CapturingInterceptor interceptor = new CapturingInterceptor();
        RecordingChatModel model = RecordingChatModel.wrap(stub, interceptor);

        try (RecordingContext ctx = RecordingContext.start("session-7").withSkillId("refund")) {
            model.call(new Prompt(List.of(new UserMessage("hi"))));
        }
        model.call(new Prompt(List.of(new UserMessage("again"))));

        assertEquals(2, interceptor.records.size());
        assertEquals("session-7", interceptor.records.get(0).getSessionId());
        assertEquals("refund", interceptor.records.get(0).getSkillId());
        assertTrue(interceptor.records.get(1).getSessionId() == null || interceptor.records.get(1).getSessionId().isEmpty(), "作用域关闭后不再携带会话标注");
    }

    @Test
    @DisplayName("异步完成线程上聚合时，会话标注仍来自调用线程")
    void streamAsyncCompletionKeepsContext() {
        StubChatModel stub = new StubChatModel();
        // 模拟真实异步 delegate：完成信号切换到非调用线程，回调线程取不到业务 ThreadLocal
        stub.nextStream = Flux.just(textResponse("你"), textResponse("好")).publishOn(Schedulers.boundedElastic());
        CapturingInterceptor interceptor = new CapturingInterceptor();
        RecordingChatModel model = RecordingChatModel.wrap(stub, interceptor);

        try (RecordingContext ctx = RecordingContext.start("session-async").withSkillId("stream-skill")) {
            model.stream(new Prompt(List.of(new UserMessage("hi")))).blockLast();
        }

        assertEquals(1, interceptor.records.size());
        assertEquals("session-async", interceptor.records.get(0).getSessionId(), "上下文必须在调用线程捕获——聚合回调发生在异步完成信号线程");
        assertEquals("stream-skill", interceptor.records.get(0).getSkillId());
    }

    /**
     * 确定性工具回调：记录调用次数，固定返回 JSON 结果。
     */
    private static ToolCallback stubTool(String name, AtomicInteger callCount) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description("查询订单").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                callCount.incrementAndGet();
                return "{\"status\":\"shipped\"}";
            }
        };
    }

    @Test
    @DisplayName("编排观察：内部回路执行的 toolCalls 按序进同一条记录（名称/参数/结果/成功）")
    void internalToolLoop_observedIntoSingleRecord() {
        AtomicInteger callCount = new AtomicInteger();
        ToolCallback tool = stubTool("get_order", callCount);
        // 模拟 1.x 内部工具回路：ChatModel 实现内部连续两次调用工具后返回最终文本
        StubChatModel stub = new StubChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
                options.getToolCallbacks().get(0).call("{\"orderId\":\"SO-1\"}");
                options.getToolCallbacks().get(0).call("{\"orderId\":\"SO-2\"}");
                return textResponse("已发货");
            }
        };
        CapturingInterceptor interceptor = new CapturingInterceptor();
        RecordingChatModel model = RecordingChatModel.wrap(stub, interceptor);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(List.of(tool)).build();

        model.call(new Prompt(List.of(new UserMessage("订单在哪")), options));

        assertEquals(1, interceptor.records.size());
        InteractionRecord record = interceptor.records.get(0);
        assertTrue(record.isHasToolCalls(), "内部回路执行的工具调用恢复工具维度");
        assertEquals(2, record.getToolCalls().size(), "多轮回合按序累积进同一条记录（编排即回归单元）");
        assertEquals("get_order", record.getToolCalls().get(0).getToolName());
        assertEquals("SO-1", record.getToolCalls().get(0).getArguments().get("orderId"));
        assertEquals("SO-2", record.getToolCalls().get(1).getArguments().get("orderId"));
        assertEquals("{\"status\":\"shipped\"}", record.getToolCalls().get(0).getResult(), "结果原文捕获（链式半重放的前提）");
        assertTrue(record.getToolCalls().get(0).isSuccess());
        assertTrue(record.getToolCalls().get(0).getArgTypes().containsValue("string"), "参数类型经 ArgTypeUtil 同词表派生");
        assertEquals(2, callCount.get(), "业务工具真实执行次数不受装饰影响");
    }

    @Test
    @DisplayName("换装发生在 options 副本上：业务 options 与原始回调零触碰")
    void decorateUsesCopy_businessOptionsNeverMutated() {
        AtomicInteger callCount = new AtomicInteger();
        ToolCallback tool = stubTool("get_order", callCount);
        List<ToolCallback> original = List.of(tool);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(original).build();
        List<ToolCallback> seenInDelegate = new CopyOnWriteArrayList<>();
        StubChatModel stub = new StubChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                seenInDelegate.add(((ToolCallingChatOptions) prompt.getOptions()).getToolCallbacks().get(0));
                return textResponse("ok");
            }
        };
        CapturingInterceptor interceptor = new CapturingInterceptor();
        RecordingChatModel model = RecordingChatModel.wrap(stub, interceptor);

        model.call(new Prompt(List.of(new UserMessage("hi")), options));

        assertSame(tool, options.getToolCallbacks().get(0), "业务 options 上的回调未被替换");
        assertNotSame(tool, seenInDelegate.get(0), "delegate 看到的是观察装饰副本");
    }

    @Test
    @DisplayName("响应自带 native toolCalls 时（ChatClient 逐轮姿势）观察缓冲静默丢弃，不双计")
    void nativeToolCallsPresent_observationDiscarded() {
        AtomicInteger callCount = new AtomicInteger();
        ToolCallback tool = stubTool("get_order", callCount);
        StubChatModel stub = new StubChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                ToolCallingChatOptions options = (ToolCallingChatOptions) prompt.getOptions();
                options.getToolCallbacks().get(0).call("{}");
                AssistantMessage output = new AssistantMessage("", Map.of(), List.of(new AssistantMessage.ToolCall("id-1", "function", "get_order", "{\"orderId\":\"SO-1\"}")));
                return new ChatResponse(List.of(new Generation(output, ChatGenerationMetadata.builder().finishReason("tool_calls").build())), ChatResponseMetadata.builder().model("deepseek-v4-flash").usage(new DefaultUsage(10, 5)).build());
            }
        };
        CapturingInterceptor interceptor = new CapturingInterceptor();
        RecordingChatModel model = RecordingChatModel.wrap(stub, interceptor);
        ToolCallingChatOptions options = ToolCallingChatOptions.builder().toolCallbacks(List.of(tool)).build();

        model.call(new Prompt(List.of(new UserMessage("hi")), options));

        InteractionRecord record = interceptor.records.get(0);
        assertEquals(1, record.getToolCalls().size(), "只保留 native 决策，观察缓冲不双计");
        assertNull(record.getToolCalls().get(0).getResult(), "native 决策无结果字段（结果在下一轮上下文）");
    }
}
