package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.config.SkillRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.*;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 重放执行流程 — 选例、成本预估、逐例重放、汇总报告与退出码。
 *
 * <p>退出码即 CI gating 契约：0 = 全部 PASS；1 = 存在非 PASS（含 DIFF、
 * REGRESSION、超时与错误——证据不完整同样不允许绿）；2 = 用法/数据问题
 * （无匹配用例、冷启动等，报告给指导信息）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-27
 */
public class ReplayRunner {

    private final StorageRepository repository;
    private final LlmClient llmClient;
    private final DeterministicComparator comparator;
    private final SkillRulesConfig rules;
    private final TestExecutionConfig executionConfig;
    private final PrintStream out;

    public ReplayRunner(StorageRepository repository, LlmClient llmClient, DeterministicComparator comparator, SkillRulesConfig rules, TestExecutionConfig executionConfig, PrintStream out) {
        this.repository = repository;
        this.llmClient = llmClient;
        this.comparator = comparator;
        this.rules = rules;
        this.executionConfig = executionConfig;
        this.out = out;
    }

    /**
     * 执行重放。
     *
     * @param newSystemPrompt  新 System Prompt 全文
     * @param skillFilter      仅重放该 skillId（null = 全部已录制 skill）
     * @param maxCasesPerSkill 默认选例模式下每 skill 的用例上限
     * @param oldPromptHash    变更前 Prompt 的 hash（null = 默认全量选例模式）
     * @param dryRun           只打印选例与成本预估，不调 LLM
     * @return 进程退出码
     */
    public int run(String newSystemPrompt, String skillFilter, int maxCasesPerSkill, String oldPromptHash, boolean dryRun) {
        // 超时下限钳位：0 在 HttpURLConnection 语义里是无限等待（CI 挂死），
        // 负数会被当可重试错误重试后洗白成笼统失败
        executionConfig.validate();

        warnIfModelDiffers();

        new BaselineService(repository).establishMissing(out);

        // 图是派生数据：每次重放现场重建并刷新快照（CLI 单进程单写者，
        // last-writer-wins 语义安全）；分析直接用内存图，快照供 status 巡检留档
        InMemoryDependencyGraph graph = CliSupport.rebuildGraph(repository);
        saveGraphQuietly(graph);
        out.println("依赖图：" + graph.nodeCount() + " 节点 / " + graph.edgeCount() + " 边" + (graph.edgeCount() == 0 ? "（无多轮会话数据时图为空，仅直接影响裁剪）" : ""));

        List<InteractionRecord> cases;
        if (oldPromptHash != null) {
            AnalysisResult analysis = new ImpactAnalyzer(repository, graph).analyzeChange(oldPromptHash, HashUtil.sha256(newSystemPrompt));
            if (!analysis.isHasBaseline() || analysis.isError()) {
                out.println(analysis.getMessage());
                return 2;
            }
            cases = filterBySkill(analysis.getTestCases(), skillFilter);
        } else {
            cases = selectTopPerSkill(skillFilter, maxCasesPerSkill);
        }

        if (cases.isEmpty()) {
            out.println(skillFilter != null ? "未找到 skill " + skillFilter + " 的可重放用例（先录制交互或核对 skillId）。" : "未找到可重放用例（先录制交互数据）。");
            return 2;
        }

        out.println(CostEstimator.estimate(cases, llmClient.name()));

        if (dryRun) {
            for (InteractionRecord c : cases) {
                out.println("  [" + displayId(c) + "] " + c.getRecordId() + "（turn " + c.getTurnIndex() + "）");
            }
            out.println("dry-run：共 " + cases.size() + " 条用例，未调用 LLM。");
            return 0;
        }

        BaselineManager baselineManager = new BaselineManager(repository);
        RegressionTestExecutor executor = new RegressionTestExecutor(llmClient, comparator, baselineManager, rules);

        int pass = 0;
        int diff = 0;
        int regression = 0;
        int failed = 0;
        for (InteractionRecord testCase : cases) {
            RegressionTestResult result = executor.execute(testCase, newSystemPrompt, executionConfig);
            out.println("  [" + displayId(testCase) + "] " + testCase.getRecordId() + "  " + describe(result));

            ComparisonResult comparison = result.getComparison();
            if (comparison != null && result.getStatus() == TestResultStatus.SUCCESS) {
                if (comparison.getVerdict() == Verdict.PASS) {
                    pass++;
                } else if (comparison.getVerdict() == Verdict.DIFF) {
                    diff++;
                } else {
                    regression++;
                }
            } else {
                failed++;
            }
        }

        out.println("汇总: PASS " + pass + " | DIFF " + diff + " | REGRESSION " + regression + " | 失败 " + failed + "（共 " + cases.size() + "）");
        if (diff + regression > 0) {
            List<String> pending = pendingGroupKeys();
            out.println("待裁决: " + String.join(", ", pending));
            out.println("用 `agentassert4j approve --skill <groupKey 前缀>` 接受，或 `agentassert4j reject --skill <groupKey 前缀>` 拒绝。");
        }
        return diff + regression + failed == 0 ? 0 : 1;
    }

