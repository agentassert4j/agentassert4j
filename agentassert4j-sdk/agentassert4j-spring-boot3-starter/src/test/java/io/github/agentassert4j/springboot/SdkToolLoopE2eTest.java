package io.github.agentassert4j.springboot;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.BaselineSides;
import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.algorithm.ParameterValueTracer;
import io.github.agentassert4j.algorithm.TaskAligner;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.GraphEdge;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.recorder.InteractionRecorder;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.springai1.RecordingChatModel;
import io.github.agentassert4j.springai1.RecordingContext;
import io.github.agentassert4j.spi.StorageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SDK 录制面端到端验证 —— starter 在真实 Spring AI 工具调用 loop 上的全链路测试
 * （真实调用，环境变量门控，缺省跳过）。
 *
 * <p>链路：Boot 自动装配把容器内 ChatModel 包上 {@link RecordingChatModel} →
 * ChatClient 两步工具 loop（订单状态 → 退款单号查物流，值经工具参数跨步流动）→
 * 旁路录制落库（工具调用按名称/参数/结果捕获进同一条记录）→ 建档 → 第二次真实
 * 执行 → 链末判定 PASS → 值溯源图在录制数据上产出跨步 HIGH 边（证据=流动的退款单号）。</p>
 *
 * <p>环境变量：{@code AGENTASSERT4J_E2E_API_KEY} 必需；{@code AGENTASSERT4J_E2E_BASE_URL}
 * 默认 DeepSeek；{@code AGENTASSERT4J_E2E_MODEL} 默认 {@code deepseek-v4-flash}。
 * 全程 4 次真实调用（两次运行 × 两步）。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
@Tag("e2e-real-llm")
@EnabledIfEnvironmentVariable(named = "AGENTASSERT4J_E2E_API_KEY", matches = ".+")
class SdkToolLoopE2eTest {

    private static final String TASK_KEY = "sdk-loop-demo";
    private static final String ORDER_INVOCATION = "orderStatus";
    private static final String LOGISTICS_INVOCATION = "logisticsCheck";
    private static final String REFUND_ID = "REF-8841";
    private static final Pattern REFUND_ID_PATTERN = Pattern.compile("REF-[A-Za-z0-9]+");

    private static final String ORDER_SYSTEM = "你是电商售后助手。涉及订单必须先调用查询工具核实事实，禁止编造。";
    private static final String LOGISTICS_SYSTEM = "你是物流查询助手。查询物流必须调用物流工具，退款单号以用户提供的为准。";

