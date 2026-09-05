package io.github.agentassert4j.cli;

import picocli.CommandLine.Command;

/**
 * graph 命令组入口 — 依赖图谱查看。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "graph", aliases = {"g"}, description = "Dependency graph inspection", subcommands = {GraphShowCommand.class}, mixinStandardHelpOptions = true)
public class GraphCommand {
}
