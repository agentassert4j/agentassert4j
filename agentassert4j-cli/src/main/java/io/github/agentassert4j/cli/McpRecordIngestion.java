package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.CostEstimator;
import io.github.agentassert4j.algorithm.InvocationResolver;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.ArgTypeUtil;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * record 摄取 — 非 Java 栈的入场券：把一次完成的 LLM 交互的原始 wire 报文
 * （OpenAI 兼容 chat completion 请求/响应 JSON）解析成落库交互记录。
 *
 * <p>字段映射与 SDK 捕获侧（SpringAiRecordMapper）同源：system 消息即模板、末位 user
 * 消息即本轮输入、其余消息进 previousTurns、响应侧提取正文/工具调用/用量。身份派生走
 * 与录制管道同一顺序——哈希投影先行、后键派生；wire 摄取无骨架，未声明标签时锚到
 * template/adhoc。幂等由存储层 INSERT OR IGNORE 承接，recordId 取值三层：
 * 调用方申报 &gt; 响应 id &gt; 内容哈希（重发同一报文自然去重）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class McpRecordIngestion {

    private static final String RECORDER_VERSION = "agentassert4j-mcp";

    private McpRecordIngestion() {
    }

    /**
     * 摄取一次交互：校验 → 解析 → 建记录 → 派生身份 → 幂等落库 → 报告。
     *
     * @param db   显式库路径（null 走配置 storage.url，与命令面一致）
     * @param args 工具 arguments
     * @return exit 0 报告行 agentassert4j.record/1（status=saved|duplicate）；
     * exit 2 时 stdout 携带 agentassert4j.error/1 包络行
     */
    static McpToolOutcome ingest(String db, Map<String, Object> args) {
        String sessionId = nonBlankString(args, "sessionId");
        String requestRaw = nonBlankString(args, "request");
        String responseRaw = nonBlankString(args, "response");
        if (sessionId == null || requestRaw == null || responseRaw == null) {
            return envelopeOutcome(CliErrorCode.E_USAGE, "record requires sessionId, request and response (request/response are the raw chat completion JSON strings).", "Send the raw OpenAI-compatible chat completion request and response JSON your stack produced, plus the session id.", "");
        }
        String metadataParam = nonBlankString(args, "metadata");
        Map<String, Object> metadata = null;
        if (metadataParam != null) {
            Object parsed = parseJsonOrNull(metadataParam);
            if (!(parsed instanceof Map)) {
                return envelopeOutcome(CliErrorCode.E_USAGE, "metadata must be a JSON object string.", "Pass metadata as a JSON object string, or drop it.", "");
            }
            metadata = castArgs(parsed);
        }

        Object requestParsed = parseJsonOrNull(requestRaw);
        if (!(requestParsed instanceof Map)) {
            return envelopeOutcome(CliErrorCode.E_USAGE, "request is not a valid JSON object.", "Send the raw request body your stack sent to the chat completions endpoint.", "");
        }
        Object responseParsed = parseJsonOrNull(responseRaw);
        if (!(responseParsed instanceof Map)) {
            return envelopeOutcome(CliErrorCode.E_USAGE, "response is not a valid JSON object.", "Send the raw response body the endpoint returned (a 200 body, not an error page).", "");
        }
        Map<String, Object> request = castArgs(requestParsed);
        Map<String, Object> response = castArgs(responseParsed);

        SqliteStorageRepository repository = null;
        try {
            InteractionRecord record = buildRecord(sessionId, requestRaw, request, responseRaw, response, args, metadata);
            repository = CliSupport.openRepository(db, CliSupport.discardStream());
            boolean saved = repository.saveInteractionIfAbsent(record);
            return McpToolOutcome.of(0, reportLine(record, saved) + "\n", "");
        } catch (RuntimeException e) {
            return envelopeOutcome(CliErrorCode.E_ENV, "record failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private static InteractionRecord buildRecord(String sessionId, String requestRaw, Map<String, Object> request, String responseRaw, Map<String, Object> response, Map<String, Object> args, Map<String, Object> metadata) {
        InteractionRecord record = new InteractionRecord();
        record.setSessionId(sessionId);
        Long timestamp = longArg(args, "timestamp");
        record.setTimestamp(timestamp != null ? timestamp : System.currentTimeMillis());
        record.setApiProtocol("openai-chat");
        record.setModel(memberString(request, "model"));
        record.setRecorderVersion(RECORDER_VERSION);
        Long latencyMs = longArg(args, "latencyMs");
        record.setLatencyMs(latencyMs != null ? latencyMs : 0L);
        record.setInvocationId(nonBlankString(args, "invocation"));

        mapMessages(request.get("messages"), record);
        if (request.get("tools") instanceof List && !((List<?>) request.get("tools")).isEmpty()) {
            record.setToolsDefinition(RecursiveJsonParser.serialize(request.get("tools")));
        }
        String sampling = samplingParamsJson(request);
        if (sampling != null) {
            record.setSamplingParams(sampling);
        }
        record.setModelRequestRaw(requestRaw);
        mapResponse(response, responseRaw, record);
        record.setMetadata(mergeTaskKey(metadata, nonBlankString(args, "taskKey")));
        record.setRecordId(resolveRecordId(args, response, record, requestRaw, responseRaw));

        // 身份派生与录制管道同序：哈希投影先行（键锚点消费 templateHash），后键派生
        if (record.getTemplateText() != null && !record.getTemplateText().isEmpty()) {
            record.setTemplateHash(HashUtil.sha256(record.getTemplateText()));
        }
        record.setInvocationKey(InvocationResolver.resolve(record).getInvocationKey());
        return record;
    }

    /**
     * 请求消息面映射（与 SDK 捕获侧同契约）：system/developer 即模板（多帧末者为准，
     * 不入轮次）；末位 user 消息即本轮输入（content 为数组按多模态落库）；其余进
     * previousTurns。
     */
    private static void mapMessages(Object messagesObj, InteractionRecord record) {
        List<TurnContext> turns = new ArrayList<>();
        if (messagesObj instanceof List) {
            List<?> messages = (List<?>) messagesObj;
            int userCount = 0;
            for (int i = 0; i < messages.size(); i++) {
                if (!(messages.get(i) instanceof Map)) {
                    continue;
                }
                Map<?, ?> message = (Map<?, ?>) messages.get(i);
                String role = memberString(message, "role");
                boolean trailing = i == messages.size() - 1;
                if ("system".equals(role) || "developer".equals(role)) {
                    // developer 帧按模板材料处置：system 的现代变体，同样锚模板不入轮次
                    String text = memberString(message, "content");
                    if (text != null && !text.isEmpty()) {
                        record.setTemplateText(text);
                    }
                    continue;
                }
                boolean isUser = "user".equals(role);
                if (isUser) {
                    userCount++;
                }
                if (trailing && isUser) {
                    record.setTurnIndex(userCount - 1);
                    Object content = message.get("content");
                    if (content instanceof List) {
                        String json = RecursiveJsonParser.serialize(content);
                        record.setMultimodalInput(true);
                        record.setMultimodalContent(json);
                        record.setUserInput(json);
                    } else {
                        record.setUserInput(content instanceof String ? (String) content : null);
                    }
                } else if ("tool".equals(role)) {
                    TurnContext turn = new TurnContext("tool", contentOrEmpty(message.get("content")));
                    String callId = memberString(message, "tool_call_id");
                    if (callId != null) {
                        turn.setToolCallId(callId);
                    }
                    turns.add(turn);
                } else {
                    turns.add(new TurnContext(role != null ? role : "user", contentOrEmpty(message.get("content"))));
                }
            }
            Object lastMessage = messages.get(messages.size() - 1);
            boolean lastIsUser = lastMessage instanceof Map && "user".equals(memberString((Map<?, ?>) lastMessage, "role"));
            if (!lastIsUser) {
                record.setTurnIndex(userCount);
            }
        }
        if (!turns.isEmpty()) {
            record.setPreviousTurns(turns);
        }
    }

    /**
     * 响应面映射：正文、工具调用（argTypes 同源派生）、用量四计数与原文、servedModel、
     * finish_reason 归一（与重放客户端同一归一器）、费用快照（查得到才算）。
     */
    private static void mapResponse(Map<String, Object> response, String responseRaw, InteractionRecord record) {
        Map<?, ?> choice = firstMapElement(response.get("choices"));
        Map<?, ?> message = memberMap(choice, "message");
        Object content = message != null ? message.get("content") : null;
        if (content instanceof String) {
            record.setModelResponse((String) content);
        } else if (content != null) {
            record.setModelResponse(RecursiveJsonParser.serialize(content));
        }

        List<ToolCall> calls = new ArrayList<>();
        if (message != null && message.get("tool_calls") instanceof List) {
            for (Object item : (List<?>) message.get("tool_calls")) {
                if (!(item instanceof Map)) {
                    continue;
                }
                Map<?, ?> call = (Map<?, ?>) item;
                Map<?, ?> function = memberMap(call, "function");
                if (function == null) {
                    continue;
                }
                ToolCall toolCall = new ToolCall();
                toolCall.setToolCallId(memberString(call, "id"));
                toolCall.setToolName(memberString(function, "name"));
                Map<String, Object> arguments = parseArguments(memberString(function, "arguments"));
                toolCall.setArguments(arguments);
                toolCall.setArgTypes(ArgTypeUtil.derive(arguments));
                toolCall.setSuccess(true);
                calls.add(toolCall);
            }
        }
        record.setToolCalls(calls);
        record.setHasToolCalls(!calls.isEmpty());

        Map<?, ?> usage = memberMap(response, "usage");
        if (usage != null) {
            record.setUsageRaw(RecursiveJsonParser.serialize(usage));
            record.setInputTokens(orZero(memberInt(usage, "prompt_tokens")));
            record.setOutputTokens(orZero(memberInt(usage, "completion_tokens")));
            Map<?, ?> promptDetails = memberMap(usage, "prompt_tokens_details");
            if (promptDetails != null) {
                record.setCacheReadTokens(memberInt(promptDetails, "cached_tokens"));
            }
            Map<?, ?> completionDetails = memberMap(usage, "completion_tokens_details");
            if (completionDetails != null) {
                record.setReasoningTokens(memberInt(completionDetails, "reasoning_tokens"));
            }
        }
        record.setServedModel(memberString(response, "model"));
        record.setFinishReason(OpenAiCompatibleClient.normalizeFinishReason(memberString(choice, "finish_reason")));
        record.setModelResponseRaw(responseRaw);
        String costModel = record.getServedModel() != null ? record.getServedModel() : record.getModel();
        Double costUsd = CostEstimator.estimateCallCostUsd(costModel, record.getInputTokens(), record.getOutputTokens());
        if (costUsd != null) {
            record.setCostUsd(costUsd);
        }
    }

    /**
     * 采样参数收集（存在才入）：与 SDK 捕获侧同一键集，另收 seed（wire 面常见）。
     */
    private static String samplingParamsJson(Map<String, Object> request) {
        Map<String, Object> sampling = new LinkedHashMap<>();
        String[] keys = {"temperature", "top_p", "top_k", "max_tokens", "frequency_penalty", "presence_penalty", "stop", "seed"};
        for (String key : keys) {
            Object value = request.get(key);
            if (value != null) {
                sampling.put(key, value);
            }
        }
        return sampling.isEmpty() ? null : RecursiveJsonParser.serialize(sampling);
    }

    /**
     * 任务键声明并入 metadata（TaskChainView 的声明字段，声明优先于派生）——
     * 与调用方自带 metadata 合并时声明字段保证在场。
     */
    private static String mergeTaskKey(Map<String, Object> metadata, String taskKey) {
        if (taskKey == null) {
            return metadata != null ? RecursiveJsonParser.serialize(metadata) : null;
        }
        Map<String, Object> merged = metadata != null ? new LinkedHashMap<>(metadata) : new LinkedHashMap<String, Object>();
        merged.put(TaskChainView.DECLARED_TASK_KEY, taskKey);
        return RecursiveJsonParser.serialize(merged);
    }

    /**
     * 幂等键三层：调用方申报 &gt; 响应 id（同一 completion 重发自然去重）&gt; 内容哈希
     * （同会话同位置同报文的重复上报收敛为一条）。
     */
    private static String resolveRecordId(Map<String, Object> args, Map<String, Object> response, InteractionRecord record, String requestRaw, String responseRaw) {
        String declared = nonBlankString(args, "recordId");
        if (declared != null) {
            return declared;
        }
        String responseId = memberString(response, "id");
        if (responseId != null && !responseId.isEmpty()) {
            return responseId;
        }
        String invocation = record.getInvocationId() != null ? record.getInvocationId() : "";
        return HashUtil.sha256(record.getSessionId() + "\n" + invocation + "\n" + record.getTurnIndex() + "\n" + HashUtil.sha256(requestRaw) + "\n" + HashUtil.sha256(responseRaw));
    }

    private static String reportLine(InteractionRecord record, boolean saved) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.record/1\",\"status\":\"").append(saved ? "saved" : "duplicate").append('"');
        sb.append(",\"recordId\":\"").append(RecursiveJsonParser.escape(record.getRecordId())).append('"');
        sb.append(",\"sessionId\":\"").append(RecursiveJsonParser.escape(record.getSessionId())).append('"');
        sb.append(",\"invocationKey\":\"").append(RecursiveJsonParser.escape(record.getInvocationKey())).append('"');
        if (record.getInvocationId() != null) {
            sb.append(",\"invocationId\":\"").append(RecursiveJsonParser.escape(record.getInvocationId())).append('"');
        }
        if (record.getModel() != null) {
            sb.append(",\"model\":\"").append(RecursiveJsonParser.escape(record.getModel())).append('"');
        }
        sb.append(",\"turnIndex\":").append(record.getTurnIndex());
        sb.append(",\"inputTokens\":").append(record.getInputTokens());
        sb.append(",\"outputTokens\":").append(record.getOutputTokens());
        sb.append(",\"hasToolCalls\":").append(record.isHasToolCalls());
        return sb.append('}').toString();
    }

    private static McpToolOutcome envelopeOutcome(CliErrorCode errorCode, String message, String hint, String nextAction) {
        return McpToolOutcome.of(2, CliSupport.errorEnvelope(errorCode, message, hint, nextAction) + "\n", "");
    }

    private static Object parseJsonOrNull(String json) {
        try {
            return RecursiveJsonParser.parse(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Object parsed = parseJsonOrNull(json);
        return parsed instanceof Map ? castArgs(parsed) : new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castArgs(Object parsed) {
        return (Map<String, Object>) parsed;
    }

    private static String nonBlankString(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (!(value instanceof String)) {
            return null;
        }
        String text = (String) value;
        return text.trim().isEmpty() ? null : text;
    }

    private static Long longArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
    }

    private static String memberString(Map<?, ?> obj, String key) {
        if (obj == null) {
            return null;
        }
        Object value = obj.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static Map<?, ?> memberMap(Map<?, ?> obj, String key) {
        if (obj == null) {
            return null;
        }
        Object value = obj.get(key);
        return value instanceof Map ? (Map<?, ?>) value : null;
    }

    private static Integer memberInt(Map<?, ?> obj, String key) {
        if (obj == null) {
            return null;
        }
        Object value = obj.get(key);
        return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
    }

    private static int orZero(Integer value) {
        return value != null ? value.intValue() : 0;
    }

    private static String contentOrEmpty(Object content) {
        return content instanceof String ? (String) content : "";
    }

    /**
     * choices 数组首元素（对象形）；形态不符按无选择项处理，退化不中断。
     */
    private static Map<?, ?> firstMapElement(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
            return null;
        }
        Object first = ((List<?>) value).get(0);
        return first instanceof Map ? (Map<?, ?>) first : null;
    }
}
