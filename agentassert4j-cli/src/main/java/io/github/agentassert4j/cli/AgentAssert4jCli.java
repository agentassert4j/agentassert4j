package io.github.agentassert4j.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * AgentAssert4j 命令行入口 — 库是一切真源：提示词内容只从应用→录制→库流入引擎，
 * bare 命令即全项目完整默认能力，参数只做缩域或开关。
 *
 * <p>典型流程：</p>
 * <pre>
 * agentassert4j status                            # inspect invocations and baseline status
 * agentassert4j replay                            # project-wide drift detection + per-task alignment (zero LLM calls)
 * agentassert4j replay --re-drive                 # controlled re-drive: re-check drift points with archived templates (spends calls)
 * agentassert4j approve                           # adjudicate all pending candidates
 * agentassert4j rollback --invocation ab12cd34 --version v1   # restore an archived baseline
 * agentassert4j completion > agentassert4j.bash   # generate a shell completion script
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "agentassert4j", version = "AgentAssert4j 1.0.0-SNAPSHOT", description = {"AI Agent behavior regression testing framework — side-channel recording, deterministic baselines, change detection with real alignment, human adjudication.", "", "Bare commands are the full-project default (replay makes zero LLM calls by default); flags only narrow scope or toggle behavior.", "Typical loop: replay for project-wide change detection → CHANGED/drift lands candidates → approve/reject to adjudicate → rollback to recover."}, exitCodeList = {"0", "no behavioral regression (in --ci mode, uncollected drift still exits 0 with a warning)", "1", "behavioral difference or evidence gap: alignment CHANGED/missing steps/added steps/rule violations/hung drifts (evidence incomplete; re-run for real or re-drive to complete)", "2", "usage, data or environment problem: selector errors, guard refusals, budget exhausted, all re-drives failed (truncated or broken environment)"}, subcommands = {BaselineCommand.class, StatusCommand.class, ReplayCommand.class, ApproveCommand.class, RejectCommand.class, RollbackCommand.class, RulesCommand.class, GraphCommand.class, VerifyCommand.class, DoctorCommand.class, CompletionCommand.class}, mixinStandardHelpOptions = true)
public class AgentAssert4jCli {

    public static void main(String[] args) {
        CliSupport.installUtf8Console();
        int exitCode = new CommandLine(new AgentAssert4jCli()).execute(args);
        System.exit(exitCode);
    }
}
