package io.github.agentassert4j.result;

/**
 * 判定结果枚举 — 二值语义。
 *
 * <p>PASS = 与基线无差异（ignorableFields 归一化规则下指纹相等）；
 * CHANGED = 与基线有差异（任何维度的任何可行动差异）。</p>
 *
 * <p>框架只陈述「与上一基线版本有无差异」，不判断变化的方向好坏——
 * 基线调工具 A、当前调工具 B，程序无从得知 B 是否更符合需求，
 * 方向判断是裁决人 approve/reject 的职责。</p>
 *
 * @author axy-yxa
 * @since 2026-08-26
 */
public enum Verdict {
    /**
     * 与基线无差异
     */
    PASS,
    /**
     * 与基线有差异（具体差异见对比结果的逐维清单）
     */
    CHANGED
}
