package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.TaskChain;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.TaskAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepAlignment;
import io.github.agentassert4j.result.TaskAlignment.StepKind;
import io.github.agentassert4j.result.Verdict;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务对齐器 — 基线链 × 新链按调用点对齐的逐步判定（纯比较，零 LLM 调用）。
 *
 * <p>对齐键 = invocationKey：两侧各按调用点键分组（组内规范序），三方分类
 * matched/missing/added；matched 组内 1:1 规范序配对（较少侧配对，富余计数进
 * 报告不判差异），每对两侧指纹现场重提后经注入的对比器判定——不消费任何存档
 * 指纹。缺步骤/新增步骤是行为差异，与配对 CHANGED 同归入链级 CHANGED。</p>
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
        TaskAlignment alignment = new TaskAlignment();
        alignment.setBaselineTime(baseline.firstTimestamp());
        alignment.setNewChainTime(newChain.firstTimestamp());
        alignment.setPrefixDependent(hasSessionPrefix(baseline) || hasSessionPrefix(newChain));

        Map<String, List<InteractionRecord>> baselineGroups = groupByInvocation(baseline.getRecords());
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
            List<InteractionRecord> baseRecords = baselineGroups.get(key);
            List<InteractionRecord> newRecords = newGroups.get(key);
            StepAlignment step = new StepAlignment();
            step.setInvocationKey(key);
            if (baseRecords == null) {
                step.setKind(StepKind.ADDED);
                step.setNewRecordId(newRecords.get(0).getRecordId());
                anyChanged = true;
            } else if (newRecords == null) {
                step.setKind(StepKind.MISSING);
                step.setBaselineRecordId(baseRecords.get(0).getRecordId());
                anyChanged = true;
            } else {
                step.setKind(StepKind.MATCHED);
                alignMatched(step, baseRecords, newRecords, comparator, rules);
                if (step.getVerdict() == Verdict.CHANGED) {
                    anyChanged = true;
                }
            }
            alignment.getSteps().add(step);
        }

        alignment.setVerdict(anyChanged ? Verdict.CHANGED : Verdict.PASS);
        return alignment;
    }

    private static void alignMatched(StepAlignment step, List<InteractionRecord> baseRecords, List<InteractionRecord> newRecords, DeterministicComparator comparator, InvocationRulesConfig rules) {
        int paired = Math.min(baseRecords.size(), newRecords.size());
        step.setSurplusCount(Math.abs(baseRecords.size() - newRecords.size()));
        step.setBaselineRecordId(baseRecords.get(0).getRecordId());
        step.setNewRecordId(newRecords.get(0).getRecordId());

        ComparisonResult firstComparison = null;
        for (int i = 0; i < paired; i++) {
            InteractionRecord b = baseRecords.get(i);
            InteractionRecord n = newRecords.get(i);
            ComparisonResult comparison = comparator.compare(FingerprintExtractor.extract(b, rules, b.getInvocationId()), FingerprintExtractor.extract(n, rules, n.getInvocationId()), n.getModelResponse());
            if (firstComparison == null) {
                firstComparison = comparison;
            }
            if (comparison.getVerdict() == Verdict.CHANGED) {
                // 步骤 verdict 取首个 CHANGED 配对（差异明细随之），停止后续配对
                step.setVerdict(Verdict.CHANGED);
                step.setComparison(comparison);
                step.setBaselineModelResponse(b.getModelResponse());
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
