package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.InvocationProfile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * approve 命令 — 接受候选指纹为新基线，旧基线归档（可回滚）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "approve", aliases = {"a"}, description = "Promote the candidate fingerprint to baseline (previous baseline archived, restorable via rollback)", mixinStandardHelpOptions = true)
public class ApproveCommand extends AdjudicateCommand {

    @Option(names = {"--approver"}, description = "Approver identity recorded with the baseline and its archives (defaults to the current OS user)")
    String approver;

    @Override
    void apply(BaselineManager manager, String invocationKey) {
        manager.approve(invocationKey, resolvedApprover());
    }

    @Override
    String action() {
        return "approve";
    }

    @Override
    String describeResult(InvocationProfile profile) {
        return "Approved (approver " + resolvedApprover() + "); baseline " + profile.getVersionTag() + " (previous baseline archived)";
    }

    private String resolvedApprover() {
        return approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
    }
}
