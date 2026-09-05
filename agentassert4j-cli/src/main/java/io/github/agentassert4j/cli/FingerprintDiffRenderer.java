package io.github.agentassert4j.cli;

import io.github.agentassert4j.model.DeterministicFingerprint;

import java.util.*;

/**
 * 指纹差异渲染 — 把基线与候选指纹的逐维度差异渲染成人类可读行。
 *
 * <p>裁决（approve/reject）的依据是候选与基线的差异内容，而 replay 的 summary
 * 是易失的进程输出，裁决常发生在另一进程另一时刻——本渲染器把持久化在画像里的
 * 两份指纹摆到裁决者面前，补上「法官开庭时手里没有卷宗」的断档。
 * 只输出存在差异的维度，全部一致时给出一行确认。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
final class FingerprintDiffRenderer {

    private FingerprintDiffRenderer() {
    }

    /**
     * 渲染基线 → 候选的差异行（每行一条，无差异时恰一行「指纹一致」）。
     */
    static List<String> render(DeterministicFingerprint baseline, DeterministicFingerprint candidate) {
        List<String> lines = new ArrayList<>();

        Set<String> baseCalls = sortedCopy(baseline == null ? null : baseline.getToolCallSet());
        Set<String> candCalls = sortedCopy(candidate == null ? null : candidate.getToolCallSet());
        if (!baseCalls.equals(candCalls)) {
            Set<String> added = new TreeSet<>(candCalls);
            added.removeAll(baseCalls);
            Set<String> removed = new TreeSet<>(baseCalls);
            removed.removeAll(candCalls);
            StringBuilder sb = new StringBuilder("Tool set: ").append(baseCalls).append(" → ").append(candCalls);
            if (!added.isEmpty()) {
                sb.append(" (added ").append(added).append(")");
            }
            if (!removed.isEmpty()) {
                sb.append(" (removed ").append(removed).append(")");
            }
            lines.add(sb.toString());
        }

        appendMapDiff(lines, "Param types", baseline == null ? null : baseline.getToolParamTypes(), candidate == null ? null : candidate.getToolParamTypes());

        String baseType = baseline == null ? null : baseline.getOutputContentType();
        String candType = candidate == null ? null : candidate.getOutputContentType();
        if (!String.valueOf(baseType).equals(String.valueOf(candType))) {
            lines.add("Output content type: " + baseType + " → " + candType);
        }

        Set<String> basePaths = sortedCopy(baseline == null ? null : baseline.getOutputFieldPaths());
        Set<String> candPaths = sortedCopy(candidate == null ? null : candidate.getOutputFieldPaths());
        if (!basePaths.equals(candPaths)) {
            Set<String> added = new TreeSet<>(candPaths);
            added.removeAll(basePaths);
            Set<String> removed = new TreeSet<>(basePaths);
            removed.removeAll(candPaths);
            StringBuilder sb = new StringBuilder("Output field set:");
            if (!added.isEmpty()) {
                sb.append(" added ").append(added).append(";");
            }
            if (!removed.isEmpty()) {
                sb.append(" removed ").append(removed).append(";");
            }
            lines.add(sb.toString());
        }

        appendMapDiff(lines, "Field types", baseline == null ? null : baseline.getOutputFieldTypeMap(), candidate == null ? null : candidate.getOutputFieldTypeMap());

        if (baseline != null && candidate != null && baseline.getTextLengthMagnitude() != candidate.getTextLengthMagnitude()) {
            lines.add("Output length magnitude: " + baseline.getTextLengthMagnitude() + " → " + candidate.getTextLengthMagnitude());
        }

        appendSetDiff(lines, "Required keywords", baseline == null ? null : baseline.getRequiredKeywords(), candidate == null ? null : candidate.getRequiredKeywords());
        appendSetDiff(lines, "Forbidden keywords", baseline == null ? null : baseline.getForbiddenKeywords(), candidate == null ? null : candidate.getForbiddenKeywords());

        int baseRegex = baseline == null || baseline.getRegexPatterns() == null ? 0 : baseline.getRegexPatterns().size();
        int candRegex = candidate == null || candidate.getRegexPatterns() == null ? 0 : candidate.getRegexPatterns().size();
        if (baseRegex != candRegex) {
            lines.add("Regex rule count: " + baseRegex + " → " + candRegex);
        }

        appendSetDiff(lines, "Declared behaviors", baseline == null ? null : baseline.getDeclaredBehaviors(), candidate == null ? null : candidate.getDeclaredBehaviors());

        if (baseline != null && candidate != null && baseline.isHasError() != candidate.isHasError()) {
            lines.add("Error marker: " + (baseline.isHasError() ? "yes" : "no") + " → " + (candidate.isHasError() ? "yes" : "no"));
        }

        if (lines.isEmpty()) {
            lines.add("Candidate fingerprint matches the baseline in all dimensions.");
        }
        return lines;
    }

    private static void appendMapDiff(List<String> lines, String label, Map<String, String> base, Map<String, String> cand) {
        Map<String, String> baseSorted = base == null ? new TreeMap<String, String>() : new TreeMap<String, String>(base);
        Map<String, String> candSorted = cand == null ? new TreeMap<String, String>() : new TreeMap<String, String>(cand);
        if (baseSorted.equals(candSorted)) {
            return;
        }
        StringBuilder sb = new StringBuilder(label).append(":");
        for (Map.Entry<String, String> entry : baseSorted.entrySet()) {
            String candValue = candSorted.get(entry.getKey());
            if (candValue == null) {
                sb.append(" removed ").append(entry.getKey()).append(":").append(entry.getValue()).append(";");
            } else if (!candValue.equals(entry.getValue())) {
                sb.append(" ").append(entry.getKey()).append("(").append(entry.getValue()).append("→").append(candValue).append(");");
            }
        }
        for (Map.Entry<String, String> entry : candSorted.entrySet()) {
            if (!baseSorted.containsKey(entry.getKey())) {
                sb.append(" added ").append(entry.getKey()).append(":").append(entry.getValue()).append(";");
            }
        }
        lines.add(sb.toString());
    }

    private static void appendSetDiff(List<String> lines, String label, Set<String> base, Set<String> cand) {
        Set<String> baseSorted = sortedCopy(base);
        Set<String> candSorted = sortedCopy(cand);
        if (!baseSorted.equals(candSorted)) {
            lines.add(label + ": " + baseSorted + " → " + candSorted);
        }
    }

    private static Set<String> sortedCopy(Set<String> set) {
        return set == null ? new TreeSet<String>() : new TreeSet<String>(set);
    }
}
