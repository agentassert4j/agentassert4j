package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
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
 * rollback 命令 — 把活跃基线恢复到指定版本的归档基线。
 *
 * <p>归档行在 approve 与 baseline --force 时生成；恢复出的旧语义基线会被
 * 重放守卫拒绝判定（属预期），再次 --force 以当前语义重建即可。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
@Command(name = "rollback", aliases = {"rb"}, description = "Restore the active baseline to an archived version", mixinStandardHelpOptions = true)
public class RollbackCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--invocation"}, required = true, description = "Target invocation: business invocationId, invocationKey, or a unique prefix (see `status` for the full list)")
    String invocation;

    @Option(names = {"--version"}, required = true, description = "Target archived version tag (see the archived column in `status`)")
    String version;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露改走 stderr
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            String invocationKey = CliSupport.resolveInvocationKeyTarget(repository, invocation);
            InvocationProfile target = repository.findInvocationByKey(invocationKey);
            if (target == null) {
                throw new IllegalStateException("Invocation " + invocationKey + " has no baseline profile.");
            }
            ensureVersionExists(repository, invocationKey, version);
            new BaselineManager(repository).rollback(invocationKey, version);
            InvocationProfile reloaded = repository.findInvocationByKey(invocationKey);
            if (jsonOutput) {
                out.println("{\"schema\":\"agentassert4j.rollback/1\",\"invocationKey\":\"" + RecursiveJsonParser.escape(invocationKey) + "\",\"versionTag\":\"" + RecursiveJsonParser.escape(version) + "\",\"status\":\"" + reloaded.getBaselineStatus() + "\",\"approvedBy\":\"" + RecursiveJsonParser.escape(reloaded.getApprovedBy() != null ? reloaded.getApprovedBy() : "") + "\",\"codeRef\":\"" + RecursiveJsonParser.escape(reloaded.getCodeRef() != null ? reloaded.getCodeRef() : "") + "\",\"ok\":true}");
            } else {
                // 审批事实按在场渲染：approvedBy=null 是合法形态（未经审批链盖章），
                // 人读输出不得出现 "null" 字样
                StringBuilder facts = new StringBuilder();
                if (reloaded.getApprovedBy() != null) {
                    facts.append("approver ").append(reloaded.getApprovedBy());
                }
                if (reloaded.getCodeRef() != null) {
                    if (facts.length() > 0) {
                        facts.append(", ");
                    }
                    facts.append("ref ").append(reloaded.getCodeRef());
                }
                out.println("  " + invocationKey + " → " + version + (facts.length() > 0 ? " (" + facts + ")" : ""));
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (IllegalStateException e) {
            // 目标画像/归档版本不存在：无对象可回滚，非环境故障
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, CliSupport.describe(e), "Pick a version from the archived column in `status`, then retry.", "agentassert4j status");
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "rollback failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 版本不存在时列出全部可选归档版本——rollback 的 --version 是必填值，
     * 可选值没有发现渠道时用户只能猜，这里是猜错的出口。
     */
    private static void ensureVersionExists(StorageRepository repository, String invocationKey, String version) {
        for (ArchivedTemplateVersion archived : repository.findArchivedVersions(invocationKey)) {
            if (version.equals(archived.getVersionTag())) {
                return;
            }
        }
        List<String> available = new ArrayList<>();
        for (ArchivedTemplateVersion archived : repository.findArchivedVersions(invocationKey)) {
            available.add(archived.getVersionTag());
        }
        throw new IllegalStateException("Invocation " + invocationKey + " has no archived version " + version + (available.isEmpty() ? "; there are no archived versions at all (never approved, or the baseline was never replaced)." : ". Available versions: " + String.join(", ", available)));
    }
}
