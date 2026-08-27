package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.SkillType;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.util.HashUtil;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 确定性 Skill 分组器 — 基于 toolName + paramSignature 的确定性分组。
 *
 * <p>分组规则（完全确定性）：
 * <ul>
 *   <li>有工具调用：sorted(toolNames).join("+") + "[" + paramSignature + "]"</li>
 *   <li>无工具调用："chat:" + templateHash（无模板时回退 SHA-256(userInput)，双缺失为 "chat:no-anchor"）</li>
 * </ul>
 *
 * <p>paramSignature 归一化：所有类型名 toLowerCase()，
 * 确保不同来源（SDK/JSON Schema/推断）生成相同签名。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class DeterministicSkillGrouper {

    private DeterministicSkillGrouper() {
    }

    /**
     * 对单条交互记录进行分组，返回对应的 SkillProfile（不含指纹）。
     *
     * @param record 交互记录
     * @return 包含 groupKey、skillId、skillName、skillType、paramSignature 的 SkillProfile
     */
    public static SkillProfile group(InteractionRecord record) {
        String groupKey;
        String skillName;
        SkillType skillType;
        String paramSignature = "";

        if (record.isHasToolCalls() && record.getToolCalls() != null && !record.getToolCalls().isEmpty()) {
            // 有工具调用：TOOL_SKILL
            List<ToolCall> calls = record.getToolCalls();

            // 1. 提取所有工具名并排序（多工具调用顺序无关）
            List<String> sortedNames = calls.stream().map(ToolCall::getToolName).sorted().collect(Collectors.toList());

            // 2. 构建参数类型签名（所有工具的参数类型合并排序）
            //    归一化 toLowerCase()：SDK 提供 "String"、JSON Schema 提供 "string"、
            //    LangChain4j 可能提供 "STRING" → 统一小写避免同一 Skill 被多分
            paramSignature = calls.stream().flatMap(tc -> {
                if (tc.getArgTypes() == null) return Stream.empty();
                return tc.getArgTypes().entrySet().stream().map(e -> e.getKey().toLowerCase() + ":" + e.getValue().toLowerCase());
            }).sorted().collect(Collectors.joining(","));

            // 3. 组合键：toolNames[paramSignature]
            groupKey = String.join("+", sortedNames) + "[" + paramSignature + "]";
            skillName = String.join("+", sortedNames);
            skillType = SkillType.TOOL_SKILL;
        } else {
            // 无工具调用：纯对话。锚点优先级（template_hash 为 null 时不得坍缩为 "chat:null"）：
            // template_hash（三元组语义主锚点）→ user_input hash（无模板时兜底）→ 稳定孤儿键
            String anchor = record.getTemplateHash();
            if (anchor == null || anchor.isEmpty()) {
                anchor = record.getUserInput() != null && !record.getUserInput().isEmpty() ? HashUtil.sha256(record.getUserInput()) : "no-anchor";
            }
            groupKey = "chat:" + anchor;
            skillName = "chat:" + anchor.substring(0, Math.min(8, anchor.length()));
            skillType = SkillType.PURE_CHAT_SKILL;
        }

        // skillId = groupKey 的 SHA-256 哈希
        String skillId = HashUtil.sha256(groupKey);

        SkillProfile profile = new SkillProfile();
        profile.setSkillId(skillId);
        profile.setGroupKey(groupKey);
        profile.setSkillName(skillName);
        profile.setSkillType(skillType);
        profile.setParamSignature(paramSignature);
        return profile;
    }
}
