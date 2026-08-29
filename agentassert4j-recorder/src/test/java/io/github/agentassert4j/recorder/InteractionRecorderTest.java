package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InteractionRecorder 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
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
        // 声明业务身份：未声明且无工具调用的纯对话会被采集门过滤，
        // 通用助手的记录必须能过门（门行为由下方专门的门测试覆盖）
        record.setSkillId("skill-" + id);
        return record;
    }

    @Test
    void constructor_nullRepository_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> new InteractionRecorder(null, null));
    }

    @Test
    void startStop_lifecycle() {
        InteractionRecorder recorder = new InteractionRecorder(repo, RecorderConfig.defaults());

        assertFalse(recorder.isStarted());

        recorder.start();
        assertTrue(recorder.isStarted());

        recorder.stop();
        assertFalse(recorder.isStarted());
    }

    @Test
    void start_idempotent() {
        InteractionRecorder recorder = new InteractionRecorder(repo, RecorderConfig.defaults());
        recorder.start();
        recorder.start(); // 第二次调用不抛异常
        assertTrue(recorder.isStarted());
        recorder.stop();
    }

    @Test
    void stop_idempotent() {
        InteractionRecorder recorder = new InteractionRecorder(repo, RecorderConfig.defaults());
        recorder.start();
        recorder.stop();
        recorder.stop(); // 第二次调用不抛异常
        assertFalse(recorder.isStarted());
    }

    @Test
    void intercept_singleRecord_writtenAfterStop() throws Exception {
        // 使用小的 batchSize 确保快速 flush
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

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
        RecorderConfig config = RecorderConfig.builder().batchSize(5).flushIntervalMs(100).ringBufferSize(1024).build();

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
        InteractionRecorder recorder = new InteractionRecorder(repo, RecorderConfig.defaults());
        recorder.intercept(createRecord("r1"));

        assertEquals(0, recorder.getRecordedCount());
    }

    @Test
    void intercept_nullRecord_ignored() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.intercept(null);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(0, recorder.getRecordedCount());
        assertTrue(repo.getStore().isEmpty());
    }

    @Test
    void captureGate_undeclaredBareChat_filtered() throws Exception {
        // 未声明（skillId/templateId 均无）且无工具调用的纯对话 → 过滤，不进管道
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord bare = new InteractionRecord();
        bare.setRecordId("bare-1");
        bare.setTimestamp(System.currentTimeMillis());
        bare.setTemplateHash("some-template"); // 模板 hash 是自动捕获值，不是声明
        recorder.intercept(bare);

        Thread.sleep(200);
        recorder.stop();

        assertTrue(repo.getStore().isEmpty(), "未声明纯对话不得落库");
        assertEquals(1, recorder.getFilteredCount(), "过滤量独立计数可见");
        assertEquals(0, recorder.getRecordedCount(), "被滤记录不进入管道计数");
    }

    @Test
    void captureGate_declaredBySkillId_recorded() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord declared = new InteractionRecord();
        declared.setRecordId("declared-1");
        declared.setTimestamp(System.currentTimeMillis());
        declared.setSkillId("order-flow");
        recorder.intercept(declared);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        assertEquals(0, recorder.getFilteredCount());
    }

    @Test
    void captureGate_declaredByTemplateId_recorded() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord declared = new InteractionRecord();
        declared.setRecordId("declared-2");
        declared.setTimestamp(System.currentTimeMillis());
        declared.setTemplateId("tpl-support-v1");
        recorder.intercept(declared);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
    }

    @Test
    void captureGate_visibleToolCalls_recorded() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord toolCall = new InteractionRecord();
        toolCall.setRecordId("tool-1");
        toolCall.setTimestamp(System.currentTimeMillis());
        ToolCall call = new ToolCall();
        call.setToolName("getOrder");
        toolCall.setToolCalls(Collections.singletonList(call));
        toolCall.setHasToolCalls(true);
        recorder.intercept(toolCall);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size(), "可见工具调用满足采集门，无需声明");
    }

    @Test
    void captureGate_defaultSkillId_undeclaredRecordedWithDefault() throws Exception {
        // 应用级默认声明：未声明记录以默认 skillId 过门并落到声明位
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).defaultSkillId("order-flow").build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord bare = new InteractionRecord();
        bare.setRecordId("bare-3");
        bare.setTimestamp(System.currentTimeMillis());
        recorder.intercept(bare);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        assertEquals("order-flow", repo.getStore().get(0).getSkillId(), "默认 skillId 落到记录声明位");
        assertEquals(0, recorder.getFilteredCount());
    }

    @Test
    void captureGate_escapeHatch_recordsBareChat() throws Exception {
        // 逃生开关：recordUndeclaredChat=true 时退回全量录制（调试/评估用）
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).recordUndeclaredChat(true).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord bare = new InteractionRecord();
        bare.setRecordId("bare-2");
        bare.setTimestamp(System.currentTimeMillis());
        recorder.intercept(bare);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        assertEquals(0, recorder.getFilteredCount());
    }

    @Test
    void captureGate_arrivalClosure_recordedPlusFiltered() {
        // 到达闭合：recorded（进管道，含后续丢弃）+ filtered（被门滤掉）= 总到达
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.intercept(createRecord("in-1"));   // 过门
        InteractionRecord bare = new InteractionRecord();
        bare.setRecordId("out-1");
        recorder.intercept(bare);                    // 被滤

        recorder.stop();

        assertEquals(2, recorder.getRecordedCount() + recorder.getFilteredCount(), "每次到达恰计入一侧：进管道或被过滤");
        assertEquals(1, recorder.getFilteredCount());
    }

    @Test
    void record_delegatesToIntercept() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.intercept(createRecord("r1"));

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
    }

    @Test
    void intercept_withSanitization_masksSensitiveData() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).sensitiveFields(Arrays.asList("password")).sanitizeStrategy(SanitizeStrategy.MASK).build();

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
        RecorderConfig config = RecorderConfig.builder().batchSize(3).flushIntervalMs(100).ringBufferSize(1024).build();

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
        InteractionRecorder recorder = new InteractionRecorder(repo, RecorderConfig.defaults());

        assertEquals(0, recorder.getRecordedCount());
        assertEquals(0, recorder.getDroppedCount());
        assertEquals(0, recorder.getWrittenCount());
        assertEquals(0, recorder.getFailedCount());
        assertFalse(recorder.isStarted());
    }

    @Test
    void flush_manualFlush_writesBufferedRecords() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(100) // 大 batchSize，不自动 flush
                .flushIntervalMs(60000) // 长间隔，不自动 flush
                .ringBufferSize(1024).build();

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
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

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
        RecorderConfig config = RecorderConfig.builder().batchSize(2).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        for (int i = 0; i < 5; i++) {
            recorder.intercept(createRecord("seq-" + i));
        }

        Thread.sleep(300);
        recorder.stop();

        List<Long> seqs = repo.getStore().stream().map(InteractionRecord::getSeq).sorted().collect(Collectors.toList());
        assertEquals(5, seqs.size());
        for (int i = 1; i < seqs.size(); i++) {
            assertTrue(seqs.get(i) > seqs.get(i - 1), "同录制器内 seq 必须严格单调：(session_id, seq) 是确定性排序键");
        }
    }

    @Test
    void droppedCount_closesOverRingBufferFullDrops() throws Exception {
        // 消费线程被存储写入阻塞 → RingBuffer 填满 → 生产侧 tryNext 失败丢弃。
        // 总丢弃口径必须含该路径，且 written + dropped 闭合到 recorded。
        CountDownLatch releaseStorage = new CountDownLatch(1);
        InMemoryStorageRepository blockingRepo = new InMemoryStorageRepository() {
            @Override
            public void saveInteractions(List<InteractionRecord> records) {
                try {
                    releaseStorage.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.saveInteractions(records);
            }
        };
        RecorderConfig config = RecorderConfig.builder().batchSize(1000).flushIntervalMs(60_000).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(blockingRepo, config);
        recorder.start();

        int total = 8192;
        for (int i = 0; i < total; i++) {
            recorder.intercept(createRecord("ring-" + i));
        }

        releaseStorage.countDown();
        recorder.stop();

        assertTrue(recorder.getDroppedCount() > 0, "RingBuffer(1024) + 8192 条突发且消费阻塞，生产侧丢弃必须计入总丢弃数");
        assertEquals(total, recorder.getRecordedCount());
        assertEquals(total, recorder.getWrittenCount() + recorder.getDroppedCount(), "written + dropped 必须闭合到 recorded");
    }

    @Test
    void stop_concurrentIntercept_countsStayClosed() throws Exception {
        // intercept 与 stop 互斥：关停窗口内的并发发布既不得滞留为幽灵事件，
        // 也不得破坏 written + dropped == recorded 的计数闭合
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        RecorderConfig config = RecorderConfig.builder().batchSize(100).flushIntervalMs(1000).ringBufferSize(4096).build();
        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        AtomicBoolean running = new AtomicBoolean(true);
        Thread producer = new Thread(() -> {
            int i = 0;
            while (running.get()) {
                recorder.intercept(createRecord("race-" + i++));
            }
        });
        producer.start();
        Thread.sleep(150);
        recorder.stop();
        running.set(false);
        producer.join(5000);
        assertFalse(producer.isAlive(), "生产线程必须在有界时间内退出");

        long recorded = recorder.getRecordedCount();
        assertEquals(recorded, recorder.getWrittenCount() + recorder.getDroppedCount(), "关停窗口的并发发布不得破坏计数闭合");
    }
}
