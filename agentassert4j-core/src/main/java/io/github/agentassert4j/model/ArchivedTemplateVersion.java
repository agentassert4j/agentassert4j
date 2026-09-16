package io.github.agentassert4j.model;

import java.util.List;

/**
 * 调用点模板版本归档行 — accept/rollback 时被替换的基线按模板版本整体快照。
 *
 * <p>治理主体 = 调用点的模板版本史：除指纹集合（该版本获批时的完整认可形态集）
 * 与版本标签外，归档行同时留存该版本对应的模板哈希（经 prompt_texts 可反查模板
 * 原文）与获批时的语义版本、审批事实，回滚时据此恢复活跃画像的治理信息
 * （rollback 恢复的是整个集合快照）。</p>
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
    /**
     * 该版本获批时的完整形态集合快照（回滚整体恢复）
     */
    private List<DeterministicFingerprint> fingerprints;
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

    public List<DeterministicFingerprint> getFingerprints() {
        return fingerprints;
    }

    public void setFingerprints(List<DeterministicFingerprint> fingerprints) {
        this.fingerprints = fingerprints;
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

    /**
     * 代码锚（申报制审计标注：该版本基线获批时调用方声明的代码参照，如 git 提交号）
     */
    private String codeRef;

    public String getCodeRef() {
        return codeRef;
    }

    public void setCodeRef(String codeRef) {
        this.codeRef = codeRef;
    }

    public long getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(long archivedAt) {
        this.archivedAt = archivedAt;
    }
}
