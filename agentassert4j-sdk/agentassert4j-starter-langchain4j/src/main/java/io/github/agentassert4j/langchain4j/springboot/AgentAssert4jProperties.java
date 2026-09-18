package io.github.agentassert4j.langchain4j.springboot;

import io.github.agentassert4j.recorder.SanitizeStrategy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * AgentAssert4j Spring Boot 配置项（前缀 {@code agentassert4j}）。
 *
 * <p>属性树镜像 agentassert4j.json 的配置命名（storage.url / llm.*），录制域全旋钮
 * 经 recorder 段暴露——同一旋钮跨通道同语义同形。默认值与
 * {@code RecorderConfig.builder()} 的代码默认一致（钳位语义由 recorder 模块统一实现）。
 * 与 Spring AI starter 同前缀同语义：混用两个框架的应用一份配置同时驱动两侧录制。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
@ConfigurationProperties(prefix = "agentassert4j")
public class AgentAssert4jProperties {

    /**
     * 总开关；false 时自动装配整体退出，不创建任何 Bean、不包装模型。
     */
    private boolean enabled = true;

    private final Storage storage = new Storage();

    private final Recorder recorder = new Recorder();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Storage getStorage() {
        return storage;
    }

    public Recorder getRecorder() {
        return recorder;
    }

    /**
     * 存储位置（与 agentassert4j.json 的 storage.url 同名同义；~ 自动展开）。
     */
    public static class Storage {

        private String url = "~/.agentassert4j/agentassert4j.db";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }
    }

    /**
     * 录制域旋钮（与 {@code RecorderConfig.builder()} 一一对应；钳位语义在
     * recorder 模块统一实现，这里不重复校验）。
     */
    public static class Recorder {

        /**
         * 应用级默认调用点标签：未声明记录的业务身份锚点（单技能应用一行声明）。
         */
        private String defaultInvocationId = "";
        /**
         * 录制器级默认端点地址（endpoint 列，基线跨部署可比的部署身份）；
         * 多模型 JVM 用逐调用 RecordingContext 声明覆盖。
         */
        private String endpoint;
        private int batchSize = 100;
        private long flushIntervalMs = 5000;
        private int maxBufferSize = 500;
        private int ringBufferSize = 16384;
        private List<String> sensitiveFields = new ArrayList<>();
        private SanitizeStrategy sanitizeStrategy = SanitizeStrategy.MASK;
        private boolean sanitizeUserInput = false;
        private boolean sanitizeModelResponse = false;
        /**
         * 采集门开关：false 时未声明且无可见工具调用的纯对话被过滤（量级卫生选项）
         */
        private boolean recordUndeclaredChat = true;
        /**
         * 录制器开关：false 时管道不启动、录制整体 no-op（自动装配仍在，
         * 与总开关的两层防护语义一致）
         */
        private boolean enabled = true;

        public String getDefaultInvocationId() {
            return defaultInvocationId;
        }

        public void setDefaultInvocationId(String defaultInvocationId) {
            this.defaultInvocationId = defaultInvocationId;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getFlushIntervalMs() {
            return flushIntervalMs;
        }

        public void setFlushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
        }

        public int getMaxBufferSize() {
            return maxBufferSize;
        }

        public void setMaxBufferSize(int maxBufferSize) {
            this.maxBufferSize = maxBufferSize;
        }

        public int getRingBufferSize() {
            return ringBufferSize;
        }

        public void setRingBufferSize(int ringBufferSize) {
            this.ringBufferSize = ringBufferSize;
        }

        public List<String> getSensitiveFields() {
            return sensitiveFields;
        }

        public void setSensitiveFields(List<String> sensitiveFields) {
            this.sensitiveFields = sensitiveFields;
        }

        public SanitizeStrategy getSanitizeStrategy() {
            return sanitizeStrategy;
        }

        public void setSanitizeStrategy(SanitizeStrategy sanitizeStrategy) {
            this.sanitizeStrategy = sanitizeStrategy;
        }

        public boolean isSanitizeUserInput() {
            return sanitizeUserInput;
        }

        public void setSanitizeUserInput(boolean sanitizeUserInput) {
            this.sanitizeUserInput = sanitizeUserInput;
        }

        public boolean isSanitizeModelResponse() {
            return sanitizeModelResponse;
        }

        public void setSanitizeModelResponse(boolean sanitizeModelResponse) {
            this.sanitizeModelResponse = sanitizeModelResponse;
        }

        public boolean isRecordUndeclaredChat() {
            return recordUndeclaredChat;
        }

        public void setRecordUndeclaredChat(boolean recordUndeclaredChat) {
            this.recordUndeclaredChat = recordUndeclaredChat;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
