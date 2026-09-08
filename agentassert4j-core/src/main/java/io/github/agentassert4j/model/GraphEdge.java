package io.github.agentassert4j.model;

/**
 * 图谱边 — 依赖图谱中两个调用点之间的数据流关系。
 *
 * <p>两种置信度：
 * <ul>
 *   <li>HIGH：字段值精确匹配（如 "ORD-2024-001" → "ORD-2024-001"）</li>
 *   <li>LOW：字段名前缀匹配（如 orderId ≈ orderRef，前缀 "order" 相同）</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class GraphEdge {

    private String source;
    private String target;
    private Confidence confidence;

    public GraphEdge(String source, String target, Confidence confidence) {
        this.source = source;
        this.target = target;
        this.confidence = confidence;
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
}
