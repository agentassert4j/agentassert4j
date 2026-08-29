package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.SkillProfile;
import io.github.agentassert4j.model.SkillType;
import io.github.agentassert4j.model.ToolCall;
import io.github.agentassert4j.util.HashUtil;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 确定性 Skill 分组器 — 身份锚点三级优先级（派生规则冻结为身份契约）。
 *
 * <p>groupKey 派生（优先级从高到低，任一命中即停）：
 * <ol>
 *   <li><b>显式声明</b>：记录声明了业务 skillId → {@code skill:<skillId>}，
 *       有模板时以 {@code :<templateHash>} 细分同一技能内的多模板步骤；</li>
 *   <li><b>模板锚点</b>：未声明但有模板 → {@code chat:<templateHash>}；</li>
 *   <li><b>工具形状派生</b>：未声明且有工具调用 → {@code sorted(toolNames).join("+") + "[" + paramSignature + "]"}。</li>
 * </ol>
 *
 * <p>录制门生效后「未声明且无工具调用的纯对话」不会进入分组；上列兜底分支仅为
 * 程序化构造记录的防御路径。声明记录内的多形状分支不靠身份拆分隔离——形状差异
 * 由指纹对比暴露，这正是回归要抓的对象。</p>
 *
 * <p>派生规则一经发布即冻结为身份契约：任何变更都等价于身份纪元事件（历史基线
 * 全部失配），必须升判定语义版本并走显式设计。黄金键测试钉住本类产出的字面键值。</p>
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
        String declared = record.getSkillId();
        boolean hasDeclaration = declared != null && !declared.isEmpty();
        String templateHash = record.getTemplateHash();
        boolean hasTemplate = templateHash != null && !templateHash.isEmpty();

        String groupKey;
        String skillName;
        SkillType skillType;
        String paramSignature = "";

        boolean hasToolCalls = record.isHasToolCalls() && record.getToolCalls() != null && !record.getToolCalls().isEmpty();

        if (hasDeclaration) {
            // 锚点 1：显式声明。业务身份是人的身份，不混入行为形状
            groupKey = "skill:" + declared + (hasTemplate ? ":" + templateHash : "");
            skillName = declared;
            skillType = hasToolCalls ? SkillType.TOOL_SKILL : SkillType.PURE_CHAT_SKILL;
            paramSignature = deriveParamSignature(record);
        } else if (hasToolCalls) {
            // 锚点 2（对工具调用）：形状派生。未声明的工具记录按调用形状归组（引导发现，status 提示补声明）
            List<ToolCall> calls = record.getToolCalls();

            // 1. 提取所有工具名并排序（多工具调用顺序无关）
            List<String> sortedNames = calls.stream().map(ToolCall::getToolName).sorted().collect(Collectors.toList());

            paramSignature = deriveParamSignature(record);

            // 2. 组合键：toolNames[paramSignature]
            groupKey = String.join("+", sortedNames) + "[" + paramSignature + "]";
            skillName = String.join("+", sortedNames);
            skillType = SkillType.TOOL_SKILL;
        } else {
            // 锚点 3：模板锚点（采集门后不可达的防御分支——未声明纯对话不录入）
            groupKey = "chat:" + (hasTemplate ? templateHash : "");
            skillName = "chat:" + groupKey.substring("chat:".length(), Math.min(groupKey.length(), "chat:".length() + 8));
            skillType = SkillType.PURE_CHAT_SKILL;
        }

        // skillId = groupKey 的 SHA-256 哈希（画像内部标识，与记录上的业务标签分列）
        String skillId = HashUtil.sha256(groupKey);

        SkillProfile profile = new SkillProfile();
        profile.setSkillId(skillId);
        profile.setGroupKey(groupKey);
        profile.setSkillName(skillName);
        profile.setSkillType(skillType);
        profile.setParamSignature(paramSignature);
        return profile;
    }

    private static String deriveParamSignature(InteractionRecord record) {
        if (record.getToolCalls() == null) return "";
        // 归一化 toLowerCase()：SDK 提供 "String"、JSON Schema 提供 "string"、
        // 其他栈可能提供 "STRING" → 统一小写避免同一技能被多分；
        // 值可能为 null（存储层反序列化的开放面）：按 "null" 归一，杜绝 NPE 击穿 enrich
        return record.getToolCalls().stream().flatMap(tc -> {
            if (tc.getArgTypes() == null) return Stream.empty();
            return tc.getArgTypes().entrySet().stream().map(e -> e.getKey().toLowerCase(Locale.ROOT) + ":" + String.valueOf(e.getValue()).toLowerCase(Locale.ROOT));
        }).sorted().collect(Collectors.joining(","));
    }
}
