package io.github.agentassert4j.recorder;

import io.github.agentassert4j.model.InteractionRecord;

/**
 * Disruptor 事件载体 — RingBuffer 中每个槽位持有一个 InteractionRecord 引用。
 *
 * <p>Disruptor 要求 Event 类是可变的（通过 publishEvent 回调设置），
 * 因此这里使用 private 字段 + getter/setter 模式。</p>
 */
public class InteractionEvent {

    private InteractionRecord record;

    public InteractionRecord getRecord() {
        return record;
    }

    public void setRecord(InteractionRecord record) {
        this.record = record;
    }
}
