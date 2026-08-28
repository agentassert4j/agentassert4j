package io.github.agentassert4j.recorder;

import com.lmax.disruptor.EventHandler;
import io.github.agentassert4j.algorithm.DeterministicSkillGrouper;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.InteractionWriteStore;
import io.github.agentassert4j.util.TextUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 批量写入处理器 — Disruptor EventHandler 实现。
 *
 * <p>策略：
 * <ul>
 *   <li>缓冲区达到 {@code batchSize} 时触发批量写入</li>
 *   <li>定时 flush（每 {@code flushIntervalMs} 毫秒）确保数据不长期滞留</li>
 *   <li>缓冲区上限 {@code maxBufferSize} 防止 OOM，超限丢弃新记录</li>
 *   <li>写入失败仅记计数器，不重试</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class BatchWriteHandler implements EventHandler<InteractionEvent> {

    private static final Logger log = LoggerFactory.getLogger(BatchWriteHandler.class);

    private final InteractionWriteStore repository;
    private final int batchSize;
    private final int maxBufferSize;
    private final List<InteractionRecord> buffer;

    /**
     * 丢弃计数（RingBuffer 满或 buffer 超限）
     */
    private final AtomicLong droppedCount;
    /**
     * 写入失败计数
     */
    private final AtomicLong failedCount;
    /**
     * 成功写入总数
     */
    private final AtomicLong writtenCount;

    private ScheduledExecutorService flushScheduler;

    /**
     * 计数器由录制器持有注入：stop→restart 会创建新 handler，实例级计数
     * 会让聚合口径（recorded = written + dropped + failed）在第二生命周期破裂。
     */
    public BatchWriteHandler(InteractionWriteStore repository, RecorderConfig config, AtomicLong writtenCount, AtomicLong failedCount, AtomicLong droppedCount) {
        this.writtenCount = writtenCount;
        this.failedCount = failedCount;
        this.droppedCount = droppedCount;
        this.repository = repository;
        this.batchSize = config.getBatchSize();
        this.maxBufferSize = config.getMaxBufferSize();
        this.buffer = new ArrayList<>(maxBufferSize);
    }

    /**
     * 启动定时 flush 线程。由 InteractionRecorder 在 start() 时调用。
     */
    void startFlushScheduler(long flushIntervalMs) {
        if (flushIntervalMs <= 0) {
            return;
        }
        flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "agentassert4j-batch-flush");
            t.setDaemon(true);
            return t;
        });
        flushScheduler.scheduleAtFixedRate(this::flush, flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 停止定时 flush 线程。由 InteractionRecorder 在 stop() 时调用。
     */
    void stopFlushScheduler() {
        if (flushScheduler != null) {
            flushScheduler.shutdown();
            try {
                if (!flushScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    flushScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                flushScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            flushScheduler = null;
        }
    }

    @Override
    public void onEvent(InteractionEvent event, long sequence, boolean endOfBatch) {
        InteractionRecord record = event.getRecord();
        if (record == null) {
            return;
        }

        // OOM 保护 + flush 判断：全部在 synchronized 内完成，避免数据竞争
        boolean shouldFlush = false;
        synchronized (buffer) {
            if (buffer.size() >= maxBufferSize) {
                droppedCount.incrementAndGet();
                // 超限丢弃必须留痕：无日志的丢数在线上无法定位
                log.warn("Buffer overflow (maxBufferSize={}), record dropped: {}", maxBufferSize, record.getRecordId());
                // buffer 满时丢弃，但 endOfBatch 仍需 flush 已有数据
                if (endOfBatch && !buffer.isEmpty()) {
                    shouldFlush = true;
                }
            } else {
                buffer.add(record);
                if (buffer.size() >= batchSize || endOfBatch) {
                    shouldFlush = true;
                }
            }
        }

        if (shouldFlush) {
            flush();
        }
    }

    /**
     * 将缓冲区中的记录批量写入 StorageRepository。
     * 线程安全：synchronized 保护 buffer 的读写。
     */
    void flush() {
        List<InteractionRecord> toWrite;
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return;
            }
            toWrite = new ArrayList<>(buffer);
            buffer.clear();
        }

        enrich(toWrite);

        try {
            repository.saveInteractions(toWrite);
            writtenCount.addAndGet(toWrite.size());
        } catch (Exception e) {
            failedCount.addAndGet(toWrite.size());
            log.error("Batch write failed, {} records lost: {}", toWrite.size(), e.getMessage(), e);
        }
    }

    /**
     * 落库前补全派生字段（skillId/分组键 + 指纹快照）。
     * 在消费线程执行——指纹提取含响应体 JSON 解析，不允许回到业务线程。
     * skill_id 与 group_key 列有 NOT NULL 约束，上游缺失时回充分组器派生 id，
     * 否则整批 INSERT 失败；已有值不覆盖（上游显式设置的优先）。
     * 单条补全失败不拦截落库——原始交互数据是真源，派生字段缺失可事后重建。
     */
    private void enrich(List<InteractionRecord> records) {
        for (InteractionRecord record : records) {
            try {
                if (TextUtil.isBlank(record.getGroupKey()) || TextUtil.isBlank(record.getSkillId())) {
                    SkillProfile grouping = DeterministicSkillGrouper.group(record);
                    if (TextUtil.isBlank(record.getGroupKey())) {
                        record.setGroupKey(grouping.getGroupKey());
                    }
                    if (TextUtil.isBlank(record.getSkillId())) {
                        record.setSkillId(grouping.getSkillId());
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Enrichment incomplete, record saved without derived fields: {} ({})", record.getRecordId(), e.getMessage());
            }
        }
    }

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public long getWrittenCount() {
        return writtenCount.get();
    }
}
