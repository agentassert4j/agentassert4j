package io.github.agentassert4j.cli;

import picocli.AutoComplete;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * completion 命令 — 生成 shell 补全脚本（bash 风格；zsh 经 bashcompinit 兼容）。
 *
 * @author axy-yxa
 * @since 2026-09-03
 */
@Command(name = "completion", aliases = {"c"}, description = "Generate a shell completion script (bash style; zsh via bashcompinit)", mixinStandardHelpOptions = true)
public class CompletionCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--shell"}, description = "Target shell: bash (default; zsh compatible)", defaultValue = "bash")
    String shell;

    @Override
    public Integer call() {
        if (!"bash".equals(shell)) {
            err.println("Only bash-style completion is supported (zsh via bashcompinit); got: " + shell);
            return 2;
        }
        try {
            out.print(AutoComplete.bash("agentassert4j", new CommandLine(new AgentAssert4jCli())));
            return 0;
        } catch (RuntimeException e) {
            err.println("completion generation failed: " + e.getMessage());
            return 2;
        }
    }
}
