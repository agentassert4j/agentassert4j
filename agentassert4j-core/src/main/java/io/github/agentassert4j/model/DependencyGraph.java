package io.github.agentassert4j.model;

/**
 * 依赖图谱数据模型 — Skill 间数据流关系的结构化视图。
 *
 * <p>用于外部展示（Dashboard / CLI），核心图计算在 InMemoryDependencyGraph 中进行。</p>
 *
 * <p><b>TODO: [模型简化]</b> 当前仅保留统计字段；CLI/Dashboard 落地时补充 Node 内部类
 * （skillId, groupKey, inboundCount, outboundCount）、edges 结构及从
 * InMemoryDependencyGraph 转换的工厂方法。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class DependencyGraph {

    private String graphId;
    private long builtAt;
    private int nodeCount;
    private int edgeCount;

    public String getGraphId() {
        return graphId;
    }

    public void setGraphId(String graphId) {
        this.graphId = graphId;
    }

    public long getBuiltAt() {
        return builtAt;
    }

    public void setBuiltAt(long builtAt) {
        this.builtAt = builtAt;
    }

    public int getNodeCount() {
        return nodeCount;
    }

    public void setNodeCount(int nodeCount) {
        this.nodeCount = nodeCount;
    }

    public int getEdgeCount() {
        return edgeCount;
    }

    public void setEdgeCount(int edgeCount) {
        this.edgeCount = edgeCount;
    }
}
