package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ComparatorConfig;
import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.cli.llm.OpenAiCompatibleClient;
import io.github.agentassert4j.config.AgentAssert4jConfig;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.TextUtil;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.concurrent.Callable;

/**
 * replay 命令 — 用新 System Prompt 重放录制用例并对比基线。
 *
 * <p>退出码即 CI gating：0 全部 PASS；1 存在非 PASS；2 用法/数据问题。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
@Command(name = "replay", description = "用新 System Prompt 重放录制用例并对比基线（非 PASS 退出码非 0，可作 CI gating）")
public class ReplayCommand implements Callable<Integer> {

    @Option(names = {"--db"}, description = "SQLite 数据库路径（默认取 agentassert4j.json 的 storage.url）")
    String db;

    @Option(names = {"--prompt"}, required = true, description = "新 System Prompt 文件路径")
    String promptPath;

    @Option(names = {"--old-prompt"}, description = "变更前 System Prompt 文件路径（提供后按依赖图裁剪影响集；缺省全量选例）")
    String oldPromptPath;

    @Option(names = {"--skill"}, description = "仅重放该 skill：业务 skillId 或 groupKey 唯一前缀（完整列表见 status 命令）")
    String skill;

    @Option(names = {"--max-cases"}, defaultValue = "3", description = "默认选例模式下每 skill 的用例上限（默认 3）")
    int maxCases;

    @Option(names = {"--dry-run"}, description = "只打印选例与成本预估，不调用 LLM")
    boolean dryRun;

    @Override
    public Integer call() {
        String newPrompt = readTextFile(promptPath);
        if (newPrompt == null) {
            System.err.println("无法读取 Prompt 文件：" + promptPath);
            return 2;
        }
        String oldPromptHash = null;
        if (oldPromptPath != null) {
            String oldPrompt = readTextFile(oldPromptPath);
            if (oldPrompt == null) {
                System.err.println("无法读取旧 Prompt 文件：" + oldPromptPath);
                return 2;
            }
            oldPromptHash = HashUtil.sha256(oldPrompt);
        }

        AgentAssert4jConfig config = ConfigLoader.loadAgentAssert4jConfig();
        StorageRepository repository = null;
        try {
            repository = CliSupport.openRepository(db);

            ComparatorConfig comparatorConfig = ComparatorConfig.defaults();
            comparatorConfig.setIgnorableFields(new HashSet<>(config.getRegression().getIgnorableFields()));
            DeterministicComparator comparator = new DeterministicComparator(comparatorConfig);

            if (TextUtil.isBlank(config.getLlm().getApiKey())) {
                System.out.println("警告：未配置 API Key（agentassert4j.json 的 llm.apiKey 或其 ${ENV} 引用），LLM 调用将失败。");
            }
            LlmClient client = new OpenAiCompatibleClient(config.getLlm().getEndpoint(), config.getLlm().getApiKey(), config.getLlm().getModel(), OpenAiCompatibleClient.DEFAULT_MAX_RETRIES, config.getLlm().getExtraBody());

            TestExecutionConfig executionConfig = new TestExecutionConfig().timeoutMs(config.getLlm().getTimeoutMs()).temperature(config.getLlm().getTemperature());
            SkillRulesConfig rules = ConfigLoader.loadRulesConfig();

            return new ReplayRunner(repository, client, comparator, rules, executionConfig, System.out).run(newPrompt, skill, maxCases, oldPromptHash, dryRun);
        } catch (RuntimeException e) {
            System.err.println("replay 失败：" + e.getMessage());
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
