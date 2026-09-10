package io.github.agentassert4j.cli;

import picocli.CommandLine.Command;

/**
 * record 命令 — 已录制交互的排障/取证查看面（读取 raw wire 双列）。
 *
 * @author axy-yxa
 * @since 2026-09-09
 */
@Command(name = "record", description = "Inspect stored interactions (raw wire payloads)", subcommands = {RecordShowCommand.class}, mixinStandardHelpOptions = true)
public class RecordCommand {
}
