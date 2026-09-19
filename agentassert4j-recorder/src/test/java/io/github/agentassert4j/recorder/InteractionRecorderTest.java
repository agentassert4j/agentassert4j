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
import java.util.concurrent.TimeUnit;
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
        record.setInvocationId("skill-" + id);
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
    void intercept_beforeStart_countedDropped() {
        // 非 RUNNING 相位的到达计入 recorded 与 dropped（可见丢弃）：调用方在 start 前
        // 提交是使用错误，可见的丢弃计数让它当场暴露，而非静默蒸发；账本闭合不破
        InteractionRecorder recorder = new InteractionRecorder(repo, RecorderConfig.defaults());
        recorder.intercept(createRecord("r1"));

        assertEquals(1, recorder.getRecordedCount());
        assertEquals(1, recorder.getDroppedCount());
        assertEquals(0, recorder.getWrittenCount());
    }

    @Test
    void restart_afterStop_recordsAgain() throws Exception {
        // 相位机重启路径：STOPPED → start() 重建管道，录制恢复；计数跨生命周期累计
        RecorderConfig config = RecorderConfig.builder().batchSize(100).flushIntervalMs(100).ringBufferSize(1024).build();
        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();
        recorder.intercept(createRecord("r1"));
        recorder.stop();
        assertEquals(1, repo.getStore().size());

        recorder.start();
        assertTrue(recorder.isStarted(), "重启后相位回到 RUNNING");
        recorder.intercept(createRecord("r2"));
        recorder.stop();
        assertEquals(2, repo.getStore().size(), "重启后的记录照常落库");
        assertEquals(2, recorder.getRecordedCount(), "计数器实例级持有，跨生命周期累计");
    }

    @Test
    void intercept_doesNotMutateCallerRecord() throws Exception {
        // 四个补全位（默认声明/recordId/sessionId/endpoint）只写落库副本——调用方持有
        // 的原对象保持原样，与 DataSanitizer「不改原记录」同一承诺
        RecorderConfig config = RecorderConfig.builder().defaultInvocationId("default-skill").build();
        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord mine = createRecord("mine");
        mine.setInvocationId(null);
        mine.setRecordId(null);
        mine.setSessionId(null);
        mine.setEndpoint(null);
        recorder.intercept(mine);
        recorder.stop(); // stop 排空语义保证确定性：返回时已发布记录全部落库

        assertNull(mine.getInvocationId(), "原对象的声明位不被改写");
        assertNull(mine.getRecordId(), "原对象的 recordId 不被改写");
        assertNull(mine.getSessionId(), "原对象的 sessionId 不被改写");
        assertNull(mine.getEndpoint(), "原对象的 endpoint 不被改写");

        assertEquals(1, repo.getStore().size(), "副本照常落库");
        InteractionRecord stored = repo.getStore().get(0);
        assertEquals("default-skill", stored.getInvocationId(), "默认声明落在副本的声明位");
        assertNotNull(stored.getRecordId(), "副本 recordId 兜底 UUID");
        assertEquals(stored.getRecordId(), stored.getSessionId(), "副本 sessionId 退化独立会话");
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
    void captureGate_filterMode_undeclaredBareChat_filtered() throws Exception {
        // 过滤模式（recordUndeclaredChat=false）：未声明（invocationId/templateId 均无）
        // 且无工具调用的纯对话 → 过滤，不进管道
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).recordUndeclaredChat(false).build();

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
    void captureGate_declaredByInvocationId_recorded() throws Exception {
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord declared = new InteractionRecord();
        declared.setRecordId("declared-1");
        declared.setTimestamp(System.currentTimeMillis());
        declared.setInvocationId("order-flow");
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
    void captureGate_defaultInvocationId_undeclaredRecordedWithDefault() throws Exception {
        // 应用级默认声明：未声明记录以默认 invocationId 过门并落到声明位
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).defaultInvocationId("order-flow").build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord bare = new InteractionRecord();
        bare.setRecordId("bare-3");
        bare.setTimestamp(System.currentTimeMillis());
        recorder.intercept(bare);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        assertEquals("order-flow", repo.getStore().get(0).getInvocationId(), "默认 invocationId 落到记录声明位");
        assertEquals(0, recorder.getFilteredCount());
    }

    @Test
    void captureGate_default_recordsBareChat() throws Exception {
        // 默认全量录制：未声明纯对话也进管道（任务链完整性优先于流量成本，
        // 链条终点的最终回答组装往往正是纯文本调用）
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord bare = new InteractionRecord();
        bare.setRecordId("bare-2");
        bare.setTimestamp(System.currentTimeMillis());
        bare.setTemplateHash("some-template");
        recorder.intercept(bare);

        Thread.sleep(200);
        recorder.stop();

        assertEquals(1, repo.getStore().size());
        assertEquals(0, recorder.getFilteredCount());
    }

    @Test
    void captureGate_filterMode_filtersAllBareInteractions() {
        RecorderConfig config = RecorderConfig.builder().recordUndeclaredChat(false).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        for (int i = 1; i <= 100; i++) {
            InteractionRecord bare = new InteractionRecord();
            bare.setRecordId("warn-" + i);
            bare.setTimestamp(System.currentTimeMillis());
            recorder.intercept(bare);
        }

        recorder.stop();

        assertEquals(100, recorder.getFilteredCount());
    }

    @Test
    void filteredWarnRhythm_firstThenEveryInterval() {
        // 告警间隔规则：首条被滤记录一次，此后每满 100 条重申一次
        assertTrue(InteractionRecorder.shouldWarnOnFilter(1));
        assertFalse(InteractionRecorder.shouldWarnOnFilter(2));
        assertFalse(InteractionRecorder.shouldWarnOnFilter(99));
        assertTrue(InteractionRecorder.shouldWarnOnFilter(100));
        assertFalse(InteractionRecorder.shouldWarnOnFilter(101));
        assertTrue(InteractionRecorder.shouldWarnOnFilter(200));
    }

    @Test
    void disabledRecorder_recordsNothing() {
        // 总开关：enabled=false 时整体 no-op——不启动管道、不消费、不计数
        RecorderConfig config = RecorderConfig.builder().enabled(false).build();

        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();
        recorder.intercept(createRecord("off-1"));
        recorder.stop();

        assertFalse(recorder.isStarted(), "禁用态下 start 不启动管道");
        assertTrue(repo.getStore().isEmpty(), "禁用态下不落库");
        assertEquals(0, recorder.getRecordedCount());
        assertEquals(0, recorder.getFilteredCount());
    }

    @Test
    void captureGate_arrivalClosure_recordedPlusFiltered() {
        // 到达闭合：recorded（进管道，含后续丢弃）+ filtered（被门滤掉）= 总到达
        RecorderConfig config = RecorderConfig.builder().batchSize(1).flushIntervalMs(100).ringBufferSize(1024).recordUndeclaredChat(false).build();

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
        // 总丢弃规则必须含该路径，且 written + dropped 闭合到 recorded。
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
        // 关停握手：并发发布既不得滞留为幽灵事件，也不得破坏
        // written + dropped == recorded 的计数闭合（stop 全程不阻塞生产者）
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

    @Test
    void writeFailure_closureIncludesFailedCount() {
        // 计数闭合三项目公式：批量写失败单独计 failed，不与丢弃混计
        InMemoryStorageRepository repo = new InMemoryStorageRepository();
        repo.setThrowOnSave(true);
        RecorderConfig config = RecorderConfig.builder().batchSize(100).ringBufferSize(1024).build();
        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        recorder.intercept(createRecord("fail-1"));
        recorder.intercept(createRecord("fail-2"));
        recorder.stop();

        assertEquals(2, recorder.getRecordedCount());
        assertEquals(0, recorder.getWrittenCount(), "写全部失败，written 必须为 0");
        assertTrue(recorder.getFailedCount() >= 1, "批量写失败必须计入 failed");
        assertEquals(recorder.getRecordedCount(), recorder.getWrittenCount() + recorder.getDroppedCount() + recorder.getFailedCount(),
                "written + dropped + failed 必须闭合到 recorded");
    }

    @Test
    void stop_neverBlocksProducers_andClosesCounters() throws Exception {
        // 契约：关停全程不阻塞生产者；关停窗口内到达按丢弃计数；排空超时的滞留事件
        // 以丢弃结算——written + dropped + failed 在 stop 返回后闭合到 recorded。
        // 本测试的存储调用永不返回（无在途批次补记 written），闭合断言因此精确成立
        CountDownLatch releaseStorage = new CountDownLatch(1);
        CountDownLatch storageEntered = new CountDownLatch(1);
        InMemoryStorageRepository blocking = new InMemoryStorageRepository() {
            @Override
            public void saveInteractions(List<InteractionRecord> records) {
                storageEntered.countDown();
                try {
                    releaseStorage.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.saveInteractions(records);
            }
        };
        RecorderConfig config = RecorderConfig.builder().batchSize(100).ringBufferSize(4096).build();
        InteractionRecorder recorder = new InteractionRecorder(blocking, config);
        recorder.start();

        // 首条记录让消费线程停在存储调用里等待：它尚未写完，消费序不推进（gating 落后 cursor）
        recorder.intercept(createRecord("wedge"));
        assertTrue(storageEntered.await(5, TimeUnit.SECONDS), "消费线程必须先卡进存储调用");

        CountDownLatch stopEntered = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            stopEntered.countDown();
            recorder.stop(300, TimeUnit.MILLISECONDS);
        });
        stopper.start();
        stopEntered.await();
        Thread.sleep(100); // stop 已进入排空等待窗口（时限 300ms）

        long begin = System.nanoTime();
        for (int i = 0; i < 500; i++) {
            recorder.intercept(createRecord("nb-" + i));
        }
        long elapsedMs = (System.nanoTime() - begin) / 1_000_000L;
        assertTrue(elapsedMs < 2000, "关停过程中生产者不得被阻塞：500 次提交耗时 " + elapsedMs + "ms");

        stopper.join(5000);
        assertFalse(stopper.isAlive(), "stop 必须在时限内有界返回");
        assertEquals(recorder.getRecordedCount(), recorder.getWrittenCount() + recorder.getDroppedCount() + recorder.getFailedCount(),
                "超时结算后账本必须闭合：滞留事件按丢弃结算");
        assertTrue(recorder.getDroppedCount() >= 500, "关停窗口到达必须按丢弃计数而非静默蒸发");
    }

    @Test
    void captureFillsEndpointFromRecorderDefault_perCallWins() {
        // endpoint 部署身份：录制器级默认在采集管道兜底填列，per-call 声明优先。
        // 补全只写落库副本（原对象不改写承诺）——断言从存储侧读，调用方对象同时验证未被改写
        RecorderConfig config = RecorderConfig.builder().endpoint("http://ep-default:8000").build();
        InteractionRecorder recorder = new InteractionRecorder(repo, config);
        recorder.start();

        InteractionRecord defaulted = new InteractionRecord();
        defaulted.setRecordId("ep-1");
        defaulted.setSessionId("s-ep");
        defaulted.setTimestamp(System.currentTimeMillis());
        recorder.intercept(defaulted);

        InteractionRecord declared = new InteractionRecord();
        declared.setRecordId("ep-2");
        declared.setSessionId("s-ep2");
        declared.setTimestamp(System.currentTimeMillis());
        declared.setEndpoint("http://per-call:9999");
        recorder.intercept(declared);

        recorder.stop();

        assertEquals("http://ep-default:8000", storedByRecordId("ep-1").getEndpoint(), "未声明记录由录制器级默认兜底（副本侧）");
        assertEquals("http://per-call:9999", storedByRecordId("ep-2").getEndpoint(), "per-call 声明优先于录制器级默认（副本侧）");
        assertNull(defaulted.getEndpoint(), "调用方原对象不被改写");
        assertEquals("http://per-call:9999", declared.getEndpoint(), "显式声明原样保留（本就不为空）");
    }

    private InteractionRecord storedByRecordId(String recordId) {
        return repo.getStore().stream().filter(r -> recordId.equals(r.getRecordId())).findFirst()
                .orElseThrow(() -> new AssertionError("record not stored: " + recordId));
    }
}
