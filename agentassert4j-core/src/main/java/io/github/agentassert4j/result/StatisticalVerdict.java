package io.github.agentassert4j.result;

/**
 * 统计判定结果 — 仅在统计模式（sampleCount > 1）时使用。
 *
 * <p>统计模式下的判定逻辑：</p>
 * <ul>
 *   <li>STABLE：PASS 率 >= passThreshold，且 REGRESSION 率 <= regressionTolerance</li>
 *   <li>UNSTABLE：PASS 率 < passThreshold，但 REGRESSION 率 <= regressionTolerance</li>
 *   <li>FLAKY：REGRESSION 率 > regressionTolerance（存在严重退化风险）</li>
 * </ul>
 */
public enum StatisticalVerdict {

    /**
     * 稳定：行为高度一致，PASS 率达标。对应单次模式的 PASS。
     */
    STABLE,

    /**
     * 不稳定：行为存在波动，但未出现严重退化。对应单次模式的 DIFF。
     */
    UNSTABLE,

    /**
     * 脆弱：存在严重退化风险，REGRESSION 比例超标。对应单次模式的 REGRESSION。
     */
    FLAKY
}
