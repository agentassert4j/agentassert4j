package io.github.agentassert4j.cli;

import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * baseline 命令 — 为已录制的交互建立基线（幂等，可重复执行）。
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "baseline", aliases = {"b"}, description = "Establish baselines for recorded interactions (idempotent, safe to re-run); the export subcommand writes an acceptance pack", mixinStandardHelpOptions = true, subcommands = {BaselineExportCommand.class})
public class BaselineCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--invocation"}, description = "Only this invocation: business invocationId or a unique invocationKey prefix (defaults to all)")
    String invocation;

    @Option(names = {"--approver"}, description = "Operator identity recorded with the baseline approval (defaults to the current OS user)")
    String approver;

    @Option(names = {"--force"}, description = "Rebuild baselines under the current judgment semantics: existing baselines are overwritten by fresh fingerprints (recovery path after a judgment-semantics upgrade)")
    boolean force;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露与告警改走 stderr，建档过程行丢弃
            PrintStream notice = jsonOutput ? err : out;
            repository = CliSupport.openRepository(db, notice);
            String actor = approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
            String resolvedInvocation = CliSupport.resolveInvocationFilter(repository, invocation, notice);
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, notice);
            List<BaselineService.BaselineOutcome> outcomes = new ArrayList<>();
            int established = new BaselineService(repository).establishMissing(jsonOutput ? CliSupport.discardStream() : out, actor, force, resolvedInvocation, rules, outcomes);
            if (jsonOutput) {
                StringBuilder invocations = new StringBuilder();
                for (BaselineService.BaselineOutcome outcome : outcomes) {
                    if (invocations.length() > 0) {
                        invocations.append(",");
                    }
                    invocations.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(outcome.getInvocationKey())).append("\",\"label\":\"").append(RecursiveJsonParser.escape(outcome.getLabel())).append("\",\"action\":\"").append(outcome.getAction()).append("\",\"versionTag\":\"").append(RecursiveJsonParser.escape(outcome.getVersionTag() != null ? outcome.getVersionTag() : "")).append("\"}");
                }
                out.println("{\"schema\":\"agentassert4j.baseline-report/1\",\"force\":" + force + ",\"established\":" + established + ",\"invocations\":[" + invocations + "]}");
            } else {
                out.println(established > 0 ? "Done: " + CliSupport.plural(established, "invocation") + " " + (force ? "re-established" : "established") + "." : "Done: every invocation already has a baseline.");
            }
            return 0;
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("baseline failed: " + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
