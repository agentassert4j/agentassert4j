package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.StatisticalRegressionExecutor;
import io.github.agentassert4j.config.ConfigLoader;
import io.github.agentassert4j.config.ScenarioConfig;
import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;
import java.util.function.Function;

/**
 * 场景执行编排 — 把一条场景声明落成「基线 → N 轮真实调用 → 断言 → 聚合判定 → 落库」。
 *
 * <p>编排原则：断言不另建检查通道——场景断言集就是规则词表（SkillRule），与
 * agentassert4j-rules.json 的站内规则合并后经引擎的规则注入进入逐轮判定，断言失败
 * 自然表现为该轮 CHANGED；verdict 映射同样内部完成（统计聚合 ≠ STABLE → DRIFTED；
 * 判定样本为零 → INSUFFICIENT），调用方只见这两个词。</p>
 *
 * <p>场景来源两路：配置文件声明的场景，或无配置时的自动派生——每个已建档分组
 * 派生一个场景（输入 = 该组最新记录的用户输入，重放其原始模板），用于量化模型
 * 自身的非确定性（噪声基线）。自动派生前先自动建档（与重放默认路径一致）。</p>
 *
 * <p>无法兑现自身声明的场景不执行：绑定无匹配分组、模板原文缺失、断言含未知
 * 行为名的场景记入跳过清单——跳过是证据缺口，不是空结果，消费方不得当绿处理。</p>
 *
 * @author axy-yxa
 * @since 2026-08-29
 */
public class ScenarioRunner {

    private final StorageRepository repository;
    private final LlmClient llmClient;
    private final DeterministicComparator comparator;
    private final PrintStream out;

    public ScenarioRunner(StorageRepository repository, LlmClient llmClient, DeterministicComparator comparator, PrintStream out) {
        this.repository = repository;
        this.llmClient = llmClient;
        this.comparator = comparator;
        this.out = out;
    }

    /**
     * 执行场景集：声明场景逐个执行；配置为空时自动派生。
     *
     * @param config 场景配置（empty = 自动派生）
     * @param dryRun 只列出将执行的场景与预计调用量，不发起真实调用
     * @return 本次执行的产出（执行事实 + 计划 + 跳过清单）
     */
    public Outcome run(ScenarioConfig config, boolean dryRun) {
        // 断言注入与建档共用站内规则口径（与重放同源）：建档也带规则，
        // 指纹的维度 3-4 才不会因「哪个命令先建档」而不同
        SkillRulesConfig baseRules = ConfigLoader.loadRulesConfig();
        CliSupport.warnUnknownBehaviors(baseRules, out);

        // 自动建档与重放默认路径一致：场景对比需要基线先行存在
        int established = new BaselineService(repository).establishMissing(out, CliSupport.currentActor(), false, null, baseRules);
        if (established > 0) {
            out.println("自动建档 " + established + " 个分组。");
        }

        List<Skip> skipped = new ArrayList<>();
        List<PlannedScenario> planned = config.getScenarios().isEmpty() ? deriveFromGroups(skipped) : planFromConfig(config, skipped);

        for (Skip skip : skipped) {
            out.println("警告：场景 " + skip.scenarioId + " 已跳过——" + skip.reason);
        }

        if (planned.isEmpty()) {
            out.println(skipped.isEmpty() ? "没有可执行的场景：未找到已建档的分组，且场景配置为空。先录制交互数据。" : "没有可执行的场景：全部 " + skipped.size() + " 个场景已被跳过（原因见上）。");
            return new Outcome(new ArrayList<ScenarioRun>(), planned, skipped);
        }

        if (dryRun) {
            int estimatedCalls = 0;
            for (PlannedScenario plannedScenario : planned) {
                out.println("  [" + plannedScenario.scenarioId + "] " + plannedScenario.description + " × " + plannedScenario.sampleCount + " 轮");
                estimatedCalls += plannedScenario.sampleCount;
            }
            out.println("共 " + planned.size() + " 个场景，预计 " + estimatedCalls + " 次 API 调用（dry-run 未发起）。");
            return new Outcome(new ArrayList<ScenarioRun>(), planned, skipped);
        }

        List<ScenarioRun> runs = new ArrayList<>();
        for (PlannedScenario plannedScenario : planned) {
            ScenarioRun run = executeScenario(plannedScenario, baseRules);
            if (run != null) {
                runs.add(run);
                out.println("  [" + plannedScenario.scenarioId + "] " + run.getVerdict() + "  通过 " + run.getPassCount() + "/" + run.getSampleCount() + "  tokens " + run.getInputTokens() + "/" + run.getOutputTokens());
            }
        }
        return new Outcome(runs, planned, skipped);
    }

