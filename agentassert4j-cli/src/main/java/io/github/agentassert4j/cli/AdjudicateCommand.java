package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.BaselineManager;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 裁决命令基类 — approve 与 reject 共用的目标解析与执行流程。
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


    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--invocation"}, description = "目标调用点：业务 invocationId、invocationKey 或其唯一前缀（完整列表见 status 命令）")
    String invocation;

    @Option(names = {"--json"}, description = "stdout 只输出单行 JSON 报告")
    boolean jsonOutput;

    @Option(names = {"--all"}, description = "裁决所有存在候选指纹的调用点")
    boolean all;

    @Override
    public Integer call() {
        if (invocation != null && all) {
            err.println("--invocation 与 --all 不能同时使用。");
            return 2;
        }
        if (invocation == null && !all) {
            err.println("需要 --invocation <业务标签 / invocationKey / 唯一前缀> 或 --all。");
            return 2;
        }
        StorageRepository repository = null;
        try {
            // --json 模式 stdout 只产出报告本体：配置披露改走 stderr，候选差异证据行不输出
            repository = CliSupport.openRepository(db, jsonOutput ? err : out);
            List<InvocationProfile> targets = resolveTargets(repository);
            if (targets.isEmpty()) {
                printNoTargets(repository);
                return 2;
            }

            BaselineManager manager = new BaselineManager(repository);
            List<String> results = jsonOutput ? new ArrayList<>() : null;
            for (InvocationProfile target : targets) {
                if (!jsonOutput) {
                    printCandidateDiff(target);
                }
                apply(manager, target.getInvocationKey());
                // approve/reject 在管理器内部改写画像，回读展示结果状态
                InvocationProfile reloaded = repository.findInvocationByKey(target.getInvocationKey());
                InvocationProfile shown = reloaded != null ? reloaded : target;
                if (jsonOutput) {
                    results.add("{\"invocationKey\":\"" + RecursiveJsonParser.escape(target.getInvocationKey()) + "\",\"versionTag\":\"" + RecursiveJsonParser.escape(shown.getVersionTag() != null ? shown.getVersionTag() : "") + "\",\"status\":\"" + shown.getBaselineStatus() + "\",\"hasCandidate\":" + (shown.getCandidateFingerprint() != null) + "}");
                } else {
                    out.println("  " + target.getInvocationKey() + ": " + describeResult(shown));
                }
            }
            if (jsonOutput) {
                out.println("{\"schema\":\"agentassert4j.adjudication/1\",\"action\":\"" + action() + "\",\"invocations\":[" + String.join(",", results) + "]}");
            }
            return 0;
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return 2;
        } catch (RuntimeException e) {
            err.println("裁决失败：" + e.getMessage());
            return 2;
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
                throw new IllegalStateException("调用点 " + invocationKey + " 尚无基线画像（先执行 baseline）。");
            }
            targets.add(profile);
            return targets;
        }
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (profile.getCandidateFingerprint() != null) {
                targets.add(profile);
            }
        }
        return targets;
    }

    private void printNoTargets(StorageRepository repository) {
        if (invocation != null) {
            err.println("没有匹配 " + invocation + " 的调用点（业务标签或 invocationKey 前缀，完整列表见 status 命令）。");
            return;
        }
        List<String> pending = new ArrayList<>();
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (profile.getCandidateFingerprint() != null) {
                pending.add(profile.getInvocationKey());
            }
        }
        err.println(pending.isEmpty() ? "没有任何待裁决的候选。" : "待裁决: " + String.join(", ", pending));
    }

    /**
     * 裁决前渲染候选与基线的逐维差异——裁决者必须在拍板时看到证据本身，
     * 而不是只看到一个「有候选」的标志位（replay 的差异输出是易失的进程输出）。
     */
    private void printCandidateDiff(InvocationProfile target) {
        if (target.getCandidateFingerprint() == null) {
            return;
        }
        out.println("  " + target.getInvocationKey() + " 候选差异（基线 → 候选）:");
        for (String line : FingerprintDiffRenderer.render(target.getFingerprint(), target.getCandidateFingerprint())) {
            out.println("    " + line);
        }
    }

    /**
     * 执行裁决操作（approve/reject）。
     */
    abstract void apply(BaselineManager manager, String invocationKey);

    /**
     * 裁决动作名——--json 报告的 action 字段，区分共用报告契约的两个命令。
     */
    abstract String action();

    /**
     * 裁决成功后的结果描述行。
     */
    abstract String describeResult(InvocationProfile profile);
}
