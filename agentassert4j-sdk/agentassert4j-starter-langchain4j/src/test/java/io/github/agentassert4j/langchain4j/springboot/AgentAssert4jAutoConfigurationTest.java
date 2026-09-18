package io.github.agentassert4j.langchain4j.springboot;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.DefaultChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.github.agentassert4j.langchain4j.RecordingChatModel;
import io.github.agentassert4j.langchain4j.RecordingContext;
import io.github.agentassert4j.langchain4j.RecordingStreamingChatModel;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.recorder.InteractionRecorder;
import io.github.agentassert4j.recorder.SanitizeStrategy;
import io.github.agentassert4j.spi.RecordingInterceptor;
import io.github.agentassert4j.spi.StorageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LangChain4j 线自动装配契约测试：双类型包装、退出、复用用户 Bean、
 * 真管道落库、最高验证版本的富回调转发与两框架共存。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class AgentAssert4jAutoConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentAssert4jAutoConfiguration.class));

    /**
     * 阻塞接口桩：经模板方法进入 doChat，返回带 id/用量/结束原因的固定响应。
     */
    static class Lc4jStubChatModel implements ChatModel {

        ChatResponse respond() {
            return ChatResponse.builder().aiMessage(AiMessage.from("已发货")).id("resp-stub").modelName("deepseek-v4-flash").tokenUsage(new TokenUsage(10, 5, 15)).finishReason(FinishReason.STOP).build();
        }

        @Override
        public ChatResponse doChat(ChatRequest chatRequest) {
            return respond();
        }
    }

    /**
     * streaming-only 桩：OpenAiStreamingChatModel 一类「只实现流式接口」的形状代表。
     */
    static class Lc4jStreamingOnlyStub implements StreamingChatModel {

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("已");
            handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("已发货")).build());
        }
    }

    /**
     * 混合桩：同时实现两接口（四个共享默认方法全部显式覆写消解菱形冲突）。
     */
    static class Lc4jHybridStub extends Lc4jStubChatModel implements StreamingChatModel {

        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of();
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OPEN_AI;
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of();
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().build();
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onCompleteResponse(respond());
        }
    }

    private String tempDbPath() {
        return tempDir.resolve("lc4j-starter-" + System.nanoTime() + ".db").toString();
    }

    /**
     * RingBuffer 发布是异步的，flush 只排空消费线程已积累的批次——
     * 轮询等待落库计数到位，避免发布后立刻断言的竞态。
     */
    private static void awaitWritten(InteractionRecorder recorder, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (recorder.getWrittenCount() < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("recorder 段全合法键哨兵值绑定（yml 通道键集 ↔ 属性字段逐项吸收）")
    void recorderPropertiesSentinelBinding() {
        runner.withBean("chatModel", Lc4jStubChatModel.class).withPropertyValues("agentassert4j.storage.url=" + tempDbPath(), "agentassert4j.recorder.default-invocation-id=inv-sentinel", "agentassert4j.recorder.endpoint=http://ep-sentinel:1234", "agentassert4j.recorder.batch-size=7", "agentassert4j.recorder.flush-interval-ms=1234", "agentassert4j.recorder.max-buffer-size=77", "agentassert4j.recorder.ring-buffer-size=2048", "agentassert4j.recorder.sensitive-fields=apiKey,password", "agentassert4j.recorder.sanitize-strategy=DROP", "agentassert4j.recorder.sanitize-user-input=true", "agentassert4j.recorder.sanitize-model-response=true", "agentassert4j.recorder.record-undeclared-chat=false", "agentassert4j.recorder.enabled=true").run(context -> {
            AgentAssert4jProperties.Recorder p = context.getBean(AgentAssert4jProperties.class).getRecorder();
            assertEquals("inv-sentinel", p.getDefaultInvocationId());
            assertEquals("http://ep-sentinel:1234", p.getEndpoint());
            assertEquals(7, p.getBatchSize());
            assertEquals(1234, p.getFlushIntervalMs());
            assertEquals(77, p.getMaxBufferSize());
            assertEquals(2048, p.getRingBufferSize());
            assertEquals(List.of("apiKey", "password"), p.getSensitiveFields());
            assertEquals(SanitizeStrategy.DROP, p.getSanitizeStrategy());
            assertTrue(p.isSanitizeUserInput());
            assertTrue(p.isSanitizeModelResponse());
            assertFalse(p.isRecordUndeclaredChat());
            assertTrue(p.isEnabled());
        });
    }

    @Test
    @DisplayName("阻塞模型 Bean 被包为 RecordingChatModel，录制器与存储就绪")
    void wrapsBlockingBean() {
        runner.withBean("chatModel", Lc4jStubChatModel.class).withPropertyValues("agentassert4j.storage.url=" + tempDbPath()).run(context -> {
            assertTrue(context.getBean("chatModel") instanceof RecordingChatModel, "阻塞模型 Bean 必须被录制装饰器替换");
            assertNotNull(context.getBean(InteractionRecorder.class));
            assertNotNull(context.getBean(StorageRepository.class));
        });
    }

    @Test
    @DisplayName("streaming-only 模型 Bean 被包为 RecordingStreamingChatModel（不漏包）")
    void wrapsStreamingOnlyBean() {
        runner.withBean("streamingModel", Lc4jStreamingOnlyStub.class).withPropertyValues("agentassert4j.storage.url=" + tempDbPath()).run(context -> assertTrue(context.getBean("streamingModel") instanceof RecordingStreamingChatModel, "仅实现流式接口的 Bean 必须被流式装饰器替换"));
    }

    @Test
    @DisplayName("混合实现两接口的 Bean 由阻塞装饰器一次收全")
    void hybridBeanWrappedOnce() {
        runner.withBean("hybridModel", Lc4jHybridStub.class).withPropertyValues("agentassert4j.storage.url=" + tempDbPath()).run(context -> {
            Object bean = context.getBean("hybridModel");
            assertTrue(bean instanceof RecordingChatModel, "混合 Bean 走阻塞装饰器");
            assertFalse(bean instanceof RecordingStreamingChatModel, "不双层包装");
        });
    }

    @Test
    @DisplayName("全链路：阻塞调用经 Disruptor 管道落 SQLite 可查")
    void recordedCallReachesStorage() {
        String dbPath = tempDbPath();
        runner.withBean("chatModel", Lc4jStubChatModel.class).withPropertyValues("agentassert4j.storage.url=" + dbPath).run(context -> {
            ChatModel model = context.getBean("chatModel", ChatModel.class);
            // 显式 finally 关闭：弹出 ThreadLocal 作用域，防止声明泄漏进测试线程
            RecordingContext scope = RecordingContext.start("lc4j-session").withInvocationId("order-flow");
            ChatResponse response;
            try {
                response = model.chat(ChatRequest.builder().messages(UserMessage.from("订单 SO-1 在哪")).build());
            } finally {
                scope.close();
            }
            assertEquals("已发货", response.aiMessage().text());

            InteractionRecorder recorder = context.getBean(InteractionRecorder.class);
            awaitWritten(recorder, 1);
            recorder.flush();
            assertEquals(1, recorder.getWrittenCount(), "一次调用必须写成一条记录");

            StorageRepository repository = context.getBean(StorageRepository.class);
            List<InteractionRecord> records = repository.findBySessionId("lc4j-session");
            assertEquals(1, records.size());
            assertEquals("订单 SO-1 在哪", records.get(0).getUserInput());
            assertEquals("已发货", records.get(0).getModelResponse());
            assertEquals("deepseek-v4-flash", records.get(0).getServedModel());
            assertEquals("resp-stub", records.get(0).getRecordId());
        });
    }

    @Test
    @DisplayName("enabled=false 时整体退出：不建 Bean 不包装")
    void disabledByPropertyBacksOff() {
        runner.withBean("chatModel", Lc4jStubChatModel.class).withPropertyValues("agentassert4j.enabled=false").run(context -> {
            assertFalse(context.getBean("chatModel") instanceof RecordingChatModel);
            assertEquals(0, context.getBeanNamesForType(InteractionRecorder.class).length);
            assertEquals(0, context.getBeanNamesForType(StorageRepository.class).length);
        });
    }

    @Test
    @DisplayName("classpath 无 langchain4j 时静默退出")
    void backOffsWithoutLangChain4j() {
        runner.withClassLoader(new FilteredClassLoader(ChatModel.class)).run(context -> assertEquals(0, context.getBeanNamesForType(InteractionRecorder.class).length));
    }

    @Test
    @DisplayName("已包装的模型不重复包")
    void doesNotDoubleWrap() {
        List<InteractionRecord> sink = new CopyOnWriteArrayList<>();
        RecordingChatModel prewrapped = RecordingChatModel.wrap(new Lc4jStubChatModel(), (RecordingInterceptor) sink::add);
        runner.withBean("prewrapped", RecordingChatModel.class, () -> prewrapped).withPropertyValues("agentassert4j.storage.url=" + tempDbPath()).run(context -> assertSame(prewrapped, context.getBean("prewrapped"), "已是装饰器的 Bean 必须原样保留"));
    }

    @Test
    @DisplayName("天花板版本富回调经全链路转发不丢（本模块测试类即天花板）")
    void richCallbackForwardedThroughWrapperStack() {
        runner.withBean("hybridModel", RichCallbackStub.class).withPropertyValues("agentassert4j.storage.url=" + tempDbPath()).run(context -> {
            StreamingChatModel model = context.getBean("hybridModel", StreamingChatModel.class);
            AtomicInteger rawEvents = new AtomicInteger();
            RecordingContext scope = RecordingContext.start("rich-session").withInvocationId("rich-flow");
            try {
                model.chat(ChatRequest.builder().messages(UserMessage.from("x")).build(), new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String token) {
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                    }

                    @Override
                    public void onError(Throwable error) {
                    }

                    @Override
                    public void onUnmappedRawEvent(Object rawEvent) {
                        rawEvents.incrementAndGet();
                    }
                });
            } finally {
                scope.close();
            }
            assertEquals(1, rawEvents.get(), "富回调必须穿透装饰器与模板包装层到达用户 handler");

            InteractionRecorder recorder = context.getBean(InteractionRecorder.class);
            awaitWritten(recorder, 1);
            recorder.flush();
            assertEquals(1, recorder.getWrittenCount(), "聚合完成仍驱动一次录制");
        });
    }

    /**
     * 富回调桩：流式入口触发一个非核心回调（编译期仅最高验证版本可见的形状）。
     */
    static class RichCallbackStub extends Lc4jStubChatModel implements StreamingChatModel {

        @Override
        public Set<Capability> supportedCapabilities() {
            return Set.of();
        }

        @Override
        public ModelProvider provider() {
            return ModelProvider.OPEN_AI;
        }

        @Override
        public List<ChatModelListener> listeners() {
            return List.of();
        }

        @Override
        public ChatRequestParameters defaultRequestParameters() {
            return DefaultChatRequestParameters.builder().build();
        }

        @Override
        public void doChat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
            handler.onUnmappedRawEvent(Map.of("vendor", "extra"));
            handler.onCompleteResponse(respond());
        }
    }

    /**
     * Spring AI 面最小桩（同名类消歧：本文件已导入 LangChain4j 同名类型）。
     */
    static class SpringAiStubChatModel implements org.springframework.ai.chat.model.ChatModel {
        @Override
        public org.springframework.ai.chat.model.ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
            return new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(new org.springframework.ai.chat.messages.AssistantMessage("库存充足"))));
        }

        @Override
        public org.springframework.ai.chat.prompt.ChatOptions getDefaultOptions() {
            return null;
        }
    }

    @Test
    @DisplayName("混架共存：两框架各自的模型各被各的装饰器包一次，共用同一录制器")
    void hybridCoexistenceWithSpringAiStarter() {
        ApplicationContextRunner mixed = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(io.github.agentassert4j.springboot.AgentAssert4jAutoConfiguration.class, AgentAssert4jAutoConfiguration.class));
        mixed.withBean("lc4jModel", Lc4jStubChatModel.class).withBean("springModel", SpringAiStubChatModel.class).withPropertyValues("agentassert4j.storage.url=" + tempDbPath()).run(context -> {
            assertTrue(context.getBean("lc4jModel") instanceof RecordingChatModel, "LangChain4j 模型由本 starter 包装");
            assertTrue(context.getBean("springModel") instanceof io.github.agentassert4j.springai1.RecordingChatModel, "Spring AI 模型由 Spring AI starter 包装");
            assertEquals(1, context.getBeanNamesForType(InteractionRecorder.class).length, "两 starter 共用同一录制器，不重复建");

            RecordingContext scope = RecordingContext.start("mixed-session").withInvocationId("mixed-flow");
            try {
                context.getBean("lc4jModel", ChatModel.class).chat(ChatRequest.builder().messages(UserMessage.from("查订单")).build());
                context.getBean("springModel", org.springframework.ai.chat.model.ChatModel.class).call(new org.springframework.ai.chat.prompt.Prompt(List.of(new org.springframework.ai.chat.messages.UserMessage("查库存"))));
            } finally {
                scope.close();
            }
            InteractionRecorder recorder = context.getBean(InteractionRecorder.class);
            awaitWritten(recorder, 2);
            recorder.flush();
            assertEquals(2, recorder.getWrittenCount(), "两框架各一条，计数闭合在同一录制器");
        });
    }
}