    /**
     * 一次 run 的产出 — 执行事实、计划与被跳过的场景。
     *
     * <p>跳过是证据缺口而非空结果：命令层据此把退出码压到非绿，
     * 证据报告也逐条披露跳过原因。</p>
     */
    public static final class Outcome {

        private final List<ScenarioRun> runs;
        private final List<PlannedScenario> planned;
        private final List<Skip> skipped;

        Outcome(List<ScenarioRun> runs, List<PlannedScenario> planned, List<Skip> skipped) {
            this.runs = Collections.unmodifiableList(runs);
            this.planned = Collections.unmodifiableList(planned);
            this.skipped = Collections.unmodifiableList(skipped);
        }

        /**
         * 已执行场景的聚合事实（dry-run 为空）。
         */
        public List<ScenarioRun> getRuns() {
            return runs;
        }

        /**
         * 计划执行的场景（dry-run 即全部计划；真实执行为已执行集合）。
         */
        public List<PlannedScenario> getPlanned() {
            return planned;
        }

        /**
         * 规划阶段被跳过的场景及原因。
         */
        public List<Skip> getSkipped() {
            return skipped;
        }
    }

    /**
     * 被跳过的场景 — 场景标识 + 人读原因，进入证据报告。
     */
    public static final class Skip {

        final String scenarioId;
        final String reason;

        Skip(String scenarioId, String reason) {
            this.scenarioId = scenarioId;
            this.reason = reason;
        }
    }

    /**
     * JSON 模式的静默输出流——进度信息丢弃，stdout 只留证据报告。
     */
    static PrintStream discardStream() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    static final class PlannedScenario {
        String scenarioId;
        String description;
        String userInput;
        String systemPrompt;
        InteractionRecord baseline;
        ScenarioConfig.Scenario source;
        int sampleCount;

        private StatisticalTestConfig testConfig() {
            StatisticalTestConfig config = new StatisticalTestConfig();
            if (source == null) {
                // 自动派生场景：默认 5 轮——一轮不足以观测概率行为
                config.setSampleCount(ScenarioConfig.DEFAULT_SAMPLE_COUNT);
            } else {
                config.setSampleCount(source.getSampleCount());
                config.setConcurrency(source.getConcurrency());
                config.setMaxTotalCalls(source.getMaxTotalCalls());
                config.setMaxTotalTokens(source.getMaxTotalTokens());
                config.setPassThreshold(source.getPassThreshold());
                config.setRegressionTolerance(source.getRegressionTolerance());
            }
            return config;
        }
    }

