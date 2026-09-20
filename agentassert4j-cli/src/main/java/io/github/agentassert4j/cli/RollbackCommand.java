package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.VersionMismatchException;
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
 * <p>归档行在 accept 与 baseline --force 时生成；恢复出的旧语义基线会被
 * 重放守卫拒绝判定（属预期），再次 --force 以当前语义重建即可。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
@Command(name = "rollback", aliases = {"rb"}, description = "Restore the active baseline to an archived version")
public class RollbackCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    // 本命令的 --version 是基线版本标签，与标准 help mixin 注入的 --version（打印框架
    // 版本）选项名冲突，冲突使 mixin 整体失效（--help 一起丢，`rollback --help` 退出码 2）——
    // 这里显式声明帮助项、不用 mixinStandardHelpOptions；框架版本查询由顶层 --version 承担
    @Option(names = {"-h", "--help"}, usageHelp = true, description = "Show this help message and exit")
    boolean helpRequested;


    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--expected-version"}, description = "Optimistic concurrency guard: refuse unless the active baseline version still equals this tag; protects against concurrent actors changing the baseline between your inspection and this write")
    String expectedVersion;

    @Option(names = {"--invocation"}, description = "Target invocation (required): business invocationId, invocationKey, or a unique prefix (see `status` for the full list)")
    String invocation;

    @Option(names = {"--version"}, description = "Target archived version tag (required; see the archived column in `status`)")
    String version;

    @Option(names = {"--approver"}, description = "Rollback executor identity recorded in the governance event trail (defaults to the current OS user; agents use agent:<name>)")
    String approver;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout")
    boolean jsonOutput;

    @Override
    public Integer call() {
        List<String> missing = new ArrayList<>();
        if (invocation == null || invocation.trim().isEmpty()) missing.add("--invocation");
        if (version == null || version.trim().isEmpty()) missing.add("--version");
        if (!missing.isEmpty()) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "rollback requires " + String.join(" and ", missing) + " (see the archived column in status).", "Run `agentassert4j status` to pick the invocation and the archived version tag to restore.", "agentassert4j status");
        }
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体（配置披露恒走 err）
            repository = CliSupport.openRepository(db, err);
            String invocationKey = CliSupport.resolveInvocationKeyTarget(repository, invocation);
            InvocationProfile target = repository.findInvocationByKey(invocationKey);
            if (target == null) {
                throw new IllegalStateException("Invocation " + invocationKey + " has no baseline profile.");
            }
            ensureVersionExists(repository, invocationKey, version);
            // 回滚会顺带清空在途候选：丢弃待裁决证据是治理副作用，必须在回执披露
            boolean discardedCandidate = target.getCandidateFingerprint() != null;
            String actor = approver != null && !approver.trim().isEmpty() ? approver.trim() : CliSupport.currentActor();
            new BaselineManager(repository).rollback(invocationKey, version, expectedVersion, actor);
            InvocationProfile reloaded = repository.findInvocationByKey(invocationKey);
            if (jsonOutput) {
                out.println("{\"schema\":\"" + ReportSchemas.ROLLBACK + "\",\"invocationKey\":\"" + RecursiveJsonParser.escape(invocationKey) + "\",\"versionTag\":\"" + RecursiveJsonParser.escape(version) + "\",\"status\":\"" + reloaded.getBaselineStatus() + "\",\"approvedBy\":\"" + RecursiveJsonParser.escape(reloaded.getApprovedBy() != null ? reloaded.getApprovedBy() : "") + "\",\"executor\":\"" + RecursiveJsonParser.escape(actor) + "\"" + (discardedCandidate ? ",\"candidateDiscarded\":true" : "") + ",\"codeRef\":\"" + RecursiveJsonParser.escape(reloaded.getCodeRef() != null ? reloaded.getCodeRef() : "") + "\",\"ok\":true}");
            } else {
                // 审批事实按在场渲染：approvedBy=null 是合法形态（未经审批链写入审批记录），
                // 人读输出不得出现 "null" 字样。approvedBy 是恢复版的原审批人，
                // 本次操作者由 executor 行披露（与 audit.actor 同源）——操作者
                // 在回执里期待看到自己，两个身份并列才不误读
                StringBuilder facts = new StringBuilder("rolled back by ").append(actor);
                if (reloaded.getApprovedBy() != null) {
                    facts.append(", approver ").append(reloaded.getApprovedBy());
                }
                if (reloaded.getCodeRef() != null) {
                    facts.append(", ref ").append(reloaded.getCodeRef());
                }
                if (discardedCandidate) {
                    facts.append(", in-flight candidate discarded");
                }
                out.println("  " + invocationKey + " → " + version + " (" + facts + ")");
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (VersionMismatchException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_GUARD, CliSupport.describe(e), "Run `agentassert4j status` to see the active version, then retry with --expected-version <tag>, or drop the guard.", "agentassert4j status");
        } catch (IllegalStateException e) {
            // 目标画像/归档版本不存在，或目标=活动版本（空回滚被拒）：用法域拒绝，非环境故障
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, CliSupport.describe(e), "Pick a different archived version in `status` (the active tag is not a rollback target); to discard an in-flight candidate use `reject`.", "agentassert4j status");
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
     * 可选值没有发现渠道时用户只能猜，这里是猜错的出口。目标=活动版本时放行给
     * BaselineManager 的空回滚守卫：那里的拒绝信息带 reject 指路，比「不在归档列表」
     * 更接近用户的真实意图（丢候选）。
     */
    private static void ensureVersionExists(StorageRepository repository, String invocationKey, String version) {
        InvocationProfile profile = repository.findInvocationByKey(invocationKey);
        if (profile != null && version.equals(profile.getVersionTag())) {
            return;
        }
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
