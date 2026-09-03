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
@Command(name = "replay", description = "全项目模板漂移检测与任务对齐判定（缺省零 LLM 调用，非 0 退出码可作 CI gating）", mixinStandardHelpOptions = true)
public class ReplayCommand implements Callable<Integer> {

    // 输出通道：实例字段而非直接引用系统流——包内测试可在实例化后注入替代流
    PrintStream out = System.out;
    PrintStream err = System.err;

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--task"}, description = "缩域：请求文本前缀选任务链（可与 --invocation 复合）")
    String task;

    @Option(names = {"--invocation"}, description = "缩域：目标调用点——业务 invocationId、invocationKey、唯一前缀或 status 显示短形（可与 --task 复合）")
    String invocation;

    @Option(names = {"--ci"}, description = "CI 模式：不自动建档，缩域内存在未建档调用点拒绝判定（退出码 2），漂移身份不收编（附警告出 0）——治理写不进流水线")
    boolean ciMode;

    @Option(names = {"--re-drive"}, description = "受控重驱（花 LLM 调用）：逐漂移点以最新归档模板重驱录制输入复核行为；建议先 --dry-run 看报价")
    boolean reDrive;

    @Option(names = {"--full-chain"}, description = "重驱扩域：取消「仅漂移点」裁剪，缩域内全部记录逐条重驱（要求 --re-drive）")
    boolean fullChain;

    @Option(names = {"--max-total-calls"}, description = "重驱预算池：本次运行真重驱调用次数合计上限（要求 --re-drive）")
    Integer maxTotalCalls;

    @Option(names = {"--max-total-tokens"}, description = "重驱预算池：本次运行真重驱 token 合计上限（要求 --re-drive）")
    Integer maxTotalTokens;

    @Option(names = {"--dry-run"}, description = "只读预演：漂移集、对齐计划与重驱成本预估，不建档、不写图快照、不处置")
    boolean dryRun;

    @Option(names = {"--json"}, description = "stdout 只输出单行 JSON 证据报告（供 CI/agent 消费）；进度静默，诊断与用法错误走 stderr")
    boolean jsonOutput;

    @Override
    public Integer call() {
        if (fullChain && !reDrive) {
            err.println("--full-chain 仅在受控重驱（--re-drive）下有效。");
            return 2;
        }
        if ((maxTotalCalls != null || maxTotalTokens != null) && !reDrive) {
            err.println("--max-total-calls/--max-total-tokens 预算池仅在受控重驱（--re-drive）下有效。");
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
                (jsonOutput ? err : out).println("警告：未配置 API Key（agentassert4j.json 的 llm.apiKey 或其 ${ENV} 引用），重驱调用将失败。");
            }
            TestExecutionConfig executionConfig = new TestExecutionConfig().timeoutMs(config.getLlm().getTimeoutMs()).temperature(config.getLlm().getTemperature());
            InvocationRulesConfig rules = ConfigLoader.loadRulesConfig();
            CliSupport.warnUnknownBehaviors(rules, jsonOutput ? System.err : System.out);
            CliSupport.warnMalformedTaskRules(rules, jsonOutput ? System.err : System.out);

            return new TaskReplayRunner(repository, client, comparator, rules, executionConfig, out, err, jsonOutput).run(task, resolvedInvocation, ciMode, dryRun, reDrive, fullChain, maxTotalCalls, maxTotalTokens);
        } catch (RuntimeException e) {
            err.println("replay 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }
}
