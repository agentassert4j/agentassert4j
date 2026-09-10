package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.algorithm.VersionMismatchException;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 裁决命令基类 — accept 与 reject 共用的目标解析与执行流程。
 *
 * <p>候选由 replay 落库；裁决与重放通常不在同一进程，操作对象是持久化的
 * invocations 行而非内存对象。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
abstract class AdjudicateCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--invocation"}, description = "Adjudication scope: business invocationId, invocationKey, or a unique prefix (defaults to all pending candidates)")
    String invocation;

    @Option(names = {"--expected-version"}, description = "Optimistic concurrency guard: refuse unless the active baseline version still equals this tag (see report); protects against concurrent actors changing the baseline between your inspection and this write")
    String expectedVersion;

    @Option(names = {"--json"}, description = "Print a single-line JSON report to stdout")
    boolean jsonOutput;

    @Override
    public Integer call() {
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露改走 stderr，候选差异证据行不输出
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            List<InvocationProfile> targets = resolveTargets(repository);
            if (targets.isEmpty()) {
                return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, "No candidates pending adjudication.", "Behavioral differences land candidates during `agentassert4j replay`; run it after a template change.", "agentassert4j replay");
            }

            BaselineManager manager = new BaselineManager(repository);
            List<String> results = jsonOutput ? new ArrayList<>() : null;
            for (InvocationProfile target : targets) {
                if (!jsonOutput) {
                    printCandidateDiff(target);
                }
                apply(manager, expectedVersion, target.getInvocationKey());
                // accept/reject 在管理器内部改写画像，回读展示结果状态
                InvocationProfile reloaded = repository.findInvocationByKey(target.getInvocationKey());
                InvocationProfile shown = reloaded != null ? reloaded : target;
                if (jsonOutput) {
                    results.add("{\"invocationKey\":\"" + RecursiveJsonParser.escape(target.getInvocationKey()) + "\",\"versionTag\":\"" + RecursiveJsonParser.escape(shown.getVersionTag() != null ? shown.getVersionTag() : "") + "\",\"status\":\"" + shown.getBaselineStatus() + "\",\"hasCandidate\":" + (shown.getCandidateFingerprint() != null) + ",\"codeRef\":\"" + RecursiveJsonParser.escape(shown.getCodeRef() != null ? shown.getCodeRef() : "") + "\"}");
                } else {
                    out.println("  " + target.getInvocationKey() + ": " + describeResult(shown));
                }
            }
            if (jsonOutput) {
                out.println("{\"schema\":\"agentassert4j.adjudication/1\",\"action\":\"" + action() + "\",\"invocations\":[" + String.join(",", results) + "]}");
            }
            return 0;
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (VersionMismatchException e) {
            // 乐观并发守卫：活跃版本与调用方所见不一致——并发写冲突就近拒绝
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_GUARD, CliSupport.describe(e), "Run report to see the active version, then retry with --expected-version <tag>, or drop the guard.", "report");
        } catch (IllegalStateException e) {
            // BaselineManager 的对象缺失守卫（画像/候选不存在）：无对象可操作，非环境故障
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_NO_DATA, CliSupport.describe(e), "Check the target against `status` output, then retry.", "agentassert4j status");
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "adjudication failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private List<InvocationProfile> resolveTargets(StorageRepository repository) {
        List<InvocationProfile> targets = new ArrayList<>();
        if (invocation != null) {
            // 统一解析：完整 invocationKey / 业务标签 / invocationKey 唯一前缀三种写法等价，
            // 与 replay/baseline 的 --invocation 值域一致
            String invocationKey = CliSupport.resolveInvocationKeyTarget(repository, invocation);
            InvocationProfile profile = repository.findInvocationByKey(invocationKey);
            if (profile == null) {
                throw new IllegalStateException("Invocation " + invocationKey + " has no baseline profile (run `agentassert4j baseline` first).");
            }
            targets.add(profile);
            return targets;
        }
        // bare = 裁决全部待裁决候选（与「bare 命令=全项目完整默认能力」同一心智）
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (profile.getCandidateFingerprint() != null) {
                targets.add(profile);
            }
        }
        return targets;
    }

    /**
     * 裁决前渲染候选与基线的逐维差异——裁决者必须在拍板时看到证据本身，
     * 而不是只看到一个「有候选」的标志位（replay 的差异输出是易失的进程输出）。
     */
    private void printCandidateDiff(InvocationProfile target) {
        if (target.getCandidateFingerprint() == null) {
            return;
        }
        out.println("  " + target.getInvocationKey() + " candidate diff (baseline → candidate):");
        for (String line : FingerprintDiffRenderer.render(target.getFingerprint(), target.getCandidateFingerprint())) {
            out.println("    " + line);
        }
    }

    /**
     * 执行裁决操作（accept/reject）。
     */
    abstract void apply(BaselineManager manager, String expectedVersion, String invocationKey);

    /**
     * 裁决动作名——--json 报告的 action 字段，区分共用报告契约的两个命令。
     */
    abstract String action();

    /**
     * 裁决成功后的结果描述行。
     */
    abstract String describeResult(InvocationProfile profile);
}
