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
@Command(name = "rollback", description = "把活跃基线恢复到指定版本的归档基线", mixinStandardHelpOptions = true)
public class RollbackCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--invocation"}, required = true, description = "目标调用点：业务 invocationId、invocationKey 或其唯一前缀（完整列表见 status 命令）")
    String invocation;

    @Option(names = {"--version"}, required = true, description = "目标归档版本标签（可选值见 status 的归档版本列）")
    String version;

    @Option(names = {"--json"}, description = "stdout 只输出单行 JSON 报告")
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
                throw new IllegalStateException("调用点 " + invocationKey + " 尚无基线画像。");
            }
            ensureVersionExists(repository, invocationKey, version);
            new BaselineManager(repository).rollback(invocationKey, version);
            InvocationProfile reloaded = repository.findInvocationByKey(invocationKey);
            if (jsonOutput) {
                out.println("{\"schema\":\"agentassert4j.rollback/1\",\"invocationKey\":\"" + RecursiveJsonParser.escape(invocationKey) + "\",\"versionTag\":\"" + RecursiveJsonParser.escape(version) + "\",\"status\":\"" + reloaded.getBaselineStatus() + "\",\"approvedBy\":\"" + RecursiveJsonParser.escape(reloaded.getApprovedBy() != null ? reloaded.getApprovedBy() : "") + "\",\"ok\":true}");
            } else {
                out.println("  " + invocationKey + " → " + version + "（审批人 " + reloaded.getApprovedBy() + "）");
            }
            return 0;
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("回滚失败：" + e.getMessage());
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
        throw new IllegalStateException("调用点 " + invocationKey + " 没有归档版本 " + version + (available.isEmpty() ? "，且没有任何归档（从未 approve 过或基线未经替换）。" : "。可选版本：" + String.join(", ", available)));
    }
}
