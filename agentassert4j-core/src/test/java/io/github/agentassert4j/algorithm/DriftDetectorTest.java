package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.result.DriftReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DriftDetector 单元测试 — 漂移检测的对照关系、凭据口径与退化行为。
 *
 * @author axy-yxa
 * @since 2026-09-03
 */
class DriftDetectorTest {

    private SimpleTestRepo repo;

    @BeforeEach
    void setUp() {
        repo = new SimpleTestRepo();
    }

    private InMemoryDependencyGraph emptyGraph() {
        return new InMemoryDependencyGraph();
    }

    /**
     * 骨架锚点记录：身份按骨架定格，全文哈希可自由变化（同键漂移形态）
     */
    private InteractionRecord skeletonRecord(String recordId, String label, String skeletonHash, String templateHash, long timestamp) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("s-1");
        r.setTimestamp(timestamp);
        r.setInvocationId(label);
        r.setSkeletonHash(skeletonHash);
        r.setTemplateHash(templateHash);
        r.setInvocationKey("invocation:" + label + ":" + skeletonHash);
        return r;
    }

    /**
     * 声明无骨架记录：全文哈希即键细分（全文变更裂出新键形态）
     */
    private InteractionRecord fullTextRecord(String recordId, String label, String templateHash, long timestamp) {
        InteractionRecord r = new InteractionRecord();
        r.setRecordId(recordId);
        r.setSessionId("s-1");
        r.setTimestamp(timestamp);
        r.setInvocationId(label);
        r.setTemplateHash(templateHash);
        r.setInvocationKey("invocation:" + label + ":" + templateHash);
        return r;
    }

    private InvocationProfile profile(String invocationKey, String label, String templateHash) {
        InvocationProfile p = new InvocationProfile();
        p.setInvocationKey(invocationKey);
        p.setLabel(label);
        p.setTemplateHash(templateHash);
        p.setFingerprint(new DeterministicFingerprint());
        return p;
    }

    @Nested
    @DisplayName("同键漂移：画像哈希与最新记录哈希不一致")
    class SameKeyDrift {

        @Test
        @DisplayName("记录插入乱序时按规范序取最新为准")
        void latestByCanonicalOrder_notInsertionOrder() {
            String key = "invocation:order-flow:skl-1";
            repo.saveInvocationProfile(profile(key, "order-flow", "h1"));
            repo.saveInteraction(skeletonRecord("r-new", "order-flow", "skl-1", "h2", 2000L));
            repo.saveInteraction(skeletonRecord("r-old", "order-flow", "skl-1", "h1", 1000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertEquals(1, report.getSameKeyDrifts().size());
            DriftReport.DriftPoint point = report.getSameKeyDrifts().get(0);
            assertEquals(key, point.getInvocationKey());
            assertEquals("h1", point.getProfileTemplateHash());
            assertEquals("h2", point.getLatestTemplateHash());
            assertTrue(report.hasDrift());
        }

        @Test
        @DisplayName("画像未携带模板哈希也构成漂移（画像侧为 null）")
        void nullProfileHash_stillDrifts() {
            String key = "invocation:order-flow:skl-1";
            repo.saveInvocationProfile(profile(key, "order-flow", null));
            repo.saveInteraction(skeletonRecord("r-1", "order-flow", "skl-1", "h1", 1000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertEquals(1, report.getSameKeyDrifts().size());
            assertNull(report.getSameKeyDrifts().get(0).getProfileTemplateHash());
            assertEquals("h1", report.getSameKeyDrifts().get(0).getLatestTemplateHash());
        }

        @Test
        @DisplayName("哈希一致无漂移")
        void identicalHashes_noDrift() {
            String key = "invocation:order-flow:skl-1";
            repo.saveInvocationProfile(profile(key, "order-flow", "h1"));
            repo.saveInteraction(skeletonRecord("r-1", "order-flow", "skl-1", "h1", 1000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertFalse(report.hasDrift());
            assertTrue(report.getSameKeyDrifts().isEmpty());
        }

        @Test
        @DisplayName("最新可分组记录无模板哈希 → 零模板点排除出检测集并计数")
        void zeroTemplateLatest_excludedAndCounted() {
            String key = "invocation:order-flow:skl-1";
            repo.saveInvocationProfile(profile(key, "order-flow", "h1"));
            repo.saveInteraction(skeletonRecord("r-new", "order-flow", "skl-1", null, 2000L));
            repo.saveInteraction(skeletonRecord("r-old", "order-flow", "skl-1", "h1", 1000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertFalse(report.hasDrift());
            assertEquals(1, report.getZeroTemplateProfiles());
        }
    }

    @Nested
    @DisplayName("标签裂键：未建档新键的声明标签与既有画像相同")
    class LabelSplits {

        @Test
        @DisplayName("同标签裂出新键进报告，携带最新模板哈希")
        void splitKey_reported() {
            InvocationProfile existing = profile("invocation:order-flow:h1", "order-flow", "h1");
            repo.saveInvocationProfile(existing);
            repo.saveInteraction(fullTextRecord("r-old", "order-flow", "h1", 1000L));
            repo.saveInteraction(fullTextRecord("r-new", "order-flow", "h2", 2000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertEquals(1, report.getLabelSplits().size());
            DriftReport.DriftPoint point = report.getLabelSplits().get(0);
            assertEquals("invocation:order-flow:h2", point.getInvocationKey());
            assertEquals("order-flow", point.getLabel());
            assertEquals("h2", point.getLatestTemplateHash());
            assertTrue(report.hasDrift());
        }

        @Test
        @DisplayName("既有画像键下的同标签记录不重复报告")
        void establishedKeys_notReported() {
            InvocationProfile existing = profile("invocation:order-flow:h1", "order-flow", "h1");
            repo.saveInvocationProfile(existing);
            repo.saveInteraction(fullTextRecord("r-1", "order-flow", "h1", 1000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertFalse(report.hasDrift());
            assertTrue(report.getLabelSplits().isEmpty());
        }

        @Test
        @DisplayName("全新标签（无画像）的记录不进漂移集")
        void unknownLabel_notDrift() {
            repo.saveInteraction(fullTextRecord("r-1", "brand-new", "h9", 1000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertFalse(report.hasDrift());
        }
    }

    @Nested
    @DisplayName("下游扩散与退化行为")
    class PropagationAndDegradation {

        @Test
        @DisplayName("漂移键经依赖图扩散为下游波及集，不含漂移键自身")
        void downstreamPropagated() {
            String driftedKey = "invocation:order-flow:skl-1";
            repo.saveInvocationProfile(profile(driftedKey, "order-flow", "h1"));
            repo.saveInteraction(skeletonRecord("r-1", "order-flow", "skl-1", "h2", 1000L));

            InMemoryDependencyGraph graph = new InMemoryDependencyGraph();
            graph.addEdge(driftedKey, "invocation:downstream:skl-9", Confidence.HIGH, null);

            DriftReport report = DriftDetector.detect(repo, graph);

            assertEquals(Collections.singletonList("invocation:downstream:skl-9"), report.getDownstreamKeys());
        }

        @Test
        @DisplayName("单条损坏记录倒序回退到次新可分组记录")
        void corruptRecord_fallsBackToOlder() {
            String key = "invocation:order-flow:skl-1";
            repo.saveInvocationProfile(profile(key, "order-flow", "h1"));
            InteractionRecord corrupt = new InteractionRecord() {
                @Override
                public String getInvocationId() {
                    throw new IllegalStateException("corrupt record");
                }
            };
            corrupt.setRecordId("r-corrupt");
            corrupt.setTimestamp(2000L);
            corrupt.setTemplateHash("h-corrupt");
            corrupt.setInvocationKey(key);
            repo.saveInteraction(corrupt);
            repo.saveInteraction(skeletonRecord("r-older", "order-flow", "skl-1", "h2", 1000L));

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertEquals(1, report.getSameKeyDrifts().size());
            assertEquals("h2", report.getSameKeyDrifts().get(0).getLatestTemplateHash());
        }

        @Test
        @DisplayName("存储键与现算键不一致的记录不作为身份凭据")
        void keyMismatchRecord_notUsableAsAnchor() {
            String key = "invocation:order-flow:skl-1";
            repo.saveInvocationProfile(profile(key, "order-flow", "h1"));
            InteractionRecord mismatched = skeletonRecord("r-mismatch", "other-flow", "skl-1", "h9", 2000L);
            // 存储键手工指到别的桶：现算键与存储键不一致，不得作为本桶身份凭据
            mismatched.setInvocationKey(key);
            repo.saveInteraction(mismatched);

            DriftReport report = DriftDetector.detect(repo, emptyGraph());

            assertFalse(report.hasDrift());
            assertEquals(1, report.getZeroTemplateProfiles());
        }

        @Test
        @DisplayName("画像枚举失败安全退化为空报告并计数")
        void profileEnumerationFailure_degradesVisibly() {
            SimpleTestRepo failingRepo = new SimpleTestRepo() {
                @Override
                public List<InvocationProfile> findAllInvocations() {
                    throw new IllegalStateException("storage broken");
                }
            };

            DriftReport report = DriftDetector.detect(failingRepo, emptyGraph());

            assertFalse(report.hasDrift());
            assertEquals(1, report.getSkippedQueries());
        }

        @Test
        @DisplayName("单键查询失败跳过该画像并计数，不中断巡检")
        void perKeyQueryFailure_skippedAndCounted() {
            String brokenKey = "invocation:broken:skl-1";
            String healthyKey = "invocation:healthy:skl-1";
            repo.saveInvocationProfile(profile(brokenKey, "broken", "h1"));
            repo.saveInvocationProfile(profile(healthyKey, "healthy", "h1"));
            repo.saveInteraction(skeletonRecord("r-h", "healthy", "skl-1", "h2", 1000L));

            SimpleTestRepo partialRepo = new SimpleTestRepo() {
                @Override
                public List<InteractionRecord> findByInvocationKey(String invocationKey) {
                    if (brokenKey.equals(invocationKey)) {
                        throw new IllegalStateException("broken bucket");
                    }
                    return super.findByInvocationKey(invocationKey);
                }
            };
            partialRepo.interactions.addAll(repo.interactions);
            partialRepo.invocationProfiles.putAll(repo.invocationProfiles);

            DriftReport report = DriftDetector.detect(partialRepo, emptyGraph());

            assertEquals(1, report.getSkippedQueries());
            assertEquals(1, report.getSameKeyDrifts().size());
            assertEquals(healthyKey, report.getSameKeyDrifts().get(0).getInvocationKey());
        }
    }
}
