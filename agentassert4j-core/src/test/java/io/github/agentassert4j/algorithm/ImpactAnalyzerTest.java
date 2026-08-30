package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.AnalysisResult;
import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ImpactAnalyzer 的单元测试 — 影响分析与图遍历共用统一调用点键空间
 * （声明与否同路），夹具按生产 enrich 契约预填 invocationKey。
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

    /**
     * 夹具键派生：与声明记录的生产文法同构（invocation:label:templateHash），
     * 键空间统一是本类所有图边与期望的前提。
     */
    private static String key(String label, String templateHash) {
        return "invocation:" + label + ":" + templateHash;
    }

    private InteractionRecord makeRecord(String label, String promptHash, String sessionId) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId("rec-" + UUID.randomUUID());
        r.setInvocationId(label);
        r.setTemplateHash(promptHash);
        r.setInvocationKey(key(label, promptHash));
        r.setSessionId(sessionId);
        r.setTimestamp(System.currentTimeMillis());
        r.setModelResponse("{\"status\":\"ok\"}");
        return r;
    }

    private InvocationProfile makeInvocationProfile(String invocationKey, String label) {
        InvocationProfile p = new InvocationProfile();
        p.setInvocationKey(invocationKey);
        p.setLabel(label);
        return p;
    }

    /**
     * 显式指定 timestamp 与 recordId 的记录构造（排序键平局决胜测试用）
     */
    private InteractionRecord scopedRecord(String label, String hash, String session, long ts, String recordId) {
        InteractionRecord r = makeRecord(label, hash, session);
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
        @DisplayName("有调用点但无匹配 hash → noBaseline，提示 hash 不匹配")
        void noMatchHash_returnsNoBaseline() {
            // 存在一个调用点但 promptHash 不匹配
            repo.invocationProfiles.put(key("order-flow", "hash-x"), makeInvocationProfile(key("order-flow", "hash-x"), "order-flow"));

            AnalysisResult result = analyzer.analyzeChange("hash-nonexistent", "hash-new");

            assertFalse(result.isHasBaseline());
            assertTrue(result.getMessage().contains("未找到使用此 Prompt hash"));
        }
    }

    @Nested
    @DisplayName("单调用点直接受影响")
    class SingleDirectInvocation {

        @Test
        @DisplayName("单调用点无下游 → 直接受影响 + 无额外下游")
        void singleInvocation_noDownstream() {
            InteractionRecord r = makeRecord("order-flow", "hash-old", "session-1");
            repo.saveInteraction(r);
            repo.invocationProfiles.put(r.getInvocationKey(), makeInvocationProfile(r.getInvocationKey(), "order-flow"));

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertTrue(result.isHasBaseline());
            assertEquals(Collections.singleton(key("order-flow", "hash-old")), result.getDirectInvocations());
            // allAffectedInvocations 包含自身，无下游则只有自身
            assertEquals(1, result.getAllAffectedInvocations().size());
            assertTrue(result.getAllAffectedInvocations().contains(key("order-flow", "hash-old")));
            // 局部 Prompt → 全量测试用例
            assertEquals(1, result.getTestCases().size());
        }

        @Test
        @DisplayName("单调用点多条记录 → 全量返回")
        void singleInvocation_multipleRecords() {
            for (int i = 0; i < 5; i++) {
                repo.saveInteraction(makeRecord("order-flow", "hash-old", "session-" + i));
            }
            repo.invocationProfiles.put(key("order-flow", "hash-old"), makeInvocationProfile(key("order-flow", "hash-old"), "order-flow"));

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(5, result.getTestCases().size());
        }
    }

    @Nested
    @DisplayName("多调用点 + 图遍历下游")
    class GraphTraversal {

        @Test
        @DisplayName("多调用点直接受影响 + 图遍历到下游")
        void multipleDirectInvocations_withDownstream() {
            // 直接使用 hash-old 的调用点
            InteractionRecord a = makeRecord("flow-a", "hash-old", "s1");
            InteractionRecord b = makeRecord("flow-b", "hash-old", "s2");
            repo.saveInteraction(a);
            repo.saveInteraction(b);
            repo.invocationProfiles.put(a.getInvocationKey(), makeInvocationProfile(a.getInvocationKey(), "flow-a"));
            repo.invocationProfiles.put(b.getInvocationKey(), makeInvocationProfile(b.getInvocationKey(), "flow-b"));

            // 下游调用点（不使用 hash-old）
            InteractionRecord c = makeRecord("flow-c", "hash-other", "s3");
            repo.saveInteraction(c);
            repo.invocationProfiles.put(c.getInvocationKey(), makeInvocationProfile(c.getInvocationKey(), "flow-c"));

            // 构建依赖图：a → c, b → c
            graph.addEdge(a.getInvocationKey(), c.getInvocationKey(), Confidence.HIGH, null);
            graph.addEdge(b.getInvocationKey(), c.getInvocationKey(), Confidence.HIGH, null);

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertTrue(result.isHasBaseline());
            assertEquals(new HashSet<>(Arrays.asList(key("flow-a", "hash-old"), key("flow-b", "hash-old"))), result.getDirectInvocations());
            assertTrue(result.getAllAffectedInvocations().contains(c.getInvocationKey()));
            assertEquals(3, result.getAllAffectedInvocations().size());
        }

        @Test
        @DisplayName("空图 + 多调用点 → 无下游，只测直接受影响的")
        void emptyGraph_multipleDirectInvocations() {
            repo.saveInteraction(makeRecord("flow-a", "hash-old", "s1"));
            repo.saveInteraction(makeRecord("flow-b", "hash-old", "s2"));

            // graph 为空（无任何边）
            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(2, result.getAllAffectedInvocations().size());
            assertEquals(2, result.getTestCases().size());
        }

        @Test
        @DisplayName("传递下游：A→B→C，直接受影响 A，下游包含 B 和 C")
        void transitiveDownstream() {
            InteractionRecord a = makeRecord("flow-a", "hash-old", "s1");
            InteractionRecord b = makeRecord("flow-b", "hash-other", "s2");
            InteractionRecord c = makeRecord("flow-c", "hash-other2", "s3");
            repo.saveInteraction(a);
            repo.saveInteraction(b);
            repo.saveInteraction(c);

            graph.addEdge(a.getInvocationKey(), b.getInvocationKey(), Confidence.HIGH, null);
            graph.addEdge(b.getInvocationKey(), c.getInvocationKey(), Confidence.HIGH, null);

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(Collections.singleton(key("flow-a", "hash-old")), result.getDirectInvocations());
            assertEquals(3, result.getAllAffectedInvocations().size());
            assertTrue(result.getAllAffectedInvocations().contains(b.getInvocationKey()));
            assertTrue(result.getAllAffectedInvocations().contains(c.getInvocationKey()));
        }
    }

    @Nested
    @DisplayName("全局 Prompt 采样策略")
    class GlobalPromptSampling {

        @Test
        @DisplayName("10+ 调用点共享 Prompt → 每调用点采样 top 3")
        void globalPrompt_samplingStrategy() {
            // 创建 12 个使用同一 hash 的调用点
            for (int i = 0; i < 12; i++) {
                String label = "flow-" + i;
                repo.saveInteraction(makeRecord(label, "hash-global", "s" + i));
                // 每人 5 条记录
                for (int j = 0; j < 4; j++) {
                    repo.saveInteraction(makeRecord(label, "hash-global", "s" + i + "-" + j));
                }
            }

            AnalysisResult result = analyzer.analyzeChange("hash-global", "hash-new");

            assertTrue(result.isHasBaseline());
            assertEquals(12, result.getDirectInvocations().size());
            // 采样策略：12 个调用点 × 3 条 = 36 条（每人 5 条取前 3）
            assertEquals(36, result.getTestCases().size());
        }

        @Test
        @DisplayName("9 个调用点共享 Prompt → 全量测试（低于阈值）")
        void belowThreshold_fullTest() {
            for (int i = 0; i < 9; i++) {
                repo.saveInteraction(makeRecord("flow-" + i, "hash-local", "s" + i));
            }

            AnalysisResult result = analyzer.analyzeChange("hash-local", "hash-new");

            assertEquals(9, result.getDirectInvocations().size());
            // 全量：9 条
            assertEquals(9, result.getTestCases().size());
        }

        @Test
        @DisplayName("全局 Prompt + 下游调用点 → 下游也采样")
        void globalPrompt_withDownstreamSampling() {
            // 10 个直接受影响的调用点
            for (int i = 0; i < 10; i++) {
                String label = "direct-" + i;
                InteractionRecord r1 = makeRecord(label, "hash-g", "s" + i);
                repo.saveInteraction(r1);
                repo.saveInteraction(makeRecord(label, "hash-g", "s" + i + "-2"));
                graph.addEdge(r1.getInvocationKey(), key("downstream", "hash-other"), Confidence.HIGH, null);
            }
            // 下游调用点有 5 条记录
            repo.saveInteraction(makeRecord("downstream", "hash-other", "ds1"));
            for (int i = 0; i < 4; i++) {
                repo.saveInteraction(makeRecord("downstream", "hash-other", "ds" + (i + 2)));
            }

            AnalysisResult result = analyzer.analyzeChange("hash-g", "hash-new");

            assertEquals(10, result.getDirectInvocations().size());
            assertEquals(11, result.getAllAffectedInvocations().size());
            // 10 × 2 条（每人只有 2 条）+ 下游 3 条 = 23
            assertEquals(23, result.getTestCases().size());
        }
    }

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("同一个 hash 的相同调用点出现多次 → 去重")
        void duplicateInvocationKeys_dedup() {
            repo.saveInteraction(makeRecord("order-flow", "hash-old", "s1"));
            repo.saveInteraction(makeRecord("order-flow", "hash-old", "s2"));
            repo.saveInteraction(makeRecord("order-flow", "hash-old", "s3"));

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            assertEquals(Collections.singleton(key("order-flow", "hash-old")), result.getDirectInvocations());
        }

        @Test
        @DisplayName("newPromptHash 参数不影响查询（仅 oldPromptHash 用于数据查询）")
        void newPromptHash_notUsed() {
            repo.saveInteraction(makeRecord("order-flow", "hash-old", "s1"));

            AnalysisResult r1 = analyzer.analyzeChange("hash-old", "hash-aaa");
            AnalysisResult r2 = analyzer.analyzeChange("hash-old", "hash-bbb");

            assertEquals(r1.getDirectInvocations(), r2.getDirectInvocations());
            assertEquals(r1.getAllAffectedInvocations(), r2.getAllAffectedInvocations());
        }

        @Test
        @DisplayName("图有环 + 直接调用点遍历下游不报错")
        void cyclicGraph_noError() {
            InteractionRecord a = makeRecord("flow-a", "hash-old", "s1");
            InteractionRecord b = makeRecord("flow-b", "hash-other", "s2");
            InteractionRecord c = makeRecord("flow-c", "hash-other2", "s3");
            repo.saveInteraction(a);
            repo.saveInteraction(b);
            repo.saveInteraction(c);

            graph.addEdge(a.getInvocationKey(), b.getInvocationKey(), Confidence.HIGH, null);
            graph.addEdge(b.getInvocationKey(), c.getInvocationKey(), Confidence.HIGH, null);
            graph.addEdge(c.getInvocationKey(), a.getInvocationKey(), Confidence.HIGH, null);

            // 不应抛异常
            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");
            assertTrue(result.isHasBaseline());
            assertTrue(result.getAllAffectedInvocations().contains(a.getInvocationKey()));
        }

        @Test
        @DisplayName("采样取确定性 top3（timestamp+recordId），与存储返回顺序无关")
        void sampling_deterministicRegardlessOfStorageOrder() {
            // 10 个调用点触发全局采样阈值；flow-0 有 5 条时间戳可分辨的记录，
            // 存储插入顺序故意乱序——top3 采样必须按 (timestamp, recordId) 规范序选取
            for (int i = 0; i < 10; i++) {
                String label = "flow-" + i;
                if (i == 0) {
                    long id = 300;
                    repo.saveInteraction(scopedRecord(label, "hash-old", "s0", id, "r-" + id));
                    id = 100;
                    repo.saveInteraction(scopedRecord(label, "hash-old", "s0", id, "r-" + id));
                    id = 500;
                    repo.saveInteraction(scopedRecord(label, "hash-old", "s0", id, "r-" + id));
                    id = 200;
                    repo.saveInteraction(scopedRecord(label, "hash-old", "s0", id, "r-" + id));
                    id = 400;
                    repo.saveInteraction(scopedRecord(label, "hash-old", "s0", id, "r-" + id));
                } else {
                    repo.saveInteraction(makeRecord(label, "hash-old", "s" + i));
                }
            }

            AnalysisResult result = analyzer.analyzeChange("hash-old", "hash-new");

            List<String> pickedForFlow0 = result.getTestCases().stream().filter(r -> "flow-0".equals(r.getInvocationId())).map(InteractionRecord::getRecordId).sorted().collect(Collectors.toList());
            assertEquals(Arrays.asList("r-100", "r-200", "r-300"), pickedForFlow0, "top3 必须是规范序（timestamp,recordId）的前三条，两次分析选例必须一致");
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
                public Set<String> findInvocationKeysByTemplateHash(String hash) {
                    throw new StorageException("findInvocationKeysByTemplateHash", new RuntimeException("db locked"));
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
                public List<InvocationProfile> findAllInvocations() {
                    throw new StorageException("findAllInvocations", new RuntimeException("disk full"));
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