    @Test
    @DisplayName("starter 录制真实工具 loop：录制面→建档→链末判定→值溯源边 全链")
    void starterRecordsRealToolLoop_fullChain() throws Exception {
        Path db = Files.createTempFile("sdk-loop-e2e-", ".db");
        db.toFile().deleteOnExit();

        SpringApplication app = new SpringApplication(TestApp.class);
        app.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app.setDefaultProperties(Map.of(
                "agentassert4j.storage.url", db.toString(),
                "agentassert4j.recorder.batch-size", "2",
                "agentassert4j.recorder.flush-interval-ms", "200"));
        try (ConfigurableApplicationContext ctx = app.run()) {
            ChatModel chatModel = ctx.getBean(ChatModel.class);
            assertTrue(chatModel instanceof RecordingChatModel, "自动装配必须把容器内 ChatModel 包上录制装饰器");
            StorageRepository repository = ctx.getBean(StorageRepository.class);
            InteractionRecorder recorder = ctx.getBean(InteractionRecorder.class);
            org.springframework.ai.chat.client.ChatClient client =
                    org.springframework.ai.chat.client.ChatClient.builder(chatModel).build();

            String runStamp = String.valueOf(System.currentTimeMillis());
            List<InteractionRecord> runA = executeTwoStepLoop(client, "sdk-e2e-" + runStamp + "-a");
            awaitRecordCount(repository, "sdk-e2e-" + runStamp + "-a", 2);
            runA = ordered(repository.findBySessionId("sdk-e2e-" + runStamp + "-a"));
            System.out.println("[sdk-e2e] run A records: " + runA.size());
            assertRecordingFace(runA);

            BaselineManager manager = new BaselineManager(repository);
            for (InteractionRecord record : runA) {
                manager.autoEstablishBaseline(record, "agent:e2e-sdk", null, "sdk-tool-loop-e2e");
            }

            List<InteractionRecord> runB = executeTwoStepLoop(client, "sdk-e2e-" + runStamp + "-b");
            awaitRecordCount(repository, "sdk-e2e-" + runStamp + "-b", 2);
            runB = ordered(repository.findBySessionId("sdk-e2e-" + runStamp + "-b"));
            System.out.println("[sdk-e2e] run B records: " + runB.size());

            TaskChain chainB = TaskChainView.resolveSession("sdk-e2e-" + runStamp + "-b", runB).stream()
                    .filter(c -> TASK_KEY.equals(c.getRequestText()))
                    .findFirst().orElseThrow(() -> new AssertionError("声明 taskKey 的任务链必须在场"));
            Map<String, InvocationProfile> profiles = new LinkedHashMap<>();
            TaskChain judged = TaskAligner.trimToLatestPerInvocation(chainB);
            for (InteractionRecord record : judged.getRecords()) {
                profiles.putIfAbsent(record.getInvocationKey(), repository.findInvocationByKey(record.getInvocationKey()));
            }
            TaskAlignment alignment = TaskAligner.alignLatestPerInvocation(
                    BaselineSides.fromProfiles(judged.getRecords(), profiles::get),
                    chainB, new DeterministicComparator(ComparatorConfig.defaults()), null);
            System.out.println("[sdk-e2e] chain-final verdict: " + alignment.getVerdict()
                    + ", steps=" + alignment.getSteps().size());
            assertEquals(Verdict.PASS, alignment.getVerdict(), "同一工具 loop 的第二次真实执行必须判定 PASS");
            assertEquals(2, alignment.getSteps().size(), "双步链：每调用点一步");

            for (InteractionRecord record : runB) {
                for (ToolCall call : record.getToolCalls()) {
                    System.out.println("[sdk-e2e] tool " + call.getToolName() + " args=" + call.getArguments()
                            + " result=" + call.getResult());
                }
            }
            InMemoryDependencyGraph graph = new InMemoryDependencyGraph();
            new ParameterValueTracer(graph).traceDependency(runB);
            for (GraphEdge e : graph.getAllEdges()) {
                System.out.println("[sdk-e2e] edge " + e.getSource() + " -> " + e.getTarget()
                        + " " + e.getConfidence() + " evidence=" + e.getEvidenceValue());
            }
            GraphEdge edge = graph.getAllEdges().stream()
                    .filter(e -> e.getSource().contains(":" + ORDER_INVOCATION + ":")
                            && e.getTarget().contains(":" + LOGISTICS_INVOCATION + ":"))
                    .findFirst().orElse(null);
            assertNotNull(edge, "退款单号从订单步工具结果流进物流步工具参数——SDK 录制数据必须出值溯源边");
            assertEquals(Confidence.HIGH, edge.getConfidence());
            assertEquals(REFUND_ID, edge.getEvidenceValue(), "边的证据值 = 跨步流动的退款单号");
            System.out.println("[sdk-e2e] provenance edge: " + edge.getSource() + " -> " + edge.getTarget()
                    + " evidence=" + edge.getEvidenceValue());

            recorder.stop();
        }
    }

    /**
     * 两步 agent loop：订单状态步（工具结果携带退款单号，模型在答复中原样给出）→
     * 物流步（退款单号进提示词，模型以此作工具参数）。两次运行同一组常量提示词。
     */
    private List<InteractionRecord> executeTwoStepLoop(org.springframework.ai.chat.client.ChatClient client, String sessionId) {
        String refundId;
        try (RecordingContext ctx = RecordingContext.start(sessionId)
                .withInvocationId(ORDER_INVOCATION).withMetadata("taskKey", TASK_KEY)) {
            String answer = client.prompt()
                    .system(ORDER_SYSTEM)
                    .user("请查询订单 SO-77 的物流状态，并在最终答复中原样给出退款单号。")
                    .tools(new OrderTools())
                    .call().content();
            refundId = extractRefundId(answer);
            assertNotNull(refundId, "第一步最终答复必须包含工具返回的退款单号: " + answer);
        }
        try (RecordingContext ctx = RecordingContext.start(sessionId)
                .withInvocationId(LOGISTICS_INVOCATION).withMetadata("taskKey", TASK_KEY)) {
            String answer = client.prompt()
                    .system(LOGISTICS_SYSTEM)
                    .user("用退款单号 " + refundId + " 查询物流轨迹，工具参数必须用这个退款单号。")
                    .tools(new LogisticsTools())
                    .call().content();
            assertNotNull(answer);
        }
        return java.util.Collections.emptyList();
    }

