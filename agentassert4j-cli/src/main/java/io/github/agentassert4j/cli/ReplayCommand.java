package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.InvocationProfile;
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

    @Option(names = {"--old-prompt"}, description = "变更前 System Prompt 文件路径（提供后仅模板与旧版门控哈希一致的记录以新提示词真重放，--task 下其余记录继承 PASS）；与 --old-key 互斥")
    String oldPromptPath;

    @Option(names = {"--old-key"}, description = "变更前调用点锚（业务标签/键前缀/status 显示短形）：以其画像模板哈希为影响裁剪根，免导出旧模板全文；与 --old-prompt 互斥")
    String oldKey;

    @Option(names = {"--invocation"}, description = "仅重放该调用点：业务 invocationId 或 invocationKey 唯一前缀（完整列表见 status 命令）")
    String invocation;

    @Option(names = {"--task"}, description = "任务域回放：请求文本前缀选链（整链回归）；提供 --prompt 为冻结重放，缺省为真实对比")
    String task;

    @Option(names = {"--affected"}, description = "任务选择器：含旧提示词调用点的任务链逐链冻结重放（要求 --prompt 与 --old-prompt）")
    boolean affected;

    @Option(names = {"--full-chain"}, description = "任务域专用：取消影响裁剪与分歧即停，链内全部记录真重放")
    boolean fullChain;

    @Option(names = {"--max-total-calls"}, description = "任务域预算池：本次运行真重放调用次数合计上限（耗尽后剩余步骤跳过）")
    Integer maxTotalCalls;

    @Option(names = {"--max-total-tokens"}, description = "任务域预算池：本次运行真重放 token 合计上限")
    Integer maxTotalTokens;

    @Option(names = {"--max-cases"}, description = "默认选例模式下每 调用点 的用例上限（默认 3；仅调用点范围有效）")
    Integer maxCases;

    @Option(names = {"--selection"}, description = "选例策略：newest=每 调用点 最新录制（缺省），oldest=最旧录制；仅调用点范围有效")
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
        if (oldKey != null && oldPromptPath != null) {
            err.println("--old-key 与 --old-prompt 互斥：同一次重放只取一个旧版根。");
            return 2;
        }
        if (affected && (promptPath == null || (oldPromptPath == null && oldKey == null))) {
            err.println("--affected 要求同时提供 --prompt 与 --old-prompt/--old-key（影响集以此二哈希为根）。");
            return 2;
        }
        if (taskScope && promptPath == null && (oldPromptPath != null || oldKey != null)) {
            err.println("--old-prompt/--old-key 仅在冻结重放（提供 --prompt）时生效；真实对比按调用点对齐，不做影响裁剪。");
            return 2;
        }
        if (fullChain && promptPath == null) {
            err.println("--full-chain 仅在冻结重放（提供 --prompt）时生效；真实对比无裁剪可取消。");
            return 2;
        }
        if (taskScope && (selection != null || maxCases != null)) {
            err.println("--selection/--max-cases 仅在调用点范围有效（任务域为整链回归，无逐调用点选例）。");
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
        if (maxCases != null && maxCases < 1) {
            err.println("--max-cases 必须 ≥ 1，当前值：" + maxCases);
            return 2;
        }

        String newPrompt = null;
        if (promptPath != null) {
            newPrompt = readTextFile(promptPath);
            if (newPrompt == null) {
                // 传入值原样回显会把误当路径传入的提示词全文倒成一堵墙——缩略到一行并点明路径语义
                err.println("无法读取 Prompt 文件（--prompt 须为可读文件路径，不是提示词文本本身）：" + CliSupport.visibleText(CliSupport.abbreviateText(promptPath, 80)));
                return 2;
            }
        }
        String oldPromptHash = null;
        if (oldPromptPath != null) {
            String oldPrompt = readTextFile(oldPromptPath);
            if (oldPrompt == null) {
                err.println("无法读取旧 Prompt 文件（--old-prompt 须为可读文件路径）：" + CliSupport.visibleText(CliSupport.abbreviateText(oldPromptPath, 80)));
                return 2;
            }
            oldPromptHash = HashUtil.sha256(oldPrompt);
            if (oldPrompt.equals(newPrompt)) {
                // 同内容两旗标是合法的噪声探针口径，但要点破，防止被误当成跨版本实验
                (jsonOutput ? err : out).println("提示：--prompt 与 --old-prompt 内容相同——本次为同模板噪声探针口径（同模板回归零假阳性验证）；跨版本实验请提供内容不同的 --old-prompt。");
            }
        }
        if (!taskScope) {
            if (selection != null && !"newest".equals(selection) && !"oldest".equals(selection)) {
                err.println("--selection 只接受 newest 或 oldest，当前值：" + selection);
                return 2;
            }
        }

        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, jsonOutput ? System.err : System.out);

            // --old-key 的哈希取画像真源列而非键内哈希段：声明骨架细分场景下键内哈希是骨架哈希
            String oldKeyHash = null;
            if (oldKey != null) {
                String resolvedKey = CliSupport.resolveInvocationKeyTarget(repository, oldKey);
                InvocationProfile profile = repository.findInvocationByKey(resolvedKey);
                oldKeyHash = profile == null ? null : profile.getTemplateHash();
                if (oldKeyHash == null || oldKeyHash.isEmpty()) {
                    err.println("调用点 " + resolvedKey + " 的画像没有模板哈希（该锚不可作 --old-key 门控根；用 status 核对）。");
                    return 2;
                }
                if (newPrompt != null && oldKeyHash.equals(HashUtil.sha256(newPrompt))) {
                    (jsonOutput ? err : out).println("提示：--old-key 指向的模板哈希与 --prompt 内容哈希相同——门控将命中该模板的全部记录（同模板口径）。");
                }
            }
            String oldRootHash = oldPromptHash != null ? oldPromptHash : oldKeyHash;

            DeterministicComparator comparator = CliSupport.createComparator(config);

            if (TextUtil.isBlank(config.getLlm().getApiKey()) && newPrompt != null) {
                (jsonOutput ? err : out).println("警告：未配置 API Key（agentassert4j.json 的 llm.apiKey 或其 ${ENV} 引用），LLM 调用将失败。");
            }
            LlmClient client = CliSupport.createLlmClient(config);

            TestExecutionConfig executionConfig = new TestExecutionConfig().timeoutMs(config.getLlm().getTimeoutMs()).temperature(config.getLlm().getTemperature());
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, jsonOutput ? System.err : System.out);
            CliSupport.warnMalformedTaskRules(rules, jsonOutput ? System.err : System.out);

            if (taskScope) {
                return new TaskReplayRunner(repository, client, comparator, rules, executionConfig, out, err, jsonOutput).run(task, affected, fullChain, newPrompt, oldRootHash, maxTotalCalls, maxTotalTokens, dryRun);
            }

            boolean newestFirst = !"oldest".equals(selection);
            return new ReplayRunner(repository, client, comparator, rules, executionConfig, out, err, jsonOutput).run(newPrompt, invocation, maxCases == null ? 3 : maxCases, oldRootHash, dryRun, ciMode, newestFirst);
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
