package io.github.agentassert4j.spi;

/**
 * LLM 超时异常。
 */
public class LlmTimeoutException extends Exception {
    public LlmTimeoutException(String message) {
        super(message);
    }

    public LlmTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
