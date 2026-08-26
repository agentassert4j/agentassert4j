package io.github.agentassert4j.spi;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.ToolCallResult;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 LLM 客户端 — 覆盖 80%+ 场景。
 *
 * <p>使用 java.net.http.HttpClient（JDK 11+），零 SDK 依赖。
 * 兼容 Azure OpenAI / 通义千问 / DeepSeek / Gemini 等 OpenAI API 格式。</p>
 *
 * <h3>核心流程</h3>
 * <pre>
 * buildRequestBody(LlmRequest) → HTTP POST → parseResponse(String) → LlmResponse
 * </pre>
 */
public class OpenAiCompatibleClient implements LlmClient {

    private final String endpoint;
    private final String apiKey;
    private final String defaultModel;
    private final HttpClient httpClient;

    /**
     * 构造客户端。
     *
     * @param endpoint     API 端点，如 "https://api.openai.com"
     * @param apiKey       API Key
     * @param defaultModel 默认模型，如 "gpt-4o"
     */
    public OpenAiCompatibleClient(String endpoint, String apiKey, String defaultModel) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 可注入自定义 HttpClient 的构造器（供测试使用）。
     */
    public OpenAiCompatibleClient(String endpoint, String apiKey, String defaultModel,
                                  HttpClient httpClient) {
        this.endpoint = normalizeEndpoint(endpoint);
        this.apiKey = apiKey;
        this.defaultModel = defaultModel;
        this.httpClient = httpClient;
    }

    /** 默认重试次数 */
    private static final int DEFAULT_MAX_RETRIES = 2;

    @Override
    public LlmResponse chat(LlmRequest request, long timeoutMs)
            throws LlmTimeoutException, LlmApiException {

        String model = request.getModel() != null ? request.getModel() : this.defaultModel;
        String body = buildRequestBody(request, model);

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(endpoint + "/v1/chat/completions"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofMillis(timeoutMs))
                .build();

        Exception lastException = null;
        long startNanos = System.nanoTime();

        for (int attempt = 0; attempt <= DEFAULT_MAX_RETRIES; attempt++) {
            if (attempt > 0) {
                // 指数退避：1s, 2s, 4s ...
                try {
                    Thread.sleep((long) (1000 * Math.pow(2, attempt - 1)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new LlmApiException("Retry interrupted", e);
                }
            }

            try {
                HttpResponse<String> response = httpClient.send(httpRequest,
                        HttpResponse.BodyHandlers.ofString());

                int statusCode = response.statusCode();

                if (statusCode == 200) {
                    LlmResponse parsed = parseResponse(response.body());
                    // 端到端墙钟（含重试等待）：latencyMs 的语义是整次调用的耗时
                    parsed.setLatencyMs((System.nanoTime() - startNanos) / 1_000_000L);
                    return parsed;
                }

                // 可重试的状态码
                if (statusCode == 429 || statusCode >= 500) {
                    lastException = new LlmApiException(
                            "HTTP " + statusCode + ": " + response.body());
                    continue;
                }

                // 不可重试的客户端错误
                throw new LlmApiException(
                        "HTTP " + statusCode + ": " + response.body());

            } catch (IOException e) {
                if (e instanceof ConnectException) {
                    lastException = new LlmApiException("Connection failed: " + e.getMessage(), e);
                    continue;
                }
                lastException = e;
                continue;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmApiException("Request interrupted", e);
            } catch (LlmApiException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
                continue;
            }
        }

        // 所有重试耗尽
        if (lastException instanceof LlmApiException) {
            throw (LlmApiException) lastException;
        }
        throw new LlmApiException("All retries exhausted: " +
                (lastException != null ? lastException.getMessage() : "unknown error"),
                lastException);
    }

    @Override
    public String name() {
        return defaultModel;
    }

    @Override
    public boolean isAvailable() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint + "/v1/models"))
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
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

        // temperature
        sb.append(",\"temperature\":").append(request.getTemperature());

        // messages
        sb.append(",\"messages\":[");

        boolean first = true;

        // system message
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            sb.append("{\"role\":\"system\",\"content\":\"")
                    .append(escapeJson(request.getSystemPrompt())).append("\"}");
            first = false;
        }

        // previousTurns（多轮上下文）
        if (request.getPreviousTurns() != null) {
            for (var turn : request.getPreviousTurns()) {
                if (!first) sb.append(",");
                String role = turn.getRole();
                // OpenAI 不支持 role="tool" 在 messages 中，统一用 user/assistant
                // 但实际上 OpenAI 2024+ 已支持 tool role
                sb.append("{\"role\":\"").append(escapeJson(role))
                        .append("\",\"content\":\"")
                        .append(escapeJson(turn.getContent())).append("\"}");
                first = false;
            }
        }

        // user message
        if (request.getUserInput() != null) {
            if (!first) sb.append(",");
            if (request.isMultimodalInput()) {
                // 多模态：userInput 存储的是 JSON 数组，原样注入
                sb.append("{\"role\":\"user\",\"content\":")
                        .append(request.getUserInput()).append("}");
            } else {
                sb.append("{\"role\":\"user\",\"content\":\"")
                        .append(escapeJson(request.getUserInput())).append("\"}");
            }
        }

        sb.append("]");

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
     * finish_reason 归一为固定枚举：stop/tool_calls/max_tokens/content_filter/error/other。
     * OpenAI 方言值 tool_calls/function_call → tool_calls；未知值归 other（TEXT 枚举加值零迁移）。
     */
    static String normalizeFinishReason(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        return switch (raw) {
            case "stop" -> "stop";
            case "tool_calls", "function_call" -> "tool_calls";
            case "max_tokens", "length" -> "max_tokens";
            case "content_filter" -> "content_filter";
            default -> "other";
        };
    }

    /**
     * 从 JSON 中提取 tool_calls 数组。
     * 格式：[{"id":"...","type":"function","function":{"name":"...","arguments":"{...}"}}]
     */
    private List<ToolCallResult> parseToolCalls(String body) {
        List<ToolCallResult> results = new ArrayList<>();

        // 找到 "tool_calls":[  的位置
        int tcStart = body.indexOf("\"tool_calls\":[");
        if (tcStart < 0) return results;

        int arrStart = body.indexOf('[', tcStart);
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
        if (openPos >= json.length() || json.charAt(openPos) != '{'
                && json.charAt(openPos) != '[') return -1;

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

    private String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case 'n': sb.append('\n'); i++; break;
                    case 'r': sb.append('\r'); i++; break;
                    case 't': sb.append('\t'); i++; break;
                    case '"': sb.append('"'); i++; break;
                    case '\\': sb.append('\\'); i++; break;
                    case '/': sb.append('/'); i++; break;
                    default: sb.append(c);
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String normalizeEndpoint(String endpoint) {
        if (endpoint == null) return "https://api.openai.com";
        return endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
    }
}
