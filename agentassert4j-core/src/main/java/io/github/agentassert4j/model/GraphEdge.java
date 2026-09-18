package io.github.agentassert4j.model;

/**
 * 图谱边 — 依赖图谱中两个调用点之间的数据流关系。
 *
 * <p>两种置信度：
 * <ul>
 *   <li>HIGH：字段值精确匹配（如 "ORD-2024-001" → "ORD-2024-001"），携带证据
 *       （命中值 + 源/目标记录 id）</li>
 *   <li>LOW：字段名前缀匹配（如 orderId ≈ orderRef，前缀 "order" 相同），是提示不是证据，
 *       证据三字段为 null</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class GraphEdge {

    private String source;
    private String target;
    private Confidence confidence;
    private String evidenceValue;
    private String evidenceSourceRecordId;
    private String evidenceTargetRecordId;

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

    public String getEvidenceValue() {
        return evidenceValue;
    }

    public void setEvidenceValue(String evidenceValue) {
        this.evidenceValue = evidenceValue;
    }

    public String getEvidenceSourceRecordId() {
        return evidenceSourceRecordId;
    }

    public void setEvidenceSourceRecordId(String evidenceSourceRecordId) {
        this.evidenceSourceRecordId = evidenceSourceRecordId;
    }

    public String getEvidenceTargetRecordId() {
        return evidenceTargetRecordId;
    }

    public void setEvidenceTargetRecordId(String evidenceTargetRecordId) {
        this.evidenceTargetRecordId = evidenceTargetRecordId;
    }
}
