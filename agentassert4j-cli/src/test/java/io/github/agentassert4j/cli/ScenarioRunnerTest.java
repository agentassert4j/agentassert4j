package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.config.ScenarioConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.LlmRequest;
import io.github.agentassert4j.model.LlmResponse;
import io.github.agentassert4j.model.ScenarioRun;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ScenarioRunner 的单元测试——桩客户端验证自动派生、断言注入与 dry-run 语义。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
class ScenarioRunnerTest {

    private SqliteStorageRepository repository;
    private ByteArrayOutputStream output;
    private CannedClient client;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(":memory:");
        repository.initialize();
        output = new ByteArrayOutputStream();
        client = new CannedClient("same answer");
    }

    @AfterEach
    void tearDown() {
        if (repository != null) repository.close();
    }

    private ScenarioRunner runner() {
        return new ScenarioRunner(repository, client, new DeterministicComparator(ComparatorConfig.defaults()), new PrintStream(output));
    }

    private ScenarioRunner.Outcome run(ScenarioConfig config, boolean dryRun) {
        return runner().run(config, dryRun, null, null);
    }

    private void seedDeclaredRecord() {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId("rec-1");
        record.setSessionId("session-1");
        record.setTimestamp(1000L);
        record.setSeq(1L);
        record.setSkillId("skill-1");
        record.setTemplateHash("hash-old");
        record.setUserInput("查订单 ORD-001");
        record.setTurnIndex(0);
        record.setModelResponse("same answer");
        record.setToolCalls(new ArrayList<>());
        record.setHasToolCalls(false);
        record.setTemplateText("系统提示词");
        repository.saveInteraction(record);
        repository.saveTemplateText("hash-old", "系统提示词");
    }

    private void seedUndeclaredRecord() {
        InteractionRecord record = new InteractionRecord();
        record.setRecordId("rec-u1");
        record.setSessionId("session-u1");
        record.setTimestamp(2000L);
        record.setSeq(1L);
        record.setTemplateHash("hash-old");
        record.setUserInput("查订单 ORD-001");
        record.setTurnIndex(0);
        record.setModelResponse("same answer");
        record.setToolCalls(new ArrayList<>());
        record.setHasToolCalls(false);
        record.setTemplateText("系统提示词");
        repository.saveInteraction(record);
        repository.saveTemplateText("hash-old", "系统提示词");
    }

    private void seedToolClosingRecord() {
        // 工具结果收尾轮：无用户输入位（userInput null），行为分组仍由模板锚点派生
        InteractionRecord record = new InteractionRecord();
        record.setRecordId("rec-t1");
        record.setSessionId("session-t1");
        record.setTimestamp(3000L);
        record.setSeq(1L);
        record.setTemplateHash("hash-old");
        record.setUserInput(null);
        record.setTurnIndex(0);
        record.setModelResponse("same answer");
        record.setToolCalls(new ArrayList<>());
        record.setHasToolCalls(false);
        record.setTemplateText("系统提示词");
        repository.saveInteraction(record);
        repository.saveTemplateText("hash-old", "系统提示词");
    }

    @Test
    void run_autoDerivesNoiseBaselineScenarios_perEstablishedGroup() {
        seedDeclaredRecord();

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.empty(), false);

        assertEquals(1, outcome.getRuns().size(), "每个已建档分组派生一个场景");
        assertTrue(outcome.getSkipped().isEmpty(), "无跳过时跳过清单为空");
        ScenarioRun run = outcome.getRuns().get(0);
        assertEquals("auto:skill:skill-1:hash-old", run.getScenarioId());
        assertEquals("STABLE", run.getVerdict(), "同输入同回答 → 噪声基线稳定");
        assertEquals(ScenarioConfig.DEFAULT_SAMPLE_COUNT, run.getSampleCount(), "自动派生默认 5 轮");
        List<ScenarioRun> persisted = repository.findScenarioRuns(run.getScenarioId());
        assertEquals(1, persisted.size(), "执行事实已落库");
    }

    @Test
    void run_declaredAssertionFailure_mapsToDrifted() {
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"kw\",\"skillId\":\"skill-1\"," + "\"userInput\":\"查订单 ORD-001\",\"assertions\":{\"requiredKeywords\":[\"不存在的词\"]}}]}";

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.fromJson(configJson), false);

        assertEquals(1, outcome.getRuns().size());
        assertEquals("DRIFTED", outcome.getRuns().get(0).getVerdict(), "断言失败经规则注入表现为逐轮 CHANGED → DRIFTED");
        assertTrue(outcome.getRuns().get(0).getFailCount() > 0);
    }

    @Test
    void run_templateHashBoundUndeclaredGroup_assertionsStillApply() {
        // 未声明分组（无业务标签）按模板 hash 绑定：断言必须经空键规则注入生效，
        // 静默丢弃断言会产出假绿（回归钉子）
        seedUndeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"tpl-bound\",\"templateHash\":\"hash-old\"," + "\"userInput\":\"查订单 ORD-001\",\"assertions\":{\"requiredKeywords\":[\"不存在的词\"]}}]}";

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.fromJson(configJson), false);

        assertEquals(1, outcome.getRuns().size(), "templateHash 绑定未声明分组应可解析");
        assertEquals("DRIFTED", outcome.getRuns().get(0).getVerdict(), "断言对未声明分组同样生效：缺词 → 逐轮 CHANGED");
        assertTrue(outcome.getRuns().get(0).getFailCount() > 0);
    }

    @Test
    void run_scenarioInputWithVariables_reachesLlmRequest() {
        // 场景核心语义：新输入（变量替换后）是发起给模型的真实输入，而非重放历史输入
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"new-input\",\"skillId\":\"skill-1\"," + "\"userInput\":\"查订单 {{order_id}} 的物流\",\"variables\":{\"order_id\":\"ORD-777\"}}]}";

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.fromJson(configJson), false);

        assertEquals(1, outcome.getRuns().size());
        assertEquals("查订单 ORD-777 的物流", client.lastRequest.getUserInput());
    }

    @Test
    void run_autoDerive_replaysBaselineInput() {
        seedDeclaredRecord();

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.empty(), false);

        assertEquals(1, outcome.getRuns().size());
        assertEquals("查订单 ORD-001", client.lastRequest.getUserInput(), "自动派生重放基线历史输入");
    }

    @Test
    void run_cacheTelemetry_aggregatedAndPersisted() {
        seedDeclaredRecord();

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.empty(), false);

        ScenarioRun run = outcome.getRuns().get(0);
        assertEquals(Integer.valueOf(150), run.getCacheReadTokens(), "5 轮 × 30 缓存读 token 聚合");
        assertEquals(Integer.valueOf(50), run.getCacheWriteTokens());
        assertEquals(Integer.valueOf(25), run.getReasoningTokens());
        List<ScenarioRun> persisted = repository.findScenarioRuns(run.getScenarioId());
        assertEquals(Integer.valueOf(150), persisted.get(0).getCacheReadTokens(), "可空遥测列写读往返");
    }

    @Test
    void run_toolClosingTurnBucket_scenarioInputDeclared_skipped() {
        // 工具结果收尾轮没有用户输入位：声明了输入的场景无法注入，跳过而非静默忽略
        seedToolClosingRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"tpl-input\",\"templateHash\":\"hash-old\"," + "\"userInput\":\"新输入来了\"}]}";

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.fromJson(configJson), false);

        assertTrue(outcome.getRuns().isEmpty());
        assertEquals(1, outcome.getSkipped().size());
        assertTrue(outcome.getSkipped().get(0).reason.contains("工具结果轮"));
        assertEquals(0, client.callCount);
    }

    @Test
    void run_unmatchedBinding_recordsSkipWithReason() {
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"ghost\",\"skillId\":\"no-such\",\"userInput\":\"任意\"}]}";

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.fromJson(configJson), false);

        assertTrue(outcome.getRuns().isEmpty(), "跳过的场景不产生执行事实");
        assertEquals(1, outcome.getSkipped().size());
        assertEquals("ghost", outcome.getSkipped().get(0).scenarioId);
        assertTrue(outcome.getSkipped().get(0).reason.contains("没有匹配的已录制分组"));
        assertEquals(0, client.callCount, "被跳过的场景不得发起真实调用");
    }

    @Test
    void run_cliBudgetOverride_capsConfiguredRounds() {
        // CLI 预算旗标全局覆盖场景声明——平台级支出护栏不依赖文件内容
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"capped\",\"skillId\":\"skill-1\"," + "\"userInput\":\"查订单 ORD-001\",\"sampling\":{\"sampleCount\":8}}]}";

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.fromJson(configJson), false, 3, null);

        assertEquals(3, client.callCount, "覆盖后的调用数上限生效");
        assertEquals(3, outcome.getRuns().get(0).getSampleCount());
    }

    @Test
    void run_cliBudgetOverride_appliesToAutoDerived() {
        seedDeclaredRecord();

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.empty(), false, 2, null);

        assertEquals(2, client.callCount, "覆盖对自动派生场景同样生效");
    }

    @Test
    void run_stallIdenticalFailures_earlyStops_persistsAndReports() {
        // 同一失败差异连续 3 轮（默认阈值）→ 早停：声明 5 轮只发 3 次，证据全链可见
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"stalled\",\"skillId\":\"skill-1\"," + "\"userInput\":\"查订单 ORD-001\",\"assertions\":{\"requiredKeywords\":[\"不存在的词\"]}}]}";

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.fromJson(configJson), false);

        assertEquals(1, outcome.getRuns().size());
        assertEquals(3, client.callCount, "剩余 2 轮因停滞早停不再发放");
        ScenarioRun stalledRun = outcome.getRuns().get(0);
        assertEquals(3, stalledRun.getSampleCount());
        assertEquals("DRIFTED", stalledRun.getVerdict());
        assertTrue(stalledRun.isStalled());
        assertEquals("{\"stalled\":true}", stalledRun.getMetadata(), "停滞事实进吸收层落库");
        List<ScenarioRun> persisted = repository.findScenarioRuns("stalled");
        assertEquals("{\"stalled\":true}", persisted.get(0).getMetadata(), "metadata 写读往返");

        String json = ScenarioRunCommand.reportsJson(outcome, false);
        assertTrue(json.contains("\"stalled\":true"), "证据报告披露停滞: " + json);
        assertTrue(json.contains("\"cacheReadTokens\":90"), "报告带缓存遥测（3 轮 × 30）: " + json);
        assertTrue(json.contains("\"skipped\":[]"), "报告带跳过清单");
    }

    @Test
    void run_unknownBehaviorInAssertions_scenarioSkipped() {
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"typo\",\"skillId\":\"skill-1\"," + "\"userInput\":\"查订单 ORD-001\",\"assertions\":{\"behaviors\":[\"noErr0\"]}}]}";

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.fromJson(configJson), false);

        assertTrue(outcome.getRuns().isEmpty(), "断言不可兑现的场景不执行");
        assertEquals(1, outcome.getSkipped().size());
        assertTrue(outcome.getSkipped().get(0).reason.contains("noErr0"), "跳过原因点名未知行为");
        assertEquals(0, client.callCount);
    }

    @Test
    void run_dryRun_zeroLlmCalls() {
        seedDeclaredRecord();

        ScenarioRunner.Outcome outcome = run(ScenarioConfig.empty(), true);

        assertTrue(outcome.getRuns().isEmpty());
        assertEquals(1, outcome.getPlanned().size(), "dry-run 产出计划");
        assertEquals(0, client.callCount, "dry-run 不得发起真实调用");
    }

    /**
     * 可编程桩：固定正文与 token 消耗。
     */
    static class CannedClient implements LlmClient {
        private final String content;
        int callCount;
        LlmRequest lastRequest;
        Integer cacheReadTokens = 30;
        Integer cacheWriteTokens = 10;
        Integer reasoningTokens = 5;

        CannedClient(String content) {
            this.content = content;
        }

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            callCount++;
            lastRequest = request;
            LlmResponse response = new LlmResponse();
            response.setContent(content);
            response.setInputTokens(10);
            response.setOutputTokens(5);
            response.setCacheReadTokens(cacheReadTokens);
            response.setCacheWriteTokens(cacheWriteTokens);
            response.setReasoningTokens(reasoningTokens);
            return response;
        }

        @Override
        public String name() {
            return "canned-model";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
