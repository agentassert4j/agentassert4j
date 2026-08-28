package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.ToolCallResult;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 LLM 客户端
 *
 * <p>基于 JDK 内置 HttpURLConnection（Java 8 可用），零 SDK 依赖。
 * 兼容 Azure OpenAI / 通义千问 / DeepSeek / Gemini 等 OpenAI API 格式。</p>
 *
 * <h3>核心流程</h3>
 * <pre>
 * buildRequestBody(LlmRequest) → HTTP POST → parseResponse(String) → LlmResponse
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public class OpenAiCompatibleClient implements LlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    /**
     * 默认重试次数（传输层失败的最大重试），供组装根构造客户端时引用
     */
    public static final int DEFAULT_MAX_RETRIES = 2;
    /**
     * 响应体读取上限（字节）——防异常端点拖垮客户端内存
     */
    static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;
    /**
     * 内置方言裁剪规则（数据文件驱动）：命中模型省略「发送即报错」的标准参数
     */
    private static final ProviderDialects DIALECTS = ProviderDialects.load();
    private final String endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final int maxRetries;
    /**
     * 厂商方言扩展字段——原样注入请求体顶层的 JSON 成员片段。
     * 例：DeepSeek V4 系模型默认开启思考态且思考 token 与输出共享预算，
     * 需注入 "thinking":{"type":"disabled"} 才能拿到非空正文。
     * 客户端保持供应商中立，不做任何按模型名的硬编码分支，
     * 由使用方按所接厂商在构造时声明；片段必须为合法 JSON 成员序列，
     * 非法时服务端以 400 拒绝——错误显式可见，不做静默修正。
     */
    private final String extraBodyFields;
    /**
     * 方言裁剪告警只发一次——批量重放对同一模型逐请求告警会淹没输出
     */
    private boolean dialectWarned;

    /**
     * 构造客户端（默认重试 2 次，无厂商方言扩展）。
     *
     * @param endpoint     API 端点，如 "https://api.openai.com"
     * @param apiKey       API Key
     * @param defaultModel 默认模型，如 "gpt-4o"
     */
    public OpenAiCompatibleClient(String endpoint, String apiKey, String defaultModel) {
        this(endpoint, apiKey, defaultModel, DEFAULT_MAX_RETRIES, null);
    }

    /**
     * 构造客户端（无厂商方言扩展）。
     *
     * @param endpoint     API 端点，如 "https://api.openai.com"
     * @param apiKey       API Key
     * @param defaultModel 默认模型，如 "gpt-4o"
     * @param maxRetries   传输层失败（429/5xx/连接被拒）的最大重试次数，负数按 0 处理
     */
    public OpenAiCompatibleClient(String endpoint, String apiKey, String defaultModel, int maxRetries) {
        this(endpoint, apiKey, defaultModel, maxRetries, null);
    }

    /**
     * 构造客户端。
     *
     * @param endpoint        API 端点，如 "https://api.deepseek.com"
     * @param apiKey          API Key
     * @param defaultModel    默认模型，如 "gpt-4o"
     * @param maxRetries      传输层失败（429/5xx/连接被拒）的最大重试次数，负数按 0 处理
     * @param extraBodyFields 原样注入请求体顶层的 JSON 成员片段（如 "thinking":{"type":"disabled"}），
     *                        null 或空白表示无扩展；须为合法 JSON 成员序列，否则请求将被服务端拒绝
     */
    public OpenAiCompatibleClient(String endpoint, String apiKey, String defaultModel, int maxRetries, String extraBodyFields) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.maxRetries = Math.max(0, maxRetries);
        this.extraBodyFields = extraBodyFields != null && !extraBodyFields.trim().isEmpty() ? extraBodyFields.trim() : null;
    }

    /**
     * finish_reason 归一为固定枚举：stop/tool_calls/max_tokens/content_filter/error/other。
     * OpenAI 方言值 tool_calls/function_call → tool_calls；未知值归 other（TEXT 枚举加值零迁移）。
     */
    static String normalizeFinishReason(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        switch (raw) {
            case "stop":
                return "stop";
            case "tool_calls":
            case "function_call":
                return "tool_calls";
            case "max_tokens":
            case "length":
                return "max_tokens";
            case "content_filter":
                return "content_filter";
            default:
                return "other";
        }
    }

    private static String normalizeEndpoint(String endpoint) {
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
                conn = openChatConnection(timeoutMs);
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

    private HttpURLConnection openChatConnection(long timeoutMs) throws IOException {
        URL url = new URL(endpoint + "/v1/chat/completions");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        // 单次尝试预算：连接与读取各自以 timeoutMs 为上限（总耗时另含重试与退避等待）
        conn.setConnectTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        conn.setReadTimeout((int) Math.min(timeoutMs, Integer.MAX_VALUE));
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
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
                throw new IOException("LLM 响应体超过 " + MAX_RESPONSE_BYTES + " 字节上限，已中止读取");
            }
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    @Override
    public String name() {
        return defaultModel;
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
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /**
     * 合成"assistant 发起工具调用"消息帧：历史录制没有该轮的独立载体，
     * arguments 以空对象占位（协议校验只看结构与 id/name 的对应关系），
     * 待 SDK 接入层为历史轮保存完整调用参数后可替换为真实值。
     */
    private static void appendSyntheticAssistantToolCall(StringBuilder sb, TurnContext toolTurn) {
        String callId = toolTurn.getToolCallId() != null ? toolTurn.getToolCallId() : "";
        String name = toolTurn.getToolName() != null ? toolTurn.getToolName() : "";
        sb.append("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"").append(escapeJson(callId)).append("\",\"type\":\"function\",\"function\":{\"name\":\"").append(escapeJson(name)).append("\",\"arguments\":\"{}\"}}]}");
    }

    /**
     * 构建 OpenAI Chat Completion 请求体。
     *
     * <p>消息格式：</p>
     * <pre>
     * {"role":"system","content":"..."}     ← systemPrompt
     * {"role":"user","content":"..."}       ← userInput
     * {"role":"assistant","content":"..."}  ← previousTurns(role=assistant)
     * {"role":"user","content":"..."}       ← previousTurns(role=user)
     * {"role":"tool","content":"..."}       ← previousTurns(role=tool)
     * </pre>
     *
     * <p>工具定义（可选）：</p>
     * <pre>
     * "tools":[{"type":"function","function":{"name":"...","parameters":{...}}}]
     * </pre>
     */
    String buildRequestBody(LlmRequest request, String model) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(escapeJson(model)).append("\"");

        // temperature——null 表示不携带该成员（推理模型方言：发送 0.0 会被 400 拒绝）；
        // 非 finite 值同样省略（JSON 无此字面量，发出即非法请求）；
        // 方言注册表命中的模型（o 系/gpt-5 系只接受默认温度）整条裁掉。
        // 显式配置被注册表覆盖必须告警——静默丢配置是排障黑洞，逃生舱（extraBody）要点名
        if (request.getTemperature() != null && Double.isFinite(request.getTemperature()) && !DIALECTS.droppedParamsFor(model).contains("temperature")) {
            sb.append(",\"temperature\":").append(request.getTemperature());
        } else if (request.getTemperature() != null && Double.isFinite(request.getTemperature()) && !dialectWarned && DIALECTS.droppedParamsFor(model).contains("temperature")) {
            dialectWarned = true;
            LOG.warn("模型 {} 属方言裁剪族：显式配置的 temperature 已从请求中移除（该模型族对标准温度参数返回 400）；如需覆盖请改用 llm.extraBody 注入。", model);
        }

        // messages
        StringBuilder messages = new StringBuilder();
        boolean wroteAny = false;

        // system message
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            messages.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(request.getSystemPrompt())).append("\"}");
            wroteAny = true;
        }

        // previousTurns（多轮上下文）
        if (request.getPreviousTurns() != null) {
            String lastEmittedToolCallId = null;
            for (TurnContext turn : request.getPreviousTurns()) {
                String role = turn.getRole();
                if ("system".equals(role)) {
                    // 系统提示属模板域，由 systemPrompt 成员承载——历史轮中的 system 帧
                    // 混进消息序列会被服务端当作异常位置拒绝
                    continue;
                }
                if ("tool".equals(role)) {
                    String callId = turn.getToolCallId();
                    if (callId == null || callId.trim().isEmpty()) {
                        // 缺失/空 callId 的 tool 帧必被服务端 400 拒绝整个请求——
                        // 跳过该轮保住其余用例，丢弃事实显式告警
                        System.err.println("警告：历史轮 tool 消息缺失 toolCallId，重放请求已跳过该轮（content 长度 " + (turn.getContent() == null ? 0 : turn.getContent().length()) + "）。");
                        continue;
                    }
                    // OpenAI 方言的硬约束：tool 消息必须紧跟在携带同 id tool_calls 的
                    // assistant 消息之后，否则服务端以 400 拒绝整个请求。
                    // 录制模型的历史轮没有"assistant 发起调用"的独立载体（无处存放
                    // arguments），渲染层按已知 id/toolName 合成最小合法请求帧补齐协议
                    if (!callId.equals(lastEmittedToolCallId)) {
                        if (wroteAny) messages.append(",");
                        appendSyntheticAssistantToolCall(messages, turn);
                        wroteAny = true;
                        lastEmittedToolCallId = callId;
                    }
                } else {
                    lastEmittedToolCallId = null;
                }
                if (wroteAny) messages.append(",");
                messages.append("{\"role\":\"").append(escapeJson(role)).append("\"");
                // tool 角色消息必须携带 tool_call_id 才能关联到前序 assistant 的调用决策，
                // 缺失时服务端以 400 拒绝整个请求
                if ("tool".equals(role)) {
                    messages.append(",\"tool_call_id\":\"").append(escapeJson(turn.getToolCallId())).append("\"");
                }
                messages.append(",\"content\":\"").append(escapeJson(turn.getContent())).append("\"}");
                wroteAny = true;
            }
        }

        // user message
        if (request.getUserInput() != null) {
            if (wroteAny) messages.append(",");
            if (request.isMultimodalInput()) {
                // 多模态：userInput 存储的是 JSON 数组，原样注入
                messages.append("{\"role\":\"user\",\"content\":").append(request.getUserInput()).append("}");
            } else {
                messages.append("{\"role\":\"user\",\"content\":\"").append(escapeJson(request.getUserInput())).append("\"}");
            }
        }

        sb.append(",\"messages\":[").append(messages).append("]");

        // tools 定义 — 允许 LLM 返回 tool_calls
        if (request.getToolDefinitions() != null && !request.getToolDefinitions().isEmpty()) {
            sb.append(",\"tools\":[");
            boolean firstTool = true;
            for (String toolDef : request.getToolDefinitions()) {
                if (!firstTool) sb.append(",");
                sb.append(toolDef);
                firstTool = false;
            }
            sb.append("]");
        }

        // 厂商方言扩展字段：位于全部标准成员之后，原样注入（model/messages 至少存在，逗号恒安全）
        if (extraBodyFields != null) {
            sb.append(",").append(extraBodyFields);
        }

        // 关闭外层 JSON 对象
        sb.append("}");

        return sb.toString();
    }

    /**
     * 解析 OpenAI Chat Completion 响应体。
     *
     * <p>提取：</p>
     * <ul>
     *   <li>choices[0].message.content → 文本输出</li>
     *   <li>choices[0].message.tool_calls → 工具调用决策</li>
     *   <li>usage.prompt_tokens / completion_tokens → token 统计</li>
     *   <li>model → served_model（版本化快照）；choices[0].finish_reason → 结束原因</li>
     *   <li>usage 子树逐字保留为 usage_raw——后续新增遥测列的回填来源</li>
     *   <li>prompt_tokens_details.cached_tokens / completion_tokens_details.reasoning_tokens
     *       → 方言归一化（仅捕获层允许持有方言知识，schema 存概念列）</li>
     * </ul>
     */
    LlmResponse parseResponse(String body) throws LlmApiException {
        try {
            LlmResponse response = new LlmResponse();

            // 提取 choices[0].message.content
            String content = extractStringField(body, "content");
            response.setContent(content);

            // 提取 tool_calls
            List<ToolCallResult> toolCalls = parseToolCalls(body);
            response.setToolCalls(toolCalls);

            // 提取 usage 子树：原文保留 + 概念归一
            String usageSection = extractSection(body, "usage");
            if (usageSection != null) {
                response.setUsageRaw(usageSection);
                String promptTokens = extractNumericField(usageSection, "prompt_tokens");
                String completionTokens = extractNumericField(usageSection, "completion_tokens");
                if (promptTokens != null) response.setInputTokens(Integer.parseInt(promptTokens));
                if (completionTokens != null) response.setOutputTokens(Integer.parseInt(completionTokens));

                // input_tokens 语义钉死为"总处理输入 token"：
                // OpenAI/DeepSeek 的 prompt_tokens 已是总量；Anthropic 合成规则由其专属客户端实现
                String promptDetails = extractSection(usageSection, "prompt_tokens_details");
                if (promptDetails != null) {
                    String cached = extractNumericField(promptDetails, "cached_tokens");
                    if (cached != null) response.setCacheReadTokens(Integer.parseInt(cached));
                }
                String completionDetails = extractSection(usageSection, "completion_tokens_details");
                if (completionDetails != null) {
                    String reasoning = extractNumericField(completionDetails, "reasoning_tokens");
                    if (reasoning != null) response.setReasoningTokens(Integer.parseInt(reasoning));
                }
            }

            // 响应报告的实际服务模型（首个顶层 "model" 字段）
            response.setServedModel(extractStringField(body, "model"));

            // choices[0].finish_reason
            String finishReason = extractStringField(body, "finish_reason");
            response.setFinishReason(normalizeFinishReason(finishReason));

            response.setHasError(false);
            return response;

        } catch (Exception e) {
            if (e instanceof LlmApiException) throw (LlmApiException) e;
            throw new LlmApiException("Failed to parse response: " + e.getMessage(), e);
        }
    }

    /**
     * 从键名结束位置向后定位值数组的起始 '['：跳过空白与冒号。
     * 形态不符返回 -1（交由上层按"无工具调用"处理）。
     */
    private static int findArrayStartAfterKey(String body, int from) {
        int i = from;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length() || body.charAt(i) != ':') return -1;
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) i++;
        if (i >= body.length() || body.charAt(i) != '[') return -1;
        return i;
    }

    /**
     * 从 JSON 中提取 tool_calls 数组。
     * 格式：[{"id":"...","type":"function","function":{"name":"...","arguments":"{...}"}}]
     */
    private List<ToolCallResult> parseToolCalls(String body) {
        List<ToolCallResult> results = new ArrayList<>();

        // 找到 "tool_calls" 键后容忍键与数组间的空白（部分网关会格式化响应体）
        int keyStart = body.indexOf("\"tool_calls\"");
        if (keyStart < 0) return results;
        int arrStart = findArrayStartAfterKey(body, keyStart + "\"tool_calls\"".length());
        if (arrStart < 0) return results;

        int arrEnd = findMatchingBracket(body, arrStart);
        if (arrEnd < 0) return results;

        String arrContent = body.substring(arrStart + 1, arrEnd);

        // 逐个解析 {...}
        int pos = 0;
        while (pos < arrContent.length()) {
            int objStart = arrContent.indexOf('{', pos);
            if (objStart < 0) break;
            int objEnd = findMatchingBracket(arrContent, objStart);
            if (objEnd < 0) break;

            String obj = arrContent.substring(objStart, objEnd + 1);

            ToolCallResult tc = new ToolCallResult();
            tc.setToolCallId(extractStringField(obj, "id"));

            // 从 function.name 提取
            String funcSection = extractSection(obj, "function");
            if (funcSection != null) {
                tc.setToolName(extractStringField(funcSection, "name"));
                // arguments 是 JSON 字符串，需要解析为 Map
                String argsStr = extractStringField(funcSection, "arguments");
                if (argsStr != null) {
                    tc.setArguments(parseArgsToMap(argsStr));
                }
            }

            results.add(tc);
            pos = objEnd + 1;
        }

        return results;
    }

    /**
     * 将 JSON arguments 字符串解析为 Map<String, Object>。
     * 简单的 key:value 解析，支持 String/Number/Boolean 基本类型。
     */
    private Map<String, Object> parseArgsToMap(String argsJson) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (argsJson == null || argsJson.isEmpty()) return map;

        // 去掉外层 {}
        String trimmed = argsJson.trim();
        if (trimmed.startsWith("{")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("}")) trimmed = trimmed.substring(0, trimmed.length() - 1);

        int pos = 0;
        while (pos < trimmed.length()) {
            // 找 key
            int keyStart = trimmed.indexOf('"', pos);
            if (keyStart < 0) break;
            int keyEnd = trimmed.indexOf('"', keyStart + 1);
            if (keyEnd < 0) break;
            String key = trimmed.substring(keyStart + 1, keyEnd);

            // 找冒号
            int colon = trimmed.indexOf(':', keyEnd);
            if (colon < 0) break;

            // 找 value
            int valStart = colon + 1;
            while (valStart < trimmed.length() && trimmed.charAt(valStart) == ' ') valStart++;

            if (valStart >= trimmed.length()) break;

            char c = trimmed.charAt(valStart);
            if (c == '"') {
                // String value
                int valEnd = findEndOfString(trimmed, valStart + 1);
                String val = unescapeJson(trimmed.substring(valStart + 1, valEnd));
                map.put(key, val);
                pos = valEnd + 2;
            } else if (c == '{' || c == '[') {
                // 嵌套对象/数组 — 找到匹配的结束括号
                int end = findMatchingBracket(trimmed, valStart);
                String nested = trimmed.substring(valStart, end + 1);
                map.put(key, nested); // 保留为字符串
                pos = end + 2;
            } else {
                // Number / Boolean / null
                int valEnd = valStart;
                while (valEnd < trimmed.length()) {
                    char vc = trimmed.charAt(valEnd);
                    if (vc == ',' || vc == '}' || vc == ']') break;
                    valEnd++;
                }
                String val = trimmed.substring(valStart, valEnd).trim();
                if ("true".equals(val)) {
                    map.put(key, Boolean.TRUE);
                } else if ("false".equals(val)) {
                    map.put(key, Boolean.FALSE);
                } else if ("null".equals(val)) {
                    map.put(key, null);
                } else {
                    try {
                        if (val.contains(".") || val.contains("e") || val.contains("E")) {
                            map.put(key, Double.parseDouble(val));
                        } else {
                            map.put(key, Long.parseLong(val));
                        }
                    } catch (NumberFormatException e) {
                        map.put(key, val);
                    }
                }
                pos = valEnd + 1;
            }
        }

        return map;
    }

    private String extractStringField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;

        int valStart = colon + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (valStart >= json.length()) return null;

        if (json.charAt(valStart) != '"') return null;
        int valEnd = findEndOfString(json, valStart + 1);
        if (valEnd < 0) return null;

        return unescapeJson(json.substring(valStart + 1, valEnd));
    }

    private String extractNumericField(String json, String field) {
        String key = "\"" + field + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;

        int valStart = colon + 1;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\n')) valStart++;
        if (valStart >= json.length()) return null;

        int valEnd = valStart;
        while (valEnd < json.length() && (Character.isDigit(json.charAt(valEnd)) || json.charAt(valEnd) == '-')) {
            valEnd++;
        }

        return json.substring(valStart, valEnd);
    }

    /**
     * 提取 JSON 中的嵌套 section（如 "usage":{...} 或 "function":{...}）
     */
    private String extractSection(String json, String section) {
        String key = "\"" + section + "\"";
        int idx = json.indexOf(key);
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + key.length());
        if (colon < 0) return null;

        int valStart = colon + 1;
        while (valStart < json.length() && json.charAt(valStart) == ' ') valStart++;
        if (valStart >= json.length() || json.charAt(valStart) != '{') return null;

        int valEnd = findMatchingBracket(json, valStart);
        if (valEnd < 0) return null;

        return json.substring(valStart, valEnd + 1);
    }

    private int findMatchingBracket(String json, int openPos) {
        if (openPos >= json.length() || json.charAt(openPos) != '{' && json.charAt(openPos) != '[') return -1;

        char open = json.charAt(openPos);
        char close = (open == '{') ? '}' : ']';
        int depth = 0;
        boolean inString = false;

        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && inString) {
                i++; // skip escaped char
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) continue;

            if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private int findEndOfString(String json, int start) {
        for (int i = start; i < json.length(); i++) {
            if (json.charAt(i) == '\\') {
                i++; // skip escaped char
                continue;
            }
            if (json.charAt(i) == '"') return i;
        }
        return -1;
    }

    /**
     * JSON 字符串转义。除常见短转义外，所有 &lt;0x20 控制字符按 JSON 规范
     * 强制转义为 \\uXXXX——用户输入携带原始控制字符时请求体必须仍是合法 JSON。
     */
    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private String unescapeJson(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n':
                        sb.append('\n');
                        i++;
                        break;
                    case 'r':
                        sb.append('\r');
                        i++;
                        break;
                    case 't':
                        sb.append('\t');
                        i++;
                        break;
                    case '"':
                        sb.append('"');
                        i++;
                        break;
                    case '\\':
                        sb.append('\\');
                        i++;
                        break;
                    case '/':
                        sb.append('/');
                        i++;
                        break;
                    case 'b':
                        sb.append('\b');
                        i++;
                        break;
                    case 'f':
                        sb.append('\f');
                        i++;
                        break;
                    case 'u':
                        if (i + 5 < s.length()) {
                            try {
                                sb.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16));
                                i += 5;
                            } catch (NumberFormatException nfe) {
                                sb.append(c);
                            }
                        } else {
                            sb.append(c);
                        }
                        break;
                    default:
                        sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
