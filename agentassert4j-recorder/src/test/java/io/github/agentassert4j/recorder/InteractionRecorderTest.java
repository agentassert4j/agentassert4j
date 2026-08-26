package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.InteractionRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionRecorderTest {

    private InMemoryStorageRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryStorageRepository();
    }

    @AfterEach
    void tearDown() {
        // 确保每个测试后 recorder 被关闭
    }

    private InteractionRecord createRecord(String id) {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId(id);
        record.setTimestamp(System.currentTimeMillis());
        record.setTemplateHash("hash-" + id);
        return record;
    }

    @Test
    void constructor_nullRepository_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new InteractionRecorder(null));
    }

    @Test
    void startStop_lifecycle() {
        InteractionRecorder recorder = new InteractionRecorder(repo);

        assertFalse(recorder.isStarted());

        recorder.start();
        assertTrue(recorder.isStarted());

        recorder.stop();
        assertFalse(recorder.isStarted());
    }

    @Test
    void start_idempotent() {
        InteractionRecorder recorder = new InteractionRecorder(repo);
        recorder.start();
        recorder.start(); // 第二次调用不抛异常
        assertTrue(recorder.isStarted());
        recorder.stop();
    }

    @Test
    void stop_idempotent() {
        InteractionRecorder recorder = new InteractionRecorder(repo);
        recorder.start();
        recorder.stop();
        recorder.stop(); // 第二次调用不抛异常
        assertFalse(recorder.isStarted());
    }

    @Test
    void intercept_singleRecord_writtenAfterStop() throws Exception {
        // 使用小的 batchSize 确保快速 flush
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(1)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.intercept(createRecord("r1"));

        // 等待 Disruptor 异步处理
        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        assertEquals("r1", repo.getStore().get(0).getRecordId());
        assertEquals(1, recorder.getRecordedCount());
    }

    @Test
    void intercept_multipleRecords_allWritten() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(5)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        for (int i = 0; i < 10; i++) {
            recorder.intercept(createRecord("r" + i));
        }

        Thread.sleep(300);
        recorder.stop();

        assertEquals(10, repo.getStore().size());
        assertEquals(10, recorder.getRecordedCount());
    }

    @Test
    void intercept_beforeStart_ignored() {
        InteractionRecorder recorder = new InteractionRecorder(repo);
        recorder.intercept(createRecord("r1"));

        assertEquals(0, recorder.getRecordedCount());
    }

    @Test
    void intercept_nullRecord_ignored() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(1)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.intercept(null);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(0, recorder.getRecordedCount());
        assertTrue(repo.getStore().isEmpty());
    }

    @Test
    void record_delegatesToIntercept() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(1)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.record(createRecord("r1"));

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
    }

    @Test
    void intercept_withSanitization_masksSensitiveData() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(1)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .sensitiveFields(java.util.Arrays.asList("password"))
                .sanitizeStrategy(SanitizeStrategy.MASK)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord record = createRecord("r1");
        record.setUserInput("ignore");
        recorder.intercept(record);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        // 原始 record 不被修改
        assertEquals("ignore", record.getUserInput());
    }

    @Test
    void statistics_afterMultipleRecords() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(3)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        for (int i = 0; i < 5; i++) {
            recorder.intercept(createRecord("r" + i));
        }

        Thread.sleep(300);
        recorder.stop();

        assertEquals(5, recorder.getRecordedCount());
        assertEquals(0, recorder.getDroppedCount());
        assertTrue(recorder.getWrittenCount() >= 5);
    }

    @Test
    void statistics_initialValues() {
        InteractionRecorder recorder = new InteractionRecorder(repo);

        assertEquals(0, recorder.getRecordedCount());
        assertEquals(0, recorder.getDroppedCount());
        assertEquals(0, recorder.getWrittenCount());
        assertEquals(0, recorder.getFailedCount());
        assertFalse(recorder.isStarted());
    }

    @Test
    void flush_manualFlush_writesBufferedRecords() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(100) // 大 batchSize，不自动 flush
                .flushIntervalMs(60000) // 长间隔，不自动 flush
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.intercept(createRecord("r1"));
        recorder.intercept(createRecord("r2"));

        Thread.sleep(100);
        // 还没有自动 flush
        // 手动 flush
        recorder.flush();
        Thread.sleep(100);

        assertEquals(2, repo.getStore().size());
        recorder.stop();
    }

    @Test
    void intercept_blankRecordId_getsUuidAssigned() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(1)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord record = createRecord(null);
        recorder.intercept(record);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        String generated = repo.getStore().get(0).getRecordId();
        assertNotNull(generated);
        assertFalse(generated.isEmpty(), "空 record_id 必须被兜底为 UUID（INSERT OR IGNORE 防重放的前提）");
        assertFalse(generated.equals(createRecord("x").getRecordId()));
    }

    @Test
    void intercept_seqStrictlyMonotonic() throws Exception {
        RecorderConfig config = RecorderConfig.builder()
                .batchSize(2)
                .flushIntervalMs(100)
                .ringBufferSize(1024)
                .build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        for (int i = 0; i < 5; i++) {
            recorder.intercept(createRecord("seq-" + i));
        }

        Thread.sleep(300);
        recorder.stop();

        List<Long> seqs = repo.getStore().stream()
                .map(InteractionRecord::getSeq)
                .sorted()
                .toList();
        assertEquals(5, seqs.size());
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1),
                    "同录制器内 seq 必须严格单调：(session_id, seq) 是确定性排序键");
        }
    }
}
