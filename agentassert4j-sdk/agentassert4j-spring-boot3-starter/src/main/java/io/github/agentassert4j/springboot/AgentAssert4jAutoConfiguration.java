package io.github.agentassert4j.springboot;

import io.github.agentassert4j.recorder.InteractionRecorder;
import io.github.agentassert4j.recorder.RecorderConfig;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.springai1.RecordingChatModel;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot 3 自动装配
 *
 * <p>装配内容：SQLite 存储（建库建表）→ 录制器（启动 Disruptor 管道）→
 * BeanPostProcessor 把容器内所有 {@link ChatModel} 包上
 * {@link RecordingChatModel}。用户自带 StorageRepository / InteractionRecorder
 * Bean 时优先复用（自带录制器需自行 start）。</p>
 *
 * <p>退出条件：classpath 无 spring-ai（{@code ConditionalOnClass} 静默退出）或
 * {@code agentassert4j.enabled=false}。</p>
 *
 * <p><b>启动期失败语义（有意决策）</b>：存储初始化失败会中断宿主应用启动——
 * 录制框架静默失效比启动失败更危险（使用者以为在录制实际没有）。不接受该
 * 语义的环境用 {@code agentassert4j.enabled=false} 显式关闭。</p>
 *
 * <p>版本契约：面向 Spring Boot 3.4+ / Spring AI 1.x；Boot 与 Spring AI 的版本
 * 由用户应用自带，starter 不锁定不传递。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@AutoConfiguration
@ConditionalOnClass(ChatModel.class)
@ConditionalOnProperty(prefix = "agentassert4j", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AgentAssert4jProperties.class)
public class AgentAssert4jAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(StorageRepository.class)
    public SqliteStorageRepository agentAssert4jStorageRepository(AgentAssert4jProperties properties) {
        SqliteStorageRepository repository = new SqliteStorageRepository(properties.getDatabase());
        repository.initialize();
        return repository;
    }

    @Bean(destroyMethod = "stop")
    @ConditionalOnMissingBean(InteractionRecorder.class)
    public InteractionRecorder agentAssert4jInteractionRecorder(StorageRepository repository, AgentAssert4jProperties properties) {
        InteractionRecorder recorder = new InteractionRecorder(repository, RecorderConfig.builder().defaultInvocationId(properties.getInvocationId()).build());
        recorder.start();
        return recorder;
    }

    /**
     * ChatModel 包装器必须 static：BeanPostProcessor 需在本配置类实例化之前注册，
     * 避免容器对过早初始化的告警与装配顺序问题。
     */
    @Bean
    public static RecordingChatModelPostProcessor agentAssert4jChatModelPostProcessor(ObjectProvider<InteractionRecorder> recorder) {
        return new RecordingChatModelPostProcessor(recorder);
    }

    /**
     * 把容器内每个 ChatModel 包上旁路录制装饰器；已包装的不重复包。
     * 录制器延迟到首个 ChatModel 包装时才解析（getObject），保持容器启动顺序干净。
     */
    static final class RecordingChatModelPostProcessor implements BeanPostProcessor {

        private final ObjectProvider<InteractionRecorder> recorder;

        RecordingChatModelPostProcessor(ObjectProvider<InteractionRecorder> recorder) {
            this.recorder = recorder;
        }

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof ChatModel && !(bean instanceof RecordingChatModel)) {
                return RecordingChatModel.wrap((ChatModel) bean, recorder.getObject());
            }
            return bean;
        }
    }
}
