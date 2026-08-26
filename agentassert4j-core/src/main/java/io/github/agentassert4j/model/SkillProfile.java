package io.github.agentassert4j.model;

/**
 * Skill 画像 — 同一组交互记录的聚合视图。
 *
 * <p>groupKey 确定性分组规则：
 * <ul>
 *   <li>有工具调用：sorted(toolNames).join("+") + "[" + paramSignature + "]"</li>
 *   <li>无工具调用：SHA-256(systemPrompt)</li>
 * </ul></p>
 */
public class SkillProfile {

    private String skillId;
    private String skillName;
    private SkillType skillType;
    /** 确定性分组键 */
    private String groupKey;
    /** 参数类型签名，如 "orderId:String" */
    private String paramSignature;
    private int totalRecords;
    /** 采样记录数（不加载完整记录，按需查询） */
    private int sampleCount;
    /** 当前基线指纹 */
    private DeterministicFingerprint fingerprint;
    /** 候选指纹（等待开发者裁决） */
    private DeterministicFingerprint candidateFingerprint;
    private BaselineStatus baselineStatus;
    /** 基线版本标签（如 "v2.3.0"） */
    private String versionTag;
    /** 判定算法语义版本（防算法升级悄悄重解释历史基线） */
    private String algoVersion;
    /** 批准人（企业治理审计链：这次行为变更由谁批准） */
    private String approvedBy;
    /** 批准时间戳（毫秒 epoch） */
    private Long approvedAt;

    public String getSkillId() { return skillId; }
    public void setSkillId(String skillId) { this.skillId = skillId; }

    public String getSkillName() { return skillName; }
    public void setSkillName(String skillName) { this.skillName = skillName; }

    public SkillType getSkillType() { return skillType; }
    public void setSkillType(SkillType skillType) { this.skillType = skillType; }

    public String getGroupKey() { return groupKey; }
    public void setGroupKey(String groupKey) { this.groupKey = groupKey; }

    public String getParamSignature() { return paramSignature; }
    public void setParamSignature(String paramSignature) { this.paramSignature = paramSignature; }

    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }

    public int getSampleCount() { return sampleCount; }
    public void setSampleCount(int sampleCount) { this.sampleCount = sampleCount; }

    public DeterministicFingerprint getFingerprint() { return fingerprint; }
    public void setFingerprint(DeterministicFingerprint fingerprint) { this.fingerprint = fingerprint; }

    public DeterministicFingerprint getCandidateFingerprint() { return candidateFingerprint; }
    public void setCandidateFingerprint(DeterministicFingerprint candidateFingerprint) { this.candidateFingerprint = candidateFingerprint; }

    public BaselineStatus getBaselineStatus() { return baselineStatus; }
    public void setBaselineStatus(BaselineStatus baselineStatus) { this.baselineStatus = baselineStatus; }

    public String getVersionTag() { return versionTag; }
    public void setVersionTag(String versionTag) { this.versionTag = versionTag; }

    public String getAlgoVersion() { return algoVersion; }
    public void setAlgoVersion(String algoVersion) { this.algoVersion = algoVersion; }

    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

    public Long getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Long approvedAt) { this.approvedAt = approvedAt; }
}
