package io.github.agentassert4j.cli;

import picocli.CommandLine.Command;

/**
 * graph 命令组入口 — 值溯源图谱查看。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "graph", aliases = {"g"}, description = "Value-flow provenance graph inspection", subcommands = {GraphShowCommand.class}, mixinStandardHelpOptions = true)
public class GraphCommand {
}
