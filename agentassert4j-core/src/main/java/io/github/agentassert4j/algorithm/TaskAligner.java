package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.config.InvocationRulesConfig.StepCount;
import io.github.agentassert4j.config.InvocationRulesConfig.TaskRule;
import io.github.agentassert4j.model.BaselineStep;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepKind;
import io.github.agentassert4j.result.TaskRuleViolation;
import io.github.agentassert4j.result.TaskRuleViolation.Type;
import io.github.agentassert4j.result.Verdict;

import java.util.*;

/**
 * 任务对齐器 — 基线链 × 新链按调用点对齐的逐步判定（纯比较，零 LLM 调用）。
 *
 * <p>对齐键 = invocationKey：两侧各按调用点键分组（组内规范序），三方分类
 * matched/missing/added；matched 组内 1:1 规范序配对（较少侧配对，富余计数进
 * 报告不判差异），每对两侧指纹现场重提后经注入的对比器判定——不消费任何存档
 * 指纹。缺步骤/新增步骤是行为差异，与配对 CHANGED 同归入链级 CHANGED。</p>
 *
 * <p>对齐收尾评 rules.tasks 任务纪律（必备步骤/次数范围/有序子序列，只对声明
 * taskKey 的任务、按新链侧评估）：违规挂入结果的 ruleViolations 并折叠为链级
 * CHANGED，不新增 verdict 值。</p>
 *
 * @author axy-yxa
 * @since 2026-08-30
 */
public final class TaskAligner {

    private TaskAligner() {
    }

    /**
     * 对齐两条任务链。
     *
     * @param baseline   基线链（次新链）
     * @param newChain   新链（最新链）
     * @param comparator 对比器（CLI 工厂单源构造，ignorableFields 口径与重放一致）
     * @param rules      规则配置（维度 3-4 口径，两侧同源；null = 无规则）
     */
    public static TaskAlignment align(TaskChain baseline, TaskChain newChain, DeterministicComparator comparator, InvocationRulesConfig rules) {
        TaskAlignment alignment = align(baselineStepsOf(baseline, rules), newChain, comparator, rules);
        alignment.setBaselineTime(baseline.firstTimestamp());
        alignment.setNewChainTime(newChain.firstTimestamp());
        alignment.setPrefixDependent(alignment.isPrefixDependent() || hasSessionPrefix(baseline));
        return alignment;
    }

    /**
     * 基线链的指纹步骤化。
     */
    public static LinkedHashMap<String, List<BaselineStep>> baselineStepsOf(TaskChain baseline, InvocationRulesConfig rules) {
        LinkedHashMap<String, List<BaselineStep>> steps = new LinkedHashMap<>();
        for (InteractionRecord record : baseline.getRecords()) {
            String key = record.getInvocationKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            BaselineStep step = new BaselineStep();
            step.setInvocationKey(key);
            step.setRecordId(record.getRecordId());
            step.setFingerprint(FingerprintExtractor.extract(record, rules, record.getInvocationId()));
            steps.computeIfAbsent(key, k -> new ArrayList<>()).add(step);
        }
        return steps;
    }

    /**
     * 基线侧步骤由调用方给定的对齐。
     *
     * @param baselineSteps 基线侧步骤（每键有序，指纹为比对依据）
     * @param newChain      新链（最新链）
     * @param comparator    对比器
     * @param rules         规则配置（新链侧现场重提口径；null = 无规则）
     */
    public static TaskAlignment align(Map<String, List<BaselineStep>> baselineSteps, TaskChain newChain, DeterministicComparator comparator, InvocationRulesConfig rules) {
        TaskAlignment alignment = new TaskAlignment();
        alignment.setPrefixDependent(hasSessionPrefix(newChain));

        Map<String, List<BaselineStep>> baselineGroups = baselineSteps;
        Map<String, List<InteractionRecord>> newGroups = groupByInvocation(newChain.getRecords());

        boolean anyChanged = false;
        // LinkedHashSet 语义：先基线序后新链序的并集，缺步骤排在其原链位置附近
        List<String> invocationOrder = new ArrayList<>(baselineGroups.keySet());
        for (String key : newGroups.keySet()) {
            if (!baselineGroups.containsKey(key)) {
                invocationOrder.add(key);
            }
        }

        for (String key : invocationOrder) {
            List<BaselineStep> baseSteps = baselineGroups.get(key);
            List<InteractionRecord> newRecords = newGroups.get(key);
            StepAlignment step = new StepAlignment();
            step.setInvocationKey(key);
            if (baseSteps == null) {
                step.setKind(StepKind.ADDED);
                step.setNewRecordId(newRecords.get(0).getRecordId());
                anyChanged = true;
            } else if (newRecords == null) {
                step.setKind(StepKind.MISSING);
                step.setBaselineRecordId(baseSteps.get(0).getRecordId());
                anyChanged = true;
            } else {
                step.setKind(StepKind.MATCHED);
                alignMatched(step, baseSteps, newRecords, comparator, rules);
                if (step.getVerdict() == Verdict.CHANGED) {
                    anyChanged = true;
                }
            }
            alignment.getSteps().add(step);
        }

        alignment.setVerdict(anyChanged ? Verdict.CHANGED : Verdict.PASS);
        List<TaskRuleViolation> violations = evaluateTaskRules(newChain, rules);
        if (!violations.isEmpty()) {
            alignment.getRuleViolations().addAll(violations);
            alignment.setVerdict(Verdict.CHANGED);
        }
        return alignment;
    }