    private List<PlannedScenario> planFromConfig(ScenarioConfig config, List<Skip> skipped) {
        List<PlannedScenario> planned = new ArrayList<>();
        Map<String, List<InteractionRecord>> buckets = CliSupport.groupBuckets(repository);
        for (ScenarioConfig.Scenario scenario : config.getScenarios()) {
            List<InteractionRecord> bucket = resolveBucket(buckets, scenario);
            if (bucket == null) {
                skipped.add(new Skip(scenario.getScenarioId(), "绑定（skillId=" + scenario.getSkillId() + "）没有匹配的已录制分组"));
                continue;
            }
            InteractionRecord baselineRecord = latestRecord(bucket);
            String systemPrompt = repository.findTemplateText(baselineRecord.getTemplateHash());
            if (systemPrompt == null || systemPrompt.isEmpty()) {
                skipped.add(new Skip(scenario.getScenarioId(), "绑定分组缺少模板原文存档，无法重构调用"));
                continue;
            }
            Set<String> unknown = CliSupport.unknownBehaviors(scenario.getAssertions());
            if (!unknown.isEmpty()) {
                skipped.add(new Skip(scenario.getScenarioId(), "断言声明了未知行为 " + String.join(", ", unknown) + "（带缺口跑会出假绿，场景整体跳过）"));
                continue;
            }
            // 场景是新输入的首次调用：声明了输入但绑定分组收尾为工具结果轮
            // （记录无用户输入位）时输入无法注入，无法兑现声明的场景不执行
            if (!scenario.getUserInput().isEmpty() && baselineRecord.getUserInput() == null) {
                skipped.add(new Skip(scenario.getScenarioId(), "绑定分组收尾为工具结果轮（无用户输入位），场景输入无法注入"));
                continue;
            }
            PlannedScenario plannedScenario = new PlannedScenario();
            plannedScenario.scenarioId = scenario.getScenarioId();
            plannedScenario.description = scenario.getName();
            plannedScenario.userInput = scenario.getUserInput().isEmpty() ? null : substituteVariables(scenario.getUserInput(), scenario.getVariables());
            plannedScenario.systemPrompt = systemPrompt;
            plannedScenario.baseline = baselineRecord;
            plannedScenario.source = scenario;
            plannedScenario.sampleCount = scenario.getSampleCount();
            planned.add(plannedScenario);
        }
        return planned;
    }

    /**
     * 自动派生：每个已建档分组一个场景——输入取该组最新记录的用户输入，
     * 模板原文从模板文本库反查，用于量化模型自身的非确定性（噪声基线）。
     */
    private List<PlannedScenario> deriveFromGroups(List<Skip> skipped) {
        List<PlannedScenario> planned = new ArrayList<>();
        for (Map.Entry<String, List<InteractionRecord>> bucket : CliSupport.groupBuckets(repository).entrySet()) {
            InteractionRecord latest = latestRecord(bucket.getValue());
            String systemPrompt = repository.findTemplateText(latest.getTemplateHash());
            if (systemPrompt == null || systemPrompt.isEmpty()) {
                skipped.add(new Skip("auto:" + bucket.getKey(), "分组缺少模板原文存档，无法重构调用"));
                continue;
            }
            PlannedScenario plannedScenario = new PlannedScenario();
            plannedScenario.scenarioId = "auto:" + bucket.getKey();
            plannedScenario.description = "自动派生噪声基线";
            plannedScenario.userInput = latest.getUserInput();
            plannedScenario.systemPrompt = systemPrompt;
            plannedScenario.baseline = latest;
            plannedScenario.sampleCount = ScenarioConfig.DEFAULT_SAMPLE_COUNT;
            planned.add(plannedScenario);
        }
        return planned;
    }

    /**
     * 绑定解析：业务标签精确匹配桶内记录；模板 hash 绑定匹配桶内记录的模板。
     */
    private List<InteractionRecord> resolveBucket(Map<String, List<InteractionRecord>> buckets, ScenarioConfig.Scenario scenario) {
        for (List<InteractionRecord> records : buckets.values()) {
            for (InteractionRecord record : records) {
                boolean labelMatch = !scenario.getSkillId().isEmpty() && scenario.getSkillId().equals(record.getSkillId());
                boolean templateMatch = !scenario.getTemplateHash().isEmpty() && scenario.getTemplateHash().equals(record.getTemplateHash());
                if (labelMatch || templateMatch) {
                    return records;
                }
            }
        }
        return null;
    }

    private static InteractionRecord latestRecord(List<InteractionRecord> records) {
        return records.get(records.size() - 1);
    }

