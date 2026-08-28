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
import io.github.agentassert4j.util.RecursiveJsonParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

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

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

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

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(1, exit, "存在非 PASS 时退出码必须为 1");
            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            assertNotNull(profile.getCandidateFingerprint(), "差异候选必须落库供跨进程裁决");
            assertEquals(BaselineStatus.CANDIDATE, profile.getBaselineStatus());

            ApproveCommand approve = new ApproveCommand();
            approve.db = tempDir.resolve("flow.db").toString();
            approve.skill = "chat:hash-old";
            PrintStream originalOut = System.out;
            ByteArrayOutputStream approveOut = new ByteArrayOutputStream();
            System.setOut(new PrintStream(approveOut, true));
            int approveExit;
            try {
                approveExit = approve.call();
            } finally {
                System.setOut(originalOut);
            }

            assertEquals(0, approveExit);
            assertTrue(approveOut.toString().contains("候选差异"), "裁决时必须渲染候选与基线的差异证据: " + approveOut);
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
            runner.run("new prompt", null, 3, null, false, false, true);

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

            int exit = runner.run("new prompt", null, 3, null, true, false, true);

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

            runner.run("new prompt", null, 2, null, false, false, true);

            assertEquals(2, stubClient.callCount, "每 skill 选例上限必须生效");
        }

        @Test
        @DisplayName("未知 skill → 指导信息 + 退出码 2")
        void unknownSkill_guidanceExitTwo() {
            seedOneSkill();

            int exit = runner.run("new prompt", "no-such-skill", 3, null, false, false, true);

            assertEquals(2, exit);
            assertTrue(output.toString().contains("no-such-skill"));
            assertEquals(0, stubClient.callCount);
        }

        @Test
        @DisplayName("空库 → 冷启动指导 + 退出码 2")
        void emptyDb_coldStartGuidance() {
            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(2, exit);
            assertTrue(output.toString().contains("未找到可重放用例"));
        }

        @Test
        @DisplayName("--skill 接受 groupKey 前缀（与 status/approve 的用户标识一致）")
        void skillFilter_acceptsGroupKeyPrefix() {
            seedOneSkill();
            stubClient.responseText = "same answer";

            String prefix = groupKeyOf("skill-1").substring(0, 10);
            int exit = runner.run("new prompt", prefix, 3, null, false, false, true);

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
            assertThrows(IllegalStateException.class, () -> runner.run("new prompt", prefix, 3, null, false, false, true));
            assertEquals(0, stubClient.callCount, "歧义路径不得发起任何 LLM 调用");
        }

        @Test
        @DisplayName("groupKey 前缀零命中 → 走「未找到用例」退出码 2")
        void prefixNoMatch_fallsThroughToNoCases() {
            seedOneSkill();

            int exit = runner.run("new prompt", "chat:zzzz", 3, null, false, false, true);

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
            runner.run("new prompt", null, 3, null, false, false, true);
            int callsAfterFirstRun = stubClient.callCount;

            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            profile.setAlgoVersion("det-v0");
            repository.saveSkillProfile(profile);

            output.reset();
            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(2, exit, "跨语义版本的判定必须被拒绝");
            assertTrue(output.toString().contains("判定语义版本不一致"));
            assertEquals(callsAfterFirstRun, stubClient.callCount, "拒绝路径不得发起 LLM 调用");
        }

        @Test
        @DisplayName("未标记版本的基线同样拒绝（null 不享受豁免）")
        void unstampedAlgoVersion_refusesJudgment() {
            seedOneSkill();
            stubClient.responseText = "same answer";
            runner.run("new prompt", null, 3, null, false, false, true);

            SkillProfile profile = repository.findSkillByGroupKey(groupKeyOf("skill-1"));
            profile.setAlgoVersion(null);
            repository.saveSkillProfile(profile);

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(2, exit);
        }

        @Test
        @DisplayName("baseline --force 以当前语义重建后重放恢复可用")
        void forceRebuild_restoresReplayability() {
            seedOneSkill();
            stubClient.responseText = "same answer";
            runner.run("new prompt", null, 3, null, false, false, true);
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
            int exit = runner.run("new prompt", null, 3, null, false, false, true);
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

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

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

            runner.run("new prompt", null, 3, null, false, false, true);

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

            runner.run("new prompt", null, 3, null, false, false, true);

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

            runner.run("new prompt", null, 3, null, false, false, true);

            assertFalse(output.toString().contains("served 模型"), "一致时不得误报: " + output);
        }

        @Test
        @DisplayName("baseline --force 覆盖后 rollback 恢复旧基线与审批人")
        void forceRebuild_thenRollbackRestoresOutgoing() {
            seedOneSkill();
            stubClient.responseText = "same answer";
            runner.run("new prompt", null, 3, null, false, false, true);
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

    @Nested
    @DisplayName("CI 模式与只读性")
    class CiModeAndReadonly {

        @Test
        @DisplayName("CI 模式：无基线 skill 拒绝判定且不自动建档")
        void ciMode_refusesUnbaselinedSkill() {
            seedOneSkill();

            int exit = runner.run("new prompt", null, 3, null, false, true, true);

            assertEquals(2, exit, "无人审的自动基线不允许在 CI 产绿灯");
            String report = output.toString();
            assertTrue(report.contains("尚无基线"), "必须点名未建档 skill: " + report);
            assertTrue(report.contains("chat:"), "拒绝名单按 groupKey 列出: " + report);
            assertEquals(0, stubClient.callCount, "拒绝判定不得发起 LLM 调用");
            assertNull(repository.findSkillByGroupKey(groupKeyOf("skill-1")), "CI 模式不得自动建档");
        }

        @Test
        @DisplayName("CI 模式：基线齐备时正常判定放行")
        void ciMode_passesWhenBaselinesExist() {
            seedOneSkill();
            new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", false, null, null);
            stubClient.responseText = "same answer";

            int exit = runner.run("new prompt", null, 3, null, false, true, true);

            assertEquals(0, exit, "基线齐备时 CI 模式必须照常判定");
        }

        @Test
        @DisplayName("dry-run 只读：不建档不落图快照")
        void dryRun_leavesNoSideEffects() {
            seedOneSkill();

            int exit = runner.run("new prompt", null, 3, null, true, false, true);

            assertEquals(0, exit);
            assertNull(repository.findSkillByGroupKey(groupKeyOf("skill-1")), "dry-run 不得建档");
            assertTrue(repository.loadGraph() == null || repository.loadGraph().isEmpty(), "dry-run 不得写图快照");
        }

        @Test
        @DisplayName("全部用例执行失败（无比对结果）→ 退出码 2 而非误报回归")
        void allInfraFailures_exitTwo() {
            seedOneSkill();
            stubClient.throwApiError = true;

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(2, exit, "基础设施故障不是行为回归，按用法/数据问题退出");
            assertTrue(output.toString().contains("无任何比对结果"), "必须点明是执行故障而非回归: " + output);
        }
    }

    @Nested
    @DisplayName("选例策略与报告完整性")
    class SelectionAndReporting {

        @Test
        @DisplayName("默认选例取每 skill 最新录制且策略就地披露")
        void defaultSelection_takesNewestRecords() {
            for (int i = 1; i <= 5; i++) {
                repository.saveInteraction(makeRecord("rec-" + i, "skill-1", 1000L * i, "same answer"));
            }
            stubClient.responseText = "same answer";

            int exit = runner.run("new prompt", null, 2, null, false, false, true);

            assertEquals(0, exit);
            String report = output.toString();
            assertTrue(report.contains("最新 2 条"), "选例策略必须就地披露: " + report);
            assertTrue(report.contains("rec-5") && report.contains("rec-4"), "默认取最新录制: " + report);
            assertFalse(report.contains("rec-1"), "最旧记录不得入选: " + report);
        }

        @Test
        @DisplayName("oldest 策略取最旧 N 条")
        void oldestSelection_takesOldestRecords() {
            for (int i = 1; i <= 5; i++) {
                repository.saveInteraction(makeRecord("rec-" + i, "skill-1", 1000L * i, "same answer"));
            }
            stubClient.responseText = "same answer";

            runner.run("new prompt", null, 2, null, false, false, false);

            String report = output.toString();
            assertTrue(report.contains("最旧 2 条"));
            assertTrue(report.contains("rec-1") && report.contains("rec-2"), "oldest 取头部: " + report);
            assertFalse(report.contains("rec-5"), "最新记录不得入选: " + report);
        }

        @Test
        @DisplayName("汇总行聚合每用例真实 token 消耗")
        void summary_reportsRealTokens() {
            seedOneSkill();
            stubClient.responseText = "same answer";

            runner.run("new prompt", null, 3, null, false, false, true);

            String report = output.toString();
            assertTrue(report.contains("[tokens 10/5]"), "每用例真实 token 必须可见: " + report);
            assertTrue(report.contains("tokens 输入 20 / 输出 10"), "汇总必须聚合真实 token: " + report);
        }

        @Test
        @DisplayName("old-prompt 影响分析打印波及面")
        void oldPromptPath_printsImpactSummary() {
            seedOneSkill();
            stubClient.responseText = "same answer";

            int exit = runner.run("new prompt", null, 3, "hash-old", false, false, true);

            assertTrue(output.toString().contains("影响分析"), "影响集是裁剪依据，必须报告给使用者: " + output);
            assertEquals(0, exit);
        }

        @Test
        @DisplayName("baseline --skill 只处理目标 skill")
        void baselineSkillFilter_scopedToSingleSkill() {
            InteractionRecord a = makeRecord("rec-a", "skill-a", 1000L, "ans");
            a.setTemplateHash("hash-a");
            repository.saveInteraction(a);
            InteractionRecord b = makeRecord("rec-b", "skill-b", 1000L, "ans");
            b.setTemplateHash("hash-b");
            repository.saveInteraction(b);

            int established = new BaselineService(repository).establishMissing(new PrintStream(new ByteArrayOutputStream()), "tester", false, "skill-a", null);

            assertEquals(1, established);
            assertNotNull(repository.findSkillByGroupKey(groupKeyOf("skill-a")), "目标 skill 必须建档");
            assertNull(repository.findSkillByGroupKey(groupKeyOf("skill-b")), "非目标 skill 不得被波及");
        }
    }

    @Nested
    @DisplayName("回滚版本发现")
    class RollbackDiscovery {

        @Test
        @DisplayName("rollback 版本不存在时列出全部可选归档版本")
        void rollback_unknownVersion_listsAvailableVersions() {
            seedOneSkill();
            stubClient.toolCallResponse = true;
            runner.run("new prompt", null, 3, null, false, false, true);

            ApproveCommand approve = new ApproveCommand();
            approve.db = tempDir.resolve("flow.db").toString();
            approve.skill = groupKeyOf("skill-1");
            assertEquals(0, approve.call(), "先 approve 制造归档 v1");

            RollbackCommand rollback = new RollbackCommand();
            rollback.db = tempDir.resolve("flow.db").toString();
            rollback.skill = groupKeyOf("skill-1");
            rollback.version = "v9";
            PrintStream originalErr = System.err;
            ByteArrayOutputStream errOut = new ByteArrayOutputStream();
            System.setErr(new PrintStream(errOut, true));
            int exit;
            try {
                exit = rollback.call();
            } finally {
                System.setErr(originalErr);
            }

            assertEquals(2, exit);
            String message = errOut.toString();
            assertTrue(message.contains("v9"), "错误信息必须复述请求的版本: " + message);
            assertTrue(message.contains("v1"), "必须列出可选归档版本: " + message);
        }
    }

    @Nested
    @DisplayName("文本差异证据")
    class TextDiffEvidence {

        @Test
        @DisplayName("非 PASS 且两侧原文齐备 → 附 2–3 行截断文本 diff")
        void nonPass_textsDiffer_appendsDiffNote() {
            // 指纹只看结构：文本不同不构成非 PASS，须叠加工具维差异
            seedOneSkill();
            stubClient.toolCallResponse = true;
            stubClient.toolCallWithText = true;
            stubClient.responseText = "completely different revised answer";

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(1, exit);
            String report = output.toString();
            assertTrue(report.contains("文本差异"), "内容差异用例必须给出文本差异证据: " + report);
            assertTrue(report.contains("~ [行1]"), "单行文本变化必须以差异行形式呈现: " + report);
        }

        @Test
        @DisplayName("PASS 用例不得出现文本差异注记")
        void pass_noDiffNote() {
            seedOneSkill();
            stubClient.responseText = "same answer";

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(0, exit);
            assertFalse(output.toString().contains("文本差异"), "PASS 无差异可展示: " + output);
        }

        @Test
        @DisplayName("差异出在结构维度且候选无文本 → 静默省略注记")
        void nonPass_noCandidateText_noteOmitted() {
            seedOneSkill();
            stubClient.toolCallResponse = true;

            int exit = runner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(1, exit);
            assertFalse(output.toString().contains("文本差异"), "候选原文缺席时不得展示空 diff: " + output);
        }
    }

    @Nested
    @DisplayName("JSON 证据报告")
    class JsonReport {

        private ByteArrayOutputStream jsonOut;
        private ByteArrayOutputStream jsonErr;
        private ReplayRunner jsonRunner;

        @BeforeEach
        void setUpJson() {
            jsonOut = new ByteArrayOutputStream();
            jsonErr = new ByteArrayOutputStream();
            jsonRunner = new ReplayRunner(repository, stubClient, new DeterministicComparator(ComparatorConfig.defaults()), new SkillRulesConfig(), TestExecutionConfig.defaults(), new PrintStream(jsonOut, true), new PrintStream(jsonErr, true), true);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> parseReport() {
            String report = jsonOut.toString().trim();
            assertFalse(report.isEmpty(), "JSON 模式必须在 stdout 产出报告");
            assertFalse(report.contains("\n"), "报告必须单行（消费方按行读取）: " + report);
            Object parsed = RecursiveJsonParser.parse(report);
            assertTrue(parsed instanceof Map, "报告必须是 JSON 对象: " + report);
            return (Map<String, Object>) parsed;
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> nested(Map<String, Object> report, String key) {
            Object value = report.get(key);
            assertTrue(value instanceof Map, key + " 必须是 JSON 对象: " + report);
            return (Map<String, Object>) value;
        }

        @Test
        @DisplayName("全 PASS：报告含汇总/逐用例/空待裁决，stderr 零输出")
        void json_fullPass_stdoutOnly() {
            seedOneSkill();
            stubClient.responseText = "same answer";

            int exit = jsonRunner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(0, exit);
            Map<String, Object> report = parseReport();
            assertEquals("agentassert4j.replay-report/1", report.get("schema"));
            assertEquals("replay", report.get("mode"));
            Map<String, Object> summary = nested(report, "summary");
            assertEquals(2, ((Number) summary.get("total")).intValue());
            assertEquals(2, ((Number) summary.get("pass")).intValue());
            List<Object> cases = (List<Object>) report.get("cases");
            assertEquals(2, cases.size(), "逐用例数组必须与判定数闭合");
            List<Object> pending = (List<Object>) report.get("pendingGroupKeys");
            assertTrue(pending.isEmpty(), "全 PASS 无待裁决");
            assertEquals("", jsonErr.toString(), "JSON 模式诊断通道应保持安静");
        }

        @Test
        @DisplayName("非 PASS：verdict/summary/token 落报告，待裁决 groupKeys 非空")
        void json_nonPass_listsPendingGroupKeys() {
            // 基线无工具、重放多出工具调用 → 新行为按 REGRESSION 计
            seedOneSkill();
            stubClient.toolCallResponse = true;

            int exit = jsonRunner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(1, exit);
            Map<String, Object> report = parseReport();
            Map<String, Object> summary = nested(report, "summary");
            assertEquals(2, ((Number) summary.get("regression")).intValue());
            assertEquals(20L, ((Number) summary.get("inputTokens")).longValue(), "汇总 token 必须聚合逐用例实际值");
            List<Object> cases = (List<Object>) report.get("cases");
            Map<String, Object> firstCase = (Map<String, Object>) cases.get(0);
            assertEquals("REGRESSION", firstCase.get("verdict"));
            assertEquals(10, ((Number) firstCase.get("inputTokens")).intValue());
            assertNotNull(firstCase.get("summary"), "非 PASS 用例必须带差异摘要");
            List<Object> pending = (List<Object>) report.get("pendingGroupKeys");
            assertEquals(1, pending.size(), "差异候选的 groupKey 必须进入待裁决名单");
            assertTrue(pending.get(0).toString().startsWith("chat:"), "待裁决按 groupKey 报告: " + pending);
        }

        @Test
        @DisplayName("冷启动：退出码 2，stdout 无 JSON，指导信息走 stderr")
        void json_coldStart_noJsonOnStdout() {
            int exit = jsonRunner.run("new prompt", null, 3, null, false, false, true);

            assertEquals(2, exit);
            assertEquals("", jsonOut.toString(), "流程性失败不得产出 JSON——消费方按退出码分流");
            assertTrue(jsonErr.toString().contains("未找到可重放用例"));
        }

        @Test
        @DisplayName("dry-run：报告只有选例清单，零 LLM 调用")
        void json_dryRun_selectionOnly() {
            seedOneSkill();

            int exit = jsonRunner.run("new prompt", null, 3, null, true, false, true);

            assertEquals(0, exit);
            Map<String, Object> report = parseReport();
            assertEquals("dry-run", report.get("mode"));
            List<Object> cases = (List<Object>) report.get("cases");
            assertEquals(2, cases.size());
            Map<String, Object> firstCase = (Map<String, Object>) cases.get(0);
            assertFalse(firstCase.containsKey("verdict"), "dry-run 无判定字段");
            assertEquals(0, stubClient.callCount);
        }
    }

    /**
     * 可编程桩 LLM 客户端——responseText 与 toolCallResponse 二选一。
     */
    static class StubLlmClient implements LlmClient {
        String responseText;
        boolean toolCallResponse;
        boolean toolCallWithText;
        String toolCallName = "queryOrder";
        int callCount;
        String servedModel;
        boolean throwApiError;

        @Override
        public LlmResponse chat(LlmRequest request, long timeoutMs) throws LlmTimeoutException, LlmApiException {
            callCount++;
            if (throwApiError) {
                throw new LlmApiException("endpoint down");
            }
            LlmResponse response = new LlmResponse();
            response.setInputTokens(10);
            response.setOutputTokens(5);
            response.setServedModel(servedModel);
            if (toolCallResponse) {
                ToolCallResult tc = new ToolCallResult();
                tc.setToolCallId("call-1");
                tc.setToolName("queryOrder");
                tc.setArguments(Collections.singletonMap("orderId", "ORD-001"));
                tc.setToolName(toolCallName);
                response.setToolCalls(Collections.singletonList(tc));
                response.setContent(toolCallWithText ? responseText : null);
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
