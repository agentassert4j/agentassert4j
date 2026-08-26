package io.github.agentassert4j.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 图谱边 — 依赖图谱中两个 Skill 之间的数据流关系。
 *
 * <p>三种置信度：
 * <ul>
 *   <li>HIGH：字段值精确匹配（如 "ORD-2024-001" → "ORD-2024-001"）</li>
 *   <li>LOW：字段名前缀匹配（如 orderId ≈ orderRef，前缀 "order" 相同）</li>
 *   <li>TRANSPARENT：穿透压缩产生的透传边，throughNodes 记录中间节点</li>
 * </ul>
 */
public class GraphEdge {

    private String source;
    private String target;
    private Confidence confidence;
    /**
     * 穿透经过的中间节点（仅 TRANSPARENT 有值）
     */
    private List<String> throughNodes;

    public GraphEdge() {
        this.throughNodes = new ArrayList<>();
    }

    public GraphEdge(String source, String target, Confidence confidence) {
        this.source = source;
        this.target = target;
        this.confidence = confidence;
        this.throughNodes = new ArrayList<>();
    }

    public GraphEdge(String source, String target, Confidence confidence, List<String> throughNodes) {
        this.source = source;
        this.target = target;
        this.confidence = confidence;
        this.throughNodes = throughNodes != null ? throughNodes : new ArrayList<>();
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public Confidence getConfidence() {
        return confidence;
    }

    public void setConfidence(Confidence confidence) {
        this.confidence = confidence;
    }

    public List<String> getThroughNodes() {
        return throughNodes;
    }

    public void setThroughNodes(List<String> throughNodes) {
        this.throughNodes = throughNodes;
    }
}
