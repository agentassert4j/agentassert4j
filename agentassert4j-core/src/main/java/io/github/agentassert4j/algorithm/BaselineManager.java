package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.spi.StorageRepository;

import java.util.List;

/**
 * 基线生命周期管理 — 框架只报告差异（侦探），接受与否由开发者裁决（法官）。
 *
 * <p>治理主体 = 调用点（invocation）的模板版本史。三态流转：BASELINE（当前认可的
 * 行为标准）→ 变更产生 CANDIDATE（待 approve/reject）→ approve 后旧基线按模板版本
 * 归档（可 rollback 回溯）。</p>
 *
 * <p>线程契约：生命周期方法以实例监视器互斥，同一 JVM 内并发调用安全
 * （SDK 多线程接入均落在此契约内）；跨进程并发写同一存储不受此保护，
 * 仍需调用方自行保证排他。</p>
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
     * 批准新基线：候选 → 基线，旧基线按模板版本归档。
     *
     * <p>归档与保存是两步独立写入，无跨表事务：保存失败经 StorageException 向上可见，
     * 重试时归档去重守卫保证不产生重复归档行， approve 可安全重放。</p>
     *
     * @param invocationKey 调用点键（InvocationResolver 派生）
     * @param approver      审批人身份，随活跃画像与归档行留痕（纯治理元数据，永不参与判定）
     * @throws IllegalStateException 无候选指纹时抛出
     */
    public synchronized void approve(String invocationKey, String approver) {
        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        if (profile == null) {
            throw new IllegalStateException("Invocation profile not found: " + invocationKey);
        }

        DeterministicFingerprint candidate = profile.getCandidateFingerprint();
        if (candidate == null) {
            throw new IllegalStateException("No candidate to approve for invocation: " + invocationKey);
        }

        // 旧基线归档（可回溯）；回滚恢复的旧基线已在归档中，跳过避免同 tag 重复行。
        // 归档行携带的是旧基线自身获批时的审批人与语义版本，必须先于新审批信息写入前快照
        archiveIfAbsent(invocationKey, profile);

        // 模板身份前移必须晚于归档：归档行的旧模板哈希是回滚的恢复源，
        // 顺序颠倒会把前移后的新身份归档进旧基线行
        recomputeTemplateHash(invocationKey, profile);

        // 候选提升为基线
        profile.setFingerprint(candidate);
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        // 更新版本标签：跳过归档中已占用的 tag，保证 tag↔指纹一一对应（回滚后不产生同 tag 双指纹）
        profile.setVersionTag(nextAvailableVersionTag(invocationKey, profile.getVersionTag()));
        stampApproval(profile, approver);
        repository.saveInvocationProfile(profile);
    }

    /**
     * 否决候选：丢弃候选，保留旧基线。
     * 开发者需自行回滚 Prompt（回滚是 git 的职责，不是测试框架的职责）。
     *
     * @param invocationKey 调用点键
     * @throws IllegalStateException 无候选指纹时抛出（与 approve 对称）
     */
    public synchronized void reject(String invocationKey) {
        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        if (profile == null) {
            throw new IllegalStateException("Invocation profile not found: " + invocationKey);
        }
        if (profile.getCandidateFingerprint() == null) {
            throw new IllegalStateException("No candidate to reject for invocation: " + invocationKey);
        }

        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        repository.saveInvocationProfile(profile);
    }

    /**
     * 将调用点的模板身份前移到最新可分组记录的模板哈希（漂移自动收编的写入口，
     * 与 approve 的前移同一重算口径）。
     *
     * <p>画像不存在、无可用记录凭据或最新模板哈希为空时保守保留原值并返回 false——
     * 不产出错误身份；哈希一致时幂等返回 false。指纹、候选、版本标签与审批链均不动。</p>
     *
     * @param invocationKey 调用点键
     * @return 是否实际前移（true = 画像模板哈希已更新）
     */
    public synchronized boolean advanceTemplateIdentity(String invocationKey) {
        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        if (profile == null) {
            return false;
        }
        boolean advanced = recomputeTemplateHash(invocationKey, profile);
        if (advanced) {
            repository.saveInvocationProfile(profile);
        }
        return advanced;
    }

    /**
     * 回滚到指定版本的归档基线。
     *
     * @param invocationKey 调用点键
     * @param versionTag    目标版本标签
     * @throws IllegalStateException 无归档基线时抛出
     */
    public synchronized void rollback(String invocationKey, String versionTag) {
        ArchivedTemplateVersion archived = repository.findArchivedVersion(invocationKey, versionTag);
        if (archived == null) {
            throw new IllegalStateException("No archived template version found for invocation: " + invocationKey + ", version: " + versionTag);
        }

        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        if (profile == null) {
            throw new IllegalStateException("Invocation profile not found: " + invocationKey);
        }

        // 当前基线也归档（若该 tag 未曾归档过）
        archiveIfAbsent(invocationKey, profile);

        // 恢复归档基线——审批人与语义版本随基线一起回退：
        // 活跃行的治理事实必须始终描述当前基线自身的获批历史
        profile.setFingerprint(archived.getFingerprint());
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        profile.setVersionTag(versionTag);
        // 模板哈希随归档快照恢复：回滚把调用点身份一并退回该版本获批时的模板，
        // 否则基线描述旧模板行为、身份却挂着新模板，下轮检测即误报漂移
        profile.setTemplateHash(archived.getTemplateHash());
        profile.setAlgoVersion(archived.getAlgoVersion());
        profile.setApprovedBy(archived.getApprovedBy());
        profile.setApprovedAt(archived.getApprovedAt());
        repository.saveInvocationProfile(profile);
    }

    /**
     * 记录回归测试产生的新指纹为候选，供后续 approve/reject 裁决。
     * 回归执行器在对比结果非 PASS 时调用——候选必须经持久层落库，
     * 否则 approve 在新进程中不可达（重放与裁决通常不在同一进程）。
     *
     * @param baseline  产生候选时所用基线交互记录（invocationKey 由解析器从记录重算）
     * @param candidate 回归测试提取的新指纹
     * @throws IllegalStateException 该调用点无画像时抛出（先录制建立基线）
     */
    public synchronized void recordCandidate(InteractionRecord baseline, DeterministicFingerprint candidate) {
        if (baseline == null || candidate == null) {
            return;
        }

        String invocationKey = InvocationResolver.resolve(baseline).getInvocationKey();
        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        if (profile == null) {
            throw new IllegalStateException("Invocation profile not found: " + invocationKey);
        }

        profile.setCandidateFingerprint(candidate);
        profile.setBaselineStatus(BaselineStatus.CANDIDATE);
        repository.saveInvocationProfile(profile);
    }

    /**
     * 首次录制自动建立基线。
     * 如果该调用点已有基线，不做任何操作（幂等）。
     *
     * @param record   首次录制的交互记录
     * @param approver 使该基线成为基线的操作者身份（自动建立同样留痕，纯治理元数据）
     */
    public synchronized void autoEstablishBaseline(InteractionRecord record, String approver, InvocationRulesConfig rules) {
        establish(record, approver, false, rules);
    }

    /**
     * 以当前判定语义重建基线——判定语义版本升级后的恢复路径。
     * 用当前算法对既有录制重新提指纹并覆盖活跃画像，版本标签按归档占用顺延；
     * 被替换的旧基线先归档留痕（含其治理事实），rollback 可恢复——恢复出的
     * 旧语义基线会被重放入口的版本校验拒绝判定，属预期，再次重建即可。
     *
     * @param record   该调用点的任一已录制交互
     * @param approver 重建操作者身份
     * @param rules    规则配置（维度 3-4 口径，与重放判定同源；null = 无规则）
     * @throws IllegalStateException 该调用点无画像且无录制数据可解析时抛出
     */
    public synchronized void reestablishBaseline(InteractionRecord record, String approver, InvocationRulesConfig rules) {
        establish(record, approver, true, rules);
    }

    private void establish(InteractionRecord record, String approver, boolean overwrite, InvocationRulesConfig rules) {
        if (record == null) {
            return;
        }
        // 画像字段（label/invocationName）全部以解析器产出为基底——
        // 记录上的 invocationId 只是可选业务声明位，未声明记录（模板/请求锚点身份）同样建档

        InvocationProfile grouping = InvocationResolver.resolve(record);
        InvocationProfile existing = repository.findInvocationByKey(grouping.getInvocationKey());

        if (!overwrite && existing != null && existing.getFingerprint() != null) {
            // 已有基线，不覆盖
            return;
        }
        if (overwrite && existing != null) {
            // 被替换基线先行归档：重建不是不可逆操作，rollback 可恢复旧语义基线
            //（归档快照的是旧基线自身的指纹与治理事实，必须先于覆盖写入）
            archiveIfAbsent(grouping.getInvocationKey(), existing);
        }

        // 提取指纹作为基线
        // 规则口径必须与重放判定同源（三参提取注入维度 3-4）；
        // 存档指纹只作展示与审计，任何对比一律现场重提，不消费存档值
        DeterministicFingerprint fingerprint = FingerprintExtractor.extract(record, rules, record.getInvocationId());

        // 以解析器产出为基底：invocation_name/invocation_type 等展示列来自解析的派生结果，
        // 裸画像会违反存储层的 NOT NULL 契约
        InvocationProfile profile = existing != null ? existing : grouping;
        profile.setFingerprint(fingerprint);
        profile.setCandidateFingerprint(null);
        profile.setBaselineStatus(BaselineStatus.BASELINE);
        profile.setVersionTag(overwrite ? nextAvailableVersionTag(grouping.getInvocationKey(), profile.getVersionTag()) : "v1");
        profile.setTotalRecords(existing != null ? existing.getTotalRecords() : 1);
        stampApproval(profile, approver);

        repository.saveInvocationProfile(profile);
    }

    /**
     * 盖上审批痕迹：语义版本 + 审批人 + 时间。建立/批准/重建三条成为基线的路径共用。
     */
    private void stampApproval(InvocationProfile profile, String approver) {
        profile.setAlgoVersion(JudgmentSemantics.VERSION);
        // 空白身份归一为 null：approvedBy=null 是「未经审批链盖章」的异常信号，
        // 空白串落库会稀释该信号。core 只归一调用方传入的身份，不嗅探环境
        profile.setApprovedBy(approver == null || approver.trim().isEmpty() ? null : approver.trim());
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
     * 按最新可分组记录重算画像模板哈希（approve 前移与漂移收编共用的口径）。
     * 身份凭据 = 存储键与现算键一致且可解析的最新记录；凭据缺失（记录损坏或全部
     * 不可用）或其模板哈希为空时保守保留原值。只改内存画像不落库，由调用方随
     * 其余字段一并写入。
     *
     * @return 是否发生前移
     */
    private boolean recomputeTemplateHash(String invocationKey, InvocationProfile profile) {
        List<InteractionRecord> records = repository.findByInvocationKey(invocationKey);
        InteractionRecord anchor = DriftDetector.latestIdentityRecord(records, invocationKey);
        String latestHash = anchor != null ? anchor.getTemplateHash() : null;
        if (latestHash == null || latestHash.isEmpty() || latestHash.equals(profile.getTemplateHash())) {
            return false;
        }
        profile.setTemplateHash(latestHash);
        return true;
    }

    /**
     * 归档当前基线为模板版本行——同 tag 已存在归档行时跳过（回滚恢复的基线本就在归档中）；
     * 无版本标签的基线无回滚句柄，不归档。
     */
    private void archiveIfAbsent(String invocationKey, InvocationProfile profile) {
        if (profile.getFingerprint() == null || profile.getVersionTag() == null) {
            return;
        }
        if (repository.findArchivedVersion(invocationKey, profile.getVersionTag()) == null) {
            // 归档行是该基线的完整快照：指纹、版本标签之外，模板哈希、语义版本与
            // 审批事实一并留痕，回滚时据此恢复活跃行的治理信息
            ArchivedTemplateVersion archived = new ArchivedTemplateVersion();
            archived.setInvocationKey(invocationKey);
            archived.setTemplateHash(profile.getTemplateHash());
            archived.setFingerprint(profile.getFingerprint());
            archived.setVersionTag(profile.getVersionTag());
            archived.setAlgoVersion(profile.getAlgoVersion());
            archived.setApprovedBy(profile.getApprovedBy());
            archived.setApprovedAt(profile.getApprovedAt());
            repository.archiveTemplateVersion(archived);
        }
    }

    /**
     * 递增版本标签并跳过归档中已占用的 tag——保证任一 tag 在归档与活跃态之间
     * 始终只对应一个指纹，rollback(tag) 不产生歧义。
     */
    private String nextAvailableVersionTag(String invocationKey, String currentTag) {
        String next = generateVersionTag(currentTag);
        while (repository.findArchivedVersion(invocationKey, next) != null) {
            next = generateVersionTag(next);
        }
        return next;
    }
}
