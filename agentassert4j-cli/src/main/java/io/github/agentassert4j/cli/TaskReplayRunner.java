package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.RegressionTestResult;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.result.*;
import io.github.agentassert4j.result.TaskAlignment.StepKind;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextDiffUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * 统一重放引擎 — bare 命令即全项目完整默认能力，参数只做缩域。
 *
 * <p>三层判定模型（前两层零 LLM 调用，构成缺省路径）：</p>
 * <ul>
 *   <li><b>身份检测</b>：DriftDetector 全库只读巡检画像模板身份 vs 最新记录，
 *       漂移键经依赖图扩散为下游波及集，检测报告全项目、不随缩域收窄；</li>
 *   <li><b>真实对齐</b>：逐任务（同名请求链）最新 vs 次新按调用点对齐，步级产出
 *       PASS/CHANGED/缺步骤/新增步骤与任务纪律违规；</li>
 *   <li><b>受控重驱</b>：花 LLM 钱的显式复核层（--re-drive 开启，逐漂移点注入
 *       最新归档模板）。</li>
 * </ul>
 *
 * <p>漂移处置状态机把每个漂移点收敛到三个出口之一：对齐 PASS → 开发态自动收编
 * （{@code --ci} 模式不落治理写、附警告）；CHANGED → 落候选等待人工裁决；
 * 证据缺口（缺步骤/新增/规则违规/无可对齐证据）→ 挂起。缩域命中的键才处置，
 * 域外漂移只进检测报告。</p>
 *
 * <p>引擎入口继承六项守卫：判定语义版本守卫、{@code --ci} 未建档拒绝判定、
 * 换模型告警（含配置缺省时比对客户端实际生效模型）、依赖图重建与快照落盘、
 * 全败按基础设施故障退出（重驱层）、served 模型不一致就地标注。</p>
 *
 * <p>退出码契约：1 = 行为差异或证据缺口（没跑够）；2 = 用法/数据/预算/环境问题
 * （被截断）；0 = 无回归。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public class TaskReplayRunner {

    private static final int TEXT_DIFF_BUDGET = 300;

    private final StorageRepository repository;
    private final LlmClient llmClient;
    private final DeterministicComparator comparator;
    private final InvocationRulesConfig rules;
    private final TestExecutionConfig executionConfig;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean jsonMode;

    public TaskReplayRunner(StorageRepository repository, LlmClient llmClient, DeterministicComparator comparator, InvocationRulesConfig rules, TestExecutionConfig executionConfig, PrintStream out, PrintStream err, boolean jsonMode) {
        this.repository = repository;
        this.llmClient = llmClient;
        this.comparator = comparator;
        this.rules = rules;
        this.executionConfig = executionConfig;
        this.out = out;
        this.err = err;
        this.jsonMode = jsonMode;
    }

    private void info(String line) {
        if (!jsonMode) {
            out.println(line);
        }
    }

    private void diagnostic(String line) {
        if (jsonMode) {
            err.println(line);
        } else {
            out.println(line);
        }
    }

    private static PrintStream discardStream() {
        return new PrintStream(new ByteArrayOutputStream(), true);
    }

    /**
     * 执行统一重放。
     *
     * @param taskPrefix     任务文本前缀选择器（--task，与 --invocation 可复合 AND；null = 全部任务）
     * @param invocationKey  已解析的调用点键（--invocation，命中含该键记录的任务链；null = 不缩域）
     * @param ciMode         CI 模式：不自动建档，缩域内存在未建档调用点拒绝判定，漂移 PASS 不收编
     * @param dryRun         只读预演：漂移集 + 对齐计划 + 重驱成本预估，不建档、不落图快照、不处置
     * @param reDrive        受控重驱（第三层）：逐漂移点以最新归档模板真重驱录制输入，花 LLM 调用
     * @param fullChain      重驱扩域：取消「仅漂移点」裁剪，缩域内全部记录逐条重驱
     * @param maxTotalCalls  重驱预算池：本次运行真重驱调用次数上限（null = 不限）
     * @param maxTotalTokens 重驱预算池：本次运行真重驱 token 合计上限（null = 不限）
     * @return 进程退出码（0/1/2）
     */
    public int run(String taskPrefix, String invocationKey, boolean ciMode, boolean dryRun, boolean reDrive, boolean fullChain, Integer maxTotalCalls, Integer maxTotalTokens) {
        executionConfig.validate();

        List<TaskChain> chains = CliSupport.taskChains(repository);
        if (chains.isEmpty()) {
            diagnostic("No recorded interactions found. Run your agent first to build up recordings.");
            return 2;
        }

        warnIfModelDiffers();

        // 图是派生数据：每次重放现场重建；快照留档供 status 巡检（dry-run 只读不落盘）。
        // 任务域对齐本身零图引用，图服务漂移检测的下游扩散与快照新鲜度
        InMemoryDependencyGraph graph = CliSupport.rebuildGraph(repository);
        if (!dryRun) {
            saveGraphQuietly(graph);
        }
        info("Dependency graph: " + graph.nodeCount() + " nodes / " + graph.edgeCount() + " edges" + (graph.edgeCount() == 0 ? " (empty without multi-turn session data; no downstream propagation)" : ""));

        // 第 1 层 身份检测（全项目，零调用）
        DriftReport drift = DriftDetector.detect(repository, graph);
        printDriftReport(drift);
        if (jsonMode) {
            out.println(driftJson(drift));
        }

        // 缩域：--task × --invocation 复合 AND（检测报告不受缩域影响）
        List<TaskChain> scoped = selectChains(chains, taskPrefix, invocationKey);
        if (scoped == null) {
            return 2;
        }
        if (scoped.isEmpty()) {
            diagnostic("No task chains matched the scope. Record interactions first, or check invocation keys and task prefixes with `status`.");
            return 2;
        }
        boolean narrowed = taskPrefix != null || invocationKey != null;

        if (dryRun) {
            return dryRunPlan(scoped, reDrive, drift, narrowed);
        }

        // --ci 未建档守卫：缩域内存在未建档调用点即拒绝判定——
        // 防止「新调用点自建基线再自比」产出无人审的绿灯
        if (ciMode) {
            Set<String> unbaselined = unbaselinedKeysInScope(scoped);
            if (!unbaselined.isEmpty()) {
                diagnostic("Refusing to judge in --ci mode: the following invocations have no baseline:");
                for (String key : unbaselined) {
                    diagnostic("  " + key);
                }
                diagnostic("Run `agentassert4j baseline` locally to review and establish baselines, then retry; or drop --ci to auto-establish.");
                return 2;
            }
        } else {
            // 自动建档（开发态自动化，报告可见）：裂键新档与全新键在此收编
            new BaselineService(repository).establishMissing(jsonMode ? discardStream() : out, CliSupport.currentActor(), false, null, rules);
        }

        // 判定语义守卫：任何画像由其他版本（含未标记历史行）批准即拒绝判定——
        // 带着不匹配的标尺出结论就是对历史基线的静默重解释
        String semanticProblem = checkJudgmentSemantics();
        if (semanticProblem != null) {
            diagnostic(semanticProblem);
            return 2;
        }
        warnUngroupableRecords(scoped);

        // 第 2 层 真实对齐（零调用，逐任务最新链 vs 次新链）
        BaselineManager manager = new BaselineManager(repository);
        Map<String, StepOutcome> outcomes = new LinkedHashMap<>();
        AlignmentTotals totals = new AlignmentTotals();

        List<List<TaskChain>> groups = groupByRequestText(scoped);
        for (List<TaskChain> group : groups) {
            if (group.size() == 1) {
                printSelfEstablished(group.get(0));
                continue;
            }
            alignTaskGroup(group.get(group.size() - 2), group.get(group.size() - 1), outcomes, totals, manager);
        }

        // 漂移处置状态机：每个缩域内的漂移点收敛到 收编/候选/挂起 之一
        Set<String> scopedKeys = new HashSet<>();
        for (TaskChain chain : scoped) {
            for (InteractionRecord record : chain.getRecords()) {
                String key = CliSupport.invocationKeyOfRecord(record);
                if (key != null) {
                    scopedKeys.add(key);
                }
            }
        }
        DispositionTotals dispositions = disposeDrifts(drift, scopedKeys, ciMode, outcomes, manager);

        // 第 3 层 受控重驱（显式开启）：逐点以最新归档模板重驱录制输入
        ReDriveTotals reDriveTotals = new ReDriveTotals();
        if (reDrive) {
            reDriveLayer(drift, fullChain, narrowed, scoped, manager, maxTotalCalls, maxTotalTokens, reDriveTotals);
        }

        if (!jsonMode && totals.pendingCandidates > 0) {
            info("Pending adjudication: " + String.join(", ", pendingInvocationKeys()));
            info("Accept with `agentassert4j approve --invocation <prefix>`, or reject with `agentassert4j reject --invocation <prefix>`.");
        }

        // 退出码复合：行为差异或证据缺口（没跑够）→ 1；环境/预算截断 → 2；否则 0
        boolean anyGap = totals.missing > 0 || totals.added > 0 || totals.ruleViolations > 0 || dispositions.hung > 0;
        if (totals.changed > 0 || totals.anyTaskChanged || anyGap || reDriveTotals.changed > 0) {
            return 1;
        }
        if (reDriveTotals.failed > 0 && reDriveTotals.pass == 0) {
            diagnostic("All re-drive calls failed (no comparisons). Check llm config, credentials and network, then retry.");
            return 2;
        }
        if (reDriveTotals.skipped > 0) {
            return 2;
        }
        return 0;
    }

    /**
     * 重驱层的聚合计数（退出码与汇总行）。
     */
    private static final class ReDriveTotals {
        int pass;
        int changed;
        int failed;
        int skipped;
        int callsUsed;
        long tokensUsed;
    }

    /**
     * 受控重驱目标集，三档优先级：--full-chain 为缩域内全部记录逐条重驱；带缩域
     * （--task/--invocation）为缩域内全部调用点每键取最新可分组记录——显式缩域即
     * 显式重驱域，不要求漂移在册；缺省为仅漂移点（同键漂移 + 标签裂键，含挂起点
     * 补证）。漂移键不在缩域链键集内、天然排除。
     */
    private List<InteractionRecord> reDriveTargets(DriftReport drift, boolean fullChain, boolean narrowed, List<TaskChain> scoped) {
        List<InteractionRecord> targets = new ArrayList<>();
        if (fullChain) {
            for (TaskChain chain : scoped) {
                for (InteractionRecord record : chain.getRecords()) {
                    if (CliSupport.invocationKeyOfRecord(record) != null) {
                        targets.add(record);
                    }
                }
            }
            return targets;
        }
        if (narrowed) {
            Set<String> seen = new LinkedHashSet<>();
            for (TaskChain chain : scoped) {
                for (InteractionRecord record : chain.getRecords()) {
                    String key = CliSupport.invocationKeyOfRecord(record);
                    if (key != null && seen.add(key)) {
                        targets.add(record);
                    }
                }
            }
            return targets;
        }
        Set<String> scopedKeys = new HashSet<>();
        for (TaskChain chain : scoped) {
            for (InteractionRecord record : chain.getRecords()) {
                String key = CliSupport.invocationKeyOfRecord(record);
                if (key != null) {
                    scopedKeys.add(key);
                }
            }
        }
        LinkedHashSet<String> driftKeys = new LinkedHashSet<>();
        for (DriftReport.DriftPoint point : drift.getSameKeyDrifts()) {
            driftKeys.add(point.getInvocationKey());
        }
        for (DriftReport.DriftPoint point : drift.getLabelSplits()) {
            driftKeys.add(point.getInvocationKey());
        }
        for (String key : driftKeys) {
            if (!scopedKeys.contains(key)) {
                continue;
            }
            InteractionRecord record = DriftDetector.latestIdentityRecord(repository.findByInvocationKey(key), key);
            if (record != null) {
                targets.add(record);
            }
        }
        return targets;
    }

    /**
     * 第三层受控重驱：逐点取其最新归档模板全文（prompt_texts 反查）注入既有步级
     * 重放执行器——检测报告已确认漂移点真实运行过，归档原文必然可反查；原文缺席
     * 属数据缺口，跳过计数可见。预算池对全部真重驱合计封顶。
     */
    private void reDriveLayer(DriftReport drift, boolean fullChain, boolean narrowed, List<TaskChain> scoped, BaselineManager manager, Integer maxTotalCalls, Integer maxTotalTokens, ReDriveTotals rd) {
        List<InteractionRecord> targets = reDriveTargets(drift, fullChain, narrowed, scoped);
        info("Re-drive: " + CliSupport.plural(targets.size(), "record") + " using each point\'s latest archived template" + (fullChain ? " (--full-chain)" : narrowed ? " (all invocations in scope)" : " (drift points only)") + ".");
        if (!targets.isEmpty()) {
            info(CostEstimator.estimate(targets, llmClient.name()));
        }
        RegressionTestExecutor executor = new RegressionTestExecutor(llmClient, comparator, manager, rules);
        List<String> stepJsons = jsonMode ? new ArrayList<>() : null;
        int index = 0;
        for (InteractionRecord record : targets) {
            index++;
            String key = CliSupport.invocationKeyOfRecord(record);
            if (budgetExhausted(maxTotalCalls, maxTotalTokens, rd.callsUsed, rd.tokensUsed)) {
                rd.skipped++;
                info(stepLine(index, key, "re-drive skipped (budget exhausted)"));
                if (stepJsons != null) {
                    stepJsons.add("{\"recordId\":\"" + RecursiveJsonParser.escape(record.getRecordId()) + "\",\"invocationKey\":\"" + RecursiveJsonParser.escape(key != null ? key : "") + "\",\"action\":\"skipped\"}");
                }
                continue;
            }
            // 模板取该点最新归档全文（而非记录自身哈希）——受控重驱的语义是
            // 「用各点自己的新模板对录制输入复核」；目标记录即最新记录时两者同值
            String templateText = null;
            InteractionRecord anchor = key != null ? DriftDetector.latestIdentityRecord(repository.findByInvocationKey(key), key) : null;
            String templateHash = anchor != null && anchor.getTemplateHash() != null ? anchor.getTemplateHash() : record.getTemplateHash();
            if (templateHash != null && !templateHash.isEmpty()) {
                templateText = repository.findTemplateText(templateHash);
            }
            if (templateText == null || templateText.isEmpty()) {
                rd.skipped++;
                info(stepLine(index, key, "re-drive skipped (archived template text missing; re-record this invocation and retry)"));
                if (stepJsons != null) {
                    stepJsons.add("{\"recordId\":\"" + RecursiveJsonParser.escape(record.getRecordId()) + "\",\"invocationKey\":\"" + RecursiveJsonParser.escape(key != null ? key : "") + "\",\"action\":\"skipped\"}");
                }
                continue;
            }
            rd.callsUsed++;
            RegressionTestResult result = executor.execute(record, templateText, null, executionConfig);
            if (result.getInputTokens() != null && result.getOutputTokens() != null) {
                rd.tokensUsed += result.getInputTokens() + (long) result.getOutputTokens();
            }
            ComparisonResult comparison = result.getComparison();
            String served = servedNote(result, record);
            if (comparison != null && comparison.getVerdict() == Verdict.CHANGED) {
                rd.changed++;
                info(stepLine(index, key, "re-drive CHANGED " + comparison.getSummary()) + served);
            } else if (comparison != null) {
                rd.pass++;
                info(stepLine(index, key, "re-drive PASS") + served);
            } else {
                rd.failed++;
                info(stepLine(index, key, result.getStatus() + " " + (result.getErrorMessage() != null ? result.getErrorMessage() : "")));
            }
            if (stepJsons != null) {
                stepJsons.add(reDriveStepJson(record, key, result));
            }
        }
        info("Re-drive summary: PASS " + rd.pass + " | CHANGED " + rd.changed + " | failed " + rd.failed + " | skipped " + rd.skipped + " (" + CliSupport.plural(rd.callsUsed, "real re-drive call") + (rd.tokensUsed > 0 ? ", " + CliSupport.plural(rd.tokensUsed, "token") : "") + ")");
        if (jsonMode) {
            StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"task-re-drive\"");
            sb.append(",\"judgmentSemantics\":\"").append(JudgmentSemantics.VERSION).append('"');
            sb.append(",\"summary\":{\"total\":").append(rd.pass + rd.changed + rd.failed).append(",\"pass\":").append(rd.pass).append(",\"changed\":").append(rd.changed).append(",\"failed\":").append(rd.failed).append(",\"skipped\":").append(rd.skipped).append(",\"callsUsed\":").append(rd.callsUsed).append("}");
            sb.append(",\"steps\":[").append(String.join(",", stepJsons)).append("]}");
            out.println(sb.toString());
        }
    }

    private static String servedNote(RegressionTestResult result, InteractionRecord baseline) {
        String served = result.getServedModel();
        String recorded = baseline.getServedModel();
        if (served == null || recorded == null || served.equals(recorded)) {
            return "";
        }
        return "  (served: " + served + ", recorded: " + recorded + ")";
    }

    private static String reDriveStepJson(InteractionRecord record, String key, RegressionTestResult result) {
        StringBuilder sb = new StringBuilder("{\"recordId\":\"" + RecursiveJsonParser.escape(record.getRecordId()) + "\"");
        sb.append(",\"invocationKey\":\"").append(RecursiveJsonParser.escape(key != null ? key : "")).append('"');
        sb.append(",\"action\":\"re-driven\"");
        ComparisonResult comparison = result.getComparison();
        if (comparison != null) {
            sb.append(",\"verdict\":\"").append(comparison.getVerdict()).append('"');
            sb.append(",\"score\":").append(comparison.getScore());
            if (comparison.getSummary() != null) {
                sb.append(",\"summary\":\"").append(RecursiveJsonParser.escape(comparison.getSummary())).append('"');
            }
        }
        if (result.getErrorMessage() != null) {
            sb.append(",\"error\":\"").append(RecursiveJsonParser.escape(result.getErrorMessage())).append('"');
        }
        return sb.append('}').toString();
    }

    private static boolean budgetExhausted(Integer maxCalls, Integer maxTokens, int callsUsed, long tokensUsed) {
        if (maxCalls != null && callsUsed >= maxCalls) {
            return true;
        }
        return maxTokens != null && tokensUsed >= maxTokens;
    }

    /**
     * 对齐与漂移处置的聚合计数。
     */
    private static final class AlignmentTotals {
        int pass;
        int changed;
        int missing;
        int added;
        int ruleViolations;
        int crossVersion;
        int pendingCandidates;
        boolean anyTaskChanged;
    }

    private static final class DispositionTotals {
        int collected;
        int candidates;
        int hung;
        int external;
        int uncollected;
    }

    /**
     * 单键的最坏对齐结果（CHANGED > 缺口 > PASS），驱动漂移处置出口。
     */
    private enum StepOutcome {
        PASS, CHANGED, GAP
    }

    /**
     * 键级结果合并：CHANGED 最坏优先，其次缺口，PASS 只补空位。
     */
    private static void worstOutcome(Map<String, StepOutcome> outcomes, String key, StepOutcome outcome) {
        StepOutcome existing = outcomes.get(key);
        if (existing == StepOutcome.CHANGED || (existing == StepOutcome.GAP && outcome == StepOutcome.PASS)) {
            return;
        }
        outcomes.put(key, outcome);
    }

    /**
     * 对齐一个任务组（基线链 → 新链），输出逐步报告并聚合键级结果；
     * CHANGED 步即测试行为，现场重提指纹落候选——候选不落库则 approve 在
     * 新进程中不可达（重放与裁决通常不同进程）。
     */
    private void alignTaskGroup(TaskChain baseline, TaskChain newChain, Map<String, StepOutcome> outcomes, AlignmentTotals totals, BaselineManager manager) {
        TaskAlignment alignment = TaskAligner.align(baseline, newChain, comparator, rules);
        List<TaskRuleViolation> violations = alignment.getRuleViolations();
        totals.ruleViolations += violations.size();
        Set<String> ruleRequiredLabels = new LinkedHashSet<>();
        for (TaskRuleViolation violation : violations) {
            if (violation.getType() == TaskRuleViolation.Type.REQUIRED_STEP_MISSING) {
                ruleRequiredLabels.add(violation.getLabel());
            }
        }

        Map<String, InteractionRecord> baselineRecords = recordsById(baseline);
        Map<String, InteractionRecord> newRecords = recordsById(newChain);

        info("Task \"" + CliSupport.abbreviateText(newChain.getRequestText(), 80) + "\": baseline chain (session " + baseline.getSessionId() + ") → new chain (session " + newChain.getSessionId() + ")");
        if (rules != null && rules.hasTaskRules() && !newChain.isDeclared()) {
            info("Note: task has no declared taskKey; task rules do not apply.");
        }
        int missing = 0;
        int added = 0;
        int changed = 0;
        int pass = 0;
        int index = 0;
        for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
            index++;
            String label = CliSupport.displayKey(step.getInvocationKey());
            if (step.getKind() == StepKind.MISSING) {
                missing++;
                String detail = "missing step: baseline invoked '" + label + "', new chain did not";
                if (step.getInvocationLabel() != null && ruleRequiredLabels.contains(step.getInvocationLabel())) {
                    detail += " (task rule violation: required step)";
                }
                info(stepLine(index, step.getInvocationKey(), detail));
                worstOutcome(outcomes, step.getInvocationKey(), StepOutcome.GAP);
            } else if (step.getKind() == StepKind.ADDED) {
                added++;
                info(stepLine(index, step.getInvocationKey(), "added step: new chain invoked '" + label + "', baseline did not"));
                worstOutcome(outcomes, step.getInvocationKey(), StepOutcome.GAP);
            } else {
                String versionPrefix = step.isVersionSwitch() ? "cross-version pair: " : "";
                String served = servedModelNote(baselineRecords.get(step.getBaselineRecordId()), newRecords.get(step.getNewRecordId()));
                if (step.getVerdict() == Verdict.CHANGED) {
                    changed++;
                    info(stepLine(index, step.getInvocationKey(), versionPrefix + step.getComparison().getSummary()) + served);
                    String note = textDiffNote(step.getBaselineModelResponse(), step.getNewModelResponse());
                    if (!note.isEmpty()) {
                        info("    " + note);
                    }
                    worstOutcome(outcomes, step.getInvocationKey(), StepOutcome.CHANGED);
                    InteractionRecord changedRecord = newRecords.get(step.getNewRecordId());
                    if (changedRecord != null) {
                        boolean registered = manager.recordCandidate(changedRecord, FingerprintExtractor.extract(changedRecord, rules, changedRecord.getInvocationId()));
                        if (registered) {
                            totals.pendingCandidates++;
                            info("Candidate registered: " + CliSupport.displayKey(step.getInvocationKey()) + " (behavior change awaiting adjudication; approve promotes to baseline, reject discards).");
                        } else {
                            info("Difference holds against the paired chain, but the record fingerprint equals the profile's active baseline; no candidate registered (nothing to adjudicate).");
                        }
                    }
                } else {
                    pass++;
                    info(stepLine(index, step.getInvocationKey(), versionPrefix + "PASS") + served);
                    worstOutcome(outcomes, step.getInvocationKey(), StepOutcome.PASS);
                }
                if (step.getSurplusCount() > 0) {
                    info("    (uneven record counts on this invocation; " + step.getSurplusCount() + " surplus unpaired, excluded from judgment)");
                }
            }
        }
        for (TaskRuleViolation violation : violations) {
            info("Task rule violation: " + violation.getDetail());
        }
        info("Alignment summary: PASS " + pass + " | CHANGED " + changed + " | missing " + missing + " | added " + added + (violations.isEmpty() ? "" : " | " + CliSupport.plural(violations.size(), "rule violation")) + (alignment.getCrossVersionCount() > 0 ? " | cross-version " + alignment.getCrossVersionCount() : ""));
        ChainCost baselineCost = new ChainCost(baseline);
        ChainCost currentCost = new ChainCost(newChain);
        info("Cost: baseline " + formatTokens(baselineCost.tokens) + formatCost(baselineCost.costUsd) + " → current " + formatTokens(currentCost.tokens) + formatCost(currentCost.costUsd));
        if (alignment.getCrossVersionCount() > 0) {
            info("Note: cross-version pairs mix template versions; treat the verdict as a behavioral signal and use --re-drive for controlled per-point re-checks.");
        }
        if (alignment.isPrefixDependent()) {
            info("Note: chain carries a session prefix; real re-execution must replay the whole session up to this question, otherwise differences come from missing context rather than regression.");
        }
        String modelShift = servedModelPairNote(baseline, newChain);
        if (!modelShift.isEmpty()) {
            info(modelShift);
        }

        totals.pass += pass;
        totals.changed += changed;
        totals.missing += missing;
        totals.added += added;
        totals.crossVersion += alignment.getCrossVersionCount();
        if (changed + missing + added + violations.size() > 0) {
            totals.anyTaskChanged = true;
        }

        if (jsonMode) {
            List<String> stepJsons = new ArrayList<>();
            for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
                String action = step.getKind() == StepKind.MISSING ? "missing" : step.getKind() == StepKind.ADDED ? "added" : "aligned";
                stepJsons.add(alignedStepJson(action, step));
            }
            List<String> violationJsons = new ArrayList<>();
            for (TaskRuleViolation violation : violations) {
                violationJsons.add("{\"type\":\"" + violation.getType() + "\",\"label\":\"" + RecursiveJsonParser.escape(violation.getLabel()) + "\",\"detail\":\"" + RecursiveJsonParser.escape(violation.getDetail()) + "\"}");
            }
            out.println(taskJson("task-align", newChain.getRequestText(), newChain.getSessionId(), alignment.getSteps().size(), pass, changed, 0, 0, 0, missing, added, alignment.getCrossVersionCount(), stepJsons, alignment.getBaselineTime(), alignment.getNewChainTime(), alignment.isPrefixDependent(), violations.size(), violationJsons, costJson(baselineCost, currentCost)));
        }
    }

    /**
     * 漂移处置：PASS→收编（开发态）/未收编（--ci）；CHANGED→落候选；证据缺口或
     * 域外→挂起/仅报告。返回处置计数供退出码复合与 JSON 报告。
     */
    private DispositionTotals disposeDrifts(DriftReport drift, Set<String> scopedKeys, boolean ciMode, Map<String, StepOutcome> outcomes, BaselineManager manager) {
        DispositionTotals totals = new DispositionTotals();
        List<String> dispositionJsons = jsonMode ? new ArrayList<>() : null;
        for (DriftReport.DriftPoint point : drift.getSameKeyDrifts()) {
            disposeOne(point, "same-key", scopedKeys, ciMode, outcomes, manager, totals, dispositionJsons);
        }
        for (DriftReport.DriftPoint point : drift.getLabelSplits()) {
            disposeOne(point, "label-split", scopedKeys, ciMode, outcomes, manager, totals, dispositionJsons);
        }
        if (jsonMode) {
            StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"drift-disposition\"");
            sb.append(",\"judgmentSemantics\":\"").append(JudgmentSemantics.VERSION).append('"');
            sb.append(",\"summary\":{\"collected\":").append(totals.collected).append(",\"candidates\":").append(totals.candidates).append(",\"hung\":").append(totals.hung).append(",\"external\":").append(totals.external).append(",\"uncollected\":").append(totals.uncollected).append("}");
            sb.append(",\"dispositions\":[").append(String.join(",", dispositionJsons)).append("]}");
            out.println(sb.toString());
        }
        return totals;
    }

    private void disposeOne(DriftReport.DriftPoint point, String kind, Set<String> scopedKeys, boolean ciMode, Map<String, StepOutcome> outcomes, BaselineManager manager, DispositionTotals totals, List<String> dispositionJsons) {
        String key = point.getInvocationKey();
        String shown = CliSupport.displayKey(key);
        StepOutcome outcome = outcomes.get(key);
        String action;
        if (outcome == null) {
            // 缩域键集外的漂移只报告不处置；缩域内但无对齐步结果的（如单链任务）
            // 是证据缺口而非域外——两出口不得混同
            if (!scopedKeys.contains(key)) {
                totals.external++;
                action = "external";
                info("Drift outside scope (not disposed): " + shown + " (outside this run's alignment scope; visible in the drift report only)");
            } else {
                totals.hung++;
                action = "hung";
                info("Hung: " + shown + " (no alignable evidence; re-run the task for real and replay to complete the evidence)");
            }
        } else if (outcome == StepOutcome.CHANGED) {
            // 候选登记已在对齐层随 CHANGED 步就地报告，这里只记处置计数
            totals.candidates++;
            action = "candidate";
        } else if (outcome == StepOutcome.GAP) {
            totals.hung++;
            action = "hung";
            info("Hung: " + shown + " (alignment evidence gap; no collect, no candidate; a real re-run resolves it into another exit)");
        } else if (ciMode) {
            // --ci 模式：PASS 也不落治理写——收敛动作留给开发态 replay 或 approve
            totals.uncollected++;
            action = "uncollected";
            info("Identity not collected (--ci writes no governance state): " + shown + " (exit stays 0; run replay in dev mode to collect)");
        } else {
            boolean advanced = manager.advanceTemplateIdentity(key);
            totals.collected++;
            action = "collected";
            info("Collected: " + shown + " (no behavioral difference; " + ("label-split".equals(kind) && !advanced ? "new profile established with the latest template as identity" : "template identity " + shortHash(point.getProfileTemplateHash()) + " → " + shortHash(point.getLatestTemplateHash())) + ")");
        }
        if (dispositionJsons != null) {
            dispositionJsons.add("{\"invocationKey\":\"" + RecursiveJsonParser.escape(key) + "\",\"kind\":\"" + kind + "\",\"action\":\"" + action + "\"}");
        }
    }

    private static String shortHash(String hash) {
        return hash == null || hash.isEmpty() ? "none" : hash.substring(0, Math.min(8, hash.length()));
    }

    /**
     * 全库画像的判定语义版本守卫：任何画像由其他版本（含未标记历史行）批准即拒绝。
     */
    private String checkJudgmentSemantics() {
        List<String> problems = new ArrayList<>();
        try {
            for (InvocationProfile profile : repository.findAllInvocations()) {
                if (profile.getAlgoVersion() == null || !JudgmentSemantics.VERSION.equals(profile.getAlgoVersion())) {
                    problems.add("Judgment semantics version mismatch: " + profile.getInvocationKey() + " baseline was approved by " + (profile.getAlgoVersion() == null ? "an unmarked version" : profile.getAlgoVersion()) + ", current engine is " + JudgmentSemantics.VERSION + ". Refusing to judge to avoid silently re-interpreting historical baselines. Run `agentassert4j baseline --force` to re-establish baselines under the current semantics.");
                }
            }
        } catch (RuntimeException e) {
            return "Judgment semantics check failed (storage unreadable): " + e.getMessage();
        }
        return problems.isEmpty() ? null : String.join(System.lineSeparator(), problems);
    }

    /**
     * 缩域链中无法解析调用点键的记录计数告警——这些记录不进对齐分组
     * （TaskAligner 分组器跳过），留在判定集外必须可见。
     */
    private void warnUngroupableRecords(List<TaskChain> scoped) {
        int ungroupable = 0;
        List<String> samples = new ArrayList<>();
        for (TaskChain chain : scoped) {
            for (InteractionRecord record : chain.getRecords()) {
                if (CliSupport.invocationKeyOfRecord(record) == null) {
                    ungroupable++;
                    if (samples.size() < 3) {
                        samples.add(record.getRecordId());
                    }
                }
            }
        }
        if (ungroupable > 0) {
            diagnostic("Warning: " + CliSupport.plural(ungroupable, "record") + " failed key grouping and were excluded from this judgment set: " + String.join(", ", samples) + (ungroupable > samples.size() ? ", and more" : ""));
        }
    }

    /**
     * 缩域链中尚无基线画像的调用点键（CI 模式守卫的拒绝名单）。
     */
    private Set<String> unbaselinedKeysInScope(List<TaskChain> scoped) {
        Set<String> missing = new TreeSet<>();
        Set<String> checked = new HashSet<>();
        for (TaskChain chain : scoped) {
            for (InteractionRecord record : chain.getRecords()) {
                String key = CliSupport.invocationKeyOfRecord(record);
                if (key != null && checked.add(key) && repository.findInvocationByKey(key) == null) {
                    missing.add(key);
                }
            }
        }
        return missing;
    }

    /**
     * 基线与重放配置的模型身份不一致时告警——换模型重放的判定结果不可与
     * 原基线直接比较。配置未指定模型时比对客户端实际生效模型，
     * 否则「默认模型 ≠ 录制模型」这一最常见场景恰成盲区。
     */
    private void warnIfModelDiffers() {
        String configModel = executionConfig.getModel();
        if (configModel == null || configModel.isEmpty()) {
            configModel = llmClient.name();
        }
        Set<String> recordedModels = new TreeSet<>();
        try {
            for (String sessionId : repository.findAllSessionIds()) {
                for (InteractionRecord record : repository.findBySessionId(sessionId)) {
                    if (record.getModel() != null && !record.getModel().isEmpty()) {
                        recordedModels.add(record.getModel());
                    }
                }
            }
        } catch (RuntimeException e) {
            return;
        }
        if (!recordedModels.isEmpty() && !recordedModels.contains(configModel)) {
            diagnostic("Warning: replay model " + configModel + " differs from recorded models " + recordedModels + "; verdicts are not directly comparable to baselines (model switching is experimental).");
        }
    }

    /**
     * 快照是分析视图留档（供 status 巡检），写失败只告警不阻断。
     */
    private void saveGraphQuietly(InMemoryDependencyGraph graph) {
        try {
            repository.saveGraph(graph.toJson());
        } catch (RuntimeException e) {
            diagnostic("Warning: dependency graph snapshot write failed (analysis unaffected): " + e.getMessage());
        }
    }

    /**
     * 逐任务组的模型身份对偶检测：基线链与新链的 served 模型族不相交即报告——
     * 同模板跨执行的行为漂移主因是换模型/换部署，数据全在记录上，零新增存储。
     */
    private static String servedModelPairNote(TaskChain baseline, TaskChain newChain) {
        Set<String> baselineModels = servedModelsOf(baseline);
        Set<String> newModels = servedModelsOf(newChain);
        if (baselineModels.isEmpty() || newModels.isEmpty() || newModels.equals(baselineModels)) {
            return "";
        }
        return "Note: model identity changed (baseline served " + baselineModels + " → current " + newModels + "); differences may come from the model switch rather than the prompt.";
    }

    private static Set<String> servedModelsOf(TaskChain chain) {
        Set<String> models = new TreeSet<>();
        for (InteractionRecord record : chain.getRecords()) {
            if (record.getServedModel() != null && !record.getServedModel().isEmpty()) {
                models.add(record.getServedModel());
            }
        }
        return models;
    }

    /**
     * 逐步判定行的 served 模型标注：两侧记录的 served 模型不一致即就地标注，
     * 与单点重放同口径——答卷人不同，判定可解释性留给使用者裁量。
     */
    private static String servedModelNote(InteractionRecord baselineRecord, InteractionRecord newRecord) {
        if (baselineRecord == null || newRecord == null) {
            return "";
        }
        String served = newRecord.getServedModel();
        String recorded = baselineRecord.getServedModel();
        if (served == null || recorded == null || served.equals(recorded)) {
            return "";
        }
        return "  (served: " + served + ", baseline: " + recorded + ")";
    }

    private static Map<String, InteractionRecord> recordsById(TaskChain chain) {
        Map<String, InteractionRecord> byId = new LinkedHashMap<>();
        for (InteractionRecord record : chain.getRecords()) {
            byId.put(record.getRecordId(), record);
        }
        return byId;
    }

    /**
     * 任务链选择：请求文本精确相等优先（同文本多链升序全保留），未命中按前缀唯一
     * 候选采用、多候选歧义报错；调用点键过滤与之复合（AND），命中含该键记录的链。
     * 返回 null 表示选择器错误（歧义/未命中），调用方以用法错误退出。
     */
    private List<TaskChain> selectChains(List<TaskChain> chains, String taskPrefix, String invocationKey) {
        List<TaskChain> scoped = chains;
        if (taskPrefix != null) {
            scoped = selectByRequestText(scoped, taskPrefix);
            if (scoped == null) {
                return null;
            }
        }
        if (invocationKey != null) {
            List<TaskChain> filtered = new ArrayList<>();
            for (TaskChain chain : scoped) {
                for (InteractionRecord record : chain.getRecords()) {
                    if (invocationKey.equals(CliSupport.invocationKeyOfRecord(record))) {
                        filtered.add(chain);
                        break;
                    }
                }
            }
            scoped = filtered;
        }
        return scoped;
    }

    /**
     * 任务链选择：请求文本精确相等优先（同文本多链是同一任务的多轮执行，升序全保留，
     * 由调用方取最新为对照）；精确未命中时按前缀匹配——唯一候选文本直接采用，
     * 多个候选文本属歧义，报错列出全部候选并返回 null（调用方以用法错误退出）。
     * 与 --invocation 的「唯一前缀 + 歧义报错」目标选择器标准同款。
     */
    private List<TaskChain> selectByRequestText(List<TaskChain> chains, String taskPrefix) {
        List<TaskChain> exact = new ArrayList<>();
        List<TaskChain> prefixed = new ArrayList<>();
        Set<String> prefixTexts = new LinkedHashSet<>();
        for (TaskChain chain : chains) {
            if (chain.getRequestText().equals(taskPrefix)) {
                exact.add(chain);
            } else if (chain.getRequestText().startsWith(taskPrefix)) {
                prefixed.add(chain);
                prefixTexts.add(chain.getRequestText());
            }
        }
        if (!exact.isEmpty()) {
            return exact;
        }
        if (prefixTexts.size() > 1) {
            List<String> sorted = new ArrayList<>(prefixTexts);
            Collections.sort(sorted);
            List<String> shown = new ArrayList<>();
            for (String text : sorted) {
                shown.add(CliSupport.visibleText(CliSupport.abbreviateText(text, 60)));
            }
            diagnostic("--task '" + CliSupport.visibleText(taskPrefix) + "' matches multiple tasks: " + String.join(", ", shown) + "; provide a longer prefix.");
            return null;
        }
        return prefixed;
    }

    /**
     * 同名请求链分组（链序 = 全库派生的时间升序；组按首次出现序）。
     */
    private static List<List<TaskChain>> groupByRequestText(List<TaskChain> chains) {
        Map<String, List<TaskChain>> groups = new LinkedHashMap<>();
        for (TaskChain chain : chains) {
            groups.computeIfAbsent(chain.getRequestText(), k -> new ArrayList<>()).add(chain);
        }
        return new ArrayList<>(groups.values());
    }

    private void printSelfEstablished(TaskChain only) {
        info("Task \"" + CliSupport.abbreviateText(only.getRequestText(), 80) + "\" has a single chain (session " + only.getSessionId() + "); first recording becomes the baseline (" + CliSupport.plural(only.getRecords().size(), "step") + ").");
        info("Re-run this command after the next real execution to pair against this baseline and produce an alignment report.");
        if (jsonMode) {
            out.println("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"task-align\",\"selfEstablished\":true,\"task\":{\"request\":\"" + RecursiveJsonParser.escape(only.getRequestText()) + "\",\"sessionId\":\"" + RecursiveJsonParser.escape(only.getSessionId()) + "\"},\"summary\":{\"total\":" + only.getRecords().size() + ",\"pass\":" + only.getRecords().size() + ",\"changed\":0,\"skipped\":0,\"missing\":0,\"added\":0},\"steps\":[],\"judgmentSemantics\":\"" + JudgmentSemantics.VERSION + "\"}");
        }
    }

    /**
     * 只读预演：漂移集已在上文报告，这里列出将发生的任务配对与规则适用性，
     * 供 CI 在执行前核对选链是否如愿。
     */
    private int dryRunPlan(List<TaskChain> scoped, boolean reDrive, DriftReport drift, boolean narrowed) {
        List<List<TaskChain>> groups = groupByRequestText(scoped);
        info("Alignment plan (dry-run; no judgments, no baselines, no dispositions): " + CliSupport.plural(groups.size(), "task") + ", zero LLM calls.");
        if (reDrive) {
            List<InteractionRecord> planned = reDriveTargets(drift, false, narrowed, scoped);
            info("Re-drive plan (--re-drive): " + CliSupport.plural(planned.size(), "record") + " to re-drive with each point\'s latest archived template.");
            if (!planned.isEmpty()) {
                info(CostEstimator.estimate(planned, llmClient.name()));
            }
        }
        for (List<TaskChain> group : groups) {
            TaskChain latest = group.get(group.size() - 1);
            if (group.size() == 1) {
                info("  Task \"" + CliSupport.abbreviateText(latest.getRequestText(), 60) + "\": single chain (" + CliSupport.plural(latest.getRecords().size(), "step") + ") → first recording self-establishes the baseline.");
            } else {
                TaskChain baseline = group.get(group.size() - 2);
                info("  Task \"" + CliSupport.abbreviateText(latest.getRequestText(), 60) + "\": baseline session " + baseline.getSessionId() + " (" + CliSupport.plural(baseline.getRecords().size(), "step") + ") → new chain session " + latest.getSessionId() + " (" + CliSupport.plural(latest.getRecords().size(), "step") + "). Task rules: " + ruleApplicability(latest));
            }
            if (jsonMode) {
                out.println(dryRunAlignJson(latest.getRequestText(), group.size() > 1 ? baselineSessionOf(group) : null, group.size() > 1 ? group.get(group.size() - 2).getRecords().size() : null, latest.getSessionId(), latest.getRecords().size()));
            }
        }
        return 0;
    }

    private static String baselineSessionOf(List<TaskChain> group) {
        return group.get(group.size() - 2).getSessionId();
    }

    private String ruleApplicability(TaskChain chain) {
        if (rules == null || !rules.hasTaskRules()) {
            return "no task rules configured.";
        }
        if (!chain.isDeclared()) {
            return "taskKey not declared; rules do not apply.";
        }
        InvocationRulesConfig.TaskRule rule = rules.getTaskRule(chain.getRequestText());
        if (rule.isEmpty()) {
            return "taskKey declared but no task rule for it in the rules file.";
        }
        return "will evaluate requiredSteps " + rule.getRequiredSteps().size() + ", requiredOrder " + rule.getRequiredOrder().size() + ", steps " + rule.getSteps().size() + ".";
    }

    /**
     * 当前存在候选指纹的 invocationKey 列表（裁决提示用）。
     */
    private List<String> pendingInvocationKeys() {
        List<String> pending = new ArrayList<>();
        for (InvocationProfile profile : repository.findAllInvocations()) {
            if (profile.getCandidateFingerprint() != null) {
                pending.add(profile.getInvocationKey());
            }
        }
        return pending;
    }

    /**
     * 漂移检测报告（人类面）：同键漂移、标签裂键、下游波及与不可检测计数。
     * 批量漂移多为「建档种子≠最新模板」的一次性收敛——首次全量对账后逐点收编，
     * 不一定是批量回归，文案显式引导该认知。
     */
    private void printDriftReport(DriftReport drift) {
        if (!drift.hasDrift()) {
            info("Drift: all invocation template identities consistent (" + CliSupport.plural(drift.getZeroTemplateProfiles(), "zero-template invocation") + " undetectable).");
            return;
        }
        info("Drift: " + drift.getSameKeyDrifts().size() + " same-key, " + CliSupport.plural(drift.getLabelSplits().size(), "label split") + ", " + drift.getDownstreamKeys().size() + " downstream (" + CliSupport.plural(drift.getZeroTemplateProfiles(), "zero-template invocation") + " undetectable)");
        for (DriftReport.DriftPoint point : drift.getSameKeyDrifts()) {
            info("  ▲ " + CliSupport.displayKey(point.getInvocationKey()) + (point.getLabel() != null ? " (" + point.getLabel() + ")" : "") + " template " + shortHash(point.getProfileTemplateHash()) + " → " + shortHash(point.getLatestTemplateHash()));
        }
        for (DriftReport.DriftPoint point : drift.getLabelSplits()) {
            info("  ▲+ " + CliSupport.displayKey(point.getInvocationKey()) + " (" + point.getLabel() + ") label split, new profile on template " + shortHash(point.getLatestTemplateHash()));
        }
        if (!drift.getDownstreamKeys().isEmpty()) {
            info("  ↳ downstream: " + String.join(", ", drift.getDownstreamKeys()));
        }
        if (drift.getSkippedQueries() > 0) {
            info("  Warning: " + CliSupport.plural(drift.getSkippedQueries(), "detection query") + " failed and were skipped (see storage logs).");
        }
        info("(Baseline seeds take the earliest record per bucket; the first full audit reports one drift per invocation whose seed template differs from its latest one, auto-collected point by point after a PASS alignment; mass drifts are usually one-time convergence.)");
    }

    private static String driftJson(DriftReport drift) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"drift-detection\"");
        sb.append(",\"judgmentSemantics\":\"").append(JudgmentSemantics.VERSION).append('"');
        sb.append(",\"summary\":{\"sameKey\":").append(drift.getSameKeyDrifts().size()).append(",\"labelSplits\":").append(drift.getLabelSplits().size()).append(",\"downstream\":").append(drift.getDownstreamKeys().size()).append(",\"zeroTemplate\":").append(drift.getZeroTemplateProfiles()).append(",\"skippedQueries\":").append(drift.getSkippedQueries()).append("}");
        sb.append(",\"drifts\":[");
        List<String> points = new ArrayList<>();
        for (DriftReport.DriftPoint point : drift.getSameKeyDrifts()) {
            points.add(driftPointJson(point, "same-key"));
        }
        for (DriftReport.DriftPoint point : drift.getLabelSplits()) {
            points.add(driftPointJson(point, "label-split"));
        }
        sb.append(String.join(",", points)).append("]");
        sb.append(",\"downstreamKeys\":[");
        List<String> keys = new ArrayList<>();
        for (String key : drift.getDownstreamKeys()) {
            keys.add("\"" + RecursiveJsonParser.escape(key) + "\"");
        }
        return sb.append(String.join(",", keys)).append("]}").toString();
    }

    private static String driftPointJson(DriftReport.DriftPoint point, String kind) {
        StringBuilder sb = new StringBuilder("{\"invocationKey\":\"").append(RecursiveJsonParser.escape(point.getInvocationKey())).append('"');
        if (point.getLabel() != null) {
            sb.append(",\"label\":\"").append(RecursiveJsonParser.escape(point.getLabel())).append('"');
        }
        if (point.getProfileTemplateHash() != null) {
            sb.append(",\"profileTemplateHash\":\"").append(RecursiveJsonParser.escape(point.getProfileTemplateHash())).append('"');
        }
        if (point.getLatestTemplateHash() != null) {
            sb.append(",\"latestTemplateHash\":\"").append(RecursiveJsonParser.escape(point.getLatestTemplateHash())).append('"');
        }
        return sb.append(",\"kind\":\"").append(kind).append("\"}").toString();
    }

    private static String stepLine(int index, String key, String detail) {
        return "  [" + index + "] " + CliSupport.displayKey(key) + "  " + detail;
    }


    private static String abbreviate(String text, int budget) {
        return CliSupport.abbreviateText(text, budget);
    }

    /**
     * 非 PASS 对齐步的文本差异证据（低置信呈现）：结构指纹说明「哪里不同」，
     * 此注记补充「说了什么不同的话」。任一原文缺席或结构一致时静默省略。
     */
    private static String textDiffNote(String baselineText, String newText) {
        if (baselineText == null || baselineText.isEmpty() || newText == null || newText.isEmpty()) {
            return "";
        }
        String diff = TextDiffUtils.diff(baselineText, newText);
        if (diff == null) {
            return "";
        }
        List<String> evidences = new ArrayList<>();
        for (String line : diff.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("+ ") || trimmed.startsWith("- ") || trimmed.startsWith("~ ")) {
                evidences.add(trimmed);
                if (evidences.size() == 3) {
                    break;
                }
            }
        }
        if (evidences.isEmpty()) {
            return "";
        }
        String note = "  ↳ text diff (low confidence) " + String.join("; ", evidences);
        return note.length() <= TEXT_DIFF_BUDGET ? note : note.substring(0, TEXT_DIFF_BUDGET) + "...";
    }

    private static String dryRunAlignJson(String request, String baselineSession, Integer baselineSteps, String newSession, int newSteps) {
        return "{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"task-dry-run\",\"alignPlan\":{\"request\":\"" + RecursiveJsonParser.escape(request) + "\",\"baselineSession\":" + (baselineSession != null ? "\"" + RecursiveJsonParser.escape(baselineSession) + "\"" : "null") + ",\"baselineSteps\":" + (baselineSteps != null ? baselineSteps.toString() : "null") + ",\"newSession\":\"" + RecursiveJsonParser.escape(newSession) + "\"" + ",\"newSteps\":" + newSteps + "},\"judgmentSemantics\":\"" + JudgmentSemantics.VERSION + "\"}";
    }

    // ---------- JSON（agentassert4j.task-report/1，单行，null 缺省即契约） ----------

    private static String taskJson(String mode, String request, String sessionId, int total, int pass, int changed, int inherited, int postDivergence, int skipped, int missing, int added, int crossVersion, List<String> steps, long baselineTime, Long newChainTime, boolean prefixDependent, Integer ruleViolationCount, List<String> ruleViolationJsons, String costJson) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"").append(mode).append('"');
        sb.append(",\"judgmentSemantics\":\"").append(JudgmentSemantics.VERSION).append('"');
        sb.append(",\"task\":{\"request\":\"").append(RecursiveJsonParser.escape(request)).append("\",\"sessionId\":\"").append(RecursiveJsonParser.escape(sessionId)).append("\"}");
        sb.append(",\"summary\":{\"total\":").append(total).append(",\"pass\":").append(pass).append(",\"changed\":").append(changed).append(",\"inherited\":").append(inherited).append(",\"postDivergence\":").append(postDivergence).append(",\"skipped\":").append(skipped).append(",\"missing\":").append(missing).append(",\"added\":").append(added).append(",\"crossVersion\":").append(crossVersion);
        if (ruleViolationCount != null) {
            sb.append(",\"ruleViolations\":").append(ruleViolationCount);
        }
        sb.append("}");
        sb.append(",\"steps\":[").append(String.join(",", steps)).append("]");
        if (ruleViolationJsons != null && !ruleViolationJsons.isEmpty()) {
            sb.append(",\"ruleViolations\":[").append(String.join(",", ruleViolationJsons)).append("]");
        }
        sb.append(",\"baselineTime\":").append(baselineTime);
        if (newChainTime != null) {
            sb.append(",\"newChainTime\":").append(newChainTime);
        }
        if (costJson != null) {
            sb.append(costJson);
        }
        if (prefixDependent) {
            sb.append(",\"prefixDependent\":true");
        }
        return sb.append('}').toString();
    }

    /**
     * 单链的成本聚合 — token 合计恒有值（原始 int 无缺失语义）；
     * 费用按报告时本地价格表逐记录计价（servedModel 回退请求模型），
     * 任一记录模型无价即 costUsd=null（无价格不出货币数）。
     */
    private static final class ChainCost {
        private final long tokens;
        private final Double costUsd;

        ChainCost(TaskChain chain) {
            long tokenSum = 0;
            Double costSum = 0.0;
            for (InteractionRecord record : chain.getRecords()) {
                tokenSum += record.getInputTokens() + record.getOutputTokens();
                String model = record.getServedModel() != null ? record.getServedModel() : record.getModel();
                Double cost = CostEstimator.estimateCallCostUsd(model, record.getInputTokens(), record.getOutputTokens());
                if (cost == null) {
                    costSum = null;
                } else if (costSum != null) {
                    costSum += cost;
                }
            }
            this.tokens = tokenSum;
            this.costUsd = costSum;
        }
    }

    private static String formatTokens(long tokens) {
        return tokens >= 10000 ? String.format("%.1fk", tokens / 1000.0) : Long.toString(tokens);
    }

    private static String formatCost(Double costUsd) {
        return costUsd == null ? "" : "/$" + String.format("%.4f", costUsd);
    }

    /**
     * JSON 数字的定点十进制形态——Double.toString 对极小/极大值产生科学计数法，
     * 合法但人读不友好；六位小数内定点化并去掉尾随零
     */
    private static String plainDecimal(double value) {
        String s = String.format(Locale.ROOT, "%.6f", value);
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && s.charAt(end - 1) == '.') {
            end--;
        }
        return s.substring(0, end);
    }

    private static String costJson(ChainCost baseline, ChainCost current) {
        StringBuilder sb = new StringBuilder(",\"baseline\":{\"tokens\":").append(baseline.tokens);
        if (baseline.costUsd != null) {
            sb.append(",\"costUsd\":").append(plainDecimal(baseline.costUsd));
        }
        sb.append("},\"current\":{\"tokens\":").append(current.tokens);
        if (current.costUsd != null) {
            sb.append(",\"costUsd\":").append(plainDecimal(current.costUsd));
        }
        return sb.append("}").toString();
    }

    private static String alignedStepJson(String action, TaskAlignment.StepAlignment step) {
        StringBuilder sb = new StringBuilder("{");
        String recordId = step.getNewRecordId() != null ? step.getNewRecordId() : step.getBaselineRecordId();
        sb.append("\"recordId\":\"").append(RecursiveJsonParser.escape(recordId != null ? recordId : "")).append('"');
        sb.append(",\"invocationKey\":\"").append(RecursiveJsonParser.escape(step.getInvocationKey())).append('"');
        sb.append(",\"action\":\"").append(action).append('"');
        if (step.getVerdict() != null) {
            sb.append(",\"verdict\":\"").append(step.getVerdict()).append('"');
        }
        if (step.getComparison() != null) {
            sb.append(",\"score\":").append(step.getComparison().getScore());
            sb.append(",\"dims\":{\"toolSet\":").append(step.getComparison().isToolCallMatch());
            sb.append(",\"paramTypes\":").append(step.getComparison().isParamTypeMatch());
            sb.append(",\"outputStructure\":").append(step.getComparison().isStructureMatch());
            sb.append(",\"contentRules\":").append(step.getComparison().isKeywordMatch() && step.getComparison().isRegexMatch());
            sb.append(",\"behaviors\":").append(step.getComparison().isBehaviorMatch()).append("}");
            if (step.getComparison().getSummary() != null) {
                sb.append(",\"summary\":\"").append(RecursiveJsonParser.escape(step.getComparison().getSummary())).append('"');
            }
        }
        if (step.getSurplusCount() > 0) {
            sb.append(",\"surplusCount\":").append(step.getSurplusCount());
        }
        if (step.getInvocationLabel() != null) {
            sb.append(",\"invocationLabel\":\"").append(RecursiveJsonParser.escape(step.getInvocationLabel())).append('"');
        }
        if (step.isVersionSwitch()) {
            sb.append(",\"versionSwitch\":true");
            sb.append(",\"baselineSubdivision\":\"").append(RecursiveJsonParser.escape(step.getBaselineSubdivision())).append('"');
            sb.append(",\"newSubdivision\":\"").append(RecursiveJsonParser.escape(step.getNewSubdivision())).append('"');
        }
        return sb.append('}').toString();
    }
}
