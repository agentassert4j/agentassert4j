package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.TextUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * replay 命令 — 全项目变更检测与真实对齐判定：库是一切真源，提示词内容只从
 * 应用→录制→库流入引擎，命令行只负责缩域。bare 执行 = 身份检测 + 逐任务对齐 +
 * 漂移处置，零 LLM 调用零参数即完整默认能力。
 *
 * <p>退出码即 CI gating：0 无行为回归；1 行为差异或证据缺口；2 用法/数据问题。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "replay", aliases = {"rp"}, description = "Project-wide template drift detection and task alignment verdicts (zero LLM calls by default; non-zero exit codes gate CI)", mixinStandardHelpOptions = true)
public class ReplayCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite database path (defaults to storage.url in agentassert4j.json)")
    String db;

    @Option(names = {"--task"}, description = "Narrow the scope: select task chains by request-text prefix (combinable with --invocation)")
    String task;

    @Option(names = {"--invocation"}, description = "Narrow the scope: target invocation by business invocationId, invocationKey, unique prefix, or the status display form (combinable with --task)")
    String invocation;

    @Option(names = {"--ci"}, description = "CI mode: no auto-establish; refuses to judge when the scope holds unestablished invocations (exit 2), drift identities are not collected (exit 0 with a warning); no governance writes from the pipeline")
    boolean ciMode;

    @Option(names = {"--re-drive"}, description = "Controlled re-drive (spends LLM calls): drift points by default, or every invocation in scope with --task/--invocation; re-drives recorded inputs with each point's latest archived template. Run --dry-run first for a cost estimate")
    boolean reDrive;

    @Option(names = {"--full-chain"}, description = "Widen the re-drive: drop the drift-points-only trim and re-drive every record in scope (requires --re-drive)")
    boolean fullChain;

    @Option(names = {"--max-total-calls"}, description = "Re-drive budget pool: cap on real re-drive calls for this run (requires --re-drive)")
    Integer maxTotalCalls;

    @Option(names = {"--max-total-tokens"}, description = "Re-drive budget pool: cap on total re-drive tokens for this run (requires --re-drive)")
    Integer maxTotalTokens;

    @Option(names = {"--dry-run"}, description = "Read-only preview: drift set, alignment plan and re-drive cost estimate; no baseline writes, no graph snapshot, no dispositions")
    boolean dryRun;

    @Option(names = {"--json"}, description = "stdout carries a single-line JSON evidence report (for CI/agent consumption); diagnostics and usage errors go to stderr")
    boolean jsonOutput;

    @Override
    public Integer call() {
        if (fullChain && !reDrive) {
            err.println("--full-chain requires --re-drive.");
            return 2;
        }
        if ((maxTotalCalls != null || maxTotalTokens != null) && !reDrive) {
            err.println("--max-total-calls/--max-total-tokens require --re-drive.");
            return 2;
        }
        if (maxTotalCalls != null && maxTotalCalls < 1) {
            err.println("--max-total-calls must be >= 1.");
            return 2;
        }
        if (maxTotalTokens != null && maxTotalTokens < 1) {
            err.println("--max-total-tokens must be >= 1.");
            return 2;
        }
        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, jsonOutput ? System.err : System.out);

            String resolvedInvocation = null;
            if (invocation != null) {
                resolvedInvocation = CliSupport.resolveInvocationKeyTarget(repository, invocation);
            }

            DeterministicComparator comparator = CliSupport.createComparator(config);
            LlmClient client = CliSupport.createLlmClient(config);
            if (reDrive && TextUtil.isBlank(config.getLlm().getApiKey())) {
                (jsonOutput ? err : out).println("Warning: no API key configured (llm.apiKey in agentassert4j.json or its ${ENV} reference); re-drive calls will fail.");
            }
            TestExecutionConfig executionConfig = new TestExecutionConfig().timeoutMs(config.getLlm().getTimeoutMs()).temperature(config.getLlm().getTemperature());
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, jsonOutput ? System.err : System.out);
            CliSupport.warnMalformedTaskRules(rules, jsonOutput ? System.err : System.out);

            return new TaskReplayRunner(repository, client, comparator, rules, executionConfig, out, err, jsonOutput).run(task, resolvedInvocation, ciMode, dryRun, reDrive, fullChain, maxTotalCalls, maxTotalTokens);
        } catch (RuntimeException e) {
            err.println("replay failed: " + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
