package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.BaselineStatus;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.ToolCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BaselineManager 单元测试 — 基线三态生命周期。
 */
class BaselineManagerTest {

    private SimpleTestRepo repo;
    private BaselineManager manager;

    @BeforeEach
    void setUp() {
        repo = new SimpleTestRepo();
        manager = new BaselineManager(repo);
    }

    // ==================== 辅助方法 ====================

    private SkillProfile makeProfileWithBaseline(String groupKey, String skillId) {
        SkillProfile p = new SkillProfile();
        p.setGroupKey(groupKey);
        p.setSkillId(skillId);
        p.setFingerprint(new DeterministicFingerprint());
        p.setBaselineStatus(BaselineStatus.BASELINE);
        p.setVersionTag("v1");
        return p;
    }

    private SkillProfile makeProfileWithCandidate(String groupKey, String skillId) {
        SkillProfile p = makeProfileWithBaseline(groupKey, skillId);
        p.setCandidateFingerprint(new DeterministicFingerprint());
        p.setBaselineStatus(BaselineStatus.CANDIDATE);
        return p;
    }

    private InteractionRecord makeToolRecord(String skillId, String toolName) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-" + System.nanoTime());
        r.setSkillId(skillId);
        r.setSystemPromptHash("hash-" + skillId);
        r.setModelResponse("{\"result\":\"ok\"}");
        r.setToolCalls(List.of(
            new ToolCall() {{ setToolName(toolName); setArguments(Map.of("arg1", "val1")); }}
        ));
        return r;
    }

    // ==================== approve ====================

    @Nested
    @DisplayName("approve - 批准候选为基线")
    class Approve {

        @Test
        @DisplayName("候选升为基线，旧基线归档")
        void candidatePromoted_oldArchived() {
            SkillProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            DeterministicFingerprint oldBaseline = profile.getFingerprint();
            DeterministicFingerprint candidate = profile.getCandidateFingerprint();
            repo.saveSkillProfile(profile);

            manager.approve("gk-1");

            SkillProfile updated = repo.findSkillByGroupKey("gk-1");
            assertEquals(BaselineStatus.BASELINE, updated.getBaselineStatus());
            // 候选已成为基线
            assertEquals(candidate, updated.getFingerprint());
            assertNull(updated.getCandidateFingerprint());
            // 版本递增
            assertEquals("v2", updated.getVersionTag());
            // 旧基线已归档
            assertEquals(1, repo.archivedBaselines.size());
            assertEquals("gk-1", repo.archivedBaselines.get(0).getSkillId());
            assertEquals("v1", repo.archivedBaselines.get(0).getVersionTag());
        }

        @Test
        @DisplayName("无候选 → 抛出 IllegalStateException")
        void noCandidate_throwsException() {
            SkillProfile profile = makeProfileWithBaseline("gk-1", "skill-1");
            repo.saveSkillProfile(profile);

            IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> manager.approve("gk-1"));
            assertTrue(ex.getMessage().contains("No candidate"));
        }

        @Test
        @DisplayName("Skill profile 不存在 → 抛出 IllegalStateException")
        void profileNotFound_throwsException() {
            assertThrows(IllegalStateException.class,
                () -> manager.approve("nonexistent"));
        }

        @Test
        @DisplayName("无旧基线（fingerprint=null）的首次 approve → 不归档")
        void approve_noOldBaseline() {
            SkillProfile profile = new SkillProfile();
            profile.setGroupKey("gk-1");
            profile.setSkillId("skill-1");
            profile.setFingerprint(null);
            profile.setCandidateFingerprint(new DeterministicFingerprint());
            profile.setBaselineStatus(BaselineStatus.CANDIDATE);
            profile.setVersionTag(null);
            repo.saveSkillProfile(profile);

            manager.approve("gk-1");

            // 无旧基线 → 不归档
            assertTrue(repo.archivedBaselines.isEmpty());
            assertEquals("v1", repo.findSkillByGroupKey("gk-1").getVersionTag());
        }

        @Test
        @DisplayName("多次 approve → 版本号递增")
        void multipleApproves_versionIncrement() {
            // v1 → v2
            SkillProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveSkillProfile(profile);
            manager.approve("gk-1");

            // 设置新候选 → v2 → v3
            SkillProfile updated = repo.findSkillByGroupKey("gk-1");
            updated.setCandidateFingerprint(new DeterministicFingerprint());
            updated.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveSkillProfile(updated);
            manager.approve("gk-1");

            assertEquals("v3", repo.findSkillByGroupKey("gk-1").getVersionTag());
            assertEquals(2, repo.archivedBaselines.size());
        }
    }

    // ==================== reject ====================

    @Nested
    @DisplayName("reject - 否决候选")
    class Reject {

        @Test
        @DisplayName("丢弃候选，保留旧基线")
        void candidateDiscarded_baselineKept() {
            SkillProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            DeterministicFingerprint oldBaseline = profile.getFingerprint();
            repo.saveSkillProfile(profile);

            manager.reject("gk-1");

            SkillProfile updated = repo.findSkillByGroupKey("gk-1");
            assertEquals(BaselineStatus.BASELINE, updated.getBaselineStatus());
            assertEquals(oldBaseline, updated.getFingerprint());
            assertNull(updated.getCandidateFingerprint());
            // 版本不变
            assertEquals("v1", updated.getVersionTag());
        }

        @Test
        @DisplayName("Skill profile 不存在 → 抛出 IllegalStateException")
        void profileNotFound_throwsException() {
            assertThrows(IllegalStateException.class,
                () -> manager.reject("nonexistent"));
        }
    }

    // ==================== rollback ====================

    @Nested
    @DisplayName("rollback - 回滚到归档基线")
    class Rollback {

        @Test
        @DisplayName("回滚到指定版本 → 恢复旧指纹")
        void rollbackToVersion_restoresOldFingerprint() {
            // 建立初始 profile 并 approve 一次
            SkillProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            DeterministicFingerprint originalBaseline = profile.getFingerprint();
            repo.saveSkillProfile(profile);
            manager.approve("gk-1");

            // 现在 profile 是 v2，归档里有 v1
            // 再设一个候选准备回滚
            SkillProfile v2Profile = repo.findSkillByGroupKey("gk-1");
            v2Profile.setCandidateFingerprint(new DeterministicFingerprint());
            v2Profile.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveSkillProfile(v2Profile);

            // 回滚到 v1
            manager.rollback("gk-1", "v1");

            SkillProfile rolled = repo.findSkillByGroupKey("gk-1");
            assertEquals(BaselineStatus.BASELINE, rolled.getBaselineStatus());
            assertEquals(originalBaseline, rolled.getFingerprint());
            assertNull(rolled.getCandidateFingerprint());
            assertEquals("v1", rolled.getVersionTag());
        }

        @Test
        @DisplayName("归档版本不存在 → 抛出 IllegalStateException")
        void archivedNotFound_throwsException() {
            SkillProfile profile = makeProfileWithBaseline("gk-1", "skill-1");
            repo.saveSkillProfile(profile);

            assertThrows(IllegalStateException.class,
                () -> manager.rollback("gk-1", "v99"));
        }

        @Test
        @DisplayName("Skill profile 不存在 → 抛出 IllegalStateException")
        void profileNotFound_throwsException() {
            assertThrows(IllegalStateException.class,
                () -> manager.rollback("nonexistent", "v1"));
        }

        @Test
        @DisplayName("回滚时当前基线也归档（保留历史）")
        void rollback_currentBaselineAlsoArchived() {
            SkillProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveSkillProfile(profile);
            manager.approve("gk-1"); // v1 → v2, 归档 v1

            // v2 设候选
            SkillProfile v2 = repo.findSkillByGroupKey("gk-1");
            v2.setCandidateFingerprint(new DeterministicFingerprint());
            v2.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveSkillProfile(v2);

            // 回滚到 v1
            manager.rollback("gk-1", "v1");

            // 应该有两条归档：v1（approve 时的）和 v2（rollback 时的）
            assertEquals(2, repo.archivedBaselines.size());
        }
    }

    // ==================== autoEstablishBaseline ====================

    @Nested
    @DisplayName("autoEstablishBaseline - 首次自动建立基线")
    class AutoEstablish {

        @Test
        @DisplayName("新 Skill → 自动建立基线")
        void newSkill_autoEstablishes() {
            InteractionRecord record = makeToolRecord("skill-new", "toolA");
            repo.saveInteraction(record);

            manager.autoEstablishBaseline(record);

            // 应该创建了 SkillProfile
            List<SkillProfile> allSkills = repo.findAllSkills();
            assertEquals(1, allSkills.size());

            SkillProfile created = allSkills.get(0);
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
            manager.autoEstablishBaseline(record);
            DeterministicFingerprint original = repo.findAllSkills().get(0).getFingerprint();

            // 再次调用
            manager.autoEstablishBaseline(record);

            // 基线不变
            List<SkillProfile> allSkills = repo.findAllSkills();
            assertEquals(1, allSkills.size());
            assertEquals(original, allSkills.get(0).getFingerprint());
        }

        @Test
        @DisplayName("null record → 安全忽略")
        void nullRecord_safeIgnore() {
            manager.autoEstablishBaseline(null);
            assertTrue(repo.findAllSkills().isEmpty());
        }

        @Test
        @DisplayName("skillId 为空 → 安全忽略")
        void emptySkillId_safeIgnore() {
            InteractionRecord r = new InteractionRecord();
            r.setSkillId(null);
            manager.autoEstablishBaseline(r);
            assertTrue(repo.findAllSkills().isEmpty());

            r.setSkillId("");
            manager.autoEstablishBaseline(r);
            assertTrue(repo.findAllSkills().isEmpty());
        }
    }

    // ==================== 版本标签生成 ====================

    @Nested
    @DisplayName("版本标签生成")
    class VersionTag {

        @Test
        @DisplayName("v1 → v2 → v3 → v4 正确递增")
        void standardVersionIncrement() {
            SkillProfile profile = makeProfileWithCandidate("gk-1", "skill-1");
            repo.saveSkillProfile(profile);

            // v1 → v2
            manager.approve("gk-1");
            assertEquals("v2", repo.findSkillByGroupKey("gk-1").getVersionTag());

            // v2 → v3
            SkillProfile p = repo.findSkillByGroupKey("gk-1");
            p.setCandidateFingerprint(new DeterministicFingerprint());
            p.setBaselineStatus(BaselineStatus.CANDIDATE);
            repo.saveSkillProfile(p);
            manager.approve("gk-1");
            assertEquals("v3", repo.findSkillByGroupKey("gk-1").getVersionTag());
        }
    }
}
