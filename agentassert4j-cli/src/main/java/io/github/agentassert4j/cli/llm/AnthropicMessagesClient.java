package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.ToolCallResult;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.net.HttpURLConnection;
import java.util.*;

/**
 * Anthropic Messages 方言 LLM 客户端 — HTTP 管道与超时/重试契约见共享基座。
 *
 * <p>请求组装要点：system 恒走顶层 {@code system} 成员（历史 system 帧跳过防双 system）；
 * {@code max_tokens} 是该文法必填项，客户端兜定 {@link #DEFAULT_MAX_TOKENS} 常量（不开放
 * 配置——真实需求出现走 issue）；工具历史帧按逐对重建——assistant 携带 tool_use 块发起、
 * 紧随的 user 消息携带配对 tool_use_id 的 tool_result 块（该文法硬约束，违规 400），
 * 录制侧无发起帧载体时按已知 id/name 合成最小合法发起帧且同一配对只合成一次；范式
 * 多模态的 data-URI 图像拆解回 base64 source（http URL 形该文法不收，丢弃并告警——
 * 宁缺勿非法）。响应解析与 MCP 摄取共用 {@link AnthropicMessagesWireFormat} 归一器。</p>
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
public class AnthropicMessagesClient extends AbstractHttpLlmClient {

    /**
     * max_tokens 兜定值——该文法必填（无缺省语义），重放是保守预算场景
     */
    public static final int DEFAULT_MAX_TOKENS = 4096;
    /**
     * anthropic-version 头的版本值（官方 Messages API 稳定版本号，随端点演进走 issue）
     */
    static final String ANTHROPIC_VERSION = "2023-06-01";

    /**
     * 构造客户端，参数语义与 OpenAI 兼容客户端一致（endpoint/apiKey/defaultModel/
     * maxRetries/extraBodyFields 见共享基座）。
     */
    public AnthropicMessagesClient(String endpoint, String apiKey, String defaultModel, int maxRetries, String extraBodyFields) {
        super(endpoint, apiKey, defaultModel, maxRetries, extraBodyFields);
    }

    @Override
    protected String requestPath() {
        return "/v1/messages";
    }

    @Override
    protected void decorateConnection(HttpURLConnection conn) {
        conn.setRequestProperty("x-api-key", apiKey);
        conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
    }

    @Override
    public String name() {
        return defaultModel;
    }

    @Override
    protected String buildRequestBody(LlmRequest request, String model) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(RecursiveJsonParser.escape(model)).append("\"");
        // 该文法必填成员：缺省即 400，兜定保守常量
        sb.append(",\"max_tokens\":").append(DEFAULT_MAX_TOKENS);

        // system 恒走顶层成员；温度与 chat 同规则——null/非 finite 省略（发出即非法请求）
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            sb.append(",\"system\":\"").append(RecursiveJsonParser.escape(request.getSystemPrompt())).append("\"");
        }
        if (request.getTemperature() != null && Double.isFinite(request.getTemperature())) {
            sb.append(",\"temperature\":").append(request.getTemperature());
        }

        sb.append(",\"messages\":").append(buildMessages(request));

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
     * messages 组装：历史轮逐帧展开 + 末位输入。工具帧逐对重建（发起块与结果块相邻、
     * 配对键一致）；缺失配对键的结果帧跳过并告警——绝不构造会被 400 拒绝的请求。
     * 相邻同角色消息合并为单条消息的 content 块数组（该文法的规范交替形——官方服务端
     * 对连续同角色做静默合并，严格旧实现则直接拒绝；user 消息内 tool_result 块按文法
     * 要求前置于自由文本块）。
     */
    private static String buildMessages(LlmRequest request) {
        List<Map<String, Object>> frames = new ArrayList<>();
        String lastEmittedCallId = null;

        if (request.getPreviousTurns() != null) {
            for (TurnContext turn : request.getPreviousTurns()) {
                String role = turn.getRole();
                if ("system".equals(role)) {
                    // 系统提示属模板域，由顶层 system 承载——历史 system 帧混进消息序列
                    // 会被该文法拒绝（messages 只收 user/assistant）
                    continue;
                }
                if ("tool".equals(role)) {
                    String callId = turn.getToolCallId();
                    if (callId == null || callId.trim().isEmpty()) {
                        System.err.println("Warning: history tool message has no toolCallId; the turn was skipped in the replay request (content length " + (turn.getContent() == null ? 0 : turn.getContent().length()) + ").");
                        continue;
                    }
                    // 文法硬约束：tool_result 必须紧跟携带同 id tool_use 的 assistant 消息。
                    // 历史录制轮没有发起帧载体时按已知 id/name 合成最小合法发起帧；
                    // 同一配对只合成一次（链式半重放的合成帧自带真值时跳过补帧）
                    if (!callId.equals(lastEmittedCallId)) {
                        frames.add(frame("assistant", null, Collections.singletonList(toolUseBlock(callId, turn.getToolName(), turn.getToolArguments()))));
                        lastEmittedCallId = callId;
                    }
                    frames.add(frame("user", null, Collections.singletonList(toolResultBlock(callId, turn.getContent()))));
                    continue;
                }
                // assistant 携带 toolCallId = 「模型发起工具调用」帧（链式半重放的合成帧）：
                // 渲染为 assistant 的 tool_use 块（arguments 有真值则无损携带），
                // 紧随的 tool 帧据此跳过补帧
                if ("assistant".equals(role) && turn.getToolCallId() != null && !turn.getToolCallId().trim().isEmpty()) {
                    frames.add(frame("assistant", null, Collections.singletonList(toolUseBlock(turn.getToolCallId(), turn.getToolName(), turn.getToolArguments()))));
                    lastEmittedCallId = turn.getToolCallId();
                    continue;
                }
                frames.add(frame(role != null ? role : "user", turn.getContent() == null ? "" : turn.getContent(), null));
                lastEmittedCallId = null;
            }
        }

        // 末位本轮输入（固定在最后）
        if (request.getUserInput() != null) {
            if (request.isMultimodalInput()) {
                frames.add(frame("user", null, convertMultimodal(request.getUserInput())));
            } else {
                frames.add(frame("user", request.getUserInput(), null));
            }
        }
        return RecursiveJsonParser.serialize(mergeRuns(frames));
    }

    private static Map<String, Object> frame(String role, String text, List<Object> blocks) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("role", role);
        if (text != null) {
            frame.put("text", text);
        }
        if (blocks != null) {
            frame.put("blocks", blocks);
        }
        return frame;
    }

    /**
     * 相邻同角色帧合并：run 只有一帧且为纯文本时保持字符串 content（常态 golden 形态）；
     * 否则组装块数组——user 把 tool_result 等非文本块前置于文本块（文法要求），
     * assistant 文本块在前、tool_use 块在后（与响应原生形态同序）。
     */
    private static List<Object> mergeRuns(List<Map<String, Object>> frames) {
        List<Object> messages = new ArrayList<>();
        int index = 0;
        while (index < frames.size()) {
            String role = (String) frames.get(index).get("role");
            List<Map<String, Object>> run = new ArrayList<>();
            while (index < frames.size() && role.equals(frames.get(index).get("role"))) {
                run.add(frames.get(index));
                index++;
            }
            List<Object> textBlocks = new ArrayList<>();
            List<Object> toolBlocks = new ArrayList<>();
            for (Map<String, Object> frame : run) {
                Object text = frame.get("text");
                if (text instanceof String) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "text");
                    block.put("text", text);
                    textBlocks.add(block);
                }
                Object blocks = frame.get("blocks");
                if (blocks instanceof List) {
                    toolBlocks.addAll((List<?>) blocks);
                }
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("role", role);
            if (toolBlocks.isEmpty() && textBlocks.isEmpty()) {
                // run 内无任何可载内容（如末位多模态输入的全部 part 被丢弃）：
                // 空字符串 content 保持消息在场且合法，不产出空 content 数组
                message.put("content", "");
            } else if (toolBlocks.isEmpty() && textBlocks.size() == 1) {
                message.put("content", ((Map<?, ?>) textBlocks.get(0)).get("text"));
            } else {
                List<Object> content = new ArrayList<>();
                if ("user".equals(role)) {
                    content.addAll(toolBlocks);
                    content.addAll(textBlocks);
                } else {
                    content.addAll(textBlocks);
                    content.addAll(toolBlocks);
                }
                message.put("content", content);
            }
            messages.add(message);
        }
        return messages;
    }

    /**
     * assistant 发起块：arguments 优先取真值（「当时输入」重建要求内容无损），
     * 缺载体时以空对象占位（文法只看结构与配对键）。
     */
    private static Map<String, Object> toolUseBlock(String callId, String toolName, String arguments) {
        String input = arguments != null && !arguments.isEmpty() ? arguments : "{}";
        Object parsed = RecursiveJsonParser.parse(input);
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_use");
        block.put("id", callId);
        block.put("name", toolName != null ? toolName : "");
        block.put("input", parsed instanceof Map ? parsed : new LinkedHashMap<String, Object>());
        return block;
    }

    private static Map<String, Object> toolResultBlock(String callId, String content) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", "tool_result");
        block.put("tool_use_id", callId);
        block.put("content", content == null ? "" : content);
        return block;
    }

    /**
     * 范式多模态数组（OpenAI content 数组）转该文法块数组：text part 直转、
     * data-URI 图像拆解回 base64 source；http URL 形图像该文法不收——丢弃并告警
     * （宁缺勿非法）。不可转换的 part 全部丢弃后数组为空时退化为纯文本空输入，
     * 仍构造合法请求。
     */
    private static List<Object> convertMultimodal(String paradigmJson) {
        List<Object> blocks = new ArrayList<>();
        Object parsed = RecursiveJsonParser.parse(paradigmJson);
        if (parsed instanceof List) {
            for (Object part : (List<?>) parsed) {
                if (!(part instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) part;
                String type = map.get("type") instanceof String ? (String) map.get("type") : null;
                if ("text".equals(type)) {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("type", "text");
                    block.put("text", map.get("text"));
                    blocks.add(block);
                } else if ("image_url".equals(type) && map.get("image_url") instanceof Map) {
                    Object url = ((Map<?, ?>) map.get("image_url")).get("url");
                    if (url instanceof String && ((String) url).startsWith("data:")) {
                        Map<String, Object> block = new LinkedHashMap<>();
                        block.put("type", "image");
                        block.put("source", imageDataUriSplit((String) url));
                        blocks.add(block);
                    } else {
                        System.err.println("Warning: dropped a non-data-URI image part in the replay request (this dialect only accepts base64 images).");
                    }
                }
            }
        }
        return blocks;
    }

    /**
     * data-URI（data:{media_type};base64,{data}）拆解回该文法的 base64 source；
     * 形态不符返回 null（调用方丢弃并告警）。
     */
    private static Map<String, Object> imageDataUriSplit(String dataUri) {
        int semicolon = dataUri.indexOf(';');
        int comma = dataUri.indexOf(',');
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "base64");
        if (semicolon > 5 && comma > semicolon) {
            source.put("media_type", dataUri.substring(5, semicolon));
            source.put("data", dataUri.substring(comma + 1));
        }
        return source;
    }

    /**
     * 范式工具定义（OpenAI tools 嵌套形）转该文法扁平形，并做方言必填字段矫正：
     * input_schema 是该文法的必填成员——无参数工具补空对象 schema、缺 type 的
     * schema 补 "type":"object"（只补缺不覆盖显式值）；参数损坏（非对象）的定义
     * 跳过——宁可不带也不构造非法请求（与 chat 侧「损坏定义不带」同策略）。
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
            Map<String, Object> inputSchema = inputSchemaForEmission(function.get("parameters"));
            if (inputSchema == null) {
                continue;
            }
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("name", function.get("name"));
            if (function.get("description") instanceof String) {
                tool.put("description", function.get("description"));
            }
            tool.put("input_schema", inputSchema);
            tools.add(tool);
        }
        return tools.isEmpty() ? null : RecursiveJsonParser.serialize(tools);
    }

    /**
     * input_schema 装配：缺省补空对象 schema（无参数工具该文法也要求 schema 在场）；
     * 对象形缺 type 补 "type":"object"（schema 草案不强制 type，该文法强制）；
     * 非对象形（损坏定义）返回 null 由调用方跳过。
     */
    private static Map<String, Object> inputSchemaForEmission(Object parameters) {
        if (parameters == null) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("type", "object");
            empty.put("properties", new LinkedHashMap<String, Object>());
            return empty;
        }
        if (!(parameters instanceof Map)) {
            return null;
        }
        Map<?, ?> schema = (Map<?, ?>) parameters;
        if (schema.containsKey("type")) {
            @SuppressWarnings("unchecked") Map<String, Object> verbatim = (Map<String, Object>) schema;
            return verbatim;
        }
        Map<String, Object> rectified = new LinkedHashMap<>();
        rectified.put("type", "object");
        for (Map.Entry<?, ?> entry : schema.entrySet()) {
            rectified.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return rectified;
    }

    /**
     * 解析 Anthropic Messages 响应体：text 块空串直拼为正文、tool_use 块为工具调用
     * （input 是对象形参数）、usage 三会计数求和为总量（缺项按 0）、stop_reason 经
     * 共享归一器。非合法 JSON 对象抛 {@link LlmApiException}；缺成员退化不中断。
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
            if (root.get("content") instanceof List) {
                for (Object block : (List<?>) root.get("content")) {
                    if (!(block instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> map = (Map<?, ?>) block;
                    String type = map.get("type") instanceof String ? (String) map.get("type") : null;
                    if ("text".equals(type)) {
                        if (map.get("text") instanceof String) {
                            text.append((String) map.get("text"));
                        }
                    } else if ("tool_use".equals(type)) {
                        ToolCallResult call = new ToolCallResult();
                        call.setToolCallId(asString(map.get("id")));
                        call.setToolName(asString(map.get("name")));
                        Object input = map.get("input");
                        if (input instanceof Map) {
                            @SuppressWarnings("unchecked") Map<String, Object> arguments = (Map<String, Object>) input;
                            call.setArguments(arguments);
                        }
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
                response.setInputTokens(AnthropicMessagesWireFormat.totalInputTokens(usage));
                response.setOutputTokens(asIntOrZero(usage, "output_tokens"));
                Integer cacheRead = asInteger(usage, "cache_read_input_tokens");
                if (cacheRead != null) {
                    response.setCacheReadTokens(cacheRead);
                }
                Integer cacheWrite = asInteger(usage, "cache_creation_input_tokens");
                if (cacheWrite != null) {
                    response.setCacheWriteTokens(cacheWrite);
                }
            }
            response.setServedModel(asString(root.get("model")));
            response.setFinishReason(AnthropicMessagesWireFormat.normalizeFinishReason(asString(root.get("stop_reason"))));
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
