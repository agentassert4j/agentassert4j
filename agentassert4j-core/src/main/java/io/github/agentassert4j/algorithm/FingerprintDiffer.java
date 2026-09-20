package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.result.FingerprintDiff;
import io.github.agentassert4j.result.FingerprintDiffDimension;
import io.github.agentassert4j.result.FingerprintDimensionChange;

import java.util.*;

/**
 * 指纹差异计算器 — 基线与候选指纹逐维对照的唯一实现。
 *
 * <p>裁决（accept/reject）的依据是候选与基线的差异内容；本计算器把
 * 对照结果结构化为 {@link FingerprintDiff}，人读渲染与机器报告共享同一
 * 产出——两侧各自实现比较规则会静默分叉。逐维判定规则：集合与映射按
 * 字典序整体相等比较；标量按字符串等值；长度量级与错误标记两侧均在场
 * 才参与比较（单侧 null 不是差异证据）。null 指纹按空指纹处理。</p>
 *
 * @author axy-yxa
 * @since 2026-09-20
 */
public final class FingerprintDiffer {

    private FingerprintDiffer() {
    }

    /**
     * 计算基线 → 候选的逐维差异。
     */
    public static FingerprintDiff diff(DeterministicFingerprint baseline, DeterministicFingerprint candidate) {
        FingerprintDiff diff = new FingerprintDiff();
        List<FingerprintDimensionChange> changes = diff.getChanges();

        diffSets(changes, FingerprintDiffDimension.TOOL_SET, baseline == null ? null : baseline.getToolCallSet(), candidate == null ? null : candidate.getToolCallSet());
        diffMaps(changes, FingerprintDiffDimension.PARAM_TYPES, baseline == null ? null : baseline.getToolParamTypes(), candidate == null ? null : candidate.getToolParamTypes());
        diffScalars(changes, FingerprintDiffDimension.OUTPUT_CONTENT_TYPE, baseline == null ? null : baseline.getOutputContentType(), candidate == null ? null : candidate.getOutputContentType());
        diffFieldPaths(changes, baseline, candidate);
        diffMaps(changes, FingerprintDiffDimension.FIELD_TYPES, baseline == null ? null : baseline.getOutputFieldTypeMap(), candidate == null ? null : candidate.getOutputFieldTypeMap());
        diffLengthMagnitude(changes, baseline, candidate);
        diffSets(changes, FingerprintDiffDimension.REQUIRED_KEYWORDS, baseline == null ? null : baseline.getRequiredKeywords(), candidate == null ? null : candidate.getRequiredKeywords());
        diffSets(changes, FingerprintDiffDimension.FORBIDDEN_KEYWORDS, baseline == null ? null : baseline.getForbiddenKeywords(), candidate == null ? null : candidate.getForbiddenKeywords());
        diffRegexCount(changes, baseline, candidate);
        diffSets(changes, FingerprintDiffDimension.DECLARED_BEHAVIORS, baseline == null ? null : baseline.getDeclaredBehaviors(), candidate == null ? null : candidate.getDeclaredBehaviors());
        diffErrorMarker(changes, baseline, candidate);
        return diff;
    }

    private static void diffSets(List<FingerprintDimensionChange> changes, FingerprintDiffDimension dimension, Set<String> base, Set<String> cand) {
        Set<String> baseSorted = sortedCopy(base);
        Set<String> candSorted = sortedCopy(cand);
        if (baseSorted.equals(candSorted)) {
            return;
        }
        FingerprintDimensionChange change = new FingerprintDimensionChange();
        change.setDimension(dimension);
        change.setBaselineView(baseSorted.toString());
        change.setCandidateView(candSorted.toString());
        change.getAdded().addAll(sortedDiff(candSorted, baseSorted));
        change.getRemoved().addAll(sortedDiff(baseSorted, candSorted));
        changes.add(change);
    }

    private static void diffFieldPaths(List<FingerprintDimensionChange> changes, DeterministicFingerprint baseline, DeterministicFingerprint candidate) {
        diffSets(changes, FingerprintDiffDimension.OUTPUT_FIELDS, baseline == null ? null : baseline.getOutputFieldPaths(), candidate == null ? null : candidate.getOutputFieldPaths());
    }

