package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.GraphEdge;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * InMemoryDependencyGraph 的单元测试。
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
class InMemoryDependencyGraphTest {

    @Test
    void addEdge_singleEdge() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");

        assertEquals(1, g.edgeCount());
        assertTrue(g.getSuccessors("A").contains("B"));
        assertTrue(g.getPredecessors("B").contains("A"));
    }

    @Test
    void addEdge_defaultHighConfidence() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");

        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(Confidence.HIGH, edge.getConfidence());
    }

    @Test
    void addEdge_duplicateUpgradesConfidence() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.LOW);
        g.addEdge("A", "B", Confidence.HIGH);

        assertEquals(1, g.edgeCount());
        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(Confidence.HIGH, edge.getConfidence());
    }

    @Test
    void addEdge_transparentWithThroughNodes() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "C", Confidence.TRANSPARENT, Collections.singletonList("B"));

        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(Confidence.TRANSPARENT, edge.getConfidence());
        assertEquals(Collections.singletonList("B"), edge.getThroughNodes());
    }

    @Test
    void addEdge_mergesThroughNodes() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "D", Confidence.TRANSPARENT, Collections.singletonList("B"));
        g.addEdge("A", "D", Confidence.TRANSPARENT, Collections.singletonList("C"));

        assertEquals(1, g.edgeCount());
        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(2, edge.getThroughNodes().size());
        assertTrue(edge.getThroughNodes().contains("B"));
        assertTrue(edge.getThroughNodes().contains("C"));
    }

    @Test
    void traverseDownstream_singleHop() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");

        Set<String> result = g.traverseDownstream("A");
        assertEquals(Collections.singleton("B"), result);
    }

    @Test
    void traverseDownstream_multiHop() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("C", "D");

        Set<String> result = g.traverseDownstream("A");
        assertEquals(new HashSet<>(Arrays.asList("B", "C", "D")), result);
    }

    @Test
    void traverseDownstream_noDownstream() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("X", "Y");

        Set<String> result = g.traverseDownstream("Y");
        assertTrue(result.isEmpty());
    }

    @Test
    void traverseDownstream_cycle_noInfiniteLoop() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("C", "A"); // 环

        Set<String> result = g.traverseDownstream("A");
        assertEquals(new HashSet<>(Arrays.asList("B", "C")), result);
    }

    @Test
    void traverseDownstream_nonExistentNode() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        Set<String> result = g.traverseDownstream("NONEXISTENT");
        assertTrue(result.isEmpty());
    }

    @Test
    void detectCycles_noCycle() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");

        assertTrue(g.detectCycles().isEmpty());
    }

    @Test
    void detectCycles_simpleCycle() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "A");

        Set<String> cycles = g.detectCycles();
        assertFalse(cycles.isEmpty());
        assertTrue(cycles.contains("A"));
        assertTrue(cycles.contains("B"));
    }

    @Test
    void detectCycles_threeNodeCycle() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("C", "A");

        Set<String> cycles = g.detectCycles();
        assertEquals(3, cycles.size());
    }

    @Test
    void detectCycles_emptyGraph() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        assertTrue(g.detectCycles().isEmpty());
    }

    @Test
    void getPredecessors_multiple() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "C");
        g.addEdge("B", "C");

        assertEquals(new HashSet<>(Arrays.asList("A", "B")), g.getPredecessors("C"));
    }

    @Test
    void getSuccessors_multiple() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("A", "C");

        assertEquals(new HashSet<>(Arrays.asList("B", "C")), g.getSuccessors("A"));
    }

    @Test
    void getPredecessors_noPredecessors() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");

        assertTrue(g.getPredecessors("A").isEmpty());
    }

    @Test
    void toJson_fromJson_roundTrip() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.HIGH);
        g.addEdge("B", "C", Confidence.LOW);
        g.addEdge("X", "Y", Confidence.TRANSPARENT, Collections.singletonList("Z"));

        String json = g.toJson();
        assertNotNull(json);
        assertTrue(json.contains("A"));
        assertTrue(json.contains("TRANSPARENT"));

        InMemoryDependencyGraph restored = InMemoryDependencyGraph.fromJson(json);
        assertEquals(g.nodeCount(), restored.nodeCount());
        assertEquals(g.edgeCount(), restored.edgeCount());
        assertTrue(restored.getSuccessors("A").contains("B"));
        assertTrue(restored.getSuccessors("B").contains("C"));
        assertTrue(restored.getSuccessors("X").contains("Y"));
    }

    @Test
    void fromJson_emptyJson() {
        InMemoryDependencyGraph g = InMemoryDependencyGraph.fromJson("");
        assertEquals(0, g.edgeCount());

        InMemoryDependencyGraph g2 = InMemoryDependencyGraph.fromJson(null);
        assertEquals(0, g2.edgeCount());
    }

    @Test
    void toJson_emptyGraph() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        String json = g.toJson();
        assertNotNull(json);

        InMemoryDependencyGraph restored = InMemoryDependencyGraph.fromJson(json);
        assertEquals(0, restored.edgeCount());
    }

    @Test
    void emptyGraph_operations() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        assertEquals(0, g.nodeCount());
        assertEquals(0, g.edgeCount());
        assertTrue(g.getAllNodes().isEmpty());
        assertTrue(g.getAllEdges().isEmpty());
        assertTrue(g.getSuccessors("X").isEmpty());
        assertTrue(g.getPredecessors("X").isEmpty());
        assertTrue(g.traverseDownstream("X").isEmpty());
        assertTrue(g.detectCycles().isEmpty());
    }

    @Test
    void nodeCount_includesSinkNodes() {
        // C 只有入边没有出边
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");

        assertEquals(3, g.nodeCount());
        assertTrue(g.getAllNodes().contains("C"));
    }

    @Test
    void selfLoop_detected() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "A");

        Set<String> cycles = g.detectCycles();
        assertTrue(cycles.contains("A"));
    }

    @Test
    void fromJson_corruptEdges_skippedFailClosed() {
        // source/target 缺失或空白、confidence 非法的边整条跳过，不造幽灵节点、不默认最高置信度
        String json = "{\"edges\":[" + "{\"target\":\"B\",\"confidence\":\"HIGH\"},"          // 缺 source
                + "{\"source\":\"A\",\"confidence\":\"HIGH\"},"          // 缺 target
                + "{\"source\":\"\",\"target\":\"B\",\"confidence\":\"HIGH\"},"  // 空 source
                + "{\"source\":\"A\",\"target\":\"B\",\"confidence\":\"BOGUS\"}," // 非法 confidence
                + "{\"source\":\"A\",\"target\":\"B\",\"confidence\":\"LOW\"}]}";

        InMemoryDependencyGraph g = InMemoryDependencyGraph.fromJson(json);

        assertEquals(1, g.edgeCount(), "只有完整合法的边可入库");
        assertEquals(2, g.nodeCount());
        assertTrue(g.getAllEdges().get(0).getConfidence() == Confidence.LOW);
    }

    @Test
    void detectCycles_tailOutsideCycle_notMarked() {
        // 真环仅 B↔C，A 是环外尾部祖先——不得被误标为环节点
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("C", "B");

        Set<String> cycles = g.detectCycles();

        assertEquals(new HashSet<>(Arrays.asList("B", "C")), cycles, "环上只有 B 和 C，尾部 A 必须排除");
    }

    @Test
    void toJson_reproducibleBytes_sameDataSameSnapshot() {
        // 快照字节可复现：同数据同插入序两次重建产出完全一致（可 diff、可调试）
        String first = buildSampleGraphJson();
        String second = buildSampleGraphJson();
        assertEquals(first, second);
    }

    private static String buildSampleGraphJson() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("queryOrder", "formatOrder", Confidence.HIGH);
        g.addEdge("queryOrder", "checkStock", Confidence.LOW);
        g.addEdge("checkStock", "formatOrder", Confidence.HIGH, Arrays.asList("transparentNode"));
        return g.toJson();
    }

    @Test
    void roundtrip_jsonPreservesEdgesAndOrder() {
        InMemoryDependencyGraph original = new InMemoryDependencyGraph();
        original.addEdge("a", "b", Confidence.HIGH);
        original.addEdge("b", "c", Confidence.LOW, Arrays.asList("t"));

        InMemoryDependencyGraph restored = InMemoryDependencyGraph.fromJson(original.toJson());

        assertEquals(original.edgeCount(), restored.edgeCount());
        assertEquals(original.getAllEdges().stream().map(GraphEdge::getSource).collect(java.util.stream.Collectors.toList()).toString(), restored.getAllEdges().stream().map(GraphEdge::getSource).collect(java.util.stream.Collectors.toList()).toString(), "边插入序在快照往返后保持一致");
    }
}
