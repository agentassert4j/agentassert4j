package io.github.agentassert4j.model;

/**
 * 基线步骤 — 对齐基线侧的一个步骤引用：调用点键 + 记录标识 + 基线指纹。
 *
 * <p>三条产生路径共用：库内链参照由录制记录现场重提（recordId 为源记录）；
 * 验收包参照由包内指纹反序列化（recordId 为导出侧源记录，供报告溯源）；
 * 画像参照由调用点活跃指纹定格投影（CI 基线对照，versionTag 携带画像活跃版本，
 * recordId 恒 null——基线侧无记录）。样本字段仅验收包传输用（已脱敏），对齐判定不消费。</p>
 *
 * @author axy-yxa
 * @since 2026-09-30
 */
public class BaselineStep {

    private String invocationKey;
    private String recordId;
    /**
     * 声明标签（内存装配字段，不落库不入验收包）：任务对齐按「标签分组可跨版本、
     * 无标签按完整键」分组——验收包步骤的标签在装载时从键解析回填。
     */
    private String invocationId;
    private DeterministicFingerprint fingerprint;
    /**
     * 仅导出 --include-samples 时携带（写入包内前已脱敏）；判定不消费
     */
    private String sampleInput;
    private String sampleOutput;
    /**
     * 画像活跃版本标签（内存装配字段，仅画像投影路径携带；包与链路径恒 null，
     * 不进任何 wire 形态）——CI 对照报告显示「vs baseline v3」的数据源
     */
    private String versionTag;

    public String getInvocationKey() {
        return invocationKey;
    }

    public void setInvocationKey(String invocationKey) {
        this.invocationKey = invocationKey;
    }

    public String getInvocationId() {
        return invocationId;
    }

    public void setInvocationId(String invocationId) {
        this.invocationId = invocationId;
    }

    public String getRecordId() {
        return recordId;
    }

    public void setRecordId(String recordId) {
        this.recordId = recordId;
    }

    public DeterministicFingerprint getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(DeterministicFingerprint fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSampleInput() {
        return sampleInput;
    }

    public void setSampleInput(String sampleInput) {
        this.sampleInput = sampleInput;
    }

    public String getSampleOutput() {
        return sampleOutput;
    }

    public void setSampleOutput(String sampleOutput) {
        this.sampleOutput = sampleOutput;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public void setVersionTag(String versionTag) {
        this.versionTag = versionTag;
    }
}
