package io.github.agentassert4j.result;

/**
 * Diff 分类枚举 — 仅作为报告注释，不用于决定测试范围。
 */
public enum DiffClassification {
    FORMAT_CHANGE,
    CONSTRAINT_CHANGE,
    ROLE_CHANGE,
    EXAMPLE_CHANGE,
    INSTRUCTION_CHANGE,
    MINOR_CHANGE
}
