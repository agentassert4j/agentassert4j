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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 核心录制器 — Disruptor 异步录制交互记录。
 *
 * <p>实现 {@link RecordingInterceptor} SPI 接口。
 * 通过 Disruptor RingBuffer 实现纳秒级入队，不阻塞业务线程（R8 零侵入）。</p>
 *
 * <p>生命周期：构造 → {@link #start()} → {@link #intercept(InteractionRecord)} → {@link #stop()}。</p>
 *
 * <p>错误处理策略：
 * <ul>
 *   <li>RingBuffer 满时丢弃记录，不阻塞生产者</li>
 *   <li>批量写入失败记录丢弃计数器，不重试</li>
 * </ul>
 */
public class InteractionRecorder implements RecordingInterceptor {

    private static final Logger log = LoggerFactory.getLogger(InteractionRecorder.class);

    private final InteractionWriteStore repository;
    private final RecorderConfig config;
    private final DataSanitizer sanitizer;
    /**
     * 已入队的记录数（含丢弃）
     */
    private final AtomicLong recordedCount = new AtomicLong(0);
    /**
     * 因 RingBuffer 满而丢弃的记录数
     */
    private final AtomicLong droppedCount = new AtomicLong(0);
    /**
     * 录制进程内单调序号源——透传给每条记录的 seq。
     * 丢弃造成的空洞合法：同会话内 seq 单调即可，(session_id, seq) 为确定性排序键。
     */
    private final AtomicLong seqSource = new AtomicLong(0);
    private Disruptor<InteractionEvent> disruptor;
    private BatchWriteHandler batchHandler;
    private volatile boolean started = false;

    /**
     * 使用默认配置创建录制器。
     *
     * @param repository 存储仓库（不能为 null）
     */
    public InteractionRecorder(InteractionWriteStore repository) {
        this(repository, RecorderConfig.defaults());
    }

    /**
     * 使用自定义配置创建录制器。
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
     */
    public synchronized void start() {
        if (started) {
            return;
        }

        batchHandler = new BatchWriteHandler(repository, config);

        disruptor = new Disruptor<>(
                InteractionEvent::new,
                config.getRingBufferSize(),
                DaemonThreadFactory.INSTANCE,
                ProducerType.MULTI,
                new SleepingWaitStrategy()
        );

        disruptor.handleEventsWith(batchHandler);
        disruptor.start();

        // 启动定时 flush
        batchHandler.startFlushScheduler(config.getFlushIntervalMs());

        started = true;
        log.info("InteractionRecorder started, ringBufferSize={}, batchSize={}, flushIntervalMs={}",
                config.getRingBufferSize(), config.getBatchSize(), config.getFlushIntervalMs());
    }

    /**
     * 实现 RecordingInterceptor SPI：拦截并录制一次 LLM 交互。
     * 先执行脱敏，再通过 Disruptor 异步入队（纳秒级，不阻塞）。
     */
    @Override
    public void intercept(InteractionRecord record) {
        if (!started || record == null) {
            return;
        }

        try {
            // TODO: [record_id UUID 兜底] 上游 SDK 未接线前在此兜底生成全局唯一 ID；
            //       INSERT OR IGNORE 的防重放语义依赖其全局唯一性
            if (record.getRecordId() == null || record.getRecordId().isEmpty()) {
                record.setRecordId(UUID.randomUUID().toString());
            }

            // 脱敏
            InteractionRecord sanitized = sanitizer.sanitize(record);

            // seq 透传：录制进程内单调（空洞合法）
            sanitized.setSeq(seqSource.incrementAndGet());

            // 非阻塞发布到 RingBuffer：满时丢弃不阻塞业务线程（R8 零侵入）
            long sequence = disruptor.getRingBuffer().tryNext();
            try {
                InteractionEvent event = disruptor.getRingBuffer().get(sequence);
                event.setRecord(sanitized);
            } finally {
                disruptor.getRingBuffer().publish(sequence);
            }
            recordedCount.incrementAndGet();
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
     * 便利方法：与 intercept() 等价，更直观的 API 名称。
     */
    public void record(InteractionRecord record) {
        intercept(record);
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
                disruptor.shutdown(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("Error during InteractionRecorder shutdown: {}", e.getMessage(), e);
            } finally {
                started = false;
                log.info("InteractionRecorder stopped, recorded={}, dropped={}, written={}, failed={}",
                        recordedCount.get(), droppedCount.get(),
                        batchHandler != null ? batchHandler.getWrittenCount() : 0,
                        batchHandler != null ? batchHandler.getFailedCount() : 0);
            }
        }
    }

    public long getRecordedCount() {
        return recordedCount.get();
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getWrittenCount() {
        return batchHandler != null ? batchHandler.getWrittenCount() : 0;
    }

    public long getFailedCount() {
        return batchHandler != null ? batchHandler.getFailedCount() : 0;
    }

    public boolean isStarted() {
        return started;
    }
}
