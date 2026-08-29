package io.github.agentassert4j.springboot4;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.recorder.InteractionRecorder;
import io.github.agentassert4j.recorder.RecorderConfig;
import io.github.agentassert4j.spi.RecordingInterceptor;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.springai2.RecordingChatModel;
import io.github.agentassert4j.springai2.RecordingContext;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自动装配契约测试：包装、退出、复用用户 Bean、真管道落库。
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
class AgentAssert4jAutoConfigurationTest {

    @TempDir
    Path tempDir;

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(AgentAssert4jAutoConfiguration.class));

    static final class StubChatModel implements ChatModel {
        @Override
        public ChatResponse call(Prompt prompt) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("已发货"), ChatGenerationMetadata.builder().finishReason("STOP").build())), ChatResponseMetadata.builder().model("deepseek-v4-flash").usage(new DefaultUsage(10, 5)).build());
        }

        @Override
        public ChatOptions getDefaultOptions() {
            return null;
        }
    }

    private String tempDbPath() {
        return tempDir.resolve("starter-" + System.nanoTime() + ".db").toString();
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
    @DisplayName("应用级默认 skillId：未声明调用按默认身份录制")
    void defaultSkillId_recordsUndeclaredCalls() {
        String dbPath = tempDbPath();
        runner.withBean("chatModel", StubChatModel.class).withPropertyValues("agentassert4j.database=" + dbPath, "agentassert4j.skill-id=order-flow").run(context -> {
            ChatModel model = context.getBean("chatModel", ChatModel.class);
            model.call(new Prompt(List.of(new UserMessage("订单 SO-1 在哪"))));

            InteractionRecorder recorder = context.getBean(InteractionRecorder.class);
            awaitWritten(recorder, 1);
            recorder.flush();
            assertEquals(1, recorder.getWrittenCount(), "默认声明使未声明调用过采集门");

            StorageRepository repository = context.getBean(StorageRepository.class);
            List<String> sessions = repository.findAllSessionIds();
            InteractionRecord record = repository.findBySessionId(sessions.iterator().next()).get(0);
            assertEquals("order-flow", record.getSkillId(), "默认 skillId 落到记录声明位");
        });
    }

    @Test
    @DisplayName("容器内 ChatModel 被包装，录制器与存储就绪")
    void wrapsChatModelBean() {
        runner.withBean("chatModel", StubChatModel.class).withPropertyValues("agentassert4j.database=" + tempDbPath()).run(context -> {
            assertTrue(context.getBean("chatModel") instanceof RecordingChatModel, "ChatModel Bean 必须被录制装饰器替换");
            assertNotNull(context.getBean(InteractionRecorder.class));
            assertNotNull(context.getBean(StorageRepository.class));
        });
    }

    @Test
    @DisplayName("全链路：包装后的调用经 Disruptor 管道落 SQLite 可查")
    void recordedCallReachesStorage() {
        String dbPath = tempDbPath();
        runner.withBean("chatModel", StubChatModel.class).withPropertyValues("agentassert4j.database=" + dbPath).run(context -> {
            ChatModel model = context.getBean("chatModel", ChatModel.class);
            ChatResponse response;
            // 采集门：未声明且无工具调用的纯对话不录——管道测试走标准声明姿势。
            // 显式 finally 关闭：弹出 ThreadLocal 作用域，防止声明泄漏进测试线程
            RecordingContext scope = RecordingContext.start(null).withSkillId("order-flow");
            try {
                response = model.call(new Prompt(List.of(new UserMessage("订单 SO-1 在哪"))));
            } finally {
                scope.close();
            }
            assertEquals("已发货", response.getResult().getOutput().getText());

            InteractionRecorder recorder = context.getBean(InteractionRecorder.class);
            awaitWritten(recorder, 1);
            recorder.flush();
            assertEquals(1, recorder.getWrittenCount(), "一次调用必须写成一条记录");

            StorageRepository repository = context.getBean(StorageRepository.class);
            List<String> sessions = repository.findAllSessionIds();
            assertEquals(1, sessions.size(), "未声明会话时按记录退化为独立会话");
            List<InteractionRecord> records = repository.findBySessionId(sessions.iterator().next());
            assertEquals(1, records.size());
            assertEquals("订单 SO-1 在哪", records.get(0).getUserInput());
            assertEquals("已发货", records.get(0).getModelResponse());
            assertEquals("deepseek-v4-flash", records.get(0).getServedModel());
            assertNotNull(records.get(0).getSkillId(), "消费侧富化必须回填 skillId");
        });
    }

    @Test
    @DisplayName("enabled=false 时整体退出：不建 Bean 不包装")
    void disabledByPropertyBacksOff() {
        runner.withBean("chatModel", StubChatModel.class).withPropertyValues("agentassert4j.enabled=false").run(context -> {
            assertFalse(context.getBean("chatModel") instanceof RecordingChatModel);
            assertEquals(0, context.getBeanNamesForType(InteractionRecorder.class).length);
            assertEquals(0, context.getBeanNamesForType(StorageRepository.class).length);
        });
    }

    @Test
    @DisplayName("classpath 无 spring-ai 时静默退出")
    void backOffsWithoutSpringAi() {
        runner.withClassLoader(new FilteredClassLoader(ChatModel.class)).run(context -> assertEquals(0, context.getBeanNamesForType(InteractionRecorder.class).length));
    }

    @Test
    @DisplayName("已包装的 ChatModel 不重复包")
    void doesNotDoubleWrap() {
        List<InteractionRecord> sink = new CopyOnWriteArrayList<>();
        RecordingChatModel prewrapped = RecordingChatModel.wrap(new StubChatModel(), (RecordingInterceptor) sink::add);
        runner.withBean("prewrapped", RecordingChatModel.class, () -> prewrapped).withPropertyValues("agentassert4j.database=" + tempDbPath()).run(context -> assertSame(prewrapped, context.getBean("prewrapped"), "已是 RecordingChatModel 的 Bean 必须原样保留"));
    }

    @Test
    @DisplayName("自定义数据库路径生效（建库即建文件）")
    void customDatabasePathHonored() {
        Path dbFile = tempDir.resolve("custom/where.db");
        runner.withBean("chatModel", StubChatModel.class).withPropertyValues("agentassert4j.database=" + dbFile).run(context -> assertTrue(dbFile.toFile().exists(), "initialize 必须落出库文件"));
    }

    @Test
    @DisplayName("用户自带存储与录制器时优先复用，不重复建")
    void userProvidedBeansPreferred() {
        SqliteStorageRepository userStorage = new SqliteStorageRepository(tempDbPath());
        userStorage.initialize();
        InteractionRecorder userRecorder = new InteractionRecorder(userStorage, RecorderConfig.defaults());
        userRecorder.start();
        // destroy 推断只认 close/shutdown，不认 stop()——录制器必须显式声明，
        // 否则关停后 flush 线程会在存储 close 后重开连接，锁住库文件
        runner.withBean("userStorage", SqliteStorageRepository.class, () -> userStorage, bd -> bd.setDestroyMethodName("close")).withBean("userRecorder", InteractionRecorder.class, () -> userRecorder, bd -> bd.setDestroyMethodName("stop")).run(context -> {
            assertSame(userRecorder, context.getBean(InteractionRecorder.class));
            assertSame(userStorage, context.getBean(StorageRepository.class));
            assertEquals(1, context.getBeanNamesForType(InteractionRecorder.class).length);
        });
    }
}
