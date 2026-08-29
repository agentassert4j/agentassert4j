package io.github.agentassert4j.storage.sqlite;

import io.github.agentassert4j.model.ScenarioDefinition;
import io.github.agentassert4j.model.ScenarioRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ScenarioStore 场景读写域的写读往返测试。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
class ScenarioStoreTest {

    private SqliteStorageRepository repo;

    @BeforeEach
    void setUp() {
        repo = new SqliteStorageRepository(":memory:");
        repo.initialize();
    }

    @AfterEach
    void tearDown() {
        if (repo != null) repo.close();
    }

    private ScenarioDefinition definition(String id) {
        ScenarioDefinition d = new ScenarioDefinition();
        d.setScenarioId(id);
        d.setName("退款话术柔和");
        d.setTemplateId("tpl-support-v1");
        d.setInputSpec("{\"userInput\":\"申请退款但订单已签收\",\"variables\":{\"order_id\":\"ORD-001\"}}");
        d.setAssertions("{\"requiredKeywords\":[\"退款\"],\"behaviors\":[\"nonEmptyOutput\"]}");
        d.setVersion(3);
        d.setMetadata("{\"owner\":\"support-team\"}");
        d.setCreatedAt(1000L);
        d.setUpdatedAt(2000L);
        return d;
    }

    @Test
    void scenario_roundTrip_fieldByField() {
        repo.saveScenario(definition("s-1"));

        List<ScenarioDefinition> all = repo.findScenarios();
        assertEquals(1, all.size());
        ScenarioDefinition loaded = all.get(0);
        assertEquals("s-1", loaded.getScenarioId());
        assertEquals("退款话术柔和", loaded.getName());
        assertEquals("tpl-support-v1", loaded.getTemplateId());
        assertEquals(definition("s-1").getInputSpec(), loaded.getInputSpec(), "inputSpec JSON 逐字保真");
        assertEquals(definition("s-1").getAssertions(), loaded.getAssertions(), "assertions JSON 逐字保真");
        assertEquals(3, loaded.getVersion());
        assertEquals("{\"owner\":\"support-team\"}", loaded.getMetadata());
        assertEquals(1000L, loaded.getCreatedAt());
        assertEquals(2000L, loaded.getUpdatedAt());
    }

    @Test
    void scenario_upsert_sameIdReplaces() {
        repo.saveScenario(definition("s-1"));
        ScenarioDefinition updated = definition("s-1");
        updated.setName("改名后的场景");
        updated.setVersion(4);
        repo.saveScenario(updated);

        List<ScenarioDefinition> all = repo.findScenarios();
        assertEquals(1, all.size(), "同 scenario_id 覆盖更新而非追加");
        assertEquals(4, all.get(0).getVersion());
        assertEquals("改名后的场景", all.get(0).getName());
    }

    @Test
    void scenario_sortedById() {
        repo.saveScenario(definition("s-b"));
        repo.saveScenario(definition("s-a"));

        assertEquals("s-a", repo.findScenarios().get(0).getScenarioId(), "按 scenario_id 字典序");
    }

