package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.spi.StorageRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * rollback 命令 — 把活跃基线恢复到指定版本的归档基线。
 *
 * <p>归档行在 approve 与 baseline --force 时生成；恢复出的旧语义基线会被
 * 重放守卫拒绝判定（属预期），再次 --force 以当前语义重建即可。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
@Command(name = "rollback", description = "把活跃基线恢复到指定版本的归档基线")
public class RollbackCommand implements Callable<Integer> {

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--skill"}, required = true, description = "目标 skill 的 groupKey 或其唯一前缀（完整列表见 status 命令）")
    String skill;

    @Option(names = {"--version"}, required = true, description = "目标归档版本标签（如 v1）")
    String version;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db);
            SkillProfile target = resolveTarget(repository);
            new BaselineManager(repository).rollback(target.getGroupKey(), version);
            SkillProfile reloaded = repository.findSkillByGroupKey(target.getGroupKey());
            System.out.println("  " + target.getGroupKey() + " → " + version + "（审批人 " + reloaded.getApprovedBy() + "）");
            return 0;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            System.err.println("回滚失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private SkillProfile resolveTarget(StorageRepository repository) {
        List<SkillProfile> matches = new ArrayList<>();
        for (SkillProfile profile : repository.findAllSkills()) {
            if (profile.getGroupKey() != null && profile.getGroupKey().startsWith(skill)) {
                matches.add(profile);
            }
        }
        if (matches.isEmpty()) {
            throw new IllegalStateException("没有匹配前缀 " + skill + " 的 skill（完整列表见 status 命令）。");
        }
        if (matches.size() > 1) {
            List<String> keys = new ArrayList<>();
            for (SkillProfile p : matches) {
                keys.add(p.getGroupKey());
            }
            throw new IllegalStateException("前缀匹配到多个 skill：" + String.join(", ", keys) + "，请提供更长的前缀。");
        }
        return matches.get(0);
    }
}
