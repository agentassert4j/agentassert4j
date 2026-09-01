package io.github.agentassert4j.algorithm;

import io.github.agentassert4j.model.InteractionRecord;
import io.github.agentassert4j.model.InvocationProfile;
import io.github.agentassert4j.model.InvocationType;
import io.github.agentassert4j.util.HashUtil;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 调用点解析器 — 从录制交互解析调用点身份（invocationKey 派生规则冻结为身份契约）。
 *
 * <p>invocationKey 派生（优先级从高到低，任一命中即停）：
 * <ol>
 *   <li><b>声明锚点</b>：记录声明了 invocationId → {@code invocation:<label>}，
 *       有模板时以 {@code :<细分哈希>} 细分同一标签内的多模板调用位置——细分哈希
 *       优先骨架哈希（动态模板下同一调用点不再随组装漂移裂键），无骨架退全文哈希；</li>
 *   <li><b>骨架锚点</b>：未声明但有骨架 → {@code skeleton:<skeletonHash>}——
 *       骨架是动态段替换为稳定占位符的模板形态，同骨架异全文同键；</li>
 *   <li><b>模板锚点</b>：未声明无骨架但有模板哈希 → {@code template:<templateHash>}——
 *       工具调用与纯对话同分支，形状（工具名/参数签名）不参与身份，只是视图维度；</li>
 *   <li><b>请求锚点兜底</b>：无声明无骨架无模板 → {@code adhoc:<sha256(modelRequestRaw)>}，
 *       退而 {@code adhoc:<sha256(userInput)>}，双缺省 {@code adhoc:no-anchor}
 *       （防御程序化构造记录）。</li>
 * </ol>
 *
 * <p>骨架哈希取值：骨架文本现算（内存新鲜记录，唯一真源），退记录上的落库投影
 * （存储读侧映射，供 recordCandidate/建档等落库记录重算键不分叉）。键文法对任意
 * 输入单射：所有可控组件经 {@link #encodeComponent} 百分号编码后才参与拼装，文法
 * 结构字符（冒号、加号、方括号、逗号）不可能出自组件内部——任何团队的命名规范都
 * 零约束、零碰撞。</p>
 *
 * <p>invocationKey 是溯源/视图域身份，永不进指纹：指纹维度保持输出侧，输入侧
 * （键、变量、历史）不参与判定，因此 adhoc 分支以输入派生键是合法的。</p>
 *
 * <p>派生规则一经发布即冻结为身份契约：任何变更都等价于身份纪元事件（历史基线
 * 全部失配），必须走显式设计。黄金键测试钉住本类产出的字面键值。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public final class InvocationResolver {

    private InvocationResolver() {
    }

    /**
     * 解析单条交互记录的调用点身份，返回对应的 InvocationProfile（不含指纹）。
     *
     * @param record 交互记录
     * @return 包含 invocationKey、label、invocationName、invocationType、templateHash、
     * paramSignature 的 InvocationProfile
     */
    public static InvocationProfile resolve(InteractionRecord record) {
        String declared = record.getInvocationId();
        boolean hasDeclaration = declared != null && !declared.isEmpty();
        String templateHash = record.getTemplateHash();
        boolean hasTemplate = templateHash != null && !templateHash.isEmpty();
        String skeletonHash = skeletonHashOf(record);
        boolean hasSkeleton = skeletonHash != null;

        boolean hasToolCalls = record.isHasToolCalls() && record.getToolCalls() != null && !record.getToolCalls().isEmpty();

        String invocationKey;
        String invocationName;

        if (hasDeclaration) {
            // 锚点 1：显式声明。业务身份是人的身份，不混入行为形状；细分哈希骨架优先
            String subdivision = hasSkeleton ? skeletonHash : templateHash;
            invocationKey = "invocation:" + encodeComponent(declared) + (subdivision != null ? ":" + encodeComponent(subdivision) : "");
            invocationName = declared;
        } else if (hasSkeleton) {
            // 锚点 2：骨架锚点。动态模板下身份按稳定骨架定格，同骨架异全文同键
            invocationKey = "skeleton:" + encodeComponent(skeletonHash);
            invocationName = "skl:" + skeletonHash.substring(0, Math.min(skeletonHash.length(), 8));
        } else if (hasTemplate) {
            // 锚点 3：模板锚点。系统提示（模板）即调用位置；工具/纯对话同键
            invocationKey = "template:" + encodeComponent(templateHash);
            invocationName = "tpl:" + templateHash.substring(0, Math.min(templateHash.length(), 8));
        } else {
            // 锚点 4：请求锚点兜底（无模板应用：纯手写循环、无 system prompt 的接入）
            String anchor = adhocAnchor(record);
            if (anchor.isEmpty()) {
                invocationKey = "adhoc:no-anchor";
                invocationName = "adhoc";
            } else {
                invocationKey = "adhoc:" + encodeComponent(anchor);
                invocationName = "adhoc:" + anchor.substring(0, Math.min(anchor.length(), 8));
            }
        }

        InvocationProfile profile = new InvocationProfile();
        profile.setInvocationKey(invocationKey);
        profile.setLabel(hasDeclaration ? declared : null);
        profile.setInvocationName(invocationName);
        profile.setInvocationType(hasToolCalls ? InvocationType.TOOL : InvocationType.PURE_CHAT);
        profile.setTemplateHash(hasTemplate ? templateHash : null);
        profile.setParamSignature(hasToolCalls ? paramPairs(record, false).sorted().collect(Collectors.joining(",")) : "");
        return profile;
    }

    /**
     * 骨架哈希：骨架文本现算优先（唯一真源），退落库投影（读侧映射，重算键不分叉）；
     * 双缺返回 null = 该记录无骨架身份
     */
    private static String skeletonHashOf(InteractionRecord record) {
        if (record.getTemplateSkeleton() != null && !record.getTemplateSkeleton().isEmpty()) {
            return HashUtil.sha256(record.getTemplateSkeleton());
        }
        if (record.getSkeletonHash() != null && !record.getSkeletonHash().isEmpty()) {
            return record.getSkeletonHash();
        }
        return null;
    }

    /**
     * 请求锚点：优先请求原文（同请求即同调用位置），退而用户输入，双缺省空串
     * （no-anchor 兜底桶）。键是溯源身份不是判定输入，因此可依赖请求内容。
     */
    private static String adhocAnchor(InteractionRecord record) {
        if (record.getModelRequestRaw() != null && !record.getModelRequestRaw().isEmpty()) {
            return HashUtil.sha256(record.getModelRequestRaw());
        }
        if (record.getUserInput() != null && !record.getUserInput().isEmpty()) {
            return HashUtil.sha256(record.getUserInput());
        }
        return "";
    }

    /**
     * 键组件百分号编码：转义文法结构字符（% : + [ ] ,），
     * 使「组件内容 → 键」的映射对任意输入单射——不同组件组合永不产生同键。
     */
    static String encodeComponent(String component) {
        StringBuilder sb = new StringBuilder(component.length());
        for (int i = 0; i < component.length(); i++) {
            char c = component.charAt(i);
            switch (c) {
                case '%':
                    sb.append("%25");
                    break;
                case ':':
                    sb.append("%3A");
                    break;
                case '+':
                    sb.append("%2B");
                    break;
                case '[':
                    sb.append("%5B");
                    break;
                case ']':
                    sb.append("%5D");
                    break;
                case ',':
                    sb.append("%2C");
                    break;
                default:
                    sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 参数类型的「键:值」对流（视图列）。显示形态（encode=false）保留原文可读；
     * 键形态（encode=true）逐组件编码。归一化 toLowerCase()：SDK 提供 "String"、
     * JSON Schema 提供 "string"、其他栈可能提供 "STRING" → 统一小写；
     * 值可能为 null（存储层反序列化的开放面）：按 "null" 归一，杜绝 NPE 击穿 resolve
     */
    private static Stream<String> paramPairs(InteractionRecord record, boolean encode) {
        if (record.getToolCalls() == null) return Stream.empty();
        return record.getToolCalls().stream().flatMap(tc -> {
            if (tc.getArgTypes() == null) return Stream.empty();
            return tc.getArgTypes().entrySet().stream().map(e -> {
                String key = e.getKey().toLowerCase(Locale.ROOT);
                String value = String.valueOf(e.getValue()).toLowerCase(Locale.ROOT);
                return encode ? encodeComponent(key) + ":" + encodeComponent(value) : key + ":" + value;
            });
        });
    }
}
