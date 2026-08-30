package io.github.agentassert4j.recorder;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RecorderConfig 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class RecorderConfigTest {

    @Test
    void defaults_returnsConfigWithExpectedValues() {
        RecorderConfig config = RecorderConfig.defaults();

        assertEquals(100, config.getBatchSize());
        assertEquals(5000, config.getFlushIntervalMs());
        assertEquals(500, config.getMaxBufferSize());
        assertEquals(16384, config.getRingBufferSize());
        assertTrue(config.getSensitiveFields().isEmpty());
        assertEquals(SanitizeStrategy.MASK, config.getSanitizeStrategy());
        assertFalse(config.isSanitizeUserInput());
        assertFalse(config.isSanitizeModelResponse());
        // 采集门默认全量录制：任务链完整性优先于流量卫生
        assertTrue(config.isRecordUndeclaredChat());
        // 总开关默认开启：接入即录制
        assertTrue(config.isEnabled());
    }

    @Test
    void builder_disabledFlag_switchesOff() {
        assertFalse(RecorderConfig.builder().enabled(false).build().isEnabled());
        assertTrue(RecorderConfig.builder().enabled(true).build().isEnabled());
    }

    @Test
    void builder_maxBufferSizeBelowBatchSize_clampedToBatchSize() {
        // 错配时按较大者执行：maxBufferSize < batchSize 会持续丢弃而非攒批刷盘
        RecorderConfig config = RecorderConfig.builder().batchSize(1000).maxBufferSize(100).build();

        assertEquals(1000, config.getMaxBufferSize());
    }

    @Test
    void builder_batchSizeZeroOrNegative_clampedToOne() {
        // batchSize<=0 会让超限判断恒真、全部记录走丢弃分支——下限 1 保住「尽快刷盘」的配置意图
        assertEquals(1, RecorderConfig.builder().batchSize(0).build().getBatchSize());
        assertEquals(1, RecorderConfig.builder().batchSize(-5).build().getBatchSize());
    }

    @Test
    void builder_ringBufferSize_roundedUpToPowerOfTwo() {
        // Disruptor RingBuffer 要求 2 的幂：非幂值向上取整，把失败前移到构造期而非 start()
        assertEquals(1024, RecorderConfig.builder().ringBufferSize(1000).build().getRingBufferSize());
        assertEquals(4, RecorderConfig.builder().ringBufferSize(3).build().getRingBufferSize());
        assertEquals(16384, RecorderConfig.builder().ringBufferSize(16384).build().getRingBufferSize());
        assertEquals(1, RecorderConfig.builder().ringBufferSize(-5).build().getRingBufferSize());
        assertEquals(1 << 30, RecorderConfig.builder().ringBufferSize(Integer.MAX_VALUE).build().getRingBufferSize());
    }

    @Test
    void builder_customValues() {
        RecorderConfig config = RecorderConfig.builder().batchSize(50).flushIntervalMs(3000).maxBufferSize(200).ringBufferSize(8192).sensitiveFields(Arrays.asList("password", "token")).sanitizeStrategy(SanitizeStrategy.HASH).sanitizeUserInput(true).sanitizeModelResponse(true).build();

        assertEquals(50, config.getBatchSize());
        assertEquals(3000, config.getFlushIntervalMs());
        assertEquals(200, config.getMaxBufferSize());
        assertEquals(8192, config.getRingBufferSize());
        assertEquals(2, config.getSensitiveFields().size());
        assertEquals(SanitizeStrategy.HASH, config.getSanitizeStrategy());
        assertTrue(config.isSanitizeUserInput());
        assertTrue(config.isSanitizeModelResponse());
    }

    @Test
    void builder_sensitiveFieldsNull_returnsEmptyList() {
        RecorderConfig config = RecorderConfig.builder().sensitiveFields(null).build();

        assertNotNull(config.getSensitiveFields());
        assertTrue(config.getSensitiveFields().isEmpty());
    }

    @Test
    void builder_sanitizeStrategyNull_defaultsToMask() {
        RecorderConfig config = RecorderConfig.builder().sanitizeStrategy(null).build();

        assertEquals(SanitizeStrategy.MASK, config.getSanitizeStrategy());
    }

    @Test
    void sensitiveFields_isUnmodifiable() {
        RecorderConfig config = RecorderConfig.builder().sensitiveFields(Arrays.asList("password")).build();

        List<String> fields = config.getSensitiveFields();
        assertThrows(UnsupportedOperationException.class, () -> fields.add("newField"));
    }

    @Test
    void builder_modifyingOriginalList_doesNotAffectConfig() {
        List<String> mutable = new ArrayList<>(Arrays.asList("password"));
        RecorderConfig config = RecorderConfig.builder().sensitiveFields(mutable).build();

        mutable.add("newField");

        assertEquals(1, config.getSensitiveFields().size());
    }

    @Test
    void defaultInvocationId_emptyByDefault_builderSetsAndNullNormalizes() {
        assertEquals("", RecorderConfig.defaults().getDefaultInvocationId());
        assertEquals("order-flow", RecorderConfig.builder().defaultInvocationId("order-flow").build().getDefaultInvocationId());
        assertEquals("", RecorderConfig.builder().defaultInvocationId(null).build().getDefaultInvocationId());
    }
}
