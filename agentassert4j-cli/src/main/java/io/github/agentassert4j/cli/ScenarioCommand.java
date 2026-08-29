package io.github.agentassert4j.cli;

import picocli.CommandLine.Command;

/**
 * scenario 命令组入口 — 场景层：多轮统计回归（语义漂移探测）。
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
@Command(name = "scenario", description = "场景层：新输入 N 轮统计回归（语义漂移探测）", subcommands = {ScenarioRunCommand.class}, mixinStandardHelpOptions = true)
public class ScenarioCommand {
}
