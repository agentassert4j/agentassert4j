package io.github.agentassert4j.storage.sqlite;

import io.github.agentassert4j.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

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
        assertEquals("hash-abc", loaded.getTemplateHash());
        assertEquals("hello", loaded.getUserInput());
        assertEquals("response text", loaded.getModelResponse());
        assertTrue(loaded.isHasToolCalls());
        assertEquals(150L, loaded.getLatencyMs());
    }

    @Test
    void findByTemplateHash() {
        repo.saveInteraction(createSampleRecord("r1", "s1", "sk1", "hash-xxx"));
        repo.saveInteraction(createSampleRecord("r2", "s2", "sk2", "hash-xxx"));
        repo.saveInteraction(createSampleRecord("r3", "s3", "sk3", "hash-yyy"));

        List<InteractionRecord> results = repo.findByTemplateHash("hash-xxx");
        assertEquals(2, results.size());
    }

    @Test
    void findSkillIdsByTemplateHash() {
        repo.saveInteraction(createSampleRecord("r1", "s1", "sk-alpha", "hash-111"));
        repo.saveInteraction(createSampleRecord("r2", "s2", "sk-beta", "hash-111"));
        repo.saveInteraction(createSampleRecord("r3", "s3", "sk-gamma", "hash-222"));

        Set<String> skillIds = repo.findSkillIdsByTemplateHash("hash-111");
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
        repo.saveTemplateText("hash-sha256-abc", "You are a helpful assistant.");
        String loaded = repo.findTemplateText("hash-sha256-abc");
        assertEquals("You are a helpful assistant.", loaded);
    }

    @Test
    void findTemplateText_notFound() {
        assertNull(repo.findTemplateText("nonexistent"));
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

    @Test
    void specialCharacters_roundTripUnescaped() {
        // 特殊字符全链路往返：写入时 escape、读回时反转义，内容必须原样
        String resultWithSpecials = "第一行\n第二行 \"引号\" 制表\t符 反斜杠\\ 结尾";
        String argValueWithNewline = "参数值\n含换行";

        InteractionRecord r = createSampleRecord("esc-1", "sess-e", "sk-e", "h-e");
        ToolCall tc = new ToolCall();
        tc.setToolName("queryOrder");
        tc.setToolCallId("tc-e1");
        tc.setSuccess(true);
        tc.setResult(resultWithSpecials);
        Map<String, Object> args = new HashMap<>();
        args.put("note", argValueWithNewline);
        tc.setArguments(args);
        r.setToolCalls(new ArrayList<>(List.of(tc)));

        repo.saveInteraction(r);

        List<ToolCall> loadedTc = repo.findBySkillId("sk-e").get(0).getToolCalls();
        assertEquals(resultWithSpecials, loadedTc.get(0).getResult(),
                "tool result 读回必须与原文一致（真实换行/引号/反斜杠），不得残留转义序列");
        assertEquals(argValueWithNewline, loadedTc.get(0).getArguments().get("note"),
                "arguments 值读回同样必须反转义");

        List<TurnContext> turns = new ArrayList<>();
        turns.add(new TurnContext("user", "内容\"引号\"\n换行"));
        InteractionRecord r2 = createSampleRecord("esc-2", "sess-e", "sk-e", "h-e");
        r2.setPreviousTurns(turns);
        repo.saveInteraction(r2);
        List<InteractionRecord> all = repo.findBySkillId("sk-e");
        assertEquals(2, all.size(), "esc-2 是新 record_id，必须正常落库");
        TurnContext loadedTurn = all.stream()
                .filter(x -> "esc-2".equals(x.getRecordId()))
                .findFirst().orElseThrow()
                .getPreviousTurns().get(0);
        assertEquals("内容\"引号\"\n换行", loadedTurn.getContent(),
                "previousTurns 内容读回必须反转义");
    }

    @Test
    void schemaVersionStamped() throws Exception {
        try (Statement stmt = repo.getConnection().createStatement();
             ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
            rs.next();
            assertEquals(Schema.USER_VERSION, rs.getInt(1), "initialize 后必须盖戳 user_version");
        }
    }

    @Test
    void reinitializeIsIdempotent() {
        assertDoesNotThrow(() -> {
            repo.close();
            repo.initialize();
        }, "重复 initialize（已盖戳版本）不得重建或报错");
    }

    @Test
    void futureSchemaVersionRejected() throws Exception {
        // :memory: 库连接关闭即消失，版本戳必须落在文件库上验证
        Path db = Files.createTempFile("agentassert4j-future", ".db");
        try {
            SqliteStorageRepository futureRepo = new SqliteStorageRepository(db.toString());
            futureRepo.initialize();
            try (Statement stmt = futureRepo.getConnection().createStatement()) {
                stmt.execute("PRAGMA user_version = " + (Schema.USER_VERSION + 1));
            }
            futureRepo.close();

            assertThrows(RuntimeException.class, futureRepo::initialize,
                    "高于支持版本的库必须被拒绝，不得静默打开");
            futureRepo.close();
        } finally {
            Files.deleteIfExists(db);
        }
    }

    @Test
    void captureFidelityColumnsRoundTrip() {
        InteractionRecord r = createSampleRecord("rec-v1", "sess-v1", "sk-v1", "hash-v1");
        r.setSeq(42L);
        r.setTemplateId("order-extract");
        r.setVariablesFingerprint("var-fp-1");
        r.setApiProtocol("openai-chat");
        r.setProvider("deepseek");
        r.setModel("deepseek-chat");
        r.setServedModel("deepseek-chat-v3-0324");
        r.setEndpoint("https://api.deepseek.com");
        r.setToolsDefinition("[{\"type\":\"function\",\"function\":{\"name\":\"queryOrder\"}}]");
        r.setSamplingParams("{\"temperature\":0.7,\"max_tokens\":1024}");
        r.setModelRequestRaw("{\"model\":\"deepseek-chat\",\"messages\":[]}");
        r.setFinishReason("tool_calls");
        r.setModelResponseRaw("{\"id\":\"resp-1\",\"choices\":[]}");
        r.setCacheReadTokens(1024);
        r.setCacheWriteTokens(null);
        r.setReasoningTokens(512);
        r.setUsageRaw("{\"prompt_tokens\":2048,\"completion_tokens\":100}");
        r.setTtftMs(120L);
        r.setCostUsd(0.0021);
        r.setGroupKey("queryOrder[orderId:string]");
        r.setMetadata("{\"agent.role\":\"build\"}");
        r.setRecorderVersion("1.0.0-SNAPSHOT");
        repo.saveInteraction(r);

        InteractionRecord loaded = repo.findBySkillId("sk-v1").get(0);
        assertEquals(42L, loaded.getSeq());
        assertEquals("order-extract", loaded.getTemplateId());
        assertEquals("var-fp-1", loaded.getVariablesFingerprint());
        assertEquals("openai-chat", loaded.getApiProtocol());
        assertEquals("deepseek", loaded.getProvider());
        assertEquals("deepseek-chat", loaded.getModel());
        assertEquals("deepseek-chat-v3-0324", loaded.getServedModel());
        assertEquals("https://api.deepseek.com", loaded.getEndpoint());
        assertTrue(loaded.getToolsDefinition().contains("queryOrder"));
        assertTrue(loaded.getSamplingParams().contains("temperature"));
        assertTrue(loaded.getModelRequestRaw().contains("messages"));
        assertEquals("tool_calls", loaded.getFinishReason());
        assertTrue(loaded.getModelResponseRaw().contains("choices"));
        assertEquals(Integer.valueOf(1024), loaded.getCacheReadTokens());
        assertNull(loaded.getCacheWriteTokens(), "供应商不报告的缓存写必须保持 NULL");
        assertEquals(Integer.valueOf(512), loaded.getReasoningTokens());
        assertTrue(loaded.getUsageRaw().contains("prompt_tokens"));
        assertEquals(Long.valueOf(120L), loaded.getTtftMs());
        assertEquals(Double.valueOf(0.0021), loaded.getCostUsd(), 1e-9);
        assertEquals("queryOrder[orderId:string]", loaded.getGroupKey());
        assertTrue(loaded.getMetadata().contains("agent.role"));
        assertEquals("1.0.0-SNAPSHOT", loaded.getRecorderVersion());
    }

    @Test
    void duplicateRecordIdIgnored() {
        InteractionRecord r = createSampleRecord("dup-1", "s1", "sk1", "h1");
        repo.saveInteraction(r);
        r.setModelResponse("changed response");
        repo.saveInteraction(r); // 崩溃重放双写场景：同 record_id 不得覆盖已有历史

        List<InteractionRecord> results = repo.findBySkillId("sk1");
        assertEquals(1, results.size(), "只追加历史表：record_id 冲突必须静默跳过");
        assertEquals("response text", results.get(0).getModelResponse(), "已落库的原始行不得被重放覆盖");
    }

    @Test
    void skillProfileGovernanceColumnsRoundTrip() {
        SkillProfile p = new SkillProfile();
        p.setSkillId("sk-gov");
        p.setGroupKey("gov-key");
        p.setSkillName("gov");
        p.setSkillType(SkillType.TOOL_SKILL);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v2");
        p.setAlgoVersion("1.0");
        p.setParamSignature("orderId:string");
        p.setSampleCount(7);
        p.setApprovedBy("axy-yxa");
        p.setApprovedAt(1735689600000L);
        p.setTotalRecords(10);
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(new java.util.HashSet<>());
        p.setFingerprint(fp);
        repo.saveSkillProfile(p);

        SkillProfile loaded = repo.findSkillByGroupKey("gov-key");
        assertEquals("1.0", loaded.getAlgoVersion());
        assertEquals("orderId:string", loaded.getParamSignature());
        assertEquals(Integer.valueOf(7), loaded.getSampleCount());
        assertEquals("axy-yxa", loaded.getApprovedBy());
        assertEquals(Long.valueOf(1735689600000L), loaded.getApprovedAt());
    }

    private InteractionRecord createSampleRecord(String id, String sessionId, String skillId, String promptHash) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(id);
        r.setSessionId(sessionId);
        r.setTimestamp(System.currentTimeMillis());
        r.setSkillId(skillId);
        r.setTemplateHash(promptHash);
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
