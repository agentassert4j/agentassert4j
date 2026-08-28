package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 裁决命令基类 — approve 与 reject 共用的目标解析与执行流程。
 *
 * <p>候选由 replay 落库；裁决与重放通常不在同一进程，操作对象是持久化的
 * skill_profiles 行而非内存对象。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
abstract class AdjudicateCommand implements Callable<Integer> {

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--skill"}, description = "目标 skill：业务 skillId、groupKey 或其唯一前缀（完整列表见 status 命令）")
    String skill;

    @Option(names = {"--all"}, description = "裁决所有存在候选指纹的 skill")
    boolean all;

    @Override
    public Integer call() {
        if (skill != null && all) {
            System.err.println("--skill 与 --all 不能同时使用。");
            return 2;
        }
        if (skill == null && !all) {
            System.err.println("需要 --skill <业务标签 / groupKey / 唯一前缀> 或 --all。");
            return 2;
        }
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db);
            List<SkillProfile> targets = resolveTargets(repository);
            if (targets.isEmpty()) {
                printNoTargets(repository);
                return 2;
            }

            BaselineManager manager = new BaselineManager(repository);
            for (SkillProfile target : targets) {
                printCandidateDiff(target);
                apply(manager, target.getGroupKey());
                // approve/reject 在管理器内部改写画像，回读展示结果状态
                SkillProfile reloaded = repository.findSkillByGroupKey(target.getGroupKey());
                System.out.println("  " + target.getGroupKey() + ": " + describeResult(reloaded != null ? reloaded : target));
            }
            return 0;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            System.err.println("裁决失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private List<SkillProfile> resolveTargets(StorageRepository repository) {
        List<SkillProfile> targets = new ArrayList<>();
        if (skill != null) {
            // 统一解析：完整 groupKey / 业务标签 / groupKey 唯一前缀三种写法等价，
            // 与 replay/baseline 的 --skill 值域一致
            String groupKey = CliSupport.resolveGroupKeyTarget(repository, skill);
            SkillProfile profile = repository.findSkillByGroupKey(groupKey);
            if (profile == null) {
                throw new IllegalStateException("skill " + groupKey + " 尚无基线画像（先执行 baseline）。");
            }
            targets.add(profile);
            return targets;
        }
        for (SkillProfile profile : repository.findAllSkills()) {
            if (profile.getCandidateFingerprint() != null) {
                targets.add(profile);
            }
        }
        return targets;
    }

    private void printNoTargets(StorageRepository repository) {
        if (skill != null) {
            System.err.println("没有匹配 " + skill + " 的 skill（业务标签或 groupKey 前缀，完整列表见 status 命令）。");
            return;
        }
        List<String> pending = new ArrayList<>();
        for (SkillProfile profile : repository.findAllSkills()) {
            if (profile.getCandidateFingerprint() != null) {
                pending.add(profile.getGroupKey());
            }
        }
        System.err.println(pending.isEmpty() ? "没有任何待裁决的候选。" : "待裁决: " + String.join(", ", pending));
    }

    /**
     * 裁决前渲染候选与基线的逐维差异——裁决者必须在拍板时看到证据本身，
     * 而不是只看到一个「有候选」的标志位（replay 的差异输出是易失的进程输出）。
     */
    private static void printCandidateDiff(SkillProfile target) {
        if (target.getCandidateFingerprint() == null) {
            return;
        }
        System.out.println("  " + target.getGroupKey() + " 候选差异（基线 → 候选）:");
        for (String line : FingerprintDiffRenderer.render(target.getFingerprint(), target.getCandidateFingerprint())) {
            System.out.println("    " + line);
        }
    }

    /**
     * 执行裁决操作（approve/reject）。
     */
    abstract void apply(BaselineManager manager, String groupKey);

    /**
     * 裁决成功后的结果描述行。
     */
    abstract String describeResult(SkillProfile profile);
}
