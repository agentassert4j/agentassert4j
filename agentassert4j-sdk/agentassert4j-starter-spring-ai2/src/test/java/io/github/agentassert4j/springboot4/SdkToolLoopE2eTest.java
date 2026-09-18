package io.github.agentassert4j.springboot4;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.recorder.InteractionRecorder;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.springai2.RecordingChatModel;
import io.github.agentassert4j.springai2.RecordingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SDK 录制面端到端验证 —— Spring AI 2.x 工具调用 loop 下的采集形态
 * （真实调用，环境变量门控，缺省跳过）。
 *
 * <p>2.x 的工具循环由 ChatClient 的 ToolCallingAdvisor 在 ChatModel 之上驱动，
 * 每个 LLM 轮次都是一次独立 ChatModel 调用——录制面逐轮可见是天然形态：发起帧
 * （响应携带的工具调用，参数在、结果不在）落在首轮记录，结果帧（工具返回原文）
 * 落在后续轮次记录的 previousTurns；直调 ChatModel 时响应即发起帧、框架不代跑工具。</p>
 *
 * <p>环境变量：{@code AGENTASSERT4J_E2E_API_KEY} 必需；{@code AGENTASSERT4J_E2E_BASE_URL}
 * 默认 DeepSeek；{@code AGENTASSERT4J_E2E_MODEL} 默认 {@code deepseek-v4-flash}。</p>
 *
 * @author axy-yxa
 * @since 2026-09-18
 */
@Tag("e2e-real-llm")
@EnabledIfEnvironmentVariable(named = "AGENTASSERT4J_E2E_API_KEY", matches = ".+")
class SdkToolLoopE2eTest {

    private static final String TASK_KEY = "sdk-loop-demo";
    private static final String ORDER_INVOCATION = "orderStatus";
    private static final String REFUND_ID = "REF-8841";

    private static final String ORDER_SYSTEM = "你是电商售后助手。涉及订单必须先调用查询工具核实事实，禁止编造。";

    @Test
    @DisplayName("2.x ChatClient 工具 loop：逐轮成记录，发起帧与结果帧分落两轮")
    void chatClientAdvisorLoop_perRoundCapture() throws Exception {
        Path db = Files.createTempFile("sdk2-loop-e2e-", ".db");
        db.toFile().deleteOnExit();
        SpringApplication app = new SpringApplication(TestApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("agentassert4j.storage.url", db.toString(), "agentassert4j.recorder.batch-size", "2", "agentassert4j.recorder.flush-interval-ms", "200"));
        try (ConfigurableApplicationContext ctx = app.run()) {
            ChatModel chatModel = ctx.getBean(ChatModel.class);
            assertTrue(chatModel instanceof RecordingChatModel, "自动装配必须把容器内 ChatModel 包上录制装饰器");
            StorageRepository repository = ctx.getBean(StorageRepository.class);
            InteractionRecorder recorder = ctx.getBean(InteractionRecorder.class);
            ChatClient client = ChatClient.builder(chatModel).build();

            String session = "sdk2-e2e-" + System.currentTimeMillis();
            String answer;
            try (RecordingContext c = RecordingContext.start(session).withInvocationId(ORDER_INVOCATION).withMetadata("taskKey", TASK_KEY)) {
                answer = client.prompt().system(ORDER_SYSTEM).user("请查询订单 SO-77 的物流状态，并在最终答复中原样给出退款单号。").tools(new OrderTools()).call().content();
            }
            assertNotNull(answer);

            awaitRecordCount(repository, session, 2);
            List<InteractionRecord> records = ordered(repository.findBySessionId(session));
            System.out.println("[sdk2-e2e] chat-client loop records: " + records.size());

            InteractionRecord first = records.get(0);
            assertTrue(first.isHasToolCalls(), "首轮记录必须携带发起帧");
            ToolCall frame = first.getToolCalls().get(0);
            assertEquals("queryOrderStatus", frame.getToolName());
            assertTrue(frame.getArguments() != null && !frame.getArguments().isEmpty(), "发起帧参数必须解析成映射");
            assertTrue(frame.getResult() == null, "发起帧记录不带结果——结果帧在下一轮请求里");

            InteractionRecord last = records.get(records.size() - 1);
            assertTrue(!last.isHasToolCalls(), "终答轮不再发起工具调用");
            assertTrue(last.getPreviousTurns() != null && last.getPreviousTurns().stream().anyMatch(t -> "tool".equals(t.getRole()) && t.getContent() != null && t.getContent().contains(REFUND_ID)), "终答轮记录的 previousTurns 必须携带工具结果帧原文: " + last.getPreviousTurns());
            System.out.println("[sdk2-e2e] per-round capture holds: frame round + result turn");

            recorder.stop();
        }
    }

