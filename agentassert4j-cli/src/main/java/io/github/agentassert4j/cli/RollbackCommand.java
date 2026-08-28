package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.ArchivedBaseline;
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
@Command(name = "rollback", description = "把活跃基线恢复到指定版本的归档基线", mixinStandardHelpOptions = true)
public class RollbackCommand implements Callable<Integer> {

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--skill"}, required = true, description = "目标 skill：业务 skillId、groupKey 或其唯一前缀（完整列表见 status 命令）")
    String skill;

    @Option(names = {"--version"}, required = true, description = "目标归档版本标签（可选值见 status 的归档版本列）")
    String version;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db);
            String groupKey = CliSupport.resolveGroupKeyTarget(repository, skill);
            SkillProfile target = repository.findSkillByGroupKey(groupKey);
            if (target == null) {
                throw new IllegalStateException("skill " + groupKey + " 尚无基线画像。");
            }
            ensureVersionExists(repository, groupKey, version);
            new BaselineManager(repository).rollback(groupKey, version);
            SkillProfile reloaded = repository.findSkillByGroupKey(groupKey);
            System.out.println("  " + groupKey + " → " + version + "（审批人 " + reloaded.getApprovedBy() + "）");
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

    /**
     * 版本不存在时列出全部可选归档版本——rollback 的 --version 是必填值，
     * 可选值没有发现渠道时用户只能猜，这里是猜错的出口。
     */
    private static void ensureVersionExists(StorageRepository repository, String groupKey, String version) {
        for (ArchivedBaseline archived : repository.findArchivedBaselines(groupKey)) {
            if (version.equals(archived.getVersionTag())) {
                return;
            }
        }
        List<String> available = new ArrayList<>();
        for (ArchivedBaseline archived : repository.findArchivedBaselines(groupKey)) {
            available.add(archived.getVersionTag());
        }
        throw new IllegalStateException("skill " + groupKey + " 没有归档版本 " + version + (available.isEmpty() ? "，且没有任何归档（从未 approve 过或基线未经替换）。" : "。可选版本：" + String.join(", ", available)));
    }
}
