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
@Command(name = "reject", description = "拒绝候选指纹，保留旧基线", mixinStandardHelpOptions = true)
public class RejectCommand extends AdjudicateCommand {

    @Override
    void apply(BaselineManager manager, String invocationKey) {
        manager.reject(invocationKey);
    }

    @Override
    String describeResult(InvocationProfile profile) {
        return "已拒绝，保留基线 " + profile.getVersionTag();
    }
}