    private static void diffMaps(List<FingerprintDimensionChange> changes, FingerprintDiffDimension dimension, Map<String, String> base, Map<String, String> cand) {
        Map<String, String> baseSorted = base == null ? new TreeMap<String, String>() : new TreeMap<String, String>(base);
        Map<String, String> candSorted = cand == null ? new TreeMap<String, String>() : new TreeMap<String, String>(cand);
        if (baseSorted.equals(candSorted)) {
            return;
        }
        FingerprintDimensionChange change = new FingerprintDimensionChange();
        change.setDimension(dimension);
        change.setBaselineView(viewOf(baseSorted));
        change.setCandidateView(viewOf(candSorted));
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<String, String> entry : baseSorted.entrySet()) {
            String candValue = candSorted.get(entry.getKey());
            if (candValue == null) {
                change.getRemoved().add(entry.getKey() + ":" + entry.getValue());
                detail.append(" removed ").append(entry.getKey()).append(":").append(entry.getValue()).append(";");
            } else if (!candValue.equals(entry.getValue())) {
                change.getChanged().add(entry.getKey() + "(" + entry.getValue() + "→" + candValue + ")");
                detail.append(" ").append(entry.getKey()).append("(").append(entry.getValue()).append("→").append(candValue).append(");");
            }
        }
        for (Map.Entry<String, String> entry : candSorted.entrySet()) {
            if (!baseSorted.containsKey(entry.getKey())) {
                change.getAdded().add(entry.getKey() + ":" + entry.getValue());
                detail.append(" added ").append(entry.getKey()).append(":").append(entry.getValue()).append(";");
            }
        }
        change.setDetail(detail.toString());
        changes.add(change);
    }

    private static void diffScalars(List<FingerprintDimensionChange> changes, FingerprintDiffDimension dimension, String base, String cand) {
        if (String.valueOf(base).equals(String.valueOf(cand))) {
            return;
        }
        FingerprintDimensionChange change = new FingerprintDimensionChange();
        change.setDimension(dimension);
        change.setBaselineView(String.valueOf(base));
        change.setCandidateView(String.valueOf(cand));
        changes.add(change);
    }

    private static void diffLengthMagnitude(List<FingerprintDimensionChange> changes, DeterministicFingerprint baseline, DeterministicFingerprint candidate) {
        if (baseline == null || candidate == null || baseline.getTextLengthMagnitude() == candidate.getTextLengthMagnitude()) {
            return;
        }
        FingerprintDimensionChange change = new FingerprintDimensionChange();
        change.setDimension(FingerprintDiffDimension.OUTPUT_LENGTH_MAGNITUDE);
        change.setBaselineView(String.valueOf(baseline.getTextLengthMagnitude()));
        change.setCandidateView(String.valueOf(candidate.getTextLengthMagnitude()));
        changes.add(change);
    }

    private static void diffRegexCount(List<FingerprintDimensionChange> changes, DeterministicFingerprint baseline, DeterministicFingerprint candidate) {
        int baseRegex = baseline == null || baseline.getRegexPatterns() == null ? 0 : baseline.getRegexPatterns().size();
        int candRegex = candidate == null || candidate.getRegexPatterns() == null ? 0 : candidate.getRegexPatterns().size();
        if (baseRegex == candRegex) {
            return;
        }
        FingerprintDimensionChange change = new FingerprintDimensionChange();
        change.setDimension(FingerprintDiffDimension.REGEX_RULE_COUNT);
        change.setBaselineView(String.valueOf(baseRegex));
        change.setCandidateView(String.valueOf(candRegex));
        changes.add(change);
    }

    private static void diffErrorMarker(List<FingerprintDimensionChange> changes, DeterministicFingerprint baseline, DeterministicFingerprint candidate) {
        if (baseline == null || candidate == null || baseline.isHasError() == candidate.isHasError()) {
            return;
        }
        FingerprintDimensionChange change = new FingerprintDimensionChange();
        change.setDimension(FingerprintDiffDimension.ERROR_MARKER);
        change.setBaselineView(baseline.isHasError() ? "yes" : "no");
        change.setCandidateView(candidate.isHasError() ? "yes" : "no");
        changes.add(change);
    }

    private static Set<String> sortedCopy(Set<String> set) {
        return set == null ? new TreeSet<String>() : new TreeSet<String>(set);
    }

    private static List<String> sortedDiff(Set<String> from, Set<String> minus) {
        Set<String> copy = new TreeSet<String>(from);
        copy.removeAll(minus);
        return new ArrayList<String>(copy);
    }

    private static String viewOf(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(";");
            }
            sb.append(entry.getKey()).append(":").append(entry.getValue());
        }
        return sb.toString();
    }
}
