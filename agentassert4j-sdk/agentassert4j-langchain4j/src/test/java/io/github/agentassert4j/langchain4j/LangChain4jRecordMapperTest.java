package io.github.agentassert4j.langchain4j;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonReferenceSchema;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.ToolResultNormalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 映射器契约钉：LangChain4j 请求/响应/历史到交互记录的逐字段对齐。
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
class LangChain4jRecordMapperTest {

    @Nested
    @DisplayName("请求面映射")
    class RequestMapping {

        @Test
        @DisplayName("系统消息成模板锚点，末位用户消息成 userInput")
        void systemMessageBecomesTemplateAnchor() {
            ChatRequest request = ChatRequest.builder().messages(SystemMessage.from("你是售后助手"), UserMessage.from("查订单 8841")).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 5L, null, null);

            assertEquals(HashUtil.sha256("你是售后助手"), record.getTemplateHash());
            assertEquals("你是售后助手", record.getTemplateText());
            assertEquals("查订单 8841", record.getUserInput());
            assertEquals(0, record.getTurnIndex());
            assertNull(record.getPreviousTurns());
        }

        @Test
        @DisplayName("工具轮回灌请求：userInput 置 null，历史进 previousTurns 含 tool 结果轮")
        void toolLoopHistoryMapsPreviousTurns() {
            ToolExecutionRequest frame = ToolExecutionRequest.builder().id("call-1").name("getOrder").arguments("{\"orderId\":\"8841\"}").build();
            List<ChatMessage> messages = List.of(SystemMessage.from("sys"), UserMessage.from("查订单 8841 的退款进度"), new AiMessage(null, List.of(frame)), ToolExecutionResultMessage.from(frame, "\"REFUND-8841\""));
            ChatRequest request = ChatRequest.builder().messages(messages).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 5L, null, null);

