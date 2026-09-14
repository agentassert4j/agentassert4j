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
 * audit 命令 — 列出 agent 驱动的治理写供人类回溯（读动词，恒退出码 0）。
 *
 * <p>数据源 = 治理事件表（governance_events）的时间线：六个治理动词（accept/reject/
 * rollback/establish/force-rebuild/collect）发生时经 BaselineManager 单源落账，
 * 含 reject 与 rollback 这两个不在画像上留状态痕迹的动作。识别口径 = 操作主体以
 * {@code agent:} 前缀申报自己（机器写主体的显式申报约定，见 governance.md）；本命令
 * 是事件表的 agent 透镜，人类写经 status/report 的版本史可见、不进本清单。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
@Command(name = "audit", aliases = {"au"}, description = "List agent-driven governance writes (actor marked agent:*) from the governance event timeline for human review", mixinStandardHelpOptions = true)
public class AuditCommand implements Callable<Integer> {

    /**
     * agent 身份前缀——治理写主体「这是机器在操作」的显式申报约定（自由字符串，
     * 框架不校验、不强制；申报让 audit 可区分机器写与人写）。
     */
    static final String AGENT_ACTOR_PREFIX = "agent:";

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
                if (isAgentDriven(event.getActor())) {
                    rows.add(rowJson(event));
                    lines.add(humanLine(event));
                }
            }
            if (rows.isEmpty()) {
                if (jsonOutput) {
                    out.println("{\"schema\":\"" + ReportSchemas.AUDIT + "\",\"writes\":[]}");
                } else {
                    out.println("No agent-driven governance writes found.");
                }
                return 0;
            }
            if (jsonOutput) {
                out.println("{\"schema\":\"" + ReportSchemas.AUDIT + "\",\"writes\":[" + String.join(",", rows) + "]}");
            } else {
                out.println("Agent-driven governance writes (" + rows.size() + ", from the governance event timeline):");
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

    static boolean isAgentDriven(String actor) {
        return actor != null && actor.startsWith(AGENT_ACTOR_PREFIX);
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
