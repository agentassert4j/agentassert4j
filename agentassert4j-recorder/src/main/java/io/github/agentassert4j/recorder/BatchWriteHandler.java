package io.github.agentassert4j.recorder;

import com.lmax.disruptor.EventHandler;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.InteractionWriteStore;
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
 *   <li>写入失败仅记计数器，不重试（L2 错误处理）</li>
 * </ul>
 */
public class BatchWriteHandler implements EventHandler<InteractionEvent> {

    private static final Logger log = LoggerFactory.getLogger(BatchWriteHandler.class);

    private final InteractionWriteStore repository;
    private final int batchSize;
    private final int maxBufferSize;
    private final List<InteractionRecord> buffer;

    /** 丢弃计数（RingBuffer 满或 buffer 超限） */
    private final AtomicLong droppedCount = new AtomicLong(0);
    /** 写入失败计数 */
    private final AtomicLong failedCount = new AtomicLong(0);
    /** 成功写入总数 */
    private final AtomicLong writtenCount = new AtomicLong(0);

    private ScheduledExecutorService flushScheduler;

    public BatchWriteHandler(InteractionWriteStore repository, RecorderConfig config) {
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
        flushScheduler.scheduleAtFixedRate(this::flush,
                flushIntervalMs, flushIntervalMs, TimeUnit.MILLISECONDS);
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

        try {
            repository.saveInteractions(toWrite);
            writtenCount.addAndGet(toWrite.size());
        } catch (Exception e) {
            failedCount.addAndGet(toWrite.size());
            log.error("Batch write failed, {} records lost: {}",
                    toWrite.size(), e.getMessage(), e);
            // L2 策略：不重试，记录丢失
        }
    }

    // ========== 统计 ==========

    public long getDroppedCount() {
        return droppedCount.get();
    }

    public long getFailedCount() {
        return failedCount.get();
    }

    public long getWrittenCount() {
        return writtenCount.get();
    }

    /**
     * 返回当前缓冲区大小（主要用于监控和测试）。
     */
    public int getBufferSize() {
        synchronized (buffer) {
            return buffer.size();
        }
    }
}
