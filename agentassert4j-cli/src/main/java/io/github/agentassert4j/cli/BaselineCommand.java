package io.github.agentassert4j.cli;

import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.spi.StorageRepository;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * baseline 命令 — 为已录制的交互建立基线（幂等，可重复执行）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "baseline", description = "为已录制的交互建立基线（幂等，可重复执行）", mixinStandardHelpOptions = true)
public class BaselineCommand implements Callable<Integer> {

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--skill"}, description = "只处理该 skill：业务 skillId 或 groupKey 唯一前缀（缺省全部 skill）")
    String skill;

    @Option(names = {"--approver"}, description = "操作者身份，随基线审批留痕（缺省取当前系统用户）")
    String approver;

    @Option(names = {"--force"}, description = "以当前判定语义重建基线：已有基线也被当前算法新指纹覆盖（判定语义升级后的恢复路径）")
    boolean force;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db);
            String actor = approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
            String resolvedSkill = CliSupport.resolveBusinessSkillFilter(repository, skill, System.out);
            SkillRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, System.out);
            int established = new BaselineService(repository).establishMissing(System.out, actor, force, resolvedSkill, rules);
            System.out.println(established > 0 ? "完成：" + established + " 个 skill " + (force ? "重建" : "新建") + "基线。" : "完成：所有 skill 均已有基线。");
            return 0;
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            System.err.println("baseline 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
