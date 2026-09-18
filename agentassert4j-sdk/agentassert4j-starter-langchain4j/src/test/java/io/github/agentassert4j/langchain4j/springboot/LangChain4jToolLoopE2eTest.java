package io.github.agentassert4j.langchain4j.springboot;

import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.service.AiServices;
import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.langchain4j.RecordingChatModel;
import io.github.agentassert4j.langchain4j.RecordingContext;
import io.github.agentassert4j.langchain4j.RecordingStreamingChatModel;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.recorder.InteractionRecorder;
import io.github.agentassert4j.recorder.RecorderConfig;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LangChain4j 真机 e2e（环境变量门控）：AiServices 内部工具回路全链
 * （录制→建档→链末判定→值溯源边）与低级手动回路的跨记录采集形状。
 * 全程纯程序化（无 Spring 容器）——兼作原生接入路径的实证。
 *
 * <p>环境变量：{@code AGENTASSERT4J_E2E_API_KEY} 必填；
 * {@code AGENTASSERT4J_E2E_BASE_URL} 默认 DeepSeek；
 * {@code AGENTASSERT4J_E2E_MODEL} 默认 {@code deepseek-v4-flash}。
 * AiServices 全链 ≈8 次真实调用（两次运行 × 两步 × 帧轮+结果轮）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
@Tag("e2e-real-llm")
@EnabledIfEnvironmentVariable(named = "AGENTASSERT4J_E2E_API_KEY", matches = ".+")
class LangChain4jToolLoopE2eTest {

    private static final String TASK_KEY = "lc4j-loop-demo";
    private static final String ORDER_INVOCATION = "orderStatus";
    private static final String LOGISTICS_INVOCATION = "logisticsCheck";
    private static final String REFUND_ID = "REF-8841";
    private static final Pattern REFUND_ID_PATTERN = Pattern.compile("REF-[A-Za-z0-9]+");

    private static final String ORDER_SYSTEM = "你是电商售后助手。涉及订单必须先调用查询工具核实事实，禁止编造，并在最终答复中原样给出退款单号。";
    private static final String LOGISTICS_SYSTEM = "你是物流查询助手。查询物流必须调用物流工具，退款单号以用户提供的为准，工具参数必须使用该退款单号。";

    interface OrderAssistant {

        @dev.langchain4j.service.SystemMessage(ORDER_SYSTEM)
        String chat(String userMessage);
    }

    interface LogisticsAssistant {

        @dev.langchain4j.service.SystemMessage(LOGISTICS_SYSTEM)
        String chat(String userMessage);
    }

    static class OrderTools {

        @Tool("查询订单状态，返回内容包含退款单号")
        public String queryOrderStatus(String orderId) {
            // JSON 返回：值溯源以 JSON 叶子为值源，退款单号必须作为叶子出现
            return "{\"orderId\":\"" + orderId + "\",\"status\":\"refund_applied\",\"refundId\":\"" + REFUND_ID + "\"}";
        }
    }

    static class LogisticsTools {

        @Tool("按退款单号查询物流轨迹")
        public String checkLogistics(String refundId) {
            return "退款单 " + refundId + " 的物流轨迹已生成";
        }
    }

