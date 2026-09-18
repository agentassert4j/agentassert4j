package io.github.agentassert4j.langchain4j;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.spi.RecordingInterceptor;

import java.util.ArrayList;
import java.util.List;

/**
 * 测试桩：内存捕获录制拦截器的全部记录。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class CapturingRecorder implements RecordingInterceptor {

    private final List<InteractionRecord> records = new ArrayList<>();

    @Override
    public void intercept(InteractionRecord record) {
        records.add(record);
    }

    List<InteractionRecord> records() {
        return records;
    }

    InteractionRecord sole() {
        if (records.size() != 1) {
            throw new IllegalStateException("expected exactly one record, got " + records.size());
        }
        return records.get(0);
    }
}
