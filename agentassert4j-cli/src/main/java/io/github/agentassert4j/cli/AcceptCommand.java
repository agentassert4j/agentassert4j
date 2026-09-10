package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.InvocationProfile;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * accept 命令 — 接受候选指纹为新基线，旧基线归档（可回滚）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "accept", aliases = {"a"}, description = "Promote the candidate fingerprint to baseline (previous baseline archived, restorable via rollback)", mixinStandardHelpOptions = true)
public class AcceptCommand extends AdjudicateCommand {

    @Option(names = {"--approver"}, description = "Approver identity recorded with the baseline and its archives (defaults to the current OS user)")
    String approver;

    @Option(names = {"--ref"}, description = "Code reference (e.g. a git commit) the promoted baseline corresponds to; declared, not verified")
    String codeRef;

    @Override
    void apply(BaselineManager manager, String expectedVersion, String invocationKey) {
        manager.accept(invocationKey, expectedVersion, resolvedApprover(), codeRef);
    }

    @Override
    String action() {
        return "accept";
    }

    @Override
    String describeResult(InvocationProfile profile) {
        return "Accepted (approver " + resolvedApprover() + "); baseline " + profile.getVersionTag() + (profile.getCodeRef() != null ? " (ref " + profile.getCodeRef() + ")" : "") + " (previous baseline archived)";
    }

    private String resolvedApprover() {
        return approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
    }
}
