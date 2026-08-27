package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.SkillProfile;
import picocli.CommandLine.Command;

/**
 * approve 命令 — 接受候选指纹为新基线，旧基线归档（可回滚）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "approve", description = "接受候选指纹为新基线（旧基线归档，可 rollback）")
public class ApproveCommand extends AdjudicateCommand {

    @Override
    void apply(BaselineManager manager, String groupKey) {
        manager.approve(groupKey);
    }

    @Override
    String describeResult(SkillProfile profile) {
        return "已批准，基线 " + profile.getVersionTag() + "（旧基线已归档）";
    }
}