    @Test
    void run_roundTrip_fullTelemetry() {
        ScenarioRun run = new ScenarioRun();
        run.setRunId("run-1");
        run.setScenarioId("s-1");
        run.setStartedAt(5000L);
        run.setVerdict("STABLE");
        run.setSampleCount(10);
        run.setPassCount(9);
        run.setFailCount(1);
        run.setInputTokens(1200);
        run.setOutputTokens(340);
        run.setCacheReadTokens(800);
        run.setCacheWriteTokens(120);
        run.setReasoningTokens(64);
        run.setLatencyMs(4200L);
        run.setTtftMs(610L);
        run.setCostUsd(0.0031);
        run.setReportRef("target/scenario-report/run-1.json");
        run.setMetadata("{\"endpoint\":\"deepseek\"}");
        repo.saveScenarioRun(run);

        List<ScenarioRun> runs = repo.findScenarioRuns("s-1");
        assertEquals(1, runs.size());
        ScenarioRun loaded = runs.get(0);
        assertEquals("run-1", loaded.getRunId());
        assertEquals("s-1", loaded.getScenarioId());
        assertEquals(5000L, loaded.getStartedAt());
        assertEquals("STABLE", loaded.getVerdict());
        assertEquals(10, loaded.getSampleCount());
        assertEquals(9, loaded.getPassCount());
        assertEquals(1, loaded.getFailCount());
        assertEquals(1200, loaded.getInputTokens());
        assertEquals(340, loaded.getOutputTokens());
        assertEquals(800, loaded.getCacheReadTokens().intValue());
        assertEquals(120, loaded.getCacheWriteTokens().intValue());
        assertEquals(64, loaded.getReasoningTokens().intValue());
        assertEquals(4200L, loaded.getLatencyMs());
        assertEquals(610L, loaded.getTtftMs().longValue());
        assertEquals(0.0031, loaded.getCostUsd(), 0.0000001);
        assertEquals("target/scenario-report/run-1.json", loaded.getReportRef());
        assertEquals("{\"endpoint\":\"deepseek\"}", loaded.getMetadata());
    }

    @Test
    void run_nullTelemetry_roundTripsAsNull() {
        // 遥测列可空：缺失的缓存/思考 token 与 ttft 往返后保持 null（与 0 语义区分）
        ScenarioRun run = new ScenarioRun();
        run.setRunId("run-2");
        run.setScenarioId("s-1");
        run.setStartedAt(1000L);
        run.setVerdict("DRIFTED");
        run.setSampleCount(3);
        run.setPassCount(1);
        run.setFailCount(2);
        run.setLatencyMs(900L);
        repo.saveScenarioRun(run);

        ScenarioRun loaded = repo.findScenarioRuns("s-1").get(0);
        assertNull(loaded.getCacheReadTokens());
        assertNull(loaded.getCacheWriteTokens());
        assertNull(loaded.getReasoningTokens());
        assertNull(loaded.getTtftMs());
        assertNull(loaded.getCostUsd());
        assertNull(loaded.getReportRef());
        assertEquals(0, loaded.getInputTokens());
    }

    @Test
    void runs_sortedByStartedAt_withinScenario() {
        repo.saveScenario(definition("s-1"));
        ScenarioRun later = new ScenarioRun();
        later.setRunId("run-late");
        later.setScenarioId("s-1");
        later.setStartedAt(2000L);
        later.setVerdict("STABLE");
        later.setSampleCount(1);
        later.setPassCount(1);
        later.setFailCount(0);
        repo.saveScenarioRun(later);
        ScenarioRun earlier = new ScenarioRun();
        earlier.setRunId("run-early");
        earlier.setScenarioId("s-1");
        earlier.setStartedAt(1000L);
        earlier.setVerdict("STABLE");
        earlier.setSampleCount(1);
        earlier.setPassCount(1);
        earlier.setFailCount(0);
        repo.saveScenarioRun(earlier);

        List<ScenarioRun> runs = repo.findScenarioRuns("s-1");
        assertEquals(2, runs.size());
        assertEquals("run-early", runs.get(0).getRunId(), "按开始时间升序");
    }

    @Test
    void runs_isolatedByScenario() {
        repo.saveScenario(definition("s-1"));
        ScenarioRun other = new ScenarioRun();
        other.setRunId("run-other");
        other.setScenarioId("s-other");
        other.setStartedAt(1000L);
        other.setVerdict("STABLE");
        other.setSampleCount(1);
        other.setPassCount(1);
        other.setFailCount(0);
        repo.saveScenarioRun(other);

        assertTrue(repo.findScenarioRuns("s-1").isEmpty(), "场景间执行事实隔离");
        assertEquals(1, repo.findScenarioRuns("s-other").size());
    }
}
