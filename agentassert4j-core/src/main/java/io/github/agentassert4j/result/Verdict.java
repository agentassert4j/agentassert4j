package io.github.agentassert4j.result;

/**
 * 判定结果枚举。
 */
public enum Verdict {
    /** 通过：加权分 >= 0.95 且无非核心字段删除 */
    PASS,
    /** 有差异但不严重：0.70 <= score < 0.95 */
    DIFF,
    /** 回归：核心字段删除 / 工具集变化 / score < 0.70 / 出现 error 字段 */
    REGRESSION
}
