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
     * <p>超时契约：{@code timeoutMs} 是<b>单次尝试</b>的预算——连接与读取
     * 各自不得超过该值。任何一次尝试超时必须立即抛出 {@link LlmTimeoutException}，
     * 不得重试（预算已耗尽，重试只会翻倍证据成本）。可重试的失败仅限
     * HTTP 429/5xx 与连接被拒等传输层错误，重试次数由客户端实现自行约定。</p>
     *
     * @param request   LLM 请求
     * @param timeoutMs 单次尝试的超时毫秒数（连接与读取各自的上限）
     * @return LLM 响应
     * @throws LlmTimeoutException 任一次尝试超时（不重试，直接抛出）
     * @throws LlmApiException     API 错误或可重试失败耗尽重试次数
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
