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
        new BaselineService(repository).establishMissing(out);

        List<InteractionRecord> cases;
        if (oldPromptHash != null) {
            AnalysisResult analysis = new ImpactAnalyzer(repository, CliSupport.loadGraphOrDefault(repository)).analyzeChange(oldPromptHash, HashUtil.sha256(newSystemPrompt));
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
}
