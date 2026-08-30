package io.github.agentassert4j.model;

/**
 * 调用点模板版本归档行 — approve/rollback 时被替换的基线按模板版本整体快照。
 *
 * <p>治理主体 = 调用点的模板版本史：除指纹与版本标签外，归档行同时留存该版本
 * 对应的模板哈希（经 prompt_texts 可反查模板原文）与获批时的语义版本、审批事实，
 * 回滚时据此恢复活跃画像的治理信息。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class ArchivedTemplateVersion {

    /**
     * 所属调用点键
     */
    private String invocationKey;
    /**
     * 该版本对应的模板哈希
     */
    private String templateHash;
    private DeterministicFingerprint fingerprint;
    private String versionTag;
    /**
     * 该基线获批时的判定语义版本（回滚恢复的依据）
     */
    private String algoVersion;
    /**
     * 该基线的审批人（纯治理元数据，永不参与判定）
     */
    private String approvedBy;
    /**
     * 审批时间戳（毫秒 epoch）
     */
    private Long approvedAt;
    private long archivedAt;

    public String getInvocationKey() {
        return invocationKey;
    }

    public void setInvocationKey(String invocationKey) {
        this.invocationKey = invocationKey;
    }

    public String getTemplateHash() {
        return templateHash;
    }

    public void setTemplateHash(String templateHash) {
        this.templateHash = templateHash;
    }

    public DeterministicFingerprint getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(DeterministicFingerprint fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getVersionTag() {
        return versionTag;
    }

    public void setVersionTag(String versionTag) {
        this.versionTag = versionTag;
    }

    public String getAlgoVersion() {
        return algoVersion;
    }

    public void setAlgoVersion(String algoVersion) {
        this.algoVersion = algoVersion;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Long getApprovedAt() {
        return approvedAt;
    }

    public void setApprovedAt(Long approvedAt) {
        this.approvedAt = approvedAt;
    }

    public long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(long archivedAt) {
        this.archivedAt = archivedAt;
    }
}
