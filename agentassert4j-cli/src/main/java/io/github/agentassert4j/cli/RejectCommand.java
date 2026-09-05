package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.InvocationProfile;
import picocli.CommandLine.Command;

/**
 * reject 命令 — 拒绝候选指纹，保留旧基线（回滚 Prompt 由开发者自理）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "reject", aliases = {"rj"}, description = "Discard the candidate fingerprint and keep the current baseline", mixinStandardHelpOptions = true)
public class RejectCommand extends AdjudicateCommand {

    @Override
    void apply(BaselineManager manager, String invocationKey) {
        manager.reject(invocationKey);
    }

    @Override
    String action() {
        return "reject";
    }

    @Override
    String describeResult(InvocationProfile profile) {
        return "Rejected; baseline kept at " + profile.getVersionTag();
    }
}
