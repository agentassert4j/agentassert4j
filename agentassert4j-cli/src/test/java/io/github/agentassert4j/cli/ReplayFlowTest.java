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
import java.util.List;

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
            assertTrue(report.contains("依赖图："), "重放输出必须含依赖图统计（图接线后的可见面）");
            assertTrue(report.contains("新建基线"), "重放前必须自动补建缺失基线");
            assertTrue(report.contains("PASS 2"), "两条用例均应 PASS");
            assertEquals(2, stubClient.callCount);

            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            assertNotNull(profile.getFingerprint());
            assertNull(profile.getCandidateFingerprint(), "PASS 不产生候选");
            assertEquals(BaselineStatus.BASELINE, profile.getBaselineStatus());
            assertEquals(2, profile.getTotalRecords(), "建基线时记录数应回填为该 skill 的真实记录数");
            assertTrue(repository.loadGraph() != null && !repository.loadGraph().isEmpty(), "重放是快照唯一写者，跑完必须留下分析视图快照");
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

        @Test
        @DisplayName("--skill 接受 groupKey 前缀（与 status/approve 的用户标识一致）")
        void skillFilter_acceptsGroupKeyPrefix() {
            seedOneSkill();
            stubClient.responseText = "same answer";

            String prefix = groupKeyOf("skill-1").substring(0, 10);
            int exit = runner.run("new prompt", prefix, 3, null, false);

            assertEquals(0, exit, "groupKey 前缀必须解析到对应 skill: " + output);
            assertTrue(output.toString().contains("按 groupKey 前缀匹配到"));
            assertEquals(2, stubClient.callCount);
        }

        @Test
        @DisplayName("前缀命中覆盖多个业务标签 → 报错退出码 2，零 LLM 调用")
        void prefixCoveringMultipleBusinessLabels_exitTwo() {
            repository.saveInteraction(makeRecord("rec-a", "skill-A", 1000L, "same answer"));
            repository.saveInteraction(makeRecord("rec-b", "skill-B", 2000L, "same answer"));
            stubClient.responseText = "same answer";

            // 两 skill 共用同一 templateHash → 同一 groupKey，任何前缀都同时命中两个标签
            String prefix = groupKeyOf("skill-A").substring(0, 8);
            // 歧义在 run 内部显式抛出（生产侧由 ReplayCommand 兜底转退出码 2）
            assertThrows(IllegalStateException.class, () -> runner.run("new prompt", prefix, 3, null, false));
            assertEquals(0, stubClient.callCount, "歧义路径不得发起任何 LLM 调用");
        }

        @Test
        @DisplayName("groupKey 前缀零命中 → 走「未找到用例」退出码 2")
        void prefixNoMatch_fallsThroughToNoCases() {
            seedOneSkill();

            int exit = runner.run("new prompt", "chat:zzzz", 3, null, false);

            assertEquals(2, exit);
            assertTrue(output.toString().contains("chat:zzzz"));
            assertEquals(0, stubClient.callCount);
        }
    }

    @Nested
    @DisplayName("判定语义守卫")
    class JudgmentGuard {

        @Test
        @DisplayName("基线语义版本过旧 → 拒绝判定 + 退出码 2，不消耗 LLM 调用")
        void staleAlgoVersion_refusesJudgment() {
            seedOneSkill();
            stubClient.responseText = "same answer";
            runner.run("new prompt", null, 3, null, false);
            int callsAfterFirstRun = stubClient.callCount;

            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            profile.setAlgoVersion("det-v0");
            repository.saveSkillProfile(profile);

            output.reset();
            int exit = runner.run("new prompt", null, 3, null, false);

            assertEquals(2, exit, "跨语义版本的判定必须被拒绝");
            assertTrue(output.toString().contains("判定语义版本不一致"));
            assertEquals(callsAfterFirstRun, stubClient.callCount, "拒绝路径不得发起 LLM 调用");
        }

        @Test
        @DisplayName("未标记版本的基线同样拒绝（null 不享受豁免）")
        void unstampedAlgoVersion_refusesJudgment() {
            seedOneSkill();
            stubClient.responseText = "same answer";
            runner.run("new prompt", null, 3, null, false);

            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            profile.setAlgoVersion(null);
            repository.saveSkillProfile(profile);

            int exit = runner.run("new prompt", null, 3, null, false);

            assertEquals(2, exit);
        }

        @Test
        @DisplayName("baseline --force 以当前语义重建后重放恢复可用")
        void forceRebuild_restoresReplayability() {
            seedOneSkill();
            stubClient.responseText = "same answer";
            runner.run("new prompt", null, 3, null, false);
            String groupKey = groupKeyOf("skill-1");

            SkillProfile profile = repository.findSkillByGroupKey(groupKey);
            profile.setAlgoVersion("det-v0");
            repository.saveSkillProfile(profile);

            BaselineCommand baseline = new BaselineCommand();
            baseline.db = tempDir.resolve("flow.db").toString();
            baseline.approver = "rebuilder";
            baseline.force = true;
            assertEquals(0, baseline.call());

            SkillProfile rebuilt = repository.findSkillByGroupKey(groupKey);
            assertEquals("rebuilder", rebuilt.getApprovedBy());
            assertNotNull(rebuilt.getAlgoVersion());
            assertEquals("v2", rebuilt.getVersionTag(), "重建版本顺延，不与既有 tag 冲突");

            output.reset();
            int exit = runner.run("new prompt", null, 3, null, false);
            assertEquals(0, exit, "重建后重放恢复全绿: " + output);
        }

        @Test
        @DisplayName("分组失败的记录被告警并剔出判定集，不产生无守卫判定")
        void ungroupableRecord_warnedAndExcluded() {
            // 正常记录在前，损坏记录在后（两个无工具名的工具调用会让分组器在排序比较时抛异常）
            repository.saveInteraction(makeRecord("rec-good-1", "skill-1", 1000L, "same answer"));
            repository.saveInteraction(makeRecord("rec-good-2", "skill-1", 1500L, "same answer"));
            InteractionRecord poisoned = makeRecord("rec-bad-1", "skill-1", 3000L, "same answer");
            List<ToolCall> brokenCalls = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                ToolCall broken = new ToolCall();
                broken.setArguments(Collections.<String, Object>emptyMap());
                brokenCalls.add(broken);
            }
            poisoned.setToolCalls(brokenCalls);
            poisoned.setHasToolCalls(true);
            repository.saveInteraction(poisoned);
            stubClient.responseText = "same answer";

            int exit = runner.run("new prompt", null, 3, null, false);

            String report = output.toString();
            assertTrue(report.contains("分组失败"), "无法核验语义的记录必须显式告警: " + report);
            assertTrue(report.contains("rec-bad-1"), "告警必须点名被剔除的记录");
            assertEquals(0, exit, "剩余可判定用例应全 PASS");
            assertEquals(2, stubClient.callCount, "被剔除记录不得参与判定");
        }

        @Test
        @DisplayName("配置未指定模型时按客户端默认模型比对——默认模型与录制模型不一致必须告警")
        void modelWarn_coversDefaultModelWhenConfigNull() {
            InteractionRecord r = makeRecord("rec-m1", "skill-1", 1000L, "same answer");
            r.setModel("gpt-4o-recorded");
            repository.saveInteraction(r);
            stubClient.responseText = "same answer";

            runner.run("new prompt", null, 3, null, false);

            assertTrue(output.toString().contains("警告：重放模型"), "config.model 缺省时必须以客户端默认模型参与比对（此前该场景是告警盲区）: " + output);
        }

        @Test
        @DisplayName("served 模型与录制模型不一致 → 结果行就地标注")
        void servedModelDiffers_annotatedInResultLine() {
            InteractionRecord r = makeRecord("rec-s1", "skill-1", 1000L, "same answer");
            r.setServedModel("recorded-snapshot-model");
            repository.saveInteraction(r);
            stubClient.responseText = "same answer";
            stubClient.servedModel = "different-served-model";

            runner.run("new prompt", null, 3, null, false);

            assertTrue(output.toString().contains("served 模型 different-served-model ≠ 录制 recorded-snapshot-model"), "served 模型漂移必须就地标注: " + output);
        }

        @Test
        @DisplayName("served 模型一致 → 不产生标注")
        void servedModelMatches_noAnnotation() {
            InteractionRecord r = makeRecord("rec-s2", "skill-1", 1000L, "same answer");
            r.setServedModel("stub-served");
            repository.saveInteraction(r);
            stubClient.responseText = "same answer";
            stubClient.servedModel = "stub-served";

            runner.run("new prompt", null, 3, null, false);

            assertFalse(output.toString().contains("served 模型"), "一致时不得误报: " + output);
        }

        @Test
        @DisplayName("baseline --force 覆盖后 rollback 恢复旧基线与审批人")
        void forceRebuild_thenRollbackRestoresOutgoing() {
            seedOneSkill();
            stubClient.responseText = "same answer";
            runner.run("new prompt", null, 3, null, false);
            String groupKey = groupKeyOf("skill-1");
            String originalApprover = repository.findSkillByGroupKey(groupKey).getApprovedBy();

            BaselineCommand baseline = new BaselineCommand();
            baseline.db = tempDir.resolve("flow.db").toString();
            baseline.approver = "rebuilder";
            baseline.force = true;
            assertEquals(0, baseline.call());
            assertNotEquals(originalApprover, repository.findSkillByGroupKey(groupKey).getApprovedBy());

            RollbackCommand rollback = new RollbackCommand();
            rollback.db = tempDir.resolve("flow.db").toString();
            rollback.skill = groupKey;
            rollback.version = "v1";
            assertEquals(0, rollback.call(), "force 覆盖的旧基线必须可经 rollback 恢复");

            SkillProfile restored = repository.findSkillByGroupKey(groupKey);
            assertEquals("v1", restored.getVersionTag());
            assertEquals(originalApprover, restored.getApprovedBy());
        }
    }

    /**
     * 可编程桩 LLM 客户端——responseText 与 toolCallResponse 二选一。
     */
    static class StubLlmClient implements LlmClient {
        String responseText;
        boolean toolCallResponse;
        int callCount;
        String servedModel;

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            callCount++;
            LlmResponse response = new LlmResponse();
            response.setInputTokens(10);
            response.setOutputTokens(5);
            response.setServedModel(servedModel);
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