            assertNull(record.getUserInput());
            assertEquals(1, record.getTurnIndex());
            assertNotNull(record.getPreviousTurns());
            assertEquals(3, record.getPreviousTurns().size());
            TurnContext user = record.getPreviousTurns().get(0);
            TurnContext assistant = record.getPreviousTurns().get(1);
            TurnContext tool = record.getPreviousTurns().get(2);
            assertEquals("user", user.getRole());
            assertEquals("查订单 8841 的退款进度", user.getContent());
            assertEquals("assistant", assistant.getRole());
            assertEquals("tool", tool.getRole());
            assertEquals("call-1", tool.getToolCallId());
            assertEquals("getOrder", tool.getToolName());
            // 工具结果是方言编码的字符串字面量，映射时解一层还原语义原文
            assertEquals("REFUND-8841", tool.getContent());
        }

        @Test
        @DisplayName("参数面：模型/采样/工具定义按 OpenAI 形状落列")
        void samplingModelAndToolsFromParameters() {
            ToolSpecification specification = ToolSpecification.builder().name("getOrder").description("查询订单状态").parameters(JsonObjectSchema.builder().addStringProperty("orderId").addIntegerProperty("limit").required("orderId").build()).build();
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("查订单")).modelName("deepseek-v4-flash").temperature(0.3).topP(0.9).maxOutputTokens(1024).stopSequences(List.of("END")).toolSpecifications(specification).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 5L, null, null);

            assertEquals("deepseek-v4-flash", record.getModel());
            assertNotNull(record.getProvider());
            assertNotNull(record.getSamplingParams());
            assertTrue(record.getSamplingParams().contains("\"temperature\":0.3"));
            assertTrue(record.getSamplingParams().contains("\"max_tokens\":1024"));
            assertTrue(record.getSamplingParams().contains("\"stop\""));
            String tools = record.getToolsDefinition();
            assertNotNull(tools);
            assertTrue(tools.contains("\"type\":\"function\""));
            assertTrue(tools.contains("\"name\":\"getOrder\""));
            assertTrue(tools.contains("\"orderId\""));
            assertTrue(tools.contains("\"required\":[\"orderId\"]"));
        }

        @Test
        @DisplayName("schema 类型面：字符串/整数/枚举/数组/引用各归其 OpenAI 形状")
        void schemaTypesMapToOpenAiShapes() {
            ToolSpecification specification = ToolSpecification.builder().name("mixed").parameters(JsonObjectSchema.builder().addProperty("s", JsonStringSchema.builder().build()).addProperty("i", JsonIntegerSchema.builder().build()).build()).build();
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("x")).toolSpecifications(specification).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 1L, null, null);

            assertTrue(record.getToolsDefinition().contains("\"s\":{\"type\":\"string\"}"));
            assertTrue(record.getToolsDefinition().contains("\"i\":{\"type\":\"integer\"}"));
        }

        @Test
        @DisplayName("深度封顶：超深嵌套 schema 截断为空对象不抛错")
        void deepNestedSchemaTruncatesWithoutThrowing() {
            JsonObjectSchema schema = JsonObjectSchema.builder().addStringProperty("leaf").build();
            for (int i = 0; i < 12; i++) {
                schema = JsonObjectSchema.builder().addProperty("nested", schema).build();
            }
            ToolSpecification specification = ToolSpecification.builder().name("deep").parameters(schema).build();
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("x")).toolSpecifications(specification).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 1L, null, null);

            assertNotNull(record.getToolsDefinition());
            assertTrue(record.getToolsDefinition().contains("\"name\":\"deep\""));
        }

        @Test
        @DisplayName("引用式 schema：定义本体落 $defs，$ref 不悬空")
        void referenceSchemaKeepsDefinitions() {
            JsonObjectSchema address = JsonObjectSchema.builder().addStringProperty("city").build();
            JsonObjectSchema schema = JsonObjectSchema.builder().addProperty("home", JsonReferenceSchema.builder().reference("#/$defs/address").build()).definitions(Map.of("address", address)).build();
            ToolSpecification specification = ToolSpecification.builder().name("withRef").parameters(schema).build();
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("x")).toolSpecifications(specification).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 1L, null, null);

            String tools = record.getToolsDefinition();
            assertTrue(tools.contains("\"$ref\":\"#/$defs/address\""), "$ref 引用在场: " + tools);
            assertTrue(tools.contains("\"$defs\""), "定义本体必须落列，否则重放时引用悬空: " + tools);
            assertTrue(tools.contains("\"city\""));
        }

        @Test
        @DisplayName("缺省参数安全退化（消息非空由 LangChain4j 构建器保证）")
        void emptyShapesDegradeSafely() {
            InteractionRecord record = LangChain4jRecordMapper.toRecord(ChatRequest.builder().messages(UserMessage.from("x")).build(), null, 0L, null, null);
            assertNull(record.getModel());
            assertNull(record.getSamplingParams());
            assertNull(record.getToolsDefinition());
        }
    }

    @Nested
    @DisplayName("响应面映射")
    class ResponseMapping {

        @Test
        @DisplayName("响应字段逐一对齐：id/模型/token/结束原因/正文/工具发起帧")
        void responseFieldsAligned() {
            ChatRequest request = ChatRequest.builder().messages(SystemMessage.from("sys"), UserMessage.from("查订单")).build();
            AiMessage output = new AiMessage(null, List.of(ToolExecutionRequest.builder().id("call-9").name("getOrder").arguments("{\"orderId\":\"8841\"}").build()));
            ChatResponse response = ChatResponse.builder().aiMessage(output).id("resp-1").modelName("deepseek-v4-flash").tokenUsage(new TokenUsage(11, 7, 18)).finishReason(FinishReason.TOOL_EXECUTION).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, response, 12L, 3L, null);

            assertEquals("resp-1", record.getRecordId());
            assertEquals("deepseek-v4-flash", record.getServedModel());
            assertEquals(11, record.getInputTokens());
            assertEquals(7, record.getOutputTokens());
            assertEquals("tool_calls", record.getFinishReason());
            assertEquals(1, record.getToolCalls().size());
            assertEquals("getOrder", record.getToolCalls().get(0).getToolName());
            assertEquals("call-9", record.getToolCalls().get(0).getToolCallId());
            assertEquals(Map.of("orderId", "8841"), record.getToolCalls().get(0).getArguments());
            assertTrue(record.getToolCalls().get(0).isSuccess());
            assertTrue(record.isHasToolCalls());
        }

        @Test
        @DisplayName("纯文本响应：正文落 modelResponse，无工具调用")
        void textResponseMapped() {
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi")).build();
            ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("订单已退款")).finishReason(FinishReason.STOP).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, response, 8L, null, null);

            assertEquals("订单已退款", record.getModelResponse());
            assertFalse(record.isHasToolCalls());
            assertEquals("stop", record.getFinishReason());
        }

        @Test
        @DisplayName("结束原因全词表归一")
        void finishReasonVocabulary() {
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("hi")).build();
            assertEquals("max_tokens", map(request, FinishReason.LENGTH));
            assertEquals("content_filter", map(request, FinishReason.CONTENT_FILTER));
            assertEquals("other", map(request, FinishReason.OTHER));
            assertNull(map(request, null));
        }

        private String map(ChatRequest request, FinishReason reason) {
            ChatResponse response = ChatResponse.builder().aiMessage(AiMessage.from("x")).finishReason(reason).build();
            return LangChain4jRecordMapper.toRecord(request, response, 1L, null, null).getFinishReason();
        }
    }

    @Nested
    @DisplayName("方言归一")
    class DialectNormalization {

        @Test
        @DisplayName("字符串字面量解一层，对象/纯文本原样保留")
        void stringLiteralDecodedOthersPassThrough() {
            assertEquals("REFUND-8841", ToolResultNormalizer.normalize("\"REFUND-8841\""));
            assertEquals("{\"orderId\":8841}", ToolResultNormalizer.normalize("{\"orderId\":8841}"));
            assertEquals("REF-123 已处理", ToolResultNormalizer.normalize("REF-123 已处理"));
            assertNull(ToolResultNormalizer.normalize(null));
            assertEquals("", ToolResultNormalizer.normalize(""));
        }
    }

    @Nested
    @DisplayName("多模态与敌对内容")
    class MultimodalAndHostile {

        @Test
        @DisplayName("图片内容映射为 OpenAI content 数组")
        void imageContentBecomesOpenAiArray() {
            UserMessage user = UserMessage.from(TextContent.from("这张图里的订单"), ImageContent.from(URI.create("https://example.com/order.png")));
            ChatRequest request = ChatRequest.builder().messages(SystemMessage.from("sys"), user).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 1L, null, null);

            assertTrue(record.isMultimodalInput());
            assertNotNull(record.getMultimodalContent());
            assertTrue(record.getUserInput().contains("\"type\":\"text\""));
            assertTrue(record.getUserInput().contains("https://example.com/order.png"));
        }

        @Test
        @DisplayName("中文/emoji/控制字符穿越映射不丢失")
        void hostileContentSurvives() {
            String hostile = "退款单号 REF-①②③ 🚚\u0007 与订单\"8841\"\n多行";
            ChatRequest request = ChatRequest.builder().messages(SystemMessage.from("sys模板"), UserMessage.from(hostile)).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 1L, null, null);

            assertEquals(hostile, record.getUserInput());
            assertEquals("sys模板", record.getTemplateText());
        }

        @Test
        @DisplayName("text() 抛错的新版形状走反射兜底不中断")
        void textThrowingShapeFallsBackToReflection() {
            ToolExecutionResultMessage hostileShape = new ToolExecutionResultMessage("id-1", "tool", "orig") {
                @Override
                public String text() {
                    throw new IllegalStateException("expected single text content");
                }

                public List<Content> contents() {
                    return List.of(TextContent.from("fallback-text"));
                }
            };
            ChatRequest request = ChatRequest.builder().messages(UserMessage.from("q"), hostileShape).build();

            InteractionRecord record = LangChain4jRecordMapper.toRecord(request, null, 1L, null, null);

            // 末位是工具结果：user 帧与 tool 帧都进轮次，tool 在后
            TurnContext tool = record.getPreviousTurns().get(1);
            assertEquals("tool", tool.getRole());
            assertEquals("fallback-text", tool.getContent());
        }
    }
}