    @Test
    @DisplayName("AiServices 真实工具 loop：录制面→建档→链末判定→值溯源边 全链")
    void aiServicesLoop_fullChain() throws Exception {
        Path db = Files.createTempFile("lc4j-loop-e2e-", ".db");
        db.toFile().deleteOnExit();
        StorageRepository repository = new SqliteStorageRepository(db.toString());
        repository.initialize();
        InteractionRecorder recorder = new InteractionRecorder(repository, RecorderConfig.builder().defaultInvocationId("lc4j-e2e").build());
        recorder.start();
        ChatModel recorded = RecordingChatModel.wrap(realModel(), recorder);

        String stamp = String.valueOf(System.currentTimeMillis());
        String sessionA = "lc4j-e2e-" + stamp + "-a";
        List<InteractionRecord> runA = executeTwoStepLoop(recorded, sessionA);
        awaitRecordCount(repository, sessionA, 4);
        runA = ordered(repository.findBySessionId(sessionA));
        System.out.println("[lc4j-e2e] run A records: " + runA.size());
        assertRecordingFace(runA);

        // 链末建档纪律：每轮成记录的形状下，一个调用点一轮会话里有多条异构记录
        // （发起帧轮/结果轮），基线锚定与判定裁剪必须同取链末记录——先到先得的
        // 建档语义下若锚到帧轮，判定的链末配对就是跨形状错配
        Map<String, InteractionRecord> chainFinalA = new LinkedHashMap<>();
        for (InteractionRecord record : runA) {
            chainFinalA.put(record.getInvocationKey(), record);
        }
        BaselineManager manager = new BaselineManager(repository);
        for (InteractionRecord record : chainFinalA.values()) {
            manager.autoEstablishBaseline(record, "agent:e2e-lc4j", null, "lc4j-tool-loop-e2e");
        }

        String sessionB = "lc4j-e2e-" + stamp + "-b";
        executeTwoStepLoop(recorded, sessionB);
        awaitRecordCount(repository, sessionB, 4);
        List<InteractionRecord> runB = ordered(repository.findBySessionId(sessionB));
        System.out.println("[lc4j-e2e] run B records: " + runB.size());

        TaskChain chainB = TaskChainView.resolveSession(sessionB, runB).stream().filter(c -> TASK_KEY.equals(c.getRequestText())).findFirst().orElseThrow(() -> new AssertionError("声明 taskKey 的任务链必须在场"));
        TaskChain judged = TaskAligner.trimToLatestPerInvocation(chainB);
        Map<String, InvocationProfile> profiles = new LinkedHashMap<>();
        for (InteractionRecord record : judged.getRecords()) {
            profiles.putIfAbsent(record.getInvocationKey(), repository.findInvocationByKey(record.getInvocationKey()));
        }
        TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(BaselineSides.fromProfiles(judged.getRecords(), profiles::get), chainB, new DeterministicComparator(ComparatorConfig.defaults()), null);
        System.out.println("[lc4j-e2e] chain-final verdict: " + alignment.getVerdict() + ", steps=" + alignment.getSteps().size());
        for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
            ComparisonResult c = step.getComparison();
            System.out.println("[lc4j-e2e] step " + step.getInvocationKey() + " verdict=" + step.getVerdict() + " tools=" + c.isToolCallMatch() + " params=" + c.isParamTypeMatch() + " struct=" + c.isStructureMatch() + " kw=" + c.isKeywordMatch() + " score=" + c.getScore() + " summary=" + c.getSummary());
        }
        assertEquals(Verdict.PASS, alignment.getVerdict(), "同一工具 loop 的第二次真实执行必须判定 PASS");
        assertEquals(2, alignment.getSteps().size(), "双步链：每调用点一步（帧轮+结果轮折叠为该调用点的最新记录）");

        InMemoryDependencyGraph graph = new InMemoryDependencyGraph();
        new ParameterValueTracer(graph).traceDependency(runB);
        GraphEdge edge = graph.getAllEdges().stream().filter(e -> e.getSource().contains(":" + ORDER_INVOCATION + ":") && e.getTarget().contains(":" + LOGISTICS_INVOCATION + ":")).findFirst().orElse(null);
        assertNotNull(edge, "退款单号从订单步流进物流步提示词与工具参数——LC4j 录制数据必须出值溯源边");
        assertEquals(Confidence.HIGH, edge.getConfidence());
        assertEquals(REFUND_ID, edge.getEvidenceValue(), "边的证据值 = 跨步流动的退款单号");
        System.out.println("[lc4j-e2e] provenance edge: " + edge.getSource() + " -> " + edge.getTarget() + " evidence=" + edge.getEvidenceValue());

