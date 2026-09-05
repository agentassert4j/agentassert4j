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
                out.println("{\"schema\":\"agentassert4j.rollback/1\",\"invocationKey\":\"" + RecursiveJsonParser.escape(invocationKey) + "\",\"versionTag\":\"" + RecursiveJsonParser.escape(version) + "\",\"status\":\"" + reloaded.getBaselineStatus() + "\",\"approvedBy\":\"" + RecursiveJsonParser.escape(reloaded.getApprovedBy() != null ? reloaded.getApprovedBy() : "") + "\",\"ok\":true}");
            } else {
                out.println("  " + invocationKey + " → " + version + " (approver " + reloaded.getApprovedBy() + ")");
            }
            return 0;
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("rollback failed: " + e.getMessage());
            return 2;
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
