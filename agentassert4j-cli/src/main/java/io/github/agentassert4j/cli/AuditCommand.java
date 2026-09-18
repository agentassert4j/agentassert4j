package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.GovernanceEvent;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * audit 命令 — 列出治理事件时间线供回溯（读动词，恒退出码 0）。
 *
 * <p>数据源 = 治理事件表（governance_events）的时间线：六个治理动词（accept/reject/
 * rollback/establish/force-rebuild/collect）发生时经 BaselineManager 统一写入——
 * 不分操作主体与通道，AI（MCP）与人类（CLI）的治理写同账本同时间线。actor 列
 * 自解释主体：{@code agent:} 前缀是机器写的显式申报约定（见 governance.md），
 * 其余为人类身份。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
@Command(name = "audit", aliases = {"au"}, description = "List governance writes (accept/reject/rollback/establish/force-rebuild/collect) from the governance event timeline for review — AI and human writes on one timeline", mixinStandardHelpOptions = true)
public class AuditCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout (agentassert4j.audit/1)")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            List<String> rows = new ArrayList<>();
            List<String> lines = new ArrayList<>();
            for (GovernanceEvent event : repository.findGovernanceEvents()) {
                rows.add(rowJson(event));
                lines.add(humanLine(event));
            }
            if (rows.isEmpty()) {
                if (jsonOutput) {
                    out.println("{\"schema\":\"" + ReportSchemas.AUDIT + "\",\"writes\":[]}");
                } else {
                    out.println("No governance writes found.");
                }
                return 0;
            }
            if (jsonOutput) {
                out.println("{\"schema\":\"" + ReportSchemas.AUDIT + "\",\"writes\":[" + String.join(",", rows) + "]}");
            } else {
                out.println("Governance writes (" + rows.size() + ", from the governance event timeline):");
                for (String line : lines) {
                    out.println(line);
                }
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "audit failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private static String verbName(GovernanceEvent event) {
        return event.getVerb() != null ? event.getVerb().wireName() : "unknown";
    }

    private static String rowJson(GovernanceEvent event) {
        StringBuilder sb = new StringBuilder("{\"verb\":\"").append(RecursiveJsonParser.escape(verbName(event))).append('"');
        sb.append(",\"invocationKey\":\"").append(RecursiveJsonParser.escape(event.getInvocationKey())).append('"');
        sb.append(",\"versionTag\":\"").append(RecursiveJsonParser.escape(event.getVersionTag() != null ? event.getVersionTag() : "")).append('"');
        sb.append(",\"actor\":\"").append(RecursiveJsonParser.escape(event.getActor() != null ? event.getActor() : "")).append('"');
        if (event.getCodeRef() != null) {
            sb.append(",\"codeRef\":\"").append(RecursiveJsonParser.escape(event.getCodeRef())).append('"');
        }
        sb.append(",\"happenedAt\":").append(event.getHappenedAt() != null ? event.getHappenedAt().toString() : "null");
        return sb.append('}').toString();
    }

    private static String humanLine(GovernanceEvent event) {
        StringBuilder sb = new StringBuilder("  [").append(verbName(event)).append("] ").append(CliSupport.displayKey(event.getInvocationKey()));
        if (event.getVersionTag() != null) {
            sb.append(' ').append(event.getVersionTag());
        }
        sb.append(' ').append(event.getActor() != null ? event.getActor() : "(no actor)");
        if (event.getCodeRef() != null) {
            sb.append(" (ref ").append(event.getCodeRef()).append(')');
        }
        return sb.toString();
    }
}
