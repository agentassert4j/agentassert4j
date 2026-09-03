package io.github.agentassert4j.storage.sqlite;

import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.StorageException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SqliteStorageRepository 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
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
    void nullModelResponse_withToolCalls_savesAndReloads() {
        // 纯工具调用的响应没有文本内容（content=null），必须可录入可重读
        InteractionRecord r = createSampleRecord("rec-toolonly", "session-1", "skill-1", "hash-abc");
        r.setModelResponse(null);

        repo.saveInteraction(r);

        InteractionRecord loaded = repo.findByInvocationId("skill-1").get(0);
        assertNull(loaded.getModelResponse());
        assertTrue(loaded.isHasToolCalls());
    }

    @Test
    void saveAndFindInteraction() {
        InteractionRecord r = createSampleRecord("rec-1", "session-1", "skill-1", "hash-abc");
        repo.saveInteraction(r);

        List<InteractionRecord> results = repo.findByInvocationId("skill-1");
        assertEquals(1, results.size());
        InteractionRecord loaded = results.get(0);
        assertEquals("rec-1", loaded.getRecordId());
        assertEquals("session-1", loaded.getSessionId());
        assertEquals("skill-1", loaded.getInvocationId());
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
    void skeletonHash_roundTrip() {
        // 骨架哈希投影落列并映射回——落库记录重算调用点键的骨架凭据
        InteractionRecord r = createSampleRecord("rec-skl", "s1", "sk-1", "hash-skl");
        r.setTemplateSkeleton("客服助手。当前日期：{{date}}");
        r.setSkeletonHash("01a6620a2e2419bc01d23b63d658872d0d100544d3e073a3aab5bcf059665a27");
        repo.saveInteraction(r);

        InteractionRecord loaded = repo.findByInvocationId("sk-1").get(0);
        assertEquals("01a6620a2e2419bc01d23b63d658872d0d100544d3e073a3aab5bcf059665a27", loaded.getSkeletonHash());
    }

    @Test
    void skeletonHash_nullStaysNull() {
        // 无骨架声明：列空、投影空——重算键退化到既有锚点，行为不变
        InteractionRecord r = createSampleRecord("rec-noskl", "s1", "sk-2", "hash-noskl");
        repo.saveInteraction(r);

        InteractionRecord loaded = repo.findByInvocationId("sk-2").get(0);
        assertNull(loaded.getSkeletonHash());
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
    void saveAndFindInvocationProfile() {
        InvocationProfile p = new InvocationProfile();
        p.setLabel("order-flow");
        p.setInvocationKey("invocation:order-flow:tmpl-1");
        p.setTemplateHash("tmpl-1");
        p.setInvocationName("queryOrder");
        p.setInvocationType(InvocationType.TOOL);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v1.0");
        p.setTotalRecords(42);

        DeterministicFingerprint fp = new DeterministicFingerprint();
        Set<String> tools = new HashSet<>();
        tools.add("queryOrder");
        fp.setToolCallSet(tools);
        p.setFingerprint(fp);

        repo.saveInvocationProfile(p);

        InvocationProfile loaded = repo.findInvocationByKey("invocation:order-flow:tmpl-1");
        assertNotNull(loaded);
        assertEquals("order-flow", loaded.getLabel());
        assertEquals("tmpl-1", loaded.getTemplateHash());
        assertEquals("queryOrder", loaded.getInvocationName());
        assertEquals(InvocationType.TOOL, loaded.getInvocationType());
        assertEquals(BaselineStatus.BASELINE, loaded.getBaselineStatus());
        assertEquals("v1.0", loaded.getVersionTag());
        assertEquals(42, loaded.getTotalRecords());
        assertNotNull(loaded.getFingerprint());
        assertTrue(loaded.getFingerprint().getToolCallSet().contains("queryOrder"));
    }

    @Test
    void findAllInvocations() {
        saveMinimalProfile("sk1", "gk1", "Tool1");
        saveMinimalProfile("sk2", "gk2", "Tool2");

        List<InvocationProfile> all = repo.findAllInvocations();
        assertEquals(2, all.size());
    }

    @Test
    void saveAndFindPromptText() {
        repo.saveTemplateText("hash-sha256-abc", "You are a helpful assistant.");
        String loaded = repo.findTemplateText("hash-sha256-abc");
        assertEquals("You are a helpful assistant.", loaded);
    }

    @Test
    void savePromptText_sameHash_firstWriteWins() {
        // 模板文本由内容哈希定键：同 hash 重复写入必须首写为准（幂等），
        // 覆盖写会让「同 hash 不同文本」在物理上不可能成立
        repo.saveTemplateText("hash-dup", "first version");
        repo.saveTemplateText("hash-dup", "second version");
        assertEquals("first version", repo.findTemplateText("hash-dup"));
    }

    @Test
    void saveInteraction_carriesTemplateTextIntoPromptTexts() {
        // 模板原文随记录落库的捕获契约：templateHash+templateText 双全时，
        // 交互写路径顺带把原文归档进 prompt_texts（status 巡检展示的数据源）
        InteractionRecord r = createSampleRecord("rec-tpl-1", "session-tpl", "skill-tpl", "hash-from-capture");
        r.setTemplateText("你是订单查询助手。");
        repo.saveInteraction(r);
        assertEquals("你是订单查询助手。", repo.findTemplateText("hash-from-capture"));

        // 缺一不可：有 hash 无文本时不落任何行
        InteractionRecord half = createSampleRecord("rec-tpl-2", "session-tpl", "skill-tpl", "hash-orphan");
        repo.saveInteraction(half);
        assertNull(repo.findTemplateText("hash-orphan"));
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
        Set<String> tools = new HashSet<>();
        tools.add("toolX");
        fp.setToolCallSet(tools);

        ArchivedTemplateVersion archived = new ArchivedTemplateVersion();
        archived.setInvocationKey("invocation:order-flow:tmpl-1");
        archived.setFingerprint(fp);
        archived.setVersionTag("v1.0");
        archived.setAlgoVersion("det-v1");
        archived.setApprovedBy("tester");
        archived.setApprovedAt(123L);
        repo.archiveTemplateVersion(archived);

        ArchivedTemplateVersion loaded = repo.findArchivedVersion("invocation:order-flow:tmpl-1", "v1.0");
        assertNotNull(loaded);
        assertEquals("invocation:order-flow:tmpl-1", loaded.getInvocationKey());
        assertEquals("v1.0", loaded.getVersionTag());
        assertNotNull(loaded.getFingerprint());
        assertTrue(loaded.getFingerprint().getToolCallSet().contains("toolX"));
        // 治理三列与归档时间戳写读对称
        assertEquals("det-v1", loaded.getAlgoVersion());
        assertEquals("tester", loaded.getApprovedBy());
        assertEquals(Long.valueOf(123L), loaded.getApprovedAt());
        assertTrue(loaded.getArchivedAt() > 0);
    }

    @Test
    void archiveAndFindBaseline_nullApprovedAtRoundTripsAsNull() {
        // 可空治理列的写读对称：approvedAt=null 绑定与读回的 wasNull 两侧都要钉住
        ArchivedTemplateVersion archived = new ArchivedTemplateVersion();
        archived.setInvocationKey("invocation:null-at:tmpl-1");
        archived.setVersionTag("v1");
        archived.setAlgoVersion("det-v1");
        archived.setApprovedBy("alice");
        archived.setApprovedAt(null);
        repo.archiveTemplateVersion(archived);

        ArchivedTemplateVersion loaded = repo.findArchivedVersion("invocation:null-at:tmpl-1", "v1");
        assertNotNull(loaded);
        assertEquals("alice", loaded.getApprovedBy());
        assertNull(loaded.getApprovedAt());
    }

    @Test
    void findArchivedTemplateVersion_notFound() {
        assertNull(repo.findArchivedVersion("nonexistent", "v99"));
    }

    @Test
    void findArchivedVersions_listsByInvocationLatestFirst() {
        // 列表查询是 rollback 版本发现的数据源：只含目标调用点，且最近归档在前
        ArchivedTemplateVersion older = new ArchivedTemplateVersion();
        older.setInvocationKey("invocation:order-flow:tmpl-1");
        older.setVersionTag("v1");
        repo.archiveTemplateVersion(older);
        ArchivedTemplateVersion newer = new ArchivedTemplateVersion();
        newer.setInvocationKey("invocation:order-flow:tmpl-1");
        newer.setVersionTag("v2");
        repo.archiveTemplateVersion(newer);
        ArchivedTemplateVersion otherSkill = new ArchivedTemplateVersion();
        otherSkill.setInvocationKey("invocation:other-flow:tmpl-1");
        otherSkill.setVersionTag("v1");
        repo.archiveTemplateVersion(otherSkill);

        List<ArchivedTemplateVersion> rows = repo.findArchivedVersions("invocation:order-flow:tmpl-1");
        assertEquals(2, rows.size());
        assertEquals("v2", rows.get(0).getVersionTag(), "最近归档的行必须排在最前");
        assertEquals("v1", rows.get(1).getVersionTag());
        assertTrue(repo.findArchivedVersions("invocation:no-such:tmpl-1").isEmpty());
    }

    @Test
    void findArchivedTemplateVersion_duplicateTag_latestArchiveWins() {
        // 同 skill 同版本多行归档时最近归档者胜（生产实现的 tiebreaker 契约）
        ArchivedTemplateVersion first = new ArchivedTemplateVersion();
        first.setInvocationKey("invocation:dup-flow:tmpl-1");
        first.setVersionTag("v1");
        first.setApprovedBy("older");
        first.setApprovedAt(1L);
        repo.archiveTemplateVersion(first);
        ArchivedTemplateVersion second = new ArchivedTemplateVersion();
        second.setInvocationKey("invocation:dup-flow:tmpl-1");
        second.setVersionTag("v1");
        second.setApprovedBy("newer");
        second.setApprovedAt(2L);
        repo.archiveTemplateVersion(second);

        assertEquals("newer", repo.findArchivedVersion("invocation:dup-flow:tmpl-1", "v1").getApprovedBy());
    }

    @Test
    void saveInteractions_runtimeExceptionMidBatch_rollsBackWholeBatch() {
        // RuntimeException 路径必须先显式回滚：finally 恢复 autoCommit 对未决事务
        // 是隐式提交，不回滚就会把半批数据落盘、破坏整批原子性
        SqliteStorageRepository failing = new SqliteStorageRepository(":memory:") {
            private int calls = 0;

            @Override
            public synchronized void saveInteraction(InteractionRecord r) {
                if (++calls == 2) {
                    throw new IllegalStateException("second record exploded");
                }
                super.saveInteraction(r);
            }
        };
        failing.initialize();
        try {
            List<InteractionRecord> batch = Arrays.asList(createSampleRecord("rec-atom-1", "s-atom", "sk-atom", "h1"), createSampleRecord("rec-atom-2", "s-atom", "sk-atom", "h1"), createSampleRecord("rec-atom-3", "s-atom", "sk-atom", "h1"));

            assertThrows(IllegalStateException.class, () -> failing.saveInteractions(batch));
            assertTrue(failing.findByInvocationId("sk-atom").isEmpty(), "半批不得落盘——失败批次必须整批回滚");
        } finally {
            failing.close();
        }
    }

    @Test
    void saveInteractions_concurrentBatches_allRowsPersisted() throws Exception {
        // 多线程 flush 汇聚同一连接：写路径串行化后不得丢批、不得交织回滚
        int threads = 4;
        int batchesPerThread = 25;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int b = 0; b < batchesPerThread; b++) {
                    List<InteractionRecord> batch = new ArrayList<>();
                    for (int i = 0; i < 5; i++) {
                        batch.add(createSampleRecord("rec-c" + tid + "-" + b + "-" + i, "s-c" + tid, "sk-c" + tid, "h" + tid));
                    }
                    repo.saveInteractions(batch);
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();

        int total = 0;
        for (int t = 0; t < threads; t++) {
            total += repo.findByInvocationId("sk-c" + t).size();
        }
        assertEquals(threads * batchesPerThread * 5, total, "并发批量写入必须全量落库（缺行=事务交织丢批）");
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

        List<InteractionRecord> loaded = repo.findByInvocationId("sk1");
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

        List<InteractionRecord> loaded = repo.findByInvocationId("sk1");
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
        r.setToolCalls(new ArrayList<>(Collections.singletonList(tc)));

        repo.saveInteraction(r);

        List<ToolCall> loadedTc = repo.findByInvocationId("sk-e").get(0).getToolCalls();
        assertEquals(resultWithSpecials, loadedTc.get(0).getResult(), "tool result 读回必须与原文一致（真实换行/引号/反斜杠），不得残留转义序列");
        assertEquals(argValueWithNewline, loadedTc.get(0).getArguments().get("note"), "arguments 值读回同样必须反转义");

        List<TurnContext> turns = new ArrayList<>();
        turns.add(new TurnContext("user", "内容\"引号\"\n换行"));
        InteractionRecord r2 = createSampleRecord("esc-2", "sess-e", "sk-e", "h-e");
        r2.setPreviousTurns(turns);
        repo.saveInteraction(r2);
        List<InteractionRecord> all = repo.findByInvocationId("sk-e");
        assertEquals(2, all.size(), "esc-2 是新 record_id，必须正常落库");
        TurnContext loadedTurn = all.stream().filter(x -> "esc-2".equals(x.getRecordId())).findFirst().get().getPreviousTurns().get(0);
        assertEquals("内容\"引号\"\n换行", loadedTurn.getContent(), "previousTurns 内容读回必须反转义");
    }

    @Test
    void schemaVersionStamped() throws Exception {
        try (Statement stmt = repo.getConnection().createStatement(); ResultSet rs = stmt.executeQuery("PRAGMA user_version")) {
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

            assertThrows(RuntimeException.class, futureRepo::initialize, "高于支持版本的库必须被拒绝，不得静默打开");
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
        r.setSkeletonHash("01a6620a2e2419bc01d23b63d658872d0d100544d3e073a3aab5bcf059665a27");
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
        r.setInvocationKey("queryOrder[orderId:string]");
        r.setMetadata("{\"agent.role\":\"build\"}");
        r.setRecorderVersion("1.0.0-SNAPSHOT");
        repo.saveInteraction(r);

        InteractionRecord loaded = repo.findByInvocationId("sk-v1").get(0);
        assertEquals(42L, loaded.getSeq());
        assertEquals("order-extract", loaded.getTemplateId());
        assertEquals("01a6620a2e2419bc01d23b63d658872d0d100544d3e073a3aab5bcf059665a27", loaded.getSkeletonHash());
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
        assertEquals("queryOrder[orderId:string]", loaded.getInvocationKey());
        assertTrue(loaded.getMetadata().contains("agent.role"));
        assertEquals("1.0.0-SNAPSHOT", loaded.getRecorderVersion());
    }

    @Test
    void duplicateRecordIdIgnored() {
        InteractionRecord r = createSampleRecord("dup-1", "s1", "sk1", "h1");
        repo.saveInteraction(r);
        r.setModelResponse("changed response");
        repo.saveInteraction(r); // 崩溃重放双写场景：同 record_id 不得覆盖已有历史

        List<InteractionRecord> results = repo.findByInvocationId("sk1");
        assertEquals(1, results.size(), "只追加历史表：record_id 冲突必须静默跳过");
        assertEquals("response text", results.get(0).getModelResponse(), "已落库的原始行不得被重放覆盖");
    }

    @Test
    void invocationProfileGovernanceColumnsRoundTrip() {
        InvocationProfile p = new InvocationProfile();
        p.setLabel("gov-flow");
        p.setInvocationKey("gov-key");
        p.setInvocationName("gov");
        p.setInvocationType(InvocationType.TOOL);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v2");
        p.setAlgoVersion("1.0");
        p.setParamSignature("orderId:string");
        p.setApprovedBy("axy-yxa");
        p.setApprovedAt(1735689600000L);
        p.setTotalRecords(10);
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(new HashSet<>());
        p.setFingerprint(fp);
        repo.saveInvocationProfile(p);

        InvocationProfile loaded = repo.findInvocationByKey("gov-key");
        assertEquals("1.0", loaded.getAlgoVersion());
        assertEquals("orderId:string", loaded.getParamSignature());
        assertEquals("axy-yxa", loaded.getApprovedBy());
        assertEquals(Long.valueOf(1735689600000L), loaded.getApprovedAt());
    }

    private InteractionRecord createSampleRecord(String id, String sessionId, String invocationId, String promptHash) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(id);
        r.setSessionId(sessionId);
        r.setTimestamp(System.currentTimeMillis());
        r.setInvocationId(invocationId);
        r.setTemplateHash(promptHash);
        r.setUserInput("hello");
        r.setModelResponse("response text");
        r.setHasToolCalls(true);
        r.setLatencyMs(150L);
        r.setToolCalls(new ArrayList<>());
        r.setPreviousTurns(new ArrayList<>());
        return r;
    }

    private void saveMinimalProfile(String invocationId, String invocationKey, String name) {
        InvocationProfile p = new InvocationProfile();
        p.setLabel(invocationId);
        p.setInvocationKey(invocationKey);
        p.setInvocationName(name);
        p.setInvocationType(InvocationType.TOOL);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setTotalRecords(5);
        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(new HashSet<>());
        p.setFingerprint(fp);
        repo.saveInvocationProfile(p);
    }

    @Test
    void storageFailure_throwsStorageException_neverSwallowed() throws Exception {
        // 底层连接失效（库锁死/磁盘满的等价模拟）：读写必须上抛，不得伪装成成功或空结果
        repo.getConnection().close();

        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-closed");
        r.setSessionId("s");
        r.setTimestamp(1L);
        r.setInvocationId("sk");
        r.setModelResponse("m");

        assertThrows(StorageException.class, () -> repo.saveInteraction(r));
        assertThrows(StorageException.class, () -> repo.saveInteractions(Collections.singletonList(r)));
        assertThrows(StorageException.class, () -> repo.findAllSessionIds());
        assertThrows(StorageException.class, () -> repo.findAllInvocations());
        assertThrows(StorageException.class, () -> repo.saveInvocationProfile(new InvocationProfile()));
        assertThrows(StorageException.class, () -> repo.loadGraph());

        // tearDown 会对已关连接再 close，保持幂等
    }

    @Test
    void jsonColumns_roundTripHostileContent() {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-nasty");
        r.setSessionId("sess-nasty");
        r.setTimestamp(42L);
        r.setInvocationId("sk");
        r.setUserInput("引号\" 反斜杠\\ 换行\n 回车\r 制表\t 控制\u0001符 emoji 😀");
        r.setModelResponse("line1\nline2 \"quoted\" \\tail\\");
        r.setTurnIndex(1);

        ToolCall tc = new ToolCall();
        tc.setToolName("query\"Order");
        tc.setToolCallId("call\\1");
        tc.setSuccess(true);
        tc.setResult("多行\n结果 \"引号\" \\\\尾\\\\");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("id", "A\"B");
        args.put("count", 3);
        args.put("flag", true);
        tc.setArguments(args);
        Map<String, String> argTypes = new LinkedHashMap<>();
        argTypes.put("id", "String");
        argTypes.put("count", "Integer");
        tc.setArgTypes(argTypes);
        r.setToolCalls(Collections.singletonList(tc));
        r.setHasToolCalls(true);

        TurnContext t1 = new TurnContext("user", "问题\"一\"\n");
        TurnContext t2 = new TurnContext("tool", "结果\\两");
        t2.setToolCallId("call\\1");
        t2.setToolName("tool\"");
        r.setPreviousTurns(Arrays.asList(t1, t2));

        repo.saveInteraction(r);

        List<InteractionRecord> found = repo.findBySessionId("sess-nasty");
        assertEquals(1, found.size());
        InteractionRecord back = found.get(0);

        assertEquals(r.getUserInput(), back.getUserInput());
        assertEquals(r.getModelResponse(), back.getModelResponse());
        assertEquals(1, back.getToolCalls().size());
        ToolCall b = back.getToolCalls().get(0);
        assertEquals("query\"Order", b.getToolName());
        assertEquals("call\\1", b.getToolCallId());
        assertTrue(b.isSuccess());
        assertEquals(tc.getResult(), b.getResult());
        assertEquals("A\"B", b.getArguments().get("id"));
        assertEquals(3, ((Number) b.getArguments().get("count")).intValue());
        assertEquals(Boolean.TRUE, b.getArguments().get("flag"));
        assertEquals("String", b.getArgTypes().get("id"));
        assertEquals("Integer", b.getArgTypes().get("count"));

        assertEquals(2, back.getPreviousTurns().size());
        assertEquals("问题\"一\"\n", back.getPreviousTurns().get(0).getContent());
        assertEquals("结果\\两", back.getPreviousTurns().get(1).getContent());
        assertEquals("call\\1", back.getPreviousTurns().get(1).getToolCallId());
        assertEquals("tool\"", back.getPreviousTurns().get(1).getToolName());
    }

    @Test
    void fingerprintColumns_roundTripHostileContent() {
        InvocationProfile p = new InvocationProfile();
        p.setLabel("fp-flow");
        p.setInvocationKey("invocation:fp-flow:tmpl-1");
        p.setInvocationName("n");
        p.setInvocationType(InvocationType.TOOL);
        p.setBaselineStatus(BaselineStatus.CANDIDATE);
        p.setVersionTag("v1");

        DeterministicFingerprint fp = new DeterministicFingerprint();
        fp.setToolCallSet(new LinkedHashSet<>(Arrays.asList("tool\"A", "tool\\B", "工具\nC")));
        Map<String, String> paramTypes = new LinkedHashMap<>();
        paramTypes.put("k\"1", "String");
        fp.setToolParamTypes(paramTypes);
        fp.setOutputContentType("text/plain");
        fp.setOutputFieldPaths(new LinkedHashSet<>(Collections.singletonList("a.b\"c")));
        fp.setTextLengthMagnitude(3);
        fp.setRequiredKeywords(new LinkedHashSet<>(Arrays.asList("必\"需", "关键字\n")));
        fp.setRegexPatterns(Collections.singletonList(new RegexPattern("^\\d+\"$", "描述\"一")));
        fp.setDeclaredBehaviors(new LinkedHashSet<>(Collections.singletonList("行为\"X")));
        fp.setHasError(false);
        p.setFingerprint(fp);

        DeterministicFingerprint candidate = new DeterministicFingerprint();
        candidate.setTextLengthMagnitude(1);
        p.setCandidateFingerprint(candidate);

        repo.saveInvocationProfile(p);

        InvocationProfile back = repo.findInvocationByKey("invocation:fp-flow:tmpl-1");
        assertNotNull(back);
        DeterministicFingerprint bf = back.getFingerprint();
        assertEquals(fp.getToolCallSet(), bf.getToolCallSet());
        assertEquals("String", bf.getToolParamTypes().get("k\"1"));
        assertEquals("text/plain", bf.getOutputContentType());
        assertEquals(fp.getOutputFieldPaths(), bf.getOutputFieldPaths());
        assertEquals(3, bf.getTextLengthMagnitude());
        assertEquals(fp.getRequiredKeywords(), bf.getRequiredKeywords());
        assertEquals(1, bf.getRegexPatterns().size());
        assertEquals("^\\d+\"$", bf.getRegexPatterns().get(0).getPattern());
        assertEquals("描述\"一", bf.getRegexPatterns().get(0).getDescription());
        assertEquals(fp.getDeclaredBehaviors(), bf.getDeclaredBehaviors());
        assertFalse(bf.isHasError());

        assertNotNull(back.getCandidateFingerprint());
        assertEquals(1, back.getCandidateFingerprint().getTextLengthMagnitude());
    }

    @Test
    void fingerprintColumn_nullRoundTripsAsNull() {
        // fingerprint 列 NOT NULL：null 指纹以 "{}" 落库，读侧映射回 null
        InvocationProfile p = new InvocationProfile();
        p.setLabel("null-flow");
        p.setInvocationKey("invocation:null-flow:tmpl-1");
        p.setInvocationName("n");
        p.setInvocationType(InvocationType.TOOL);
        p.setBaselineStatus(BaselineStatus.BASELINE);
        repo.saveInvocationProfile(p);

        InvocationProfile back = repo.findInvocationByKey("invocation:null-flow:tmpl-1");
        assertNull(back.getFingerprint());
        assertNull(back.getCandidateFingerprint());
    }
}
