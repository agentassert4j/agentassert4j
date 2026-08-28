package io.github.agentassert4j.springai1;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.util.ArgTypeUtil;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.net.URI;
import java.net.URL;
import java.util.*;

/**
 * Spring AI 1.x 类型到交互记录的字段映射。
 *
 * <p>映射契约（与重放链路对齐）：
 * 系统消息 → templateHash（sha256），不进轮次，重放时由重放侧注入新系统提示；
 * 末位用户消息 → userInput + 多模态数组，其余消息 → previousTurns；
 * 工具定义按 OpenAI function 形状序列化，重放侧原样携带。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
final class SpringAiRecordMapper {

    static final String SDK_VERSION = "agentassert4j-sdk-spring-ai1";

    private SpringAiRecordMapper() {
    }

    /**
     * 组装一次调用的完整交互记录；response 为 null 时只落请求面字段。
     * context 由调用方在业务线程捕获传入——异步回调线程的 ThreadLocal 不可达。
     */
    static InteractionRecord toRecord(Prompt prompt, ChatResponse response, long latencyMs, Long ttftMs, RecordingContext context) {
        InteractionRecord record = new InteractionRecord();
        record.setTimestamp(System.currentTimeMillis());
        record.setRecorderVersion(SDK_VERSION);
        record.setLatencyMs(latencyMs);
        record.setTtftMs(ttftMs);

        if (context != null) {
            record.setSessionId(context.sessionId());
            record.setSkillId(context.skillId());
            record.setTemplateId(context.templateId());
            if (!context.metadata().isEmpty()) {
                record.setMetadata(RecursiveJsonParser.serialize(new TreeMap<String, Object>(context.metadata())));
            }
        }

        mapRequest(prompt, record);
        if (response != null) {
            mapResponse(response, record);
        }
        return record;
    }

    private static void mapRequest(Prompt prompt, InteractionRecord record) {
        List<Message> instructions = prompt.getInstructions() != null ? prompt.getInstructions() : new ArrayList<Message>();

        List<TurnContext> turns = new ArrayList<>();
        int userMessageCount = 0;
        for (int i = 0; i < instructions.size(); i++) {
            Message message = instructions.get(i);
            if (message instanceof SystemMessage) {
                // 系统提示即模板：哈希进 templateHash 作为模板锚点，
                // 依赖图的 prompt 变更检测以此为节点；
                // 原文随记录携带，存储侧归档进 prompt_texts 供 status 巡检展示
                record.setTemplateHash(HashUtil.sha256(message.getText()));
                record.setTemplateText(message.getText());
            }
            if (message.getMessageType() == MessageType.USER) {
                userMessageCount++;
            }
            boolean trailing = i == instructions.size() - 1;
            if (trailing && message instanceof UserMessage) {
                record.setTurnIndex(userMessageCount - 1);
                applyUserInput((UserMessage) message, record);
            } else {
                turns.addAll(toTurns(message));
            }
        }
        // 非用户消息收尾（典型：工具结果消息）时无本轮用户输入，
        // userInput 置 null——重放渲染层对 null 跳过末位 user 帧
        if (instructions.isEmpty() || !(instructions.get(instructions.size() - 1) instanceof UserMessage)) {
            record.setTurnIndex(userMessageCount);
        }
        if (!turns.isEmpty()) {
            record.setPreviousTurns(turns);
        }

        // TODO: [ChatModel 层无线上原文] 请求/响应 raw 列在 ChatModel 抽象层不可得
        //（原文在 provider HTTP 客户端内部）；需要逐字 raw 时由 provider 适配模块
        // 在 HTTP 层拦截回填，本层不伪造
        mapOptions(prompt.getOptions(), record);
    }

    private static void applyUserInput(UserMessage user, InteractionRecord record) {
        String text = user.getText();
        List<Media> media = user.getMedia();
        if (media == null || media.isEmpty()) {
            record.setUserInput(text);
            return;
        }
        // 多模态：userInput 直接存 OpenAI content 数组结构——重放客户端按
        // multimodalInput 标志把该数组原样注入请求体，multimodalContent 存同构副本
        List<Object> content = new ArrayList<>();
        if (text != null && !text.isEmpty()) {
            Map<String, Object> textPart = new LinkedHashMap<>();
            textPart.put("type", "text");
            textPart.put("text", text);
            content.add(textPart);
        }
        for (Media item : media) {
            content.add(mediaPart(item));
        }
        String json = RecursiveJsonParser.serialize(content);
        record.setMultimodalInput(true);
        record.setMultimodalContent(json);
        record.setUserInput(json);
    }

    /**
     * 媒体片段尽力映射为 OpenAI 多模态 content 形状；未知类型降级为
     * mimeType+name 的描述性片段，不中断录制。
     */
    private static Map<String, Object> mediaPart(Media media) {
        String mime = media.getMimeType() != null ? media.getMimeType().toString() : "application/octet-stream";
        Object data = media.getData();
        Map<String, Object> part = new LinkedHashMap<>();
        if (data != null && mime.startsWith("image/")) {
            part.put("type", "image_url");
            Map<String, Object> url = new LinkedHashMap<>();
            url.put("url", dataToUri(mime, data));
            part.put("image_url", url);
        } else if (data instanceof byte[] && mime.startsWith("audio/")) {
            // input_audio.data 契约是裸 base64——data URI 或 URL 形态会被服务端拒绝
            part.put("type", "input_audio");
            Map<String, Object> audio = new LinkedHashMap<>();
            audio.put("data", Base64.getEncoder().encodeToString((byte[]) data));
            audio.put("format", mime.substring(mime.indexOf('/') + 1));
            part.put("input_audio", audio);
        } else {
            part.put("type", "media");
            part.put("mimeType", mime);
            if (media.getName() != null) {
                part.put("name", media.getName());
            }
        }
        return part;
    }

    private static String dataToUri(String mime, Object data) {
        if (data instanceof URI || data instanceof URL) {
            return data.toString();
        }
        if (data instanceof byte[]) {
            return "data:" + mime + ";base64," + Base64.getEncoder().encodeToString((byte[]) data);
        }
        return String.valueOf(data);
    }

    private static List<TurnContext> toTurns(Message message) {
        List<TurnContext> turns = new ArrayList<>();
        if (message instanceof SystemMessage) {
            // 系统消息是模板材料（templateHash），不进入轮次
            return turns;
        }
        if (message instanceof ToolResponseMessage) {
            for (ToolResponseMessage.ToolResponse response : ((ToolResponseMessage) message).getResponses()) {
                TurnContext turn = new TurnContext("tool", response.responseData());
                turn.setToolCallId(response.id());
                turn.setToolName(response.name());
                turns.add(turn);
            }
            return turns;
        }
        String role = message.getMessageType() != null ? message.getMessageType().getValue() : "user";
        String text = message.getText();
        turns.add(new TurnContext(role, text != null ? text : ""));
        return turns;
    }

    private static void mapOptions(ChatOptions options, InteractionRecord record) {
        if (options == null) {
            return;
        }
        String model = options.getModel();
        record.setModel(model);
        record.setProvider(guessProvider(model));

        Map<String, Object> sampling = new LinkedHashMap<>();
        if (options.getTemperature() != null) {
            sampling.put("temperature", options.getTemperature());
        }
        if (options.getTopP() != null) {
            sampling.put("top_p", options.getTopP());
        }
        if (options.getTopK() != null) {
            sampling.put("top_k", options.getTopK());
        }
        if (options.getMaxTokens() != null) {
            sampling.put("max_tokens", options.getMaxTokens());
        }
        if (options.getFrequencyPenalty() != null) {
            sampling.put("frequency_penalty", options.getFrequencyPenalty());
        }
        if (options.getPresencePenalty() != null) {
            sampling.put("presence_penalty", options.getPresencePenalty());
        }
        if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            sampling.put("stop", options.getStopSequences());
        }
        if (!sampling.isEmpty()) {
            record.setSamplingParams(RecursiveJsonParser.serialize(sampling));
        }

        if (options instanceof ToolCallingChatOptions) {
            List<ToolCallback> callbacks = ((ToolCallingChatOptions) options).getToolCallbacks();
            if (callbacks == null || callbacks.isEmpty()) {
                return;
            }
            List<Object> tools = new ArrayList<>();
            for (ToolCallback callback : callbacks) {
                if (callback == null || callback.getToolDefinition() == null) {
                    continue;
                }
                ToolDefinition definition = callback.getToolDefinition();
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", definition.name());
                function.put("description", definition.description() != null ? definition.description() : "");
                function.put("parameters", parseOrEmpty(definition.inputSchema()));
                Map<String, Object> tool = new LinkedHashMap<>();
                tool.put("type", "function");
                tool.put("function", function);
                tools.add(tool);
            }
            if (!tools.isEmpty()) {
                record.setToolsDefinition(RecursiveJsonParser.serialize(tools));
            }
        }
    }

    private static Object parseOrEmpty(String json) {
        if (json == null || json.isEmpty()) {
            return new LinkedHashMap<String, Object>();
        }
        try {
            Object parsed = RecursiveJsonParser.parse(json);
            return parsed != null ? parsed : new LinkedHashMap<String, Object>();
        } catch (RuntimeException e) {
            // 损坏 schema 存原文：重放侧对损坏定义按既有策略跳过，不中断录制
            return json;
        }
    }

    private static void mapResponse(ChatResponse response, InteractionRecord record) {
        if (response.getMetadata() != null) {
            record.setServedModel(response.getMetadata().getModel());
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                record.setInputTokens(usage.getPromptTokens() != null ? usage.getPromptTokens() : 0);
                record.setOutputTokens(usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0);
                probeNativeUsage(usage, record);
            }
        }
        Generation result = response.getResult();
        if (result == null) {
            return;
        }
        String finishReason = result.getMetadata() != null ? result.getMetadata().getFinishReason() : null;
        record.setFinishReason(normalizeFinishReason(finishReason));
        AssistantMessage output = result.getOutput();
        if (output == null) {
            return;
        }
        record.setModelResponse(output.getText());

        List<ToolCall> calls = new ArrayList<>();
        if (output.getToolCalls() != null) {
            for (AssistantMessage.ToolCall toolCall : output.getToolCalls()) {
                ToolCall call = new ToolCall();
                call.setToolName(toolCall.name());
                call.setToolCallId(toolCall.id());
                Map<String, Object> arguments = parseArguments(toolCall.arguments());
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
     * 结束原因归一为框架枚举词表；provider 未报告（NULL）返回 null。
     */
    private static String normalizeFinishReason(String finishReason) {
        if (finishReason == null) {
            return null;
        }
        switch (finishReason) {
            case "STOP":
                return "stop";
            case "TOOL_EXECUTION":
                return "tool_calls";
            case "LENGTH":
                return "max_tokens";
            case "CONTENT_FILTER":
                return "content_filter";
            case "NULL":
                return null;
            default:
                return "other";
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

    /**
     * 缓存命中/思考 token 不在 Usage 接口上，属 provider 实现字段——反射尽力提取，
     * 取不到保持 null；SDK 不为读两个数引入具体 provider 依赖。
     */
    private static void probeNativeUsage(Usage usage, InteractionRecord record) {
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage == null) {
            return;
        }
        Integer cached = readLeaf(nativeUsage, "cachedTokens", "promptTokensDetails");
        if (cached != null) {
            record.setCacheReadTokens(cached);
        }
        Integer reasoning = readLeaf(nativeUsage, "reasoningTokens", "completionTokensDetails");
        if (reasoning != null) {
            record.setReasoningTokens(reasoning);
        }
    }

    private static Integer readLeaf(Object source, String leafMethod, String containerMethod) {
        Integer direct = invokeNumber(source, leafMethod);
        if (direct != null) {
            return direct;
        }
        Object container = invokeQuietly(source, containerMethod);
        return container != null ? invokeNumber(container, leafMethod) : null;
    }

    private static Integer invokeNumber(Object target, String methodName) {
        Object value = invokeQuietly(target, methodName);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    private static Object invokeQuietly(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (Exception e) {
            return null;
        }
    }

    private static String guessProvider(String model) {
        if (model == null) {
            return null;
        }
        String lower = model.toLowerCase(Locale.ROOT);
        if (lower.startsWith("deepseek")) {
            return "deepseek";
        }
        if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("o4")) {
            return "openai";
        }
        if (lower.startsWith("claude")) {
            return "anthropic";
        }
        if (lower.startsWith("qwen") || lower.startsWith("qwq")) {
            return "qwen";
        }
        if (lower.startsWith("gemini")) {
            return "gemini";
        }
        if (lower.startsWith("llama")) {
            return "ollama";
        }
        return "custom";
    }
}
