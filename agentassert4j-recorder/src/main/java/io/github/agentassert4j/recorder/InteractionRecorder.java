package io.github.agentassert4j.recorder;

import com.lmax.disruptor.InsufficientCapacityException;
import com.lmax.disruptor.SleepingWaitStrategy;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.lmax.disruptor.util.DaemonThreadFactory;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.InteractionWriteStore;
import io.github.agentassert4j.spi.RecordingInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 核心录制器 — Disruptor 异步录制交互记录。
 *
 * <p>实现 {@link RecordingInterceptor} SPI 接口。
 * 通过 Disruptor RingBuffer 实现纳秒级入队，不阻塞业务线程（零侵入）。</p>
 *
 * <p>生命周期：构造 → {@link #start()} → {@link #intercept(InteractionRecord)} → {@link #stop()}。</p>
 *
 * <p>错误处理策略：
 * <ul>
 *   <li>采集门：默认全量录制；recordUndeclaredChat=false 时未声明且无可见工具调用的纯对话被过滤，过滤量独立计数并告警</li>
 *   <li>enabled=false 时录制器不启动管道、不消费记录（生产打包形态）</li>
 *   <li>RingBuffer 满时丢弃记录，不阻塞生产者</li>
 *   <li>批量写入失败记录丢弃计数器，不重试</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class InteractionRecorder implements RecordingInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InteractionRecorder.class);

    private final InteractionWriteStore repository;
    private final RecorderConfig config;
    private final DataSanitizer sanitizer;
    /**
     * 到达录制器的记录数（含丢弃）——written + dropped 与之闭合；
     * 被采集门过滤的记录不计入本数（它们从未进入管道），总到达 = recorded + filtered
     */
    private final AtomicLong recordedCount = new AtomicLong(0);
    /**
     * 被采集门过滤的记录数：未声明（invocationId/templateId 均无）且无可见工具调用的
     * 纯对话默认不录——过滤是决策不是故障，与丢弃分列
     */
    private final AtomicLong filteredCount = new AtomicLong(0);
    /**
     * 因 RingBuffer 满而丢弃的记录数（生产侧）
     */
    private final AtomicLong droppedCount = new AtomicLong(0);
    /**
     * 消费侧丢弃/写入/失败计数——recorder 级持有，restart 后跨生命周期累计
     */
    private final AtomicLong consumerDroppedCount = new AtomicLong(0);
    private final AtomicLong writtenCount = new AtomicLong(0);
    private final AtomicLong failedCount = new AtomicLong(0);
    /**
     * 录制进程内单调序号源——透传给每条记录的 seq。
     * 丢弃造成的空洞合法：同会话内 seq 单调即可，(session_id, seq) 为确定性排序键。
     */
    private final AtomicLong seqSource = new AtomicLong(0);
    private Disruptor<InteractionEvent> disruptor;
    private BatchWriteHandler batchHandler;
    private volatile boolean started = false;
    /**
     * 过滤告警的重申间隔：首条被滤记录告警一次，此后每满 100 条重申一次累计数——
     * 静默丢数据比丢数据本身更危险
     */
    private static final long FILTERED_WARN_INTERVAL = 100;
    private final AtomicLong filteredWarnEmissions = new AtomicLong(0);

    /**
     * 创建录制器。
     *
     * @param repository 存储仓库（不能为 null）
     * @param config     录制器配置（null 时使用默认配置）
     */
    public InteractionRecorder(InteractionWriteStore repository, RecorderConfig config) {
        if (repository == null) {
            throw new IllegalArgumentException("StorageRepository must not be null");
        }
        this.repository = repository;
        this.config = config != null ? config : RecorderConfig.defaults();
        this.sanitizer = new DataSanitizer(this.config);
    }

    /**
     * 启动 Disruptor 和定时 flush 线程。
     * 必须在 {@link #intercept(InteractionRecord)} 之前调用。
     * enabled=false 时不启动任何管道，本录制器整体退化为 no-op。
     */
    public synchronized void start() {
        if (started) {
            return;
        }
        if (!config.isEnabled()) {
            log.info("InteractionRecorder disabled by configuration, no recording will happen");
            return;
        }

        batchHandler = new BatchWriteHandler(repository, config, writtenCount, failedCount, consumerDroppedCount);

        disruptor = new Disruptor<>(InteractionEvent::new, config.getRingBufferSize(), DaemonThreadFactory.INSTANCE, ProducerType.MULTI, new SleepingWaitStrategy());

        disruptor.handleEventsWith(batchHandler);
        disruptor.start();

        // 启动定时 flush
        batchHandler.startFlushScheduler(config.getFlushIntervalMs());

        started = true;
        log.info("InteractionRecorder started, ringBufferSize={}, batchSize={}, flushIntervalMs={}", config.getRingBufferSize(), config.getBatchSize(), config.getFlushIntervalMs());
    }

    /**
     * 实现 RecordingInterceptor SPI：拦截并录制一次 LLM 交互。
     * 先落应用级默认声明（如配置），再过采集门（默认全量录制，过滤模式仅
     * 在 recordUndeclaredChat=false 时生效），然后执行脱敏，
     * 最后通过 Disruptor 异步入队（纳秒级，不阻塞）。
     */
    @Override
    public synchronized void intercept(InteractionRecord record) {
        if (!started || record == null) {
            return;
        }

        // 默认声明：未声明且无可见工具调用的记录先以应用级默认 invocationId 落到
        // 声明位（单技能应用零声明成本；声明锚点在身份优先级中高于模板哈希）
        if (!isDeclared(record) && !hasVisibleToolCalls(record) && isNonEmpty(config.getDefaultInvocationId())) {
            record.setInvocationId(config.getDefaultInvocationId());
        }

        // 采集门：默认全量录制（任务链完整性优先于流量卫生，链条终点的最终
        // 回答组装往往正是纯文本调用）；recordUndeclaredChat=false 时未声明且
        // 无可见工具调用的纯对话被过滤——过滤是决策不是故障，与丢弃分列。
        // 被滤记录不进入管道、不占用 RingBuffer 与 seq，独立计数保证
        // 「滤了多少」可见；首条与每满 100 条各发一次 WARN，静默丢数据比
        // 丢数据本身更危险
        if (!config.isRecordUndeclaredChat() && !isDeclared(record) && !hasVisibleToolCalls(record)) {
            long filtered = filteredCount.incrementAndGet();
            if (filtered == 1 || filtered % FILTERED_WARN_INTERVAL == 0) {
                filteredWarnEmissions.incrementAndGet();
                log.warn("Capture gate filtered undeclared interaction: declare invocationId/templateId or set recordUndeclaredChat=true to record; filtered total={}", filtered);
            }
            return;
        }

        // 与 stop() 互斥：无锁窗口内关停完成会把事件发布进已停摆的
        // RingBuffer——记录永久滞留且计数不闭合。无竞争锁开销纳秒级，
        // 相比 tryNext 本身可忽略
        try {
            // TODO: [record_id UUID 兜底] 上游 SDK 未接线前在此兜底生成全局唯一 ID；
            //       INSERT OR IGNORE 的防重放语义依赖其全局唯一性
            if (record.getRecordId() == null || record.getRecordId().isEmpty()) {
                record.setRecordId(UUID.randomUUID().toString());
            }
            // session_id 列有 NOT NULL 约束：缺失时退化为独立会话
            // （每条自成一组，依赖链为空），保住录制不整批失败
            if (record.getSessionId() == null || record.getSessionId().isEmpty()) {
                record.setSessionId(record.getRecordId());
            }

            // 到达即计数（含后续丢弃）：written + dropped 闭合到本计数
            recordedCount.incrementAndGet();

            // 脱敏
            InteractionRecord sanitized = sanitizer.sanitize(record);

            // seq 透传：录制进程内单调（空洞合法）
            sanitized.setSeq(seqSource.incrementAndGet());

            // 非阻塞发布到 RingBuffer：满时丢弃不阻塞业务线程（零侵入）
            long sequence = disruptor.getRingBuffer().tryNext();
            try {
                InteractionEvent event = disruptor.getRingBuffer().get(sequence);
                event.setRecord(sanitized);
            } finally {
                disruptor.getRingBuffer().publish(sequence);
            }
        } catch (InsufficientCapacityException e) {
            // RingBuffer 满时丢弃，不阻塞
            droppedCount.incrementAndGet();
            log.warn("RingBuffer full, record dropped: {}", record.getRecordId());
        } catch (Exception e) {
            // 脱敏异常等：退化到丢弃
            droppedCount.incrementAndGet();
            log.warn("Failed to publish interaction record: {}", e.getMessage());
        }
    }

    /**
     * 采集门判定：业务身份声明（invocationId 或 templateId 任一非空）即视为已声明。
     */
    private static boolean isDeclared(InteractionRecord record) {
        return isNonEmpty(record.getInvocationId()) || isNonEmpty(record.getTemplateId());
    }

    private static boolean hasVisibleToolCalls(InteractionRecord record) {
        return record.isHasToolCalls() && record.getToolCalls() != null && !record.getToolCalls().isEmpty();
    }

    private static boolean isNonEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /**
     * 手动触发 flush，将缓冲区中的记录立即写入存储。
     */
    public void flush() {
        if (batchHandler != null) {
            batchHandler.flush();
        }
    }

    /**
     * 优雅停止录制器：flush 剩余记录 → 停止 Disruptor → 停止定时线程。
     * 超时 10 秒后强制关闭。
     */
    public void stop() {
        synchronized (this) {
            if (!started) {
                return;
            }

            try {
                // 先 flush 剩余数据
                if (batchHandler != null) {
                    batchHandler.flush();
                    batchHandler.stopFlushScheduler();
                }

                // 关闭 Disruptor
                disruptor.shutdown(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error during InteractionRecorder shutdown: {}", e.getMessage(), e);
            } finally {
                started = false;
                log.info("InteractionRecorder stopped, recorded={}, filtered={}, dropped(ringBuffer={}, bufferOverflow={}), written={}, failed={}", recordedCount.get(), filteredCount.get(), droppedCount.get(), batchHandler != null ? batchHandler.getDroppedCount() : 0, batchHandler != null ? batchHandler.getWrittenCount() : 0, batchHandler != null ? batchHandler.getFailedCount() : 0);
            }
        }
    }

    public long getRecordedCount() {
        return recordedCount.get();
    }

    /**
     * 被采集门过滤的记录数（未声明且无可见工具调用的纯对话）。
     */
    public long getFilteredCount() {
        return filteredCount.get();
    }

    /**
     * 过滤告警的发放次数（首条被滤记录一次，此后每满 100 条重申一次）。
     */
    long getFilteredWarnEmissions() {
        return filteredWarnEmissions.get();
    }

    /**
     * 总丢弃数 = 生产侧（RingBuffer 满/发布异常）+ 消费侧（缓冲超限）。
     * 两个计数器分属不同线程域，聚合口径以本方法为准。
     */
    public long getDroppedCount() {
        return droppedCount.get() + consumerDroppedCount.get();
    }

    public long getWrittenCount() {
        return writtenCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public boolean isStarted() {
        return started;
    }
}
