package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.ArchivedBaseline;
import io.github.agentassert4j.model.BaselineStatus;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;

/**
 * 基线生命周期管理（与方案文档 5.9 节一致）。
 *
 * <h3>设计哲学</h3>
 * <p>框架是侦探，不是法官。侦探负责发现变化和取证，法官（开发者）负责判断变化是否正确。
 * 框架不评判"新的好还是旧的好"，只报告差异，由开发者裁决。</p>
 *
 * <h3>基线三态生命周期</h3>
 * <pre>
 * BASELINE（基线）
 *   → 首次录制时自动建立，代表"当前认可的行为标准"
 *   → 后续对比以此为基准
 *
 * CANDIDATE（候选）
 *   → Prompt 变更后产生的新指纹，正在和 BASELINE 做对比
 *   → 等待开发者裁决：approve 或 reject
 *
 * ARCHIVED（已归档）
 *   → 被 approve 替换掉的旧基线，保留可回溯
 *   → 支持回滚到任意历史版本
 * </pre>
 *
 * <h3>生命周期流程</h3>
 * <pre>
 * 首次录制 → 自动建立 BASELINE
 *               ↓
 * Prompt 变更 → 回归测试 → 新指纹成为 CANDIDATE
 *                            ↓
 *                   开发者审查报告
 *                   ┌──────┴───────┐
 *                有意改动       意外回归
 *                   ↓               ↓
 *             approve()        reject()
 *               ↓                  ↓
 *         旧基线归档         丢弃候选
 *         候选升为基线       保留旧基线
 * </pre>
 */
public class BaselineManager {

    private final StorageRepository repository;

    public BaselineManager(StorageRepository repository) {
        this.repository = repository;
    }

    /**
     * 批准新基线：候选 → 基线，旧基线归档。
     *
     * @param groupKey Skill 的分组键（DeterministicSkillGrouper 生成的 groupKey）
     * @throws IllegalStateException 无候选指纹时抛出
     */
    public void approve(String groupKey) {
        SkillProfile profile = repository.findSkillByGroupKey(groupKey);
        if (profile == null) {
            throw new IllegalStateException("Skill profile not found: " + groupKey);
        }

        DeterministicFingerprint candidate = profile.getCandidateFingerprint();
        if (candidate == null) {
            throw new IllegalStateException("No candidate to approve for skill: " + groupKey);
        }

        // 旧基线归档（可回溯）
        if (profile.getFingerprint() != null) {
            repository.archiveBaseline(groupKey, profile.getFingerprint(), profile.getVersionTag());
        }

        // 候选提升为基线
        profile.setFingerprint(candidate);
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        // 更新版本标签
        profile.setVersionTag(generateVersionTag(profile.getVersionTag()));
        repository.saveSkillProfile(profile);
    }

    /**
     * 否决候选：丢弃候选，保留旧基线。
     * 开发者需自行回滚 Prompt（回滚是 git 的职责，不是测试框架的职责）。
     *
     * @param groupKey Skill 的分组键
     */
    public void reject(String groupKey) {
        SkillProfile profile = repository.findSkillByGroupKey(groupKey);
        if (profile == null) {
            throw new IllegalStateException("Skill profile not found: " + groupKey);
        }

        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        repository.saveSkillProfile(profile);
    }

    /**
     * 回滚到指定版本的归档基线。
     *
     * @param groupKey    Skill 的分组键
     * @param versionTag 目标版本标签
     * @throws IllegalStateException 无归档基线时抛出
     */
    public void rollback(String groupKey, String versionTag) {
        ArchivedBaseline archived = repository.findArchivedBaseline(groupKey, versionTag);
        if (archived == null) {
            throw new IllegalStateException(
                "No archived baseline found for skill: " + groupKey + ", version: " + versionTag);
        }

        SkillProfile profile = repository.findSkillByGroupKey(groupKey);
        if (profile == null) {
            throw new IllegalStateException("Skill profile not found: " + groupKey);
        }

        // 当前基线也归档
        if (profile.getFingerprint() != null) {
            repository.archiveBaseline(groupKey, profile.getFingerprint(), profile.getVersionTag());
        }

        // 恢复归档基线
        profile.setFingerprint(archived.getFingerprint());
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        profile.setVersionTag(versionTag);
        repository.saveSkillProfile(profile);
    }

    /**
     * 首次录制自动建立基线。
     * 如果该 Skill 已有基线，不做任何操作（幂等）。
     *
     * @param record 首次录制的交互记录
     */
    public void autoEstablishBaseline(InteractionRecord record) {
        if (record == null || record.getSkillId() == null || record.getSkillId().isEmpty()) {
            return;
        }

        SkillProfile grouping = DeterministicSkillGrouper.group(record);
        SkillProfile existing = repository.findSkillByGroupKey(grouping.getGroupKey());

        if (existing != null && existing.getFingerprint() != null) {
            // 已有基线，不覆盖
            return;
        }

        // 提取指纹作为基线
        DeterministicFingerprint fingerprint = FingerprintExtractor.extract(record);

        SkillProfile profile = existing != null ? existing : new SkillProfile();
        profile.setSkillId(record.getSkillId());
        profile.setGroupKey(grouping.getGroupKey());
        profile.setFingerprint(fingerprint);
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        profile.setVersionTag("v1");
        profile.setTotalRecords(existing != null ? existing.getTotalRecords() : 1);

        repository.saveSkillProfile(profile);
    }

    /**
     * 生成新版本标签。
     * 规则：v1 → v2，v2 → v3，null → v1。
     */
    private String generateVersionTag(String currentTag) {
        if (currentTag == null || currentTag.isEmpty()) {
            return "v1";
        }
        // 尝试解析 "vN" 格式
        if (currentTag.startsWith("v")) {
            try {
                int version = Integer.parseInt(currentTag.substring(1));
                return "v" + (version + 1);
            } catch (NumberFormatException e) {
                // 非标准格式，追加后缀
                return currentTag + "-next";
            }
        }
        return currentTag + "-next";
    }
}
