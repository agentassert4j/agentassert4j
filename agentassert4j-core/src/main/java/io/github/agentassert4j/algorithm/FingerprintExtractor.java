package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.InvocationRulesConfig;
import io.github.agentassert4j.model.DeterministicFingerprint;
import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.util.RecursiveJsonParser;
import io.github.agentassert4j.util.TextUtil;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 行为指纹提取器 — 从交互记录中提取四维度确定性指纹。
 *
 * <p>维度 1-2 全自动提取，维度 3-4 从声明式规则加载。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class FingerprintExtractor {

    private FingerprintExtractor() {
    }

    /**
     * 从交互记录提取确定性行为指纹，并从 InvocationRulesConfig 注入维度 3-4 的
     * 声明式规则。
     *
     * <p>规则查找键是记录上的业务标签；未声明调用点（无标签）统一落到空键——
     * 调用方可用空键为未声明调用点统一注入断言。rules 传 null 时维度 3-4
     * 保持空集口径（无规则声明即无该维断言）。</p>
     *
     * @param record       交互记录
     * @param rules        规则配置（null 时维度 3-4 为空集）
     * @param invocationId 声明标签（可为 null，视同空键）
     * @return 四维度确定性指纹
     */
    public static DeterministicFingerprint extract(InteractionRecord record, InvocationRulesConfig rules, String invocationId) {
        DeterministicFingerprint fp = new DeterministicFingerprint();

        extractDimension1(record, fp);
        extractDimension2(record, fp);

        if (rules == null) {
            fp.setRequiredKeywords(Collections.emptySet());
            fp.setForbiddenKeywords(Collections.emptySet());
            fp.setRegexPatterns(Collections.emptyList());
            fp.setDeclaredBehaviors(Collections.emptySet());
        } else {
            InvocationRulesConfig.InvocationRule rule = rules.getRulesForInvocation(invocationId != null ? invocationId : "");
            fp.setRequiredKeywords(rule.getRequiredKeywords());
            fp.setForbiddenKeywords(rule.getForbiddenKeywords());
            fp.setRegexPatterns(rule.getRegexPatterns());
            fp.setDeclaredBehaviors(rule.getBehaviors());
        }

        fp.setHasError(record.getToolCalls() != null && record.getToolCalls().stream().anyMatch(tc -> !tc.isSuccess()));
        return fp;
    }

    private static void extractDimension1(InteractionRecord record, DeterministicFingerprint fp) {
        if (record.getToolCalls() == null || record.getToolCalls().isEmpty()) {
            fp.setToolCallSet(Collections.emptySet());
            fp.setToolParamTypes(Collections.emptyMap());
            return;
        }

        // toolCallSet：忽略顺序
        Set<String> toolCallSet = record.getToolCalls().stream().map(ToolCall::getToolName).collect(Collectors.toSet());
        fp.setToolCallSet(toolCallSet);

        // toolParamTypes：合并所有工具的参数类型
        // 归一化 toLowerCase()：确保存储层反序列化后的比较一致
        // （与 InvocationResolver 的 paramSignature 归一化策略对齐）
        Map<String, String> paramTypes = new HashMap<>();
        for (ToolCall tc : record.getToolCalls()) {
            if (tc.getArgTypes() != null) {
                tc.getArgTypes().forEach((k, v) -> paramTypes.put(k.toLowerCase(Locale.ROOT), v.toLowerCase(Locale.ROOT)));
            }
        }
        fp.setToolParamTypes(paramTypes);
    }

    private static void extractDimension2(InteractionRecord record, DeterministicFingerprint fp) {
        String response = record.getModelResponse();
        if (TextUtil.isBlank(response)) {
            fp.setOutputContentType("text/plain");
            fp.setOutputFieldPaths(Collections.emptySet());
            fp.setOutputFieldTypeMap(Collections.emptyMap());
            fp.setTextLengthMagnitude(0);
            return;
        }

        Object json = RecursiveJsonParser.parse(response);

        if (json instanceof Map || json instanceof List) {
            // JSON 输出
            fp.setOutputContentType("application/json");
            fp.setOutputFieldPaths(RecursiveJsonParser.extractFieldPaths(json));
            fp.setOutputFieldTypeMap(RecursiveJsonParser.extractFieldTypeMap(json));
            fp.setTextLengthMagnitude(0);
        } else {
            // 非 JSON 输出（纯文本）
            fp.setOutputContentType("text/plain");
            fp.setOutputFieldPaths(Collections.emptySet());
            fp.setOutputFieldTypeMap(Collections.emptyMap());
            // 纯文本退化为长度数量级检测
            // log10(length) + 1：1-9字→1, 10-99字→2, 100-999字→3
            int len = response.length();
            fp.setTextLengthMagnitude(len == 0 ? 0 : (int) Math.log10(Math.max(1, len)) + 1);
        }
    }
}
