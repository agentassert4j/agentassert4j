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

    @Test
    void run_autoDerivesNoiseBaselineScenarios_perEstablishedGroup() {
        seedDeclaredRecord();

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.empty(), false);

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
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"kw\",\"skillId\":\"skill-1\"," +
                "\"userInput\":\"查订单 ORD-001\",\"assertions\":{\"requiredKeywords\":[\"不存在的词\"]}}]}";

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.fromJson(configJson), false);

        assertEquals(1, outcome.getRuns().size());
        assertEquals("DRIFTED", outcome.getRuns().get(0).getVerdict(), "断言失败经规则注入表现为逐轮 CHANGED → DRIFTED");
        assertTrue(outcome.getRuns().get(0).getFailCount() > 0);
    }

    @Test
    void run_templateHashBoundUndeclaredGroup_assertionsStillApply() {
        // 未声明分组（无业务标签）按模板 hash 绑定：断言必须经空键规则注入生效，
        // 静默丢弃断言会产出假绿（回归钉子）
        seedUndeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"tpl-bound\",\"templateHash\":\"hash-old\"," +
                "\"userInput\":\"查订单 ORD-001\",\"assertions\":{\"requiredKeywords\":[\"不存在的词\"]}}]}";

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.fromJson(configJson), false);

        assertEquals(1, outcome.getRuns().size(), "templateHash 绑定未声明分组应可解析");
        assertEquals("DRIFTED", outcome.getRuns().get(0).getVerdict(), "断言对未声明分组同样生效：缺词 → 逐轮 CHANGED");
        assertTrue(outcome.getRuns().get(0).getFailCount() > 0);
    }

    @Test
    void run_unmatchedBinding_recordsSkipWithReason() {
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"ghost\",\"skillId\":\"no-such\",\"userInput\":\"任意\"}]}";

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.fromJson(configJson), false);

        assertTrue(outcome.getRuns().isEmpty(), "跳过的场景不产生执行事实");
        assertEquals(1, outcome.getSkipped().size());
        assertEquals("ghost", outcome.getSkipped().get(0).scenarioId);
        assertTrue(outcome.getSkipped().get(0).reason.contains("没有匹配的已录制分组"));
        assertEquals(0, client.callCount, "被跳过的场景不得发起真实调用");
    }

    @Test
    void run_unknownBehaviorInAssertions_scenarioSkipped() {
        seedDeclaredRecord();
        String configJson = "{\"scenarios\":[{\"scenarioId\":\"typo\",\"skillId\":\"skill-1\"," +
                "\"userInput\":\"查订单 ORD-001\",\"assertions\":{\"behaviors\":[\"noErr0\"]}}]}";

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.fromJson(configJson), false);

        assertTrue(outcome.getRuns().isEmpty(), "断言不可兑现的场景不执行");
        assertEquals(1, outcome.getSkipped().size());
        assertTrue(outcome.getSkipped().get(0).reason.contains("noErr0"), "跳过原因点名未知行为");
        assertEquals(0, client.callCount);
    }

    @Test
    void run_dryRun_zeroLlmCalls() {
        seedDeclaredRecord();

        ScenarioRunner.Outcome outcome = runner().run(ScenarioConfig.empty(), true);

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

        CannedClient(String content) {
            this.content = content;
        }

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            callCount++;
            LlmResponse response = new LlmResponse();
            response.setContent(content);
            response.setInputTokens(10);
            response.setOutputTokens(5);
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