    /**
     * 对齐收尾评任务规则（只对声明 taskKey 的任务生效，键 = 声明值精确相等）。
     * 计数与顺序都看新链的声明标签序列（规范序）；无标签步骤不参与。
     * 呈现顺序 = 规则声明序：requiredSteps → requiredOrder → steps。
     */
    private static List<TaskRuleViolation> evaluateTaskRules(TaskChain newChain, InvocationRulesConfig rules) {
        if (rules == null || !rules.hasTaskRules() || !newChain.isDeclared()) {
            return Collections.emptyList();
        }
        TaskRule rule = rules.getTaskRule(newChain.getRequestText());
        if (rule.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> labelSequence = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (InteractionRecord record : newChain.getRecords()) {
            String label = record.getInvocationId();
            if (label == null || label.isEmpty()) {
                continue;
            }
            labelSequence.add(label);
            counts.merge(label, 1, Integer::sum);
        }

        List<TaskRuleViolation> violations = new ArrayList<>();
        for (String required : rule.getRequiredSteps()) {
            if (!counts.containsKey(required)) {
                violations.add(new TaskRuleViolation(Type.REQUIRED_STEP_MISSING, required, "缺少必备步骤「" + required + "」"));
            }
        }
        if (!rule.getRequiredOrder().isEmpty() && !isSubsequence(rule.getRequiredOrder(), labelSequence)) {
            violations.add(new TaskRuleViolation(Type.ORDER_VIOLATION, String.join(",", rule.getRequiredOrder()), "步骤「" + String.join(",", rule.getRequiredOrder()) + "」未出现或顺序不符"));
        }
        for (Map.Entry<String, StepCount> entry : rule.getSteps().entrySet()) {
            String label = entry.getKey();
            int count = counts.containsKey(label) ? counts.get(label) : 0;
            StepCount bounds = entry.getValue();
            if (bounds.outOfRange(count)) {
                violations.add(new TaskRuleViolation(Type.STEP_COUNT_OUT_OF_RANGE, label, "步骤「" + label + "」出现 " + count + " 次，超出声明范围 [" + (bounds.getMin() == null ? "无下限" : bounds.getMin()) + ", " + (bounds.getMax() == null ? "无上限" : bounds.getMax()) + "]"));
            }
        }
        return violations;
    }

    /**
     * 有序子序列判定：expected 的每个标签按相对顺序出现在 actual 中即真
     */
    private static boolean isSubsequence(List<String> expected, List<String> actual) {
        int cursor = 0;
        for (String label : actual) {
            if (cursor < expected.size() && expected.get(cursor).equals(label)) {
                cursor++;
            }
        }
        return cursor == expected.size();
    }

    private static void alignMatched(StepAlignment step, List<BaselineStep> baseSteps, List<InteractionRecord> newRecords, DeterministicComparator comparator, InvocationRulesConfig rules) {
        int paired = Math.min(baseSteps.size(), newRecords.size());
        step.setSurplusCount(Math.abs(baseSteps.size() - newRecords.size()));
        step.setBaselineRecordId(baseSteps.get(0).getRecordId());
        step.setNewRecordId(newRecords.get(0).getRecordId());

        ComparisonResult firstComparison = null;
        for (int i = 0; i < paired; i++) {
            BaselineStep b = baseSteps.get(i);
            InteractionRecord n = newRecords.get(i);
            ComparisonResult comparison = comparator.compare(b.getFingerprint(), FingerprintExtractor.extract(n, rules, n.getInvocationId()), n.getModelResponse());
            if (firstComparison == null) {
                firstComparison = comparison;
            }
            if (comparison.getVerdict() == Verdict.CHANGED) {
                // 步骤 verdict 取首个 CHANGED 配对（差异明细随之），停止后续配对
                step.setVerdict(Verdict.CHANGED);
                step.setComparison(comparison);
                step.setBaselineModelResponse(b.getSampleOutput());
                step.setNewModelResponse(n.getModelResponse());
                step.setBaselineRecordId(b.getRecordId());
                step.setNewRecordId(n.getRecordId());
                return;
            }
        }
        step.setVerdict(Verdict.PASS);
        step.setComparison(firstComparison);
    }

    private static Map<String, List<InteractionRecord>> groupByInvocation(List<InteractionRecord> records) {
        Map<String, List<InteractionRecord>> groups = new LinkedHashMap<>();
        for (InteractionRecord record : records) {
            String key = record.getInvocationKey();
            if (key == null || key.isEmpty()) {
                continue;
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record);
        }
        return groups;
    }

    private static boolean hasSessionPrefix(TaskChain chain) {
        for (InteractionRecord record : chain.getRecords()) {
            if (record.getTurnIndex() > 0 || (record.getPreviousTurns() != null && !record.getPreviousTurns().isEmpty())) {
                return true;
            }
        }
        return false;
    }
}
