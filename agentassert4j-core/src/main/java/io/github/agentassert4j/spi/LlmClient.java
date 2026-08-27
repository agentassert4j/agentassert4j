package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;

/**
 * LLM 客户端 SPI — 回归测试时调用 LLM API。
 *
 * <p>core 只定义契约；具体客户端实现由上层模块提供。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public interface LlmClient {

    /**
     * 同步调用（回归测试是同步阻塞的）。
     *
     * @param request   LLM 请求
     * @param timeoutMs 超时毫秒数
     * @return LLM 响应
     * @throws LlmTimeoutException 超时
     * @throws LlmApiException     API 错误
     */
    LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException;

    /**
     * 客户端名称
     */
    String name();

    /**
     * 健康检查
     */
    boolean isAvailable();
}
