package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.Confidence;
import io.github.agentassert4j.model.GraphEdge;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextUtil;

import java.util.*;

/**
 * 内存依赖图谱 — 纯内存邻接表 + JSON 持久化。
 *
 * <p>规模（10-50 节点，10-200 边）远低于需要图数据库的门槛。
 * BFS 下游遍历微秒级，JSON 序列化 &lt; 5KB。</p>
 *
 * <p>数据结构：
 * <ul>
 *   <li>正向邻接表 outEdges：src → (tgt → GraphEdge)</li>
 *   <li>反向邻接表 inEdges：tgt → Set&lt;src&gt;</li>
 * </ul>
 * 穿透压缩依赖反向邻接表做向上搜索。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class InMemoryDependencyGraph {

    /**
     * 正向邻接表：source → (target → edge)。
     * 插入序集合保证快照字节可复现——同数据全量重建产出完全相同的 JSON。
     */
    private final Map<String, Map<String, GraphEdge>> outEdges = new LinkedHashMap<>();
    /**
     * 反向邻接表：target → Set<source>
     */
    private final Map<String, Set<String>> inEdges = new LinkedHashMap<>();

    /**
     * 从 JSON 字符串反序列化图谱。
     * 破坏的边（source/target 缺失或空白、confidence 非法）整条跳过——
     * 快照是派生数据，坏数据宁可不建边也不能造出幽灵拓扑污染影响集。
     */
    @SuppressWarnings("unchecked")
    public static InMemoryDependencyGraph fromJson(String json) {
        InMemoryDependencyGraph graph = new InMemoryDependencyGraph();
        if (TextUtil.isBlank(json)) return graph;

        Object parsed = RecursiveJsonParser.parse(json);
        if (!(parsed instanceof Map)) return graph;

        Map<String, Object> root = (Map<String, Object>) parsed;
        Object edgesObj = root.get("edges");
        if (!(edgesObj instanceof List)) return graph;

        List<Object> edgesList = (List<Object>) edgesObj;
        for (Object edgeObj : edgesList) {
            if (!(edgeObj instanceof Map)) continue;
            Map<String, Object> edgeMap = (Map<String, Object>) edgeObj;
            String src = asNonBlank(edgeMap.get("source"));
            String tgt = asNonBlank(edgeMap.get("target"));
            Confidence conf;
            try {
                conf = Confidence.valueOf(String.valueOf(edgeMap.get("confidence")));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (src == null || tgt == null) {
                continue;
            }
            List<String> through = new ArrayList<>();
            Object throughObj = edgeMap.get("throughNodes");
            if (throughObj instanceof List) {
                for (Object t : (List<?>) throughObj) {
                    if (t != null) through.add(String.valueOf(t));
                }
            }
            graph.addEdge(src, tgt, conf, through);
        }
        return graph;
    }

    private static String asNonBlank(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    /**
     * 添加一条边（默认 HIGH 置信度）。
     * 同一条边多次添加时合并 confidence：HIGH > LOW > TRANSPARENT。
     */
    public void addEdge(String src, String tgt) {
        addEdge(src, tgt, Confidence.HIGH);
    }

    /**
     * 添加一条边（指定置信度）。
     * 同一条边多次添加时保留最高置信度。
     */
    public void addEdge(String src, String tgt, Confidence confidence) {
        addEdge(src, tgt, confidence, Collections.emptyList());
    }

    /**
     * 添加一条边（完整参数）。
     * 同一条边多次添加时保留最高置信度，throughNodes 合并。
     */
    public void addEdge(String src, String tgt, Confidence confidence, List<String> throughNodes) {
        Map<String, GraphEdge> targets = outEdges.computeIfAbsent(src, k -> new LinkedHashMap<>());
        GraphEdge existing = targets.get(tgt);
        if (existing != null) {
            // 已存在：升级置信度（保留最高的）
            if (existing.getConfidence().ordinal() > confidence.ordinal()) {
                existing.setConfidence(confidence);
            }
            // 合并 throughNodes
            if (throughNodes != null && !throughNodes.isEmpty()) {
                Set<String> merged = new LinkedHashSet<>(existing.getThroughNodes());
                merged.addAll(throughNodes);
                existing.setThroughNodes(new ArrayList<>(merged));
            }
        } else {
            targets.put(tgt, new GraphEdge(src, tgt, confidence, throughNodes));
        }
        // 维护反向邻接表
        inEdges.computeIfAbsent(tgt, k -> new LinkedHashSet<>()).add(src);
    }

    /**
     * BFS 下游遍历（微秒级），从 start 出发的所有可达节点（不含自身）。
     * 环检测：visited 集合防止死循环。
     */
    public Set<String> traverseDownstream(String start) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start);
        while (!queue.isEmpty()) {
            String curr = queue.poll();
            if (!visited.add(curr)) continue; // 环检测
            Map<String, GraphEdge> succs = outEdges.get(curr);
            if (succs != null) {
                queue.addAll(succs.keySet());
            }
        }
        visited.remove(start);
        return visited;
    }

    /**
     * 环检测 — DFS 三色染色法 + 显式递归栈。
     * 返回所有参与环的节点集合（空集表示无环）；环外尾部祖先不在环上，
     * 只有栈中回边目标到栈顶的区段才是环。
     */
    public Set<String> detectCycles() {
        Set<String> white = new HashSet<>(getAllNodes());
        Set<String> gray = new HashSet<>();
        Set<String> black = new HashSet<>();
        Set<String> cycleNodes = new LinkedHashSet<>();
        Deque<String> stack = new ArrayDeque<>();

        for (String node : getAllNodes()) {
            if (white.contains(node)) {
                dfsCycle(node, white, gray, black, cycleNodes, stack);
            }
        }
        return cycleNodes;
    }

    private void dfsCycle(String node, Set<String> white, Set<String> gray, Set<String> black, Set<String> cycleNodes, Deque<String> stack) {
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
                    dfsCycle(next, white, gray, black, cycleNodes, stack);
                }
            }
        }

        stack.pop();
        gray.remove(node);
        black.add(node);
    }

    /**
     * 获取直接后继节点
     */
    public Set<String> getSuccessors(String node) {
        Map<String, GraphEdge> succs = outEdges.get(node);
        return succs != null ? Collections.unmodifiableSet(succs.keySet()) : Collections.emptySet();
    }

    /**
     * 获取直接前驱节点
     */
    public Set<String> getPredecessors(String node) {
        Set<String> preds = inEdges.get(node);
        return preds != null ? Collections.unmodifiableSet(preds) : Collections.emptySet();
    }

    /**
     * 获取所有节点（插入序，快照与展示可复现）。
     */
    public Set<String> getAllNodes() {
        Set<String> nodes = new LinkedHashSet<>(outEdges.keySet());
        for (Map<String, GraphEdge> targets : outEdges.values()) {
            nodes.addAll(targets.keySet());
        }
        // 也检查只有入边没有出边的节点
        nodes.addAll(inEdges.keySet());
        return nodes;
    }

    /**
     * 获取所有边
     */
    public List<GraphEdge> getAllEdges() {
        List<GraphEdge> edges = new ArrayList<>();
        for (Map<String, GraphEdge> targets : outEdges.values()) {
            edges.addAll(targets.values());
        }
        return edges;
    }

    /**
     * 获取节点数
     */
    public int nodeCount() {
        return getAllNodes().size();
    }

    /**
     * 获取边数
     */
    public int edgeCount() {
        return getAllEdges().size();
    }

    /**
     * 移除节点及其关联的所有边。
     * 正向和反向邻接表同步清理。
     */
    public void removeNode(String node) {
        // 移除该节点的所有出边
        Map<String, GraphEdge> removed = outEdges.remove(node);
        if (removed != null) {
            for (String tgt : removed.keySet()) {
                Set<String> preds = inEdges.get(tgt);
                if (preds != null) {
                    preds.remove(node);
                }
            }
        }
        // 移除该节点的所有入边
        Set<String> preds = inEdges.remove(node);
        if (preds != null) {
            for (String pred : preds) {
                Map<String, GraphEdge> targets = outEdges.get(pred);
                if (targets != null) {
                    targets.remove(node);
                }
            }
        }
    }

    /**
     * 穿透压缩 — 被排除的节点不是简单删除，而是将前驱和后继直接连通。
     * 使用传递闭包：先为每个被排除节点找非排除前驱和后继，一次建边。
     * 然后统一移除被排除节点。
     *
     * @param excludedTools 需要排除的工具节点集合
     */
    public void compressExcludedNodes(Set<String> excludedTools) {
        if (excludedTools == null || excludedTools.isEmpty()) return;

        // 第 1 步：为每个被排除节点，找非排除前驱和后继，建立穿透边
        // 记录待添加的穿透边（避免在遍历中修改图结构）
        List<GraphEdge> transparentEdges = new ArrayList<>();
        for (String excluded : excludedTools) {
            Set<String> nonExcludedPreds = findNonExcludedReachable(excluded, Direction.UP, excludedTools);
            Set<String> nonExcludedSuccs = findNonExcludedReachable(excluded, Direction.DOWN, excludedTools);

            for (String pred : nonExcludedPreds) {
                for (String succ : nonExcludedSuccs) {
                    if (!pred.equals(succ)) {
                        transparentEdges.add(new GraphEdge(pred, succ, Confidence.TRANSPARENT, Collections.singletonList(excluded)));
                    }
                }
            }
        }

        // 第 2 步：添加所有穿透边
        for (GraphEdge edge : transparentEdges) {
            addEdge(edge.getSource(), edge.getTarget(), edge.getConfidence(), edge.getThroughNodes());
        }

        // 第 3 步：统一移除被排除节点
        for (String excluded : excludedTools) {
            removeNode(excluded);
        }
    }

    /**
     * 沿图向上/向下搜索，跳过被排除的中间节点，找到最近的非排除节点。
     */
    private Set<String> findNonExcludedReachable(String start, Direction dir, Set<String> excluded) {
        Set<String> result = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();

        // 初始邻居
        Set<String> neighbors = (dir == Direction.UP) ? getPredecessors(start) : getSuccessors(start);

        queue.addAll(neighbors);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            if (!visited.add(node)) continue;
            if (excluded.contains(node)) {
                // 继续穿透
                Set<String> next = (dir == Direction.UP) ? getPredecessors(node) : getSuccessors(node);
                queue.addAll(next);
            } else {
                result.add(node);
            }
        }
        return result;
    }

    /**
     * 将图谱序列化为 JSON 字符串。
     * 使用 RecursiveJsonParser.serialize() 辅助。
     */
    public String toJson() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("nodeCount", nodeCount());
        root.put("edgeCount", edgeCount());

        // 序列化边
        List<Object> edgeList = new ArrayList<>();
        for (GraphEdge e : getAllEdges()) {
            Map<String, Object> edgeMap = new LinkedHashMap<>();
            edgeMap.put("source", e.getSource());
            edgeMap.put("target", e.getTarget());
            edgeMap.put("confidence", e.getConfidence().name());
            if (e.getThroughNodes() != null && !e.getThroughNodes().isEmpty()) {
                edgeMap.put("throughNodes", e.getThroughNodes());
            }
            edgeList.add(edgeMap);
        }
        root.put("edges", edgeList);

        return RecursiveJsonParser.serialize(root);
    }

    /**
     * 搜索方向
     */
    private enum Direction {UP, DOWN}
}
