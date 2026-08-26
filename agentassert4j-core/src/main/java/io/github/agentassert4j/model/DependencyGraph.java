package io.github.agentassert4j.model;

/**
 * 依赖图谱数据模型 — Skill 间数据流关系的结构化视图。
 *
 * <p>用于外部展示（Dashboard / CLI），核心图计算在 InMemoryDependencyGraph 中进行。</p>
 *
 * <p><b>TODO: [模型简化]</b> 方案文档 4.6 节定义了 Node 内部类（skillId, groupKey, inboundCount, outboundCount）
 * 和 edges 结构。当前阶段 CLI/Dashboard 尚未实现，暂时简化为统计字段。
 * 待 Phase 1 CLI 开发时补充 Node 内部类和完整 edges 结构，
 * 并增加从 InMemoryDependencyGraph 转换的工厂方法。</p>
 */
public class DependencyGraph {

    private String graphId;
    private long builtAt;
    private int nodeCount;
    private int edgeCount;

    // ========== Getters & Setters ==========

    public String getGraphId() { return graphId; }
    public void setGraphId(String graphId) { this.graphId = graphId; }

    public long getBuiltAt() { return builtAt; }
    public void setBuiltAt(long builtAt) { this.builtAt = builtAt; }

    public int getNodeCount() { return nodeCount; }
    public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }

    public int getEdgeCount() { return edgeCount; }
    public void setEdgeCount(int edgeCount) { this.edgeCount = edgeCount; }
}
