package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.InteractionRecord;

/**
 * 录制拦截器 SPI — 接入层实现此接口将交互数据转成 InteractionRecord。
 */
public interface RecordingInterceptor {

    /**
     * 拦截并录制一次 LLM 交互。
     * 实现必须是非阻塞的（通过 Disruptor 异步入队）。
     */
    void intercept(InteractionRecord record);
}
