package io.github.agentassert4j.spi;

/**
 * LLM API 错误异常。
 */
public class LlmApiException extends Exception {
    public LlmApiException(String message) { super(message); }
    public LlmApiException(String message, Throwable cause) { super(message, cause); }
}
