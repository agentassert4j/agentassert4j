package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.StorageRepository;

/**
 * 基线生命周期管理。
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
 *
 * <p><b>线程契约</b>：本类非线程安全——生命周期操作（approve/reject/rollback/
 * recordCandidate）是无锁读改写，并发调用会丢失更新。CLI 单进程单线程使用
 * 安全；并发场景需调用方自行串行化（SkillStore 无版本号，乐观锁待真实需求）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class BaselineManager {

    private final StorageRepository repository;

    public BaselineManager(StorageRepository repository) {
        this.repository = repository;
    }

    /**
     * 批准新基线：候选 → 基线，旧基线归档。
     *
     * <p>归档与保存是两步独立写入，无跨表事务：保存失败经 StorageException 向上可见，
     * 重试时归档去重守卫保证不产生重复归档行， approve 可安全重放。</p>
     *
     * @param groupKey Skill 的分组键（DeterministicSkillGrouper 生成的 groupKey）
     * @param approver 审批人身份，随活跃画像与归档行留痕（纯治理元数据，永不参与判定）
     * @throws IllegalStateException 无候选指纹时抛出
     */
    public void approve(String groupKey, String approver) {
        SkillProfile profile = repository.findSkillByGroupKey(groupKey);
        if (profile == null) {
            throw new IllegalStateException("Skill profile not found: " + groupKey);
        }

        DeterministicFingerprint candidate = profile.getCandidateFingerprint();
        if (candidate == null) {
            throw new IllegalStateException("No candidate to approve for skill: " + groupKey);
        }

        // 旧基线归档（可回溯）；回滚恢复的旧基线已在归档中，跳过避免同 tag 重复行。
        // 归档行携带的是旧基线自身获批时的审批人与语义版本，必须先于新审批信息写入前快照
        archiveIfAbsent(groupKey, profile);

        // 候选提升为基线
        profile.setFingerprint(candidate);
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        // 更新版本标签：跳过归档中已占用的 tag，保证 tag↔指纹一一对应（回滚后不产生同 tag 双指纹）
        profile.setVersionTag(nextAvailableVersionTag(groupKey, profile.getVersionTag()));
        stampApproval(profile, approver);
        repository.saveSkillProfile(profile);
    }

    /**
     * 否决候选：丢弃候选，保留旧基线。
     * 开发者需自行回滚 Prompt（回滚是 git 的职责，不是测试框架的职责）。
     *
     * @param groupKey Skill 的分组键
     * @throws IllegalStateException 无候选指纹时抛出（与 approve 对称）
     */
    public void reject(String groupKey) {
        SkillProfile profile = repository.findSkillByGroupKey(groupKey);
        if (profile == null) {
            throw new IllegalStateException("Skill profile not found: " + groupKey);
        }
        if (profile.getCandidateFingerprint() == null) {
            throw new IllegalStateException("No candidate to reject for skill: " + groupKey);
        }

        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        repository.saveSkillProfile(profile);
    }

    /**
     * 回滚到指定版本的归档基线。
     *
     * @param groupKey   Skill 的分组键
     * @param versionTag 目标版本标签
     * @throws IllegalStateException 无归档基线时抛出
     */
    public void rollback(String groupKey, String versionTag) {
        ArchivedBaseline archived = repository.findArchivedBaseline(groupKey, versionTag);
        if (archived == null) {
            throw new IllegalStateException("No archived baseline found for skill: " + groupKey + ", version: " + versionTag);
        }

        SkillProfile profile = repository.findSkillByGroupKey(groupKey);
        if (profile == null) {
            throw new IllegalStateException("Skill profile not found: " + groupKey);
        }

        // 当前基线也归档（若该 tag 未曾归档过）
        archiveIfAbsent(groupKey, profile);

        // 恢复归档基线——审批人与语义版本随基线一起回退：
        // 活跃行的治理事实必须始终描述当前基线自身的获批历史
        profile.setFingerprint(archived.getFingerprint());
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        profile.setVersionTag(versionTag);
        profile.setAlgoVersion(archived.getAlgoVersion());
        profile.setApprovedBy(archived.getApprovedBy());
        profile.setApprovedAt(archived.getApprovedAt());
        repository.saveSkillProfile(profile);
    }

    /**
     * 记录回归测试产生的新指纹为候选，供后续 approve/reject 裁决。
     * 回归执行器在对比结果非 PASS 时调用——候选必须经持久层落库，
     * 否则 approve 在新进程中不可达（重放与裁决通常不在同一进程）。
     *
     * @param baseline  产生候选时所用基线交互记录（groupKey 由分组器从记录重算）
     * @param candidate 回归测试提取的新指纹
     * @throws IllegalStateException 该 Skill 无画像时抛出（先录制建立基线）
     */
    public void recordCandidate(InteractionRecord baseline, DeterministicFingerprint candidate) {
        if (baseline == null || candidate == null) {
            return;
        }

        String groupKey = DeterministicSkillGrouper.group(baseline).getGroupKey();
        SkillProfile profile = repository.findSkillByGroupKey(groupKey);
        if (profile == null) {
            throw new IllegalStateException("Skill profile not found: " + groupKey);
        }

        profile.setCandidateFingerprint(candidate);
        profile.setBaselineStatus(BaselineStatus.CANDIDATE);
        repository.saveSkillProfile(profile);
    }

    /**
     * 首次录制自动建立基线。
     * 如果该 Skill 已有基线，不做任何操作（幂等）。
     *
     * @param record   首次录制的交互记录
     * @param approver 使该基线成为基线的操作者身份（自动建立同样留痕，纯治理元数据）
     */
    public void autoEstablishBaseline(InteractionRecord record, String approver) {
        establish(record, approver, false);
    }

    /**
     * 以当前判定语义重建基线——判定语义版本升级后的恢复路径。
     * 用当前算法对既有录制重新提指纹并覆盖活跃画像，版本标签按归档占用顺延，
     * 不触碰归档历史（回滚到旧语义版本的归档行会被重放入口的版本校验拒绝，属预期）。
     *
     * @param record   该 skill 的任一已录制交互
     * @param approver 重建操作者身份
     * @throws IllegalStateException 该 Skill 无画像且无录制数据可分组时抛出
     */
    public void reestablishBaseline(InteractionRecord record, String approver) {
        establish(record, approver, true);
    }

    private void establish(InteractionRecord record, String approver, boolean overwrite) {
        if (record == null || record.getSkillId() == null || record.getSkillId().isEmpty()) {
            return;
        }

        SkillProfile grouping = DeterministicSkillGrouper.group(record);
        SkillProfile existing = repository.findSkillByGroupKey(grouping.getGroupKey());

        if (!overwrite && existing != null && existing.getFingerprint() != null) {
            // 已有基线，不覆盖
            return;
        }

        // 提取指纹作为基线
        DeterministicFingerprint fingerprint = FingerprintExtractor.extract(record);

        // 以分组器产出为基底：skill_name/skill_type 等展示列来自分组的派生结果，
        // 裸画像会违反存储层的 NOT NULL 契约
        SkillProfile profile = existing != null ? existing : grouping;
        profile.setFingerprint(fingerprint);
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        profile.setVersionTag(overwrite ? nextAvailableVersionTag(grouping.getGroupKey(), profile.getVersionTag()) : "v1");
        profile.setTotalRecords(existing != null ? existing.getTotalRecords() : 1);
        stampApproval(profile, approver);

        repository.saveSkillProfile(profile);
    }

    /**
     * 盖上审批痕迹：语义版本 + 审批人 + 时间。建立/批准/重建三条成为基线的路径共用。
     */
    private void stampApproval(SkillProfile profile, String approver) {
        profile.setAlgoVersion(JudgmentSemantics.VERSION);
        profile.setApprovedBy(approver);
        profile.setApprovedAt(System.currentTimeMillis());
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

    /**
     * 归档当前基线——同 tag 已存在归档行时跳过（回滚恢复的基线本就在归档中）；
     * 无版本标签的基线无回滚句柄，不归档。
     */
    private void archiveIfAbsent(String groupKey, SkillProfile profile) {
        if (profile.getFingerprint() == null || profile.getVersionTag() == null) {
            return;
        }
        if (repository.findArchivedBaseline(groupKey, profile.getVersionTag()) == null) {
            // 归档行是该基线的完整快照：指纹、版本标签之外，语义版本与审批事实一并留痕，
            // 回滚时据此恢复活跃行的治理信息
            ArchivedBaseline archived = new ArchivedBaseline();
            archived.setSkillId(groupKey);
            archived.setFingerprint(profile.getFingerprint());
            archived.setVersionTag(profile.getVersionTag());
            archived.setAlgoVersion(profile.getAlgoVersion());
            archived.setApprovedBy(profile.getApprovedBy());
            archived.setApprovedAt(profile.getApprovedAt());
            repository.archiveBaseline(archived);
        }
    }

    /**
     * 递增版本标签并跳过归档中已占用的 tag——保证任一 tag 在归档与活跃态之间
     * 始终只对应一个指纹，rollback(tag) 不产生歧义。
     */
    private String nextAvailableVersionTag(String groupKey, String currentTag) {
        String next = generateVersionTag(currentTag);
        while (repository.findArchivedBaseline(groupKey, next) != null) {
            next = generateVersionTag(next);
        }
        return next;
    }
}
