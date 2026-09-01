package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BaselineManager 单元测试 — 基线三态生命周期。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class BaselineManagerTest {

    private SimpleTestRepo repo;
    private BaselineManager manager;

    @BeforeEach
    void setUp() {
        repo = new SimpleTestRepo();
        manager = new BaselineManager(repo);
    }

    private InvocationProfile makeProfileWithBaseline(String invocationKey, String label) {
        InvocationProfile p = new InvocationProfile();
        p.setInvocationKey(invocationKey);
        p.setLabel(label);
        p.setFingerprint(new DeterministicFingerprint());
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v1");
        return p;
    }

    private InvocationProfile makeProfileWithCandidate(String invocationKey, String invocationId) {
        InvocationProfile p = makeProfileWithBaseline(invocationKey, invocationId);
        p.setCandidateFingerprint(new DeterministicFingerprint());
        p.setBaselineStatus(BaselineStatus.CANDIDATE);
        return p;
    }

    private InteractionRecord makeToolRecord(String invocationId, String toolName) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-" + System.nanoTime());
        r.setInvocationId(invocationId);
        r.setTemplateHash("hash-" + invocationId);
        r.setModelResponse("{\"result\":\"ok\"}");
        r.setToolCalls(Collections.singletonList(new ToolCall() {{
            setToolName(toolName);
            setArguments(Collections.singletonMap("arg1", "val1"));
        }}));
        return r;
    }

    @Nested
    @DisplayName("approve - 批准候选为基线")
    class Approve {

        @Test
        @DisplayName("候选升为基线，旧基线归档")
        void candidatePromoted_oldArchived() {
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            DeterministicFingerprint oldBaseline = profile.getFingerprint();
            DeterministicFingerprint candidate = profile.getCandidateFingerprint();
            repo.saveInvocationProfile(profile);

            manager.approve("gk-1", "tester");

            InvocationProfile updated = repo.findInvocationByKey("gk-1");
            assertEquals(BaselineStatus.BASELINE, updated.getBaselineStatus());
            // 候选已成为基线
            assertEquals(candidate, updated.getFingerprint());
            assertNull(updated.getCandidateFingerprint());
            // 版本递增
            assertEquals("v2", updated.getVersionTag());
            // 旧基线已归档
            assertEquals(1, repo.archivedBaselines.size());
            assertEquals("gk-1", repo.archivedBaselines.get(0).getInvocationKey());
            assertEquals("v1", repo.archivedBaselines.get(0).getVersionTag());
        }

        @Test
        @DisplayName("无候选 → 抛出 IllegalStateException")
        void noCandidate_throwsException() {
            InvocationProfile profile = makeProfileWithBaseline("gk-1", "order-flow");
            repo.saveInvocationProfile(profile);

            IllegalStateException ex = assertThrows(IllegalStateException.class, () -> manager.approve("gk-1", "tester"));
            assertTrue(ex.getMessage().contains("No candidate"));
        }

        @Test
        @DisplayName("Skill profile 不存在 → 抛出 IllegalStateException")
        void profileNotFound_throwsException() {
            assertThrows(IllegalStateException.class, () -> manager.approve("nonexistent", "tester"));
        }

        @Test
        @DisplayName("无旧基线（fingerprint=null）的首次 approve → 不归档")
        void approve_noOldBaseline() {
            InvocationProfile profile = new InvocationProfile();
            profile.setInvocationKey("gk-1");
            profile.setLabel("order-flow");
            profile.setFingerprint(null);
            profile.setCandidateFingerprint(new DeterministicFingerprint());
            profile.setBaselineStatus(BaselineStatus.CANDIDATE);
            profile.setVersionTag(null);
            repo.saveInvocationProfile(profile);

            manager.approve("gk-1", "tester");

            // 无旧基线 → 不归档
            assertTrue(repo.archivedBaselines.isEmpty());
            assertEquals("v1", repo.findInvocationByKey("gk-1").getVersionTag());
        }

        @Test
        @DisplayName("多次 approve → 版本号递增")
        void multipleApproves_versionIncrement() {
            // v1 → v2
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveInvocationProfile(profile);
            manager.approve("gk-1", "tester");

            // 设置新候选 → v2 → v3
            InvocationProfile updated = repo.findInvocationByKey("gk-1");
            updated.setCandidateFingerprint(new DeterministicFingerprint());
            updated.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(updated);
            manager.approve("gk-1", "tester");

            assertEquals("v3", repo.findInvocationByKey("gk-1").getVersionTag());
            assertEquals(2, repo.archivedBaselines.size());
        }
    }

    @Nested
    @DisplayName("reject - 否决候选")
    class Reject {

        @Test
        @DisplayName("丢弃候选，保留旧基线")
        void candidateDiscarded_baselineKept() {
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            DeterministicFingerprint oldBaseline = profile.getFingerprint();
            repo.saveInvocationProfile(profile);

            manager.reject("gk-1");

            InvocationProfile updated = repo.findInvocationByKey("gk-1");
            assertEquals(BaselineStatus.BASELINE, updated.getBaselineStatus());
            assertEquals(oldBaseline, updated.getFingerprint());
            assertNull(updated.getCandidateFingerprint());
            // 版本不变
            assertEquals("v1", updated.getVersionTag());
        }

        @Test
        @DisplayName("Skill profile 不存在 → 抛出 IllegalStateException")
        void profileNotFound_throwsException() {
            assertThrows(IllegalStateException.class, () -> manager.reject("nonexistent"));
        }

        @Test
        @DisplayName("无候选指纹 → 抛出 IllegalStateException（与 approve 对称）")
        void noCandidate_throwsException() {
            InvocationProfile profile = makeProfileWithBaseline("gk-1", "order-flow");
            repo.saveInvocationProfile(profile);

            assertThrows(IllegalStateException.class, () -> manager.reject("gk-1"));
        }
    }

    @Nested
    @DisplayName("rollback - 回滚到归档基线")
    class Rollback {

        @Test
        @DisplayName("回滚到指定版本 → 恢复旧指纹")
        void rollbackToVersion_restoresOldFingerprint() {
            // 建立初始 profile 并 approve 一次
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            DeterministicFingerprint originalBaseline = profile.getFingerprint();
            repo.saveInvocationProfile(profile);
            manager.approve("gk-1", "tester");

            // 现在 profile 是 v2，归档里有 v1
            // 再设一个候选准备回滚
            InvocationProfile v2Profile = repo.findInvocationByKey("gk-1");
            v2Profile.setCandidateFingerprint(new DeterministicFingerprint());
            v2Profile.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(v2Profile);

            // 回滚到 v1
            manager.rollback("gk-1", "v1");

            InvocationProfile rolled = repo.findInvocationByKey("gk-1");
            assertEquals(BaselineStatus.BASELINE, rolled.getBaselineStatus());
            assertEquals(originalBaseline, rolled.getFingerprint());
            assertNull(rolled.getCandidateFingerprint());
            assertEquals("v1", rolled.getVersionTag());
        }

        @Test
        @DisplayName("归档版本不存在 → 抛出 IllegalStateException")
        void archivedNotFound_throwsException() {
            InvocationProfile profile = makeProfileWithBaseline("gk-1", "order-flow");
            repo.saveInvocationProfile(profile);

            assertThrows(IllegalStateException.class, () -> manager.rollback("gk-1", "v99"));
        }

        @Test
        @DisplayName("Skill profile 不存在 → 抛出 IllegalStateException")
        void profileNotFound_throwsException() {
            assertThrows(IllegalStateException.class, () -> manager.rollback("nonexistent", "v1"));
        }

        @Test
        @DisplayName("回滚时当前基线也归档（保留历史）")
        void rollback_currentBaselineAlsoArchived() {
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveInvocationProfile(profile);
            manager.approve("gk-1", "tester"); // v1 → v2, 归档 v1

            // v2 设候选
            InvocationProfile v2 = repo.findInvocationByKey("gk-1");
            v2.setCandidateFingerprint(new DeterministicFingerprint());
            v2.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(v2);

            // 回滚到 v1
            manager.rollback("gk-1", "v1");

            // 应该有两条归档：v1（approve 时的）和 v2（rollback 时的）
            assertEquals(2, repo.archivedBaselines.size());
        }

        @Test
        @DisplayName("回滚后再 approve → 新 tag 跳过归档已占用值，不产生同 tag 双指纹")
        void rollbackThenApprove_versionTagSkipsArchived() {
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveInvocationProfile(profile);
            manager.approve("gk-1", "tester"); // v1 归档，活跃 v2

            InvocationProfile v2 = repo.findInvocationByKey("gk-1");
            v2.setCandidateFingerprint(new DeterministicFingerprint());
            v2.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(v2);
            manager.approve("gk-1", "tester"); // v2 归档，活跃 v3

            manager.rollback("gk-1", "v1"); // 活跃恢复 v1，v3 归档

            InvocationProfile rolled = repo.findInvocationByKey("gk-1");
            rolled.setCandidateFingerprint(new DeterministicFingerprint());
            rolled.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(rolled);

            manager.approve("gk-1", "tester");

            // v2/v3 已在归档中，新基线必须跳到 v4——否则 rollback("v2") 无法区分两个不同指纹
            assertEquals("v4", repo.findInvocationByKey("gk-1").getVersionTag());
            long distinctTags = repo.archivedBaselines.stream().map(ArchivedTemplateVersion::getVersionTag).distinct().count();
            assertEquals(repo.archivedBaselines.size(), distinctTags);
            // 回滚恢复的 v1 基线已在归档中，不得重复归档
            assertEquals(3, repo.archivedBaselines.size());
        }
    }

    @Nested
    @DisplayName("autoEstablishBaseline - 首次自动建立基线")
    class AutoEstablish {

        @Test
        @DisplayName("骨架调用点：落库形态记录（仅投影）建立基线命中存储键——键不分叉")
        void establish_skeletonProjectionRecord_keyConsistent() {
            // 落库形态：骨架文本不落列，读侧记录只带投影（与 storage 读侧映射一致）——
            // establish/recordCandidate 对这类记录重算键必须落回同一骨架键
            String projection = io.github.agentassert4j.util.HashUtil.sha256("客服助手。当前日期：{{date}}");
            String storedKey = "skeleton:" + projection;
            InteractionRecord dbForm = new InteractionRecord();
            dbForm.setRecordId("rec-skl");
            dbForm.setSessionId("s1");
            dbForm.setTimestamp(1000L);
            dbForm.setInvocationKey(storedKey);
            dbForm.setSkeletonHash(projection);
            dbForm.setModelResponse("答A");

            manager.autoEstablishBaseline(dbForm, "tester", null);

            InvocationProfile profile = repo.findInvocationByKey(storedKey);
            assertNotNull(profile, "仅投影的落库记录必须 establish 到同一骨架键（recordCandidate/重建同路）");
            assertEquals(storedKey, profile.getInvocationKey());
            assertEquals(BaselineStatus.BASELINE, profile.getBaselineStatus());
        }

        @Test
        @DisplayName("新 Skill → 自动建立基线")
        void newSkill_autoEstablishes() {
            InteractionRecord record = makeToolRecord("skill-new", "toolA");
            repo.saveInteraction(record);

            manager.autoEstablishBaseline(record, "tester", null);

            // 应该创建了 InvocationProfile
            List<InvocationProfile> allSkills = repo.findAllInvocations();
            assertEquals(1, allSkills.size());

            InvocationProfile created = allSkills.get(0);
            assertNotNull(created.getFingerprint());
            assertEquals(BaselineStatus.BASELINE, created.getBaselineStatus());
            assertEquals("v1", created.getVersionTag());
            assertNull(created.getCandidateFingerprint());
        }

        @Test
        @DisplayName("已有基线 → 不覆盖（幂等）")
        void existingBaseline_noOverwrite() {
            InteractionRecord record = makeToolRecord("skill-exist", "toolA");
            repo.saveInteraction(record);

            // 首次建立
            manager.autoEstablishBaseline(record, "tester", null);
            DeterministicFingerprint original = repo.findAllInvocations().get(0).getFingerprint();

            // 再次调用
            manager.autoEstablishBaseline(record, "tester", null);

            // 基线不变
            List<InvocationProfile> allSkills = repo.findAllInvocations();
            assertEquals(1, allSkills.size());
            assertEquals(original, allSkills.get(0).getFingerprint());
        }

        @Test
        @DisplayName("null record → 安全忽略")
        void nullRecord_safeIgnore() {
            manager.autoEstablishBaseline(null, null, null);
            assertTrue(repo.findAllInvocations().isEmpty());
        }

        @Test
        @DisplayName("invocationId 为空 → 安全忽略")
        void emptyInvocationId_establishedViaDerivedAnchor() {
            // 原断言钉住「无 invocationId 一律忽略建档」——形状组一等公民后该行为废止：
            // 未声明记录按派生身份（模板/形状锚点）正常建档，派生不依赖声明位
            InteractionRecord r = new InteractionRecord();
            manager.autoEstablishBaseline(r, "tester", null);
            assertEquals(1, repo.findAllInvocations().size());
            assertEquals("adhoc:no-anchor", repo.findAllInvocations().get(0).getInvocationKey(), "全缺失记录落 adhoc:no-anchor 兜底桶");

            r.setInvocationId("");
            manager.autoEstablishBaseline(r, "tester", null);
            assertEquals(1, repo.findAllInvocations().size(), "空串声明视同未声明，幂等不重复建档");
        }
    }

    @Nested
    @DisplayName("版本标签生成")
    class VersionTag {

        @Test
        @DisplayName("v1 → v2 → v3 → v4 正确递增")
        void standardVersionIncrement() {
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveInvocationProfile(profile);

            // v1 → v2
            manager.approve("gk-1", "tester");
            assertEquals("v2", repo.findInvocationByKey("gk-1").getVersionTag());

            // v2 → v3
            InvocationProfile p = repo.findInvocationByKey("gk-1");
            p.setCandidateFingerprint(new DeterministicFingerprint());
            p.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(p);
            manager.approve("gk-1", "tester");
            assertEquals("v3", repo.findInvocationByKey("gk-1").getVersionTag());
        }
    }

    @Nested
    @DisplayName("recordCandidate - 候选指纹落库")
    class RecordCandidate {

        @Test
        @DisplayName("候选落库，状态转为 CANDIDATE，旧基线不动")
        void persistsCandidate_statusTransitions() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "tester", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();
            DeterministicFingerprint oldBaseline = repo.findInvocationByKey(invocationKey).getFingerprint();

            DeterministicFingerprint candidate = new DeterministicFingerprint();
            manager.recordCandidate(record, candidate);

            InvocationProfile updated = repo.findInvocationByKey(invocationKey);
            assertEquals(BaselineStatus.CANDIDATE, updated.getBaselineStatus());
            assertEquals(candidate, updated.getCandidateFingerprint());
            assertEquals(oldBaseline, updated.getFingerprint());
        }

        @Test
        @DisplayName("落库后 approve 在新进程可达（管道闭环）")
        void persistedCandidate_approvable() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "tester", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();

            manager.recordCandidate(record, new DeterministicFingerprint());
            manager.approve(invocationKey, "tester");

            assertNull(repo.findInvocationByKey(invocationKey).getCandidateFingerprint());
        }

        @Test
        @DisplayName("画像不存在 → 抛出 IllegalStateException")
        void profileNotFound_throwsException() {
            InteractionRecord record = makeToolRecord("skill-x", "queryOrder");

            assertThrows(IllegalStateException.class, () -> manager.recordCandidate(record, new DeterministicFingerprint()));
        }

        @Test
        @DisplayName("null 记录或 null 指纹 → 安全忽略")
        void nullArguments_safeIgnore() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "tester", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();

            manager.recordCandidate(null, new DeterministicFingerprint());
            manager.recordCandidate(record, null);

            assertNull(repo.findInvocationByKey(invocationKey).getCandidateFingerprint());
        }
    }

    @Nested
    @DisplayName("治理链 - 语义版本与审批留痕")
    class Governance {

        @Test
        @DisplayName("approve 盖上审批人、时间与当前语义版本")
        void approveStampsGovernanceFacts() {
            InvocationProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveInvocationProfile(profile);

            manager.approve("gk-1", "alice");

            InvocationProfile updated = repo.findInvocationByKey("gk-1");
            assertEquals("alice", updated.getApprovedBy());
            assertNotNull(updated.getApprovedAt());
            assertEquals(JudgmentSemantics.VERSION, updated.getAlgoVersion());
        }

        @Test
        @DisplayName("空白审批人归一为 null——null 是「未经审批链盖章」的异常信号")
        void blankApprover_normalizedToNull() {
            InvocationProfile profile = makeProfileWithCandidate("gk-blank", "skill-blank");
            repo.saveInvocationProfile(profile);

            manager.approve("gk-blank", "   ");

            assertNull(repo.findInvocationByKey("gk-blank").getApprovedBy());
        }

        @Test
        @DisplayName("审批人首尾空白被裁剪")
        void approver_trimmed() {
            InvocationProfile profile = makeProfileWithCandidate("gk-trim", "skill-trim");
            repo.saveInvocationProfile(profile);

            manager.approve("gk-trim", "  bob  ");

            assertEquals("bob", repo.findInvocationByKey("gk-trim").getApprovedBy());
        }

        @Test
        @DisplayName("归档行携带旧基线自身的审批事实，新基线携带新审批人")
        void archiveCarriesOutgoingApproval() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "alice", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();
            String firstVersion = repo.findInvocationByKey(invocationKey).getVersionTag();

            InvocationProfile p = repo.findInvocationByKey(invocationKey);
            p.setCandidateFingerprint(new DeterministicFingerprint());
            p.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(p);
            manager.approve(invocationKey, "bob");

            ArchivedTemplateVersion archived = repo.findArchivedVersion(invocationKey, firstVersion);
            assertNotNull(archived);
            assertEquals("alice", archived.getApprovedBy());
            assertEquals(JudgmentSemantics.VERSION, archived.getAlgoVersion());
            assertEquals("bob", repo.findInvocationByKey(invocationKey).getApprovedBy());
        }

        @Test
        @DisplayName("rollback 恢复归档行的语义版本与审批事实")
        void rollbackRestoresGovernanceFacts() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "alice", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();
            String firstVersion = repo.findInvocationByKey(invocationKey).getVersionTag();
            Long aliceAt = repo.findInvocationByKey(invocationKey).getApprovedAt();

            InvocationProfile p = repo.findInvocationByKey(invocationKey);
            p.setCandidateFingerprint(new DeterministicFingerprint());
            p.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveInvocationProfile(p);
            manager.approve(invocationKey, "bob");

            manager.rollback(invocationKey, firstVersion);

            InvocationProfile restored = repo.findInvocationByKey(invocationKey);
            assertEquals("alice", restored.getApprovedBy());
            assertEquals(aliceAt, restored.getApprovedAt());
            assertEquals(JudgmentSemantics.VERSION, restored.getAlgoVersion());
        }

        @Test
        @DisplayName("recordCandidate 不改写审批与语义版本（基线未变）")
        void recordCandidateKeepsStamp() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "alice", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();
            Long before = repo.findInvocationByKey(invocationKey).getApprovedAt();

            manager.recordCandidate(record, new DeterministicFingerprint());

            InvocationProfile updated = repo.findInvocationByKey(invocationKey);
            assertEquals("alice", updated.getApprovedBy());
            assertEquals(before, updated.getApprovedAt());
            assertEquals(JudgmentSemantics.VERSION, updated.getAlgoVersion());
        }

        @Test
        @DisplayName("reestablishBaseline 以当前语义覆盖活跃基线，版本顺延不与归档冲突")
        void reestablishOverwritesWithCurrentSemantics() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "alice", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();
            String firstVersion = repo.findInvocationByKey(invocationKey).getVersionTag();

            manager.reestablishBaseline(record, "bob", null);

            InvocationProfile reestablished = repo.findInvocationByKey(invocationKey);
            assertEquals("bob", reestablished.getApprovedBy());
            assertEquals(JudgmentSemantics.VERSION, reestablished.getAlgoVersion());
            assertNotEquals(firstVersion, reestablished.getVersionTag());
            // 重建必须以当前算法重新提取指纹：与对同一记录的现算结果逐维一致
            DeterministicFingerprint expected = FingerprintExtractor.extract(record);
            assertEquals(expected.getToolCallSet(), reestablished.getFingerprint().getToolCallSet());
            assertEquals(expected.getOutputContentType(), reestablished.getFingerprint().getOutputContentType());
            assertEquals(expected.getOutputFieldPaths(), reestablished.getFingerprint().getOutputFieldPaths());
            // 被替换基线先归档留痕（含治理事实），rollback 可恢复——重建不再不可逆
            ArchivedTemplateVersion outgoing = repo.findArchivedVersion(invocationKey, firstVersion);
            assertNotNull(outgoing);
            assertEquals("alice", outgoing.getApprovedBy());
            // 活跃 tag 不与任何归档行同指纹冲突
            assertNull(repo.findArchivedVersion(invocationKey, reestablished.getVersionTag()));
        }

        @Test
        @DisplayName("重建覆盖后 rollback 恢复旧基线及其治理事实")
        void rollbackAfterReestablish_restoresOutgoingBaseline() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "alice", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();
            String firstVersion = repo.findInvocationByKey(invocationKey).getVersionTag();
            Long aliceAt = repo.findInvocationByKey(invocationKey).getApprovedAt();

            manager.reestablishBaseline(record, "bob", null);
            manager.rollback(invocationKey, firstVersion);

            InvocationProfile restored = repo.findInvocationByKey(invocationKey);
            assertEquals(firstVersion, restored.getVersionTag());
            assertEquals("alice", restored.getApprovedBy());
            assertEquals(aliceAt, restored.getApprovedAt());
            assertEquals(JudgmentSemantics.VERSION, restored.getAlgoVersion());
        }

        @Test
        @DisplayName("自动建立基线同样留痕操作者身份")
        void autoEstablishStampsActor() {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");

            manager.autoEstablishBaseline(record, "ci-bot", null);

            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();
            assertEquals("ci-bot", repo.findInvocationByKey(invocationKey).getApprovedBy());
            assertEquals(JudgmentSemantics.VERSION, repo.findInvocationByKey(invocationKey).getAlgoVersion());
        }
    }

    @Nested
    @DisplayName("并发契约 - 同一 JVM 内生命周期方法互斥")
    class Concurrency {

        @Test
        @DisplayName("approve 与 recordCandidate 并发交织后版本号与成功次数严格一致")
        void interleavedApproveAndRecordCandidate_versionAdvancesOncePerSuccess() throws Exception {
            InteractionRecord record = makeToolRecord("skill-1", "queryOrder");
            manager.autoEstablishBaseline(record, "owner", null);
            String invocationKey = InvocationResolver.resolve(record).getInvocationKey();

            int threads = 8;
            int iterations = 40;
            AtomicInteger successfulApproves = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            for (int i = 0; i < threads; i++) {
                final String actor = "actor-" + i;
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int j = 0; j < iterations; j++) {
                            // 先补候选再批准；候选被其他线程先消费时 approve 按契约抛出
                            manager.recordCandidate(record, new DeterministicFingerprint());
                            try {
                                manager.approve(invocationKey, actor);
                                successfulApproves.incrementAndGet();
                            } catch (IllegalStateException noCandidate) {
                                // 并发下候选缺席属正常分支
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(30, TimeUnit.SECONDS), "并发生命周期操作必须在有界时间内完成");
            pool.shutdownNow();

            // 互斥下的确定性不变量：每次成功的 approve 恰好推进一个版本，
            // 交织读改写（丢更新）会让版本推进数落后于成功次数
            InvocationProfile latest = repo.findInvocationByKey(invocationKey);
            assertNotNull(latest);
            assertEquals("v" + (1 + successfulApproves.get()), latest.getVersionTag(), "版本推进数必须与成功的 approve 次数严格一致");
            assertEquals(JudgmentSemantics.VERSION, latest.getAlgoVersion());
            assertNotNull(latest.getApprovedBy());
        }
    }
}
