package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.VersionMismatchException;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
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

    @Option(names = {"--invocation"}, description = "Target invocations: business label (fans out to all its buckets, listed before writing), invocationKey or its unique prefix, or the status display form (defaults to all)")
    String invocation;

    @Option(names = {"--approver"}, description = "Operator identity recorded with the baseline approval (defaults to the current OS user)")
    String approver;

    @Option(names = {"--force"}, description = "Rebuild baselines under the current judgment semantics: existing baselines are overwritten by fresh fingerprints (recovery path after a judgment-semantics upgrade)")
    boolean force;

    @Option(names = {"--expected-version"}, description = "Optimistic concurrency guard for --force: refuse unless every active baseline version still equals this tag")
    String expectedVersion;

    @Option(names = {"--ref"}, description = "Code reference (e.g. a git commit) the established baselines correspond to; declared, not verified")
    String codeRef;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露与告警改走 stderr，建档过程行丢弃
            PrintStream notice = jsonOutput ? err : out;
            repository = CliSupport.openRepository(db, err);
            String actor = approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
            List<String> resolvedKeys = CliSupport.resolveInvocationKeys(repository, invocation, true, notice);
            discloseFanOut(repository, resolvedKeys, notice);
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, notice);
            List<BaselineService.BaselineOutcome> outcomes = new ArrayList<>();
            int established = new BaselineService(repository).establishMissing(jsonOutput ? CliSupport.discardStream() : out, actor, codeRef, force, resolvedKeys == null ? null : new LinkedHashSet<>(resolvedKeys), rules, outcomes, expectedVersion);
            if (outcomes.isEmpty() && resolvedKeys != null) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, "No recorded invocation bucket matches the resolved selection.", "Check the value against `status` output, then retry.", "agentassert4j status");
            }
            if (jsonOutput) {
                StringBuilder invocations = new StringBuilder();
                for (BaselineService.BaselineOutcome outcome : outcomes) {
                    if (invocations.length() > 0) {
                        invocations.append(",");
                    }
                    invocations.append("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(outcome.getInvocationKey())).append("\",\"label\":\"").append(RecursiveJsonParser.escape(outcome.getLabel())).append("\",\"action\":\"").append(outcome.getAction()).append("\",\"versionTag\":\"").append(RecursiveJsonParser.escape(outcome.getVersionTag() != null ? outcome.getVersionTag() : "")).append("\",\"codeRef\":\"").append(RecursiveJsonParser.escape(outcome.getCodeRef() != null ? outcome.getCodeRef() : "")).append("\",\"seedRecordId\":\"").append(RecursiveJsonParser.escape(outcome.getSeedRecordId() != null ? outcome.getSeedRecordId() : "")).append("\"}");
                }
                // 选择段进报告本体：扇出披露只走诊断流时，机器消费方（MCP 的
                // structuredContent 只收 stdout 报告行）感知不到「一次调用覆盖了几个键」
                String selectionJson = resolvedKeys != null ? ",\"selection\":{\"requested\":\"" + RecursiveJsonParser.escape(invocation) + "\",\"matched\":" + resolvedKeys.size() + "}" : "";
                out.println("{\"schema\":\"" + ReportSchemas.BASELINE_REPORT + "\",\"force\":" + force + ",\"established\":" + established + selectionJson + ",\"invocations\":[" + invocations + "]}");
            } else {
                out.println(established > 0 ? "Done: " + CliSupport.plural(established, "invocation") + " " + (force ? "re-established" : "established") + "." : "Done: every selected invocation already has a baseline.");
            }
            return 0;
        } catch (VersionMismatchException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_GUARD, CliSupport.describe(e), "Run `agentassert4j status` to see the active versions, then retry with --expected-version <tag>, or drop the guard.", "agentassert4j status");
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "baseline failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 多键扇出的写前披露：一个选择器覆盖多个调用点时，逐键列出目标与建档状态——
     * 治理写动词的目标集必须在写入前可见（跨宿主共享库时防顺手为他键建档）。
     */
    private static void discloseFanOut(StorageRepository repository, List<String> resolvedKeys, PrintStream notice) {
        if (resolvedKeys == null || resolvedKeys.size() < 2) {
            return;
        }
        notice.println("Note: the selection covers " + CliSupport.plural(resolvedKeys.size(), "invocation") + ":");
        for (String key : resolvedKeys) {
            InvocationProfile profile = repository.findInvocationByKey(key);
            String status = CliSupport.hasBaseline(profile) ? "exists " + profile.getVersionTag() : "no baseline";
            notice.println("  " + CliSupport.displayKey(key) + " (" + status + ")");
        }
    }
}
