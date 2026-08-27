package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.GraphEdge;
import org.junit.jupiter.api.Test;

import java.util.List;
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
        g.addEdge("A", "C", Confidence.TRANSPARENT, List.of("B"));

        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(Confidence.TRANSPARENT, edge.getConfidence());
        assertEquals(List.of("B"), edge.getThroughNodes());
    }

    @Test
    void addEdge_mergesThroughNodes() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "D", Confidence.TRANSPARENT, List.of("B"));
        g.addEdge("A", "D", Confidence.TRANSPARENT, List.of("C"));

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
        assertEquals(Set.of("B"), result);
    }

    @Test
    void traverseDownstream_multiHop() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("C", "D");

        Set<String> result = g.traverseDownstream("A");
        assertEquals(Set.of("B", "C", "D"), result);
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
        assertEquals(Set.of("B", "C"), result);
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

        assertEquals(Set.of("A", "B"), g.getPredecessors("C"));
    }

    @Test
    void getSuccessors_multiple() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("A", "C");

        assertEquals(Set.of("B", "C"), g.getSuccessors("A"));
    }

    @Test
    void getPredecessors_noPredecessors() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");

        assertTrue(g.getPredecessors("A").isEmpty());
    }

    @Test
    void removeNode_cleansAllEdges() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("X", "B");

        g.removeNode("B");

        // B 的出边和入边都应被清理
        assertEquals(0, g.edgeCount());
        assertTrue(g.getSuccessors("A").isEmpty());
        assertTrue(g.getSuccessors("X").isEmpty());
        assertTrue(g.getPredecessors("C").isEmpty());
    }

    @Test
    void removeNode_nonExistent_noError() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");

        assertDoesNotThrow(() -> g.removeNode("NONEXISTENT"));
        assertEquals(1, g.edgeCount());
    }

    @Test
    void compressExcludedNodes_noExclusion() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");

        g.compressExcludedNodes(Set.of());

        assertEquals(2, g.edgeCount());
        assertTrue(g.getSuccessors("A").contains("B"));
    }

    @Test
    void compressExcludedNodes_singleLayer() {
        // A → infra → C → 排除 infra 后 → A → C（穿透边）
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "infra");
        g.addEdge("infra", "C");

        g.compressExcludedNodes(Set.of("infra"));

        // infra 被移除，A → C 穿透边建立
        assertFalse(g.getAllNodes().contains("infra"));
        assertTrue(g.getSuccessors("A").contains("C"));
        assertEquals(1, g.edgeCount());
        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(Confidence.TRANSPARENT, edge.getConfidence());
        assertTrue(edge.getThroughNodes().contains("infra"));
    }

    @Test
    void compressExcludedNodes_multiLayer() {
        // A → infra1 → infra2 → D
        // 排除 infra1 和 infra2 → A → D（穿透边）
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "infra1");
        g.addEdge("infra1", "infra2");
        g.addEdge("infra2", "D");

        g.compressExcludedNodes(Set.of("infra1", "infra2"));

        assertFalse(g.getAllNodes().contains("infra1"));
        assertFalse(g.getAllNodes().contains("infra2"));
        assertTrue(g.getSuccessors("A").contains("D"));
    }

    @Test
    void compressExcludedNodes_preservesExistingEdges() {
        // A → B → C, 排除 B
        // 同时有 A → D（不受影响）
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        g.addEdge("B", "C");
        g.addEdge("A", "D");

        g.compressExcludedNodes(Set.of("B"));

        // A → C 穿透边 + A → D 保留
        assertTrue(g.getSuccessors("A").contains("C"));
        assertTrue(g.getSuccessors("A").contains("D"));
        assertFalse(g.getAllNodes().contains("B"));
    }

    @Test
    void compressExcludedNodes_nullSet_noError() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B");
        assertDoesNotThrow(() -> g.compressExcludedNodes(null));
    }

    @Test
    void toJson_fromJson_roundTrip() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.HIGH);
        g.addEdge("B", "C", Confidence.LOW);
        g.addEdge("X", "Y", Confidence.TRANSPARENT, List.of("Z"));

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
}
