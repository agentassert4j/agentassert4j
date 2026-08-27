package io.github.agentassert4j.recorder;

import io.github.agentassert4j.algorithm.DeterministicSkillGrouper;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BatchWriteHandler 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class BatchWriteHandlerTest {

    private InteractionRecord createRecord(String id) {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId(id);
        record.setTimestamp(System.currentTimeMillis());
        return record;
    }

    @Nested
    @DisplayName("落库前派生字段补全")
    class Enrichment {

        private InteractionRecord enrichableRecord(String id) {
            InteractionRecord record = new InteractionRecord();
            record.setRecordId(id);
            record.setTimestamp(System.currentTimeMillis());
            record.setSkillId("skill-1");
            record.setSessionId("session-1");
            record.setUserInput("查订单 ORD-001");
            record.setModelResponse("{\"data\":{\"orderId\":\"ORD-001\"}}");
            return record;
        }

        @Test
        @DisplayName("缺失的 groupKey/指纹在落库前补全")
        void flush_enrichesMissingDerivedFields() {
            InMemoryStorageRepository repo = new InMemoryStorageRepository();
            RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
            BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

            InteractionEvent event = new InteractionEvent();
            event.setRecord(enrichableRecord("r1"));
            handler.onEvent(event, 0, true);

            InteractionRecord saved = repo.getStore().get(0);
            assertEquals(DeterministicSkillGrouper.group(saved).getGroupKey(), saved.getGroupKey(), "groupKey 必须由分组器补全，存储与画像才可关联");
            assertNotNull(saved.getFingerprint(), "指纹快照必须落库前提取");
            assertEquals("application/json", saved.getFingerprint().getOutputContentType());
        }

        @Test
        @DisplayName("上游显式设置的派生字段不被覆盖")
        void flush_preservesExplicitlySetDerivedFields() {
            InMemoryStorageRepository repo = new InMemoryStorageRepository();
            RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
            BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

            InteractionRecord record = enrichableRecord("r1");
            record.setGroupKey("custom-group-key");
            DeterministicFingerprint preset = new DeterministicFingerprint();
            preset.setOutputContentType("text/plain");
            preset.setTextLengthMagnitude(7);
            record.setFingerprint(preset);

            InteractionEvent event = new InteractionEvent();
            event.setRecord(record);
            handler.onEvent(event, 0, true);

            InteractionRecord saved = repo.getStore().get(0);
            assertEquals("custom-group-key", saved.getGroupKey());
            assertEquals("text/plain", saved.getFingerprint().getOutputContentType());
            assertEquals(7, saved.getFingerprint().getTextLengthMagnitude());
        }

        @Test
        @DisplayName("无 skillId 的记录回充分组派生 id（skill_id 列 NOT NULL，缺失会整批 INSERT 失败）")
        void flush_noSkillId_backfilledFromGrouper() {
            InMemoryStorageRepository repo = new InMemoryStorageRepository();
            RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
            BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

            InteractionRecord record = createRecord("r1");

            InteractionEvent event = new InteractionEvent();
            event.setRecord(record);
            handler.onEvent(event, 0, true);

            InteractionRecord saved = repo.getStore().get(0);
            assertNotNull(saved.getSkillId(), "skillId 必须回充分组器派生 id");
            assertEquals(DeterministicSkillGrouper.group(saved).getSkillId(), saved.getSkillId());
            assertNotNull(saved.getGroupKey());
            assertNotNull(saved.getFingerprint());
        }
    }

    @Test
    void onEvent_nullRecord_skipped() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(10).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        InteractionEvent event = new InteractionEvent();
        event.setRecord(null);

        handler.onEvent(event, 0, true);

        assertEquals(0, handler.getBufferSize());
        assertTrue(repo.getStore().isEmpty());
    }

    @Test
    void onEvent_singleEvent_endOfBatch_flushes() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        InteractionEvent event = new InteractionEvent();
        event.setRecord(createRecord("r1"));

        handler.onEvent(event, 0, true);

        assertEquals(1, repo.getStore().size());
        assertEquals("r1", repo.getStore().get(0).getRecordId());
        assertEquals(1, handler.getWrittenCount());
    }

    @Test
    void onEvent_batchSize_flushesAtThreshold() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(3).maxBufferSize(100).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        // 添加 2 条，endOfBatch=false，不 flush
        for (int i = 0; i < 2; i++) {
            InteractionEvent event = new InteractionEvent();
            event.setRecord(createRecord("r" + i));
            handler.onEvent(event, i, false);
        }
        assertEquals(0, repo.getStore().size());

        // 添加第 3 条，达到 batchSize=3，触发 flush
        InteractionEvent event = new InteractionEvent();
        event.setRecord(createRecord("r2"));
        handler.onEvent(event, 2, false);

        assertEquals(3, repo.getStore().size());
        assertEquals(3, handler.getWrittenCount());
    }

    @Test
    void onEvent_maxBufferSizeExceeded_dropsRecord() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        // batchSize 很大，maxBufferSize=2
        RecorderConfig config = RecorderConfig.builder().batchSize(100).maxBufferSize(2).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        // 添加 3 条（maxBufferSize=2，第 3 条丢弃）
        for (int i = 0; i < 3; i++) {
            InteractionEvent event = new InteractionEvent();
            event.setRecord(createRecord("r" + i));
            handler.onEvent(event, i, i == 2);
        }

        // maxBufferSize 已被钳位到 batchSize：错配不得演变为持续丢弃，3 条全部落库
        assertEquals(3, repo.getStore().size());
        assertEquals(0, handler.getDroppedCount());
    }

    @Test
    void flush_writeFailure_incrementsFailedCount() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        repo.setThrowOnSave(true);
        RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        InteractionEvent event = new InteractionEvent();
        event.setRecord(createRecord("r1"));
        handler.onEvent(event, 0, true);

        assertEquals(1, handler.getFailedCount());
        assertEquals(0, handler.getWrittenCount());
    }

    @Test
    void flush_emptyBuffer_noop() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        handler.flush(); // 不应该抛异常

        assertEquals(0, repo.getStore().size());
        assertEquals(0, handler.getWrittenCount());
    }

    @Test
    void flushScheduler_startsAndStops() throws Exception {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        handler.startFlushScheduler(100); // 100ms interval

        // 添加一条记录，不 endOfBatch，所以不 flush
        InteractionEvent event = new InteractionEvent();
        event.setRecord(createRecord("r1"));
        handler.onEvent(event, 0, false);

        assertEquals(0, repo.getStore().size());

        // 等待 flush 调度器触发
        Thread.sleep(300);

        handler.stopFlushScheduler();

        // 应该已经通过定时 flush 写入了
        assertTrue(repo.getStore().size() >= 1);
    }

    @Test
    void flushScheduler_zeroInterval_doesNotStart() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        // flushIntervalMs=0 不启动调度器
        handler.startFlushScheduler(0);
        handler.stopFlushScheduler(); // 应该不抛异常
    }

    @Test
    void onEvent_misconfiguredBuffers_selfHealToBatchSize() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(100).maxBufferSize(1).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config, new AtomicLong(), new AtomicLong(), new AtomicLong());

        InteractionEvent first = new InteractionEvent();
        first.setRecord(createRecord("r1"));
        handler.onEvent(first, 0, false); // 入缓冲；未到批量阈值且非批尾 → 不 flush

        InteractionEvent second = new InteractionEvent();
        second.setRecord(createRecord("r2"));
        handler.onEvent(second, 1, true); // 批尾 flush

        // maxBufferSize 钳位到 batchSize（100）：错配自愈，不得丢弃 r2
        assertEquals(0, handler.getDroppedCount());
        assertEquals(2, handler.getWrittenCount());
        assertTrue(repo.getStore().stream().anyMatch(r -> "r2".equals(r.getRecordId())), "错配配置下的记录同样必须落库");
    }
}
