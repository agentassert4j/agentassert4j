package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.JudgmentSemantics;
import io.github.agentassert4j.algorithm.TaskAligner;
import io.github.agentassert4j.algorithm.TaskChainView;
import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.AcceptancePack;
import io.github.agentassert4j.model.BaselineStep;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.Verdict;
import io.github.agentassert4j.spi.StorageRepository;
import io.github.agentassert4j.util.PackCodec;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextDiffUtils;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * verify 执行流程 — 验收包（导入参照）× 本地录制链（现场重提）的交付验收比对。
 *
 * <p>包内指纹作为基线侧、本地录制链现场重提作为当前侧，走同一对齐器；
 * 验收包只读：不落库、不改本地基线与候选状态。包判定语义与当前引擎不一致时拒绝判定；
 * 包任务未执行属证据缺口，不允许冒充通过。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public class VerifyRunner {

    private static final int TEXT_DIFF_BUDGET = 300;

    private final StorageRepository repository;
    private final DeterministicComparator comparator;
    private final PrintStream out;
    private final PrintStream err;
    private final boolean jsonMode;

    public VerifyRunner(StorageRepository repository, DeterministicComparator comparator, PrintStream out, PrintStream err, boolean jsonMode) {
        this.repository = repository;
        this.comparator = comparator;
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
     * 执行验收比对。
     *
     * @param dryRun 只读预演：装载包、列任务与本地链配对情况、跨模型注记，零判定零写入
     * @return 进程退出码（0 全部结构一致；1 任一结构偏差；2 用法/版本守卫/覆盖缺口）
     */
    public int run(String packContent, String packDigest, String taskPrefix, String reportPath, boolean dryRun) {
        AcceptancePack pack;
        try {
            pack = PackCodec.fromJson(packContent);
        } catch (IllegalArgumentException e) {
            diagnostic("Acceptance pack rejected: " + e.getMessage());
            return 2;
        }
        if (pack.getMeta() == null || !JudgmentSemantics.VERSION.equals(pack.getMeta().getJudgmentSemantics())) {
            diagnostic("Version guard: pack judgment semantics is " + (pack.getMeta() == null ? "unmarked" : pack.getMeta().getJudgmentSemantics()) + ", current engine is " + JudgmentSemantics.VERSION + ". Refusing to judge to avoid silent re-interpretation.");
            return 2;
        }

        List<TaskChain> localChains = TaskChainView.resolveAll(repository);
        List<AcceptancePack.PackTask> tasks = new ArrayList<>();
        for (AcceptancePack.PackTask task : pack.getTasks()) {
            if (taskPrefix == null || task.getTaskKey().startsWith(taskPrefix)) {
                tasks.add(task);
            }
        }
        if (tasks.isEmpty()) {
            diagnostic("No tasks in the pack match prefix '" + taskPrefix + "'.");
            return 2;
        }

        if (dryRun) {
            return dryRunPlan(pack, tasks, localChains);
        }

        // 维度 3/4 与任务纪律的比对口径来自基线侧声明：包内嵌规则段是验收侧的规则真源，
        // 不读本地规则文件；无规则段的包退化为空规则——两侧默认 match，报告注记降级
        InvocationRulesConfig packRules = InvocationRulesConfig.fromMap(pack.getRules());
        boolean rulesEmbedded = packRules.hasRules();

        TreeSet<String> localServedModels = new TreeSet<>();
        List<String> uncovered = new ArrayList<>();
        List<String> unmatchedLocal = new ArrayList<>();
        List<String> taskJsons = jsonMode ? new ArrayList<>() : null;
        List<String> reportSections = new ArrayList<>();
        int pass = 0;
        int changed = 0;
        int missing = 0;
        int added = 0;

        for (AcceptancePack.PackTask task : tasks) {
            TaskChain local = latestLocalChain(localChains, task.getTaskKey());
            if (local == null) {
                uncovered.add(task.getTaskKey());
                continue;
            }
            Map<String, List<BaselineStep>> baselineSteps = new LinkedHashMap<>();
            for (BaselineStep step : task.getSteps()) {
                step.setInvocationId(TaskAligner.declaredLabelOfKey(step.getInvocationKey()));
                baselineSteps.computeIfAbsent(step.getInvocationKey(), k -> new ArrayList<>()).add(step);
            }
            TaskAlignment alignment = TaskAligner.align(baselineSteps, local, comparator, packRules);
            alignment.setBaselineTime(task.getBaselineTime());
            alignment.setNewChainTime(local.firstTimestamp());
            boolean crossModel = isCrossModel(local, pack.getMeta().getServedModel());
            collectLocalServedModels(local, localServedModels);

            if (alignment.getVerdict() == Verdict.CHANGED) {
                changed++;
            } else {
                pass++;
            }
            for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
                if (step.getKind() == TaskAlignment.StepKind.MISSING) {
                    missing++;
                } else if (step.getKind() == TaskAlignment.StepKind.ADDED) {
                    added++;
                }
            }
            reportSections.add(renderTask(task, alignment, crossModel));
            if (jsonMode) {
                taskJsons.add(taskJson(task, alignment));
            }
        }

        // 范围外本地链：不匹配任何包任务键的链（仅列出不判定）。
        // 配对语义=请求文本精确相等（防误配对）：前缀同名的链不是证据，与覆盖缺口对称列出
        for (TaskChain chain : localChains) {
            boolean matched = false;
            for (AcceptancePack.PackTask task : tasks) {
                if (chain.getRequestText().equals(task.getTaskKey())) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                unmatchedLocal.add(chain.getRequestText() + " (session " + chain.getSessionId() + ")");
            }
        }

        int totalSteps = 0;
        // 因果提示：范围外链最常见的成因是包导出后才录制（未建档或未入包）——不指路时
        // 用户第一反应是配对故障，实际是数据卫生问题
        List<String> hints = new ArrayList<>();
        if (!unmatchedLocal.isEmpty()) {
            hints.add("Out-of-scope local chains usually come from recordings made after the pack export (new tasks not baselined or not in the pack). Run `agentassert4j baseline` to establish them and re-export the pack, or confirm they are out of delivery scope.");
        }
        info("Verification summary: PASS " + pass + " | CHANGED " + changed + " | missing " + missing + " | added " + added + " | coverage gaps " + uncovered.size() + " | out-of-scope chains " + unmatchedLocal.size());
        for (String hint : hints) {
            info("Note: " + hint);
        }
        info("Pack digest (SHA-256): " + packDigest);
        boolean crossModel = !localServedModels.isEmpty() && pack.getMeta().getServedModel() != null && !String.join(",", localServedModels).equals(pack.getMeta().getServedModel());
        if (crossModel) {
            info("Cross-model acceptance: dev side " + pack.getMeta().getServedModel() + " / local " + String.join(",", localServedModels) + "; structural verdicts valid, text differences are expected wording variation.");
        }

        if (jsonMode) {
            out.println(verifyJson(pack, packDigest, pass, changed, missing, added, uncovered.size(), unmatchedLocal.size(), crossModel, taskJsons, uncovered, hints));
        }
        if (reportPath != null) {
            writeMarkdownReport(reportPath, pack, packDigest, crossModel, rulesEmbedded, localServedModels, reportSections, uncovered, unmatchedLocal, pass, changed, missing, added);
            info("Verification report written: " + reportPath);
        }

        if (changed + missing + added > 0) {
            return 1;
        }
        return uncovered.isEmpty() ? 0 : 2;
    }

    /**
     * 只读预演：包元信息、任务清单与本地链配对情况（覆盖/未覆盖/范围外）、
     * 跨模型注记——验收人在执行前核对包与本库是否对得上。零判定零写入。
     */
    private int dryRunPlan(AcceptancePack pack, List<AcceptancePack.PackTask> tasks, List<TaskChain> localChains) {
        info("Verification dry-run (no judgments, no report written): " + CliSupport.plural(tasks.size(), "pack task") + ", " + CliSupport.plural(localChains.size(), "local chain") + ".");
        for (AcceptancePack.PackTask task : tasks) {
            TaskChain local = latestLocalChain(localChains, task.getTaskKey());
            String pairing = local == null ? "no matching local chain (counts as a coverage gap when executed)" : "pairs with local chain session " + local.getSessionId() + " (" + CliSupport.plural(local.getRecords().size(), "step") + "; pack baseline " + CliSupport.plural(task.getSteps().size(), "step") + ")";
            info("  " + task.getTaskKey() + " → " + pairing);
        }
        String crossModel = isCrossModelOverTasks(tasks, localChains, pack.getMeta().getServedModel());
        if (!crossModel.isEmpty()) {
            info("Cross-model note: pack served " + pack.getMeta().getServedModel() + " differs from local served " + crossModel + "; structural fingerprints are the primary evidence for cross-model verdicts.");
        }
        if (jsonMode) {
            out.println("{\"schema\":\"agentassert4j.verify-report/1\",\"mode\":\"dry-run\",\"summary\":{\"tasks\":" + tasks.size() + ",\"localChains\":" + localChains.size() + ",\"uncovered\":" + uncoveredCount(tasks, localChains) + "},\"judgmentSemantics\":\"" + JudgmentSemantics.VERSION + "\"}");
        }
        return 0;
    }

    private int uncoveredCount(List<AcceptancePack.PackTask> tasks, List<TaskChain> localChains) {
        int uncovered = 0;
        for (AcceptancePack.PackTask task : tasks) {
            if (latestLocalChain(localChains, task.getTaskKey()) == null) {
                uncovered++;
            }
        }
        return uncovered;
    }

    private String isCrossModelOverTasks(List<AcceptancePack.PackTask> tasks, List<TaskChain> localChains, String packServedModel) {
        TreeSet<String> localServed = new TreeSet<>();
        for (TaskChain chain : localChains) {
            for (AcceptancePack.PackTask task : tasks) {
                if (chain.getRequestText().equals(task.getTaskKey())) {
                    collectLocalServedModels(chain, localServed);
                }
            }
        }
        if (localServed.isEmpty() || (packServedModel != null && localServed.size() == 1 && localServed.contains(packServedModel))) {
            return "";
        }
        return String.join(", ", localServed);
    }

    /**
     * 包任务在本地链中的精确匹配（请求文本相等，多链取链首时间最新）。
     * 交付配对是键对键语义：前缀同名文本不是同一任务，宁作覆盖缺口不作误配。
     */
    private TaskChain latestLocalChain(List<TaskChain> chains, String taskKey) {
        TaskChain latest = null;
        for (TaskChain chain : chains) {
            if (chain.getRequestText().equals(taskKey)) {
                latest = chain;
            }
        }
        return latest;
    }

    private void collectLocalServedModels(TaskChain chain, TreeSet<String> into) {
        for (InteractionRecord record : chain.getRecords()) {
            if (record.getServedModel() != null) {
                into.add(record.getServedModel());
            }
        }
    }

    private boolean isCrossModel(TaskChain local, String packServedModel) {
        if (packServedModel == null || packServedModel.isEmpty()) {
            return false;
        }
        TreeSet<String> localModels = new TreeSet<>();
        collectLocalServedModels(local, localModels);
        return !localModels.isEmpty() && !String.join(",", localModels).equals(packServedModel);
    }

    // ---------- 报告渲染 ----------

    private String renderTask(AcceptancePack.PackTask task, TaskAlignment alignment, boolean crossModel) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Task \"").append(task.getTaskKey()).append("\"\n\n");
        sb.append("- Verdict: **").append(alignment.getVerdict()).append("**");
        if (crossModel) {
            sb.append(" (cross-model: text differences are expected wording variation)");
        }
        sb.append('\n');
        int index = 0;
        for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
            index++;
            sb.append("- Step ").append(index).append(" `").append(shortKey(step.getInvocationKey())).append("` ");
            if (step.getKind() == TaskAlignment.StepKind.MISSING) {
                sb.append("**missing step** (executed on the dev side, absent locally)\n");
            } else if (step.getKind() == TaskAlignment.StepKind.ADDED) {
                sb.append("**added step** (present locally, not executed on the dev side)\n");
            } else {
                ComparisonResult comparison = step.getComparison();
                sb.append("**").append(step.getVerdict()).append("**");
                if (comparison != null) {
                    sb.append(" score=").append(String.format("%.2f", comparison.getScore()));
                    if (comparison.getSummary() != null) {
                        sb.append("  ").append(comparison.getSummary());
                    }
                }
                if (step.isVersionSwitch()) {
                    sb.append(" (cross-version pair ").append(shortHash(step.getBaselineSubdivision())).append("→").append(shortHash(step.getNewSubdivision())).append(")");
                }
                sb.append('\n');
                String diff = textDiffNote(step.getBaselineModelResponse(), step.getNewModelResponse());
                if (!diff.isEmpty()) {
                    sb.append("  - ").append(diff).append(" (text diff, low confidence").append(crossModel ? "; expected under cross-model" : "").append(")\n");
                }
                if (step.getSurplusCount() > 0) {
                    sb.append("  - uneven record counts on this invocation; ").append(step.getSurplusCount()).append(" surplus unpaired\n");
                }
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private void writeMarkdownReport(String reportPath, AcceptancePack pack, String digest, boolean crossModel, boolean rulesEmbedded, TreeSet<String> localServedModels, List<String> sections, List<String> uncovered, List<String> unmatchedLocal, int pass, int changed, int missing, int added) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AgentAssert Acceptance Verification Report\n\n");
        sb.append("| Item | Value |\n|----|----|\n");
        sb.append("| Pack schema | ").append(AcceptancePack.SCHEMA).append(" |\n");
        sb.append("| Pack SHA-256 | `").append(digest).append("` |\n");
        sb.append("| Judgment semantics | ").append(JudgmentSemantics.VERSION).append(" |\n");
        sb.append("| Content rules | ").append(rulesEmbedded ? "embedded in pack (dimensions 3/4 active)" : "not embedded in pack (dimensions 3/4 skipped)").append(" |\n");
        sb.append("| Dev-side servedModel | ").append(pack.getMeta().getServedModel() != null ? pack.getMeta().getServedModel() : "(not recorded)").append(" |\n");
        sb.append("| Local servedModel | ").append(localServedModels.isEmpty() ? "(not recorded)" : String.join(",", localServedModels)).append(" |\n");
        sb.append("| Cross-model | ").append(crossModel ? "yes (structural verdicts valid; text differences are expected wording variation)" : "no").append(" |\n\n");
        sb.append("**Verdict summary**: PASS ").append(pass).append(" | CHANGED ").append(changed).append(" | missing ").append(missing).append(" | added ").append(added).append('\n');
        if (!uncovered.isEmpty()) {
            sb.append("\n> **Coverage gaps** (pack tasks not executed locally; evidence incomplete): ").append(String.join("; ", uncovered)).append('\n');
        }
        if (!unmatchedLocal.isEmpty()) {
            sb.append("\n> Out-of-scope local chains (not judged): ").append(String.join("; ", unmatchedLocal)).append('\n');
            sb.append("> Note: out-of-scope chains usually come from recordings made after the pack export. Run `agentassert4j baseline` to establish them and re-export the pack, or confirm they are out of delivery scope.\n");
        }
        sb.append('\n');
        for (String section : sections) {
            sb.append(section);
        }
        try {
            Files.write(Paths.get(reportPath), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            diagnostic("Failed to write the verification report: " + e.getMessage());
        }
    }

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
        String note = "text diff: " + String.join("; ", evidences);
        return note.length() <= TEXT_DIFF_BUDGET ? note : note.substring(0, TEXT_DIFF_BUDGET) + "...";
    }

    private static String shortKey(String key) {
        return key.length() <= 48 ? key : key.substring(0, 48) + "...";
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() <= 8 ? hash : hash.substring(0, 8);
    }

    // ---------- JSON（agentassert4j.verify-report/1，单行） ----------

    private String verifyJson(AcceptancePack pack, String digest, int pass, int changed, int missing, int added, int uncovered, int unmatchedLocal, boolean crossModel, List<String> taskJsons, List<String> uncoveredKeys, List<String> hints) {
        StringBuilder sb = new StringBuilder("{\"schema\":\"agentassert4j.verify-report/1\",\"judgmentSemantics\":\"").append(JudgmentSemantics.VERSION).append('"');
        sb.append(",\"pack\":{\"digest\":\"").append(RecursiveJsonParser.escape(digest)).append("\",\"servedModel\":\"").append(RecursiveJsonParser.escape(pack.getMeta().getServedModel() != null ? pack.getMeta().getServedModel() : "")).append("\"}");
        sb.append(",\"summary\":{\"tasks\":").append(taskJsons.size()).append(",\"pass\":").append(pass).append(",\"changed\":").append(changed).append(",\"missing\":").append(missing).append(",\"added\":").append(added).append(",\"uncovered\":").append(uncovered).append(",\"unmatchedLocal\":").append(unmatchedLocal).append(",\"crossModel\":").append(crossModel).append("}");
        sb.append(",\"tasks\":[").append(String.join(",", taskJsons)).append("]");
        List<String> quoted = new ArrayList<>();
        for (String key : uncoveredKeys) {
            quoted.add("\"" + RecursiveJsonParser.escape(key) + "\"");
        }
        sb.append(",\"uncoveredTaskKeys\":[").append(String.join(",", quoted)).append("]");
        List<String> hintJsons = new ArrayList<>();
        for (String hint : hints) {
            hintJsons.add("\"" + RecursiveJsonParser.escape(hint) + "\"");
        }
        sb.append(",\"hints\":[").append(String.join(",", hintJsons)).append("]}");
        return sb.toString();
    }

    private String taskJson(AcceptancePack.PackTask task, TaskAlignment alignment) {
        StringBuilder sb = new StringBuilder("{\"taskKey\":\"").append(RecursiveJsonParser.escape(task.getTaskKey())).append('"');
        sb.append(",\"verdict\":\"").append(alignment.getVerdict()).append('"');
        sb.append(",\"steps\":[");
        List<String> steps = new ArrayList<>();
        for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
            StringBuilder ss = new StringBuilder("{");
            ss.append("\"invocationKey\":\"").append(RecursiveJsonParser.escape(step.getInvocationKey())).append('"');
            ss.append(",\"kind\":\"").append(step.getKind()).append('"');
            if (step.getVerdict() != null) {
                ss.append(",\"verdict\":\"").append(step.getVerdict()).append('"');
            }
            if (step.getComparison() != null) {
                ss.append(",\"score\":").append(step.getComparison().getScore());
                ss.append(",\"dims\":{\"toolSet\":").append(step.getComparison().isToolCallMatch());
                ss.append(",\"paramTypes\":").append(step.getComparison().isParamTypeMatch());
                ss.append(",\"outputStructure\":").append(step.getComparison().isStructureMatch());
                ss.append(",\"contentRules\":").append(step.getComparison().isKeywordMatch() && step.getComparison().isRegexMatch());
                ss.append(",\"behaviors\":").append(step.getComparison().isBehaviorMatch()).append("}");
                if (step.getComparison().getSummary() != null) {
                    ss.append(",\"summary\":\"").append(RecursiveJsonParser.escape(step.getComparison().getSummary())).append('"');
                }
            }
            if (step.getSurplusCount() > 0) {
                ss.append(",\"surplusCount\":").append(step.getSurplusCount());
            }
            if (step.getInvocationLabel() != null) {
                ss.append(",\"invocationLabel\":\"").append(RecursiveJsonParser.escape(step.getInvocationLabel())).append('"');
            }
            if (step.isVersionSwitch()) {
                ss.append(",\"versionSwitch\":true");
                ss.append(",\"baselineSubdivision\":\"").append(RecursiveJsonParser.escape(step.getBaselineSubdivision())).append('"');
                ss.append(",\"newSubdivision\":\"").append(RecursiveJsonParser.escape(step.getNewSubdivision())).append('"');
            }
            steps.add(ss.append('}').toString());
        }
        sb.append(String.join(",", steps)).append("]}");
        return sb.toString();
    }
}
