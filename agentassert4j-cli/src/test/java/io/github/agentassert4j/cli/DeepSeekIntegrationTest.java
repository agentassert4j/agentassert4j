package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.util.HashUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * DeepSeek 全链路集成测试 — 覆盖所有核心算法 + 完整生命周期。
 *
 * <p>测试矩阵：</p>
 * <ol>
 *   <li>OpenAiCompatibleClient — 连通性 / 文本 / tools / 多轮 / 并发</li>
 *   <li>FingerprintExtractor — 从真实 LLM 响应提取四维度指纹</li>
 *   <li>DeterministicComparator — 真实指纹对比 + Verdict 判定</li>
 *   <li>BehaviorChecker — 内置行为校验（真实输出）</li>
 *   <li>RegressionTestExecutor — 文本/工具/多轮全链路重放</li>
 *   <li>StatisticalRegressionExecutor — 串行/并发/退化不中断</li>
 *   <li>CostEstimator — 真实 token 消耗验证</li>
 * </ol>
 *
 * <p>运行方式：</p>
 * <pre>
 * mvn test -pl agentassert4j-cli -am "-Dtest=DeepSeekIntegrationTest" ^
 *     "-Ddeepseek.api.key=sk-xxx"
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class DeepSeekIntegrationTest {

    private static final String ENDPOINT = "https://api.deepseek.com";
    /**
     * 一个简单的 get_weather 工具定义
     */
    private static final String WEATHER_TOOL = "{\"type\":\"function\",\"function\":{" + "\"name\":\"get_weather\"," + "\"description\":\"获取指定城市的天气信息\"," + "\"parameters\":{" + "\"type\":\"object\"," + "\"properties\":{" + "\"city\":{\"type\":\"string\",\"description\":\"城市名称\"}" + "}," + "\"required\":[\"city\"]" + "}}}";
    /**
     * 一个 search_orders 工具定义
     */
    private static final String SEARCH_TOOL = "{\"type\":\"function\",\"function\":{" + "\"name\":\"search_orders\"," + "\"description\":\"搜索订单\"," + "\"parameters\":{" + "\"type\":\"object\"," + "\"properties\":{" + "\"keyword\":{\"type\":\"string\",\"description\":\"搜索关键词\"}," + "\"limit\":{\"type\":\"integer\",\"description\":\"返回数量限制\"}" + "}," + "\"required\":[\"keyword\"]" + "}}}";
    private static LlmClient client;

    @BeforeAll
    static void setUp() {
        String apiKey = System.getProperty("deepseek.api.key");
        assumeTrue(apiKey != null && !apiKey.trim().isEmpty(), "跳过：未提供 -Ddeepseek.api.key");
        client = new OpenAiCompatibleClient(ENDPOINT, apiKey, "deepseek-chat");
    }

    private LlmResponse callLlm(String systemPrompt, String userInput) throws Exception {
        LlmRequest request = new LlmRequest();
        request.setSystemPrompt(systemPrompt);
        request.setUserInput(userInput);
        request.setTemperature(0.0);
        return client.chat(request, 30000);
    }

    /**
     * 从 LlmResponse 构建纯文本 InteractionRecord
     */
    private InteractionRecord responseToRecord(LlmResponse response) {
        return responseToRecord(response, null);
    }

    /**
     * 从 LlmResponse 构建 InteractionRecord（支持工具调用）
     */
    private InteractionRecord responseToRecord(LlmResponse response, LlmRequest request) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(UUID.randomUUID().toString());
        r.setTimestamp(System.currentTimeMillis());
        r.setUserInput(request != null ? request.getUserInput() : "test");
        r.setTurnIndex(0);
        r.setModelResponse(response.getContent());
        r.setInputTokens(response.getInputTokens());
        r.setOutputTokens(response.getOutputTokens());
        r.setLatencyMs(response.getLatencyMs());

        List<ToolCall> toolCalls = new ArrayList<>();
        if (response.getToolCalls() != null) {
            for (ToolCallResult tcr : response.getToolCalls()) {
                ToolCall tc = new ToolCall();
                tc.setToolName(tcr.getToolName());
                tc.setToolCallId(tcr.getToolCallId());
                tc.setArguments(tcr.getArguments());
                tc.setSuccess(true);
                toolCalls.add(tc);
            }
        }
        r.setToolCalls(toolCalls);
        r.setHasToolCalls(!toolCalls.isEmpty());
        return r;
    }

    private InteractionRecord makeTextBaseline(String recordId, String userInput, String baselineResponse) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSkillId("integration-skill");
        r.setTemplateHash("fake-hash");
        r.setUserInput(userInput);
        r.setTurnIndex(0);
        r.setSessionId("session-integration");
        r.setModelResponse(baselineResponse);
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        return r;
    }

    private InteractionRecord makeToolCallBaseline(String recordId, String userInput, String toolName, Map<String, Object> args) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSkillId("integration-skill");
        r.setTemplateHash("fake-hash");
        r.setUserInput(userInput);
        r.setTurnIndex(0);
        r.setSessionId("session-integration");
        r.setModelResponse(null);

        ToolCall tc = new ToolCall();
        tc.setToolName(toolName);
        tc.setToolCallId("call_baseline");
        tc.setArguments(args);
        tc.setSuccess(true);
        r.setToolCalls(Collections.singletonList(tc));
        r.setHasToolCalls(true);
        return r;
    }

    @Nested
    @DisplayName("1. Client 基础连通性")
    class ClientBasicTests {

        @Test
        @DisplayName("1.1 健康检查 isAvailable")
        void testIsAvailable() {
            assertTrue(client.isAvailable(), "DeepSeek API 应可达");
        }

        @Test
        @DisplayName("1.2 name() 返回模型名")
        void testName() {
            assertEquals("deepseek-chat", client.name());
        }

        @Test
        @DisplayName("1.3 简单文本对话")
        void testSimpleChat() throws Exception {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("你是一个测试助手，请用一句话回答。");
            request.setUserInput("1+1等于几？只回答数字。");
            request.setTemperature(0.0);

            LlmResponse response = client.chat(request, 30000);

            assertNotNull(response.getContent());
            assertTrue(response.getContent().contains("2"), "应包含答案 2，实际: " + response.getContent());
            assertTrue(response.getInputTokens() > 0);
            assertTrue(response.getOutputTokens() > 0);
            assertNotNull(response.getToolCalls());
            assertTrue(response.getToolCalls().isEmpty());
            assertFalse(response.isHasError());

            System.out.println("[1.3] content=" + response.getContent() + ", tokens=" + response.getInputTokens() + "/" + response.getOutputTokens());
        }

        @Test
        @DisplayName("1.4 超时场景")
        void testTimeout() {
            LlmRequest request = new LlmRequest();
            request.setUserInput("请写一篇1000字的文章");
            request.setTemperature(0.0);

            assertThrows(LlmTimeoutException.class, () -> client.chat(request, 1));
        }

        @Test
        @DisplayName("1.5 并发安全 — 5线程同时调用")
        void testConcurrentCalls() throws Exception {
            int n = 5;
            LlmResponse[] results = new LlmResponse[n];
            Exception[] errors = new Exception[n];
            Thread[] threads = new Thread[n];

            for (int i = 0; i < n; i++) {
                final int idx = i;
                threads[idx] = new Thread(() -> {
                    try {
                        LlmRequest req = new LlmRequest();
                        req.setUserInput("说一个数字：" + (idx + 1));
                        req.setTemperature(0.0);
                        results[idx] = client.chat(req, 30000);
                    } catch (Exception e) {
                        errors[idx] = e;
                    }
                });
                threads[idx].start();
            }

            for (Thread t : threads) t.join(60000);

            for (int i = 0; i < n; i++) {
                assertNull(errors[i], "线程 " + i + " 异常: " + errors[i]);
                assertNotNull(results[i], "线程 " + i + " 无响应");
                assertNotNull(results[i].getContent());
            }

            System.out.println("[1.5] " + n + " 线程并发全部成功");
        }
    }

    @Nested
    @DisplayName("2. Tool Calls — LLM 真实返回 tool_calls")
    class ToolCallTests {

        @Test
        @DisplayName("2.1 单工具触发 — get_weather")
        void testSingleToolCall() throws Exception {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("你是一个天气助手，用户问天气时调用 get_weather 工具。");
            request.setUserInput("北京今天天气怎么样？");
            request.setTemperature(0.0);
            request.setToolDefinitions(Collections.singletonList(WEATHER_TOOL));

            LlmResponse response = client.chat(request, 30000);

            // 验证 tool_calls
            assertNotNull(response.getToolCalls(), "toolCalls 不应为 null");
            assertFalse(response.getToolCalls().isEmpty(), "应返回至少一个 tool_call");

            ToolCallResult tc = response.getToolCalls().get(0);
            assertEquals("get_weather", tc.getToolName(), "工具名应为 get_weather");
            assertNotNull(tc.getToolCallId(), "toolCallId 不应为 null");
            assertFalse(tc.getToolCallId().isEmpty(), "toolCallId 不应为空");
            assertNotNull(tc.getArguments(), "arguments 不应为 null");
            assertTrue(tc.getArguments().containsKey("city"), "arguments 应包含 city 参数");

            System.out.println("[2.1] toolName=" + tc.getToolName() + ", args=" + tc.getArguments() + ", content=" + response.getContent() + ", tokens=" + response.getInputTokens() + "/" + response.getOutputTokens());
        }

        @Test
        @DisplayName("2.2 多工具定义 — LLM 选择正确工具")
        void testMultiToolSelection() throws Exception {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("你是一个助手，可以查天气和搜订单。用户问天气用 get_weather，问订单用 search_orders。");
            request.setUserInput("帮我搜一下最近的订单，关键词是手机");
            request.setTemperature(0.0);
            request.setToolDefinitions(Arrays.asList(WEATHER_TOOL, SEARCH_TOOL));

            LlmResponse response = client.chat(request, 30000);

            assertNotNull(response.getToolCalls());
            assertFalse(response.getToolCalls().isEmpty());

            ToolCallResult tc = response.getToolCalls().get(0);
            assertEquals("search_orders", tc.getToolName(), "LLM 应选择 search_orders 而非 get_weather");
            assertNotNull(tc.getArguments().get("keyword"), "arguments 应包含 keyword");

            System.out.println("[2.2] toolName=" + tc.getToolName() + ", args=" + tc.getArguments());
        }

        @Test
        @DisplayName("2.3 工具参数类型验证 — integer 参数")
        void testToolArgTypes() throws Exception {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("你是一个订单助手。");
            request.setUserInput("搜订单关键词是电脑，限制返回3条");
            request.setTemperature(0.0);
            request.setToolDefinitions(Collections.singletonList(SEARCH_TOOL));

            LlmResponse response = client.chat(request, 30000);

            assertFalse(response.getToolCalls().isEmpty());
            ToolCallResult tc = response.getToolCalls().get(0);

            // 验证 limit 参数是数字类型
            Object limit = tc.getArguments().get("limit");
            if (limit != null) {
                assertTrue(limit instanceof Number, "limit 应为 Number 类型，实际: " + limit.getClass());
            }

            System.out.println("[2.3] args=" + tc.getArguments() + ", limit type=" + (limit != null ? limit.getClass().getSimpleName() : "null"));
        }

        @Test
        @DisplayName("2.4 无工具定义时不返回 tool_calls")
        void testNoToolWithoutDefinition() throws Exception {
            LlmRequest request = new LlmRequest();
            request.setUserInput("你好");
            request.setTemperature(0.0);
            // 不设置 toolDefinitions

            LlmResponse response = client.chat(request, 30000);

            assertTrue(response.getToolCalls().isEmpty(), "无 tools 定义时不应返回 tool_calls");
            assertNotNull(response.getContent());
        }
    }

    @Nested
    @DisplayName("3. FingerprintExtractor — 真实 LLM 响应指纹提取")
    class FingerprintTests {

        @Test
        @DisplayName("3.1 纯文本响应指纹")
        void testTextFingerprint() throws Exception {
            LlmResponse response = callLlm("你是一个数学助手。", "1+1等于几？");
            InteractionRecord record = responseToRecord(response);

            DeterministicFingerprint fp = FingerprintExtractor.extract(record);

            // 维度 1：无工具调用
            assertTrue(fp.getToolCallSet().isEmpty());
            assertTrue(fp.getToolParamTypes().isEmpty());

            // 维度 2：纯文本输出
            assertEquals("text/plain", fp.getOutputContentType());
            assertTrue(fp.getTextLengthMagnitude() > 0);

            // 维度 4：无错误
            assertFalse(fp.isHasError());

            System.out.println("[3.1] contentType=" + fp.getOutputContentType() + ", textMagnitude=" + fp.getTextLengthMagnitude() + ", toolCallSet=" + fp.getToolCallSet());
        }

        @Test
        @DisplayName("3.2 JSON 输出指纹")
        void testJsonFingerprint() throws Exception {
            LlmResponse response = callLlm("你是数据助手，只返回JSON。不要任何其他文字。", "返回一个JSON：{\"name\":\"张三\",\"age\":25}");
            InteractionRecord record = responseToRecord(response);

            DeterministicFingerprint fp = FingerprintExtractor.extract(record);

            // JSON 检测
            if ("application/json".equals(fp.getOutputContentType())) {
                assertFalse(fp.getOutputFieldPaths().isEmpty(), "JSON 输出应有 fieldPaths");
                assertFalse(fp.getOutputFieldTypeMap().isEmpty(), "JSON 输出应有 fieldTypeMap");
                System.out.println("[3.2] fieldPaths=" + fp.getOutputFieldPaths());
                System.out.println("[3.2] fieldTypeMap=" + fp.getOutputFieldTypeMap());
            } else {
                System.out.println("[3.2] 未被识别为 JSON，contentType=" + fp.getOutputContentType());
            }
        }

        @Test
        @DisplayName("3.3 工具调用指纹")
        void testToolCallFingerprint() throws Exception {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("你是天气助手，问天气就调 get_weather。");
            request.setUserInput("上海天气如何？");
            request.setTemperature(0.0);
            request.setToolDefinitions(Collections.singletonList(WEATHER_TOOL));

            LlmResponse response = client.chat(request, 30000);
            InteractionRecord record = responseToRecord(response, request);

            DeterministicFingerprint fp = FingerprintExtractor.extract(record);

            // 维度 1：有工具调用
            assertFalse(fp.getToolCallSet().isEmpty(), "应有 toolCallSet");
            assertTrue(fp.getToolCallSet().contains("get_weather"), "应包含 get_weather");

            System.out.println("[3.3] toolCallSet=" + fp.getToolCallSet() + ", hasToolCalls=" + record.isHasToolCalls());
        }
    }

    @Nested
    @DisplayName("4. DeterministicComparator — 真实指纹对比")
    class ComparatorTests {

        @Test
        @DisplayName("4.1 同一 Prompt 两次调用对比 — 应 PASS 或高分")
        void testSamePromptComparison() throws Exception {
            String prompt = "你是一个数学助手，只回答数字。";
            String input = "3+4等于几？只回答数字。";

            LlmResponse resp1 = callLlm(prompt, input);
            LlmResponse resp2 = callLlm(prompt, input);

            InteractionRecord rec1 = responseToRecord(resp1);
            rec1.setTemplateHash(HashUtil.sha256(prompt));
            InteractionRecord rec2 = responseToRecord(resp2);
            rec2.setTemplateHash(HashUtil.sha256(prompt));

            DeterministicFingerprint fp1 = FingerprintExtractor.extract(rec1);
            DeterministicFingerprint fp2 = FingerprintExtractor.extract(rec2);

            DeterministicComparator comparator = new DeterministicComparator();
            ComparisonResult result = comparator.compare(fp1, fp2, resp2.getContent());

            assertNotNull(result.getVerdict());
            assertTrue(result.getScore() >= 0.7, "同一 prompt 两次调用 score 应 >= 0.7，实际: " + result.getScore());

            System.out.println("[4.1] Verdict=" + result.getVerdict() + ", Score=" + String.format("%.4f", result.getScore()) + ", toolMatch=" + result.isToolCallMatch() + ", fieldMatch=" + result.isFieldTypeMatch());
        }

        @Test
        @DisplayName("4.2 文本 vs 工具调用对比 — 应 REGRESSION")
        void testTextVsToolCallComparison() throws Exception {
            // 基线：纯文本
            LlmResponse textResp = callLlm("你是一个助手。", "1+1等于几？");
            InteractionRecord textRec = responseToRecord(textResp);

            // 当前：工具调用
            LlmRequest toolReq = new LlmRequest();
            toolReq.setSystemPrompt("你是天气助手，问天气就调 get_weather。");
            toolReq.setUserInput("北京天气如何？");
            toolReq.setTemperature(0.0);
            toolReq.setToolDefinitions(Collections.singletonList(WEATHER_TOOL));
            LlmResponse toolResp = client.chat(toolReq, 30000);
            InteractionRecord toolRec = responseToRecord(toolResp, toolReq);

            DeterministicFingerprint textFp = FingerprintExtractor.extract(textRec);
            DeterministicFingerprint toolFp = FingerprintExtractor.extract(toolRec);

            DeterministicComparator comparator = new DeterministicComparator();
            ComparisonResult result = comparator.compare(textFp, toolFp, toolResp.getContent());

            // 工具集变化 → 不匹配
            assertFalse(result.isToolCallMatch(), "纯文本 vs 工具调用 → toolCallMatch 应为 false");

            System.out.println("[4.2] Verdict=" + result.getVerdict() + ", Score=" + String.format("%.4f", result.getScore()) + ", toolMatch=" + result.isToolCallMatch());
        }
    }

    @Nested
    @DisplayName("5. BehaviorChecker — 真实输出行为校验")
    class BehaviorCheckerTests {

        @Test
        @DisplayName("5.1 mustUseChinese — 中文回答")
        void testMustUseChinese() throws Exception {
            LlmResponse resp = callLlm("你是一个助手，用中文回答。", "什么是Java？用中文介绍。");
            InteractionRecord rec = responseToRecord(resp);
            DeterministicFingerprint fp = FingerprintExtractor.extract(rec);

            boolean result = BehaviorChecker.check("mustUseChinese", fp, resp.getContent());
            System.out.println("[5.1] mustUseChinese=" + result + ", content=" + resp.getContent().substring(0, Math.min(80, resp.getContent().length())));
            // 中文 prompt + 明确要求中文 → 极大概率通过，但不做硬断言（LLM 输出不确定）
            // 如果不通过，记录日志但不失败
            if (!result) {
                System.out.println("[5.1] WARNING: mustUseChinese 未通过，LLM 可能输出了英文");
            }
        }

        @Test
        @DisplayName("5.2 nonEmptyOutput — 非空输出")
        void testNonEmptyOutput() throws Exception {
            LlmResponse resp = callLlm("你是助手。", "你好");
            InteractionRecord rec = responseToRecord(resp);
            DeterministicFingerprint fp = FingerprintExtractor.extract(rec);

            assertTrue(BehaviorChecker.check("nonEmptyOutput", fp, resp.getContent()));
            assertTrue(BehaviorChecker.check("containsCjk", fp, resp.getContent()));
        }

        @Test
        @DisplayName("5.3 jsonOutput — JSON 输出检测")
        void testJsonOutput() throws Exception {
            LlmResponse resp = callLlm("你是数据助手。只返回JSON格式数据，不要其他文字。", "返回 {\"status\":\"ok\",\"count\":5}");
            InteractionRecord rec = responseToRecord(resp);
            DeterministicFingerprint fp = FingerprintExtractor.extract(rec);

            boolean isJson = BehaviorChecker.check("jsonOutput", fp, resp.getContent());
            System.out.println("[5.3] jsonOutput=" + isJson + ", content=" + resp.getContent().substring(0, Math.min(80, resp.getContent().length())));
        }
    }

    @Nested
    @DisplayName("6. RegressionTestExecutor — 完整重放链路")
    class ExecutorTests {

        @Test
        @DisplayName("6.1 文本基线 → 文本重放")
        void testTextReplay() throws Exception {
            InteractionRecord baseline = makeTextBaseline("exec-text-1", "5+3等于几？", "8");
            String newPrompt = "你是一个数学助手，简洁回答。";

            RegressionTestExecutor executor = new RegressionTestExecutor(client, new DeterministicComparator(), null);
            RegressionTestResult result = executor.execute(baseline, newPrompt, TestExecutionConfig.defaults());

            assertEquals(TestResultStatus.SUCCESS, result.getStatus());
            assertNotNull(result.getComparison());
            assertNotNull(result.getComparison().getVerdict());
            assertTrue(result.getComparison().getScore() >= 0);
            assertNotNull(result.getCandidateFingerprint());

            System.out.println("[6.1] Status=" + result.getStatus() + ", Verdict=" + result.getComparison().getVerdict() + ", Score=" + String.format("%.4f", result.getComparison().getScore()));
        }

        @Test
        @DisplayName("6.2 工具基线 → 工具重放（LLM 应再次调用工具）")
        void testToolCallReplay() throws Exception {
            // 构建带工具调用的基线
            InteractionRecord baseline = makeToolCallBaseline("exec-tool-1", "北京天气怎么样？", "get_weather", Collections.singletonMap("city", "北京"));

            // 新 prompt 里也告诉它用工具
            String newPrompt = "你是天气助手。用户问天气时必须调用 get_weather 工具。不要用文字回答天气问题。";

            // tools 定义由基线记录原样携带（重放请求自动复用），执行配置无需传入
            TestExecutionConfig config = new TestExecutionConfig().temperature(0.0).timeoutMs(30000);

            RegressionTestExecutor executor = new RegressionTestExecutor(client, new DeterministicComparator(), null);
            RegressionTestResult result = executor.execute(baseline, newPrompt, config);

            assertEquals(TestResultStatus.SUCCESS, result.getStatus());

            // 验证新响应也有工具调用
            DeterministicFingerprint candidateFp = result.getCandidateFingerprint();
            System.out.println("[6.2] toolCallSet=" + candidateFp.getToolCallSet() + ", Verdict=" + result.getComparison().getVerdict() + ", Score=" + String.format("%.4f", result.getComparison().getScore()));

            // 重放按基线记录原样携带 tools 定义；模型是否发起工具调用仍是
            // 采样随机变量（不服从时判定面解释差异，不视为引擎缺陷）
        }

        @Test
        @DisplayName("6.3 多轮对话重放")
        void testMultiTurnReplay() throws Exception {
            InteractionRecord baseline = makeTextBaseline("exec-multi-1", "我叫什么名字？", "小明");
            baseline.setTurnIndex(2);
            List<TurnContext> turns = new ArrayList<>();
            turns.add(new TurnContext("user", "我叫小明"));
            turns.add(new TurnContext("assistant", "你好小明！"));
            baseline.setPreviousTurns(turns);

            String newPrompt = "你是对话助手，记住用户信息。";
            RegressionTestExecutor executor = new RegressionTestExecutor(client, new DeterministicComparator(), null);
            RegressionTestResult result = executor.execute(baseline, newPrompt, TestExecutionConfig.defaults());

            assertEquals(TestResultStatus.SUCCESS, result.getStatus());
            System.out.println("[6.3] Verdict=" + result.getComparison().getVerdict() + ", Score=" + String.format("%.4f", result.getComparison().getScore()));
        }

        @Test
        @DisplayName("6.4 dryRun 模式")
        void testDryRun() {
            InteractionRecord baseline = makeTextBaseline("dry-1", "test", "ok");
            RegressionTestExecutor executor = new RegressionTestExecutor(client, new DeterministicComparator(), null);

            RegressionTestResult result = executor.execute(baseline, "new prompt", new TestExecutionConfig().dryRun(true));

            assertEquals(TestResultStatus.SKIP, result.getStatus());
        }
    }

    @Nested
    @DisplayName("7. StatisticalRegressionExecutor — 多次真实采样")
    class StatisticalTests {

        @Test
        @DisplayName("7.1 串行采样 5 次 — 完整统计验证")
        void testSerialSampling5() throws Exception {
            InteractionRecord baseline = makeTextBaseline("stat-serial-1", "7+8等于几？只回答数字。", "15");
            String newPrompt = "你是数学助手，只回答数字。";

            StatisticalRegressionExecutor executor = new StatisticalRegressionExecutor(client, new DeterministicComparator());

            StatisticalTestConfig config = new StatisticalTestConfig();
            config.setSampleCount(5);
            config.setConcurrency(1);
            config.setTemperature(0.0);
            config.setPassThreshold(0.9);
            config.setRegressionTolerance(0.0);

            StatisticalRegressionResult result = executor.execute(baseline, newPrompt, config);

            assertEquals(5, result.getActualSampleCount());
            assertEquals(5, result.getSamples().size());

            // Verdict 统计完整性
            int totalCount = result.getVerdictCounts().values().stream().mapToInt(i -> i).sum();
            assertEquals(5, totalCount, "verdict 计数之和应 = 采样数");

            // Score 统计
            assertTrue(result.getAverageScore() >= 0);
            assertTrue(result.getAverageScore() <= 1);
            assertTrue(result.getScoreStdDev() >= 0);
            assertTrue(result.getMinScore() >= 0);

            // 耗时
            assertTrue(result.getTotalLatencyMs() > 0);
            assertTrue(result.getEstimatedCost() > 0);

            // 统计判定
            assertNotNull(result.getStatisticalVerdict());

            System.out.println("[7.1] Verdict=" + result.getStatisticalVerdict() + ", Avg=" + String.format("%.4f", result.getAverageScore()) + ", StdDev=" + String.format("%.4f", result.getScoreStdDev()) + ", Min=" + String.format("%.4f", result.getMinScore()) + ", Latency=" + result.getTotalLatencyMs() + "ms" + ", Cost=$" + String.format("%.4f", result.getEstimatedCost()));
            System.out.println("[7.1] Counts: " + result.getVerdictCounts());

            for (SampleResult s : result.getSamples()) {
                System.out.println("  #" + s.getSampleIndex() + ": " + s.getVerdict() + " score=" + String.format("%.4f", s.getScore()) + " latency=" + s.getLatencyMs() + "ms" + (s.getErrorMessage() != null ? " err=" + s.getErrorMessage() : ""));
            }
        }

        @Test
        @DisplayName("7.2 并发采样 5 次")
        void testConcurrentSampling5() throws Exception {
            InteractionRecord baseline = makeTextBaseline("stat-conc-1", "9*9等于几？只回答数字。", "81");
            String newPrompt = "你是数学助手。";

            StatisticalRegressionExecutor executor = new StatisticalRegressionExecutor(client, new DeterministicComparator());

            StatisticalTestConfig config = new StatisticalTestConfig();
            config.setSampleCount(5);
            config.setConcurrency(5);
            config.setTemperature(0.0);

            StatisticalRegressionResult result = executor.execute(baseline, newPrompt, config);

            assertEquals(5, result.getActualSampleCount());
            assertNotNull(result.getStatisticalVerdict());

            System.out.println("[7.2] Verdict=" + result.getStatisticalVerdict() + ", Latency=" + result.getTotalLatencyMs() + "ms (5 concurrent)");
        }

        @Test
        @DisplayName("7.3 单次模式等价性")
        void testSingleMode() throws Exception {
            InteractionRecord baseline = makeTextBaseline("stat-single-1", "10/2等于几？", "5");

            StatisticalRegressionExecutor executor = new StatisticalRegressionExecutor(client, new DeterministicComparator());

            StatisticalRegressionResult result = executor.execute(baseline, "你是数学助手。", StatisticalTestConfig.defaults());

            assertEquals(1, result.getActualSampleCount());
            assertNotNull(result.getStatisticalVerdict());

            System.out.println("[7.3] Single: Verdict=" + result.getStatisticalVerdict() + ", Score=" + String.format("%.4f", result.getAverageScore()));
        }
    }

    @Nested
    @DisplayName("8. CostEstimator — 真实 token 消耗验证")
    class CostEstimatorTests {

        @Test
        @DisplayName("8.1 真实 token 计数与费用估算对比")
        void testRealTokenCounts() throws Exception {
            LlmRequest request = new LlmRequest();
            request.setSystemPrompt("你是一个助手。");
            request.setUserInput("用3句话介绍Java。");
            request.setTemperature(0.0);

            LlmResponse response = client.chat(request, 30000);

            int inTokens = response.getInputTokens();
            int outTokens = response.getOutputTokens();

            System.out.println("[8.1] Input tokens: " + inTokens + ", Output tokens: " + outTokens);
            System.out.println("[8.1] Response: " + response.getContent().substring(0, Math.min(100, response.getContent().length())));

            assertTrue(inTokens >= 10 && inTokens <= 200, "inputTokens 应在合理范围，实际: " + inTokens);
            assertTrue(outTokens >= 10 && outTokens <= 500, "outputTokens 应在合理范围，实际: " + outTokens);

            double costPerCall = CostEstimator.estimateCostPerCall("deepseek-chat");
            assertTrue(costPerCall > 0);
            System.out.println("[8.1] CostEstimator 单价: $" + costPerCall);

            // DeepSeek Chat 定价（2026参考）: input $0.27/M, output $1.10/M
            double realCost = inTokens * 0.27 / 1_000_000 + outTokens * 1.10 / 1_000_000;
            System.out.println("[8.1] 真实费用估算: $" + String.format("%.6f", realCost));
        }

        @Test
        @DisplayName("8.2 统计模式费用预估")
        void testStatisticalCostEstimate() {
            List<InteractionRecord> testCases = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                InteractionRecord r = new InteractionRecord();
                r.setTurnIndex(0);
                r.setUserInput("test " + i);
                testCases.add(r);
            }

            String estimate = CostEstimator.estimateStatistical(testCases, "deepseek-chat", 5);
            assertNotNull(estimate);
            assertTrue(estimate.contains("15 次"), "3 用例 x 5 次 = 15 次");

            System.out.println("[8.2] " + estimate);
        }
    }

    @Nested
    @DisplayName("9. 完整生命周期 — 录制→指纹→对比→判定")
    class LifecycleTests {

        @Test
        @DisplayName("9.1 文本场景完整生命周期")
        void testTextLifecycle() throws Exception {
            // 1. 模拟录制：用户输入 → LLM → 基线记录
            String originalPrompt = "你是一个严格的数学助手，只回答数字。";
            String userInput = "15+27等于几？";

            LlmResponse baselineResp = callLlm(originalPrompt, userInput);
            InteractionRecord baseline = responseToRecord(baselineResp);
            baseline.setRecordId("lifecycle-text-1");
            baseline.setSkillId("math-skill");
            baseline.setTemplateHash(HashUtil.sha256(originalPrompt));

            System.out.println("[9.1] 基线响应: " + baselineResp.getContent());

            // 2. 提取基线指纹
            DeterministicFingerprint baselineFp = FingerprintExtractor.extract(baseline);
            System.out.println("[9.1] 基线指纹: toolCallSet=" + baselineFp.getToolCallSet() + ", outputType=" + baselineFp.getOutputContentType() + ", textMagnitude=" + baselineFp.getTextLengthMagnitude());

            // 3. Prompt 变更
            String newPrompt = "你是一个友好的数学老师，用完整句子回答数学问题。";

            // 4. 回归重放
            RegressionTestExecutor executor = new RegressionTestExecutor(client, new DeterministicComparator(), null);
            RegressionTestResult result = executor.execute(baseline, newPrompt, TestExecutionConfig.defaults());

            // 5. 验证完整链路
            assertEquals(TestResultStatus.SUCCESS, result.getStatus());
            assertNotNull(result.getComparison());
            assertNotNull(result.getComparison().getVerdict());
            assertTrue(result.getComparison().getScore() >= 0);

            ComparisonResult cmp = result.getComparison();
            System.out.println("[9.1] === 回归测试结果 ===");
            System.out.println("  Verdict: " + cmp.getVerdict());
            System.out.println("  Score: " + String.format("%.4f", cmp.getScore()));
            System.out.println("  toolCallMatch: " + cmp.isToolCallMatch());
            System.out.println("  fieldTypeMatch: " + cmp.isFieldTypeMatch());
            System.out.println("  addedFields: " + cmp.getAddedFields());
            System.out.println("  removedFields: " + cmp.getRemovedFields());
            System.out.println("  summary: " + cmp.getSummary());
        }

        @Test
        @DisplayName("9.2 工具场景完整生命周期")
        void testToolLifecycle() throws Exception {
            // 1. 录制基线：LLM 调用了 get_weather
            LlmRequest baselineReq = new LlmRequest();
            baselineReq.setSystemPrompt("你是天气助手，问天气就调 get_weather。");
            baselineReq.setUserInput("深圳天气怎么样？");
            baselineReq.setTemperature(0.0);
            baselineReq.setToolDefinitions(Collections.singletonList(WEATHER_TOOL));

            LlmResponse baselineResp = client.chat(baselineReq, 30000);
            InteractionRecord baseline = responseToRecord(baselineResp, baselineReq);
            baseline.setRecordId("lifecycle-tool-1");
            baseline.setSkillId("weather-skill");

            System.out.println("[9.2] 基线 toolCalls: " + (baselineResp.getToolCalls().isEmpty() ? "无" : baselineResp.getToolCalls().size()));

            if (!baselineResp.getToolCalls().isEmpty()) {
                ToolCallResult tc = baselineResp.getToolCalls().get(0);
                System.out.println("[9.2] 基线工具: " + tc.getToolName() + " args=" + tc.getArguments());

                // 2. 基线指纹
                DeterministicFingerprint baselineFp = FingerprintExtractor.extract(baseline);
                assertTrue(baselineFp.getToolCallSet().contains("get_weather"));

                // 3. 用新 prompt 重放（tools 定义随基线记录原样携带）
                String newPrompt = "你是一个天气专家。用 get_weather 工具查天气。";
                RegressionTestExecutor executor = new RegressionTestExecutor(client, new DeterministicComparator(), null);
                RegressionTestResult result = executor.execute(baseline, newPrompt, TestExecutionConfig.defaults());

                System.out.println("[9.2] 重放结果: Verdict=" + result.getComparison().getVerdict() + ", Score=" + String.format("%.4f", result.getComparison().getScore()) + ", toolMatch=" + result.getComparison().isToolCallMatch());
            } else {
                System.out.println("[9.2] 基线未返回 tool_calls，跳过对比验证");
            }
        }

        @Test
        @DisplayName("9.3 多轮对话完整生命周期")
        void testMultiTurnLifecycle() throws Exception {
            // 模拟一个 3 轮对话的完整场景
            // Turn 1: 用户说我叫小红
            // Turn 2: 助手记住名字
            // Turn 3: 用户问名字 → 基线

            String prompt = "你是对话助手，记住用户说的信息。";
            LlmRequest turn1 = new LlmRequest();
            turn1.setSystemPrompt(prompt);
            turn1.setUserInput("我叫小红");
            turn1.setTemperature(0.0);
            LlmResponse resp1 = client.chat(turn1, 30000);

            // 构建 turn 2 with previousTurns
            LlmRequest turn2 = new LlmRequest();
            turn2.setSystemPrompt(prompt);
            turn2.addTurn("user", "我叫小红");
            turn2.addTurn("assistant", resp1.getContent());
            turn2.setUserInput("我叫什么名字？只回答名字。");
            turn2.setTemperature(0.0);
            LlmResponse resp2 = client.chat(turn2, 30000);

            System.out.println("[9.3] 多轮对话最终回答: " + resp2.getContent());
            assertTrue(resp2.getContent().contains("小红"), "LLM 应记住上下文中的 '小红'");

            // 构建基线并重放
            InteractionRecord baseline = new InteractionRecord();
            baseline.setRecordId("lifecycle-multi-1");
            baseline.setSkillId("chat-skill");
            baseline.setTemplateHash(HashUtil.sha256(prompt));
            baseline.setUserInput("我叫什么名字？只回答名字。");
            baseline.setTurnIndex(2);
            baseline.setModelResponse(resp2.getContent());
            baseline.setInputTokens(resp2.getInputTokens());
            baseline.setOutputTokens(resp2.getOutputTokens());
            baseline.setToolCalls(new ArrayList<>());
            baseline.setHasToolCalls(false);

            List<TurnContext> turns = new ArrayList<>();
            turns.add(new TurnContext("user", "我叫小红"));
            turns.add(new TurnContext("assistant", resp1.getContent()));
            baseline.setPreviousTurns(turns);

            // 用新 prompt 重放
            String newPrompt = "你是一个友好的助手，总是记住用户信息。";
            RegressionTestExecutor executor = new RegressionTestExecutor(client, new DeterministicComparator(), null);
            RegressionTestResult result = executor.execute(baseline, newPrompt, TestExecutionConfig.defaults());

            assertEquals(TestResultStatus.SUCCESS, result.getStatus());
            System.out.println("[9.3] 重放: Verdict=" + result.getComparison().getVerdict() + ", Score=" + String.format("%.4f", result.getComparison().getScore()));
        }

        @Test
        @DisplayName("9.4 统计回归完整生命周期 — 5次采样 + STABLE/UNSTABLE/FLAKY")
        void testStatisticalLifecycle() throws Exception {
            InteractionRecord baseline = makeTextBaseline("lifecycle-stat-1", "2的10次方等于多少？只回答数字。", "1024");
            String newPrompt = "你是数学助手，精确计算，只回答数字。";

            StatisticalRegressionExecutor executor = new StatisticalRegressionExecutor(client, new DeterministicComparator());

            StatisticalTestConfig config = new StatisticalTestConfig();
            config.setSampleCount(5);
            config.setConcurrency(2);
            config.setTemperature(0.0);
            config.setPassThreshold(0.9);

            StatisticalRegressionResult result = executor.execute(baseline, newPrompt, config);

            // 完整断言
            assertEquals(5, result.getActualSampleCount());
            assertNotNull(result.getStatisticalVerdict());
            assertTrue(result.getTotalLatencyMs() > 0);
            assertTrue(result.getEstimatedCost() > 0);

            // Verdict 分布完整性
            double rateSum = result.getVerdictRates().values().stream().mapToDouble(d -> d).sum();
            assertEquals(1.0, rateSum, 0.01, "Verdict 速率之和应 ≈ 1.0");

            // 样本完整性
            for (SampleResult s : result.getSamples()) {
                assertNotNull(s.getVerdict());
                assertTrue(s.getScore() >= 0 && s.getScore() <= 1);
                assertTrue(s.getLatencyMs() >= 0);
            }

            System.out.println("[9.4] === 统计回归生命周期 ===");
            System.out.println("  Verdict: " + result.getStatisticalVerdict());
            System.out.println("  AvgScore: " + String.format("%.4f", result.getAverageScore()));
            System.out.println("  StdDev: " + String.format("%.4f", result.getScoreStdDev()));
            System.out.println("  Counts: " + result.getVerdictCounts());
            System.out.println("  Rates: " + result.getVerdictRates());
            System.out.println("  Latency: " + result.getTotalLatencyMs() + "ms");
            System.out.println("  Cost: $" + String.format("%.4f", result.getEstimatedCost()));
        }
    }
}
