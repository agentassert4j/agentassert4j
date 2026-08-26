package io.github.agentassert4j.recorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 录制器配置 — 控制批量写入行为和数据脱敏策略。
 *
 * <p>所有字段带安全默认值，构造后不可变（R10 退化不中断）。</p>
 */
public final class RecorderConfig {

    /**
     * 批量写入阈值：buffer 达到此数量触发 flush
     */
    private final int batchSize;
    /**
     * 定时 flush 间隔（毫秒），0 表示禁用定时 flush
     */
    private final long flushIntervalMs;
    /**
     * buffer 上限，防止 OOM。超限后新记录丢弃
     */
    private final int maxBufferSize;
    /**
     * Disruptor RingBuffer 大小（必须是 2 的幂）
     */
    private final int ringBufferSize;
    /**
     * 敏感字段名称列表（忽略大小写匹配）
     */
    private final List<String> sensitiveFields;
    /**
     * 脱敏策略
     */
    private final SanitizeStrategy sanitizeStrategy;
    /**
     * 是否脱敏 userInput（默认 false，因为影响回归重放）
     */
    private final boolean sanitizeUserInput;
    /**
     * 是否脱敏 modelResponse（默认 false）
     */
    private final boolean sanitizeModelResponse;

    private RecorderConfig(Builder builder) {
        this.batchSize = builder.batchSize;
        this.flushIntervalMs = builder.flushIntervalMs;
        this.maxBufferSize = builder.maxBufferSize;
        this.ringBufferSize = builder.ringBufferSize;
        this.sensitiveFields = Collections.unmodifiableList(new ArrayList<>(builder.sensitiveFields));
        this.sanitizeStrategy = builder.sanitizeStrategy;
        this.sanitizeUserInput = builder.sanitizeUserInput;
        this.sanitizeModelResponse = builder.sanitizeModelResponse;
    }

    /**
     * 返回默认配置：
     * batchSize=100, flushIntervalMs=5000, maxBufferSize=500,
     * ringBufferSize=16384, sanitizeStrategy=MASK, sanitizeUserInput/modelResponse=false
     */
    public static RecorderConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public int getBatchSize() {
        return batchSize;
    }

    public long getFlushIntervalMs() {
        return flushIntervalMs;
    }

    public int getMaxBufferSize() {
        return maxBufferSize;
    }

    public int getRingBufferSize() {
        return ringBufferSize;
    }

    public List<String> getSensitiveFields() {
        return sensitiveFields;
    }

    public SanitizeStrategy getSanitizeStrategy() {
        return sanitizeStrategy;
    }

    public boolean isSanitizeUserInput() {
        return sanitizeUserInput;
    }

    public boolean isSanitizeModelResponse() {
        return sanitizeModelResponse;
    }

    public static final class Builder {
        private int batchSize = 100;
        private long flushIntervalMs = 5000;
        private int maxBufferSize = 500;
        private int ringBufferSize = 16384;
        private List<String> sensitiveFields = new ArrayList<>();
        private SanitizeStrategy sanitizeStrategy = SanitizeStrategy.MASK;
        private boolean sanitizeUserInput = false;
        private boolean sanitizeModelResponse = false;

        private Builder() {
        }

        public Builder batchSize(int batchSize) {
            this.batchSize = batchSize;
            return this;
        }

        public Builder flushIntervalMs(long flushIntervalMs) {
            this.flushIntervalMs = flushIntervalMs;
            return this;
        }

        public Builder maxBufferSize(int maxBufferSize) {
            this.maxBufferSize = maxBufferSize;
            return this;
        }

        public Builder ringBufferSize(int ringBufferSize) {
            this.ringBufferSize = ringBufferSize;
            return this;
        }

        public Builder sensitiveFields(List<String> sensitiveFields) {
            this.sensitiveFields = sensitiveFields != null
                    ? new ArrayList<>(sensitiveFields)
                    : new ArrayList<>();
            return this;
        }

        public Builder sanitizeStrategy(SanitizeStrategy sanitizeStrategy) {
            this.sanitizeStrategy = sanitizeStrategy != null ? sanitizeStrategy : SanitizeStrategy.MASK;
            return this;
        }

        public Builder sanitizeUserInput(boolean sanitizeUserInput) {
            this.sanitizeUserInput = sanitizeUserInput;
            return this;
        }

        public Builder sanitizeModelResponse(boolean sanitizeModelResponse) {
            this.sanitizeModelResponse = sanitizeModelResponse;
            return this;
        }

        public RecorderConfig build() {
            return new RecorderConfig(this);
        }
    }
}
