package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.InteractionRecord;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BatchWriteHandlerTest {

    private InteractionRecord createRecord(String id) {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId(id);
        record.setTimestamp(System.currentTimeMillis());
        return record;
    }

    @Test
    void onEvent_nullRecord_skipped() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(10).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

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
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

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
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

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
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

        // 添加 3 条（maxBufferSize=2，第 3 条丢弃）
        for (int i = 0; i < 3; i++) {
            InteractionEvent event = new InteractionEvent();
            event.setRecord(createRecord("r" + i));
            handler.onEvent(event, i, i == 2);
        }

        // 第 3 条被丢弃，但 flush 了前 2 条
        assertEquals(2, repo.getStore().size());
        assertEquals(1, handler.getDroppedCount());
    }

    @Test
    void flush_writeFailure_incrementsFailedCount() {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        repo.setThrowOnSave(true);
        RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

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
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

        handler.flush(); // 不应该抛异常

        assertEquals(0, repo.getStore().size());
        assertEquals(0, handler.getWrittenCount());
    }

    @Test
    void flushScheduler_startsAndStops() throws Exception {
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(100).build();
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

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
        BatchWriteHandler handler = new BatchWriteHandler(repo, config);

        // flushIntervalMs=0 不启动调度器
        handler.startFlushScheduler(0);
        handler.stopFlushScheduler(); // 应该不抛异常
    }
}
