package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.JudgmentSemantics;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.ScenarioConfig;
import io.github.agentassert4j.model.ScenarioRun;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * scenario run — 执行场景集：声明场景逐个执行；未提供场景文件时自动派生
 * （每个已建档分组一个噪声基线场景，量化模型自身非确定性）。
 *
 * <p>退出码契约与 replay 一致：0 = 证据完整且全部 STABLE；1 = 存在 DRIFTED
 * （行为漂移或断言失败）；2 = 用法或基础设施问题——无场景可执行、存在跳过
 * 场景（绑定无匹配/模板缺失/断言不可用）或存在 INSUFFICIENT（判定样本为零）。
 * 证据不完整不允许冒充绿。</p>
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
@Command(name = "run", description = "执行场景集（N 轮统计回归 + 断言）", mixinStandardHelpOptions = true)
public class ScenarioRunCommand implements Callable<Integer> {

    @Option(names = "--scenarios", description = "场景配置文件路径（缺省读取 agentassert4j-scenarios.json；未找到则自动派生）")
    String scenariosPath;

    @Option(names = "--db", description = "SQLite 数据库路径（缺省读主配置 storage.url）")
    String db;

    @Option(names = "--json", description = "stdout 仅输出单行 JSON 证据报告")
    boolean jsonMode;

    @Option(names = "--dry-run", description = "只列出将执行的场景与预计调用量，不发起真实调用")
    boolean dryRun;

    PrintStream out = System.out;
    PrintStream err = System.err;

    @Override
    public Integer call() {
        PrintStream reportOut = jsonMode ? ScenarioRunner.discardStream() : out;
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db, reportOut);
            ScenarioConfig config = loadScenarios();

            AgentAssert4jConfig mainConfig = ConfigLoader.loadAgentAssert4jConfig();
            if (TextUtil.isBlank(mainConfig.getLlm().getApiKey())) {
                (jsonMode ? err : out).println("警告：未配置 API Key（agentassert4j.json 的 llm.apiKey 或其 ${ENV} 引用），LLM 调用将失败。");
            }
            LlmClient client = CliSupport.createLlmClient(mainConfig);
            ScenarioRunner runner = new ScenarioRunner(repository, client, CliSupport.createComparator(mainConfig), reportOut);
            ScenarioRunner.Outcome outcome = runner.run(config, dryRun);

            if (jsonMode) {
                out.println(reportsJson(outcome));
            }
            return exitCode(outcome);
        } catch (RuntimeException e) {
            err.println("scenario run 失败：" + e.getMessage());
            return 2;
        } finally {
            if (repository != null) {
                repository.close();
            }
        }
    }

    /**
     * 场景配置加载：隐式查找链（cwd→home→classpath）缺省；显式 --scenarios
     * 路径优先且读不到即配置错误（fail-fast，不静默换源）。
     */
    private ScenarioConfig loadScenarios() {
        ScenarioConfig config = ConfigLoader.loadScenariosConfig();
        if (scenariosPath != null && !scenariosPath.isEmpty()) {
            String json = ConfigLoader.loadFromFile(scenariosPath);
            if (json == null) {
                throw new IllegalStateException("显式指定的场景文件不可读: " + scenariosPath);
            }
            config = ScenarioConfig.fromJson(json);
        }
        if (!jsonMode) {
            out.println("场景 " + config.getScenarios().size() + " 个" + (config.getScenarios().isEmpty() ? "（未声明场景，将按已建档分组自动派生）" : "") + "。");
        }
        return config;
    }

    /**
     * 退出码：有 DRIFTED 即 1（行为漂移或断言失败）；无漂移时存在 INSUFFICIENT
     * （判定样本为零）或跳过场景（绑定无匹配/模板缺失/断言不可用）按 2 处理，
     * 与「证据不完整不允许冒充绿」同一口径。
     */
    private int exitCode(ScenarioRunner.Outcome outcome) {
        if (dryRun) {
            return 0;
        }
        boolean drifted = false;
        boolean insufficient = false;
        for (ScenarioRun run : outcome.getRuns()) {
            if ("DRIFTED".equals(run.getVerdict())) {
                drifted = true;
            }
            if ("INSUFFICIENT".equals(run.getVerdict())) {
                insufficient = true;
            }
        }
        if (drifted) {
            return 1;
        }
        if (insufficient || !outcome.getSkipped().isEmpty() || outcome.getRuns().isEmpty()) {
            return 2;
        }
        return 0;
    }

    /**
     * 证据报告（单行 JSON）：run 模式给执行事实，dry-run 模式给执行计划；
     * 两模式都带跳过清单——跳过是证据缺口，必须与判定同场披露。
     */
    private String reportsJson(ScenarioRunner.Outcome outcome) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.scenario-report/1\",\"mode\":\"");
        sb.append(dryRun ? "dry-run" : "run");
        sb.append("\",\"judgmentSemantics\":\"").append(JudgmentSemantics.VERSION).append('"');
        sb.append(",\"skipped\":").append(skippedJson(outcome.getSkipped()));
        if (dryRun) {
            sb.append(",\"planned\":").append(plannedJson(outcome.getPlanned()));
        } else {
            sb.append(",\"runs\":").append(runsJson(outcome.getRuns()));
        }
        return sb.append('}').toString();
    }

    private static String skippedJson(List<ScenarioRunner.Skip> skipped) {
        List<String> items = new ArrayList<>();
        for (ScenarioRunner.Skip skip : skipped) {
            items.add("{\"scenarioId\":\"" + RecursiveJsonParser.escape(skip.scenarioId) + "\",\"reason\":\"" + RecursiveJsonParser.escape(skip.reason) + "\"}");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String plannedJson(List<ScenarioRunner.PlannedScenario> planned) {
        List<String> items = new ArrayList<>();
        for (ScenarioRunner.PlannedScenario plannedScenario : planned) {
            items.add("{\"scenarioId\":\"" + RecursiveJsonParser.escape(plannedScenario.scenarioId) + "\",\"description\":\"" + RecursiveJsonParser.escape(plannedScenario.description) + "\",\"sampleCount\":" + plannedScenario.sampleCount + "}");
        }
        return "[" + String.join(",", items) + "]";
    }

    private static String runsJson(List<ScenarioRun> runs) {
        List<String> items = new ArrayList<>();
        for (ScenarioRun run : runs) {
            StringBuilder item = new StringBuilder("{");
            item.append("\"scenarioId\":\"").append(RecursiveJsonParser.escape(run.getScenarioId())).append('"');
            item.append(",\"verdict\":\"").append(run.getVerdict()).append('"');
            item.append(",\"sampleCount\":").append(run.getSampleCount());
            item.append(",\"passCount\":").append(run.getPassCount());
            item.append(",\"failCount\":").append(run.getFailCount());
            item.append(",\"inputTokens\":").append(run.getInputTokens());
            item.append(",\"outputTokens\":").append(run.getOutputTokens());
            item.append("}");
            items.add(item.toString());
        }
        return "[" + String.join(",", items) + "]";
    }
}