    @Test
    @DisplayName("2.x 直调 ChatModel：响应即发起帧，框架不代跑工具")
    void directChatModelCall_returnsToolCallFrames() throws Exception {
        Path db = Files.createTempFile("sdk2-direct-e2e-", ".db");
        db.toFile().deleteOnExit();
        SpringApplication app = new SpringApplication(TestApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("agentassert4j.storage.url", db.toString(), "agentassert4j.recorder.batch-size", "2", "agentassert4j.recorder.flush-interval-ms", "200"));
        try (ConfigurableApplicationContext ctx = app.run()) {
            ChatModel chatModel = ctx.getBean(ChatModel.class);
            StorageRepository repository = ctx.getBean(StorageRepository.class);
            InteractionRecorder recorder = ctx.getBean(InteractionRecorder.class);

            // 2.x 直调时 OpenAiChatModel 要求运行时 options 为 provider 选项类型——
            // 泛型 ToolCallingChatOptions 会被 createRequest 直接转型失败；且运行时
            // options 须自带 model（合并链不回填默认模型的场景会落进客户端自家默认）
            OpenAiChatOptions options = OpenAiChatOptions.builder().model(env("AGENTASSERT4J_E2E_MODEL", "deepseek-v4-flash")).toolCallbacks(ToolCallbacks.from(new OrderTools())).build();
            String session = "sdk2-direct-" + System.currentTimeMillis();
            ChatResponse response;
            try (RecordingContext c = RecordingContext.start(session).withInvocationId(ORDER_INVOCATION + "Direct").withMetadata("taskKey", "sdk-direct-loop")) {
                response = chatModel.call(new Prompt(List.of(new SystemMessage(ORDER_SYSTEM), new UserMessage("请查询订单 SO-77 的物流状态。")), options));
            }
            AssistantMessage output = response.getResult().getOutput();
            assertNotNull(output.getToolCalls(), "直调响应必须携带发起帧");
            assertFalse(output.getToolCalls().isEmpty(), "系统提示词要求先调用工具");

            awaitRecordCount(repository, session, 1);
            List<InteractionRecord> records = ordered(repository.findBySessionId(session));
            InteractionRecord record = records.get(0);
            assertTrue(record.isHasToolCalls(), "直调记录必须带发起帧");
            assertTrue(record.getToolCalls().get(0).getResult() == null, "2.x 直调不代跑工具——记录里只有发起帧，无结果");
            System.out.println("[sdk2-e2e] direct call captures frames without execution");

            recorder.stop();
        }
    }

    /**
     * 订单工具：返回值固定携带退款单号（被测真实点在 LLM 的工具编排与录制链路，工具本体检为固定桩）。
     */
    static class OrderTools {
        @Tool(description = "按订单号查询订单状态与关联退款单号")
        String queryOrderStatus(String orderId) {
            return "{\"orderId\":\"SO-77\",\"status\":\"shipped\",\"refundId\":\"" + REFUND_ID + "\",\"eta\":\"2026-09-20\"}";
        }
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
        assertEquals(expected, repository.findBySessionId(sessionId).size(), "异步录制管道必须在超时内落库（sessionId=" + sessionId + "）");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(AgentAssert4jAutoConfiguration.class)
    static class TestApp {

        /**
         * 2.x 的连接配置（端点/密钥）在 OpenAiChatOptions 上——OpenAiSetup 据此构建
         * 官方 openai-java 客户端（1.x 自有的 REST 抽象随大版本移除）。
         */
        @Bean
        ChatModel chatModel() {
            return OpenAiChatModel.builder().options(OpenAiChatOptions.builder().baseUrl(env("AGENTASSERT4J_E2E_BASE_URL", "https://api.deepseek.com/v1")).apiKey(env("AGENTASSERT4J_E2E_API_KEY", "")).model(env("AGENTASSERT4J_E2E_MODEL", "deepseek-v4-flash")).temperature(0.1).build()).build();
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
