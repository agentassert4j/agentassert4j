package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.BaselineStatus;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 统一重放引擎的端到端流程测试 — 临时 SQLite 上走通
 * 建基线 → 检测/对齐 → 候选落库 → approve/reject/rollback 全链收敛。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class ReplayFlowTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private ByteArrayOutputStream output;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(tempDir.resolve("flow.db").toString());
        repository.initialize();
        output = new ByteArrayOutputStream();
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    private TaskReplayRunner runner() {
        return new TaskReplayRunner(repository, new StubLlmClient(), new DeterministicComparator(ComparatorConfig.defaults()), new InvocationRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true), new PrintStream(output, true), false);
    }

    private InteractionRecord saveRecord(String recordId, String sessionId, long ts, String label, String templateHash, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId(sessionId);
        r.setTimestamp(ts);
        r.setSeq(ts);
        r.setUserInput("查订单 ORD-001");
        r.setInvocationId(label);
        r.setTemplateHash(templateHash);
        r.setInvocationKey("invocation:" + label + ":" + templateHash);
        r.setModelResponse(response);
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        repository.saveInteraction(r);
        return r;
    }

    private void establishAll() {
        new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream(), true), "tester", false, null, null);
    }

    @Nested
    @DisplayName("全 PASS 流程")
    class AllPass {

        @Test
        @DisplayName("同形响应两链 → bare 重放退出码 0")
        void identicalChains_exit0() {
            saveRecord("rec-1", "session-a", 1000L, "order", "hash-a", "same answer");
            saveRecord("rec-2", "session-b", 2000L, "order", "hash-a", "same answer");
            establishAll();

            assertEquals(0, runner().run(null, null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("空库冷启动 → 退出码 2 带录制引导")
        void coldStart_exit2() {
            assertEquals(2, runner().run(null, null, false, false, false, false, null, null));
            assertTrue(output.toString().contains("未录制到任何交互数据"));
        }
    }

    @Nested
    @DisplayName("差异 → 候选 → 裁决收敛全链")
    class DiffAndAdjudicate {

        @Test
        @DisplayName("行为差异 → 退出码 1 + 候选落库 → approve 清候选转正基线")
        void diff_candidate_approve_settles() {
            saveRecord("rec-1", "session-a", 1000L, "order", "hash-a", "{\"answer\":\"old\"}");
            saveRecord("rec-2", "session-b", 2000L, "order", "hash-a", "{\"result\":\"new\"}");
            establishAll();

            assertEquals(1, runner().run(null, null, false, false, false, false, null, null));
            InvocationProfile profile = repository.findInvocationByKey("invocation:order:hash-a");
            assertEquals(BaselineStatus.CANDIDATE, profile.getBaselineStatus());

            new BaselineManager(repository).approve("invocation:order:hash-a", "tester");

            InvocationProfile settled = repository.findInvocationByKey("invocation:order:hash-a");
            assertEquals(BaselineStatus.BASELINE, settled.getBaselineStatus(), "approve 必须清候选转正");
            assertNotNull(settled.getCandidateFingerprint() == null ? settled.getFingerprint() : null);
            assertEquals("v2", settled.getVersionTag());
            // 对齐层陈述的是「最近两次真实执行之间变了」——事实差异在新真实链入账前如实存续
            assertEquals(1, runner().run(null, null, false, false, false, false, null, null));
            assertTrue(output.toString().contains("对齐汇总: PASS 0 | CHANGED 1"));
        }

        @Test
        @DisplayName("拒绝候选 → 保留旧基线，重放仍报差异")
        void reject_keepsBaseline_stillReports() {
            saveRecord("rec-1", "session-a", 1000L, "order", "hash-a", "{\"answer\":\"old\"}");
            saveRecord("rec-2", "session-b", 2000L, "order", "hash-a", "{\"result\":\"new\"}");
            establishAll();
            runner().run(null, null, false, false, false, false, null, null);

            new BaselineManager(repository).reject("invocation:order:hash-a");

            InvocationProfile profile = repository.findInvocationByKey("invocation:order:hash-a");
            assertNull(profile.getCandidateFingerprint(), "reject 必须丢弃候选");
            assertEquals(BaselineStatus.BASELINE, profile.getBaselineStatus());
            assertEquals(1, runner().run(null, null, false, false, false, false, null, null), "行为差异仍在，重放必须继续报告");
        }

        @Test
        @DisplayName("approve 覆盖后 rollback 恢复旧基线，重放恢复通过")
        void approveThenRollback_restoresBaseline() {
            saveRecord("rec-1", "session-a", 1000L, "order", "hash-a", "{\"answer\":\"old\"}");
            saveRecord("rec-2", "session-b", 2000L, "order", "hash-a", "{\"result\":\"new\"}");
            establishAll();
            TaskReplayRunner engine = runner();
            engine.run(null, null, false, false, false, false, null, null);
            DeterministicFingerprint oldBaseline = repository.findInvocationByKey("invocation:order:hash-a").getFingerprint();

            new BaselineManager(repository).approve("invocation:order:hash-a", "tester");
            assertEquals("v2", repository.findInvocationByKey("invocation:order:hash-a").getVersionTag());

            new BaselineManager(repository).rollback("invocation:order:hash-a", "v1");

            InvocationProfile restored = repository.findInvocationByKey("invocation:order:hash-a");
            assertEquals(oldBaseline, restored.getFingerprint(), "回滚必须恢复旧基线指纹");
            assertEquals("hash-a", restored.getTemplateHash(), "回滚必须随归档恢复模板身份");
            assertEquals(1, engine.run(null, null, false, false, false, false, null, null), "回滚后行为差异重新可见");
        }
    }

    @Nested
    @DisplayName("bare 裁决")
    class BareAdjudicate {

        @Test
        @DisplayName("bare approve = 裁决全部待裁决候选并各自转正")
        void bareApprove_adjudicatesAllPending() {
            saveRecord("rec-1", "session-a", 1000L, "order", "hash-a", "{\"answer\":\"old\"}");
            saveRecord("rec-2", "session-b", 2000L, "order", "hash-a", "{\"result\":\"new\"}");
            saveRecord("rec-3", "session-c", 3000L, "poem", "hash-p", "{\"answer\":\"old\"}");
            saveRecord("rec-4", "session-d", 4000L, "poem", "hash-p", "{\"result\":\"new\"}");
            establishAll();
            assertEquals(1, runner().run(null, null, false, false, false, false, null, null));

            ApproveCommand approve = new ApproveCommand();
            approve.db = tempDir.resolve("flow.db").toString();
            approve.out = new PrintStream(new ByteArrayOutputStream(), true);
            approve.err = new PrintStream(new ByteArrayOutputStream(), true);
            assertEquals(0, approve.call());

            assertEquals(BaselineStatus.BASELINE, repository.findInvocationByKey("invocation:order:hash-a").getBaselineStatus());
            assertEquals(BaselineStatus.BASELINE, repository.findInvocationByKey("invocation:poem:hash-p").getBaselineStatus());
        }
    }

    @Nested
    @DisplayName("守卫与恢复")
    class Guards {

        @Test
        @DisplayName("基线语义版本过旧 → 拒绝判定退出码 2；当前语义重建后恢复可用")
        void staleSemantics_forceReestablish_recovers() {
            saveRecord("rec-1", "session-a", 1000L, "order", "hash-a", "same answer");
            saveRecord("rec-2", "session-b", 2000L, "order", "hash-a", "same answer");
            establishAll();
            String key = "invocation:order:hash-a";
            InvocationProfile stale = repository.findInvocationByKey(key);
            stale.setAlgoVersion("det-v0");
            repository.saveInvocationProfile(stale);

            assertEquals(2, runner().run(null, null, false, false, false, false, null, null));
            assertTrue(output.toString().contains("判定语义版本不一致"));

            new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream(), true), "tester", true, null, null);
            assertEquals(JudgmentSemantics.VERSION, repository.findInvocationByKey(key).getAlgoVersion());
            assertEquals(0, runner().run(null, null, false, false, false, false, null, null));
        }

        @Test
        @DisplayName("绕过录制管道的直插记录（派生列空缺）现场重派生、照常对齐")
        void rawInsertedRecords_rederiveAndAlign() {
            InteractionRecord bare = new InteractionRecord();
            bare.setRecordId("raw-1");
            bare.setSessionId("session-a");
            bare.setTimestamp(1000L);
            bare.setUserInput("查订单 ORD-001");
            bare.setInvocationId("order");
            bare.setTemplateHash("hash-a");
            bare.setModelResponse("{\"result\":\"ok\"}");
            repository.saveInteraction(bare);
            InteractionRecord bare2 = new InteractionRecord();
            bare2.setRecordId("raw-2");
            bare2.setSessionId("session-b");
            bare2.setTimestamp(2000L);
            bare2.setUserInput("查订单 ORD-001");
            bare2.setInvocationId("order");
            bare2.setTemplateHash("hash-a");
            bare2.setModelResponse("{\"result\":\"ok\"}");
            repository.saveInteraction(bare2);

            assertEquals(0, runner().run(null, null, false, false, false, false, null, null), "键派生列空缺由解析器现算兜底");
            assertEquals("invocation:order:hash-a", InvocationResolver.resolve(repository.findBySessionId("session-a").get(0)).getInvocationKey());
        }

        @Test
        @DisplayName("未声明形状组（模板锚点）一等公民：建档与对齐全程可达")
        void undeclaredShapeGroup_firstClass() {
            saveRecord("rec-1", "session-a", 1000L, null, "hash-a", "{\"result\":\"ok\"}");
            saveRecord("rec-2", "session-b", 2000L, null, "hash-a", "{\"result\":\"ok\"}");
            establishAll();

            assertEquals(0, runner().run(null, null, false, false, false, false, null, null));
            assertNotNull(repository.findInvocationByKey("template:hash-a"), "未声明键必须建档");
        }
    }

    static class StubLlmClient implements LlmClient {
        @Override
        public io.github.agentassert4j.model.LlmResponse chat(io.github.agentassert4j.model.LlmRequest request, long timeoutMs) {
            throw new UnsupportedOperationException("统一引擎缺省路径零 LLM 调用");
        }

        @Override
        public String name() {
            return "stub-model";
        }

        @Override
        public boolean isAvailable() {
            return false;
        }
    }
}
