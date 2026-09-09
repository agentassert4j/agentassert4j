package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.CostEstimator;
import io.github.agentassert4j.algorithm.InvocationResolver;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.cli.llm.AnthropicMessagesWireFormat;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.cli.llm.OpenAiResponsesWireFormat;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.LlmWireProtocol;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.ArgTypeUtil;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;

import java.util.*;

/**
 * record 摄取 — 非 Java 栈的入场券：把一次完成的 LLM 交互的原始 wire 报文
 * （OpenAI chat / Anthropic Messages / OpenAI Responses 的请求与响应 JSON）
 * 解析成落库交互记录。
 *
 * <p>协议由显式 protocol 参数声明或按响应形态自动识别；落库记录是协议中立的
 * OpenAI chat 范式形——协议差异在本边界归一（工具定义转范式嵌套形、图像转
 * data-URI、finish/usage 按方言归一表折算），跨协议重放的比较在结构指纹层天然
 * 成立。字段映射与 SDK 捕获侧（SpringAiRecordMapper）同源：模板即 system/
 * instructions、末位 user 输入即本轮输入、其余消息进 previousTurns、响应侧提取
 * 正文/工具调用/用量。身份派生走与录制管道同一顺序——哈希投影先行、后键派生；
 * wire 摄取无骨架，未声明标签时锚到 template/adhoc。幂等由存储层 INSERT OR
 * IGNORE 承接，recordId 取值三层：调用方申报 &gt; 响应 id &gt; 内容哈希（重发同一
 * 报文自然去重）。不可转换的 part（历史轮图像等）宁缺勿非法——丢弃并经 stderr
 * 可见告警。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
final class McpRecordIngestion {

    private static final String RECORDER_VERSION = "agentassert4j-mcp";

    private static final String[] CHAT_SAMPLING_KEYS = {"temperature", "top_p", "top_k", "max_tokens", "frequency_penalty", "presence_penalty", "stop", "seed"};
    private static final String[] ANTHROPIC_SAMPLING_KEYS = {"temperature", "top_p", "top_k", "max_tokens", "stop_sequences"};
    private static final String[] RESPONSES_SAMPLING_KEYS = {"temperature", "top_p", "max_output_tokens"};

    private static final WireRecordMapper OPENAI_MAPPER = new OpenAiWireMapper();
    private static final WireRecordMapper ANTHROPIC_MAPPER = new AnthropicWireMapper();
    private static final WireRecordMapper RESPONSES_MAPPER = new ResponsesWireMapper();

    private McpRecordIngestion() {
    }

    /**
     * 摄取一次交互：校验 → 协议确定（显式声明优先，缺省按响应形态识别）→ 解析 →
     * 建记录 → 派生身份 → 幂等落库 → 报告。
     *
     * @param db   显式库路径（null 走配置 storage.url，与命令面一致）
     * @param args 工具 arguments
     * @return exit 0 报告行 agentassert4j.record/1（status=saved|duplicate，含
     * protocol 字段回显实际采用的方言——误判可当场发现，重发显式 protocol 即可）；
     * exit 2 时 stdout 携带 agentassert4j.error/1 包络行
     */
    static McpToolOutcome ingest(String db, Map<String, Object> args) {
        String sessionId = nonBlankString(args, "sessionId");
        String requestRaw = nonBlankString(args, "request");
        String responseRaw = nonBlankString(args, "response");
        if (sessionId == null || requestRaw == null || responseRaw == null) {
            return envelopeOutcome(CliErrorCode.E_USAGE, "record requires sessionId, request and response (request/response are the raw LLM wire JSON strings).", "Send the raw request and response JSON your stack produced, plus the session id.", "");
        }
        String protocolParam = nonBlankString(args, "protocol");
        if (protocolParam != null && LlmWireProtocol.fromWireName(protocolParam) == null) {
            return envelopeOutcome(CliErrorCode.E_USAGE, "protocol '" + protocolParam + "' is not a known wire protocol.", "Valid values: " + LlmWireProtocol.legalWireNames() + ".", "");
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
            return envelopeOutcome(CliErrorCode.E_USAGE, "request is not a valid JSON object.", "Send the raw request body your stack sent to the LLM endpoint.", "");
        }
        Object responseParsed = parseJsonOrNull(responseRaw);
        if (!(responseParsed instanceof Map)) {
            return envelopeOutcome(CliErrorCode.E_USAGE, "response is not a valid JSON object.", "Send the raw response body the endpoint returned (a 200 body, not an error page).", "");
        }
        Map<String, Object> request = castArgs(requestParsed);
        Map<String, Object> response = castArgs(responseParsed);

        LlmWireProtocol protocol = protocolParam != null ? LlmWireProtocol.fromWireName(protocolParam) : detectProtocol(response);
        if (protocol == null) {
            return envelopeOutcome(CliErrorCode.E_USAGE, "cannot detect the wire protocol from the response shape (no choices array, no Anthropic content blocks, no Responses output/status).", "Pass protocol explicitly (" + LlmWireProtocol.legalWireNames() + "), or send the raw 200 body the endpoint returned.", "");
        }

        List<String> warnings = new ArrayList<>();
        SqliteStorageRepository repository = null;
        try {
            InteractionRecord record = buildRecord(sessionId, protocol, requestRaw, request, responseRaw, response, args, metadata, warnings);
            repository = CliSupport.openRepository(db, CliSupport.discardStream());
            boolean saved = repository.saveInteractionIfAbsent(record);
            String stderr = warnings.isEmpty() ? "" : String.join("\n", warnings) + "\n";
            return McpToolOutcome.of(0, reportLine(record, saved) + "\n", stderr);
        } catch (RuntimeException e) {
            return envelopeOutcome(CliErrorCode.E_ENV, "record failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 响应形态自动识别（显式 protocol 缺省时采用；请求体按同协议假定）：
     * choices → openai-chat；stop_reason 或顶层 content 块数组 →
     * anthropic-messages；output 数组或 status → openai-responses。
     * 全部不中返回 null（调用方报用法错误列三候选）。
     */
    private static LlmWireProtocol detectProtocol(Map<String, Object> response) {
        if (response.containsKey("choices")) {
            return LlmWireProtocol.OPENAI_CHAT;
        }
        if (response.containsKey("stop_reason") || isTypedBlockArray(response.get("content"))) {
            return LlmWireProtocol.ANTHROPIC_MESSAGES;
        }
        if (response.get("output") instanceof List || response.containsKey("status")) {
            return LlmWireProtocol.OPENAI_RESPONSES;
        }
        return null;
    }

    /**
     * 顶层 content 是「带 type 的块数组」（Anthropic 响应形态签名）；空数组与
     * 非 type 块形态不算——识别失败走显式报错比静默错判诚实。
     */
    private static boolean isTypedBlockArray(Object value) {
        if (!(value instanceof List) || ((List<?>) value).isEmpty()) {
            return false;
        }
        Object first = ((List<?>) value).get(0);
        return first instanceof Map && ((Map<?, ?>) first).containsKey("type");
    }

    /**
     * wire 方言到范式记录的映射器 — 每协议一实现。请求面映射可产出丢弃告警
     * （不可转换的 part 宁缺勿非法，丢弃必须可见）；响应面形态不符静默退化
     * （对应字段保持 null，与既有 chat 摄取口径一致）。
     */
    private interface WireRecordMapper {

        void mapRequest(Map<String, Object> request, InteractionRecord record, List<String> warnings);

        void mapResponse(Map<String, Object> response, String responseRaw, InteractionRecord record);
    }

    private static WireRecordMapper mapperFor(LlmWireProtocol protocol) {
        switch (protocol) {
            case ANTHROPIC_MESSAGES:
                return ANTHROPIC_MAPPER;
            case OPENAI_RESPONSES:
                return RESPONSES_MAPPER;
            default:
                return OPENAI_MAPPER;
        }
    }

    private static InteractionRecord buildRecord(String sessionId, LlmWireProtocol protocol, String requestRaw, Map<String, Object> request, String responseRaw, Map<String, Object> response, Map<String, Object> args, Map<String, Object> metadata, List<String> warnings) {
        WireRecordMapper mapper = mapperFor(protocol);
        InteractionRecord record = new InteractionRecord();
        record.setSessionId(sessionId);
        Long timestamp = longArg(args, "timestamp");
        record.setTimestamp(timestamp != null ? timestamp : System.currentTimeMillis());
        // apiProtocol 标记「这条记录按哪个方言的 wire 报文摄取」；落库数据本身恒为
        // OpenAI chat 范式形（协议差异已在映射器边界归一）
        record.setApiProtocol(protocol.wireName());
        record.setModel(memberString(request, "model"));
        record.setRecorderVersion(RECORDER_VERSION);
        Long latencyMs = longArg(args, "latencyMs");
        record.setLatencyMs(latencyMs != null ? latencyMs : 0L);
        record.setInvocationId(nonBlankString(args, "invocation"));

        mapper.mapRequest(request, record, warnings);
        record.setModelRequestRaw(requestRaw);
        mapper.mapResponse(response, responseRaw, record);
        attachCostEstimate(record);
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
     * OpenAI chat 方言映射 — 行为契约与既有单协议实现逐字段一致（迁移而非重设计）。
     */
    private static final class OpenAiWireMapper implements WireRecordMapper {

        @Override
        public void mapRequest(Map<String, Object> request, InteractionRecord record, List<String> warnings) {
            mapMessages(request.get("messages"), record);
            if (request.get("tools") instanceof List && !((List<?>) request.get("tools")).isEmpty()) {
                record.setToolsDefinition(RecursiveJsonParser.serialize(request.get("tools")));
            }
            String sampling = samplingParamsJson(request, CHAT_SAMPLING_KEYS);
            if (sampling != null) {
                record.setSamplingParams(sampling);
            }
        }

        @Override
        public void mapResponse(Map<String, Object> response, String responseRaw, InteractionRecord record) {
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
                    calls.add(responseToolCall(memberString(call, "id"), memberString(function, "name"), memberString(function, "arguments")));
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
                    } else if ("assistant".equals(role) && message.get("tool_calls") instanceof List) {
                        // assistant 携带 tool_calls = 模型发起工具调用的历史帧（与 anthropic 的
                        // tool_use 块、responses 的 function_call item 同构）：content 文本轮
                        // （非空时）+ 逐调用发起帧（id/name/arguments 真值）——三协议的
                        // previousTurns 结构因此一致，重放的发起帧合成拿到真值而非空名占位
                        String content = contentOrEmpty(message.get("content"));
                        if (!content.isEmpty()) {
                            turns.add(new TurnContext("assistant", content));
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
                            TurnContext initFrame = new TurnContext("assistant", "");
                            String id = memberString(call, "id");
                            if (id != null) {
                                initFrame.setToolCallId(id);
                            }
                            initFrame.setToolName(memberString(function, "name"));
                            String arguments = memberString(function, "arguments");
                            if (arguments != null && !arguments.isEmpty()) {
                                initFrame.setToolArguments(arguments);
                            }
                            turns.add(initFrame);
                        }
                    } else {
                        turns.add(new TurnContext(role != null ? role : "user", contentOrEmpty(message.get("content"))));
                    }
                }
                if (!messages.isEmpty()) {
                    Object lastMessage = messages.get(messages.size() - 1);
                    boolean lastIsUser = lastMessage instanceof Map && "user".equals(memberString((Map<?, ?>) lastMessage, "role"));
                    if (!lastIsUser) {
                        record.setTurnIndex(userCount);
                    }
                }
            }
            if (!turns.isEmpty()) {
                record.setPreviousTurns(turns);
            }
        }
    }

    /**
     * Anthropic Messages 方言映射：顶层 system 即模板（字符串或 text 块拼接）；
     * assistant 轮的 tool_use 块与 user 轮的 tool_result 块按 id+name 保真为
     * 发起帧与结果帧（结果帧的名字由同请求内配对回填——该方言的结果块自身无名字）；
     * image 块在末位输入转范式 data-URI，历史轮无载体丢弃并告警。
     */
    private static final class AnthropicWireMapper implements WireRecordMapper {

        @Override
        public void mapRequest(Map<String, Object> request, InteractionRecord record, List<String> warnings) {
            String system = systemText(request.get("system"));
            if (system != null && !system.isEmpty()) {
                record.setTemplateText(system);
            }
            if (request.get("messages") instanceof List) {
                List<?> messages = (List<?>) request.get("messages");
                Map<String, String> toolNames = toolUseNames(messages);
                List<TurnContext> turns = new ArrayList<>();
                int userCount = 0;
                for (int i = 0; i < messages.size(); i++) {
                    if (!(messages.get(i) instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> message = (Map<?, ?>) messages.get(i);
                    String role = memberString(message, "role");
                    boolean isUser = "user".equals(role);
                    if (isUser) {
                        userCount++;
                    }
                    // 末位 user 且不含工具结果块 = 本轮新输入；含 tool_result 的末位 user
                    // 是链式中间态（结果回传后无新输入），按历史轮处理——与 chat 摄取对
                    // 末位 tool 消息的处置同构
                    boolean trailingInput = i == messages.size() - 1 && isUser && !hasBlockOfType(message.get("content"), "tool_result");
                    if (trailingInput) {
                        record.setTurnIndex(userCount - 1);
                        mapCurrentInput(message.get("content"), record, warnings);
                    } else {
                        appendHistoryMessage(role, message.get("content"), toolNames, turns, warnings);
                    }
                }
                if (!messages.isEmpty()) {
                    Object last = messages.get(messages.size() - 1);
                    boolean lastIsInput = last instanceof Map && "user".equals(memberString((Map<?, ?>) last, "role")) && !hasBlockOfType(((Map<?, ?>) last).get("content"), "tool_result");
                    if (!lastIsInput) {
                        record.setTurnIndex(userCount);
                    }
                }
                if (!turns.isEmpty()) {
                    record.setPreviousTurns(turns);
                }
            }
            String tools = toolsDefinition(request.get("tools"));
            if (tools != null) {
                record.setToolsDefinition(tools);
            }
            String sampling = samplingParamsJson(request, ANTHROPIC_SAMPLING_KEYS);
            if (sampling != null) {
                record.setSamplingParams(sampling);
            }
        }

        @Override
        public void mapResponse(Map<String, Object> response, String responseRaw, InteractionRecord record) {
            if (response.get("content") instanceof List) {
                StringBuilder text = new StringBuilder();
                List<ToolCall> calls = new ArrayList<>();
                for (Object block : (List<?>) response.get("content")) {
                    if (!(block instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> map = (Map<?, ?>) block;
                    String type = memberString(map, "type");
                    if ("text".equals(type)) {
                        String t = memberString(map, "text");
                        if (t != null) {
                            text.append(t);
                        }
                    } else if ("tool_use".equals(type)) {
                        // input 是对象形参数（该方言与 OpenAI 系的字符串形不同）
                        calls.add(responseToolCall(memberString(map, "id"), memberString(map, "name"), map.get("input")));
                    }
                }
                if (text.length() > 0) {
                    record.setModelResponse(text.toString());
                }
                record.setToolCalls(calls);
                record.setHasToolCalls(!calls.isEmpty());
            }
            Map<?, ?> usage = memberMap(response, "usage");
            if (usage != null) {
                record.setUsageRaw(RecursiveJsonParser.serialize(usage));
                // input_tokens 是非缓存口径：总量 = 三会计数求和（缺项按 0）
                record.setInputTokens(AnthropicMessagesWireFormat.totalInputTokens(usage));
                record.setOutputTokens(orZero(memberInt(usage, "output_tokens")));
                Integer cacheRead = memberInt(usage, "cache_read_input_tokens");
                if (cacheRead != null) {
                    record.setCacheReadTokens(cacheRead);
                }
                Integer cacheWrite = memberInt(usage, "cache_creation_input_tokens");
                if (cacheWrite != null) {
                    record.setCacheWriteTokens(cacheWrite);
                }
            }
            record.setServedModel(memberString(response, "model"));
            record.setFinishReason(AnthropicMessagesWireFormat.normalizeFinishReason(memberString(response, "stop_reason")));
            record.setModelResponseRaw(responseRaw);
        }

        /**
         * 末位输入映射：块数组含 image 块时转范式多模态数组（text 块转范式 text part、
         * image 块的 base64 source 转 data-URI）；纯 text 块数组拼接为文本——与
         * chat 摄取的纯文本形态可比。
         */
        private static void mapCurrentInput(Object content, InteractionRecord record, List<String> warnings) {
            if (content instanceof List && hasBlockOfType(content, "image")) {
                List<Object> paradigm = new ArrayList<>();
                for (Object block : (List<?>) content) {
                    if (!(block instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> map = (Map<?, ?>) block;
                    String type = memberString(map, "type");
                    if ("text".equals(type)) {
                        Map<String, Object> part = new LinkedHashMap<>();
                        part.put("type", "text");
                        part.put("text", memberString(map, "text"));
                        paradigm.add(part);
                    } else if ("image".equals(type)) {
                        String uri = AnthropicMessagesWireFormat.imageDataUri(map.get("source"));
                        if (uri == null) {
                            warnings.add("Warning: dropped a malformed image part in the final input (no base64 source).");
                            continue;
                        }
                        Map<String, Object> url = new LinkedHashMap<>();
                        url.put("url", uri);
                        Map<String, Object> part = new LinkedHashMap<>();
                        part.put("type", "image_url");
                        part.put("image_url", url);
                        paradigm.add(part);
                    }
                }
                String json = RecursiveJsonParser.serialize(paradigm);
                record.setMultimodalInput(true);
                record.setMultimodalContent(json);
                record.setUserInput(json);
            } else if (content instanceof List) {
                record.setUserInput(joinTextBlocks(content));
            } else {
                record.setUserInput(content instanceof String ? (String) content : null);
            }
        }

        /**
         * 历史消息映射（块序保真）：text 块聚合缓冲，遇 tool_use/tool_result 帧
         * 先落缓冲文本再出帧；image 块在历史轮无载体丢弃并告警。
         */
        private static void appendHistoryMessage(String role, Object content, Map<String, String> toolNames, List<TurnContext> turns, List<String> warnings) {
            String frameRole = "user".equals(role) || "assistant".equals(role) ? role : "user";
            if (!(content instanceof List)) {
                turns.add(new TurnContext(frameRole, contentOrEmpty(content)));
                return;
            }
            StringBuilder text = new StringBuilder();
            for (Object block : (List<?>) content) {
                if (!(block instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) block;
                String type = memberString(map, "type");
                if ("text".equals(type)) {
                    String t = memberString(map, "text");
                    if (t != null) {
                        text.append(t);
                    }
                } else if ("tool_use".equals(type)) {
                    flushText(text, frameRole, turns);
                    TurnContext turn = new TurnContext("assistant", "");
                    String id = memberString(map, "id");
                    if (id != null) {
                        turn.setToolCallId(id);
                    }
                    String name = memberString(map, "name");
                    if (name != null) {
                        turn.setToolName(name);
                    }
                    Object input = map.get("input");
                    if (input instanceof Map) {
                        turn.setToolArguments(RecursiveJsonParser.serialize(input));
                    }
                    turns.add(turn);
                } else if ("tool_result".equals(type)) {
                    flushText(text, "user", turns);
                    TurnContext turn = new TurnContext("tool", toolResultContent(map.get("content")));
                    String id = memberString(map, "tool_use_id");
                    if (id != null) {
                        turn.setToolCallId(id);
                        String name = toolNames.get(id);
                        if (name != null) {
                            turn.setToolName(name);
                        }
                    }
                    turns.add(turn);
                } else if ("image".equals(type)) {
                    warnings.add("Warning: dropped an image part in a history turn (history turns are text-only in the record model).");
                }
            }
            flushText(text, frameRole, turns);
        }

        private static void flushText(StringBuilder text, String role, List<TurnContext> turns) {
            if (text.length() > 0) {
                turns.add(new TurnContext(role, text.toString()));
                text.setLength(0);
            }
        }

        /**
         * tool_result 块的 content 归一为文本：字符串直取，text 块数组拼接。
         */
        private static String toolResultContent(Object content) {
            if (content instanceof String) {
                return (String) content;
            }
            return content instanceof List ? joinTextBlocks(content) : "";
        }

        /**
         * 顶层 system 归一：字符串直取，text 块数组拼接。
         */
        private static String systemText(Object system) {
            if (system instanceof String) {
                return (String) system;
            }
            return system instanceof List ? joinTextBlocks(system) : null;
        }

        /**
         * 预扫全部消息收集 tool_use 的 id→name 映射——tool_result 帧的工具名回填源
         * （结果块自身无名字，配对回填让重放的发起帧合成拿到真名）。
         */
        private static Map<String, String> toolUseNames(List<?> messages) {
            Map<String, String> names = new HashMap<>();
            for (Object message : messages) {
                if (!(message instanceof Map)) {
                    continue;
                }
                Object content = ((Map<?, ?>) message).get("content");
                if (!(content instanceof List)) {
                    continue;
                }
                for (Object block : (List<?>) content) {
                    if (block instanceof Map && "tool_use".equals(memberString((Map<?, ?>) block, "type"))) {
                        String id = memberString((Map<?, ?>) block, "id");
                        String name = memberString((Map<?, ?>) block, "name");
                        if (id != null && name != null) {
                            names.put(id, name);
                        }
                    }
                }
            }
            return names;
        }

        /**
         * 工具定义扁平形（{name,description,input_schema}）转范式嵌套形；缺 name 的
         * 残缺条目跳过——宁可不录也不产出无名范式。
         */
        private static String toolsDefinition(Object toolsObj) {
            if (!(toolsObj instanceof List) || ((List<?>) toolsObj).isEmpty()) {
                return null;
            }
            List<Object> paradigm = new ArrayList<>();
            for (Object tool : (List<?>) toolsObj) {
                if (!(tool instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) tool;
                String name = memberString(map, "name");
                if (name == null) {
                    continue;
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", name);
                String description = memberString(map, "description");
                if (description != null) {
                    function.put("description", description);
                }
                Object schema = map.get("input_schema");
                if (schema != null) {
                    function.put("parameters", schema);
                }
                Map<String, Object> def = new LinkedHashMap<>();
                def.put("type", "function");
                def.put("function", function);
                paradigm.add(def);
            }
            return paradigm.isEmpty() ? null : RecursiveJsonParser.serialize(paradigm);
        }
    }

    /**
     * OpenAI Responses 方言映射：instructions（或 items 内的 system/developer
     * message）即模板；input 字符串直为末位输入，items 按类型拆解——message→轮、
     * function_call→发起帧（arguments 真值保留）、function_call_output→结果帧
     * （名字由同请求配对回填）、reasoning 等其余 item 不是对话内容跳过。
     */
    private static final class ResponsesWireMapper implements WireRecordMapper {

        @Override
        public void mapRequest(Map<String, Object> request, InteractionRecord record, List<String> warnings) {
            String instructions = memberString(request, "instructions");
            if (instructions != null && !instructions.isEmpty()) {
                record.setTemplateText(instructions);
            }
            Object input = request.get("input");
            if (input instanceof String) {
                record.setUserInput((String) input);
                record.setTurnIndex(0);
            } else if (input instanceof List) {
                mapInputItems((List<?>) input, record, warnings);
            }
            String tools = toolsDefinition(request.get("tools"));
            if (tools != null) {
                record.setToolsDefinition(tools);
            }
            String sampling = samplingParamsJson(request, RESPONSES_SAMPLING_KEYS);
            if (sampling != null) {
                record.setSamplingParams(sampling);
            }
        }

        @Override
        public void mapResponse(Map<String, Object> response, String responseRaw, InteractionRecord record) {
            StringBuilder text = new StringBuilder();
            List<ToolCall> calls = new ArrayList<>();
            boolean hasFunctionCall = false;
            if (response.get("output") instanceof List) {
                for (Object item : (List<?>) response.get("output")) {
                    if (!(item instanceof Map)) {
                        continue;
                    }
                    Map<?, ?> map = (Map<?, ?>) item;
                    String type = memberString(map, "type");
                    if ("message".equals(type)) {
                        text.append(joinContentText(map.get("content"), null));
                    } else if ("function_call".equals(type)) {
                        hasFunctionCall = true;
                        calls.add(responseToolCall(memberString(map, "call_id"), memberString(map, "name"), memberString(map, "arguments")));
                    }
                }
            }
            if (text.length() > 0) {
                record.setModelResponse(text.toString());
            }
            record.setToolCalls(calls);
            record.setHasToolCalls(!calls.isEmpty());

            Map<?, ?> usage = memberMap(response, "usage");
            if (usage != null) {
                record.setUsageRaw(RecursiveJsonParser.serialize(usage));
                record.setInputTokens(orZero(memberInt(usage, "input_tokens")));
                record.setOutputTokens(orZero(memberInt(usage, "output_tokens")));
                Map<?, ?> inputDetails = memberMap(usage, "input_tokens_details");
                if (inputDetails != null) {
                    record.setCacheReadTokens(memberInt(inputDetails, "cached_tokens"));
                }
                Map<?, ?> outputDetails = memberMap(usage, "output_tokens_details");
                if (outputDetails != null) {
                    record.setReasoningTokens(memberInt(outputDetails, "reasoning_tokens"));
                }
            }
            record.setServedModel(memberString(response, "model"));
            Map<?, ?> incomplete = memberMap(response, "incomplete_details");
            record.setFinishReason(OpenAiResponsesWireFormat.deriveFinishReason(memberString(response, "status"), incomplete != null ? memberString(incomplete, "reason") : null, hasFunctionCall));
            record.setModelResponseRaw(responseRaw);
        }

        private static void mapInputItems(List<?> items, InteractionRecord record, List<String> warnings) {
            Map<String, String> callNames = functionCallNames(items);
            List<TurnContext> turns = new ArrayList<>();
            int userCount = 0;
            Object last = items.isEmpty() ? null : items.get(items.size() - 1);
            boolean lastIsInput = last instanceof Map && "message".equals(memberString((Map<?, ?>) last, "type")) && "user".equals(memberString((Map<?, ?>) last, "role"));
            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof Map)) {
                    continue;
                }
                Map<?, ?> item = (Map<?, ?>) items.get(i);
                String type = memberString(item, "type");
                if ("message".equals(type)) {
                    String role = memberString(item, "role");
                    if ("system".equals(role) || "developer".equals(role)) {
                        String text = joinContentText(item.get("content"), warnings);
                        if (!text.isEmpty()) {
                            record.setTemplateText(text);
                        }
                        continue;
                    }
                    boolean isUser = "user".equals(role);
                    if (isUser) {
                        userCount++;
                    }
                    if (i == items.size() - 1 && lastIsInput) {
                        record.setTurnIndex(userCount - 1);
                        mapCurrentInput(item.get("content"), record, warnings);
                    } else {
                        String frameRole = isUser || "assistant".equals(role) ? role : "user";
                        turns.add(new TurnContext(frameRole, joinContentText(item.get("content"), warnings)));
                    }
                } else if ("function_call".equals(type)) {
                    TurnContext turn = new TurnContext("assistant", "");
                    String callId = memberString(item, "call_id");
                    if (callId != null) {
                        turn.setToolCallId(callId);
                    }
                    String name = memberString(item, "name");
                    if (name != null) {
                        turn.setToolName(name);
                    }
                    String arguments = memberString(item, "arguments");
                    if (arguments != null && !arguments.isEmpty()) {
                        turn.setToolArguments(arguments);
                    }
                    turns.add(turn);
                } else if ("function_call_output".equals(type)) {
                    TurnContext turn = new TurnContext("tool", contentOrEmpty(item.get("output")));
                    String callId = memberString(item, "call_id");
                    if (callId != null) {
                        turn.setToolCallId(callId);
                        String name = callNames.get(callId);
                        if (name != null) {
                            turn.setToolName(name);
                        }
                    }
                    turns.add(turn);
                }
            }
            if (!lastIsInput) {
                record.setTurnIndex(userCount);
            }
            if (!turns.isEmpty()) {
                record.setPreviousTurns(turns);
            }
        }

        /**
         * 末位输入映射：content 为 part 数组且含图像 part 时转范式多模态数组
         * （input_image 的 image_url 直通——url 与 data-URI 形均合法）；纯文本
         * part 聚合为文本。
         */
        private static void mapCurrentInput(Object content, InteractionRecord record, List<String> warnings) {
            if (!(content instanceof List)) {
                record.setUserInput(content instanceof String ? (String) content : null);
                return;
            }
            List<?> parts = (List<?>) content;
            if (!hasBlockOfType(parts, "input_image")) {
                record.setUserInput(joinContentText(parts, null));
                return;
            }
            List<Object> paradigm = new ArrayList<>();
            for (Object part : parts) {
                if (!(part instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) part;
                if ("input_image".equals(memberString(map, "type"))) {
                    Object url = map.get("image_url");
                    if (!(url instanceof String)) {
                        warnings.add("Warning: dropped an image part without image_url in the final input.");
                        continue;
                    }
                    Map<String, Object> imageUrl = new LinkedHashMap<>();
                    imageUrl.put("url", url);
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("type", "image_url");
                    p.put("image_url", imageUrl);
                    paradigm.add(p);
                } else {
                    String text = memberString(map, "text");
                    if (text != null) {
                        Map<String, Object> p = new LinkedHashMap<>();
                        p.put("type", "text");
                        p.put("text", text);
                        paradigm.add(p);
                    }
                }
            }
            String json = RecursiveJsonParser.serialize(paradigm);
            record.setMultimodalInput(true);
            record.setMultimodalContent(json);
            record.setUserInput(json);
        }

        /**
         * message item 的 content 归一为文本：字符串直取；part 数组聚合文本 part
         * （input_text/output_text/text 同为文本载体）；图像 part 不在文本聚合内，
         * 历史轮出现时告警（warnings 为 null 表示纯提取场景不告警）。
         */
        private static String joinContentText(Object content, List<String> warnings) {
            if (content instanceof String) {
                return (String) content;
            }
            if (!(content instanceof List)) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            for (Object part : (List<?>) content) {
                if (!(part instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) part;
                String type = memberString(map, "type");
                if ("input_image".equals(type)) {
                    if (warnings != null) {
                        warnings.add("Warning: dropped an image part in a history turn (history turns are text-only in the record model).");
                    }
                    continue;
                }
                String text = memberString(map, "text");
                if (text != null) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }

        /**
         * 预扫 items 收集 function_call 的 call_id→name 映射——输出帧的工具名回填源。
         */
        private static Map<String, String> functionCallNames(List<?> items) {
            Map<String, String> names = new HashMap<>();
            for (Object item : items) {
                if (!(item instanceof Map) || !"function_call".equals(memberString((Map<?, ?>) item, "type"))) {
                    continue;
                }
                String callId = memberString((Map<?, ?>) item, "call_id");
                String name = memberString((Map<?, ?>) item, "name");
                if (callId != null && name != null) {
                    names.put(callId, name);
                }
            }
            return names;
        }

        /**
         * 工具定义扁平形（{type:"function",name,description,parameters}）转范式
         * 嵌套形；缺 name 的残缺条目跳过。
         */
        private static String toolsDefinition(Object toolsObj) {
            if (!(toolsObj instanceof List) || ((List<?>) toolsObj).isEmpty()) {
                return null;
            }
            List<Object> paradigm = new ArrayList<>();
            for (Object tool : (List<?>) toolsObj) {
                if (!(tool instanceof Map)) {
                    continue;
                }
                Map<?, ?> map = (Map<?, ?>) tool;
                String name = memberString(map, "name");
                if (name == null) {
                    continue;
                }
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", name);
                String description = memberString(map, "description");
                if (description != null) {
                    function.put("description", description);
                }
                Object parameters = map.get("parameters");
                if (parameters != null) {
                    function.put("parameters", parameters);
                }
                Map<String, Object> def = new LinkedHashMap<>();
                def.put("type", "function");
                def.put("function", function);
                paradigm.add(def);
            }
            return paradigm.isEmpty() ? null : RecursiveJsonParser.serialize(paradigm);
        }
    }

    /**
     * 响应侧工具调用条目：arguments 容忍对象形（Anthropic 的 input）与 JSON
     * 字符串形（OpenAI 系的 arguments），非对象/不可解析退化为空参数表；
     * argTypes 同源派生、success 恒真（响应帧表达「模型发起了调用」，成败属
     * 结果帧语义）。
     */
    private static ToolCall responseToolCall(String id, String name, Object arguments) {
        ToolCall toolCall = new ToolCall();
        toolCall.setToolCallId(id);
        toolCall.setToolName(name);
        Map<String, Object> args;
        if (arguments instanceof Map) {
            args = castArgs(arguments);
        } else if (arguments instanceof String) {
            args = parseArguments((String) arguments);
        } else {
            args = new LinkedHashMap<String, Object>();
        }
        toolCall.setArguments(args);
        toolCall.setArgTypes(ArgTypeUtil.derive(args));
        toolCall.setSuccess(true);
        return toolCall;
    }

    /**
     * 费用快照（查得到单价才算）——三协议响应映射共用；模型取响应报告值，
     * 缺失回退请求值。
     */
    private static void attachCostEstimate(InteractionRecord record) {
        String costModel = record.getServedModel() != null ? record.getServedModel() : record.getModel();
        Double costUsd = CostEstimator.estimateCallCostUsd(costModel, record.getInputTokens(), record.getOutputTokens());
        if (costUsd != null) {
            record.setCostUsd(costUsd);
        }
    }

    /**
     * text 块聚合（空串直拼——正文是顺序字符流，引入分隔符即引入凭空语义）。
     */
    private static String joinTextBlocks(Object blocksObj) {
        if (!(blocksObj instanceof List)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object block : (List<?>) blocksObj) {
            if (block instanceof Map && "text".equals(memberString((Map<?, ?>) block, "type"))) {
                String text = memberString((Map<?, ?>) block, "text");
                if (text != null) {
                    sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static boolean hasBlockOfType(Object content, String type) {
        if (!(content instanceof List)) {
            return false;
        }
        for (Object block : (List<?>) content) {
            if (block instanceof Map && type.equals(memberString((Map<?, ?>) block, "type"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 采样参数收集（存在才入）：每协议按自身 wire 键集收取，另收 seed（chat 面
     * 常见）——键集差异是方言事实，不强行归一。
     */
    private static String samplingParamsJson(Map<String, Object> request, String[] keys) {
        Map<String, Object> sampling = new LinkedHashMap<>();
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
        // 实际采用的方言（含自动识别结果）回显——误判当场可见，重发显式 protocol 即可
        sb.append(",\"protocol\":\"").append(RecursiveJsonParser.escape(record.getApiProtocol())).append('"');
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
