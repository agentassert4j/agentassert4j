package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.DeterministicSkillGrouper;
import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.LlmApiException;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.LlmTimeoutException;
import io.github.agentassert4j.storage.sqlite.SqliteStorageRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReplayRunner 的端到端流程测试 — 临时 SQLite 上走通
 * 建基线 → 重放 → 候选落库 → approve/reject 全链。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
class ReplayFlowTest {

    @TempDir
    Path tempDir;

    private SqliteStorageRepository repository;
    private StubLlmClient stubClient;
    private ByteArrayOutputStream output;
    private ReplayRunner runner;

    @BeforeEach
    void setUp() {
        repository = new SqliteStorageRepository(tempDir.resolve("flow.db").toString());
        repository.initialize();
        stubClient = new StubLlmClient();
        output = new ByteArrayOutputStream();
        runner = new ReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new SkillRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(output, true));
    }

    @AfterEach
    void tearDown() {
        if (repository != null) {
            repository.close();
        }
    }

    private void seedOneSkill() {
        repository.saveInteraction(makeRecord("rec-1", "skill-1", 1000L, "same answer"));
        repository.saveInteraction(makeRecord("rec-2", "skill-1", 2000L, "same answer"));
    }

    private InteractionRecord makeRecord(String recordId, String skillId, long timestamp, String response) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("session-1");
        r.setTimestamp(timestamp);
        r.setSeq(timestamp);
        r.setSkillId(skillId);
        r.setTemplateHash("hash-old");
        r.setUserInput("查订单 ORD-001");
        r.setTurnIndex(0);
        r.setModelResponse(response);
        r.setToolCalls(new ArrayList<>());
        r.setHasToolCalls(false);
        return r;
    }

    private String groupKeyOf(String skillId) {
        return DeterministicSkillGrouper.group(repository.findBySkillId(skillId).get(0)).getGroupKey();
    }

    @Nested
    @DisplayName("全 PASS 流程")
    class AllPass {

        @Test
        @DisplayName("同形响应 → 自动建基线 + 全 PASS + 退出码 0")
        void replay_identicalResponses_exitZero() {
            seedOneSkill();
            stubClient.responseText = "same answer";

            int exit = runner.run("new prompt", null, 3, null, false);

            assertEquals(0, exit, "全部 PASS 时退出码必须为 0（CI gating 契约）");
            String report = output.toString();
            assertTrue(report.contains("新建基线"), "重放前必须自动补建缺失基线");
            assertTrue(report.contains("PASS 2"), "两条用例均应 PASS");
            assertEquals(2, stubClient.callCount);

            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            assertNotNull(profile.getFingerprint());
            assertNull(profile.getCandidateFingerprint(), "PASS 不产生候选");
            assertEquals(BaselineStatus.BASELINE, profile.getBaselineStatus());
            assertEquals(2, profile.getTotalRecords(), "建基线时记录数应回填为该 skill 的真实记录数");
        }
    }

    @Nested
    @DisplayName("差异 → 候选 → 裁决流程")
    class DiffAndAdjudicate {

        @Test
        @DisplayName("行为差异 → 退出码 1 + 候选落库 → approve 升版")
        void replayDiff_thenApprove() {
            seedOneSkill();
            stubClient.toolCallResponse = true;

            int exit = runner.run("new prompt", null, 3, null, false);

            assertEquals(1, exit, "存在非 PASS 时退出码必须为 1");
            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            assertNotNull(profile.getCandidateFingerprint(), "差异候选必须落库供跨进程裁决");
            assertEquals(BaselineStatus.CANDIDATE, profile.getBaselineStatus());

            ApproveCommand approve = new ApproveCommand();
            approve.db = tempDir.resolve("flow.db").toString();
            approve.skill = "chat:hash-old";
            int approveExit = approve.call();

            assertEquals(0, approveExit);
            SkillProfile approved = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            assertEquals(BaselineStatus.BASELINE, approved.getBaselineStatus());
            assertEquals("v2", approved.getVersionTag(), "approve 后版本递增");
            assertNull(approved.getCandidateFingerprint());
        }

        @Test
        @DisplayName("拒绝候选 → 保留旧基线与版本")
        void replayDiff_thenReject() {
            seedOneSkill();
            stubClient.toolCallResponse = true;
            runner.run("new prompt", null, 3, null, false);

            RejectCommand reject = new RejectCommand();
            reject.db = tempDir.resolve("flow.db").toString();
            reject.skill = "chat:hash-old";
            assertEquals(0, reject.call());

            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            assertEquals(BaselineStatus.BASELINE, profile.getBaselineStatus());
            assertEquals("v1", profile.getVersionTag());
            assertNull(profile.getCandidateFingerprint());
        }
    }

    @Nested
    @DisplayName("选例与防御")
    class Selection {

        @Test
        @DisplayName("dry-run 只列用例不调 LLM")
        void dryRun_noLlmCalls() {
            seedOneSkill();

            int exit = runner.run("new prompt", null, 3, null, true);

            assertEquals(0, exit);
            assertEquals(0, stubClient.callCount);
            String report = output.toString();
            assertTrue(report.contains("rec-1"));
            assertTrue(report.contains("未调用 LLM"));
        }

        @Test
        @DisplayName("max-cases 每 skill 截断选例")
        void maxCases_limitsSelection() {
            for (int i = 1; i <= 5; i++) {
                repository.saveInteraction(makeRecord("rec-" + i, "skill-1", 1000L * i, "same answer"));
            }
            stubClient.responseText = "same answer";

            runner.run("new prompt", null, 2, null, false);

            assertEquals(2, stubClient.callCount, "每 skill 选例上限必须生效");
        }

        @Test
        @DisplayName("未知 skill → 指导信息 + 退出码 2")
        void unknownSkill_guidanceExitTwo() {
            seedOneSkill();

            int exit = runner.run("new prompt", "no-such-skill", 3, null, false);

            assertEquals(2, exit);
            assertTrue(output.toString().contains("no-such-skill"));
            assertEquals(0, stubClient.callCount);
        }

        @Test
        @DisplayName("空库 → 冷启动指导 + 退出码 2")
        void emptyDb_coldStartGuidance() {
            int exit = runner.run("new prompt", null, 3, null, false);

            assertEquals(2, exit);
            assertTrue(output.toString().contains("未找到可重放用例"));
        }
    }

    /**
     * 可编程桩 LLM 客户端——responseText 与 toolCallResponse 二选一。
     */
    static class StubLlmClient implements LlmClient {
        String responseText;
        boolean toolCallResponse;
        int callCount;

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            callCount++;
            LlmResponse response = new LlmResponse();
            response.setInputTokens(10);
            response.setOutputTokens(5);
            if (toolCallResponse) {
                ToolCallResult tc = new ToolCallResult();
                tc.setToolCallId("call-1");
                tc.setToolName("queryOrder");
                tc.setArguments(Collections.singletonMap("orderId", "ORD-001"));
                response.setToolCalls(Collections.singletonList(tc));
                response.setContent(null);
            } else {
                response.setContent(responseText);
                response.setToolCalls(Collections.emptyList());
            }
            return response;
        }

        @Override
        public String name() {
            return "stub-model";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
