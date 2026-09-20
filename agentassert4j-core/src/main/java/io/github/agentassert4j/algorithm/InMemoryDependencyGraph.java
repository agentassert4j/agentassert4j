package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.GraphEdge;

import java.util.*;

/**
 * 内存依赖图谱 — 纯内存邻接表，从交互记录现场重建的勘察视图。
 *
 * <p>规模（10-50 节点，10-200 边）远低于需要图数据库的门槛。
 * 正向邻接表 LinkedHashMap 插入序保证同数据重建的边序可复现。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class InMemoryDependencyGraph {

    /**
     * 正向邻接表：source → (target → edge)
     */
    private final Map<String, Map<String, GraphEdge>> outEdges = new LinkedHashMap<>();

    /**
     * 只出现于记录、尚未参与任何边的调用点键——勘察视图的节点全集语义：
     * 库里有数据时节点行非空，「数据在、无边」与「没数据」由此可区分
     */
    private final Set<String> isolatedNodes = new LinkedHashSet<>();

    /**
     * 登记一个调用点键为节点（无边也入集；null 与空串忽略）。
     */
    public void addNode(String node) {
        if (node != null && !node.isEmpty()) {
            isolatedNodes.add(node);
        }
    }

    /**
     * 添加一条边（携带证据载荷：命中值 + 源/目标记录 id；LOW 边传 null）。
     * 同一条边多次添加时保留高置信度（秩小者优先）：
     * LOW→HIGH 升级时替换证据（高置信度证据更有解释力）；同级重复保留最早证据
     * （调用方迭代序确定 ⇒ 可复现）；HIGH→LOW 不降级、证据不动。
     */
    public void addEdge(String src, String tgt, Confidence confidence,
                        String evidenceValue, String evidenceSourceRecordId, String evidenceTargetRecordId) {
        Map<String, GraphEdge> targets = outEdges.computeIfAbsent(src, k -> new LinkedHashMap<>());
        GraphEdge existing = targets.get(tgt);
        if (existing != null) {
            // 已存在：升级置信度（保留最高的）——按显式秩比较，不依赖枚举声明顺序
            if (existing.getConfidence().rank() > confidence.rank()) {
                existing.setConfidence(confidence);
                existing.setEvidenceValue(evidenceValue);
                existing.setEvidenceSourceRecordId(evidenceSourceRecordId);
                existing.setEvidenceTargetRecordId(evidenceTargetRecordId);
            }
        } else {
            GraphEdge edge = new GraphEdge(src, tgt, confidence);
            edge.setEvidenceValue(evidenceValue);
            edge.setEvidenceSourceRecordId(evidenceSourceRecordId);
            edge.setEvidenceTargetRecordId(evidenceTargetRecordId);
            targets.put(tgt, edge);
        }
    }

    /**
     * 环检测 — DFS 染色法 + 显式递归栈：白色未访问、灰色在当前递归栈中。
     * 返回所有参与环的节点集合（空集表示无环）；环外尾部祖先不在环上，
     * 只有栈中回边目标到栈顶的区段才是环。
     */
    public Set<String> detectCycles() {
        Set<String> white = new HashSet<>(getAllNodes());
        Set<String> gray = new HashSet<>();
        Set<String> cycleNodes = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();

        for (String node : getAllNodes()) {
            if (white.contains(node)) {
                dfsCycle(node, white, gray, cycleNodes, stack);
            }
        }
        return cycleNodes;
    }

    private void dfsCycle(String node, Set<String> white, Set<String> gray, Set<String> cycleNodes, Deque<String> stack) {
        white.remove(node);
        gray.add(node);
        stack.push(node);

        Map<String, GraphEdge> succs = outEdges.get(node);
        if (succs != null) {
            for (String next : succs.keySet()) {
                if (gray.contains(next)) {
                    // 回边：环 = 栈顶一路向下到回边目标；更深的栈节点是环外尾部祖先
                    for (Iterator<String> it = stack.iterator(); it.hasNext(); ) {
                        String inCycle = it.next();
                        cycleNodes.add(inCycle);
                        if (inCycle.equals(next)) {
                            break;
                        }
                    }
                } else if (white.contains(next)) {
                    dfsCycle(next, white, gray, cycleNodes, stack);
                }
            }
        }

        stack.pop();
        gray.remove(node);
    }

    /**
     * 获取所有节点：边的全部端点，加上只出现于记录的孤立键
     */
    public Set<String> getAllNodes() {
        Set<String> nodes = new LinkedHashSet<>(outEdges.keySet());
        for (Map<String, GraphEdge> targets : outEdges.values()) {
            nodes.addAll(targets.keySet());
        }
        nodes.addAll(isolatedNodes);
        return nodes;
    }

    /**
     * 获取所有边（正向邻接序）
     */
    public List<GraphEdge> getAllEdges() {
        List<GraphEdge> edges = new ArrayList<>();
        for (Map<String, GraphEdge> targets : outEdges.values()) {
            edges.addAll(targets.values());
        }
        return edges;
    }
}
