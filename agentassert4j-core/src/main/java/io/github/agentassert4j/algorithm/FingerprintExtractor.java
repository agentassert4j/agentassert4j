package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.config.SkillRulesConfig;
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
 */
public final class FingerprintExtractor {

    private FingerprintExtractor() {
    }

    /**
     * 从交互记录中提取确定性行为指纹。
     *
     * @param record 交互记录
     * @return 四维度确定性指纹
     */
    public static DeterministicFingerprint extract(InteractionRecord record) {
        DeterministicFingerprint fp = new DeterministicFingerprint();

        // ====== 维度 1：工具调用（全自动，可靠）======
        extractDimension1(record, fp);

        // ====== 维度 2：输出结构（全自动，使用 RecursiveJsonParser）======
        extractDimension2(record, fp);

        // ====== 维度 3：内容规则（从 rules 配置加载，非自动提取）======
        // 无参 extract() 不传入规则配置，维度 3-4 保持空
        fp.setRequiredKeywords(Collections.emptySet());
        fp.setForbiddenKeywords(Collections.emptySet());
        fp.setRegexPatterns(Collections.emptyList());

        // ====== 维度 4：约束行为（从 rules 配置加载）======
        fp.setDeclaredBehaviors(Collections.emptySet());
        fp.setHasError(record.getToolCalls() != null && record.getToolCalls().stream().anyMatch(tc -> !tc.isSuccess()));

        return fp;
    }

    /**
     * 带规则配置的指纹提取 — 由上层（recorder / CLI）调用，
     * 从 SkillRulesConfig 获取维度 3-4 的声明式规则。
     *
     * @param record  交互记录
     * @param rules   规则配置（null 时维度 3-4 保持空）
     * @param skillId Skill 标识
     * @return 四维度确定性指纹
     */
    public static DeterministicFingerprint extract(InteractionRecord record, SkillRulesConfig rules, String skillId) {
        DeterministicFingerprint fp = extract(record);
        if (rules == null || skillId == null) {
            return fp;
        }
        SkillRulesConfig.SkillRule rule = rules.getRulesForSkill(skillId);
        fp.setRequiredKeywords(rule.getRequiredKeywords());
        fp.setForbiddenKeywords(rule.getForbiddenKeywords());
        fp.setRegexPatterns(rule.getRegexPatterns());
        fp.setDeclaredBehaviors(rule.getBehaviors());
        return fp;
    }

    private static void extractDimension1(InteractionRecord record, DeterministicFingerprint fp) {
        if (record.getToolCalls() == null || record.getToolCalls().isEmpty()) {
            fp.setToolCallSet(Collections.emptySet());
            fp.setToolParamTypes(Collections.emptyMap());
            fp.setToolParamRequired(Collections.emptyMap());
            return;
        }

        // toolCallSet：忽略顺序
        Set<String> toolCallSet = record.getToolCalls().stream().map(ToolCall::getToolName).collect(Collectors.toSet());
        fp.setToolCallSet(toolCallSet);

        // toolParamTypes：合并所有工具的参数类型
        // 归一化 toLowerCase()：确保存储层反序列化后的比较一致
        // （与 DeterministicSkillGrouper 的 paramSignature 归一化策略对齐）
        Map<String, String> paramTypes = new HashMap<>();
        for (ToolCall tc : record.getToolCalls()) {
            if (tc.getArgTypes() != null) {
                tc.getArgTypes().forEach((k, v) -> paramTypes.put(k.toLowerCase(), v.toLowerCase()));
            }
        }
        fp.setToolParamTypes(paramTypes);

        // toolParamRequired：暂无数据来源，默认全部 false
        // TODO: 待 SDK 接入层提供 required 信息后填充
        Map<String, Boolean> paramRequired = new LinkedHashMap<>();
        for (String key : paramTypes.keySet()) {
            paramRequired.put(key, false);
        }
        fp.setToolParamRequired(paramRequired);
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
