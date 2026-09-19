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
import java.util.concurrent.locks.LockSupport;

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
 *   <li>stop() 全程不阻塞生产者：关停窗口内到达的记录按丢弃计数，排空超时的滞留事件以丢弃结算</li>
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

    /**
     * 生命周期相位。RUNNING=正常录制；DRAINING=stop 已截住新发布、正在锁外排空；
     * STOPPED=排空完成或未启动。相位迁移都在发布临界区内发生（start 的 RUNNING
     * 迁移除外，它持有生命周期锁先于任何发布）——生产者的发布段与 stop 的标志翻转
     * 共享同一把只护临界区的锁：stop 在锁内翻转相位（微秒级）后即释放，flush/排空/
     * 停机全部锁外进行。因此「通过临界区复核的发布必然先于排空完成」，记录不会
     * 滞留进已停摆的 RingBuffer，计数闭合与生产者不阻塞同时成立。
     */
    private volatile Phase phase = Phase.STOPPED;

    /**
     * 发布临界区锁：intercept 的认领-发布段与 stop 的相位翻转互斥。不护采集门、
     * 脱敏与任何 I/O——生产者之间在此只有纳秒级串行。
     */
    private final Object publishLock = new Object();

    private enum Phase { RUNNING, DRAINING, STOPPED }
    /**
     * 过滤告警的重申间隔：首条被滤记录告警一次，此后每满 100 条重申一次累计数——
     * 静默丢数据比丢数据本身更危险
     */
    private static final long FILTERED_WARN_INTERVAL = 100;

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
        if (phase == Phase.RUNNING) {
            return;
        }
        if (phase == Phase.DRAINING) {
            throw new IllegalStateException("stop() is in progress; start() must not race a concurrent stop()");
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

        phase = Phase.RUNNING;
        log.info("InteractionRecorder started, ringBufferSize={}, batchSize={}, flushIntervalMs={}", config.getRingBufferSize(), config.getBatchSize(), config.getFlushIntervalMs());
    }

    /**
     * 实现 RecordingInterceptor SPI：拦截并录制一次 LLM 交互。
     * 先落应用级默认声明（如配置），再过采集门（默认全量录制，过滤模式仅
     * 在 recordUndeclaredChat=false 时生效），然后执行脱敏，
     * 最后通过 Disruptor 异步入队（纳秒级，不阻塞）。
     */
    @Override
    public void intercept(InteractionRecord record) {
        if (record == null) {
            return;
        }
        if (phase != Phase.RUNNING) {
            if (!config.isEnabled()) {
                // enabled=false 的 no-op 形态整体静默：不启动、不消费、不计数
                return;
            }
            // 关停窗口与停止后的到达同样计入 recorded 与 dropped：账本闭合不因关停
            // 破坏，丢弃对诊断可见而非静默蒸发
            recordedCount.incrementAndGet();
            droppedCount.incrementAndGet();
            return;
        }

        // 采集门与默认声明判定只读原值；配了 defaultInvocationId 时，应用级默认声明
        // 同时是未声明纯对话的过门凭证（声明位补全表达应用级归属，不改写记录本身）
        boolean undeclared = !isDeclared(record);
        boolean noVisibleTools = !hasVisibleToolCalls(record);
        boolean applyDefaultInvocation = undeclared && noVisibleTools && isNonEmpty(config.getDefaultInvocationId());

        // 采集门：默认全量录制（任务链完整性优先于流量成本，链条终点的最终
        // 回答组装往往正是纯文本调用）；recordUndeclaredChat=false 时未声明且
        // 无可见工具调用的纯对话被过滤——过滤是决策不是故障，与丢弃分列。
        // 被滤记录不进入管道、不占用 RingBuffer 与 seq，独立计数保证
        // 「滤了多少」可见；首条与每满 100 条各发一次 WARN，静默丢数据比
        // 丢数据本身更危险
        if (!config.isRecordUndeclaredChat() && undeclared && noVisibleTools && !applyDefaultInvocation) {
            long filtered = filteredCount.incrementAndGet();
            if (shouldWarnOnFilter(filtered)) {
                log.warn("Capture gate filtered undeclared interaction: declare invocationId/templateId or set recordUndeclaredChat=true to record; filtered total={}", filtered);
            }
            return;
        }

        try {
            // 到达即计数（含后续丢弃）：written + dropped + failed 闭合到本计数
            recordedCount.incrementAndGet();

            // 脱敏（深拷贝点）：以下全部补全只写副本，调用方持有的原对象保持原样，
            // 与 DataSanitizer「不改原记录」同一承诺
            InteractionRecord sanitized = sanitizer.sanitize(record);

            // record_id 身份的唯一权威来源 = LLM 响应 id（SDK 捕获侧与 MCP 摄取侧同源写入，
            // INSERT OR IGNORE 的跨入口去重依赖其全局唯一）；捕获侧未携带（无 id 的
            // provider、mock、stream 聚合元数据缺失）时回退 UUID——每次拦截各自生成，
            // 无 id 记录的重复拦截视为两次独立交互
            if (sanitized.getRecordId() == null || sanitized.getRecordId().isEmpty()) {
                sanitized.setRecordId(UUID.randomUUID().toString());
            }
            // session_id 列有 NOT NULL 约束：缺失时退化为独立会话
            // （每条自成一组，依赖链为空），保住录制不整批失败
            if (sanitized.getSessionId() == null || sanitized.getSessionId().isEmpty()) {
                sanitized.setSessionId(sanitized.getRecordId());
            }
            // endpoint 是基线跨部署可比的部署身份（指纹可比性前提）：per-call 声明
            // 优先，缺失时以录制器级默认兜底——两者皆缺则该列留空（不编造）
            if (sanitized.getEndpoint() == null || sanitized.getEndpoint().isEmpty()) {
                sanitized.setEndpoint(config.getEndpoint());
            }
            // 默认声明落到副本的声明位（单技能应用零声明成本；声明锚点在身份优先级中
            // 高于模板哈希）
            if (applyDefaultInvocation) {
                sanitized.setInvocationId(config.getDefaultInvocationId());
            }

            // seq 透传：录制进程内单调（空洞合法）
            sanitized.setSeq(seqSource.incrementAndGet());

            // 发布临界区：与 stop 的相位翻转互斥。复核通过后才认领序列——关停握手
            // 保证「通过复核的发布必然先于排空完成」，记录不会滞留进已停摆的
            // RingBuffer；临界区只含认领与发布（纳秒级），stop 不在此等待排空
            synchronized (publishLock) {
                if (phase != Phase.RUNNING) {
                    droppedCount.incrementAndGet();
                    return;
                }
                long sequence = disruptor.getRingBuffer().tryNext();
                try {
                    InteractionEvent event = disruptor.getRingBuffer().get(sequence);
                    event.setRecord(sanitized);
                } finally {
                    disruptor.getRingBuffer().publish(sequence);
                }
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
     * 手动触发 flush，立即排干缓冲写入存储（不停止管道）。定时 flush 间隔内的
     * 持久化验证（嵌入方与测试在管道存活期的确定性断言）经此入口完成。
     */
    public void flush() {
        if (batchHandler != null) {
            batchHandler.flush();
        }
    }

    /**
     * 优雅停止录制器：翻转相位截住新发布（微秒级持锁）→ flush 剩余 → 排空
     * RingBuffer（有界等待）→ flush 尾批 → 强制停机并结算滞留。整个关停过程
     * 不阻塞生产者；幂等——进行中或已完成的再次调用立即返回。
     */
    public void stop() {
        stop(10, TimeUnit.SECONDS);
    }

    /**
     * 停止实现核：时限只约束 RingBuffer 排空等待。包内可见供测试以短时限驱动
     * 超时结算路径；公开 API 恒为 10 秒。
     */
    void stop(long drainTimeout, TimeUnit unit) {
        synchronized (this) {
            synchronized (publishLock) {
                if (phase != Phase.RUNNING) {
                    return;
                }
                // 握手点：持锁期间在途的发布必然已完成；翻转后不再有任何发布能
                // 通过 intercept 的临界区复核——待排空的事件集自此封闭
                phase = Phase.DRAINING;
            }
        }

        // 自此生产者自由流动（DRAINING 相位到达按丢弃计数）；排空与停机全部锁外
        try {
            if (batchHandler != null) {
                batchHandler.flush();
                batchHandler.stopFlushScheduler();
            }

            long lastPublished = disruptor.getRingBuffer().getCursor();
            long deadline = System.nanoTime() + unit.toNanos(drainTimeout);
            while (System.nanoTime() < deadline
                    && disruptor.getRingBuffer().getMinimumGatingSequence() < lastPublished) {
                if (Thread.currentThread().isInterrupted()) {
                    // 中断按时限到处理：立即停机并结算（parkNanos 被中断会立即返回，
                    // 不检查标志会让剩余等待变成空转），中断标志保留给调用方
                    break;
                }
                LockSupport.parkNanos(1_000_000L);
            }

            // 排空后的尾批：消费线程在排空期间新缓冲的记录在此落库
            if (batchHandler != null) {
                batchHandler.flush();
            }

            disruptor.halt();

            // 超时滞留结算：已发布但未被消费的事件按丢弃计数，闭合公式在 stop 返回后
            // 成立。例外是存储调用悬挂超过时限的在途批次——它可能在结算之后才补记
            // written，该一次性不精确在契约 9 披露
            long stranded = lastPublished - disruptor.getRingBuffer().getMinimumGatingSequence();
            if (stranded > 0) {
                droppedCount.addAndGet(stranded);
                log.warn("Stop drain timeout ({} ms): {} published record(s) not consumed, counted as dropped; an in-flight storage call may still settle later",
                        unit.toMillis(drainTimeout), stranded);
            }
        } catch (Exception e) {
            log.error("Error during InteractionRecorder shutdown: {}", e.getMessage(), e);
        } finally {
            phase = Phase.STOPPED;
            log.info("InteractionRecorder stopped, recorded={}, filtered={}, dropped(ringBuffer={}, bufferOverflow={}), written={}, failed={}", recordedCount.get(), filteredCount.get(), droppedCount.get(), batchHandler != null ? batchHandler.getDroppedCount() : 0, batchHandler != null ? batchHandler.getWrittenCount() : 0, batchHandler != null ? batchHandler.getFailedCount() : 0);
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
     * 过滤告警间隔规则：首条被滤记录告警一次，此后每满 100 条重申一次。告警本体经
     * SLF4J 发射，间隔规则提取为纯函数以便确定性验证。
     */
    static boolean shouldWarnOnFilter(long filteredTotal) {
        return filteredTotal == 1 || filteredTotal % FILTERED_WARN_INTERVAL == 0;
    }

    /**
     * 总丢弃数 = 生产侧（RingBuffer 满/发布异常）+ 消费侧（缓冲超限）。
     * 两个计数器分属不同线程域，聚合规则以本方法为准。
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

    /**
     * 生命周期状态：RUNNING 相位为 true——start() 成功后、stop() 翻转相位前；
     * 关停进行中即为 false。enabled=false 的录制器恒为 false（整体 no-op）。
     * 生命周期断言与嵌入方的状态查询经此查询。
     */
    public boolean isStarted() {
        return phase == Phase.RUNNING;
    }
}
