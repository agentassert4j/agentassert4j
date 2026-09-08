package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.GraphEdge;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

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
        g.addEdge("A", "B", Confidence.HIGH);

        assertEquals(1, g.getAllEdges().size());
        assertTrue(g.getAllNodes().contains("A"));
        assertTrue(g.getAllNodes().contains("B"));
    }

    @Test
    void addEdge_defaultHighConfidence() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.HIGH);

        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(Confidence.HIGH, edge.getConfidence());
    }

    @Test
    void addEdge_duplicateUpgradesConfidence() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.LOW);
        g.addEdge("A", "B", Confidence.HIGH);

        assertEquals(1, g.getAllEdges().size());
        GraphEdge edge = g.getAllEdges().get(0);
        assertEquals(Confidence.HIGH, edge.getConfidence());
    }

    @Test
    void addEdge_duplicateNeverDowngrades() {
        // 高置信边被后续低置信重复添加不得降级——合并方向钉死为「只升不降」
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.HIGH);
        g.addEdge("A", "B", Confidence.LOW);
        assertEquals(1, g.getAllEdges().size());
        assertEquals(Confidence.HIGH, g.getAllEdges().get(0).getConfidence());
    }

    @Test
    void confidence_rankIsIndependentOfDeclarationOrder() {
        // 合并按显式秩比较：秩随置信度单调递减，与枚举常量声明顺序解耦
        assertTrue(Confidence.HIGH.rank() < Confidence.LOW.rank());
    }

    @Test
    void getAllNodes_includesSinkNodes() {
        // C 只有入边没有出边
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.HIGH);
        g.addEdge("B", "C", Confidence.HIGH);

        assertEquals(3, g.getAllNodes().size());
        assertTrue(g.getAllNodes().contains("C"));
    }

    @Test
    void selfLoop_detected() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "A", Confidence.HIGH);

        Set<String> cycles = g.detectCycles();
        assertTrue(cycles.contains("A"));
    }

    @Test
    void detectCycles_tailOutsideCycle_notMarked() {
        // 真环仅 B↔C，A 是环外尾部祖先——不得被误标为环节点
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.HIGH);
        g.addEdge("B", "C", Confidence.HIGH);
        g.addEdge("C", "B", Confidence.HIGH);

        Set<String> cycles = g.detectCycles();

        assertEquals(new HashSet<>(Arrays.asList("B", "C")), cycles, "环上只有 B 和 C，尾部 A 必须排除");
    }

    @Test
    void emptyGraph_operations() {
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        assertTrue(g.getAllNodes().isEmpty());
        assertTrue(g.getAllEdges().isEmpty());
        assertTrue(g.detectCycles().isEmpty());
    }

    @Test
    void getAllEdges_preservesInsertionOrder() {
        // 同数据同插入序的边枚举序可复现——勘察视图渲染确定性的基础
        InMemoryDependencyGraph g = new InMemoryDependencyGraph();
        g.addEdge("A", "B", Confidence.HIGH);
        g.addEdge("B", "C", Confidence.LOW);
        g.addEdge("X", "Y", Confidence.LOW);

        assertEquals(Arrays.asList("A", "B", "X"),
                g.getAllEdges().stream().map(GraphEdge::getSource).collect(Collectors.toList()));
    }
}
