package io.github.agentassert4j.model;

/**
 * 调用点画像 — 一个调用点（invocation）的登记与治理载体。
 *
 * <p>三分模型中的「调用点」= 产生调用的模板/代码位置，是变更单元与治理主体
 * （治理对象 = 调用点的模板版本史）。主键即 {@link #invocationKey}（派生键），
 * 不设代理哈希标识。一条录制交互（case）是回归最小单元，期望永远现场重提，
 * 本画像的存档指纹只作展示与审计。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class InvocationProfile {

    /**
     * 调用点键（InvocationResolver 派生，唯一身份）
     */
    private String invocationKey;
    /**
     * 声明标签（业务身份，可空；未声明调用点键即身份）
     */
    private String label;
    /**
     * 派生展示名
     */
    private String invocationName;
    /**
     * 视图分类：有工具调用 / 纯对话（不影响判定）
     */
    private InvocationType invocationType;
    /**
     * 建档时模板哈希（视图与归档引用；参数类型签名类同属视图域）
     */
    private String templateHash;
    /**
     * 参数类型签名，如 "orderId:String"
     */
    private String paramSignature;
    private int totalRecords;
    /**
     * 当前基线指纹
     */
    private DeterministicFingerprint fingerprint;
    /**
     * 候选指纹（等待开发者裁决）
     */
    private DeterministicFingerprint candidateFingerprint;
    private BaselineStatus baselineStatus;
    /**
     * 基线版本标签（如 "v2.3.0"）
     */
    private String versionTag;
    /**
     * 判定算法语义版本（防算法升级悄悄重解释历史基线）
     */
    private String algoVersion;
    /**
     * 批准人（企业治理审计链：这次行为变更由谁批准）
     */
    private String approvedBy;
    /**
     * 批准时间戳（毫秒 epoch）
     */
    private Long approvedAt;

    public String getInvocationKey() {
        return invocationKey;
    }

    public void setInvocationKey(String invocationKey) {
        this.invocationKey = invocationKey;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public String getInvocationName() {
        return invocationName;
    }

    public void setInvocationName(String invocationName) {
        this.invocationName = invocationName;
    }

    public InvocationType getInvocationType() {
        return invocationType;
    }

    public void setInvocationType(InvocationType invocationType) {
        this.invocationType = invocationType;
    }

    public String getTemplateHash() {
        return templateHash;
    }

    public void setTemplateHash(String templateHash) {
        this.templateHash = templateHash;
    }

    public String getParamSignature() {
        return paramSignature;
    }

    public void setParamSignature(String paramSignature) {
        this.paramSignature = paramSignature;
    }

    public int getTotalRecords() {
        return totalRecords;
    }

    public void setTotalRecords(int totalRecords) {
        this.totalRecords = totalRecords;
    }


    public DeterministicFingerprint getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(DeterministicFingerprint fingerprint) {
        this.fingerprint = fingerprint;
    }

    public DeterministicFingerprint getCandidateFingerprint() {
        return candidateFingerprint;
    }

    public void setCandidateFingerprint(DeterministicFingerprint candidateFingerprint) {
        this.candidateFingerprint = candidateFingerprint;
    }

    public BaselineStatus getBaselineStatus() {
        return baselineStatus;
    }

    public void setBaselineStatus(BaselineStatus baselineStatus) {
        this.baselineStatus = baselineStatus;
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
}
