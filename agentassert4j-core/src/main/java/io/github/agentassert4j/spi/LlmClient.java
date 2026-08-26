package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;

/**
 * LLM 客户端 SPI — 回归测试时调用 LLM API。
 *
 * <p>内置 OpenAI 兼容客户端覆盖 80%+ 场景。
 * 使用 java.net.http.HttpClient（JDK 自带，零 SDK 依赖）。</p>
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
    LlmResponse chat(LlmRequest request, long timeoutMs)
        throws LlmTimeoutException, LlmApiException;

    /** 客户端名称 */
    String name();

    /** 健康检查 */
    boolean isAvailable();
}
