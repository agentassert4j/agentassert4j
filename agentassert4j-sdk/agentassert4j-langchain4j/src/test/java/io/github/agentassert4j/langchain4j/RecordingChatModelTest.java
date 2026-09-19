package io.github.agentassert4j.langchain4j;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.recorder.RecordingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 阻塞装饰器契约测试：透传保真、监听器单次触发、录制面与流式入口行为。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class RecordingChatModelTest {

    private static final ChatResponse CANNED = ChatResponse.builder().aiMessage(AiMessage.from("ok")).id("resp-id").build();

    /**
     * 阻塞桩：计数监听器烤入，doChat 返回固定响应并捕获收到的请求。
     */
    static class StubChatModel implements ChatModel {

        final AtomicInteger onRequestCount = new AtomicInteger();
        final AtomicInteger receivedTemperature = new AtomicInteger(-1);
        volatile ChatRequest lastRequest;
        ChatResponse toReturn = CANNED;
        RuntimeException toThrow;

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            if (toThrow != null) {
                throw toThrow;
            }
            lastRequest = chatRequest;
            if (chatRequest.parameters() != null && chatRequest.parameters().temperature() != null) {
                receivedTemperature.set((int) (chatRequest.parameters().temperature() * 10));
            }
            return toReturn;
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().modelName("stub-model").build();
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of(new ChatModelListener() {
                @Override
                public void onRequest(ChatModelRequestContext requestContext) {
                    onRequestCount.incrementAndGet();
                }
            });
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OPEN_AI;
        }
    }

    /**
     * 混合桩：同时实现阻塞与流式，流式入口逐回调驱动用户 handler。
     */
    static class HybridStubModel extends StubChatModel implements StreamingChatModel {

        // 同时实现两接口必须显式覆写共享默认方法，否则菱形默认冲突编译不过
        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of();
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("he");
            handler.onCompleteResponse(CANNED);
        }
    }

    /**
     * 编译期可见的扩展回调：验证代理对未知方法的转发机制。
     */
    interface RichHandler extends StreamingChatResponseHandler {

        void onCustomSignal(String signal);
    }

    @Nested
    @DisplayName("透传保真")
    class Passthrough {

        @Test
        @DisplayName("调用返回透传，收到的请求参数不被吞")
        void chatReturnsDelegateResponse() {
            StubChatModel stub = new StubChatModel();
            RecordingChatModel wrapper = RecordingChatModel.wrap(stub, new CapturingRecorder());
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("查订单")).temperature(0.5).build();

            ChatResponse response = wrapper.chat(request);

            assertSame(CANNED, response);
            assertEquals(5, stub.receivedTemperature.get());
        }

        @Test
        @DisplayName("异常原样透传且不产记录")
        void exceptionPropagatesWithoutRecord() {
            StubChatModel stub = new StubChatModel();
            stub.toThrow = new IllegalStateException("boom");
            CapturingRecorder recorder = new CapturingRecorder();
            RecordingChatModel wrapper = RecordingChatModel.wrap(stub, recorder);

            assertThrows(IllegalStateException.class, () -> wrapper.chat(ChatRequest.builder().messages(UserMessage.from("x")).build()));
            assertTrue(recorder.records().isEmpty());
        }

        @Test
        @DisplayName("上下文方法透传，listeners 返回空清单")
        void contextMethodsDelegate() {
            StubChatModel stub = new StubChatModel();
            RecordingChatModel wrapper = RecordingChatModel.wrap(stub, new CapturingRecorder());

            assertEquals("stub-model", wrapper.defaultRequestParameters().modelName());
            assertEquals(ModelProvider.OPEN_AI, wrapper.provider());
            assertEquals(Set.of(), wrapper.supportedCapabilities());
            assertTrue(wrapper.listeners().isEmpty());
        }
    }

    @Nested
    @DisplayName("监听器单次触发")
    class ListenerFiring {

        @Test
        @DisplayName("内模型监听器恰触发一次（防双触发是空清单设计的目的）")
        void delegateListenerFiresExactlyOnce() {
            StubChatModel stub = new StubChatModel();
            RecordingChatModel wrapper = RecordingChatModel.wrap(stub, new CapturingRecorder());

            wrapper.chat(ChatRequest.builder().messages(UserMessage.from("x")).build());

            assertEquals(1, stub.onRequestCount.get());
        }
    }

    @Nested
    @DisplayName("录制面")
    class Recording {

        @Test
        @DisplayName("阻塞调用录一条：请求响应字段与上下文标注齐备")
        void chatRecordsOnceWithContext() {
            StubChatModel stub = new StubChatModel();
            CapturingRecorder recorder = new CapturingRecorder();
            RecordingChatModel wrapper = RecordingChatModel.wrap(stub, recorder);
            ChatRequest request = ChatRequest.builder().messages(SystemMessage.from("sys"), UserMessage.from("查订单")).build();

            try (RecordingContext ctx = RecordingContext.start("session-9").withInvocationId("order-refund")) {
                wrapper.chat(request);
            }

            InteractionRecord record = recorder.sole();
            assertEquals("session-9", record.getSessionId());
            assertEquals("order-refund", record.getInvocationId());
            assertEquals("查订单", record.getUserInput());
            assertEquals("resp-id", record.getRecordId());
            assertTrue(record.getLatencyMs() >= 0);
        }
    }

    @Nested
    @DisplayName("流式入口")
    class StreamingFace {

        @Test
        @DisplayName("混合模型流式调用聚合录制，用户回调与首 token 时延齐备")
        void hybridStreamingRecordsOnComplete() {
            HybridStubModel stub = new HybridStubModel();
            CapturingRecorder recorder = new CapturingRecorder();
            RecordingChatModel wrapper = RecordingChatModel.wrap(stub, recorder);
            AtomicInteger partialCount = new AtomicInteger();
            AtomicInteger completeCount = new AtomicInteger();

            wrapper.chat(ChatRequest.builder().messages(UserMessage.from("x")).build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    partialCount.incrementAndGet();
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    completeCount.incrementAndGet();
                }

                @Override
                public void onError(Throwable error) {
                    throw new AssertionError("unexpected onError");
                }
            });

            assertEquals(1, partialCount.get());
            assertEquals(1, completeCount.get());
            InteractionRecord record = recorder.sole();
            assertEquals("resp-id", record.getRecordId());
            // 先有分片再完成：首 token 时延必须被记下且非负
            assertTrue(record.getTtftMs() != null && record.getTtftMs() >= 0, "ttft 应为非负实数，实际 " + record.getTtftMs());
        }

        @Test
        @DisplayName("非流式模型走流式入口显式抛错，不静默")
        void nonStreamingDelegateThrowsOnStream() {
            RecordingChatModel wrapper = RecordingChatModel.wrap(new StubChatModel(), new CapturingRecorder());

            UnsupportedOperationException error = assertThrows(UnsupportedOperationException.class, () -> wrapper.chat(ChatRequest.builder().messages(UserMessage.from("x")).build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                }

                @Override
                public void onError(Throwable e) {
                }
            }));
            assertTrue(error.getMessage().contains("StreamingChatModel"));
        }

        @Test
        @DisplayName("用户完成回调抛错不丢该次记录（录制在 finally）")
        void userCallbackThrowingStillRecords() {
            HybridStubModel stub = new HybridStubModel();
            CapturingRecorder recorder = new CapturingRecorder();
            RecordingChatModel wrapper = RecordingChatModel.wrap(stub, recorder);

            assertThrows(IllegalStateException.class, () -> wrapper.chat(ChatRequest.builder().messages(UserMessage.from("x")).build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    throw new IllegalStateException("user bug");
                }

                @Override
                public void onError(Throwable error) {
                }
            }));

            assertEquals("resp-id", recorder.sole().getRecordId(), "完成信号已到，用户回调抛错不丢该次记录");
        }

        @Test
        @DisplayName("代理对编译期不可见的方法同样转发（扩展回调不丢）")
        void proxyForwardsExtraInterfaceMethods() {
            AtomicInteger signals = new AtomicInteger();
            AtomicInteger completes = new AtomicInteger();
            RichHandler user = new RichHandler() {
                @Override
                public void onCustomSignal(String signal) {
                    signals.incrementAndGet();
                }

                @Override
                public void onPartialResponse(String token) {
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                    completes.incrementAndGet();
                }

                @Override
                public void onError(Throwable error) {
                }
            };

            RichHandler proxied = (RichHandler) StreamingHandlerProxy.wrap(user, (response, ttftMs, latencyMs) -> {
            });

            proxied.onCustomSignal("ping");
            proxied.onCompleteResponse(CANNED);

            assertEquals(1, signals.get());
            assertEquals(1, completes.get());
        }
    }
}
