package io.github.agentassert4j.cli;

import io.github.agentassert4j.algorithm.DeterministicComparator;
import io.github.agentassert4j.algorithm.JudgmentSemantics;
import io.github.agentassert4j.algorithm.TaskAligner;
import io.github.agentassert4j.algorithm.TaskChainView;
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
            diagnostic("验收包被拒绝：" + e.getMessage());
            return 2;
        }
        if (pack.getMeta() == null || !JudgmentSemantics.VERSION.equals(pack.getMeta().getJudgmentSemantics())) {
            diagnostic("版本守卫：验收包判定语义为 " + (pack.getMeta() == null ? "未标记" : pack.getMeta().getJudgmentSemantics()) + "，当前引擎为 " + JudgmentSemantics.VERSION + "——拒绝判定以防止静默重解释。");
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
            diagnostic("包内没有匹配前缀「" + taskPrefix + "」的任务。");
            return 2;
        }

        if (dryRun) {
            return dryRunPlan(pack, tasks, localChains);
        }

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
            TaskAlignment alignment = TaskAligner.align(baselineSteps, local, comparator, null);
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
                unmatchedLocal.add(chain.getRequestText() + "（session " + chain.getSessionId() + "）");
            }
        }

        int totalSteps = 0;
        // 因果提示：范围外链最常见的成因是包导出后才录制（未建档或未入包）——不指路时
        // 用户第一反应是配对故障，实际是数据卫生问题
        List<String> hints = new ArrayList<>();
        if (!unmatchedLocal.isEmpty()) {
            hints.add("范围外本地链通常来自验收包导出之后的新录制（新任务未建档或未入包）——先执行 baseline 补建档并重新导出验收包，或确认这些链本就属交付范围外。");
        }
        info("验收汇总: PASS " + pass + " | CHANGED " + changed + " | 缺步骤 " + missing + " | 新增步骤 " + added + " | 覆盖缺口 " + uncovered.size() + " | 范围外链 " + unmatchedLocal.size());
        for (String hint : hints) {
            info("提示：" + hint);
        }
        info("包 digest(SHA-256): " + packDigest);
        boolean crossModel = !localServedModels.isEmpty() && pack.getMeta().getServedModel() != null && !String.join(",", localServedModels).equals(pack.getMeta().getServedModel());
        if (crossModel) {
            info("跨模型验收：开发侧 " + pack.getMeta().getServedModel() + " / 本地 " + String.join(",", localServedModels) + "——结构判定有效，文本差异属措辞预期内。");
        }

        if (jsonMode) {
            out.println(verifyJson(pack, packDigest, pass, changed, missing, added, uncovered.size(), unmatchedLocal.size(), crossModel, taskJsons, uncovered, hints));
        }
        if (reportPath != null) {
            writeMarkdownReport(reportPath, pack, packDigest, crossModel, localServedModels, reportSections, uncovered, unmatchedLocal, pass, changed, missing, added);
            info("验收报告已写出：" + reportPath);
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
        info("验收预演（dry-run，未执行判定、未写报告）：包任务 " + tasks.size() + " 个，本地链 " + localChains.size() + " 条。");
        for (AcceptancePack.PackTask task : tasks) {
            TaskChain local = latestLocalChain(localChains, task.getTaskKey());
            String pairing = local == null ? "本地无匹配链（执行后将计入覆盖缺口）" : "配对本地链 session " + local.getSessionId() + "（" + local.getRecords().size() + " 步，包基线 " + task.getSteps().size() + " 步）";
            info("  " + task.getTaskKey() + " → " + pairing);
        }
        String crossModel = isCrossModelOverTasks(tasks, localChains, pack.getMeta().getServedModel());
        if (!crossModel.isEmpty()) {
            info("跨模型注记：包 served " + pack.getMeta().getServedModel() + " 与本地链 served " + crossModel + " 不一致——跨模型判定以结构指纹为主判据。");
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
        sb.append("### 任务「").append(task.getTaskKey()).append("」\n\n");
        sb.append("- 判定: **").append(alignment.getVerdict()).append("**");
        if (crossModel) {
            sb.append("（跨模型：文本差异属措辞预期内）");
        }
        sb.append('\n');
        int index = 0;
        for (TaskAlignment.StepAlignment step : alignment.getSteps()) {
            index++;
            sb.append("- 步骤 ").append(index).append(" `").append(shortKey(step.getInvocationKey())).append("` ");
            if (step.getKind() == TaskAlignment.StepKind.MISSING) {
                sb.append("**缺步骤**（开发侧执行、验收未出现）\n");
            } else if (step.getKind() == TaskAlignment.StepKind.ADDED) {
                sb.append("**新增步骤**（验收出现、开发侧未执行）\n");
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
                    sb.append("（跨版本配对 ").append(shortHash(step.getBaselineSubdivision())).append("→").append(shortHash(step.getNewSubdivision())).append("）");
                }
                sb.append('\n');
                String diff = textDiffNote(step.getBaselineModelResponse(), step.getNewModelResponse());
                if (!diff.isEmpty()) {
                    sb.append("  - ").append(diff).append("（文本差异低置信").append(crossModel ? "，跨模型属预期内" : "").append("）\n");
                }
                if (step.getSurplusCount() > 0) {
                    sb.append("  - 该调用点两侧记录数不齐，富余 ").append(step.getSurplusCount()).append(" 条未配对\n");
                }
            }
        }
        sb.append('\n');
        return sb.toString();
    }

    private void writeMarkdownReport(String reportPath, AcceptancePack pack, String digest, boolean crossModel, TreeSet<String> localServedModels, List<String> sections, List<String> uncovered, List<String> unmatchedLocal, int pass, int changed, int missing, int added) {
        StringBuilder sb = new StringBuilder();
        sb.append("# AgentAssert 交付验收报告\n\n");
        sb.append("| 项 | 值 |\n|----|----|\n");
        sb.append("| 包 schema | ").append(AcceptancePack.SCHEMA).append(" |\n");
        sb.append("| 包 SHA-256 | `").append(digest).append("` |\n");
        sb.append("| 判定语义 | ").append(JudgmentSemantics.VERSION).append(" |\n");
        sb.append("| 开发侧 servedModel | ").append(pack.getMeta().getServedModel() != null ? pack.getMeta().getServedModel() : "（未记录）").append(" |\n");
        sb.append("| 验收侧 servedModel | ").append(localServedModels.isEmpty() ? "（未记录）" : String.join(",", localServedModels)).append(" |\n");
        sb.append("| 跨模型验收 | ").append(crossModel ? "是（结构判定有效，文本差异属措辞预期内）" : "否").append(" |\n\n");
        sb.append("**判定汇总**: PASS ").append(pass).append(" | CHANGED ").append(changed).append(" | 缺步骤 ").append(missing).append(" | 新增步骤 ").append(added).append('\n');
        if (!uncovered.isEmpty()) {
            sb.append("\n> **覆盖缺口**（包内任务未在验收侧执行，证据不完整）：").append(String.join("；", uncovered)).append('\n');
        }
        if (!unmatchedLocal.isEmpty()) {
            sb.append("\n> 范围外本地链（未判定）：").append(String.join("；", unmatchedLocal)).append('\n');
            sb.append("> 提示：范围外链通常来自包导出后的新录制——先 baseline 补建档并重新导出验收包，或确认其属交付范围外。\n");
        }
        sb.append('\n');
        for (String section : sections) {
            sb.append(section);
        }
        try {
            Files.write(Paths.get(reportPath), sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            diagnostic("验收报告写入失败：" + e.getMessage());
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
        String note = "文本差异: " + String.join("；", evidences);
        return note.length() <= TEXT_DIFF_BUDGET ? note : note.substring(0, TEXT_DIFF_BUDGET) + "…";
    }

    private static String shortKey(String key) {
        return key.length() <= 48 ? key : key.substring(0, 48) + "…";
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
