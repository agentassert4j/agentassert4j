package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.RegexPattern;
import io.github.agentassert4j.result.ComparisonResult;
import io.github.agentassert4j.result.Verdict;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 确定性对比器 — 四维度加权评分 + 确定性判定。
 *
 * <p>判定矩阵：
 * <ul>
 *   <li>新增 error 类字段 → REGRESSION（自动）</li>
 *   <li>score >= 0.95 且无核心字段删除 → PASS</li>
 *   <li>核心字段删除 || 工具集变化 || 参数类型变化 → REGRESSION</li>
 *   <li>0.70 <= score < 0.95 → DIFF</li>
 *   <li>score < 0.70 → REGRESSION</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public class DeterministicComparator {

    // 自动触发 REGRESSION 的字段名（无论用户是否配置为可忽略）
    private static final Set<String> AUTO_REGRESSION_FIELDS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList("error", "errormessage", "error_code", "errorcode", "err", "exception", "error_msg", "errormsg", "fail_reason", "failreason")));
    private final ComparatorConfig config;

    public DeterministicComparator() {
        this(ComparatorConfig.defaults());
    }

    public DeterministicComparator(ComparatorConfig config) {
        this.config = config != null ? config : ComparatorConfig.defaults();
    }

    /**
     * 对比基线指纹与当前指纹，返回对比结果。
     *
     * @param baseline      基线指纹
     * @param current       当前指纹
     * @param currentOutput 当前输出文本（用于内容规则校验）
     * @return 对比结果（含评分、判定、差异详情）
     */
    public ComparisonResult compare(DeterministicFingerprint baseline, DeterministicFingerprint current, String currentOutput) {
        String output = currentOutput != null ? currentOutput : "";
        ComparisonResult r = new ComparisonResult();

        // === 维度 1：工具调用（40%）===
        boolean toolMatch = baseline.getToolCallSet().equals(current.getToolCallSet());
        boolean paramMatch = baseline.getToolParamTypes().equals(current.getToolParamTypes());
        r.setToolCallMatch(toolMatch);
        r.setParamTypeMatch(paramMatch);
        double d1 = (toolMatch ? 0.7 : 0.0) + (paramMatch ? 0.3 : 0.0);

        // === 维度 2：输出结构（25%）===
        // 自动回归检测使用"未过滤"字段集：用户把 error 配置为可忽略时，
        // 过滤后的 added 集合不再含 error，会静默击穿下方不变量。故先存原始集合。
        Set<String> bFieldsRaw = baseline.getOutputFieldPaths();
        Set<String> cFieldsRaw = current.getOutputFieldPaths();
        Set<String> bFields = filterIgnorable(bFieldsRaw);
        Set<String> cFields = filterIgnorable(cFieldsRaw);
        Set<String> added = new HashSet<>(cFields);
        added.removeAll(bFields);
        Set<String> removed = new HashSet<>(bFields);
        removed.removeAll(cFields);
        r.setAddedFields(added);
        r.setRemovedFields(removed);

        boolean typeOk = baseline.getOutputFieldTypeMap().entrySet().stream().filter(e -> !isIgnorable(e.getKey())).allMatch(e -> e.getValue().equals(current.getOutputFieldTypeMap().get(e.getKey())));
        r.setFieldTypeMatch(typeOk);

        double d2 = computeDimension2(baseline, current, removed, typeOk);

        // === 维度 3：内容规则（动态权重）===
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
            d3 = 1.0; // 无声明规则，不扣分
            r.setKeywordMatch(true);
            r.setRegexMatch(true);
        }

        // === 维度 4：约束行为（动态权重）===
        boolean hasDeclaredBehaviors = !isEmpty(baseline.getDeclaredBehaviors());
        double d4;
        if (hasDeclaredBehaviors) {
            boolean behMatch = BehaviorChecker.checkAll(baseline.getDeclaredBehaviors(), current, output);
            r.setBehaviorMatch(behMatch);
            d4 = behMatch ? 1.0 : 0.0;
        } else {
            d4 = 1.0; // 无声明行为，不扣分
            r.setBehaviorMatch(true);
        }

        // === 动态权重分配 ===
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

        // === 加权综合评分 ===
        double score = d1 * w1 + d2 * w2 + d3 * w3 + d4 * w4;
        r.setScore(score);

        // === 确定性判定 ===
        Set<String> realRemoved = filterIgnorable(removed);

        // 自动回归信号：error 类字段出现（用未过滤集合——无论用户是否配置为可忽略）
        Set<String> rawAdded = new HashSet<>(cFieldsRaw);
        rawAdded.removeAll(bFieldsRaw);
        boolean addedErrorField = rawAdded.stream().anyMatch(f -> AUTO_REGRESSION_FIELDS.contains(f.contains(".") ? f.substring(f.lastIndexOf('.') + 1).toLowerCase() : f.toLowerCase()));

        if (addedErrorField) {
            r.setVerdict(Verdict.REGRESSION);
        } else if (score >= 0.95 && realRemoved.isEmpty()) {
            r.setVerdict(Verdict.PASS);
        } else if (!realRemoved.isEmpty() || !toolMatch || !paramMatch) {
            r.setVerdict(Verdict.REGRESSION);
        } else if (score >= 0.70) {
            r.setVerdict(Verdict.DIFF);
        } else {
            r.setVerdict(Verdict.REGRESSION);
        }

        r.setSummary(buildSummary(r));
        return r;
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
        if (!r.isKeywordMatch()) sb.append(" | 内容规则不匹配");
        if (!r.isRegexMatch()) sb.append(" | 正则规则不匹配");
        if (!r.isBehaviorMatch()) sb.append(" | 行为约束不满足");

        return sb.toString();
    }
}
