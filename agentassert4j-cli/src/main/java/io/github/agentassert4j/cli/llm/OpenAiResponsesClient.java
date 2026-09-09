package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.ToolCallResult;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses 方言 LLM 客户端 — HTTP 管道与超时/重试契约见共享基座。
 *
 * <p>请求组装要点：system 恒走 {@code instructions}（历史 system 帧跳过）；{@code input}
 * 恒用 items 数组（message item 的文本 part 按 role 取 input_text/output_text）；工具
 * 历史帧为 function_call / function_call_output item 对（call_id 配对是该文法硬约束，
 * 违规 400），录制侧无发起帧载体时按已知 call_id/name 合成最小合法发起 item 且同一
 * 配对只合成一次；范式多模态的 image_url 直通（该文法同形收 url 与 data-URI）。有状态
 * 成员（previous_response_id/store）不携带——重放是无状态全量输入。max_output_tokens
 * 缺省不报（该文法无必填上限）。响应解析与 MCP 摄取共用
 * {@link OpenAiResponsesWireFormat} 的 finish 派生器。</p>
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public class OpenAiResponsesClient extends AbstractHttpLlmClient {

    /**
     * 构造客户端，参数语义与 OpenAI 兼容客户端一致（endpoint/apiKey/defaultModel/
     * maxRetries/extraBodyFields 见共享基座）。
     */
    public OpenAiResponsesClient(String endpoint, String apiKey, String defaultModel, int maxRetries, String extraBodyFields) {
        super(endpoint, apiKey, defaultModel, maxRetries, extraBodyFields);
    }

    @Override
    protected String requestPath() {
        return "/v1/responses";
    }

    @Override
    protected void decorateConnection(HttpURLConnection conn) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
    }

    @Override
    public String name() {
        return defaultModel;
    }

    @Override
    protected String buildRequestBody(LlmRequest request, String model) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(RecursiveJsonParser.escape(model)).append("\"");
        // system 恒走 instructions；温度与 chat 同规则——null/非 finite 省略
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            sb.append(",\"instructions\":\"").append(RecursiveJsonParser.escape(request.getSystemPrompt())).append("\"");
        }
        if (request.getTemperature() != null && Double.isFinite(request.getTemperature())) {
            sb.append(",\"temperature\":").append(request.getTemperature());
        }
        sb.append(",\"input\":[").append(buildInputItems(request)).append("]");

        String tools = buildTools(request.getToolDefinitions());
        if (tools != null) {
            sb.append(",\"tools\":").append(tools);
        }
        if (extraBodyFields != null) {
            sb.append(",").append(extraBodyFields);
        }
        sb.append("}");
        return sb.toString();
    }

    /**
     * input items 组装：历史轮逐帧展开 + 末位输入。工具帧逐对重建（function_call 与
     * function_call_output 相邻、call_id 一致）；缺失配对键的输出帧跳过并告警——
     * 绝不构造会被 400 拒绝的请求。
     */
    private static String buildInputItems(LlmRequest request) {
        StringBuilder items = new StringBuilder();
        boolean wroteAny = false;
        String lastEmittedCallId = null;

        if (request.getPreviousTurns() != null) {
            for (TurnContext turn : request.getPreviousTurns()) {
                String role = turn.getRole();
                if ("system".equals(role)) {
                    // 系统提示属模板域，由 instructions 承载——历史 system 帧跳过
                    continue;
                }
                if ("tool".equals(role)) {
                    String callId = turn.getToolCallId();
                    if (callId == null || callId.trim().isEmpty()) {
                        System.err.println("Warning: history tool message has no toolCallId; the turn was skipped in the replay request (content length " + (turn.getContent() == null ? 0 : turn.getContent().length()) + ").");
                        continue;
                    }
                    // 文法硬约束：function_call_output 必须紧跟同 call_id 的 function_call。
                    // 历史录制轮没有发起帧载体时按已知 call_id/name 合成最小合法发起 item；
                    // 同一配对只合成一次（链式半重放的合成帧自带真值时跳过补帧）
                    if (!callId.equals(lastEmittedCallId)) {
                        if (wroteAny) items.append(",");
                        items.append(functionCallItem(callId, turn.getToolName(), turn.getToolArguments()));
                        wroteAny = true;
                        lastEmittedCallId = callId;
                    }
                    if (wroteAny) items.append(",");
                    items.append("{\"type\":\"function_call_output\",\"call_id\":\"").append(RecursiveJsonParser.escape(callId)).append("\",\"output\":\"").append(RecursiveJsonParser.escape(turn.getContent() == null ? "" : turn.getContent())).append("\"}");
                    wroteAny = true;
                    continue;
                }
                // assistant 携带 toolCallId = 「模型发起工具调用」帧（链式半重放的合成帧）：
                // 渲染为 function_call item（arguments 有真值则无损携带，序列化为字符串——
                // 该文法的 arguments 是字符串形），紧随的输出帧据此跳过补帧
                if ("assistant".equals(role) && turn.getToolCallId() != null && !turn.getToolCallId().trim().isEmpty()) {
                    if (wroteAny) items.append(",");
                    items.append(functionCallItem(turn.getToolCallId(), turn.getToolName(), turn.getToolArguments()));
                    wroteAny = true;
                    lastEmittedCallId = turn.getToolCallId();
                    continue;
                }
                if (wroteAny) items.append(",");
                items.append(messageItem(role != null && "assistant".equals(role) ? "assistant" : "user", turn.getContent() == null ? "" : turn.getContent()));
                wroteAny = true;
                lastEmittedCallId = null;
            }
        }

        // 末位本轮输入（固定在最后）
        if (request.getUserInput() != null) {
            if (wroteAny) items.append(",");
            if (request.isMultimodalInput()) {
                items.append("{\"type\":\"message\",\"role\":\"user\",\"content\":").append(convertMultimodal(request.getUserInput())).append("}");
            } else {
                items.append(messageItem("user", request.getUserInput()));
            }
        }
        return items.toString();
    }

    /**
     * message item：content 为单文本 part，user 位用 input_text、assistant 位用
     * output_text（该文法的 part 类型按角色区分）。
     */
    private static String messageItem(String role, String text) {
        String partType = "assistant".equals(role) ? "output_text" : "input_text";
        return "{\"type\":\"message\",\"role\":\"" + role + "\",\"content\":[{\"type\":\"" + partType + "\",\"text\":\"" + RecursiveJsonParser.escape(text) + "\"}]}";
    }

    /**
     * function_call item：arguments 是字符串形（范式 Map/JSON 文本序列化为字符串），
     * 缺载体时以空对象字符串占位（文法只看结构与 call_id 配对）。
     */
    private static String functionCallItem(String callId, String toolName, String arguments) {
        String input = arguments != null && !arguments.isEmpty() ? arguments : "{}";
        Object parsed = RecursiveJsonParser.parse(input);
        String serialized = parsed instanceof Map ? RecursiveJsonParser.serialize(parsed) : "{}";
        return "{\"type\":\"function_call\",\"call_id\":\"" + RecursiveJsonParser.escape(callId) + "\",\"name\":\"" + RecursiveJsonParser.escape(toolName != null ? toolName : "") + "\",\"arguments\":\"" + RecursiveJsonParser.escape(serialized) + "\"}";
    }

    /**
     * 范式多模态数组（OpenAI content 数组）转该文法的 content parts：text part 直转
     * （input_text）、image_url 直通（该文法的 input_image 同形收 url 与 data-URI）；
     * 形态不符的 part 跳过（宁缺勿非法）。
     */
    private static String convertMultimodal(String paradigmJson) {
        List<Object> parts = new ArrayList<>();
        Object parsed = RecursiveJsonParser.parse(paradigmJson);
        if (parsed instanceof List) {
            for (Object part : (List<?>) parsed) {
                if (!(part instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) part;
                String type = map.get("type") instanceof String ? (String) map.get("type") : null;
                if ("text".equals(type)) {
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("type", "input_text");
                    p.put("text", map.get("text"));
                    parts.add(p);
                } else if ("image_url".equals(type) && map.get("image_url") instanceof Map) {
                    Object url = ((Map<?, ?>) map.get("image_url")).get("url");
                    if (url instanceof String) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("type", "input_image");
                        p.put("image_url", url);
                        parts.add(p);
                    }
                }
            }
        }
        return RecursiveJsonParser.serialize(parts);
    }

    /**
     * 范式工具定义（OpenAI tools 嵌套形）转该文法扁平形；解析失败或缺 name 的
     * 定义跳过——宁可不带也不构造非法请求（与 chat 侧「损坏定义不带」同策略）。
     */
    private static String buildTools(List<String> toolDefinitions) {
        if (toolDefinitions == null || toolDefinitions.isEmpty()) {
            return null;
        }
        List<Object> tools = new ArrayList<>();
        for (String definition : toolDefinitions) {
            Object parsed = RecursiveJsonParser.parse(definition);
            if (!(parsed instanceof Map)) {
                continue;
            }
            Map<?, ?> function = ((Map<?, ?>) parsed).get("function") instanceof Map ? (Map<?, ?>) ((Map<?, ?>) parsed).get("function") : null;
            if (function == null || !(function.get("name") instanceof String)) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("name", function.get("name"));
            if (function.get("description") instanceof String) {
                tool.put("description", function.get("description"));
            }
            if (function.get("parameters") != null) {
                tool.put("parameters", function.get("parameters"));
            }
            tools.add(tool);
        }
        return tools.isEmpty() ? null : RecursiveJsonParser.serialize(tools);
    }

    /**
     * 解析 OpenAI Responses 响应体：message item 的 output_text 拼接为正文、
     * function_call item 为工具调用（arguments 字符串二次解析）、usage 按总量口径
     * 提取、finish 由 status×output 形态经共享派生器。非合法 JSON 对象抛
     * {@link LlmApiException}；缺成员退化不中断。
     */
    @Override
    protected LlmResponse parseResponse(String body) throws LlmApiException {
        try {
            Object parsed = RecursiveJsonParser.parse(body);
            if (!(parsed instanceof Map)) {
                throw new LlmApiException("Failed to parse response: body is not a JSON object");
            }
            Map<?, ?> root = (Map<?, ?>) parsed;

            LlmResponse response = new LlmResponse();
            StringBuilder text = new StringBuilder();
            List<ToolCallResult> calls = new ArrayList<>();
            boolean hasFunctionCall = false;
            if (root.get("output") instanceof List) {
                for (Object item : (List<?>) root.get("output")) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> map = (Map<?, ?>) item;
                    String type = map.get("type") instanceof String ? (String) map.get("type") : null;
                    if ("message".equals(type) && map.get("content") instanceof List) {
                        for (Object part : (List<?>) map.get("content")) {
                            if (part instanceof Map && "output_text".equals(((Map<?, ?>) part).get("type")) && ((Map<?, ?>) part).get("text") instanceof String) {
                                text.append((String) ((Map<?, ?>) part).get("text"));
                            }
                        }
                    } else if ("function_call".equals(type)) {
                        hasFunctionCall = true;
                        ToolCallResult call = new ToolCallResult();
                        call.setToolCallId(asString(map.get("call_id")));
                        call.setToolName(asString(map.get("name")));
                        String arguments = asString(map.get("arguments"));
                        Object parsedArgs = RecursiveJsonParser.parse(arguments);
                        @SuppressWarnings("unchecked") Map<String, Object> args = parsedArgs instanceof Map ? (Map<String, Object>) parsedArgs : new LinkedHashMap<String, Object>();
                        call.setArguments(args);
                        calls.add(call);
                    }
                }
            }
            if (text.length() > 0) {
                response.setContent(text.toString());
            }
            response.setToolCalls(calls);

            Map<?, ?> usage = root.get("usage") instanceof Map ? (Map<?, ?>) root.get("usage") : null;
            if (usage != null) {
                response.setUsageRaw(RecursiveJsonParser.serialize(usage));
                response.setInputTokens(asIntOrZero(usage, "input_tokens"));
                response.setOutputTokens(asIntOrZero(usage, "output_tokens"));
                Map<?, ?> inputDetails = usage.get("input_tokens_details") instanceof Map ? (Map<?, ?>) usage.get("input_tokens_details") : null;
                if (inputDetails != null) {
                    Integer cached = asInteger(inputDetails, "cached_tokens");
                    if (cached != null) {
                        response.setCacheReadTokens(cached);
                    }
                }
                Map<?, ?> outputDetails = usage.get("output_tokens_details") instanceof Map ? (Map<?, ?>) usage.get("output_tokens_details") : null;
                if (outputDetails != null) {
                    Integer reasoning = asInteger(outputDetails, "reasoning_tokens");
                    if (reasoning != null) {
                        response.setReasoningTokens(reasoning);
                    }
                }
            }
            response.setServedModel(asString(root.get("model")));
            Map<?, ?> incomplete = root.get("incomplete_details") instanceof Map ? (Map<?, ?>) root.get("incomplete_details") : null;
            response.setFinishReason(OpenAiResponsesWireFormat.deriveFinishReason(asString(root.get("status")), incomplete != null ? asString(incomplete.get("reason")) : null, hasFunctionCall));
            response.setHasError(false);
            return response;
        } catch (LlmApiException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmApiException("Failed to parse response: " + e.getMessage(), e);
        }
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static Integer asInteger(Map<?, ?> obj, String key) {
        Object value = obj.get(key);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private static int asIntOrZero(Map<?, ?> obj, String key) {
        Integer value = asInteger(obj, key);
        return value != null ? value.intValue() : 0;
    }
}
