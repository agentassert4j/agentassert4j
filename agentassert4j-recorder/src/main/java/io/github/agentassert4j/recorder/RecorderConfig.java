package io.github.agentassert4j.recorder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 录制器配置 — 控制批量写入行为和数据脱敏策略。
 *
 * <p>所有字段带安全默认值，构造后不可变（退化不中断）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
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
     * Disruptor RingBuffer 大小（非 2 的幂在构造时向上钳位到最近的 2 的幂）
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
    /**
     * 采集门开关：true（默认）时全量录制——任务链的完整性优先于流量卫生，
     * 链条终点（最终回答组装）往往正是纯文本调用；false 时未声明且无可见
     * 工具调用的纯对话被过滤（超大流量场景的量级卫生选项），过滤量独立
     * 计数并告警。声明了 invocationId/templateId 或带可见 toolCalls 的调用不受
     * 本开关影响，一律录制
     */
    private final boolean recordUndeclaredChat;
    /**
     * 录制总开关：false 时录制器不启动管道、不消费任何记录——生产打包形态
     * 的门（发布后的正常运行不录制，需要取证时临时打开）。默认 true。
     * Spring starter 另有同键条件装配（agentassert4j.enabled），两层防线语义一致
     */
    private final boolean enabled;
    /**
     * 应用级默认 invocationId：记录未声明且无工具调用时，以此身份作为
     * 业务声明锚点（单技能应用零代码即得跨提示词编辑的稳定身份）。
     * 空串 = 无默认（默认值）；显式 per-call 声明（RecordingContext）优先级更高
     */
    private final String defaultInvocationId;

    private RecorderConfig(Builder builder) {
        // 钳位：batchSize/maxBufferSize <= 0（如意图立即刷盘的 0 配置）会让
        // 超限判断恒真、全部记录走丢弃分支——下限 1 保住配置意图
        this.batchSize = Math.max(1, builder.batchSize);
        this.flushIntervalMs = builder.flushIntervalMs;
        // 钳位：maxBufferSize < batchSize 时批量阈值永远达不到，超限记录会被
        // 持续丢弃而非攒批刷盘——按较大者执行
        this.maxBufferSize = Math.max(Math.max(1, builder.maxBufferSize), this.batchSize);
        // 钳位：Disruptor RingBuffer 要求 2 的幂，非幂值原本要到 start() 才抛
        // IllegalArgumentException——向上取整把失败前移到构造期
        this.ringBufferSize = clampToPowerOfTwo(builder.ringBufferSize);
        this.sensitiveFields = Collections.unmodifiableList(new ArrayList<>(builder.sensitiveFields));
        this.sanitizeStrategy = builder.sanitizeStrategy;
        this.sanitizeUserInput = builder.sanitizeUserInput;
        this.sanitizeModelResponse = builder.sanitizeModelResponse;
        this.recordUndeclaredChat = builder.recordUndeclaredChat;
        this.enabled = builder.enabled;
        this.defaultInvocationId = builder.defaultInvocationId;
    }

    /**
     * 返回默认配置：
     * batchSize=100, flushIntervalMs=5000, maxBufferSize=500,
     * ringBufferSize=16384, sanitizeStrategy=MASK, sanitizeUserInput/modelResponse=false,
     * recordUndeclaredChat=true（全量录制）, enabled=true
     */
    public static RecorderConfig defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 向上取整到不小于 value 的最小 2 的幂（下限 1，上限 2^30 防 int 溢出）。
     */
    private static int clampToPowerOfTwo(int value) {
        int v = Math.max(1, Math.min(value, 1 << 30));
        if (v <= 1) {
            return 1;
        }
        int rounded = Integer.highestOneBit(v - 1) << 1;
        return Math.min(rounded, 1 << 30);
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

    public boolean isRecordUndeclaredChat() {
        return recordUndeclaredChat;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getDefaultInvocationId() {
        return defaultInvocationId;
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
        private boolean recordUndeclaredChat = true;
        private boolean enabled = true;
        private String defaultInvocationId = "";

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
            this.sensitiveFields = sensitiveFields != null ? new ArrayList<>(sensitiveFields) : new ArrayList<>();
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

        public Builder recordUndeclaredChat(boolean recordUndeclaredChat) {
            this.recordUndeclaredChat = recordUndeclaredChat;
            return this;
        }

        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        public Builder defaultInvocationId(String defaultInvocationId) {
            this.defaultInvocationId = defaultInvocationId != null ? defaultInvocationId : "";
            return this;
        }

        public RecorderConfig build() {
            return new RecorderConfig(this);
        }
    }
}
