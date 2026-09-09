package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.FingerprintExtractor;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * record 摄取的三协议矩阵测试 — 每协议 wire 夹具经 ingest 全链路落库后逐字段
 * 回读断言：范式归一（模板/末位输入/工具帧/多模态/工具定义嵌套形）、finish 与
 * usage 归一表全值、响应形态自动识别与显式 protocol 覆盖、敌对输入退化不中断。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
class McpRecordIngestionTest {

    @TempDir
    Path tempDir;

    private String dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("ingest-test.db").toString();
    }

    private static Map<String, Object> args(String request, String response) {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("sessionId", "s1");
        args.put("request", request);
        args.put("response", response);
        return args;
    }

    private static Map<String, Object> reportOf(McpToolOutcome outcome) {
        Object parsed = RecursiveJsonParser.parse(outcome.stdout.trim());
        assertTrue(parsed instanceof Map, "stdout 必须是报告 JSON 对象: " + outcome.stdout);
        @SuppressWarnings("unchecked") Map<String, Object> report = (Map<String, Object>) parsed;
        return report;
    }

    private static Map<String, Object> errorOf(McpToolOutcome outcome) {
        Object parsed = RecursiveJsonParser.parse(outcome.stdout.trim());
        assertTrue(parsed instanceof Map, "stdout 必须是错误包络 JSON: " + outcome.stdout);
        @SuppressWarnings("unchecked") Map<String, Object> envelope = (Map<String, Object>) parsed;
        assertEquals("agentassert4j.error/1", envelope.get("schema"), "exit 2 时 stdout 携带错误包络");
        return envelope;
    }

    /**
     * 模板原文是瞬态字段（经 prompt_texts 以 hash 归档）——断言须走该回读路径。
     */
    private String storedTemplate(InteractionRecord record) {
        SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
        try {
            repository.initialize();
            return repository.findTemplateText(record.getTemplateHash());
        } finally {
            repository.close();
        }
    }

    private InteractionRecord stored() {
        SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
        try {
            repository.initialize();
            List<InteractionRecord> records = repository.findBySessionId("s1");
            assertEquals(1, records.size(), "应恰好落库一条: " + records.size());
            return records.get(0);
        } finally {
            repository.close();
        }
    }

    @Nested
    @DisplayName("OpenAI chat 摄取（重构回归钉）")
    class OpenAiChat {

        @Test
        @DisplayName("wire 字段逐项落库 + protocol 回显 openai-chat")
        void happyPath_storesAndEchoesProtocol() {
            String request = "{\"model\":\"deepseek-chat\",\"messages\":[" + "{\"role\":\"system\",\"content\":\"You are a helpful assistant.\"}," + "{\"role\":\"user\",\"content\":\"What is the capital of France?\"}],\"temperature\":0.2}";
            String response = "{\"id\":\"chatcmpl-a\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":" + "{\"role\":\"assistant\",\"content\":\"Paris.\"},\"finish_reason\":\"stop\"}]," + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":2,\"prompt_tokens_details\":{\"cached_tokens\":3}}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, "正常摄取 exit 0: " + outcome.stdout);
            Map<String, Object> report = reportOf(outcome);
            assertEquals("saved", report.get("status"));
            assertEquals("openai-chat", report.get("protocol"));

            InteractionRecord record = stored();
            assertEquals("openai-chat", record.getApiProtocol());
            assertEquals("What is the capital of France?", record.getUserInput());
            assertEquals("Paris.", record.getModelResponse());
            assertEquals(10, record.getInputTokens());
            assertEquals(2, record.getOutputTokens());
            assertEquals(Integer.valueOf(3), record.getCacheReadTokens());
            assertEquals("stop", record.getFinishReason());
        }

        @Test
        @DisplayName("assistant tool_calls 历史帧收编为发起帧（与 anthropic/responses 结构一致）")
        void assistantToolCalls_becomeInitFrames() {
            String request = "{\"model\":\"deepseek-chat\",\"messages\":[" + "{\"role\":\"user\",\"content\":\"Query order 42\"}," + "{\"role\":\"assistant\",\"content\":\"Looking up.\",\"tool_calls\":[{\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"getOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"42\\\"}\"}}]}," + "{\"role\":\"tool\",\"tool_call_id\":\"call_1\",\"content\":\"ORDER-42 found\"}," + "{\"role\":\"user\",\"content\":\"Summarize\"}]}";
            String response = "{\"id\":\"chatcmpl-hist\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"Done.\"},\"finish_reason\":\"stop\"}]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);

            InteractionRecord record = stored();
            List<TurnContext> turns = record.getPreviousTurns();
            assertEquals(4, turns.size(), "user 轮 + assistant 文本轮 + 发起帧 + tool 结果帧: " + turns.size());
            assertEquals("assistant", turns.get(1).getRole());
            assertEquals("Looking up.", turns.get(1).getContent());
            assertEquals("assistant", turns.get(2).getRole());
            assertEquals("call_1", turns.get(2).getToolCallId());
            assertEquals("getOrder", turns.get(2).getToolName());
            assertEquals("{\"orderId\":\"42\"}", turns.get(2).getToolArguments(), "arguments 真值保留");
            assertEquals("tool", turns.get(3).getRole());
            assertEquals("call_1", turns.get(3).getToolCallId());
        }

        @Test
        @DisplayName("空 messages 不再越界：空记录正常落库（身份走形状锚）")
        void emptyMessages_degradesNotCrashes() {
            String request = "{\"model\":\"deepseek-chat\",\"messages\":[]}";
            String response = "{\"id\":\"chatcmpl-empty\",\"model\":\"deepseek-chat\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"hi\"},\"finish_reason\":\"stop\"}]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, "空 messages 应退化落库而非崩溃: " + outcome.stdout);
            InteractionRecord record = stored();
            assertNull(record.getUserInput());
            assertEquals(0, record.getTurnIndex());
        }
    }

    @Nested
    @DisplayName("Anthropic Messages 摄取")
    class AnthropicMessages {

        @Test
        @DisplayName("system 字符串即模板；text 块正文；stop_reason 归一；usage 三项求和")
        void happyPath_mapsAllFaces() {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":1024,\"system\":\"You are a helpful assistant.\"," + "\"messages\":[{\"role\":\"user\",\"content\":\"What is the capital of France?\"}],\"temperature\":0.2}";
            String response = "{\"id\":\"msg_a\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-3-5\"," + "\"content\":[{\"type\":\"text\",\"text\":\"Paris.\"}],\"stop_reason\":\"end_turn\"," + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"cache_creation_input_tokens\":3,\"cache_read_input_tokens\":2}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            Map<String, Object> report = reportOf(outcome);
            assertEquals("anthropic-messages", report.get("protocol"));

            InteractionRecord record = stored();
            assertEquals("anthropic-messages", record.getApiProtocol());
            assertEquals("You are a helpful assistant.", storedTemplate(record));
            assertEquals("What is the capital of France?", record.getUserInput());
            assertEquals(0, record.getTurnIndex());
            assertEquals("Paris.", record.getModelResponse());
            assertEquals("stop", record.getFinishReason());
            // input_tokens 是非缓存口径：总量 = 10 + 3 + 2
            assertEquals(15, record.getInputTokens());
            assertEquals(2, record.getOutputTokens());
            assertEquals(Integer.valueOf(2), record.getCacheReadTokens());
            assertEquals(Integer.valueOf(3), record.getCacheWriteTokens());
            assertEquals("claude-3-5", record.getServedModel());
            assertEquals("msg_a", record.getRecordId());
        }

        @Test
        @DisplayName("system 块数组拼接为模板；末位 user 纯 text 块数组拼接为文本")
        void blockSystemAndTrailingTextBlocks() {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":1024,\"system\":[{\"type\":\"text\",\"text\":\"Part one. \"},{\"type\":\"text\",\"text\":\"Part two.\"}]," + "\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"Hello \"},{\"type\":\"text\",\"text\":\"world\"}]}]}";
            String response = "{\"id\":\"msg_b\",\"model\":\"claude-3-5\",\"content\":[{\"type\":\"text\",\"text\":\"Hi.\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":5,\"output_tokens\":1}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            InteractionRecord record = stored();
            assertEquals("Part one. Part two.", storedTemplate(record));
            assertEquals("Hello world", record.getUserInput());
            assertFalse(record.isMultimodalInput(), "纯 text 块拼接为文本，不是多模态");
        }

        @Test
        @DisplayName("tool_use 响应块→toolCalls（对象形参数直取）；tools 扁平定义转范式嵌套形")
        void toolUseResponse_andFlatTools() {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":1024,\"system\":\"You call tools.\"," + "\"messages\":[{\"role\":\"user\",\"content\":\"Query order 42\"}]," + "\"tools\":[{\"name\":\"getOrder\",\"description\":\"Fetch an order\",\"input_schema\":{\"type\":\"object\",\"properties\":{\"orderId\":{\"type\":\"string\"}}}}]}";
            String response = "{\"id\":\"msg_t\",\"model\":\"claude-3-5\"," + "\"content\":[{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"getOrder\",\"input\":{\"orderId\":\"42\"}}]," + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":20,\"output_tokens\":5}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertEquals("saved", reportOf(outcome).get("status"));

            InteractionRecord record = stored();
            assertTrue(record.isHasToolCalls());
            assertEquals(1, record.getToolCalls().size());
            assertEquals("toolu_1", record.getToolCalls().get(0).getToolCallId());
            assertEquals("getOrder", record.getToolCalls().get(0).getToolName());
            assertEquals("42", record.getToolCalls().get(0).getArguments().get("orderId"));
            assertFalse(record.getToolCalls().get(0).getArgTypes().isEmpty());
            assertEquals("tool_calls", record.getFinishReason());
            assertNull(record.getModelResponse(), "纯 tool_use 响应无正文");
            // 工具定义范式嵌套形（范式 = OpenAI tools 形）
            assertTrue(record.getToolsDefinition().contains("\"function\":{\"name\":\"getOrder\""), record.getToolsDefinition());
            assertTrue(record.getToolsDefinition().contains("\"parameters\":"), record.getToolsDefinition());
        }

        @Test
        @DisplayName("多轮带工具帧：发起帧带 id/name/arguments 真值，结果帧 id 配对回填 name")
        void historyToolFrames_pairingAndNameBackfill() {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":1024," + "\"messages\":[" + "{\"role\":\"user\",\"content\":\"Query order 42\"}," + "{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"Looking up.\"},{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"getOrder\",\"input\":{\"orderId\":\"42\"}}]}," + "{\"role\":\"user\",\"content\":[{\"type\":\"tool_result\",\"tool_use_id\":\"toolu_1\",\"content\":\"ORDER-42 found\"}]}]}";
            String response = "{\"id\":\"msg_h\",\"model\":\"claude-3-5\",\"content\":[{\"type\":\"text\",\"text\":\"Done.\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":30,\"output_tokens\":4}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);

            InteractionRecord record = stored();
            List<TurnContext> turns = record.getPreviousTurns();
            assertEquals(4, turns.size(), "user 轮 + assistant 文本轮 + assistant 发起帧 + tool 结果帧: " + turns.size());
            // 逐帧断言：块序保真（text 聚合在前、tool_use 帧在后、tool_result 帧最后）
            assertEquals("user", turns.get(0).getRole());
            assertEquals("Query order 42", turns.get(0).getContent());
            assertEquals("assistant", turns.get(1).getRole());
            assertEquals("Looking up.", turns.get(1).getContent());
            assertEquals("assistant", turns.get(2).getRole());
            assertEquals("toolu_1", turns.get(2).getToolCallId());
            assertEquals("getOrder", turns.get(2).getToolName());
            assertEquals("{\"orderId\":\"42\"}", turns.get(2).getToolArguments());
            assertEquals("tool", turns.get(3).getRole());
            assertEquals("ORDER-42 found", turns.get(3).getContent());
            assertEquals("toolu_1", turns.get(3).getToolCallId());
            assertEquals("getOrder", turns.get(3).getToolName(), "结果帧的名字由同请求内配对回填");
            // 末位 user 含 tool_result = 链式中间态：turnIndex 走下一轮计数（2 个 user 消息后）
            assertEquals(2, record.getTurnIndex());
        }

        @ParameterizedTest(name = "stop_reason={0} → {1}")
        @DisplayName("stop_reason 归一表全值")
        @CsvSource({"end_turn, stop", "stop_sequence, stop", "tool_use, tool_calls", "max_tokens, max_tokens", "refusal, content_filter", "pause_turn, other"})
        void finishNormalization_fullTable(String stopReason, String expected) {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":1024,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            String response = "{\"id\":\"msg_f\",\"model\":\"claude-3-5\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"stop_reason\":\"" + stopReason + "\"}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertEquals(expected, stored().getFinishReason());
        }

        @Test
        @DisplayName("末位 user image 块转范式 data-URI；历史轮 image 丢弃并告警")
        void imageBlocks_paradigmAndWarning() {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":1024," + "\"messages\":[" + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"first\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\"AAAA\"}}]}," + "{\"role\":\"assistant\",\"content\":\"ok\"}," + "{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"describe\"},{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/jpeg\",\"data\":\"BBBB\"}}]}]}";
            String response = "{\"id\":\"msg_i\",\"model\":\"claude-3-5\",\"content\":[{\"type\":\"text\",\"text\":\"a cat\"}],\"stop_reason\":\"end_turn\"}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertTrue(outcome.stderr.contains("Warning"), "历史轮 image 丢弃必须可见: " + outcome.stderr);

            InteractionRecord record = stored();
            assertTrue(record.isMultimodalInput());
            assertTrue(record.getUserInput().contains("\"url\":\"data:image/jpeg;base64,BBBB\""), record.getUserInput());
            assertTrue(record.getUserInput().contains("\"type\":\"text\",\"text\":\"describe\""), record.getUserInput());
            // 历史轮 image 无载体：轮内只剩文本
            assertEquals("first", record.getPreviousTurns().get(0).getContent());
        }

        @Test
        @DisplayName("采样参数按该方言键集收取（max_tokens/stop_sequences）")
        void samplingKeys_anthropicDialect() {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":512,\"temperature\":0.3,\"top_p\":0.9,\"stop_sequences\":[\"END\"]," + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            String response = "{\"id\":\"msg_s\",\"model\":\"claude-3-5\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"stop_reason\":\"end_turn\"}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            String sampling = stored().getSamplingParams();
            assertTrue(sampling.contains("\"max_tokens\":512"), sampling);
            assertTrue(sampling.contains("\"stop_sequences\""), sampling);
            assertFalse(sampling.contains("frequency_penalty"), "chat 专属键不入该方言采样面: " + sampling);
        }

        @Test
        @DisplayName("中文 UTF-8 全链")
        void chineseSurvives() {
            String request = "{\"model\":\"claude-3-5\",\"max_tokens\":1024,\"system\":\"你是订单助手。\"," + "\"messages\":[{\"role\":\"user\",\"content\":\"查订单 ORD-001\"}]}";
            String response = "{\"id\":\"msg_z\",\"model\":\"claude-3-5\",\"content\":[{\"type\":\"text\",\"text\":\"订单已发货。\"}],\"stop_reason\":\"end_turn\"}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            InteractionRecord record = stored();
            assertEquals("查订单 ORD-001", record.getUserInput());
            assertEquals("订单已发货。", record.getModelResponse());
            assertEquals("你是订单助手。", storedTemplate(record));
        }
    }

    @Nested
    @DisplayName("OpenAI Responses 摄取")
    class OpenAiResponses {

        @Test
        @DisplayName("instructions 即模板；input 字符串直为末位输入；output_text 正文")
        void stringInput_happyPath() {
            String request = "{\"model\":\"gpt-4o\",\"instructions\":\"You are a helpful assistant.\",\"input\":\"What is the capital of France?\",\"temperature\":0.2}";
            String response = "{\"id\":\"resp_a\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o\"," + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Paris.\"}]}]," + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2,\"input_tokens_details\":{\"cached_tokens\":3},\"output_tokens_details\":{\"reasoning_tokens\":4}}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            Map<String, Object> report = reportOf(outcome);
            assertEquals("openai-responses", report.get("protocol"));

            InteractionRecord record = stored();
            assertEquals("openai-responses", record.getApiProtocol());
            assertEquals("You are a helpful assistant.", storedTemplate(record));
            assertEquals("What is the capital of France?", record.getUserInput());
            assertEquals(0, record.getTurnIndex());
            assertEquals("Paris.", record.getModelResponse());
            assertEquals("stop", record.getFinishReason());
            assertEquals(10, record.getInputTokens());
            assertEquals(2, record.getOutputTokens());
            assertEquals(Integer.valueOf(3), record.getCacheReadTokens());
            assertEquals(Integer.valueOf(4), record.getReasoningTokens());
            assertEquals("resp_a", record.getRecordId());
        }

        @Test
        @DisplayName("items：message 轮 + function_call 发起帧（arguments 真值）+ 输出帧 name 回填 + reasoning 跳过")
        void items_functionRoundTrip() {
            String request = "{\"model\":\"gpt-4o\",\"instructions\":\"You call tools.\"," + "\"input\":[" + "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Query order 42\"}]}," + "{\"type\":\"reasoning\",\"summary\":[{\"type\":\"summary_text\",\"text\":\"thinking\"}]}," + "{\"type\":\"function_call\",\"call_id\":\"call_1\",\"name\":\"getOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"42\\\"}\"}," + "{\"type\":\"function_call_output\",\"call_id\":\"call_1\",\"output\":\"ORDER-42 found\"}," + "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"Summarize\"}]}]," + "\"tools\":[{\"type\":\"function\",\"name\":\"getOrder\",\"description\":\"Fetch\",\"parameters\":{\"type\":\"object\"}}]}";
            String response = "{\"id\":\"resp_h\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o\"," + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"Done.\"}]}],\"usage\":{\"input_tokens\":30,\"output_tokens\":4}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);

            InteractionRecord record = stored();
            assertEquals("Summarize", record.getUserInput());
            assertEquals(1, record.getTurnIndex(), "第 2 个 user 输入（0 基 1）");
            List<TurnContext> turns = record.getPreviousTurns();
            assertEquals(3, turns.size(), "user 轮 + 发起帧 + 结果帧（reasoning item 跳过、末位输入不入轮）: " + turns.size());
            assertEquals("user", turns.get(0).getRole());
            assertEquals("Query order 42", turns.get(0).getContent());
            assertEquals("assistant", turns.get(1).getRole());
            assertEquals("call_1", turns.get(1).getToolCallId());
            assertEquals("getOrder", turns.get(1).getToolName());
            assertEquals("{\"orderId\":\"42\"}", turns.get(1).getToolArguments(), "发起帧 arguments 真值保留");
            assertEquals("tool", turns.get(2).getRole());
            assertEquals("ORDER-42 found", turns.get(2).getContent());
            assertEquals("call_1", turns.get(2).getToolCallId());
            assertEquals("getOrder", turns.get(2).getToolName(), "输出帧名字由同请求配对回填");
            // 工具定义扁平形转范式嵌套形
            assertTrue(record.getToolsDefinition().contains("\"function\":{\"name\":\"getOrder\""), record.getToolsDefinition());
        }

        @Test
        @DisplayName("响应 function_call item→toolCalls；有 function_call 时 finish 恒 tool_calls")
        void responseFunctionCall() {
            String request = "{\"model\":\"gpt-4o\",\"instructions\":\"You call tools.\",\"input\":\"Query order 42\"}";
            String response = "{\"id\":\"resp_t\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o\"," + "\"output\":[{\"type\":\"function_call\",\"call_id\":\"call_9\",\"name\":\"getOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"42\\\"}\"}]," + "\"usage\":{\"input_tokens\":20,\"output_tokens\":5}}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            InteractionRecord record = stored();
            assertTrue(record.isHasToolCalls());
            assertEquals("call_9", record.getToolCalls().get(0).getToolCallId());
            assertEquals("getOrder", record.getToolCalls().get(0).getToolName());
            assertEquals("42", record.getToolCalls().get(0).getArguments().get("orderId"));
            assertEquals("tool_calls", record.getFinishReason());
        }

        @ParameterizedTest(name = "status={0}/{1} → {2}")
        @DisplayName("finish 派生表全值（status × incomplete reason × output 形态）")
        @CsvSource({"completed, , stop", "incomplete, max_output_tokens, max_tokens", "incomplete, content_filter, content_filter", "failed, , other"})
        void finishDerivation_fullTable(String status, String reason, String expected) {
            String request = "{\"model\":\"gpt-4o\",\"input\":\"hi\"}";
            String incomplete = reason == null || reason.isEmpty() ? "" : ",\"incomplete_details\":{\"reason\":\"" + reason + "\"}";
            String response = "{\"id\":\"resp_f\",\"object\":\"response\",\"status\":\"" + status + "\"" + incomplete + "," + "\"output\":[]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertEquals(expected, stored().getFinishReason());
        }

        @Test
        @DisplayName("末位 input_image 直通范式多模态（url 与 data-URI 形均合法）")
        void inputImage_passthrough() {
            String request = "{\"model\":\"gpt-4o\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[" + "{\"type\":\"input_text\",\"text\":\"describe\"}," + "{\"type\":\"input_image\",\"image_url\":\"data:image/png;base64,AAAA\"}]}]}";
            String response = "{\"id\":\"resp_m\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o\"," + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"a cat\"}]}]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            InteractionRecord record = stored();
            assertTrue(record.isMultimodalInput());
            assertTrue(record.getUserInput().contains("\"url\":\"data:image/png;base64,AAAA\""), record.getUserInput());
        }

        @Test
        @DisplayName("items 内 system message 锚模板；采样键集按该方言（max_output_tokens）")
        void systemMessageItem_andSampling() {
            String request = "{\"model\":\"gpt-4o\"," + "\"input\":[{\"type\":\"message\",\"role\":\"system\",\"content\":[{\"type\":\"input_text\",\"text\":\"You are terse.\"}]}," + "{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"hi\"}]}]," + "\"max_output_tokens\":256,\"temperature\":0.1}";
            String response = "{\"id\":\"resp_s\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-4o\"," + "\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"hi\"}]}]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            InteractionRecord record = stored();
            assertEquals("You are terse.", storedTemplate(record));
            assertTrue(record.getSamplingParams().contains("\"max_output_tokens\":256"), record.getSamplingParams());
            assertFalse(record.getSamplingParams().contains("max_tokens"), "chat 专属键不入该方言采样面: " + record.getSamplingParams());
        }
    }

    @Nested
    @DisplayName("协议自动识别与显式声明")
    class ProtocolDetection {

        @Test
        @DisplayName("chat 响应形态（choices）→ openai-chat")
        void detectsChat() {
            String request = "{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            String response = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\"stop\"}]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertEquals("openai-chat", reportOf(outcome).get("protocol"));
        }

        @Test
        @DisplayName("anthropic 响应形态（stop_reason / content 块数组）→ anthropic-messages")
        void detectsAnthropic() {
            String request = "{\"model\":\"m\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            String response = "{\"id\":\"m1\",\"content\":[{\"type\":\"text\",\"text\":\"x\"}],\"stop_reason\":\"end_turn\"}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertEquals("anthropic-messages", reportOf(outcome).get("protocol"));
        }

        @Test
        @DisplayName("responses 响应形态（status + output 数组）→ openai-responses")
        void detectsResponses() {
            String request = "{\"model\":\"m\",\"input\":\"hi\"}";
            String response = "{\"id\":\"r1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertEquals("openai-responses", reportOf(outcome).get("protocol"));
        }

        @Test
        @DisplayName("无法识别 → E-USAGE 列三候选与建议")
        void unrecognizable_isErrorUsage() {
            String request = "{\"model\":\"m\"}";
            String response = "{\"id\":\"x\",\"foo\":1}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(2, outcome.exit);
            Map<String, Object> error = errorOf(outcome);
            assertEquals("E-USAGE", error.get("errorCode"));
            assertTrue(outcome.stdout.contains("openai-chat"), "错误信息列三合法值: " + outcome.stdout);
            assertTrue(outcome.stdout.contains("anthropic-messages"), outcome.stdout);
            assertTrue(outcome.stdout.contains("openai-responses"), outcome.stdout);
        }

        @Test
        @DisplayName("显式 protocol 覆盖识别（chat 形响应 + 显式 anthropic-messages 按该方言解析）")
        void explicitProtocol_overridesDetection() {
            String request = "{\"model\":\"m\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            String response = "{\"id\":\"c1\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"x\"},\"finish_reason\":\"stop\"}]}";
            Map<String, Object> call = args(request, response);
            call.put("protocol", "anthropic-messages");
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, call);
            assertEquals(0, outcome.exit, "显式声明优先，按该方言解析（请求体同协议假定）: " + outcome.stdout);
            assertEquals("anthropic-messages", reportOf(outcome).get("protocol"));
            assertEquals("anthropic-messages", stored().getApiProtocol());
        }

        @Test
        @DisplayName("非法 protocol 值 → E-USAGE 列三合法值")
        void illegalProtocol_isErrorUsage() {
            Map<String, Object> call = args("{\"model\":\"m\"}", "{\"id\":\"x\",\"choices\":[]}");
            call.put("protocol", "gemini");
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, call);
            assertEquals(2, outcome.exit);
            assertEquals("E-USAGE", errorOf(outcome).get("errorCode"));
            assertTrue(outcome.stdout.contains("openai-chat"), outcome.stdout);
        }
    }

    @Nested
    @DisplayName("跨源可比性（范式归一的价值主张）")
    class CrossSourceComparability {

        /**
         * 同语义交互的三协议 wire 夹具落库回读（同声明标签 crossSource）。
         */
        private InteractionRecord ingestWire(String sessionId, String request, String response) {
            Map<String, Object> call = args(request, response);
            call.put("sessionId", sessionId);
            call.put("invocation", "crossSource");
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, call);
            assertEquals(0, outcome.exit, outcome.stdout);
            SqliteStorageRepository repository = new SqliteStorageRepository(dbPath);
            try {
                repository.initialize();
                List<InteractionRecord> records = repository.findBySessionId(sessionId);
                assertEquals(1, records.size());
                return records.get(0);
            } finally {
                repository.close();
            }
        }

        @Test
        @DisplayName("同语义交互三协议各摄取一条 → 身份同键、指纹 text/tool 维等价")
        void sameSemantics_equivalentFingerprints() {
            String chatRequest = "{\"model\":\"m\",\"messages\":[" + "{\"role\":\"system\",\"content\":\"You call tools.\"}," + "{\"role\":\"user\",\"content\":\"Query order 42\"}]}";
            String chatResponse = "{\"id\":\"c1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ORDER-42 found.\"," + "\"tool_calls\":[{\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"getOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"42\\\"}\"}}]}," + "\"finish_reason\":\"tool_calls\"}]}";
            String anthropicRequest = "{\"model\":\"m\",\"max_tokens\":10,\"system\":\"You call tools.\",\"messages\":[{\"role\":\"user\",\"content\":\"Query order 42\"}]}";
            String anthropicResponse = "{\"id\":\"m1\",\"content\":[{\"type\":\"text\",\"text\":\"ORDER-42 found.\"}," + "{\"type\":\"tool_use\",\"id\":\"t1\",\"name\":\"getOrder\",\"input\":{\"orderId\":\"42\"}}],\"stop_reason\":\"tool_use\"}";
            String responsesRequest = "{\"model\":\"m\",\"instructions\":\"You call tools.\",\"input\":\"Query order 42\"}";
            String responsesResponse = "{\"id\":\"r1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[" + "{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ORDER-42 found.\"}]}," + "{\"type\":\"function_call\",\"call_id\":\"t1\",\"name\":\"getOrder\",\"arguments\":\"{\\\"orderId\\\":\\\"42\\\"}\"}]}";

            InteractionRecord chat = ingestWire("cross-chat", chatRequest, chatResponse);
            InteractionRecord anthropic = ingestWire("cross-anthropic", anthropicRequest, anthropicResponse);
            InteractionRecord responses = ingestWire("cross-responses", responsesRequest, responsesResponse);

            assertEquals(chat.getInvocationKey(), anthropic.getInvocationKey(), "同声明标签同模板 → 同调用点键");
            assertEquals(chat.getInvocationKey(), responses.getInvocationKey());

            InvocationRulesConfig rules = new InvocationRulesConfig();
            DeterministicFingerprint chatFp = FingerprintExtractor.extract(chat, rules, "crossSource");
            DeterministicFingerprint anthropicFp = FingerprintExtractor.extract(anthropic, rules, "crossSource");
            DeterministicFingerprint responsesFp = FingerprintExtractor.extract(responses, rules, "crossSource");

            assertEquals(chatFp.getOutputContentType(), anthropicFp.getOutputContentType(), "text 维：输出内容类型等价");
            assertEquals(chatFp.getOutputContentType(), responsesFp.getOutputContentType());
            assertEquals(chatFp.getOutputFieldPaths(), anthropicFp.getOutputFieldPaths(), "text 维：输出字段路径等价");
            assertEquals(chatFp.getOutputFieldPaths(), responsesFp.getOutputFieldPaths());
            assertEquals(chatFp.getToolCallSet(), anthropicFp.getToolCallSet(), "tool 维：工具集合等价");
            assertEquals(chatFp.getToolCallSet(), responsesFp.getToolCallSet());
            assertEquals(chatFp.getToolParamTypes(), anthropicFp.getToolParamTypes(), "tool 维：工具参数类型等价");
            assertEquals(chatFp.getToolParamTypes(), responsesFp.getToolParamTypes());
        }
    }

    @Nested
    @DisplayName("敌对输入退化")
    class Hostile {

        @Test
        @DisplayName("块形态不符（元素非对象/无 type）静默跳过，不中断落库")
        void malformedBlocks_skipQuietly() {
            String request = "{\"model\":\"m\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":[\"bare string\", 42]}]}";
            String response = "{\"id\":\"m1\",\"content\":[\"bare\", {\"text\":\"no type\"}],\"stop_reason\":\"end_turn\"}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            InteractionRecord record = stored();
            assertEquals("", record.getUserInput(), "无可识别 text 块，输入退化空串");
            assertNull(record.getModelResponse());
        }

        @Test
        @DisplayName("tool_use input 非对象 → 空参数表；function_call arguments 坏 JSON → 空参数表")
        void badArguments_degradeToEmpty() {
            String request = "{\"model\":\"m\",\"input\":[{\"type\":\"message\",\"role\":\"user\",\"content\":[{\"type\":\"input_text\",\"text\":\"q\"}]}]}";
            String response = "{\"id\":\"r1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[" + "{\"type\":\"function_call\",\"call_id\":\"c1\",\"name\":\"t\",\"arguments\":\"not json {\"}]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            assertTrue(stored().getToolCalls().get(0).getArguments().isEmpty());
        }

        @Test
        @DisplayName("anthropic 响应 content 空数组且无 stop_reason → 不识别（宁报错不静默错判）")
        void emptyContentArrayNoStopReason_unrecognized() {
            String request = "{\"model\":\"m\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
            String response = "{\"id\":\"m1\",\"content\":[]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(2, outcome.exit);
            assertEquals("E-USAGE", errorOf(outcome).get("errorCode"));
        }

        @Test
        @DisplayName("responses 空 output 数组 → 识别成功（status 在），正文/工具退化空")
        void emptyOutputArray_stillRecognized() {
            String request = "{\"model\":\"m\",\"input\":\"hi\"}";
            String response = "{\"id\":\"r1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[]}";
            McpToolOutcome outcome = McpRecordIngestion.ingest(dbPath, args(request, response));
            assertEquals(0, outcome.exit, outcome.stdout);
            InteractionRecord record = stored();
            assertNull(record.getModelResponse());
            assertFalse(record.isHasToolCalls());
        }
    }
}
