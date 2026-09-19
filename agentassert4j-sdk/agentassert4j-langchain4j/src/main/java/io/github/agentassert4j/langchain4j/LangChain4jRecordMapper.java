package io.github.agentassert4j.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.github.agentassert4j.algorithm.CostEstimator;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.util.*;
import io.github.agentassert4j.recorder.RecordingContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.*;

/**
 * LangChain4j 类型到交互记录的字段映射。
 *
 * <p>映射契约（与 Spring AI 适配及重放链路对齐）：系统消息 → templateHash（sha256），
 * 不进轮次，重放时由重放侧注入新系统提示；末位用户消息 → userInput + 多模态数组，
 * 其余消息 → previousTurns；工具定义按 OpenAI function 形状序列化，重放侧原样携带。
 * LangChain4j 的工具回路编排发生在 ChatModel 之外（AiServices 层），每个 LLM 轮次
 * 各自成记录：发起帧轮的响应携带 toolCalls，工具结果出现在下一轮请求历史的
 * tool 角色轮次中。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
final class LangChain4jRecordMapper {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jRecordMapper.class);

    static final String SDK_VERSION = "agentassert4j-langchain4j";

    /**
     * 工具参数 schema 的递归转换深度上限——防御性截断，超深嵌套退化为空对象，
     * 录制路径不把深层递归的失败外溢给业务调用。
     */
    private static final int MAX_SCHEMA_DEPTH = 8;

    private LangChain4jRecordMapper() {
    }

    /**
     * 组装一次调用的完整交互记录；response 为 null 时只落请求面字段。context 由
     * 调用方在业务线程捕获传入——异步完成线程的 ThreadLocal 不可达。
     */
    static InteractionRecord toRecord(ChatRequest request, ChatResponse response, long latencyMs, Long ttftMs, RecordingContext context) {
        InteractionRecord record = new InteractionRecord();
        record.setTimestamp(System.currentTimeMillis());
        record.setRecorderVersion(SDK_VERSION);
        record.setLatencyMs(latencyMs);
        record.setTtftMs(ttftMs);

        if (context != null) {
            record.setSessionId(context.sessionId());
            record.setInvocationId(context.invocationId());
            record.setTemplateId(context.templateId());
            record.setTemplateSkeleton(context.templateSkeleton());
            record.setEndpoint(context.endpoint());
            if (!context.metadata().isEmpty()) {
                record.setMetadata(RecursiveJsonParser.serialize(new TreeMap<String, Object>(context.metadata())));
            }
        }

        mapRequest(request, record);
        if (response != null) {
            mapResponse(response, record);
        }
        return record;
    }

    private static void mapRequest(ChatRequest request, InteractionRecord record) {
        // 记录本身即 OpenAI chat 形状（system/user/tool 帧与多模态 content 数组）：
        // apiProtocol 描述的是落库数据协议而非上游供应商
        record.setApiProtocol(LlmWireProtocol.OPENAI_CHAT.wireName());

        List<ChatMessage> messages = request.messages() != null ? request.messages() : new ArrayList<ChatMessage>();

        List<TurnContext> turns = new ArrayList<>();
        int userMessageCount = 0;
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (message instanceof SystemMessage) {
                // 系统提示即模板：哈希进 templateHash 作为模板锚点，
                // 依赖图的 prompt 变更检测以此为节点；原文随记录携带
                record.setTemplateHash(HashUtil.sha256(((SystemMessage) message).text()));
                record.setTemplateText(((SystemMessage) message).text());
            }
            if (message instanceof UserMessage) {
                userMessageCount++;
            }
            boolean trailing = i == messages.size() - 1;
            if (trailing && message instanceof UserMessage) {
                record.setTurnIndex(userMessageCount - 1);
                applyUserInput((UserMessage) message, record);
            } else {
                turns.addAll(toTurns(message));
            }
        }
        // 非用户消息收尾（典型：工具结果消息）时无本轮用户输入，
        // userInput 置 null——重放渲染层对 null 跳过末位 user 帧
        if (messages.isEmpty() || !(messages.get(messages.size() - 1) instanceof UserMessage)) {
            record.setTurnIndex(userMessageCount);
        }
        if (!turns.isEmpty()) {
            record.setPreviousTurns(turns);
        }

        // 契约边界（非待办，前提经两版本字节码审计证实永真）：请求/响应 raw 列在
        // ChatModel 抽象层不可得——原文在 provider HTTP 客户端内部，本层不伪造；
        // 需要逐字 raw 的消费面走 CLI 重驱记录或 MCP 摄取记录（wire 原文全量）
        mapParameters(request.parameters(), record);
    }

    private static void applyUserInput(UserMessage user, InteractionRecord record) {
        List<Content> contents = user.contents();
        if (contents == null || contents.isEmpty()) {
            return;
        }
        boolean hasNonText = false;
        for (Content content : contents) {
            if (!(content instanceof TextContent)) {
                hasNonText = true;
            }
        }
        if (!hasNonText) {
            StringBuilder text = new StringBuilder();
            for (int i = 0; i < contents.size(); i++) {
                if (i > 0) {
                    text.append('\n');
                }
                text.append(((TextContent) contents.get(i)).text());
            }
            record.setUserInput(text.toString());
            return;
        }
        // 多模态：userInput 直接存 OpenAI content 数组结构——重放客户端按
        // multimodalInput 标志把该数组原样注入请求体，multimodalContent 存同构副本
        List<Object> content = new ArrayList<>();
        for (Content item : contents) {
            content.add(contentPart(item));
        }
        String json = RecursiveJsonParser.serialize(content);
        record.setMultimodalInput(true);
        record.setMultimodalContent(json);
        record.setUserInput(json);
    }

    /**
     * 内容片段尽力映射为 OpenAI 多模态 content 形状；未知类型降级为
     * mimeType 的描述性片段，不中断录制。
     */
    private static Map<String, Object> contentPart(Content item) {
        if (item instanceof TextContent) {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", ((TextContent) item).text());
            return textPart;
        }
        if (item instanceof ImageContent) {
            Map<String, Object> imagePart = new LinkedHashMap<>();
            imagePart.put("type", "image_url");
            Map<String, Object> url = new LinkedHashMap<>();
            Image image = ((ImageContent) item).image();
            if (image != null && image.url() != null) {
                url.put("url", image.url().toString());
            } else if (image != null && image.base64Data() != null) {
                String mime = image.mimeType() != null ? image.mimeType() : "image/png";
                url.put("url", "data:" + mime + ";base64," + image.base64Data());
            }
            imagePart.put("image_url", url);
            return imagePart;
        }
        Map<String, Object> media = new LinkedHashMap<>();
        media.put("type", "media");
        media.put("contentType", String.valueOf(item.type()));
        return media;
    }

    private static List<TurnContext> toTurns(ChatMessage message) {
        List<TurnContext> turns = new ArrayList<>();
        if (message instanceof SystemMessage) {
            // 系统消息是模板材料（templateHash），不进入轮次
            return turns;
        }
        if (message instanceof ToolExecutionResultMessage) {
            ToolExecutionResultMessage tool = (ToolExecutionResultMessage) message;
            TurnContext turn = new TurnContext("tool", ToolResultNormalizer.normalize(toolResultText(tool)));
            turn.setToolCallId(tool.id());
            turn.setToolName(tool.toolName());
            turns.add(turn);
            return turns;
        }
        if (message instanceof AiMessage) {
            String text = ((AiMessage) message).text();
            turns.add(new TurnContext("assistant", text != null ? text : ""));
            return turns;
        }
        if (message instanceof UserMessage) {
            turns.add(new TurnContext("user", userPlainText((UserMessage) message)));
            return turns;
        }
        turns.add(new TurnContext("user", String.valueOf(message)));
        return turns;
    }

    private static String userPlainText(UserMessage user) {
        List<Content> contents = user.contents();
        if (contents == null || contents.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Content content : contents) {
            if (content instanceof TextContent) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(((TextContent) content).text());
            }
        }
        return text.toString();
    }

    /**
     * 工具结果文本提取。text() 在结果为多元素或非文本 contents 的新版本上会抛错
     * （结果载荷从单一字符串演进为内容列表，编译最低支持版本上又看不见列表访问器）——
     * 抛错时反射读 contents() 逐元素取文本，反射不可得再退到 toString，
     * 保证该路径永不中断录制。
     */
    private static String toolResultText(ToolExecutionResultMessage message) {
        try {
            return message.text();
        } catch (RuntimeException e) {
            return reflectiveContentsText(message);
        }
    }

    private static String reflectiveContentsText(ToolExecutionResultMessage message) {
        try {
            Method contents = message.getClass().getMethod("contents");
            Object value = contents.invoke(message);
            if (value instanceof List) {
                StringBuilder text = new StringBuilder();
                for (Object element : (List<?>) value) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(elementText(element));
                }
                return text.toString();
            }
        } catch (Exception ignored) {
            // 反射路径不可得（老版本形状）：落到 toString 兜底
        }
        return String.valueOf(message);
    }

    private static String elementText(Object element) {
        if (element instanceof TextContent) {
            return ((TextContent) element).text();
        }
        return String.valueOf(element);
    }

    private static void mapParameters(ChatRequestParameters parameters, InteractionRecord record) {
        if (parameters == null) {
            return;
        }
        String model = parameters.modelName();
        record.setModel(model);
        record.setProvider(LlmProviderUtil.inferFromModel(model));

        Map<String, Object> sampling = OpenAiWireUtil.sampling(parameters.temperature(), parameters.topP(), parameters.topK(), parameters.maxOutputTokens(), parameters.frequencyPenalty(), parameters.presencePenalty(), parameters.stopSequences());
        if (!sampling.isEmpty()) {
            record.setSamplingParams(RecursiveJsonParser.serialize(sampling));
        }

        List<ToolSpecification> specifications = parameters.toolSpecifications();
        if (specifications == null || specifications.isEmpty()) {
            return;
        }
        List<Object> tools = new ArrayList<>();
        for (ToolSpecification specification : specifications) {
            if (specification == null) {
                continue;
            }
            tools.add(OpenAiWireUtil.functionTool(specification.name(), specification.description(), schemaToMap(specification.parameters())));
        }
        if (!tools.isEmpty()) {
            record.setToolsDefinition(RecursiveJsonParser.serialize(tools));
        }
    }

    /**
     * 类型化工具参数 schema 转为 OpenAI function 形状的普通映射——wire 键词取
     * {@link OpenAiWireUtil} 唯一定义处，未知 schema 类型退化为空对象不中断录制。
     * 深度封顶防御：schema 嵌套超限时截断并记 WARN（就近可见），录制路径绝不
     * 把深层递归的失败外溢到业务调用。
     */
    private static Map<String, Object> schemaToMap(JsonSchemaElement element) {
        return schemaToMap(element, 0);
    }

    private static Map<String, Object> schemaToMap(JsonSchemaElement element, int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (element == null) {
            return map;
        }
        if (depth >= MAX_SCHEMA_DEPTH) {
            log.warn("Tool parameter schema nested deeper than {} levels; truncated (recording continues)", MAX_SCHEMA_DEPTH);
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_OBJECT);
            return map;
        }
        if (element instanceof JsonStringSchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_STRING);
        } else if (element instanceof JsonIntegerSchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_INTEGER);
        } else if (element instanceof JsonNumberSchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_NUMBER);
        } else if (element instanceof JsonBooleanSchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_BOOLEAN);
        } else if (element instanceof JsonNullSchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_NULL);
        } else if (element instanceof JsonEnumSchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_STRING);
            map.put(OpenAiWireUtil.SCHEMA_ENUM, ((JsonEnumSchema) element).enumValues());
        } else if (element instanceof JsonArraySchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_ARRAY);
            map.put(OpenAiWireUtil.SCHEMA_ITEMS, schemaToMap(((JsonArraySchema) element).items(), depth + 1));
        } else if (element instanceof JsonObjectSchema) {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_OBJECT);
            JsonObjectSchema object = (JsonObjectSchema) element;
            if (object.properties() != null) {
                Map<String, Object> properties = new LinkedHashMap<>();
                for (Map.Entry<String, JsonSchemaElement> entry : object.properties().entrySet()) {
                    properties.put(entry.getKey(), schemaToMap(entry.getValue(), depth + 1));
                }
                map.put(OpenAiWireUtil.SCHEMA_PROPERTIES, properties);
            }
            if (object.required() != null && !object.required().isEmpty()) {
                map.put(OpenAiWireUtil.SCHEMA_REQUIRED, object.required());
            }
            if (object.additionalProperties() != null) {
                map.put(OpenAiWireUtil.SCHEMA_ADDITIONAL_PROPERTIES, object.additionalProperties());
            }
            if (object.definitions() != null && !object.definitions().isEmpty()) {
                // 引用式 schema 的定义本体：不落则 $ref 全部悬空，重放时 provider 拒绝
                Map<String, Object> definitions = new LinkedHashMap<>();
                for (Map.Entry<String, JsonSchemaElement> entry : object.definitions().entrySet()) {
                    definitions.put(entry.getKey(), schemaToMap(entry.getValue(), depth + 1));
                }
                map.put(OpenAiWireUtil.SCHEMA_DEFS, definitions);
            }
        } else if (element instanceof JsonReferenceSchema) {
            map.put(OpenAiWireUtil.SCHEMA_REF, ((JsonReferenceSchema) element).reference());
        } else if (element instanceof JsonAnyOfSchema) {
            List<Object> alternatives = new ArrayList<>();
            for (JsonSchemaElement alternative : ((JsonAnyOfSchema) element).anyOf()) {
                alternatives.add(schemaToMap(alternative, depth + 1));
            }
            map.put(OpenAiWireUtil.SCHEMA_ANY_OF, alternatives);
        } else {
            map.put(OpenAiWireUtil.SCHEMA_TYPE, OpenAiWireUtil.SCHEMA_OBJECT);
        }
        if (element.description() != null) {
            map.put(OpenAiWireUtil.KEY_DESCRIPTION, element.description());
        }
        return map;
    }

    private static void mapResponse(ChatResponse response, InteractionRecord record) {
        // record_id 身份的唯一权威来源 = LLM 响应 id（与 MCP 摄取侧同源，跨入口去重依赖它）；
        // 缺失（无 id 的 provider/mock/流式聚合）时留空，录制管道回退 UUID
        String responseId = response.id();
        if (responseId != null && !responseId.trim().isEmpty()) {
            record.setRecordId(responseId);
        }
        record.setServedModel(response.modelName());
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            record.setInputTokens(usage.inputTokenCount() != null ? usage.inputTokenCount() : 0);
            record.setOutputTokens(usage.outputTokenCount() != null ? usage.outputTokenCount() : 0);
            // 调用时刻冻结的费用：价格快照查得到才算，查不到保持 null（不编造）
            Double costUsd = CostEstimator.estimateCallCostUsd(record.getServedModel() != null ? record.getServedModel() : record.getModel(), record.getInputTokens(), record.getOutputTokens());
            if (costUsd != null) {
                record.setCostUsd(costUsd);
            }
        }
        record.setFinishReason(normalizeFinishReason(response.finishReason()));
        AiMessage output = response.aiMessage();
        if (output == null) {
            return;
        }
        record.setModelResponse(output.text());

        List<ToolCall> calls = new ArrayList<>();
        if (output.toolExecutionRequests() != null) {
            for (ToolExecutionRequest request : output.toolExecutionRequests()) {
                ToolCall call = new ToolCall();
                call.setToolName(request.name());
                call.setToolCallId(request.id());
                Map<String, Object> arguments = parseArguments(request.arguments());
                call.setArguments(arguments);
                // 捕获与重放两侧共用同一词表派生，参数类型维指纹才可比
                call.setArgTypes(ArgTypeUtil.derive(arguments));
                call.setSuccess(true);
                calls.add(call);
            }
        }
        record.setToolCalls(calls);
        record.setHasToolCalls(!calls.isEmpty());
    }

    /**
     * 结束原因归一为规范词表（{@link LlmFinishReason}）；provider 未报告返回 null。
     */
    private static String normalizeFinishReason(FinishReason finishReason) {
        if (finishReason == null) {
            return null;
        }
        switch (finishReason) {
            case STOP:
                return LlmFinishReason.STOP.wireName();
            case TOOL_EXECUTION:
                return LlmFinishReason.TOOL_CALLS.wireName();
            case LENGTH:
                return LlmFinishReason.MAX_TOKENS.wireName();
            case CONTENT_FILTER:
                return LlmFinishReason.CONTENT_FILTER.wireName();
            default:
                return LlmFinishReason.OTHER.wireName();
        }
    }

    private static Map<String, Object> parseArguments(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            Object parsed = RecursiveJsonParser.parse(json);
            if (parsed instanceof Map) {
                Map<String, Object> arguments = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) parsed).entrySet()) {
                    arguments.put(String.valueOf(entry.getKey()), entry.getValue());
                }
                return arguments;
            }
            return new LinkedHashMap<>();
        } catch (RuntimeException e) {
            return new LinkedHashMap<>();
        }
    }
}
