package io.github.agentassert4j.storage.sqlite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.BaselineStatus;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.SkillType;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.model.TurnContext;
import io.github.agentassert4j.model.Checkpoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SqliteStorageRepositoryTest {

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

    @Test
    void type_returnsSqlite() {
        assertEquals("sqlite", repo.type());
    }

    @Test
    void saveAndFindInteraction() {
        InteractionRecord r = createSampleRecord("rec-1", "session-1", "skill-1", "hash-abc");
        repo.saveInteraction(r);

        List<InteractionRecord> results = repo.findBySkillId("skill-1");
        assertEquals(1, results.size());
        InteractionRecord loaded = results.get(0);
        assertEquals("rec-1", loaded.getRecordId());
        assertEquals("session-1", loaded.getSessionId());
        assertEquals("skill-1", loaded.getSkillId());
        assertEquals("hash-abc", loaded.getSystemPromptHash());
        assertEquals("hello", loaded.getUserInput());
        assertEquals("response text", loaded.getModelResponse());
        assertTrue(loaded.isHasToolCalls());
        assertEquals(150L, loaded.getLatencyMs());
    }

    @Test
    void findByPromptHash() {
        repo.saveInteraction(createSampleRecord("r1", "s1", "sk1", "hash-xxx"));
        repo.saveInteraction(createSampleRecord("r2", "s2", "sk2", "hash-xxx"));
        repo.saveInteraction(createSampleRecord("r3", "s3", "sk3", "hash-yyy"));

        List<InteractionRecord> results = repo.findByPromptHash("hash-xxx");
        assertEquals(2, results.size());
    }

    @Test
    void findSkillIdsByPromptHash() {
        repo.saveInteraction(createSampleRecord("r1", "s1", "sk-alpha", "hash-111"));
        repo.saveInteraction(createSampleRecord("r2", "s2", "sk-beta", "hash-111"));
        repo.saveInteraction(createSampleRecord("r3", "s3", "sk-gamma", "hash-222"));

        Set<String> skillIds = repo.findSkillIdsByPromptHash("hash-111");
        assertEquals(2, skillIds.size());
        assertTrue(skillIds.contains("sk-alpha"));
        assertTrue(skillIds.contains("sk-beta"));
    }

    @Test
    void findBySessionId() {
        repo.saveInteraction(createSampleRecord("r1", "sess-A", "sk1", "h1"));
        repo.saveInteraction(createSampleRecord("r2", "sess-B", "sk2", "h2"));
        repo.saveInteraction(createSampleRecord("r3", "sess-A", "sk3", "h3"));

        List<InteractionRecord> results = repo.findBySessionId("sess-A");
        assertEquals(2, results.size());
    }

    @Test
    void findAllSessionIds() {
        repo.saveInteraction(createSampleRecord("r1", "sa", "sk1", "h1"));
        repo.saveInteraction(createSampleRecord("r2", "sb", "sk2", "h2"));
        repo.saveInteraction(createSampleRecord("r3", "sa", "sk3", "h3"));

        List<String> ids = repo.findAllSessionIds();
        assertEquals(2, ids.size());
        assertTrue(ids.contains("sa"));
        assertTrue(ids.contains("sb"));
    }

    @Test
    void saveInteractionsBatch() {
        List<InteractionRecord> batch = new ArrayList<>();
        batch.add(createSampleRecord("b1", "s1", "sk1", "h1"));
        batch.add(createSampleRecord("b2", "s1", "sk2", "h2"));
        batch.add(createSampleRecord("b3", "s1", "sk3", "h3"));
        repo.saveInteractions(batch);

        assertEquals(3, repo.findBySessionId("s1").size());
    }

    @Test
    void saveAndFindSkillProfile() {
        SkillProfile p = new SkillProfile();
        p.setSkillId("sk-001");
        p.setGroupKey("queryOrder[orderId:String]");
        p.setSkillName("queryOrder");
        p.setSkillType(SkillType.TOOL_SKILL);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v1.0");
        p.setTotalRecords(42);

        DeterministicFingerprint fp = new DeterministicFingerprint();
        Set<String> tools = new java.util.HashSet<>();
        tools.add("queryOrder");
        fp.setToolCallSet(tools);
        p.setFingerprint(fp);

        repo.saveSkillProfile(p);

        SkillProfile loaded = repo.findSkillByGroupKey("queryOrder[orderId:String]");
        assertNotNull(loaded);
        assertEquals("sk-001", loaded.getSkillId());
        assertEquals("queryOrder", loaded.getSkillName());
        assertEquals(SkillType.TOOL_SKILL, loaded.getSkillType());
        assertEquals(BaselineStatus.BASELINE, loaded.getBaselineStatus());
        assertEquals("v1.0", loaded.getVersionTag());
        assertEquals(42, loaded.getTotalRecords());
        assertNotNull(loaded.getFingerprint());
        assertTrue(loaded.getFingerprint().getToolCallSet().contains("queryOrder"));
    }

    @Test
    void findAllSkills() {
        saveMinimalProfile("sk1", "gk1", "Tool1");
        saveMinimalProfile("sk2", "gk2", "Tool2");

        List<SkillProfile> all = repo.findAllSkills();
        assertEquals(2, all.size());
    }

    @Test
    void saveAndFindPromptText() {
        repo.savePromptText("hash-sha256-abc", "You are a helpful assistant.");
        String loaded = repo.findPromptText("hash-sha256-abc");
        assertEquals("You are a helpful assistant.", loaded);
    }

    @Test
    void findPromptText_notFound() {
        assertNull(repo.findPromptText("nonexistent"));
    }

    @Test
    void saveAndLoadGraph() {
        String graphJson = "{\"nodes\":{\"sk1\":{\"outbound\":1}},\"edges\":{\"sk1\":[\"sk2\"]}}";
        repo.saveGraph(graphJson);
        String loaded = repo.loadGraph();
        assertEquals(graphJson, loaded);
    }

    @Test
    void loadGraph_empty() {
        assertNull(repo.loadGraph());
    }

    @Test
    void archiveAndFindBaseline() {
        DeterministicFingerprint fp = new DeterministicFingerprint();
        Set<String> tools = new java.util.HashSet<>();
        tools.add("toolX");
        fp.setToolCallSet(tools);

        repo.archiveBaseline("sk-001", fp, "v1.0");

        ArchivedBaseline loaded = repo.findArchivedBaseline("sk-001", "v1.0");
        assertNotNull(loaded);
        assertEquals("sk-001", loaded.getSkillId());
        assertEquals("v1.0", loaded.getVersionTag());
        assertNotNull(loaded.getFingerprint());
        assertTrue(loaded.getFingerprint().getToolCallSet().contains("toolX"));
    }

    @Test
    void findArchivedBaseline_notFound() {
        assertNull(repo.findArchivedBaseline("nonexistent", "v99"));
    }

    @Test
    void saveAndFindCheckpoint() {
        Checkpoint c = new Checkpoint();
        c.setId("cp-001");
        c.setName("post-prompt-change");
        c.setTimestamp(System.currentTimeMillis());
        c.setPassed(10);
        c.setFailed(2);
        c.setDiff(1);
        c.setFullReport("{\"summary\":\"2 failures\"}");
        repo.saveCheckpoint(c);

        // Checkpoint 保存只验证不抛异常（没有 find 方法在 SPI 中）
        // 通过重新保存验证覆盖不报错
        c.setPassed(12);
        assertDoesNotThrow(() -> repo.saveCheckpoint(c));
    }

    @Test
    void interactionWithToolCalls() {
        InteractionRecord r = createSampleRecord("rec-tools", "sess-1", "sk1", "h1");

        List<ToolCall> toolCalls = new ArrayList<>();
        ToolCall tc = new ToolCall();
        tc.setToolName("queryOrder");
        tc.setToolCallId("tc-001");
        tc.setSuccess(true);
        tc.setResult("{\"orderId\":\"ORD-001\"}");
        Map<String, Object> args = new HashMap<>();
        args.put("orderId", "ORD-001");
        tc.setArguments(args);
        toolCalls.add(tc);
        r.setToolCalls(toolCalls);

        repo.saveInteraction(r);

        List<InteractionRecord> loaded = repo.findBySkillId("sk1");
        assertEquals(1, loaded.size());
        List<ToolCall> loadedTc = loaded.get(0).getToolCalls();
        assertEquals(1, loadedTc.size());
        assertEquals("queryOrder", loadedTc.get(0).getToolName());
        assertEquals("tc-001", loadedTc.get(0).getToolCallId());
        assertTrue(loadedTc.get(0).isSuccess());
    }

    @Test
    void interactionWithPreviousTurns() {
        InteractionRecord r = createSampleRecord("rec-turns", "sess-1", "sk1", "h1");
        List<TurnContext> turns = new ArrayList<>();
        turns.add(new TurnContext("user", "hello"));
        turns.add(new TurnContext("assistant", "hi there"));
        r.setPreviousTurns(turns);

        repo.saveInteraction(r);

        List<InteractionRecord> loaded = repo.findBySkillId("sk1");
        assertEquals(1, loaded.size());
        List<TurnContext> loadedTurns = loaded.get(0).getPreviousTurns();
        assertEquals(2, loadedTurns.size());
        assertEquals("user", loadedTurns.get(0).getRole());
        assertEquals("hello", loadedTurns.get(0).getContent());
        assertEquals("assistant", loadedTurns.get(1).getRole());
    }

    // ======================== 辅助方法 ========================

    private InteractionRecord createSampleRecord(String id, String sessionId, String skillId, String promptHash) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(id);
        r.setSessionId(sessionId);
        r.setTimestamp(System.currentTimeMillis());
        r.setSkillId(skillId);
        r.setSystemPromptHash(promptHash);
        r.setUserInput("hello");
        r.setModelResponse("response text");
        r.setHasToolCalls(true);
        r.setLatencyMs(150L);
        r.setToolCalls(new ArrayList<>());
        r.setPreviousTurns(new ArrayList<>());
        return r;
    }

    private void saveMinimalProfile(String skillId, String groupKey, String name) {
        SkillProfile p = new SkillProfile();
        p.setSkillId(skillId);
        p.setGroupKey(groupKey);
        p.setSkillName(name);
        p.setSkillType(SkillType.TOOL_SKILL);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setTotalRecords(5);
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(new java.util.HashSet<>());
        p.setFingerprint(fp);
        repo.saveSkillProfile(p);
    }
}
