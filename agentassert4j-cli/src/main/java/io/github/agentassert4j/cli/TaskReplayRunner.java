package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.*;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.RegressionTestResult;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.TaskRuleViolation;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextDiffUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.*;

/**
 * 任务域重放执行流程 — 以任务链（一次用户请求触发的全部记录）为回放单元。
 *
 * <p>两个互斥模式（旗标矩阵见 replay 命令帮助）：</p>
 * <ul>
 *   <li><b>冻结重放</b>（提供 --prompt）：取该文本最新链按规范序逐记录复用既有重放引擎；
 *       提供旧提示词时按影响集裁剪——非受影响记录继承 PASS，真重放遇 CHANGED 即停止后续
 *       （分歧即停的 task 级推广），后续记录标注分歧后下游（条件态）；--full-chain 全跑。</li>
 *   <li><b>真实对比</b>（无 --prompt）：同名链最新 vs 次新走 TaskAligner，零 LLM 调用。</li>
 * </ul>
 *
 * <p>退出码契约：changed 或 missing/added &gt; 0 → 1；skipped &gt; 0 → 2；否则 0。
 * 预算池 = 本次运行全局池（--max-total-calls/--max-total-tokens 对全部真重放合计封顶）。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public class TaskReplayRunner {

    private static final int TEXT_DIFF_BUDGET = 300;
    private static final int REPLAY_OUTPUT_EVIDENCE_BUDGET = 65536;

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

    /**
     * 执行任务域重放。
     *
     * @param taskPrefix     任务文本前缀选择器（--task；affected=true 时忽略）
     * @param affected       影响集选择器（--affected：含受影响调用点的全部任务链）
     * @param fullChain      取消影响裁剪与分歧即停，全部记录真重放
     * @param newPrompt      新 System Prompt 全文（null = 真实对比模式）
     * @param oldPrompt      旧 System Prompt 全文（影响裁剪根；null = 全链真重放）
     * @param maxTotalCalls  预算池：本次运行真重放调用次数上限（null = 不限）
     * @param maxTotalTokens 预算池：本次运行真重放 token 合计上限（null = 不限）
     * @return 进程退出码（0/1/2）
     */
    public int run(String taskPrefix, boolean affected, boolean fullChain, String newPrompt, String oldPrompt, Integer maxTotalCalls, Integer maxTotalTokens) {
        return run(taskPrefix, affected, fullChain, newPrompt, oldPrompt, maxTotalCalls, maxTotalTokens, false);
    }

    public int run(String taskPrefix, boolean affected, boolean fullChain, String newPrompt, String oldPrompt, Integer maxTotalCalls, Integer maxTotalTokens, boolean dryRun) {
        executionConfig.validate();

        List<TaskChain> chains = CliSupport.taskChains(repository);
        if (chains.isEmpty()) {
            diagnostic("未录制到任何交互数据（先运行 Agent 积累录制）。");
            return 2;
        }
        if (dryRun && newPrompt == null) {
            diagnostic("任务域 --dry-run 仅冻结重放（--prompt）适用；真实对比模式本身零 LLM 调用，直接执行即可。");
            return 2;
        }

        return newPrompt != null ? runFrozenReplay(taskPrefix, affected, fullChain, newPrompt, oldPrompt, maxTotalCalls, maxTotalTokens, chains, dryRun) : runAlignment(taskPrefix, chains);
    }

    private int runFrozenReplay(String taskPrefix, boolean affected, boolean fullChain, String newPrompt, String oldPrompt, Integer maxTotalCalls, Integer maxTotalTokens, List<TaskChain> chains, boolean dryRun) {
        // 冻结重放的受影响口径：仅「模板与旧提示词一致」的记录以新提示词真重放。
        // 影响集的图下游传播节点不参与——冻结重放喂的是录制原输入，其模板又未变，
        // 重放只复现原行为、不产生验证信号（下游真实影响由真实再执行+对齐收口）。
        Set<String> affectedKeys = null;
        if (oldPrompt != null) {
            affectedKeys = repository.findInvocationKeysByTemplateHash(HashUtil.sha256(oldPrompt));
            affectedKeys.remove("");
            if (affectedKeys.isEmpty()) {
                diagnostic(describeOldPromptMiss(oldPrompt, chains, affected ? null : taskPrefix));
                return 2;
            }
        }

        List<TaskChain> tasks = selectTasks(chains, taskPrefix, affected, affectedKeys);
        if (tasks == null) {
            return 2;
        }
        if (tasks.isEmpty()) {
            diagnostic(affected ? "受影响调用点未出现在任何任务链中（无任务可重放）。" : "未找到请求文本匹配「" + CliSupport.visibleText(taskPrefix) + "」的任务链（先录制交互或核对前缀）。");
            return 2;
        }
        if (dryRun) {
            if (jsonMode) {
                for (TaskChain task : tasks) {
                    printDryRunJson(task, affectedKeys, fullChain);
                }
                return 0;
            }
            return dryRunPlan(tasks, affectedKeys, fullChain);
        }

        // 自动建档（与单点重放同款语义）：候选落库以画像存在为前提
        new BaselineService(repository).establishMissing(jsonMode ? discardStream() : out, CliSupport.currentActor(), false, null, rules);

        BaselineManager baselineManager = new BaselineManager(repository);
        RegressionTestExecutor executor = new RegressionTestExecutor(llmClient, comparator, baselineManager, rules);

        List<String> stepJsons = jsonMode ? new ArrayList<>() : null;
        String taskRequest = null;
        String taskSession = null;
        int pass = 0;
        int changed = 0;
        int inherited = 0;
        int postDivergence = 0;
        int skipped = 0;
        int callsUsed = 0;
        long tokensUsed = 0;
        boolean diverged = false;
        long baselineTime = 0;

        for (TaskChain task : tasks) {
            if (taskRequest != null) {
                info("");
            }
            taskRequest = task.getRequestText();
            taskSession = task.getSessionId();
            baselineTime = task.firstTimestamp();
            info("任务「" + taskRequest + "」（session " + taskSession + "，" + task.getRecords().size() + " 步）：");

            int stepIndex = 0;
            for (InteractionRecord record : task.getRecords()) {
                stepIndex++;
                String key = CliSupport.invocationKeyOfRecord(record);
                if (affectedKeys != null && !fullChain && (key == null || !affectedKeys.contains(key))) {
                    inherited++;
                    info(stepLine(stepIndex, key, "继承 PASS（未受影响）"));
                    if (jsonMode) {
                        stepJsons.add(stepJson("inherited", record, key, null, null, null));
                    }
                    continue;
                }
                if (diverged && !fullChain) {
                    postDivergence++;
                    info(stepLine(stepIndex, key, "分歧后下游——未执行（条件态：基线行为在此之后是否仍成立需真实重跑收口）"));
                    if (jsonMode) {
                        stepJsons.add(stepJson("post-divergence", record, key, null, null, null));
                    }
                    continue;
                }
                if (budgetExhausted(maxTotalCalls, maxTotalTokens, callsUsed, tokensUsed)) {
                    skipped++;
                    info(stepLine(stepIndex, key, "跳过（预算耗尽 budget_exhausted）"));
                    if (jsonMode) {
                        stepJsons.add(stepJson("skipped", record, key, null, null, null));
                    }
                    continue;
                }

                RegressionTestResult result = executor.execute(record, newPrompt, null, executionConfig);
                callsUsed++;
                if (result.getInputTokens() != null && result.getOutputTokens() != null) {
                    tokensUsed += result.getInputTokens() + (long) result.getOutputTokens();
                }
                ComparisonResult comparison = result.getComparison();
                if (comparison != null && comparison.getVerdict() == Verdict.CHANGED) {
                    changed++;
                    info(stepLine(stepIndex, key, describe(result)));
                    info("    " + toolSummary(record));
                    if (jsonMode) {
                        stepJsons.add(stepJson("replayed", record, key, Verdict.CHANGED, comparison, result));
                    }
                    if (!fullChain) {
                        diverged = true;
                    }
                } else if (comparison != null) {
                    pass++;
                    info(stepLine(stepIndex, key, describe(result)));
                    if (jsonMode) {
                        stepJsons.add(stepJson("replayed", record, key, Verdict.PASS, comparison, result));
                    }
                } else {
                    // 超时/API 错误：证据缺口，按 skipped 口径计（证据不完整不允许冒充绿）
                    skipped++;
                    info(stepLine(stepIndex, key, result.getStatus() + " " + (result.getErrorMessage() != null ? result.getErrorMessage() : "")));
                    if (jsonMode) {
                        stepJsons.add(stepJson("skipped", record, key, null, null, result));
                    }
                }
            }
        }

        int total = pass + changed + inherited + postDivergence + skipped;
        info("任务汇总: PASS " + pass + " | CHANGED " + changed + " | 继承 " + inherited + " | 分歧后 " + postDivergence + " | 跳过 " + skipped + "（共 " + total + " 步，真重放 " + callsUsed + " 次");
        if (tokensUsed > 0) {
            info("，tokens " + tokensUsed + "）");
        } else {
            info("）");
        }
        info("用 `agentassert4j approve --invocation <调用点键前缀>` 接受差异，或 `reject` 拒绝。");

        if (jsonMode) {
            out.println(taskJson("task-frozen-replay", taskRequest, taskSession, total, pass, changed, inherited, postDivergence, skipped, 0, 0, stepJsons, baselineTime, null, false, null, null, null));
        }
        if (changed > 0) {
            return 1;
        }
        return skipped > 0 ? 2 : 0;
    }

    /**
     * 干跑计划：列出选中任务链的逐步执行计划（真重放 / 继承）与成本预估，
     * 不建档、不发起任何真实调用。分歧后下游无法在计划期预知（取决于运行时是否 CHANGED）。
     */
    private int dryRunPlan(List<TaskChain> tasks, Set<String> affectedKeys, boolean fullChain) {
        List<InteractionRecord> plannedRecords = new ArrayList<>();
        int plannedTotal = 0;
        int inheritedTotal = 0;
        for (TaskChain task : tasks) {
            info("任务「" + task.getRequestText() + "」（session " + task.getSessionId() + "，" + task.getRecords().size() + " 步）：");
            int index = 0;
            for (InteractionRecord record : task.getRecords()) {
                index++;
                String key = CliSupport.invocationKeyOfRecord(record);
                boolean replay = fullChain || affectedKeys == null || affectedKeys.contains(record.getInvocationKey());
                if (replay) {
                    plannedTotal++;
                    plannedRecords.add(record);
                    info(stepLine(index, key, "真重放（受影响）"));
                } else {
                    inheritedTotal++;
                    info(stepLine(index, key, "继承 PASS（未受影响）"));
                }
            }
        }
        info("dry-run：共 " + tasks.size() + " 条任务链，真重放 " + plannedTotal + " 步 / 继承 " + inheritedTotal + " 步，未调用 LLM、未建档。");
        if (!plannedRecords.isEmpty()) {
            info(CostEstimator.estimate(plannedRecords, llmClient.name()));
        }
        return 0;
    }

    private void printDryRunJson(TaskChain task, Set<String> affectedKeys, boolean fullChain) {
        StringBuilder steps = new StringBuilder();
        int total = 0;
        int planned = 0;
        int inherited = 0;
        for (InteractionRecord record : task.getRecords()) {
            total++;
            boolean replay = fullChain || affectedKeys == null || affectedKeys.contains(record.getInvocationKey());
            if (replay) {
                planned++;
            } else {
                inherited++;
            }
            if (steps.length() > 0) {
                steps.append(",");
            }
            steps.append("{\"recordId\":\"").append(RecursiveJsonParser.escape(record.getRecordId())).append("\",\"invocationKey\":\"").append(RecursiveJsonParser.escape(record.getInvocationKey())).append("\",\"action\":\"").append(replay ? "planned-replay" : "inherited").append("\"}");
        }
        out.println("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"task-dry-run\",\"judgmentSemantics\":\"" + JudgmentSemantics.VERSION + "\",\"task\":{\"request\":\"" + RecursiveJsonParser.escape(task.getRequestText()) + "\",\"sessionId\":\"" + RecursiveJsonParser.escape(task.getSessionId()) + "\"},\"summary\":{\"chains\":1,\"total\":" + total + ",\"plannedReplay\":" + planned + ",\"inherited\":" + inherited + "},\"steps\":[" + steps + "]}");
    }

    private int runAlignment(String taskPrefix, List<TaskChain> chains) {
        List<TaskChain> matching = selectByRequestText(chains, taskPrefix);
        if (matching == null) {
            return 2;
        }
        if (matching.isEmpty()) {
            diagnostic("未找到请求文本匹配「" + CliSupport.visibleText(taskPrefix) + "」的任务链。");
            return 2;
        }
        TaskChain newChain = matching.get(matching.size() - 1);
        if (matching.size() == 1) {
            info("任务「" + newChain.getRequestText() + "」仅一条链（session " + newChain.getSessionId() + "）——首录即基线，自建基线完成（" + newChain.getRecords().size() + " 步）。");
            info("下次真实再执行后重跑本命令，将自动配对本次基线并出对齐报告。");
            if (jsonMode) {
                out.println("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"task-align\",\"selfEstablished\":true,\"task\":{\"request\":\"" + RecursiveJsonParser.escape(newChain.getRequestText()) + "\",\"sessionId\":\"" + RecursiveJsonParser.escape(newChain.getSessionId()) + "\"},\"summary\":{\"total\":" + newChain.getRecords().size() + ",\"pass\":" + newChain.getRecords().size() + ",\"changed\":0,\"skipped\":0,\"missing\":0,\"added\":0},\"steps\":[],\"judgmentSemantics\":\"" + JudgmentSemantics.VERSION + "\"}");
            }
            return 0;
        }
        TaskChain baseline = matching.get(matching.size() - 2);
        TaskAlignment alignment = TaskAligner.align(baseline, newChain, comparator, rules);
        List<TaskRuleViolation> violations = alignment.getRuleViolations();

        info("任务「" + newChain.getRequestText() + "」对齐：基线链（session " + baseline.getSessionId() + "）→ 新链（session " + newChain.getSessionId() + "）");
        if (rules != null && rules.hasTaskRules() && !newChain.isDeclared()) {
            info("注意：本任务未声明 taskKey，任务规则不适用。");
        }
        int missing = 0;
        int added = 0;
        int changed = 0;
        int pass = 0;
        int index = 0;
        for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
            index++;
            String label = shortKey(step.getInvocationKey());
            if (step.getKind() == TaskAlignment.StepKind.MISSING) {
                missing++;
                info(stepLine(index, step.getInvocationKey(), "缺步骤——基线执行了「" + label + "」，新链未调用"));
            } else if (step.getKind() == TaskAlignment.StepKind.ADDED) {
                added++;
                info(stepLine(index, step.getInvocationKey(), "新增步骤——新链调用了「" + label + "」，基线未调用"));
            } else {
                if (step.getVerdict() == Verdict.CHANGED) {
                    changed++;
                    info(stepLine(index, step.getInvocationKey(), step.getComparison().getSummary()));
                    String note = textDiffNote(step.getBaselineModelResponse(), step.getNewModelResponse());
                    if (!note.isEmpty()) {
                        info("    " + note);
                    }
                } else {
                    pass++;
                    info(stepLine(index, step.getInvocationKey(), "PASS"));
                }
                if (step.getSurplusCount() > 0) {
                    info("    （该调用点两侧记录数不齐，富余 " + step.getSurplusCount() + " 条未配对，不判差异）");
                }
            }
        }
        for (TaskRuleViolation violation : violations) {
            info("违反任务规则: " + violation.getDetail());
        }
        info("对齐汇总: PASS " + pass + " | CHANGED " + changed + " | 缺步骤 " + missing + " | 新增步骤 " + added + (violations.isEmpty() ? "" : " | 违规 " + violations.size()));
        ChainCost baselineCost = new ChainCost(baseline);
        ChainCost currentCost = new ChainCost(newChain);
        info("成本对照: 基线 " + formatTokens(baselineCost.tokens) + formatCost(baselineCost.costUsd) + " → 当前 " + formatTokens(currentCost.tokens) + formatCost(currentCost.costUsd));
        if (alignment.isPrefixDependent()) {
            info("注意：任务链携带会话前缀——真实再执行对照必须重演到该问为止的整个会话前缀，否则差异源于上下文缺失而非回归。");
        }

        if (jsonMode) {
            List<String> stepJsons = new ArrayList<>();
            for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
                String action = step.getKind() == TaskAlignment.StepKind.MISSING ? "missing" : step.getKind() == TaskAlignment.StepKind.ADDED ? "added" : "aligned";
                stepJsons.add(alignedStepJson(action, step));
            }
            List<String> violationJsons = new ArrayList<>();
            for (TaskRuleViolation violation : violations) {
                violationJsons.add("{\"type\":\"" + violation.getType() + "\",\"label\":\"" + RecursiveJsonParser.escape(violation.getLabel()) + "\",\"detail\":\"" + RecursiveJsonParser.escape(violation.getDetail()) + "\"}");
            }
            out.println(taskJson("task-align", newChain.getRequestText(), newChain.getSessionId(), alignment.getSteps().size(), pass, changed, 0, 0, 0, missing, added, stepJsons, alignment.getBaselineTime(), alignment.getNewChainTime(), alignment.isPrefixDependent(), violations.size(), violationJsons, costJson(baselineCost, currentCost)));
        }
        return changed + missing + added + violations.size() > 0 ? 1 : 0;
    }

    private List<TaskChain> selectTasks(List<TaskChain> chains, String taskPrefix, boolean affected, Set<String> affectedKeys) {
        if (affected) {
            List<TaskChain> selected = new ArrayList<>();
            for (TaskChain chain : chains) {
                for (InteractionRecord record : chain.getRecords()) {
                    String key = CliSupport.invocationKeyOfRecord(record);
                    if (key != null && affectedKeys.contains(key)) {
                        selected.add(chain);
                        break;
                    }
                }
            }
            return selected;
        }
        return selectByRequestText(chains, taskPrefix);
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
                shown.add(CliSupport.visibleText(text));
            }
            diagnostic("--task " + CliSupport.visibleText(taskPrefix) + " 前缀匹配到多个任务：" + String.join("、", shown) + "，请提供更长前缀。");
            return null;
        }
        return prefixed;
    }

    /**
     * 旧提示词哈希无命中的诊断——只回一句「无命中」用户无从下手：最常见的根因是拿单段转储
     * 或重新组装的文本当旧提示词，而录制归档的是完整拼装后的 system 全文（差一个全局段/
     * 技能列表段哈希必然不等）。回显提供文本的哈希与靶链现存模板变体，供对照定位。
     */
    private String describeOldPromptMiss(String oldPrompt, List<TaskChain> chains, String taskPrefix) {
        StringBuilder sb = new StringBuilder();
        sb.append("--old-prompt 与任何录制模板都不相等（提供文本 sha256=").append(HashUtil.sha256(oldPrompt), 0, 16).append("…）。");
        List<TaskChain> scope = chains;
        if (taskPrefix != null) {
            List<TaskChain> selected = selectByRequestText(chains, taskPrefix);
            if (selected != null && !selected.isEmpty()) {
                scope = selected;
            }
        }
        Set<String> variants = new LinkedHashSet<>();
        for (TaskChain chain : scope) {
            for (InteractionRecord record : chain.getRecords()) {
                String label = record.getInvocationId() != null && !record.getInvocationId().isEmpty() ? record.getInvocationId() : CliSupport.invocationKeyOfRecord(record);
                String hash = record.getTemplateHash();
                if (hash != null && !hash.isEmpty()) {
                    variants.add(label + "@" + hash.substring(0, Math.min(8, hash.length())));
                }
            }
        }
        if (!variants.isEmpty()) {
            sb.append("靶链现存模板变体：").append(String.join("、", variants)).append("。");
        }
        sb.append("旧提示词必须是录制时归档的完整 system 模板全文（含全局拼接段）——单段转储或重新组装的文本哈希不等，归档原文见库内模板巡检（status）。");
        return sb.toString();
    }

    private static boolean budgetExhausted(Integer maxCalls, Integer maxTokens, int callsUsed, long tokensUsed) {
        if (maxCalls != null && callsUsed >= maxCalls) {
            return true;
        }
        return maxTokens != null && tokensUsed >= maxTokens;
    }

    private static String stepLine(int index, String key, String detail) {
        return "  [" + index + "] " + (key != null ? shortKey(key) : "(未解析调用点)") + "  " + detail;
    }

    private static String shortKey(String key) {
        return key.length() <= 48 ? key : key.substring(0, 48) + "…";
    }

    private String describe(RegressionTestResult result) {
        ComparisonResult comparison = result.getComparison();
        if (comparison != null) {
            return comparison.getSummary();
        }
        return result.getStatus() + "  " + (result.getErrorMessage() != null ? result.getErrorMessage() : "");
    }

    /**
     * 首次验收链视图要素：该步调了什么工具、带了什么参数、结果摘要与调用费用
     */
    private static String toolSummary(InteractionRecord record) {
        if (!record.isHasToolCalls() || record.getToolCalls() == null || record.getToolCalls().isEmpty()) {
            return "（纯文本调用）";
        }
        StringBuilder sb = new StringBuilder("工具: ");
        for (ToolCall call : record.getToolCalls()) {
            if (sb.length() > 3) {
                sb.append("; ");
            }
            sb.append(call.getToolName()).append("(").append(abbreviate(String.valueOf(call.getArguments()), 60)).append(")");
            String resultText = call.getResult();
            if (resultText != null && !resultText.isEmpty()) {
                sb.append(" → ").append(abbreviate(resultText, 60));
            }
        }
        if (record.getCostUsd() != null) {
            sb.append("  [cost $").append(String.format("%.4f", record.getCostUsd())).append("]");
        }
        return sb.toString();
    }

    private static String abbreviate(String text, int budget) {
        String flat = text.replaceAll("\\s+", " ");
        return flat.length() <= budget ? flat : flat.substring(0, budget) + "…";
    }

    /**
     * 非 PASS 用例的文本差异证据（低置信呈现，与单点重放同口径）
     */
    private static String textDiffNote(String baselineText, String replayText) {
        if (baselineText == null || baselineText.isEmpty() || replayText == null || replayText.isEmpty()) {
            return "";
        }
        String diff = TextDiffUtils.diff(baselineText, replayText);
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
        String note = "  ↳ 文本差异（低置信）" + String.join("；", evidences);
        return note.length() <= TEXT_DIFF_BUDGET ? note : note.substring(0, TEXT_DIFF_BUDGET) + "…";
    }

    private static PrintStream discardStream() {
        return new PrintStream(new ByteArrayOutputStream(), true);
    }

    // ---------- JSON（agentassert4j.task-report/1，单行，null 缺省即契约） ----------

    private static String taskJson(String mode, String request, String sessionId, int total, int pass, int changed, int inherited, int postDivergence, int skipped, int missing, int added, List<String> steps, long baselineTime, Long newChainTime, boolean prefixDependent, Integer ruleViolationCount, List<String> ruleViolationJsons, String costJson) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"").append(mode).append('"');
        sb.append(",\"judgmentSemantics\":\"").append(JudgmentSemantics.VERSION).append('"');
        sb.append(",\"task\":{\"request\":\"").append(RecursiveJsonParser.escape(request)).append("\",\"sessionId\":\"").append(RecursiveJsonParser.escape(sessionId)).append("\"}");
        sb.append(",\"summary\":{\"total\":").append(total).append(",\"pass\":").append(pass).append(",\"changed\":").append(changed).append(",\"inherited\":").append(inherited).append(",\"postDivergence\":").append(postDivergence).append(",\"skipped\":").append(skipped).append(",\"missing\":").append(missing).append(",\"added\":").append(added);
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

    private static String costJson(ChainCost baseline, ChainCost current) {
        StringBuilder sb = new StringBuilder(",\"baseline\":{\"tokens\":").append(baseline.tokens);
        if (baseline.costUsd != null) {
            sb.append(",\"costUsd\":").append(baseline.costUsd);
        }
        sb.append("},\"current\":{\"tokens\":").append(current.tokens);
        if (current.costUsd != null) {
            sb.append(",\"costUsd\":").append(current.costUsd);
        }
        return sb.append("}").toString();
    }

    private String stepJson(String action, InteractionRecord record, String key, Verdict verdict, ComparisonResult comparison, RegressionTestResult result) {
        StringBuilder sb = new StringBuilder("{\"recordId\":\"").append(RecursiveJsonParser.escape(record.getRecordId())).append('"');
        sb.append(",\"invocationKey\":\"").append(RecursiveJsonParser.escape(key != null ? key : "")).append('"');
        sb.append(",\"action\":\"").append(action).append('"');
        if (verdict != null) {
            sb.append(",\"verdict\":\"").append(verdict).append('"');
        }
        if (comparison != null) {
            sb.append(",\"score\":").append(comparison.getScore());
            sb.append(",\"dims\":{\"toolSet\":").append(comparison.isToolCallMatch());
            sb.append(",\"paramTypes\":").append(comparison.isParamTypeMatch());
            sb.append(",\"outputStructure\":").append(comparison.isStructureMatch());
            sb.append(",\"contentRules\":").append(comparison.isKeywordMatch() && comparison.isRegexMatch());
            sb.append(",\"behaviors\":").append(comparison.isBehaviorMatch()).append("}");
            if (comparison.getSummary() != null) {
                sb.append(",\"summary\":\"").append(RecursiveJsonParser.escape(comparison.getSummary())).append('"');
            }
        }
        if (result != null && result.getReplayOutput() != null && Verdict.CHANGED.equals(verdict)) {
            String output = result.getReplayOutput();
            if (output.length() > REPLAY_OUTPUT_EVIDENCE_BUDGET) {
                output = output.substring(0, REPLAY_OUTPUT_EVIDENCE_BUDGET);
                sb.append(",\"replayOutputTruncated\":true");
            }
            sb.append(",\"replayOutput\":\"").append(RecursiveJsonParser.escape(output)).append('"');
        }
        return sb.append('}').toString();
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
        return sb.append('}').toString();
    }
}
