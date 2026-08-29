package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 确定性对比器 — 逐维差异比对 + 二值判定。
 *
 * <p>判定 = 在 ignorableFields 归一化下，任一维度存在可行动差异即 CHANGED，否则 PASS。
 * 加权评分仅作展示参考（多差异场景的排序辅助），不参与任何分支判断；
 * 逐维差异清单是唯一的诊断输出，「严重程度」由裁决人读差异列表自行得出——
 * 基线调工具 A、当前调工具 B，程序无从判断方向，只如实报告「有差异」。</p>
 *
 * <p>输出结构维的口径：字段集增删、字段类型变化、contentType 变化、纯文本长度数量级
 * 变化，任一出现即为该维差异。内容规则与约束行为是「基线声明、当前答卷」：
 * 基线指纹携带声明，对当前输出文本校验。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class DeterministicComparator {

    private final ComparatorConfig config;

    public DeterministicComparator(ComparatorConfig config) {
        this.config = config != null ? config : ComparatorConfig.defaults();
    }

    /**
     * 对比基线指纹与当前指纹，返回对比结果。
     *
     * @param baseline      基线指纹
     * @param current       当前指纹
     * @param currentOutput 当前输出文本（用于内容规则校验）
     * @return 对比结果（含逐维差异、展示用评分、判定）
     */
    public ComparisonResult compare(DeterministicFingerprint baseline, DeterministicFingerprint current, String currentOutput) {
        String output = currentOutput != null ? currentOutput : "";
        ComparisonResult r = new ComparisonResult();

        // 集合字段统一空集兜底：程序化构造的指纹允许缺省集合字段，比较路径不应 NPE
        Set<String> bTools = orEmpty(baseline.getToolCallSet());
        Set<String> cTools = orEmpty(current.getToolCallSet());
        Map<String, String> bParams = orEmptyMap(baseline.getToolParamTypes());
        Map<String, String> cParams = orEmptyMap(current.getToolParamTypes());

        // === 维度 1：工具调用 ===
        boolean toolMatch = bTools.equals(cTools);
        boolean paramMatch = bParams.equals(cParams);
        r.setToolCallMatch(toolMatch);
        r.setParamTypeMatch(paramMatch);
        double d1 = (toolMatch ? 0.7 : 0.0) + (paramMatch ? 0.3 : 0.0);

        // === 维度 2：输出结构 ===
        Set<String> bFields = filterIgnorable(orEmpty(baseline.getOutputFieldPaths()));
        Set<String> cFields = filterIgnorable(orEmpty(current.getOutputFieldPaths()));
        Set<String> added = new HashSet<>(cFields);
        added.removeAll(bFields);
        Set<String> removed = new HashSet<>(bFields);
        removed.removeAll(cFields);
        r.setAddedFields(added);
        r.setRemovedFields(removed);

        Map<String, String> bTypes = orEmptyMap(baseline.getOutputFieldTypeMap());
        Map<String, String> cTypes = orEmptyMap(current.getOutputFieldTypeMap());
        boolean typeOk = bTypes.entrySet().stream().filter(e -> !isIgnorable(e.getKey())).allMatch(e -> e.getValue().equals(cTypes.get(e.getKey())));
        r.setFieldTypeMatch(typeOk);

        boolean contentTypeOk = equalsOrNull(baseline.getOutputContentType(), current.getOutputContentType());
        boolean magnitudeOk = textMagnitudeOk(baseline, current);
        boolean structureOk = contentTypeOk && magnitudeOk && added.isEmpty() && removed.isEmpty() && typeOk;
        r.setStructureMatch(structureOk);
        double d2 = computeDimension2(baseline, current, removed, typeOk);

        // === 维度 3：内容规则（基线声明、当前答卷）===
        boolean hasDeclaredRules = !isEmpty(baseline.getRequiredKeywords()) || !isEmpty(baseline.getForbiddenKeywords()) || (baseline.getRegexPatterns() != null && !baseline.getRegexPatterns().isEmpty());

        double d3;
        if (hasDeclaredRules) {
            boolean kwOk = baseline.getRequiredKeywords().stream().allMatch(kw -> output.contains(kw));
            boolean fkOk = baseline.getForbiddenKeywords().stream().noneMatch(kw -> output.contains(kw));
            boolean reOk = matchRegexPatterns(baseline.getRegexPatterns(), output);
            r.setKeywordMatch(kwOk && fkOk);
            r.setRegexMatch(reOk);
            d3 = (kwOk ? 0.4 : 0.0) + (fkOk ? 0.3 : 0.0) + (reOk ? 0.3 : 0.0);
        } else {
            d3 = 1.0; // 无声明规则，该维不构成差异
            r.setKeywordMatch(true);
            r.setRegexMatch(true);
        }

        // === 维度 4：约束行为（基线声明、当前答卷）===
        boolean hasDeclaredBehaviors = !isEmpty(baseline.getDeclaredBehaviors());
        double d4;
        if (hasDeclaredBehaviors) {
            boolean behMatch = BehaviorChecker.checkAll(baseline.getDeclaredBehaviors(), current, output);
            r.setBehaviorMatch(behMatch);
            d4 = behMatch ? 1.0 : 0.0;
        } else {
            d4 = 1.0; // 无声明行为，该维不构成差异
            r.setBehaviorMatch(true);
        }

        // === 展示用评分（不参与判定）===
        double w1 = 0.40, w2 = 0.25, w3 = 0.20, w4 = 0.15;
        if (!hasDeclaredRules && !hasDeclaredBehaviors) {
            w1 = 0.60;
            w2 = 0.40;
            w3 = 0;
            w4 = 0;
        } else if (!hasDeclaredRules) {
            w1 = 0.50;
            w2 = 0.30;
            w3 = 0;
            w4 = 0.20;
        } else if (!hasDeclaredBehaviors) {
            w1 = 0.48;
            w2 = 0.30;
            w3 = 0.22;
            w4 = 0;
        }
        r.setScore(d1 * w1 + d2 * w2 + d3 * w3 + d4 * w4);

        // === 二值判定：任一维度存在可行动差异即 CHANGED ===
        boolean anyDiff = !toolMatch || !paramMatch || !structureOk || !r.isKeywordMatch() || !r.isRegexMatch() || !r.isBehaviorMatch();
        r.setVerdict(anyDiff ? Verdict.CHANGED : Verdict.PASS);

        r.setSummary(buildSummary(r));
        return r;
    }

    /**
     * 纯文本响应的长度数量级对比；非纯文本对（或 contentType 缺失）不构成该子维差异。
     */
    private boolean textMagnitudeOk(DeterministicFingerprint baseline, DeterministicFingerprint current) {
        String bType = baseline.getOutputContentType();
        String cType = current.getOutputContentType();
        if (!"text/plain".equals(bType) || !"text/plain".equals(cType)) {
            return true;
        }
        return baseline.getTextLengthMagnitude() == current.getTextLengthMagnitude();
    }

    private static boolean equalsOrNull(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private double computeDimension2(DeterministicFingerprint baseline, DeterministicFingerprint current, Set<String> removed, boolean typeOk) {
        String bType = baseline.getOutputContentType();
        String cType = current.getOutputContentType();

        if ("text/plain".equals(bType) && "text/plain".equals(cType)) {
            // 纯文本退化策略：长度数量级对比
            int bMag = baseline.getTextLengthMagnitude();
            int cMag = current.getTextLengthMagnitude();
            if (bMag == cMag) return 1.0;
            if (Math.abs(bMag - cMag) == 1) return 0.7;
            return 0.2;
        } else if (bType != null && bType.equals(cType)) {
            double d2 = 0;
            d2 += 0.2; // contentType 匹配
            if (removed.isEmpty()) d2 += 0.5;
            if (typeOk) d2 += 0.3;
            return d2;
        } else {
            return 0.0; // contentType 不同
        }
    }

    private boolean matchRegexPatterns(List<RegexPattern> patterns, String output) {
        if (patterns == null || patterns.isEmpty()) return true;
        return patterns.stream().allMatch(p -> p.matches(output));
    }

    private Set<String> filterIgnorable(Set<String> fields) {
        if (fields == null) return Collections.emptySet();
        return fields.stream().filter(f -> !isIgnorable(f)).collect(Collectors.toSet());
    }

    private static Set<String> orEmpty(Set<String> set) {
        return set != null ? set : Collections.<String>emptySet();
    }

    private static Map<String, String> orEmptyMap(Map<String, String> map) {
        return map != null ? map : Collections.<String, String>emptyMap();
    }

    private boolean isIgnorable(String fieldPath) {
        return config.getIgnorableFields().contains(fieldPath);
    }

    private boolean isEmpty(Set<String> set) {
        return set == null || set.isEmpty();
    }

    private String buildSummary(ComparisonResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("score=%.2f verdict=%s", r.getScore(), r.getVerdict()));

        if (r.isToolCallMatch() && r.isParamTypeMatch()) {
            sb.append(" | 工具调用匹配");
        } else {
            if (!r.isToolCallMatch()) sb.append(" | 工具集变化");
            if (!r.isParamTypeMatch()) sb.append(" | 参数类型变化");
        }

        if (r.getAddedFields() != null && !r.getAddedFields().isEmpty()) {
            sb.append(" | 新增字段: ").append(r.getAddedFields());
        }
        if (r.getRemovedFields() != null && !r.getRemovedFields().isEmpty()) {
            sb.append(" | 删除字段: ").append(r.getRemovedFields());
        }
        if (!r.isFieldTypeMatch()) sb.append(" | 字段类型变化");
        if (!r.isKeywordMatch()) sb.append(" | 内容规则不匹配");
        if (!r.isRegexMatch()) sb.append(" | 正则规则不匹配");
        if (!r.isBehaviorMatch()) sb.append(" | 行为约束不满足");

        return sb.toString();
    }
}