    private static String extractRefundId(String answer) {
        Matcher m = REFUND_ID_PATTERN.matcher(answer == null ? "" : answer);
        return m.find() ? m.group() : null;
    }

    /**
     * 录制面断言：每步一条记录、工具调用完整（名称/参数/结果齐）、声明标注齐（taskKey/调用点标签）。
     */
    private static void assertRecordingFace(List<InteractionRecord> records) {
        assertEquals(2, records.size(), "内部工具回路 = 每步一次 ChatModel 调用一条记录");
        for (InteractionRecord record : records) {
            assertTrue(record.getInvocationKey().startsWith("invocation:"), "声明调用点标签必须落进调用点键: " + record.getInvocationKey());
            assertTrue(record.getMetadata() != null && record.getMetadata().contains(TASK_KEY), "声明 taskKey 必须进 metadata");
            assertTrue(record.isHasToolCalls(), "工具 loop 的记录必须带工具调用: " + record.getInvocationKey());
            assertNotNull(record.getToolCalls());
            assertTrue(record.getToolCalls().size() >= 1, "观察装饰器必须捕获至少一次工具调用");
            for (ToolCall call : record.getToolCalls()) {
                assertNotNull(call.getToolName(), "工具名必须在场");
                assertNotNull(call.getArguments(), "工具参数必须在场（值溯源的下游数据源）");
                assertNotNull(call.getResult(), "工具结果必须在场（值溯源的上游数据源）");
            }
            assertNotNull(record.getModelResponse(), "最终聚合回复必须在场");
        }
        assertTrue(records.get(0).getInvocationKey().contains(":" + ORDER_INVOCATION + ":"));
        assertTrue(records.get(1).getInvocationKey().contains(":" + LOGISTICS_INVOCATION + ":"));
    }

    private static List<InteractionRecord> ordered(List<InteractionRecord> records) {
        List<InteractionRecord> copy = new java.util.ArrayList<>(records);
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
        assertEquals(expected, repository.findBySessionId(sessionId).size(), "异步录制管道必须在超时内落库（sessionId=" + sessionId + "）");
    }

    /** 订单工具：返回值固定携带退款单号——两步之间的流动值（被测真实点在 LLM 的工具编排与录制链路，工具本体检为固定桩）。 */
    static class OrderTools {
        @Tool(description = "按订单号查询订单状态与关联退款单号")
        String queryOrderStatus(String orderId) {
            return "{\"orderId\":\"SO-77\",\"status\":\"shipped\",\"refundId\":\"" + REFUND_ID + "\",\"eta\":\"2026-09-20\"}";
        }
    }

    /** 物流工具：以退款单号为参数——跨步流动值的下游消费点。 */
    static class LogisticsTools {
        @Tool(description = "按退款单号查询物流轨迹")
        String queryLogistics(String refundId) {
            return "{\"refundId\":\"" + REFUND_ID + "\",\"carrier\":\"SF\",\"trail\":\"hub-A-hub-B\",\"delivered\":false}";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(AgentAssert4jAutoConfiguration.class)
    static class TestApp {

        @Bean
        ChatModel chatModel() {
            OpenAiApi api = OpenAiApi.builder()
                    .baseUrl(env("AGENTASSERT4J_E2E_BASE_URL", "https://api.deepseek.com"))
                    .apiKey(env("AGENTASSERT4J_E2E_API_KEY", ""))
                    .build();
            return OpenAiChatModel.builder()
                    .openAiApi(api)
                    .defaultOptions(OpenAiChatOptions.builder()
                            .model(env("AGENTASSERT4J_E2E_MODEL", "deepseek-v4-flash"))
                            .temperature(0.1)
                            .build())
                    .build();
        }
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.trim().isEmpty()) {
            value = System.getProperty(name);
        }
        return value != null && !value.trim().isEmpty() ? value.trim() : defaultValue;
    }
}
