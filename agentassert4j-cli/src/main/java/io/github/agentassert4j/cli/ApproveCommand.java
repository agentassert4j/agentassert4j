package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.SkillProfile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * approve 命令 — 接受候选指纹为新基线，旧基线归档（可回滚）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "approve", description = "接受候选指纹为新基线（旧基线归档，可 rollback）", mixinStandardHelpOptions = true)
public class ApproveCommand extends AdjudicateCommand {

    @Option(names = {"--approver"}, description = "审批人身份，随基线与归档留痕（缺省取当前系统用户）")
    String approver;

    @Override
    void apply(BaselineManager manager, String groupKey) {
        manager.approve(groupKey, resolvedApprover());
    }

    @Override
    String describeResult(SkillProfile profile) {
        return "已批准（审批人 " + resolvedApprover() + "），基线 " + profile.getVersionTag() + "（旧基线已归档）";
    }

    private String resolvedApprover() {
        return approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
    }
}
