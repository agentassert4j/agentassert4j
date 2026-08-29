package io.github.agentassert4j.result;

/**
 * 统计判定结果 — 仅在统计模式（sampleCount > 1）时使用。
 *
 * <p>统计模式下的判定逻辑（单次判定为二值 PASS/CHANGED，统计层在其上聚合）：</p>
 * <ul>
 *   <li>STABLE：PASS 率 >= passThreshold，且 CHANGED 率 <= regressionTolerance</li>
 *   <li>UNSTABLE：PASS 率 < passThreshold，但 CHANGED 率 <= regressionTolerance
 *       （一致性地偏离基线，非翻转）</li>
 *   <li>FLAKY：CHANGED 率 > regressionTolerance（行为本身在翻转——同输入时对时错）</li>
 *   <li>INSUFFICIENT_SAMPLES：无可用判定样本（零采样或全部为基础设施错误）——
 *       无样本在数学上不可判定，绝不能默认稳定放行</li>
 * </ul>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public enum StatisticalVerdict {

    /**
     * 稳定：行为高度一致，PASS 率达标。对应单次模式的 PASS。
     */
    STABLE,

    /**
     * 不稳定：PASS 率不达标，但偏离一致（CHANGED 占比未超容忍线）。
     */
    UNSTABLE,

    /**
     * 脆弱：行为翻转，CHANGED 比例超过容忍线。
     */
    FLAKY,

    /**
     * 样本不足：无可用判定样本（零采样或全部为超时/API 错误等基础设施错误）。
     */
    INSUFFICIENT_SAMPLES
}
