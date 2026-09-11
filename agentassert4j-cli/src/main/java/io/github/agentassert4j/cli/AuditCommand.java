package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.ArchivedTemplateVersion;
import io.github.agentassert4j.model.InvocationProfile;
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
 * <p>识别口径 = 审批人以 {@code agent:} 前缀申报自己（机器写主体的显式申报
 * 约定，见 governance.md）。活跃画像与归档行都列出——归档是历史批准，回溯时
 * 同样需要。已知边界：rollback 恢复历史行不产生新审批痕迹；reject 不盖章，
 * 两者不进本清单。</p>
 *
 * @author axy-yxa
 * @since 2026-09-08
 */
@Command(name = "audit", aliases = {"au"}, description = "List agent-driven governance writes (approver marked agent:*) for human review", mixinStandardHelpOptions = true)
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
            for (InvocationProfile profile : repository.findAllInvocations()) {
                if (isAgentDriven(profile.getApprovedBy())) {
                    rows.add(rowJson("active", profile.getInvocationKey(), profile.getVersionTag(), profile.getApprovedBy(), profile.getCodeRef(), profile.getApprovedAt()));
                    lines.add(humanLine("active", profile.getInvocationKey(), profile.getVersionTag(), profile.getApprovedBy(), profile.getCodeRef()));
                }
                for (ArchivedTemplateVersion archived : repository.findArchivedVersions(profile.getInvocationKey())) {
                    if (isAgentDriven(archived.getApprovedBy())) {
                        rows.add(rowJson("archived", archived.getInvocationKey(), archived.getVersionTag(), archived.getApprovedBy(), archived.getCodeRef(), archived.getApprovedAt()));
                        lines.add(humanLine("archived", archived.getInvocationKey(), archived.getVersionTag(), archived.getApprovedBy(), archived.getCodeRef()));
                    }
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
                out.println("Agent-driven governance writes (" + rows.size() + "):");
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

    static boolean isAgentDriven(String approvedBy) {
        return approvedBy != null && approvedBy.startsWith(AGENT_ACTOR_PREFIX);
    }

    private static String rowJson(String state, String invocationKey, String versionTag, String approvedBy, String codeRef, Long approvedAt) {
        StringBuilder sb = new StringBuilder("{\"state\":\"").append(state).append('"');
        sb.append(",\"invocationKey\":\"").append(RecursiveJsonParser.escape(invocationKey)).append('"');
        sb.append(",\"versionTag\":\"").append(RecursiveJsonParser.escape(versionTag != null ? versionTag : "")).append('"');
        sb.append(",\"approvedBy\":\"").append(RecursiveJsonParser.escape(approvedBy)).append('"');
        if (codeRef != null) {
            sb.append(",\"codeRef\":\"").append(RecursiveJsonParser.escape(codeRef)).append('"');
        }
        sb.append(",\"approvedAt\":").append(approvedAt != null ? approvedAt.toString() : "null");
        return sb.append('}').toString();
    }

    private static String humanLine(String state, String invocationKey, String versionTag, String approvedBy, String codeRef) {
        StringBuilder sb = new StringBuilder("  [").append(state).append("] ").append(CliSupport.displayKey(invocationKey));
        if (versionTag != null) {
            sb.append(' ').append(versionTag);
        }
        sb.append(' ').append(approvedBy);
        if (codeRef != null) {
            sb.append(" (ref ").append(codeRef).append(')');
        }
        return sb.toString();
    }
}
