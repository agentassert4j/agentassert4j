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
 * replay 命令 — 全项目变更检测与真实对齐判定：库是一切数据的唯一权威来源，提示词内容只从
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

    @Option(names = {"--task"}, description = "Task selector: matches exactly one task chain by request-text prefix (a prefix hitting several tasks errors with the candidate list; see --dry-run for the pairing plan)")
    String task;

    @Option(names = {"--invocation"}, description = "Invocation selector: a business invocationId selects all its template-version buckets; an invocationKey prefix or the status display form must resolve to exactly one key (multiple matches error with the candidate list). Narrows alignment reporting and drift/re-drive to this invocation; task discipline still sees the full chain")
    String invocation;

    @Option(names = {"--ci"}, description = "CI mode: judges the latest execution of each invocation in each task's latest chain against its approved shape set (earlier same-session records stay visible as notes, not gated); no auto-establish (refuses to judge when the chain-final invocations hold unestablished keys, exit 2); drift identity PASS is not collected (exit 0 with a warning); CHANGED findings still land candidates awaiting adjudication — no other governance writes")
    boolean ciMode;

    @Option(names = {"--member-check"}, description = "Member determination: the latest chain of each task is checked against the most recent chains (bounded window) and passes if its behavior matches any of them; the member block carries the matched count (stability probe before accepting a new shape); default pairing compares the latest chain against the previous one only")
    boolean memberCheck;

    @Option(names = {"--member-window"}, paramLabel = "N|all", description = "Member-check sample window: an integer >= 1, or 'all' to scan every historical chain (archaeology, not a stability signal). Default: 5, overridable via regression.memberSampleWindow in agentassert4j.json (finite integers only); this run's value wins (requires --member-check)")
    String memberWindow;

    @Option(names = {"--re-drive"}, description = "Controlled re-drive (spends LLM calls): drift points by default, or every invocation in scope with --task/--invocation; re-drives recorded inputs with each point's latest archived template. Run --dry-run first for a cost estimate")
    boolean reDrive;

    @Option(names = {"--full-chain"}, description = "Widen the re-drive: drop the drift-points-only trim and re-drive every record in scope (requires --re-drive)")
    boolean fullChain;

    @Option(names = {"--max-total-calls"}, description = "Re-drive budget pool: cap on real re-drive calls for this run (requires --re-drive)")
    Integer maxTotalCalls;

    @Option(names = {"--max-total-tokens"}, description = "Re-drive budget pool: cap on total re-drive tokens for this run (requires --re-drive)")
    Integer maxTotalTokens;

    @Option(names = {"--model"}, description = "Re-drive model override for this run (requires --re-drive): passed to the LLM endpoint as-is, wins over llm.model in agentassert4j.json; the emitter disclosure and the model-switch warning reflect it")
    String model;

    @Option(names = {"--endpoint"}, description = "Re-drive endpoint override for this run (requires --re-drive): base URL of the LLM API, wins over llm.endpoint in agentassert4j.json")
    String endpoint;

    @Option(names = {"--dry-run"}, description = "Read-only preview: drift set, alignment plan and re-drive cost estimate; no baseline writes, no graph snapshot, no dispositions")
    boolean dryRun;

    @Option(names = {"--json"}, description = "stdout carries JSON evidence documents, one per line (drift summary, per-task alignments, disposition, exit health; for CI/agent consumption); diagnostics and usage errors go to stderr")
    boolean jsonOutput;

    @Override
    public Integer call() {
        if (fullChain && !reDrive) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--full-chain requires --re-drive.", "Add --re-drive to run the widened re-drive, or drop --full-chain.", "agentassert4j replay --re-drive --full-chain");
        }
        if ((maxTotalCalls != null || maxTotalTokens != null) && !reDrive) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--max-total-calls/--max-total-tokens require --re-drive.", "Add --re-drive, or drop the budget caps.", "");
        }
        if (maxTotalCalls != null && maxTotalCalls < 1) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--max-total-calls must be >= 1.", "Pass a positive call cap, or drop the flag for no cap.", "");
        }
        if (maxTotalTokens != null && maxTotalTokens < 1) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--max-total-tokens must be >= 1.", "Pass a positive token cap, or drop the flag for no cap.", "");
        }
        if (memberWindow != null && !memberCheck) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--member-window requires --member-check.", "Add --member-check, or drop --member-window to keep the configured default window.", "agentassert4j replay --member-check");
        }
        // 解析阶梯的显式侧（配置默认在 config 加载后并入）；all 只接受单次调用
        // 显式传入（配置默认只收有限整数）
        Integer resolvedMemberWindow = null;
        boolean memberAllHistory = false;
        if (memberWindow != null) {
            if (memberWindow.trim().equalsIgnoreCase("all")) {
                memberAllHistory = true;
            } else {
                try {
                    resolvedMemberWindow = Integer.valueOf(Integer.parseInt(memberWindow.trim()));
                } catch (NumberFormatException e) {
                    return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--member-window must be an integer >= 1 or 'all', got '" + memberWindow + "'.", "Pass a positive integer, 'all', or drop the flag.", "");
                }
                if (resolvedMemberWindow.intValue() < 1) {
                    return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--member-window must be >= 1.", "Pass a positive integer, 'all', or drop the flag.", "");
                }
            }
        }
        if ((model != null && model.trim().isEmpty()) || (endpoint != null && endpoint.trim().isEmpty())) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--model/--endpoint must not be blank.", "Pass a model name / base URL, or drop the flag to keep the configured value.", "");
        }
        if ((model != null || endpoint != null) && !reDrive) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_USAGE, "--model/--endpoint override the re-drive LLM call; add --re-drive.", "The only LLM-consuming path is the controlled re-drive; zero-call commands have no model to override.", "");
        }
        StorageRepository repository = null;
        try {
            // 配置加载在 try 内：坏配置文件抛出的异常必须落 E-ENV 包络，
            // 不得穿透给 picocli 打全栈
            AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
            // 解析阶梯的配置侧：显式缺省时取 regression.memberSampleWindow（有限整数，
            // 配置面只收 N；all 仅单次调用显式传入）
            if (memberWindow == null && config.getRegression().getMemberSampleWindow() != null) {
                resolvedMemberWindow = config.getRegression().getMemberSampleWindow();
            }
            repository = CliSupport.openRepository(db, err);

            String resolvedInvocation = null;
            if (invocation != null) {
                resolvedInvocation = CliSupport.resolveInvocationKeyTarget(repository, invocation);
            }

            DeterministicComparator comparator = CliSupport.createComparator(config);
            // 本次运行覆盖（--model/--endpoint）在客户端构造前并入配置——发射客户端
            // 的模型/端点经同一条装配路径生效；executionConfig 的 model 单独携带
            // （观测记录的请求模型身份与换模型告警消费它）
            CliSupport.applyReDriveOverrides(config, model, endpoint);
            LlmClient client = CliSupport.createLlmClient(config);
            if (reDrive && TextUtil.isBlank(config.getLlm().getApiKey())) {
                (jsonOutput ? err : out).println("Warning: no API key configured (llm.apiKey in agentassert4j.json or its ${ENV} reference); re-drive calls will fail.");
            }
            TestExecutionConfig executionConfig = new TestExecutionConfig().timeoutMs(config.getLlm().getTimeoutMs()).temperature(config.getLlm().getTemperature()).maxTokens(config.getLlm().getMaxTokens()).endpoint(config.getLlm().getEndpoint()).wireProtocol(config.getLlm().getProtocol()).model(model != null ? model.trim() : null);
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, jsonOutput ? err : out);
            CliSupport.warnMalformedTaskRules(rules, jsonOutput ? err : out);

            return new TaskReplayRunner(repository, client, comparator, rules, executionConfig, out, err, jsonOutput).run(task, resolvedInvocation, ciMode, dryRun, memberCheck, resolvedMemberWindow, memberAllHistory, reDrive, fullChain, maxTotalCalls, maxTotalTokens);
        } catch (CliFailureException e) {
            return CliSupport.fail(jsonOutput, out, err, e);
        } catch (RuntimeException e) {
            return CliSupport.fail(jsonOutput, out, err, CliErrorCode.E_ENV, "replay failed: " + CliSupport.describe(e), "Fix the reported problem and retry; `agentassert4j doctor` reports database and config health.", "agentassert4j doctor");
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
