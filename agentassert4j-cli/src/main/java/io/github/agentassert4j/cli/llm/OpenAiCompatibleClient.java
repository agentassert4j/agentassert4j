package io.github.agentassert4j.cli.llm;

import io.github.agentassert4j.model.LlmFinishReason;
import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.ToolCallResult;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容 LLM 客户端（Chat Completions 方言）。
 *
 * <p>兼容 Azure OpenAI / 通义千问 / DeepSeek 等一切 OpenAI chat 格式端点；
 * HTTP 管道与超时/重试契约见共享基座。请求体手工拼装、响应体统一经 core 的
 * RecursiveJsonParser 解析——转义与解析语法不在此处另立第二真源。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public class OpenAiCompatibleClient extends AbstractHttpLlmClient {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiCompatibleClient.class);

    /**
     * 默认重试次数（传输层失败的最大重试），供组装根构造客户端时引用
     */
    public static final int DEFAULT_MAX_RETRIES = 2;
    /**
     * 内置方言裁剪规则（数据文件驱动）：命中模型省略「发送即报错」的标准参数
     */
    private static final ProviderDialects DIALECTS = ProviderDialects.load();
    /**
     * 方言裁剪告警只发一次——批量重放对同一模型逐请求告警会淹没输出
     */
    private boolean dialectWarned;

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
        super(endpoint, apiKey, defaultModel, maxRetries, extraBodyFields);
    }

    /**
     * finish_reason 归一为固定枚举：stop/tool_calls/max_tokens/content_filter/error/other。
     * OpenAI 方言值 tool_calls/function_call → tool_calls；未知值归 other（TEXT 枚举加值零迁移）。
     * wire 摄取（MCP record）与客户端重放共用本归一器——同一方言不得有两套词表。
     */
    public static String normalizeFinishReason(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        switch (raw) {
            case "stop":
                return LlmFinishReason.STOP.wireName();
            case "tool_calls":
            case "function_call":
                return LlmFinishReason.TOOL_CALLS.wireName();
            case "max_tokens":
            case "length":
                return LlmFinishReason.MAX_TOKENS.wireName();
            case "content_filter":
                return LlmFinishReason.CONTENT_FILTER.wireName();
            default:
                return LlmFinishReason.OTHER.wireName();
        }
    }

    @Override
    protected String requestPath() {
        return "/v1/chat/completions";
    }

    @Override
    protected void decorateConnection(HttpURLConnection conn) {
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
    }

    @Override
    public String name() {
        return defaultModel;
    }

    /**
     * 合成"assistant 发起工具调用"消息帧：arguments 优先取 TurnContext 携带的真值
     * （链式半重放的合成帧——「当时输入」重建要求内容无损）；历史录制轮没有该载体时
     * 以空对象占位（协议校验只看结构与 id/name 的对应关系）。
     */
    private static void appendAssistantToolCall(StringBuilder sb, TurnContext turn) {
        String callId = turn.getToolCallId() != null ? turn.getToolCallId() : "";
        String name = turn.getToolName() != null ? turn.getToolName() : "";
        String arguments = turn.getToolArguments() != null && !turn.getToolArguments().isEmpty() ? turn.getToolArguments() : "{}";
        sb.append("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"id\":\"").append(RecursiveJsonParser.escape(callId)).append("\",\"type\":\"function\",\"function\":{\"name\":\"").append(RecursiveJsonParser.escape(name)).append("\",\"arguments\":\"").append(RecursiveJsonParser.escape(arguments)).append("\"}}]}");
    }

    /**
     * 构建 OpenAI Chat Completion 请求体。
     *
     * <p>消息顺序与录制轮次序保真：</p>
     * <pre>
     * {"role":"system","content":"..."}     ← systemPrompt（新模板；历史 system 帧一律跳过防双 system）
     * {"role":"assistant"/"user"/"tool",...} ← previousTurns 按录制顺序原样展开
     * {"role":"user","content":"..."}       ← userInput（本轮输入固定在最后）
     * </pre>
     *
     * <p>工具定义（可选）：</p>
     * <pre>
     * "tools":[{"type":"function","function":{"name":"...","parameters":{...}}}]
     * </pre>
     */
    @Override
    protected String buildRequestBody(LlmRequest request, String model) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"model\":\"").append(RecursiveJsonParser.escape(model)).append("\"");

        // temperature——null 表示不携带该成员（推理模型方言：发送 0.0 会被 400 拒绝）；
        // 非 finite 值同样省略（JSON 无此字面量，发出即非法请求）；
        // 方言注册表命中的模型（o 系/gpt-5 系只接受默认温度）整条裁掉。
        // 显式配置被注册表覆盖必须告警——静默丢配置是排障黑洞，逃生舱（extraBody）要点名
        if (request.getTemperature() != null && Double.isFinite(request.getTemperature()) && !DIALECTS.droppedParamsFor(model).contains("temperature")) {
            sb.append(",\"temperature\":").append(request.getTemperature());
        } else if (request.getTemperature() != null && Double.isFinite(request.getTemperature()) && !dialectWarned && DIALECTS.droppedParamsFor(model).contains("temperature")) {
            dialectWarned = true;
            LOG.warn("Model {} belongs to a dialect family that rejects the temperature parameter; the explicit temperature was dropped from the request. To override, inject it via llm.extraBody.", model);
        }

        // messages
        StringBuilder messages = new StringBuilder();
        boolean wroteAny = false;

        // system message
        if (request.getSystemPrompt() != null && !request.getSystemPrompt().isEmpty()) {
            messages.append("{\"role\":\"system\",\"content\":\"").append(RecursiveJsonParser.escape(request.getSystemPrompt())).append("\"}");
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
                        System.err.println("Warning: history tool message has no toolCallId; the turn was skipped in the replay request (content length " + (turn.getContent() == null ? 0 : turn.getContent().length()) + ").");
                        continue;
                    }
                    // OpenAI 方言的硬约束：tool 消息必须紧跟在携带同 id tool_calls 的
                    // assistant 消息之后，否则服务端以 400 拒绝整个请求。
                    // 录制模型的历史轮没有"assistant 发起调用"的独立载体（无处存放
                    // arguments），渲染层按已知 id/toolName 合成最小合法请求帧补齐协议
                    if (!callId.equals(lastEmittedToolCallId)) {
                        if (wroteAny) messages.append(",");
                        appendAssistantToolCall(messages, turn);
                        wroteAny = true;
                        lastEmittedToolCallId = callId;
                    }
                } else {
                    lastEmittedToolCallId = null;
                }
                if (wroteAny) messages.append(",");
                // assistant 携带 toolCallId = 「模型发起工具调用」帧（链式半重放的合成帧），
                // 渲染为 assistant + tool_calls 结构；普通 assistant/user 帧走纯文本。
                // 登记已发射的 callId：紧随的 tool 帧据此跳过补帧，否则同一调用
                // 会出现第二个（空名）assistant 帧，服务端按非法 tool_calls 拒绝整个请求
                if ("assistant".equals(role) && turn.getToolCallId() != null && !turn.getToolCallId().trim().isEmpty()) {
                    appendAssistantToolCall(messages, turn);
                    wroteAny = true;
                    lastEmittedToolCallId = turn.getToolCallId();
                    continue;
                }
                messages.append("{\"role\":\"").append(RecursiveJsonParser.escape(role)).append("\"");
                // tool 角色消息必须携带 tool_call_id 才能关联到前序 assistant 的调用决策，
                // 缺失时服务端以 400 拒绝整个请求
                if ("tool".equals(role)) {
                    messages.append(",\"tool_call_id\":\"").append(RecursiveJsonParser.escape(turn.getToolCallId())).append("\"");
                }
                messages.append(",\"content\":\"").append(RecursiveJsonParser.escape(turn.getContent())).append("\"}");
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
                messages.append("{\"role\":\"user\",\"content\":\"").append(RecursiveJsonParser.escape(request.getUserInput())).append("\"}");
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
     * <p>提取 choices[0].message.content / tool_calls、usage 子树、顶层 model、
     * choices[0].finish_reason。usage 子树原文保留为 usage_raw（后续新增遥测列的
     * 回填来源），缓存读/思考 token 在此完成方言归一。响应体不是合法 JSON 对象时
     * 抛 {@link LlmApiException}；合法但缺成员时对应字段保持 null，退化不中断。</p>
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

            Map<?, ?> choice = firstMapElement(root.get("choices"));
            Map<?, ?> message = memberMap(choice, "message");
            response.setContent(memberString(message, "content"));
            response.setToolCalls(parseToolCalls(message));

            Map<?, ?> usage = memberMap(root, "usage");
            if (usage != null) {
                response.setUsageRaw(RecursiveJsonParser.serialize(usage));
                // usage 块残缺（缺 prompt_tokens/completion_tokens）按 0 兜底：
                // 字段是 primitive，拆箱 null 会把整个合法响应误判为解析失败
                response.setInputTokens(orZero(memberInt(usage, "prompt_tokens")));
                response.setOutputTokens(orZero(memberInt(usage, "completion_tokens")));
                // input_tokens 语义钉死为"总处理输入 token"：
                // OpenAI/DeepSeek 的 prompt_tokens 已是总量；Anthropic 的
                // cache_creation/cache_read 属于总量的一部分，其求和规则由该方言的
                // 摄取与客户端实现
                Map<?, ?> promptDetails = memberMap(usage, "prompt_tokens_details");
                if (promptDetails != null) {
                    response.setCacheReadTokens(memberInt(promptDetails, "cached_tokens"));
                }
                Map<?, ?> completionDetails = memberMap(usage, "completion_tokens_details");
                if (completionDetails != null) {
                    response.setReasoningTokens(memberInt(completionDetails, "reasoning_tokens"));
                }
            }

            // 响应报告的实际服务模型（顶层 "model" 字段）
            response.setServedModel(memberString(root, "model"));
            response.setFinishReason(normalizeFinishReason(memberString(choice, "finish_reason")));

            response.setHasError(false);
            return response;
        } catch (LlmApiException e) {
            throw e;
        } catch (Exception e) {
            throw new LlmApiException("Failed to parse response: " + e.getMessage(), e);
        }
    }

    /**
     * choices 数组的首元素；形态不符（缺列/空列/元素非对象）按无选择项处理。
     */
    private static Map<?, ?> firstMapElement(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) value).get(0);
        return first instanceof Map ? (Map<?, ?>) first : null;
    }

    private static Map<?, ?> memberMap(Map<?, ?> obj, String key) {
        if (obj == null) {
            return null;
        }
        Object value = obj.get(key);
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static String memberString(Map<?, ?> obj, String key) {
        if (obj == null) {
            return null;
        }
        Object value = obj.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static Integer memberInt(Map<?, ?> obj, String key) {
        if (obj == null) {
            return null;
        }
        Object value = obj.get(key);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private static int orZero(Integer value) {
        return value != null ? value : 0;
    }

    /**
     * 从 message 提取 tool_calls 数组；arguments 是内嵌 JSON 字符串，二次解析为 Map。
     * 缺 function 成员的结构残缺条目跳过——对重放而言无名的工具调用无意义。
     */
    private static List<ToolCallResult> parseToolCalls(Map<?, ?> message) {
        List<ToolCallResult> results = new ArrayList<>();
        if (message == null || !(message.get("tool_calls") instanceof List)) {
            return results;
        }
        for (Object item : (List<?>) message.get("tool_calls")) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> call = (Map<?, ?>) item;
            Map<?, ?> function = memberMap(call, "function");
            if (function == null) {
                continue;
            }
            ToolCallResult tc = new ToolCallResult();
            tc.setToolCallId(memberString(call, "id"));
            tc.setToolName(memberString(function, "name"));
            String arguments = memberString(function, "arguments");
            if (arguments != null) {
                Object parsedArgs = RecursiveJsonParser.parse(arguments);
                tc.setArguments(parsedArgs instanceof Map ? castStringKeyMap(parsedArgs) : new LinkedHashMap<String, Object>());
            }
            results.add(tc);
        }
        return results;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringKeyMap(Object parsed) {
        return (Map<String, Object>) parsed;
    }
}
