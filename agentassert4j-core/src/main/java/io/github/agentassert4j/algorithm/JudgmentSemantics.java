package io.github.agentassert4j.algorithm;

/**
 * 判定语义版本 —— 确定性判定引擎（分组规则、指纹定义、比较器裁决规则）的语义版本戳。
 *
 * <p>每个基线画像在建立/批准时被盖上当前版本并随归档行留痕；重放入口校验基线版本与
 * 引擎版本一致，不一致（含未标记的历史行）即拒绝判定——防止算法升级后静默重解释
 * 用户已批准的历史基线。</p>
 *
 * <p><b>何时必须递增版本</b>：任何会改变「同样行为差异得出什么判定结论」的变更——
 * 指纹维度口径（FingerprintExtractor）、分组键派生规则（DeterministicSkillGrouper）、
 * 比较器裁决矩阵与评分权重（DeterministicComparator）。仅增强捕获保真度（新增遥测列、
 * 转义修正）或纯性能优化不改变判定结论，不递增。</p>
 *
 * <p>版本值一经发布即不可重定义：同一版本号下的判定语义永不改变。</p>
 *
 * @author axy-yxa
 * @since 2026-08-28
 */
public final class JudgmentSemantics {

    /**
     * 当前判定语义版本
     */
    public static final String VERSION = "det-v1";

    private JudgmentSemantics() {
    }
}
