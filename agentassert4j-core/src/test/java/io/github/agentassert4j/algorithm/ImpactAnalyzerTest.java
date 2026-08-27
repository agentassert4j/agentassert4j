package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.AnalysisResult;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImpactAnalyzer 单元测试 — 数据驱动影响分析。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class ImpactAnalyzerTest {

    private SimpleTestRepo repo;
    private InMemoryDependencyGraph graph;
    private ImpactAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        repo = new SimpleTestRepo();
        graph = new InMemoryDependencyGraph();
        analyzer = new ImpactAnalyzer(repo, graph);
    }

    private InteractionRecord makeRecord(String skillId, String promptHash, String sessionId) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-" + skillId + "-" + System.nanoTime());
        r.setSkillId(skillId);
        r.setTemplateHash(promptHash);
        r.setSessionId(sessionId);
        r.setTimestamp(System.currentTimeMillis());
        r.setModelResponse("{\"status\":\"ok\"}");
        return r;
    }

    private SkillProfile makeSkillProfile(String groupKey, String skillId) {
        SkillProfile p = new SkillProfile();
        p.setGroupKey(groupKey);
        p.setSkillId(skillId);
        return p;
    }

    /**
     * 显式指定 timestamp 与 recordId 的记录构造（M5 测试专用）
     */
    private InteractionRecord scopedRecord(String skillId, String hash, String session,
                                           long ts, String recordId) {
        InteractionRecord r = makeRecord(skillId, hash, session);
        r.setRecordId(recordId);
        r.setTimestamp(ts);
        return r;
    }

    @Nested
    @DisplayName("冷启动检测")
    class ColdStart {

        @Test
        @DisplayName("数据库完全为空 → noBaseline，提示积累数据")
        void emptyDatabase_returnsNoBaseline() {
            AnalysisResult result = analyzer.analyzeChange("hash-a", "hash-b");

            assertFalse(result.isHasBaseline());
            assertTrue(result.getMessage().contains("未录制到任何交互数据"));
        }

        @Test
        @DisplayName("有 Skill 但无匹配 hash → noBaseline，提示 hash 不匹配")
        void noMatchHash_returnsNoBaseline() {
            // 存在一个 Skill 但 promptHash 不匹配
            repo.skillProfiles.put("gk-1", makeSkillProfile("gk-1", "skill-1"));

            AnalysisResult result = analyzer.analyzeChange("hash-nonexistent", "hash-new");

            assertFalse(result.isHasBaseline());
            assertTrue(result.getMessage().contains("未找到使用此 Prompt hash"));
        }
    }

    @Nested
    @DisplayName("单 Skill 直接受影响")
    class SingleDirectSkill {

        @Test
        @DisplayName("单 Skill 无下游 → 直接受影响 + 无额外下游")
        void singleSkill_noDownstream() {
            InteractionRecord r = makeRecord("skill-1", "hash-old", "session-1");
            repo.saveInteraction(r);
            repo.skillProfiles.put("gk-1", makeSkillProfile("gk-1", "skill-1"));

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertTrue(result.isHasBaseline());
            assertEquals(Set.of("skill-1"), result.getDirectSkills());
            // allAffectedSkills 包含自身，无下游则只有自身
            assertEquals(1, result.getAllAffectedSkills().size());
            assertTrue(result.getAllAffectedSkills().contains("skill-1"));
            // 局部 Prompt → 全量测试用例
            assertEquals(1, result.getTestCases().size());
        }

        @Test
        @DisplayName("单 Skill 多条记录 → 全量返回")
        void singleSkill_multipleRecords() {
            for (int i = 0; i < 5; i++) {
                repo.saveInteraction(makeRecord("skill-1", "hash-old", "session-" + i));
            }
            repo.skillProfiles.put("gk-1", makeSkillProfile("gk-1", "skill-1"));

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(5, result.getTestCases().size());
        }
    }

    @Nested
    @DisplayName("多 Skill + 图遍历下游")
    class GraphTraversal {

        @Test
        @DisplayName("多 Skill 直接受影响 + 图遍历到下游 Skill")
        void multipleDirectSkills_withDownstream() {
            // 直接使用 hash-old 的 Skill
            repo.saveInteraction(makeRecord("skill-a", "hash-old", "s1"));
            repo.saveInteraction(makeRecord("skill-b", "hash-old", "s2"));
            repo.skillProfiles.put("gk-a", makeSkillProfile("gk-a", "skill-a"));
            repo.skillProfiles.put("gk-b", makeSkillProfile("gk-b", "skill-b"));

            // 下游 Skill（不使用 hash-old）
            repo.saveInteraction(makeRecord("skill-c", "hash-other", "s3"));
            repo.skillProfiles.put("gk-c", makeSkillProfile("gk-c", "skill-c"));

            // 构建依赖图：skill-a → skill-c, skill-b → skill-c
            graph.addEdge("skill-a", "skill-c");
            graph.addEdge("skill-b", "skill-c");

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertTrue(result.isHasBaseline());
            assertEquals(Set.of("skill-a", "skill-b"), result.getDirectSkills());
            assertTrue(result.getAllAffectedSkills().contains("skill-c"));
            assertEquals(3, result.getAllAffectedSkills().size());
        }

        @Test
        @DisplayName("空图 + 多 Skill → 无下游，只测直接受影响的")
        void emptyGraph_multipleDirectSkills() {
            repo.saveInteraction(makeRecord("skill-a", "hash-old", "s1"));
            repo.saveInteraction(makeRecord("skill-b", "hash-old", "s2"));
            repo.skillProfiles.put("gk-a", makeSkillProfile("gk-a", "skill-a"));
            repo.skillProfiles.put("gk-b", makeSkillProfile("gk-b", "skill-b"));

            // graph 为空（无任何边）
            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(2, result.getAllAffectedSkills().size());
            assertEquals(2, result.getTestCases().size());
        }

        @Test
        @DisplayName("传递下游：A→B→C，直接受影响 A，下游包含 B 和 C")
        void transitiveDownstream() {
            repo.saveInteraction(makeRecord("skill-a", "hash-old", "s1"));
            repo.saveInteraction(makeRecord("skill-b", "hash-other", "s2"));
            repo.saveInteraction(makeRecord("skill-c", "hash-other2", "s3"));
            repo.skillProfiles.put("gk-a", makeSkillProfile("gk-a", "skill-a"));
            repo.skillProfiles.put("gk-b", makeSkillProfile("gk-b", "skill-b"));
            repo.skillProfiles.put("gk-c", makeSkillProfile("gk-c", "skill-c"));

            graph.addEdge("skill-a", "skill-b");
            graph.addEdge("skill-b", "skill-c");

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(Set.of("skill-a"), result.getDirectSkills());
            assertEquals(3, result.getAllAffectedSkills().size());
            assertTrue(result.getAllAffectedSkills().contains("skill-b"));
            assertTrue(result.getAllAffectedSkills().contains("skill-c"));
        }
    }

    @Nested
    @DisplayName("全局 Prompt 采样策略")
    class GlobalPromptSampling {

        @Test
        @DisplayName("10+ Skill 共享 Prompt → 每 Skill 采样 top 3")
        void globalPrompt_samplingStrategy() {
            // 创建 12 个使用同一 hash 的 Skill
            for (int i = 0; i < 12; i++) {
                String skillId = "skill-" + i;
                repo.saveInteraction(makeRecord(skillId, "hash-global", "s" + i));
                // 每人 5 条记录
                for (int j = 0; j < 4; j++) {
                    repo.saveInteraction(makeRecord(skillId, "hash-global", "s" + i + "-" + j));
                }
                repo.skillProfiles.put("gk-" + i, makeSkillProfile("gk-" + i, skillId));
            }

            AnalysisResult result = analyzer.analyzeChange("hash-global", "hash-new");

            assertTrue(result.isHasBaseline());
            assertEquals(12, result.getDirectSkills().size());
            // 采样策略：12 个 Skill × 3 条 = 36 条（每人 5 条取前 3）
            assertEquals(36, result.getTestCases().size());
        }

        @Test
        @DisplayName("9 个 Skill 共享 Prompt → 全量测试（低于阈值）")
        void belowThreshold_fullTest() {
            for (int i = 0; i < 9; i++) {
                String skillId = "skill-" + i;
                repo.saveInteraction(makeRecord(skillId, "hash-local", "s" + i));
                repo.skillProfiles.put("gk-" + i, makeSkillProfile("gk-" + i, skillId));
            }

            AnalysisResult result = analyzer.analyzeChange("hash-local", "hash-new");

            assertEquals(9, result.getDirectSkills().size());
            // 全量：9 条
            assertEquals(9, result.getTestCases().size());
        }

        @Test
        @DisplayName("全局 Prompt + 下游 Skill → 下游也采样")
        void globalPrompt_withDownstreamSampling() {
            // 10 个直接受影响的 Skill
            for (int i = 0; i < 10; i++) {
                String skillId = "direct-" + i;
                repo.saveInteraction(makeRecord(skillId, "hash-g", "s" + i));
                repo.saveInteraction(makeRecord(skillId, "hash-g", "s" + i + "-2"));
                repo.skillProfiles.put("gk-d" + i, makeSkillProfile("gk-d" + i, skillId));
                graph.addEdge(skillId, "downstream-skill");
            }
            // 下游 Skill 有 5 条记录
            repo.saveInteraction(makeRecord("downstream-skill", "hash-other", "ds1"));
            for (int i = 0; i < 4; i++) {
                repo.saveInteraction(makeRecord("downstream-skill", "hash-other", "ds" + (i + 2)));
            }
            repo.skillProfiles.put("gk-ds", makeSkillProfile("gk-ds", "downstream-skill"));

            AnalysisResult result = analyzer.analyzeChange("hash-g", "hash-new");

            assertEquals(10, result.getDirectSkills().size());
            assertEquals(11, result.getAllAffectedSkills().size());
            // 10 × 2 条（每人只有 2 条）+ 下游 3 条 = 23
            assertEquals(23, result.getTestCases().size());
        }
    }

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("同一个 hash 的相同 Skill 出现多次 → 去重")
        void duplicateSkillIds_dedup() {
            repo.saveInteraction(makeRecord("skill-1", "hash-old", "s1"));
            repo.saveInteraction(makeRecord("skill-1", "hash-old", "s2"));
            repo.saveInteraction(makeRecord("skill-1", "hash-old", "s3"));
            repo.skillProfiles.put("gk-1", makeSkillProfile("gk-1", "skill-1"));

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(Set.of("skill-1"), result.getDirectSkills());
        }

        @Test
        @DisplayName("newPromptHash 参数不影响查询（仅 oldPromptHash 用于数据查询）")
        void newPromptHash_notUsed() {
            repo.saveInteraction(makeRecord("skill-1", "hash-old", "s1"));
            repo.skillProfiles.put("gk-1", makeSkillProfile("gk-1", "skill-1"));

            AnalysisResult r1 = analyzer.analyzeChange("hash-old", "hash-aaa");
            AnalysisResult r2 = analyzer.analyzeChange("hash-old", "hash-bbb");

            assertEquals(r1.getDirectSkills(), r2.getDirectSkills());
            assertEquals(r1.getAllAffectedSkills(), r2.getAllAffectedSkills());
        }

        @Test
        @DisplayName("图有环 + 直接 Skill 遍历下游不报错")
        void cyclicGraph_noError() {
            repo.saveInteraction(makeRecord("skill-a", "hash-old", "s1"));
            repo.saveInteraction(makeRecord("skill-b", "hash-other", "s2"));
            repo.saveInteraction(makeRecord("skill-c", "hash-other2", "s3"));
            repo.skillProfiles.put("gk-a", makeSkillProfile("gk-a", "skill-a"));
            repo.skillProfiles.put("gk-b", makeSkillProfile("gk-b", "skill-b"));
            repo.skillProfiles.put("gk-c", makeSkillProfile("gk-c", "skill-c"));

            graph.addEdge("skill-a", "skill-b");
            graph.addEdge("skill-b", "skill-c");
            graph.addEdge("skill-c", "skill-a");

            // 不应抛异常
            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");
            assertTrue(result.isHasBaseline());
            assertTrue(result.getAllAffectedSkills().contains("skill-a"));
        }

        @Test
        @DisplayName("采样取确定性 top3（timestamp+recordId），与存储返回顺序无关")
        void sampling_deterministicRegardlessOfStorageOrder() {
            // 10 个 Skill 触发全局采样阈值；skill-0 有 5 条时间戳可分辨的记录，
            // 存储插入顺序故意乱序——top3 采样必须按 (timestamp, recordId) 规范序选取
            for (int i = 0; i < 10; i++) {
                String skillId = "skill-" + i;
                if (i == 0) {
                    long id = 300;
                    repo.saveInteraction(scopedRecord(skillId, "hash-old", "s0", id, "r-" + id));
                    id = 100;
                    repo.saveInteraction(scopedRecord(skillId, "hash-old", "s0", id, "r-" + id));
                    id = 500;
                    repo.saveInteraction(scopedRecord(skillId, "hash-old", "s0", id, "r-" + id));
                    id = 200;
                    repo.saveInteraction(scopedRecord(skillId, "hash-old", "s0", id, "r-" + id));
                    id = 400;
                    repo.saveInteraction(scopedRecord(skillId, "hash-old", "s0", id, "r-" + id));
                } else {
                    repo.saveInteraction(makeRecord(skillId, "hash-old", "s" + i));
                }
                repo.skillProfiles.put("gk-" + i, makeSkillProfile("gk-" + i, skillId));
            }

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            List<String> pickedForSkill0 = result.getTestCases().stream()
                    .filter(r -> "skill-0".equals(r.getSkillId()))
                    .map(InteractionRecord::getRecordId)
                    .sorted()
                    .toList();
            assertEquals(List.of("r-100", "r-200", "r-300"), pickedForSkill0,
                    "top3 必须是规范序（timestamp,recordId）的前三条，两次分析选例必须一致");
        }
    }

    @Nested
    @DisplayName("存储失败 - 与冷启动严格区分")
    class StorageFailure {

        @Test
        @DisplayName("直接查询失败 → 报告为存储错误而非冷启动")
        void directQueryFailure_reportedAsError() {
            SimpleTestRepo broken = new SimpleTestRepo() {
                @Override
                public Set<String> findSkillIdsByTemplateHash(String hash) {
                    throw new StorageException("findSkillIdsByTemplateHash", new RuntimeException("db locked"));
                }
            };
            ImpactAnalyzer brokenAnalyzer = new ImpactAnalyzer(broken, new InMemoryDependencyGraph());

            AnalysisResult result = brokenAnalyzer.analyzeChange("hash-old", "hash-new");

            assertTrue(result.isError(), "查询失败必须带错误标志");
            assertFalse(result.isHasBaseline());
            assertNotNull(result.getMessage());
            assertNull(result.getTestCases());
        }

        @Test
        @DisplayName("冷启动探测查询失败 → 同样报告为存储错误")
        void coldStartProbeFailure_reportedAsError() {
            SimpleTestRepo broken = new SimpleTestRepo() {
                @Override
                public List<SkillProfile> findAllSkills() {
                    throw new StorageException("findAllSkills", new RuntimeException("disk full"));
                }
            };
            ImpactAnalyzer brokenAnalyzer = new ImpactAnalyzer(broken, new InMemoryDependencyGraph());

            AnalysisResult result = brokenAnalyzer.analyzeChange("hash-old", "hash-new");

            assertTrue(result.isError(), "DB 故障不得伪装成'未录制到任何交互数据'的冷启动引导");
        }

        @Test
        @DisplayName("真正的空库 → 仍是冷启动而非错误")
        void genuinelyEmptyDb_remainsColdStart() {
            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertFalse(result.isError());
            assertFalse(result.isHasBaseline());
        }
    }
}
