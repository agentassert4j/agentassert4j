package io.github.agentassert4j.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * AgentAssert4j 命令行入口 — 库是一切数据的唯一权威来源：提示词内容只从应用→录制→库流入引擎，
 * bare 命令即全项目完整默认能力，参数只做缩域或开关。
 *
 * <p>典型流程：</p>
 * <pre>
 * agentassert4j status                            # inspect invocations and baseline status
 * agentassert4j replay                            # project-wide drift detection + per-task alignment (zero LLM calls)
 * agentassert4j replay --re-drive                 # controlled re-drive: re-check drift points with archived templates (spends calls)
 * agentassert4j accept                            # adjudicate all pending candidates
 * agentassert4j rollback --invocation ab12cd34 --version v1   # restore an archived baseline
 * agentassert4j completion > agentassert4j.bash   # generate a shell completion script
 * </pre>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "agentassert4j", versionProvider = FrameworkVersionProvider.class, description = {"AI Agent behavior regression testing framework — side-channel recording, deterministic baselines, change detection with real alignment, human adjudication.", "", "Bare commands are the full-project default (replay makes zero LLM calls by default); flags only narrow scope or toggle behavior.", "Typical loop: replay for project-wide change detection → CHANGED/drift lands candidates → accept/reject to adjudicate → rollback to recover.", "Machine channel: --json emits schema-tagged JSON documents, one report per line (multi-report commands emit a document stream); a failed run appends an agentassert4j.error/1 envelope line with hints and the next action."}, exitCodeList = {"0: no behavioral regression (in --ci mode, uncollected drift still exits 0 with a warning)", "1: behavioral difference or evidence gap: alignment CHANGED/missing steps/added steps/rule violations/hung drifts (evidence incomplete; re-run for real or re-drive to complete)", "2: usage, data or environment problem: selector errors, guard refusals, budget exhausted, all re-drives failed (truncated or broken environment)"}, exitCodeListHeading = "Exit Codes:%n", subcommands = {BaselineCommand.class, StatusCommand.class, ReplayCommand.class, AcceptCommand.class, RejectCommand.class, RollbackCommand.class, RecordCommand.class, RulesCommand.class, GraphCommand.class, VerifyCommand.class, DoctorCommand.class, AuditCommand.class, McpCommand.class, CompletionCommand.class}, mixinStandardHelpOptions = true)
public class AgentAssert4jCli {

    /**
     * 框架版本——--version 输出、验收包 meta.frameworkVersion、MCP serverInfo 共用本值。
     * 真源是根 POM 的 project.version：构建期资源过滤填充 framework-version.properties，
     * 本类只读取不持有字面量（版本翻转只改 POM 一处）。资源缺失或过滤未生效时退化为
     * "unknown"（诊断值宁缺勿错），过滤失效由 FrameworkVersionProviderTest 岗哨拦截。
     */
    public static final String FRAMEWORK_VERSION = loadFrameworkVersion();

    private static String loadFrameworkVersion() {
        try (InputStream in = AgentAssert4jCli.class.getResourceAsStream("framework-version.properties")) {
            if (in == null) {
                return "unknown";
            }
            Properties props = new Properties();
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            String version = props.getProperty("framework.version");
            return version == null || version.isEmpty() || version.contains("${") ? "unknown" : version;
        } catch (IOException e) {
            return "unknown";
        }
    }

    public static void main(String[] args) {
        CliSupport.installUtf8Console();
        CliSupport.installEnglishJulFormatter();
        int exitCode = new CommandLine(new AgentAssert4jCli()).execute(args);
        System.exit(exitCode);
    }
}
