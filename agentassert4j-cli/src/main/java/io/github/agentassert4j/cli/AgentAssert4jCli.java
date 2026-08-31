package io.github.agentassert4j.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * AgentAssert4j 命令行入口 — 录制数据落库后的基线/重放/裁决工作流驱动。
 *
 * <p>典型流程：</p>
 * <pre>
 * agentassert4j status                        # 查看已录制 调用点 与基线状态
 * agentassert4j replay --prompt new.txt       # 全量选例重放（每 调用点 3 条）
 * agentassert4j replay --old-prompt old.txt --prompt new.txt   # 依赖图裁剪影响集
 * agentassert4j approve --invocation queryOrder    # 接受候选为新基线
 * agentassert4j rollback --invocation chat:ab12 --version v1   # 恢复到归档基线
 * agentassert4j graph show                   # 现场重建并查看依赖图谱
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "agentassert4j", version = "AgentAssert4j 1.0.0-SNAPSHOT", description = "AI Agent 行为回归测试框架 — 基线、重放、裁决", subcommands = {BaselineCommand.class, StatusCommand.class, ReplayCommand.class, ApproveCommand.class, RejectCommand.class, RollbackCommand.class, RulesCommand.class, GraphCommand.class, VerifyCommand.class}, mixinStandardHelpOptions = true)
public class AgentAssert4jCli {

    public static void main(String[] args) {
        CliSupport.installUtf8Console();
        int exitCode = new CommandLine(new AgentAssert4jCli()).execute(args);
        System.exit(exitCode);
    }
}
