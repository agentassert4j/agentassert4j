package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * HTTP LLM 客户端共享基座 — 各 wire 协议客户端（OpenAI chat / Anthropic Messages /
 * OpenAI Responses）的公共管道：连接与单次超时契约、429/5xx/拒连的指数退避重试、
 * 响应体读取上限、健康探测。基于 JDK 内置 HttpURLConnection（Java 8 可用），零 SDK 依赖。
 *
 * <p>超时契约：{@code timeoutMs} 是单次尝试的预算（连接与读取各自上限）；任一次尝试
 * 超时立即判 {@link LlmTimeoutException} 不重试；可重试失败仅限 429/5xx 与连接被拒。</p>
 *
 * <p>子类按 wire 方言实现四个模板点：请求路径、协议头装饰、请求体组装、响应体解析。
 * 请求体组装的帧合成不变量（三协议同一条）：工具结果帧必须携带配对键，缺失的帧跳过
 * 并可见告警，绝不构造会被服务端 400 拒绝的请求；文法要求显式发起帧而录制侧无独立
 * 载体时，从结果帧的关联键合成最小合法发起帧，同一配对只合成一次；system 恒走协议
 * 的 system 位，历史 system 帧一律跳过。</p>
 *
 * <p>健康探测口径统一为传输层可达性而非鉴权校验：GET models 端点，2xx/404/405 均算
 * 可达——鉴权有效性在首个真实调用暴露（重放调用必然发生，无需在健康检查里验证）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public abstract class AbstractHttpLlmClient implements LlmClient {

    /**
     * 响应体读取上限（字节）——防异常端点拖垮客户端内存
     */
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

    final String endpoint;
    final String apiKey;
    final String defaultModel;
    final int maxRetries;
    /**
     * 厂商方言扩展字段——原样注入请求体顶层的 JSON 成员片段。
     * 例：DeepSeek V4 系模型默认开启思考态且思考 token 与输出共享预算，
     * 需注入 "thinking":{"type":"disabled"} 才能拿到非空正文。
     * 客户端保持供应商中立，不做任何按模型名的硬编码分支，
     * 由使用方按所接厂商在构造时声明；片段必须为合法 JSON 成员序列，
     * 非法时服务端以 400 拒绝——错误显式可见，不做静默修正。
     */
    final String extraBodyFields;

    /**
     * 构造客户端。
     *
     * @param endpoint        API 端点基址（协议路径由 {@link #requestPath()} 给出）
     * @param apiKey          API Key
     * @param defaultModel    默认模型（请求未显式携带 model 时采用）
     * @param maxRetries      传输层失败（429/5xx/连接被拒）的最大重试次数，负数按 0 处理
     * @param extraBodyFields 原样注入请求体顶层的 JSON 成员片段，null 或空白表示无扩展；
     *                        须为合法 JSON 成员序列，否则请求将被服务端拒绝
     */
    protected AbstractHttpLlmClient(String endpoint, String apiKey, String defaultModel, int maxRetries, String extraBodyFields) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.maxRetries = Math.max(0, maxRetries);
        this.extraBodyFields = extraBodyFields != null && !extraBodyFields.trim().isEmpty() ? extraBodyFields.trim() : null;
    }

    /**
     * 协议请求路径（含前导斜杠），如 "/v1/chat/completions"。
     */
    protected abstract String requestPath();

    /**
     * 协议头装饰——鉴权与协议版本头由子类追加（POST、Content-Type 与超时由基座统一设置）。
     */
    protected abstract void decorateConnection(HttpURLConnection conn);

    /**
     * 组装协议请求体（含默认模型解析后的最终 model）。
     */
    protected abstract String buildRequestBody(LlmRequest request, String model);

    /**
     * 解析协议响应体；不是合法 JSON 对象时抛 {@link LlmApiException}，
     * 合法但缺成员时对应字段保持 null，退化不中断。
     */
    protected abstract LlmResponse parseResponse(String body) throws LlmApiException;

    static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) return "https://api.openai.com";
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }

    @Override
    public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {

        String model = request.getModel() != null ? request.getModel() : this.defaultModel;
        String body = buildRequestBody(request, model);

        Exception lastException = null;
        long startNanos = System.nanoTime();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            if (attempt > 0) {
                // 指数退避：1s, 2s, 4s ...
                try {
                    Thread.sleep((long) (1000 * Math.pow(2, attempt - 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LlmApiException("Retry interrupted", e);
                }
            }

            HttpURLConnection conn = null;
            try {
                conn = openConnection(timeoutMs);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.getBytes(StandardCharsets.UTF_8));
                }

                int statusCode = conn.getResponseCode();
                String responseBody = readBody(conn);

                if (statusCode == 200) {
                    LlmResponse parsed = parseResponse(responseBody);
                    // 端到端墙钟（含重试等待）：latencyMs 的语义是整次调用的耗时
                    parsed.setLatencyMs((System.nanoTime() - startNanos) / 1_000_000L);
                    return parsed;
                }

                // 可重试的状态码
                if (statusCode == 429 || statusCode >= 500) {
                    lastException = new LlmApiException("HTTP " + statusCode + ": " + responseBody);
                    continue;
                }

                // 不可重试的客户端错误
                throw new LlmApiException("HTTP " + statusCode + ": " + responseBody);

            } catch (SocketTimeoutException e) {
                // 单次尝试的超时预算已耗尽：立即判超时，不重试
                throw new LlmTimeoutException("LLM call timed out after " + timeoutMs + "ms", e);
            } catch (ConnectException e) {
                // 连接被拒是声明契约中唯一可重试的 IO 故障（对端暂时不可达）
                lastException = new LlmApiException("Connection failed: " + e.getMessage(), e);
            } catch (IOException e) {
                // 其余 IO 故障（读中断/流意外关闭）不在可重试集合内——
                // 重试洗白只会放大耗时与费用，且让真实故障形态失真
                throw new LlmApiException("I/O error during LLM call: " + e.getMessage(), e);
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        // 所有重试耗尽
        if (lastException instanceof LlmApiException) {
            throw (LlmApiException) lastException;
        }
        throw new LlmApiException("All retries exhausted: " + (lastException != null ? lastException.getMessage() : "unknown error"), lastException);
    }

    private HttpURLConnection openConnection(long timeoutMs) throws IOException {
        URL url = new URL(endpoint + requestPath());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        // 单次尝试预算：连接与读取各自以 timeoutMs 为上限（总耗时另含重试与退避等待）
        conn.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        conn.setReadTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        decorateConnection(conn);
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream stream = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (stream == null) {
            return "";
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = stream.read(chunk)) != -1) {
            if (buffer.size() + read > MAX_RESPONSE_BYTES) {
                // 异常端点可能返回任意大小的响应体，无上限会拖垮客户端内存
                throw new IOException("LLM response body exceeds the " + MAX_RESPONSE_BYTES + "-byte limit; read aborted");
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public boolean isAvailable() {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(endpoint + "/v1/models");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("GET");
            decorateConnection(conn);
            int code = conn.getResponseCode();
            // 可达性而非鉴权校验：models 端点不存在的兼容端点回 404/405 也证明传输层在
            return (code >= 200 && code < 300) || code == 404 || code == 405;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}
