package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.InteractionRecord;

import java.util.List;

/**
 * 交互记录写入域 SPI — 录制管道的唯一天然消费者。
 *
 * <p>interactions 是只追加历史：实现方必须保证同 record_id 重复写入不覆盖已有行
 * （如 SQLite 的 INSERT OR IGNORE），崩溃重放双写不得篡改已落库数据。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface InteractionWriteStore {

    /**
     * 写入单条交互记录并回告是否真正落库：record_id 已存在 = duplicate（返回 false），
     * 新写入 = saved（返回 true）。摄取方据此如实报告，不靠异常区分。
     */
    boolean saveInteractionIfAbsent(InteractionRecord r);

    /**
     * 批量写入（实现方可做事务优化；record_id 冲突静默跳过，不逐条回告）
     */
    void saveInteractions(List<InteractionRecord> records);
}