    /**
     * 结构化变量替换：userInput 中的 {{键}} 占位符以变量值填充，未声明变量保持原样。
     */
    private static String substituteVariables(String userInput, Map<String, String> variables) {
        if (userInput == null) {
            return null;
        }
        String result = userInput;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return result;
    }

    /**
     * 执行单个场景并落库：断言合并进站内规则注入引擎逐轮判定，
     * 统计聚合映射为 STABLE/DRIFTED/INSUFFICIENT 后追加执行事实。
     *
     * <p>规则注入键 = 基线记录的业务标签，未声明分组统一空键（与引擎查找键
     * 归一口径一致）——templateHash 绑定的未声明分组同样能吃到场景断言。</p>
     */
    private ScenarioRun executeScenario(PlannedScenario planned, SkillRulesConfig baseRules) {
        String identityKey = planned.baseline.getSkillId() != null ? planned.baseline.getSkillId() : "";
        SkillRulesConfig rules = planned.source != null ? baseRules.merging(identityKey, planned.source.getAssertions()) : baseRules;
        StatisticalRegressionExecutor executor = new StatisticalRegressionExecutor(llmClient, comparator, rules);

        long startedAt = System.currentTimeMillis();
        StatisticalRegressionResult result = executor.execute(planned.baseline, planned.systemPrompt, planned.userInput, planned.testConfig());

        ScenarioRun run = new ScenarioRun();
        run.setRunId(UUID.randomUUID().toString());
        run.setScenarioId(planned.scenarioId);
        run.setStartedAt(startedAt);
        run.setVerdict(mapVerdict(result));
        run.setSampleCount(result.getActualSampleCount());
        run.setPassCount(result.getVerdictCounts().getOrDefault(Verdict.PASS, 0));
        run.setFailCount(result.getVerdictCounts().getOrDefault(Verdict.CHANGED, 0));
        run.setInputTokens(result.getSamples().isEmpty() ? 0 : aggregateInputTokens(result));
        run.setOutputTokens(aggregateOutputTokens(result));
        run.setCacheReadTokens(aggregateNullableToken(result.getSamples(), SampleResult::getCacheReadTokens));
        run.setCacheWriteTokens(aggregateNullableToken(result.getSamples(), SampleResult::getCacheWriteTokens));
        run.setReasoningTokens(aggregateNullableToken(result.getSamples(), SampleResult::getReasoningTokens));
        run.setLatencyMs(result.getTotalLatencyMs());
        run.setCostUsd(result.getEstimatedCost() > 0 ? result.getEstimatedCost() : null);
        repository.saveScenarioRun(run);
        return run;
    }

    private static String mapVerdict(StatisticalRegressionResult result) {
        switch (result.getStatisticalVerdict()) {
            case STABLE:
                return "STABLE";
            case UNSTABLE:
            case FLAKY:
                return "DRIFTED";
            default:
                return "INSUFFICIENT";
        }
    }

    private static int aggregateInputTokens(StatisticalRegressionResult result) {
        int total = 0;
        for (SampleResult sample : result.getSamples()) {
            total += sample.getInputTokens() != null ? sample.getInputTokens() : 0;
        }
        return total;
    }

    private static int aggregateOutputTokens(StatisticalRegressionResult result) {
        int total = 0;
        for (SampleResult sample : result.getSamples()) {
            total += sample.getOutputTokens() != null ? sample.getOutputTokens() : 0;
        }
        return total;
    }

    /**
     * 可空遥测聚合：全部样本缺失返回 null（未知 ≠ 0），任一样本有值则取非空和。
     */
    private static Integer aggregateNullableToken(List<SampleResult> samples, Function<SampleResult, Integer> extractor) {
        Integer total = null;
        for (SampleResult sample : samples) {
            Integer value = extractor.apply(sample);
            if (value != null) {
                total = (total != null ? total : 0) + value;
            }
        }
        return total;
    }

}