    /**
     * 当前存在候选指纹的 groupKey 列表（裁决提示用）。
     */
    private List<String> pendingGroupKeys() {
        List<String> pending = new ArrayList<>();
        for (SkillProfile profile : repository.findAllSkills()) {
            if (profile.getCandidateFingerprint() != null) {
                pending.add(profile.getGroupKey());
            }
        }
        return pending;
    }

    private List<InteractionRecord> selectTopPerSkill(String skillFilter, int maxCasesPerSkill) {
        List<InteractionRecord> selected = new ArrayList<>();
        for (String skillId : CliSupport.recordedSkillIds(repository)) {
            if (skillFilter != null && !skillFilter.equals(skillId)) {
                continue;
            }
            List<InteractionRecord> records = repository.findBySkillId(skillId);
            int limit = Math.min(maxCasesPerSkill, records.size());
            selected.addAll(records.subList(0, limit));
        }
        return selected;
    }

    private List<InteractionRecord> filterBySkill(List<InteractionRecord> cases, String skillFilter) {
        if (skillFilter == null) {
            return cases;
        }
        List<InteractionRecord> filtered = new ArrayList<>();
        for (InteractionRecord c : cases) {
            if (skillFilter.equals(c.getSkillId())) {
                filtered.add(c);
            }
        }
        return filtered;
    }

    /**
     * 展示标识：优先分组键（与 status/approve 一致），无分组键时退回业务标签。
     */
    private static String displayId(InteractionRecord record) {
        if (record.getGroupKey() != null && !record.getGroupKey().isEmpty()) {
            return record.getGroupKey();
        }
        return String.valueOf(record.getSkillId());
    }

    private String describe(RegressionTestResult result) {
        ComparisonResult comparison = result.getComparison();
        if (comparison != null) {
            return String.format("%s  score=%.2f  %s", comparison.getVerdict(), comparison.getScore(), comparison.getSummary());
        }
        return result.getStatus() + "  " + (result.getErrorMessage() != null ? result.getErrorMessage() : "");
    }

    /**
     * 基线与重放配置的模型身份不一致时告警——换模型重放的判定结果不可与
     * 原基线直接比较（伪回归/伪通过都可能出现），决策留给使用者。
     */
    private void warnIfModelDiffers() {
        String configModel = executionConfig.getModel();
        if (configModel == null || configModel.isEmpty()) {
            return;
        }
        Set<String> baselineModels = new TreeSet<>();
        try {
            for (String sessionId : repository.findAllSessionIds()) {
                for (InteractionRecord record : repository.findBySessionId(sessionId)) {
                    if (record.getModel() != null && !record.getModel().isEmpty()) {
                        baselineModels.add(record.getModel());
                    }
                }
            }
        } catch (RuntimeException e) {
            return; // 告警路径不得阻断重放
        }
        if (!baselineModels.isEmpty() && !baselineModels.contains(configModel)) {
            out.println("警告：重放模型 " + configModel + " 与录制模型 " + baselineModels + " 不一致，行为判定结果不与基线直接可比（换模型属实验性操作）。");
        }
    }

    /**
     * 快照是分析视图留档（供 status 巡检），写失败只告警不阻断——
     * 影响分析用的是已重建的内存图，快照缺席不改变本次判定。
     */
    private void saveGraphQuietly(InMemoryDependencyGraph graph) {
        try {
            repository.saveGraph(graph.toJson());
        } catch (RuntimeException e) {
            out.println("警告：依赖图快照写入失败（不影响本次分析）：" + e.getMessage());
        }
    }
}
