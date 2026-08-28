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

    @Option(names = {"--skill"}, description = "目标 skill 的 groupKey 或其唯一前缀（完整列表见 status 命令）")
    String skill;

    @Option(names = {"--all"}, description = "裁决所有存在候选指纹的 skill")
    boolean all;

    @Override
    public Integer call() {
        if (skill == null && !all) {
            System.err.println("需要 --skill <groupKey 或唯一前缀> 或 --all。");
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
        for (SkillProfile profile : repository.findAllSkills()) {
            if (skill != null) {
                // groupKey 是用户可操作的稳定标识（chat 类含长 hash，支持前缀匹配）
                if (profile.getGroupKey() != null && profile.getGroupKey().startsWith(skill)) {
                    targets.add(profile);
                }
            } else if (profile.getCandidateFingerprint() != null) {
                targets.add(profile);
            }
        }
        if (skill != null && targets.size() > 1) {
            throw new IllegalStateException("前缀匹配到多个 skill：" + groupKeysOf(targets) + "，请提供更长的前缀。");
        }
        return targets;
    }

    private static String groupKeysOf(List<SkillProfile> profiles) {
        List<String> keys = new ArrayList<>();
        for (SkillProfile p : profiles) {
            keys.add(p.getGroupKey());
        }
        return String.join(", ", keys);
    }

    private void printNoTargets(StorageRepository repository) {
        if (skill != null) {
            System.err.println("没有匹配前缀 " + skill + " 的 skill（完整列表见 status 命令）。");
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
     * 执行裁决操作（approve/reject）。
     */
    abstract void apply(BaselineManager manager, String groupKey);

    /**
     * 裁决成功后的结果描述行。
     */
    abstract String describeResult(SkillProfile profile);
}