        recorder.stop();
    }

    @Test
    @DisplayName("低级手动回路：发起帧与结果帧跨记录采集")
    void manualLoop_frameAndResultAcrossRecords() throws Exception {
        Path db = Files.createTempFile("lc4j-manual-e2e-", ".db");
        db.toFile().deleteOnExit();
        StorageRepository repository = new SqliteStorageRepository(db.toString());
        repository.initialize();
        InteractionRecorder recorder = new InteractionRecorder(repository, RecorderConfig.builder().defaultInvocationId("lc4j-e2e").build());
        recorder.start();
        ChatModel recorded = RecordingChatModel.wrap(realModel(), recorder);
        String session = "lc4j-e2e-manual-" + System.currentTimeMillis();
        String invocation = "orderStatusManual";

        ToolSpecification specification = ToolSpecification.builder().name("queryOrderStatus").description("查询订单状态，返回内容包含退款单号").parameters(JsonObjectSchema.builder().addStringProperty("arg0").required("arg0").build()).build();

        ChatResponse frame;
        try (RecordingContext ctx = RecordingContext.start(session).withInvocationId(invocation).withMetadata("taskKey", TASK_KEY)) {
            ChatRequest round1 = ChatRequest.builder().messages(SystemMessage.from(ORDER_SYSTEM), UserMessage.from("请查询订单 SO-77 的物流状态，并在最终答复中原样给出退款单号。")).toolSpecifications(specification).build();
            frame = recorded.chat(round1);
        }
        assertNotNull(frame.aiMessage().toolExecutionRequests());
        assertTrue(!frame.aiMessage().toolExecutionRequests().isEmpty(), "手动回路第一轮必须返回工具发起帧");
        ToolExecutionRequest request = frame.aiMessage().toolExecutionRequests().get(0);
        String orderId = firstToolArgument(request.arguments());
        String toolResult = "订单 " + orderId + " 已申请退款，退款单号 " + REFUND_ID;

        try (RecordingContext ctx = RecordingContext.start(session).withInvocationId(invocation).withMetadata("taskKey", TASK_KEY)) {
            ChatRequest round2 = ChatRequest.builder().messages(SystemMessage.from(ORDER_SYSTEM), UserMessage.from("请查询订单 SO-77 的物流状态，并在最终答复中原样给出退款单号。"), frame.aiMessage(), ToolExecutionResultMessage.from(request, toolResult)).toolSpecifications(specification).build();
            ChatResponse finalResponse = recorded.chat(round2);
            assertTrue(finalResponse.aiMessage().text() != null && finalResponse.aiMessage().text().contains(REFUND_ID), "第二轮必须给出含退款单号的终答: " + finalResponse.aiMessage().text());
        }

        awaitRecordCount(repository, session, 2);
        List<InteractionRecord> records = ordered(repository.findBySessionId(session));
        System.out.println("[lc4j-e2e-manual] records: " + records.size());

        InteractionRecord r1 = records.get(0);
        assertTrue(r1.isHasToolCalls(), "第一轮记录必须带发起帧");
        assertEquals("queryOrderStatus", r1.getToolCalls().get(0).getToolName());
        assertTrue(r1.getToolCalls().get(0).getArguments() != null && !r1.getToolCalls().get(0).getArguments().isEmpty(), "发起帧参数必须解析成映射");
        assertTrue(r1.getToolCalls().get(0).getResult() == null, "发起帧记录不带结果——结果在下一轮请求里");

        InteractionRecord r2 = records.get(1);
        assertTrue(!r2.isHasToolCalls(), "终答轮不再发起工具调用");
        assertTrue(r2.getPreviousTurns() != null && r2.getPreviousTurns().stream().anyMatch(t -> "tool".equals(t.getRole()) && t.getContent() != null && t.getContent().contains(REFUND_ID)), "第二轮记录的 previousTurns 必须携带工具结果帧原文: " + r2.getPreviousTurns());
        System.out.println("[lc4j-e2e-manual] frame round captured; result turn carries tool payload");

        recorder.stop();
    }

    @Test
    @DisplayName("流式模型真机：分片透传、聚合录制、首 token 时延在记录")
    void streamingRealModel_recordsAggregated() throws Exception {
        Path db = Files.createTempFile("lc4j-stream-e2e-", ".db");
        db.toFile().deleteOnExit();
        StorageRepository repository = new SqliteStorageRepository(db.toString());
        repository.initialize();
        InteractionRecorder recorder = new InteractionRecorder(repository, RecorderConfig.builder().defaultInvocationId("lc4j-e2e").build());
        recorder.start();
        OpenAiStreamingChatModel streaming = OpenAiStreamingChatModel.builder().baseUrl(env("AGENTASSERT4J_E2E_BASE_URL", "https://api.deepseek.com/v1")).apiKey(env("AGENTASSERT4J_E2E_API_KEY", "")).modelName(env("AGENTASSERT4J_E2E_MODEL", "deepseek-v4-flash")).temperature(0.1).build();
        RecordingStreamingChatModel wrapper = RecordingStreamingChatModel.wrapStreaming(streaming, recorder);
        String session = "lc4j-e2e-stream-" + System.currentTimeMillis();
        StringBuilder partials = new StringBuilder();
        try (RecordingContext ctx = RecordingContext.start(session).withInvocationId("streamChat").withMetadata("taskKey", TASK_KEY)) {
            wrapper.chat(ChatRequest.builder().messages(UserMessage.from("用一句话说明订单 SO-77 已发货")).build(), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String token) {
                    partials.append(token);
                }

                @Override
                public void onCompleteResponse(ChatResponse response) {
                }

                @Override
                public void onError(Throwable error) {
                    throw new AssertionError("stream failed", error);
                }
            });
        }
        // 流式调用异步返回：先等聚合记录落库（完成信号已到），再验分片透传
        awaitRecordCount(repository, session, 1);
        assertTrue(partials.length() > 0, "分片必须透传给用户 handler: " + partials);
        InteractionRecord record = ordered(repository.findBySessionId(session)).get(0);
        assertNotNull(record.getModelResponse(), "聚合完成响应必须落记录");
        assertTrue(record.getTtftMs() == null || record.getTtftMs() >= 0, "首 token 时延应为非负或缺失");
        System.out.println("[lc4j-e2e-stream] record ttft=" + record.getTtftMs() + " response=" + record.getModelResponse());
        recorder.stop();
    }

    private static ChatModel realModel() {
        return OpenAiChatModel.builder().baseUrl(env("AGENTASSERT4J_E2E_BASE_URL", "https://api.deepseek.com/v1")).apiKey(env("AGENTASSERT4J_E2E_API_KEY", "")).modelName(env("AGENTASSERT4J_E2E_MODEL", "deepseek-v4-flash")).temperature(0.1).build();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value != null && !value.isEmpty() ? value : fallback;
    }

    /**
     * 两步 agent loop：订单状态步（工具结果携带退款单号，模型在终答中原样给出）→
     * 物流步（退款单号进提示词，模型以此作工具参数）。每步经 AiServices 内部回路
     * 产生两条记录（发起帧轮 + 结果轮），两次运行同一组常量提示词。
     */
    private List<InteractionRecord> executeTwoStepLoop(ChatModel recorded, String sessionId) {
        OrderAssistant order = AiServices.builder(OrderAssistant.class).chatModel(recorded).tools(new OrderTools()).build();
        LogisticsAssistant logistics = AiServices.builder(LogisticsAssistant.class).chatModel(recorded).tools(new LogisticsTools()).build();

        String refundId;
        try (RecordingContext ctx = RecordingContext.start(sessionId).withInvocationId(ORDER_INVOCATION).withMetadata("taskKey", TASK_KEY)) {
            String answer = order.chat("请查询订单 SO-77 的物流状态，并在最终答复中原样给出退款单号。");
            refundId = extractRefundId(answer);
            assertNotNull(refundId, "第一步终答必须包含工具返回的退款单号: " + answer);
        }
        try (RecordingContext ctx = RecordingContext.start(sessionId).withInvocationId(LOGISTICS_INVOCATION).withMetadata("taskKey", TASK_KEY)) {
            String answer = logistics.chat("用退款单号 " + refundId + " 查询物流轨迹，工具参数必须用这个退款单号。");
            assertNotNull(answer);
        }
        return new ArrayList<>();
    }

    private static String extractRefundId(String answer) {
        Matcher m = REFUND_ID_PATTERN.matcher(answer == null ? "" : answer);
        return m.find() ? m.group() : null;
    }

    /**
     * 录制面断言：按调用点分组——每组至少帧轮+结果轮两条（模型多走一轮工具
     * 不构成失败，轮数漂移归判定链管），首条=发起帧（带 toolCalls 无结果），
     * 末条=结果轮（带最终回复）。
     */
    private static void assertRecordingFace(List<InteractionRecord> records) {
        List<InteractionRecord> order = recordsOf(records, ORDER_INVOCATION);
        List<InteractionRecord> logistics = recordsOf(records, LOGISTICS_INVOCATION);
        assertEquals(records.size(), order.size() + logistics.size(), "全部记录归属两个调用点");
        assertStepShape(order, ORDER_INVOCATION);
        assertStepShape(logistics, LOGISTICS_INVOCATION);

        InteractionRecord orderResult = order.get(order.size() - 1);
        assertTrue(orderResult.getPreviousTurns() != null && orderResult.getPreviousTurns().stream().anyMatch(t -> "tool".equals(t.getRole()) && t.getContent() != null && t.getContent().contains(REFUND_ID)), "订单步结果轮的 previousTurns 必须携带含退款单号的工具结果: " + orderResult.getPreviousTurns());

        assertTrue(logistics.get(0).getUserInput() != null && logistics.get(0).getUserInput().contains(REFUND_ID), "物流步提示词携带退款单号（值溯源的上游注入点）");
        boolean refundUsedAsArg = logistics.stream().flatMap(r -> r.getToolCalls() == null ? Stream.<ToolCall>empty() : r.getToolCalls().stream()).anyMatch(c -> c.getArguments() != null && c.getArguments().containsValue(REFUND_ID));
        assertTrue(refundUsedAsArg, "物流步工具参数必须使用退款单号（值溯源的下游消费点）");
    }

    private static void assertStepShape(List<InteractionRecord> step, String invocation) {
        assertTrue(step.size() >= 2, "每步至少帧轮+结果轮两条记录: " + invocation + " 实际 " + step.size());
        for (InteractionRecord record : step) {
            assertTrue(record.getInvocationKey().startsWith("invocation:"), "声明调用点标签必须落进调用点键: " + record.getInvocationKey());
            assertTrue(record.getMetadata() != null && record.getMetadata().contains(TASK_KEY), "声明 taskKey 必须进 metadata");
        }
        InteractionRecord frame = step.get(0);
        assertTrue(frame.isHasToolCalls(), invocation + " 首条必须带工具发起帧");
        for (ToolCall call : frame.getToolCalls()) {
            assertNotNull(call.getToolName(), "工具名必须在场");
            assertTrue(call.getArguments() != null && !call.getArguments().isEmpty(), "发起帧参数必须解析成映射（值溯源的下游数据源）");
            assertTrue(call.getResult() == null, "帧轮记录不带结果——结果出现在下一轮 previousTurns");
        }
        assertNotNull(step.get(step.size() - 1).getModelResponse(), "末条必须带最终聚合回复");
    }

    private static List<InteractionRecord> recordsOf(List<InteractionRecord> records, String invocation) {
        List<InteractionRecord> out = new ArrayList<>();
        for (InteractionRecord record : records) {
            if (record.getInvocationKey().contains(":" + invocation + ":")) {
                out.add(record);
            }
        }
        return out;
    }

    /**
     * 发起帧的参数原文取首个参数值（单参数工具；编译期无参数名时框架用 arg0 等占位名）。
     */
    private static String firstToolArgument(String argumentsJson) {
        Object parsed = RecursiveJsonParser.parse(argumentsJson);
        if (parsed instanceof Map && !((Map<?, ?>) parsed).isEmpty()) {
            return String.valueOf(((Map<?, ?>) parsed).values().iterator().next());
        }
        return "SO-77";
    }

    private static List<InteractionRecord> ordered(List<InteractionRecord> records) {
        List<InteractionRecord> copy = new ArrayList<>(records);
        copy.sort(Comparator.comparingLong(InteractionRecord::getTimestamp).thenComparing(InteractionRecord::getSeq));
        return copy;
    }

    private static void awaitRecordCount(StorageRepository repository, String sessionId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            if (repository.findBySessionId(sessionId).size() >= expected) {
                return;
            }
            Thread.sleep(200);
        }
    }
}
