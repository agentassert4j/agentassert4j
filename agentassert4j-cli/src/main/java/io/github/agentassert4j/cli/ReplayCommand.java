package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.TextUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.Callable;

/**
 * replay 命令 — 用新 System Prompt 重放录制用例并对比基线；双 scope 并存：
 * --invocation（单调用点重放）与 --task/--affected（整链任务回归）。
 *
 * <p>退出码即 CI gating：0 全部 PASS；1 存在行为差异（CHANGED/缺步骤/新增步骤）；
 * 2 用法/数据问题（含预算耗尽无判定）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "replay", description = "用新 System Prompt 重放录制用例并对比基线（非 PASS 退出码非 0，可作 CI gating）；--task/--affected 以用户任务为回放单元", mixinStandardHelpOptions = true)
public class ReplayCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;


    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--prompt"}, description = "新 System Prompt 文件路径（--task 下可选：缺省=真实对比模式，同名链最新 vs 次新纯比较零调用）")
    String promptPath;

    @Option(names = {"--old-prompt"}, description = "变更前 System Prompt 文件路径（提供后按依赖图裁剪影响集；--task 下受影响记录真重放、其余继承 PASS）")
    String oldPromptPath;

    @Option(names = {"--invocation"}, description = "仅重放该调用点：业务 invocationId 或 invocationKey 唯一前缀（完整列表见 status 命令）")
    String invocation;

    @Option(names = {"--task"}, description = "任务域回放：请求文本前缀选链（整链回归）；提供 --prompt 为冻结重放，缺省为真实对比")
    String task;

    @Option(names = {"--affected"}, description = "任务选择器：图查受影响调用点 → 含它的任务链逐链冻结重放（要求 --prompt 与 --old-prompt）")
    boolean affected;

    @Option(names = {"--full-chain"}, description = "任务域专用：取消影响裁剪与分歧即停，链内全部记录真重放")
    boolean fullChain;

    @Option(names = {"--max-total-calls"}, description = "任务域预算池：本次运行真重放调用次数合计上限（耗尽后剩余步骤跳过）")
    Integer maxTotalCalls;

    @Option(names = {"--max-total-tokens"}, description = "任务域预算池：本次运行真重放 token 合计上限")
    Integer maxTotalTokens;

    @Option(names = {"--max-cases"}, defaultValue = "3", description = "默认选例模式下每 调用点 的用例上限（默认 3）")
    int maxCases;

    @Option(names = {"--selection"}, defaultValue = "newest", description = "选例策略：newest=每 调用点 最新录制（默认），oldest=最旧录制")
    String selection;

    @Option(names = {"--ci", "--no-establish"}, description = "CI 模式：不为无基线的 调用点 自动建档，存在未建档 调用点 时拒绝判定（退出码 2）——防止无人审的自动基线产出绿灯")
    boolean ciMode;

    @Option(names = {"--dry-run"}, description = "只打印选例与成本预估，不调用 LLM（只读：不建档、不写图快照）")
    boolean dryRun;

    @Option(names = {"--json"}, description = "stdout 只输出单行 JSON 证据报告（供 CI/agent 消费）；进度静默，诊断与用法错误走 stderr")
    boolean jsonOutput;

    @Override
    public Integer call() {
        int selectors = (task != null ? 1 : 0) + (invocation != null ? 1 : 0) + (affected ? 1 : 0);
        if (selectors > 1) {
            err.println("--task、--invocation、--affected 三种回放范围互斥，请只选其一。");
            return 2;
        }
        boolean taskScope = task != null || affected;
        if (fullChain && !taskScope) {
            err.println("--full-chain 仅在任务域（--task/--affected）有效。");
            return 2;
        }
        if ((maxTotalCalls != null || maxTotalTokens != null) && !taskScope) {
            err.println("--max-total-calls/--max-total-tokens 预算池仅在任务域（--task/--affected）有效。");
            return 2;
        }
        if (affected && (promptPath == null || oldPromptPath == null)) {
            err.println("--affected 要求同时提供 --prompt 与 --old-prompt（影响集以此二 hash 为根）。");
            return 2;
        }
        if (!taskScope && promptPath == null) {
            err.println("缺少 --prompt（调用点范围重放必须提供新 System Prompt 文件；任务域对比模式可省略）。");
            return 2;
        }
        if (maxTotalCalls != null && maxTotalCalls < 1) {
            err.println("--max-total-calls 必须 ≥ 1。");
            return 2;
        }
        if (maxTotalTokens != null && maxTotalTokens < 1) {
            err.println("--max-total-tokens 必须 ≥ 1。");
            return 2;
        }

        String newPrompt = null;
        if (promptPath != null) {
            newPrompt = readTextFile(promptPath);
            if (newPrompt == null) {
                err.println("无法读取 Prompt 文件：" + promptPath);
                return 2;
            }
        }
        String oldPrompt = null;
        String oldPromptHash = null;
        if (oldPromptPath != null) {
            oldPrompt = readTextFile(oldPromptPath);
            if (oldPrompt == null) {
                err.println("无法读取旧 Prompt 文件：" + oldPromptPath);
                return 2;
            }
            oldPromptHash = HashUtil.sha256(oldPrompt);
        }
        if (!taskScope) {
            if (!"newest".equals(selection) && !"oldest".equals(selection)) {
                err.println("--selection 只接受 newest 或 oldest，当前值：" + selection);
                return 2;
            }
            if (maxCases < 1) {
                err.println("--max-cases 必须 ≥ 1，当前值：" + maxCases);
                return 2;
            }
        }

        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, jsonOutput ? System.err : System.out);

            DeterministicComparator comparator = CliSupport.createComparator(config);

            if (TextUtil.isBlank(config.getLlm().getApiKey()) && newPrompt != null) {
                (jsonOutput ? err : out).println("警告：未配置 API Key（agentassert4j.json 的 llm.apiKey 或其 ${ENV} 引用），LLM 调用将失败。");
            }
            LlmClient client = CliSupport.createLlmClient(config);

            TestExecutionConfig executionConfig = new TestExecutionConfig().timeoutMs(config.getLlm().getTimeoutMs()).temperature(config.getLlm().getTemperature());
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, jsonOutput ? System.err : System.out);

            if (taskScope) {
                return new TaskReplayRunner(repository, client, comparator, rules, executionConfig, out, err, jsonOutput).run(task, affected, fullChain, newPrompt, oldPrompt, maxTotalCalls, maxTotalTokens);
            }

            boolean newestFirst = "newest".equals(selection);
            return new ReplayRunner(repository, client, comparator, rules, executionConfig, out, err, jsonOutput).run(newPrompt, invocation, maxCases, oldPromptHash, dryRun, ciMode, newestFirst);
        } catch (RuntimeException e) {
            err.println("replay 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    private static String readTextFile(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }
}
