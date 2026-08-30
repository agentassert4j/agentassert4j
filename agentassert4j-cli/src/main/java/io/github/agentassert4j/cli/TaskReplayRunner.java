package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.ImpactAnalyzer;
import io.github.agentassert4j.algorithm.InMemoryDependencyGraph;
import io.github.agentassert4j.algorithm.RegressionTestExecutor;
import io.github.agentassert4j.algorithm.TaskAligner;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.TestExecutionConfig;
import io.github.agentassert4j.model.AnalysisResult;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.LlmClient;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.HashUtil;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextDiffUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    private final io.github.agentassert4j.algorithm.DeterministicComparator comparator;
    private final InvocationRulesConfig rules;
    private final TestExecutionConfig executionConfig;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean jsonMode;

    public TaskReplayRunner(StorageRepository repository, LlmClient llmClient, io.github.agentassert4j.algorithm.DeterministicComparator comparator, InvocationRulesConfig rules, TestExecutionConfig executionConfig, PrintStream out, PrintStream err, boolean jsonMode) {
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
        executionConfig.validate();

        List<TaskChain> chains = CliSupport.taskChains(repository);
        if (chains.isEmpty()) {
            diagnostic("未录制到任何交互数据（先运行 Agent 积累录制）。");
            return 2;
        }

        return newPrompt != null ? runFrozenReplay(taskPrefix, affected, fullChain, newPrompt, oldPrompt, maxTotalCalls, maxTotalTokens, chains) : runAlignment(taskPrefix, chains);
    }

    // ---------- Mode A：冻结重放 ----------

    private int runFrozenReplay(String taskPrefix, boolean affected, boolean fullChain, String newPrompt, String oldPrompt, Integer maxTotalCalls, Integer maxTotalTokens, List<TaskChain> chains) {
        Set<String> affectedKeys = null;
        if (affected) {
            InMemoryDependencyGraph graph = CliSupport.rebuildGraph(repository);
            AnalysisResult analysis = new ImpactAnalyzer(repository, graph).analyzeChange(HashUtil.sha256(oldPrompt), HashUtil.sha256(newPrompt));
            if (!analysis.isHasBaseline() || analysis.isError()) {
                diagnostic(analysis.getMessage());
                return 2;
            }
            affectedKeys = analysis.getAllAffectedInvocations();
        }

        List<TaskChain> tasks = selectTasks(chains, taskPrefix, affected, affectedKeys);
        if (tasks.isEmpty()) {
            diagnostic(affected ? "受影响调用点未出现在任何任务链中（无任务可重放）。" : "未找到请求文本匹配「" + taskPrefix + "」的任务链（先录制交互或核对前缀）。");
            return 2;
        }

        // 自动建档（与单点重放同款语义）：候选落库以画像存在为前提
        new BaselineService(repository).establishMissing(jsonMode ? discardStream() : out, CliSupport.currentActor(), false, null, rules);

        io.github.agentassert4j.algorithm.BaselineManager baselineManager = new io.github.agentassert4j.algorithm.BaselineManager(repository);
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

                io.github.agentassert4j.model.RegressionTestResult result = executor.execute(record, newPrompt, null, executionConfig);
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
            out.println(taskJson("task-frozen-replay", taskRequest, taskSession, total, pass, changed, inherited, postDivergence, skipped, 0, 0, stepJsons, baselineTime, null, false));
        }
        if (changed > 0) {
            return 1;
        }
        return skipped > 0 ? 2 : 0;
    }

    // ---------- Mode B：真实对比 ----------

    private int runAlignment(String taskPrefix, List<TaskChain> chains) {
        List<TaskChain> matching = new ArrayList<>();
        for (TaskChain chain : chains) {
            if (chain.getRequestText().startsWith(taskPrefix)) {
                matching.add(chain);
            }
        }
        if (matching.isEmpty()) {
            diagnostic("未找到请求文本匹配「" + taskPrefix + "」的任务链。");
            return 2;
        }
        TaskChain newChain = matching.get(matching.size() - 1);
        if (matching.size() == 1) {
            info("任务「" + newChain.getRequestText() + "」仅一条链（session " + newChain.getSessionId() + "）——首录即基线，自建基线完成（" + newChain.getRecords().size() + " 步）。");
            info("下次真实再执行后重跑本命令，将自动配对本次基线并出对齐报告。");
            if (jsonMode) {
                out.println("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"task-align\",\"selfEstablished\":true,\"task\":{\"request\":\"" + RecursiveJsonParser.escape(newChain.getRequestText()) + "\",\"sessionId\":\"" + RecursiveJsonParser.escape(newChain.getSessionId()) + "\"},\"summary\":{\"total\":" + newChain.getRecords().size() + ",\"pass\":" + newChain.getRecords().size() + ",\"changed\":0,\"skipped\":0,\"missing\":0,\"added\":0},\"steps\":[],\"judgmentSemantics\":\"" + io.github.agentassert4j.algorithm.JudgmentSemantics.VERSION + "\"}");
            }
            return 0;
        }
        TaskChain baseline = matching.get(matching.size() - 2);
        TaskAlignment alignment = TaskAligner.align(baseline, newChain, comparator, rules);

        info("任务「" + newChain.getRequestText() + "」对齐：基线链（session " + baseline.getSessionId() + "）→ 新链（session " + newChain.getSessionId() + "）");
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
                    info(stepLine(index, step.getInvocationKey(), "CHANGED  score=" + String.format("%.2f", step.getComparison().getScore()) + "  " + step.getComparison().getSummary()));
                    info("    " + textDiffNote(step.getBaselineModelResponse(), step.getNewModelResponse()));
                } else {
                    pass++;
                    info(stepLine(index, step.getInvocationKey(), "PASS"));
                }
                if (step.getSurplusCount() > 0) {
                    info("    （该调用点两侧记录数不齐，富余 " + step.getSurplusCount() + " 条未配对，不判差异）");
                }
            }
        }
        info("对齐汇总: PASS " + pass + " | CHANGED " + changed + " | 缺步骤 " + missing + " | 新增步骤 " + added);
        if (alignment.isPrefixDependent()) {
            info("注意：任务链携带会话前缀——真实再执行对照必须重演到该问为止的整个会话前缀，否则差异源于上下文缺失而非回归。");
        }

        if (jsonMode) {
            List<String> stepJsons = new ArrayList<>();
            for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
                String action = step.getKind() == TaskAlignment.StepKind.MISSING ? "missing" : step.getKind() == TaskAlignment.StepKind.ADDED ? "added" : "aligned";
                stepJsons.add(alignedStepJson(action, step));
            }
            out.println(taskJson("task-align", newChain.getRequestText(), newChain.getSessionId(), alignment.getSteps().size(), pass, changed, 0, 0, 0, missing, added, stepJsons, alignment.getBaselineTime(), alignment.getNewChainTime(), alignment.isPrefixDependent()));
        }
        return changed + missing + added > 0 ? 1 : 0;
    }

    // ---------- 共用 ----------

    private List<TaskChain> selectTasks(List<TaskChain> chains, String taskPrefix, boolean affected, Set<String> affectedKeys) {
        List<TaskChain> selected = new ArrayList<>();
        for (TaskChain chain : chains) {
            if (affected) {
                for (InteractionRecord record : chain.getRecords()) {
                    String key = CliSupport.invocationKeyOfRecord(record);
                    if (key != null && affectedKeys.contains(key)) {
                        selected.add(chain);
                        break;
                    }
                }
            } else if (chain.getRequestText().startsWith(taskPrefix)) {
                selected.add(chain);
            }
        }
        if (affected) {
            return selected;
        }
        // 文本前缀命中多条链时取最新一条为回放对象（最新录制代表当前行为）
        return selected.isEmpty() ? selected : new ArrayList<>(java.util.Collections.singletonList(selected.get(selected.size() - 1)));
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

    private String describe(io.github.agentassert4j.model.RegressionTestResult result) {
        ComparisonResult comparison = result.getComparison();
        if (comparison != null) {
            return String.format("%s  score=%.2f  %s", comparison.getVerdict(), comparison.getScore(), comparison.getSummary());
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

    private static String taskJson(String mode, String request, String sessionId, int total, int pass, int changed, int inherited, int postDivergence, int skipped, int missing, int added, List<String> steps, long baselineTime, Long newChainTime, boolean prefixDependent) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.task-report/1\",\"mode\":\"").append(mode).append('"');
        sb.append(",\"judgmentSemantics\":\"").append(io.github.agentassert4j.algorithm.JudgmentSemantics.VERSION).append('"');
        sb.append(",\"task\":{\"request\":\"").append(RecursiveJsonParser.escape(request)).append("\",\"sessionId\":\"").append(RecursiveJsonParser.escape(sessionId)).append("\"}");
        sb.append(",\"summary\":{\"total\":").append(total).append(",\"pass\":").append(pass).append(",\"changed\":").append(changed).append(",\"inherited\":").append(inherited).append(",\"postDivergence\":").append(postDivergence).append(",\"skipped\":").append(skipped).append(",\"missing\":").append(missing).append(",\"added\":").append(added).append("}");
        sb.append(",\"steps\":[").append(String.join(",", steps)).append("]");
        sb.append(",\"baselineTime\":").append(baselineTime);
        if (newChainTime != null) {
            sb.append(",\"newChainTime\":").append(newChainTime);
        }
        if (prefixDependent) {
            sb.append(",\"prefixDependent\":true");
        }
        return sb.append('}').toString();
    }

    private String stepJson(String action, InteractionRecord record, String key, Verdict verdict, ComparisonResult comparison, io.github.agentassert4j.model.RegressionTestResult result) {
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
