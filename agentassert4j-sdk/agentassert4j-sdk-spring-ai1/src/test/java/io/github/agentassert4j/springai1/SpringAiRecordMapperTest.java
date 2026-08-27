package io.github.agentassert4j.springai1;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.util.MimeType;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Spring AI 1.x 到交互记录的字段映射契约测试。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class SpringAiRecordMapperTest {

    private static UserMessage user(String text) {
        return new UserMessage(text);
    }

    private static AssistantMessage assistant(String text) {
        return new AssistantMessage(text);
    }

    private static ToolResponseMessage toolResponse(String id, String name, String data) {
        return new ToolResponseMessage(List.of(new ToolResponseMessage.ToolResponse(id, name, data)));
    }

    private static ChatResponse chatResponse(String content, String finishReason, Integer promptTokens, Integer completionTokens, String servedModel) {
        Generation generation = new Generation(new AssistantMessage(content), ChatGenerationMetadata.builder().finishReason(finishReason).build());
        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder().usage(new DefaultUsage(promptTokens, completionTokens));
        if (servedModel != null) {
            metadata.model(servedModel);
        }
        return new ChatResponse(List.of(generation), metadata.build());
    }

    @Nested
    class RequestMapping {

        @Test
        @DisplayName("系统消息映射为 templateHash，不进入轮次")
        void systemMessageBecomesTemplateHash() {
            Prompt prompt = new Prompt(List.of(new SystemMessage("你是售后客服助手"), user("订单 SO-1 在哪")));

            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, null, 10, null, null);

            assertEquals(HashUtil.sha256("你是售后客服助手"), record.getTemplateHash());
            assertTrue(record.getPreviousTurns() == null || record.getPreviousTurns().isEmpty(), "系统消息不得混入 previousTurns");
            assertEquals("订单 SO-1 在哪", record.getUserInput());
        }

        @Test
        @DisplayName("多轮对话：末位用户消息为 userInput，其余进 previousTurns")
        void multiTurnMapsUserInputAndTurns() {
            Prompt prompt = new Prompt(List.of(new SystemMessage("sys"), user("第一轮问题"), assistant("第一轮回答"), user("第二轮问题")));

            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, null, 10, null, null);

            assertEquals("第二轮问题", record.getUserInput());
            assertEquals(2, record.getPreviousTurns().size());
            assertEquals("user", record.getPreviousTurns().get(0).getRole());
            assertEquals("第一轮问题", record.getPreviousTurns().get(0).getContent());
            assertEquals("assistant", record.getPreviousTurns().get(1).getRole());
            assertEquals(1, record.getTurnIndex(), "末位是第 2 个用户消息（0 基）");
        }

        @Test
        @DisplayName("工具结果收尾的回合：userInput 为 null，tool 轮携带调用关联键")
        void toolTerminatedRoundMapsToolTurn() {
            Prompt prompt = new Prompt(List.of(new SystemMessage("sys"), user("查一下订单"), assistant(""), toolResponse("call-1", "query_order", "{\"status\":\"shipped\"}")));

            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, null, 10, null, null);

            assertNull(record.getUserInput(), "工具结果收尾时无本轮用户输入");
            List<TurnContext> turns = record.getPreviousTurns();
            assertEquals(3, turns.size());
            TurnContext toolTurn = turns.get(2);
            assertEquals("tool", toolTurn.getRole());
            assertEquals("call-1", toolTurn.getToolCallId());
            assertEquals("query_order", toolTurn.getToolName());
            assertEquals("{\"status\":\"shipped\"}", toolTurn.getContent());
            assertEquals(1, record.getTurnIndex());
        }

        @Test
        @DisplayName("采样参数只序列化非空项")
        void samplingParamsSerializeNonNullOnly() {
            DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
            options.setModel("deepseek-chat");
            options.setTemperature(0.0);
            options.setMaxTokens(512);
            Prompt prompt = new Prompt(List.of(user("hi")), options);

            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, null, 10, null, null);

            assertEquals("deepseek-chat", record.getModel());
            assertEquals("deepseek", record.getProvider());
            Map<String, Object> sampling = (Map<String, Object>) RecursiveJsonParser.parse(record.getSamplingParams());
            assertEquals(2, sampling.size(), "未设置的参数不得出现");
            assertEquals(0.0, ((Number) sampling.get("temperature")).doubleValue());
            assertEquals(512, ((Number) sampling.get("max_tokens")).intValue());
        }

        @Test
        @DisplayName("工具定义序列化为 function 形状数组，schema 解析为对象嵌入")
        void toolsDefinitionBuildsFunctionShape() {
            ToolCallback callback = new ToolCallback() {
                @Override
                public ToolDefinition getToolDefinition() {
                    return DefaultToolDefinition.builder().name("query_order").description("查询订单状态").inputSchema("{\"type\":\"object\",\"properties\":{\"order_id\":{\"type\":\"string\"}}}").build();
                }

                @Override
                public String call(String toolInput) {
                    return "{}";
                }
            };
            DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
            options.setToolCallbacks(List.of(callback));
            Prompt prompt = new Prompt(List.of(user("查订单")), options);

            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, null, 10, null, null);

            List<Object> tools = (List<Object>) RecursiveJsonParser.parse(record.getToolsDefinition());
            assertEquals(1, tools.size());
            Map<String, Object> tool = (Map<String, Object>) tools.get(0);
            assertEquals("function", tool.get("type"));
            Map<String, Object> function = (Map<String, Object>) tool.get("function");
            assertEquals("query_order", function.get("name"));
            assertEquals("查询订单状态", function.get("description"));
            Map<String, Object> schema = (Map<String, Object>) function.get("parameters");
            assertEquals("object", schema.get("type"), "schema 必须以对象嵌入而非字符串");
        }

        @Test
        @DisplayName("模型前缀推断供应商标识，未知前缀归 custom")
        void providerHeuristicByModelPrefix() {
            DefaultToolCallingChatOptions options = new DefaultToolCallingChatOptions();
            options.setModel("gpt-4o-mini");
            Prompt prompt = new Prompt(List.of(user("hi")), options);
            assertEquals("openai", SpringAiRecordMapper.toRecord(prompt, null, 1, null, null).getProvider());

            options.setModel("qwen3-32b");
            assertEquals("qwen", SpringAiRecordMapper.toRecord(prompt, null, 1, null, null).getProvider());

            options.setModel("my-private-model");
            assertEquals("custom", SpringAiRecordMapper.toRecord(prompt, null, 1, null, null).getProvider());
        }

        @Test
        @DisplayName("多模态用户消息捕获为 OpenAI content 数组形状")
        void multimodalUserMessageCapturesContentArray() {
            UserMessage multimodal = UserMessage.builder().text("这张图是什么").media(new Media(new MimeType("image", "png"), URI.create("https://cdn.example.com/a.png"))).build();
            Prompt prompt = new Prompt(List.of(new SystemMessage("sys"), multimodal));

            InteractionRecord record = SpringAiRecordMapper.toRecord(prompt, null, 10, null, null);

            assertTrue(record.isMultimodalInput());
            assertEquals(record.getMultimodalContent(), record.getUserInput(), "userInput 与 multimodalContent 存同构副本，重放按数组原样注入");
            List<Object> content = (List<Object>) RecursiveJsonParser.parse(record.getUserInput());
            assertEquals(2, content.size());
            Map<String, Object> textPart = (Map<String, Object>) content.get(0);
            assertEquals("text", textPart.get("type"));
            Map<String, Object> imagePart = (Map<String, Object>) content.get(1);
            assertEquals("image_url", imagePart.get("type"));
            Map<String, Object> url = (Map<String, Object>) imagePart.get("image_url");
            assertEquals("https://cdn.example.com/a.png", url.get("url"));
        }
    }

    @Nested
    class ResponseMapping {

        @Test
        @DisplayName("正文/用量/服务模型/结束原因逐字段对齐")
        void contentUsageModelFinishReasonMapped() {
            ChatResponse response = chatResponse("已发货", "STOP", 12, 7, "deepseek-v4-flash-vision-exp");

            InteractionRecord record = SpringAiRecordMapper.toRecord(new Prompt(List.of(user("hi"))), response, 33, null, null);

            assertEquals("已发货", record.getModelResponse());
            assertEquals(12, record.getInputTokens());
            assertEquals(7, record.getOutputTokens());
            assertEquals("deepseek-v4-flash-vision-exp", record.getServedModel());
            assertEquals("stop", record.getFinishReason());
            assertFalse(record.isHasToolCalls());
            assertEquals(33, record.getLatencyMs());
        }

        @Test
        @DisplayName("结束原因归一词表全覆盖")
        void finishReasonNormalization() {
            assertEquals("stop", finishReasonOf("STOP"));
            assertEquals("tool_calls", finishReasonOf("TOOL_EXECUTION"));
            assertEquals("max_tokens", finishReasonOf("LENGTH"));
            assertEquals("content_filter", finishReasonOf("CONTENT_FILTER"));
            assertNull(finishReasonOf("NULL"), "provider 未报告归 null");
            assertEquals("other", finishReasonOf("WEIRD_FUTURE_REASON"));
        }

        private String finishReasonOf(String springAiReason) {
            ChatResponse response = chatResponse("x", springAiReason, null, null, null);
            return SpringAiRecordMapper.toRecord(new Prompt(List.of(user("hi"))), response, 1, null, null).getFinishReason();
        }

        @Test
        @DisplayName("工具调用解析 arguments 并派生参数类型词表")
        void toolCallsParseArgumentsAndDeriveTypes() {
            AssistantMessage.ToolCall springCall = new AssistantMessage.ToolCall("call-9", "function", "query_order", "{\"order_id\":\"SO-9\",\"lines\":3}");
            AssistantMessage output = new AssistantMessage("", Map.of(), List.of(springCall));
            ChatResponse response = new ChatResponse(List.of(new Generation(output, ChatGenerationMetadata.builder().finishReason("TOOL_EXECUTION").build())));

            InteractionRecord record = SpringAiRecordMapper.toRecord(new Prompt(List.of(user("查订单"))), response, 5, null, null);

            assertTrue(record.isHasToolCalls());
            assertEquals(1, record.getToolCalls().size());
            ToolCall call = record.getToolCalls().get(0);
            assertEquals("query_order", call.getToolName());
            assertEquals("call-9", call.getToolCallId());
            assertEquals("SO-9", call.getArguments().get("order_id"));
            assertEquals("string", call.getArgTypes().get("order_id"));
            assertEquals("number", call.getArgTypes().get("lines"));
            assertEquals("tool_calls", record.getFinishReason());
        }

        @Test
        @DisplayName("usage 缺失时 token 归零，响应缺 result 不炸")
        void nullUsageDegradesToZero() {
            ChatResponse response = new ChatResponse(List.of(new Generation(new AssistantMessage("hi"), ChatGenerationMetadata.NULL)));

            InteractionRecord record = SpringAiRecordMapper.toRecord(new Prompt(List.of(user("hi"))), response, 1, null, null);

            assertEquals(0, record.getInputTokens());
            assertEquals(0, record.getOutputTokens());
            assertEquals("hi", record.getModelResponse());
        }
    }

    @Test
    @DisplayName("录音上下文声明的会话/技能/元数据落到记录")
    void recordingContextFieldsApplied() {
        try (RecordingContext ctx = RecordingContext.start("session-42").withSkillId("order-refund").withTemplateId("order-extract/v2").withMetadata("channel", "app")) {
            InteractionRecord record = SpringAiRecordMapper.toRecord(new Prompt(List.of(user("hi"))), null, 1, null, RecordingContext.currentOrNull());
            assertEquals("session-42", record.getSessionId());
            assertEquals("order-refund", record.getSkillId());
            assertEquals("order-extract/v2", record.getTemplateId());
            assertEquals("app", ((Map<String, Object>) RecursiveJsonParser.parse(record.getMetadata())).get("channel"));
        }
    }

    @Test
    @DisplayName("录制器版本标识随记录落库")
    void recorderVersionStamped() {
        InteractionRecord record = SpringAiRecordMapper.toRecord(new Prompt(List.of(user("hi"))), null, 1, null, null);
        assertEquals(SpringAiRecordMapper.SDK_VERSION, record.getRecorderVersion());
    }
}
