package io.github.agentassert4j.langchain4j.springboot;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.github.agentassert4j.langchain4j.RecordingChatModel;
import io.github.agentassert4j.langchain4j.RecordingStreamingChatModel;
import io.github.agentassert4j.recorder.InteractionRecorder;
import io.github.agentassert4j.recorder.RecorderConfig;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.config.ConfigLoader;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * LangChain4j 线的 Spring Boot 3 自动装配
 *
 * <p>装配内容：SQLite 存储（建库建表）→ 录制器（启动 Disruptor 管道）→
 * BeanPostProcessor 把容器内所有 LangChain4j 模型 Bean 包上录制装饰器——
 * 实现阻塞接口的（含混合实现两接口的）包 {@link RecordingChatModel}，
 * 仅实现流式接口的包 {@link RecordingStreamingChatModel}。用户自带
 * StorageRepository / InteractionRecorder Bean 时优先复用（自带录制器需自行 start）。</p>
 *
 * <p>与 Spring AI starter 共存（混架应用）：两边经 ConditionalOnMissingBean 复用
 * 同一 StorageRepository/InteractionRecorder，两个后置处理器各按各自框架的模型
 * 类型包各自的 Bean，互不误包；同一套 agentassert4j.* 配置同时驱动两侧录制。</p>
 *
 * <p>退出条件：classpath 无 langchain4j（ConditionalOnClass 静默退出）或
 * {@code agentassert4j.enabled=false}。</p>
 *
 * <p><b>启动期失败语义（有意决策）</b>：存储初始化失败会中断宿主应用启动——
 * 录制框架静默失效比启动失败更危险（使用者以为在录制实际没有）。不接受该
 * 语义的环境用 {@code agentassert4j.enabled=false} 显式关闭。</p>
 *
 * <p>版本契约：面向 Spring Boot 3 线 / LangChain4j 1.x；两者版本由用户应用自带，
 * starter 不锁定不传递。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(prefix = "agentassert4j", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentAssert4jProperties.class)
public class AgentAssert4jAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(StorageRepository.class)
    public SqliteStorageRepository agentAssert4jStorageRepository(AgentAssert4jProperties properties) {
        SqliteStorageRepository repository = new SqliteStorageRepository(ConfigLoader.expandHome(properties.getStorage().getUrl()));
        repository.initialize();
        return repository;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean(InteractionRecorder.class)
    public InteractionRecorder agentAssert4jInteractionRecorder(StorageRepository repository, AgentAssert4jProperties properties) {
        AgentAssert4jProperties.Recorder recorderProperties = properties.getRecorder();
        RecorderConfig recorderConfig = RecorderConfig.builder().defaultInvocationId(recorderProperties.getDefaultInvocationId()).endpoint(recorderProperties.getEndpoint()).batchSize(recorderProperties.getBatchSize()).flushIntervalMs(recorderProperties.getFlushIntervalMs()).maxBufferSize(recorderProperties.getMaxBufferSize()).ringBufferSize(recorderProperties.getRingBufferSize()).sensitiveFields(recorderProperties.getSensitiveFields()).sanitizeStrategy(recorderProperties.getSanitizeStrategy()).sanitizeUserInput(recorderProperties.isSanitizeUserInput()).sanitizeModelResponse(recorderProperties.isSanitizeModelResponse()).recordUndeclaredChat(recorderProperties.isRecordUndeclaredChat()).enabled(recorderProperties.isEnabled()).build();
        InteractionRecorder recorder = new InteractionRecorder(repository, recorderConfig);
        recorder.start();
        return recorder;
    }

    /**
     * 模型包装器必须 static：BeanPostProcessor 需在本配置类实例化之前注册，
     * 避免容器对过早初始化的告警与装配顺序问题。
     */
    @Bean
    public static RecordingModelPostProcessor agentAssert4jModelPostProcessor(ObjectProvider<InteractionRecorder> recorder) {
        return new RecordingModelPostProcessor(recorder);
    }

    /**
     * 把容器内每个 LangChain4j 模型包上旁路录制装饰器；已包装的不重复包。
     * 录制器延迟到首个模型包装时才解析（getObject），保持容器启动顺序干净。
     */
    static final class RecordingModelPostProcessor implements BeanPostProcessor {

        private final ObjectProvider<InteractionRecorder> recorder;

        RecordingModelPostProcessor(ObjectProvider<InteractionRecorder> recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof RecordingChatModel || bean instanceof RecordingStreamingChatModel) {
                return bean;
            }
            if (bean instanceof ChatModel) {
                return RecordingChatModel.wrap((ChatModel) bean, recorder.getObject());
            }
            if (bean instanceof StreamingChatModel) {
                return RecordingStreamingChatModel.wrapStreaming((StreamingChatModel) bean, recorder.getObject());
            }
            return bean;
        }
    }
}
